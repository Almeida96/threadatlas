package io.github.almeida96.threadatlas

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files

class AnalysisTest {
    private fun thread(id: Int, state: String = "BLOCKED", body: String = "", name: String = "worker-$id") = """
"$name" #$id prio=5 tid=0x00$id nid=0x$id waiting
   java.lang.Thread.State: $state
        at warehouse.Stock.update(Stock.java:42)
$body
   Locked ownable synchronizers:
        - None
"""
    private fun dump(vararg threads: String) = "2026-11-18 10:00:00\nFull thread dump OpenJDK 64-Bit Server VM (21.0.13 mixed mode):\n" + threads.joinToString("\n") + "JNI global refs: 12, weak refs: 0\n"
    private fun parse(text: String) = DumpParser.parse("capture.txt", text.toByteArray())
    private fun snap(vararg ts: String) = Analyzer.snapshot(1, parse(dump(*ts)))
    private fun lock(id: String) = "        - locked <$id> (a java.lang.Object)"
    private fun wait(id: String) = "        - waiting to lock <$id> (a java.lang.Object)"
    private fun cycle(a: Int = 1, b: Int = 2) = arrayOf(thread(a, body = wait("0x22") + "\n" + lock("0x11")),
        thread(b, body = wait("0x11") + "\n" + lock("0x22")))

    @Test fun monitorCycleHasExactSourceEvidence() {
        val s = snap(*cycle())
        assertEquals("DEADLOCK", s.verdict)
        assertEquals(listOf("1", "2"), s.cycles.single().threads)
        assertTrue(s.edges.all { it.waitLine > 0 && it.ownerLine > 0 })
        assertTrue(s.blockers.all { it.downstreamWaiters == 1 })
    }
    @Test fun nativeDeadlockFooterIsNotUsedAsEvidence() {
        val text = dump(thread(1, "RUNNABLE")) + "Found one Java-level deadlock:\n\"fake\" #2 tid=0xa\n"
        assertTrue(Analyzer.snapshot(1, parse(text)).cycles.isEmpty())
    }
    @Test fun synchronizerOwnershipCreatesEdges() {
        fun t(id: Int, wait: String, hold: String) = thread(id, "WAITING",
            "        - parking to wait for <$wait> (a java.util.concurrent.locks.ReentrantLock\$NonfairSync)\n" +
            "   Locked ownable synchronizers:\n        - <$hold> (a java.util.concurrent.locks.ReentrantLock\$NonfairSync)")
        val s = snap(t(1, "0xb", "0xa"), t(2, "0xa", "0xb"))
        assertEquals("DEADLOCK", s.verdict); assertTrue(s.edges.all { it.kind == "SYNCHRONIZER" })
    }
    @Test fun timedAcquisitionCycleDoesNotClaimPermanentDeadlock() {
        val one = thread(1, "TIMED_WAITING", " - parking to wait for <0xb> (a Lock)\n Locked ownable synchronizers:\n - <0xa> (a Lock)")
        val two = thread(2, "WAITING", " - parking to wait for <0xa> (a Lock)\n Locked ownable synchronizers:\n - <0xb> (a Lock)")
        val s = snap(one, two)
        assertEquals("CONTENTION",s.verdict); assertEquals("TIMED_CYCLE",s.cycles.single().kind)
    }
    @Test fun ordinaryParkIsNotAnUnknownLockOwner() {
        val s = snap(thread(1, "WAITING", " - parking to wait for <0xa> (a ConditionObject)"))
        assertTrue(s.edges.isEmpty()); assertTrue(s.unresolved.isEmpty())
    }
    @Test fun objectWaitReleasesOnlyTheWaitedMonitor() {
        val owner = thread(1, "WAITING", " - waiting on <0xa> (a Object)\n" + lock("0xa") + "\n" + lock("0xb"))
        val s = snap(owner, thread(2,body=wait("0xa")),thread(3,body=wait("0xb")))
        assertEquals(listOf("3"),s.edges.map { it.waiter })
        assertEquals("OWNER_NOT_OBSERVED",s.unresolved.single().reason)
    }
    @Test fun reenteringObjectWaitDoesNotOwnTheMonitorYet() {
        val s = snap(thread(1,body=" - waiting to re-lock in wait() <0xa> (a Object)\n"+lock("0xa")),thread(2,"RUNNABLE",lock("0xa")))
        assertEquals("2",s.edges.single().owner)
    }
    @Test fun ambiguousOwnerDoesNotCreateAnArbitraryCycle() {
        val s = snap(thread(1,"RUNNABLE",lock("0xa")),thread(2,"RUNNABLE",lock("0xa")),thread(3,body=wait("0xa")))
        assertTrue(s.edges.isEmpty()); assertEquals("AMBIGUOUS_OWNERSHIP",s.unresolved.single().reason)
    }
    @Test fun reentrantAnnotationsAreDeduplicated() {
        val s = snap(thread(1,"RUNNABLE",lock("0xa")+"\n"+lock("0xa")),thread(2,body=wait("0xa")))
        assertEquals(1,s.edges.size)
    }
    @Test fun stateMismatchIsAnEvidenceGap() {
        val s = snap(thread(1,"RUNNABLE",wait("0xa")),thread(2,"RUNNABLE",lock("0xa")))
        assertTrue(s.edges.isEmpty()); assertEquals("WAIT_STATE_MISMATCH",s.unresolved.single().reason)
    }
    @Test fun leadingZeroAndUppercaseAddressesMatch() {
        val s = snap(thread(1,body=wait("0x00Ab")),thread(2,"RUNNABLE",lock("0xab")))
        assertEquals("0xab",s.edges.single().lock)
    }
    @Test fun downstreamWaitersIncludeTransitiveChains() {
        val s = snap(thread(1,"RUNNABLE",lock("0xa")),thread(2,body=wait("0xa")+"\n"+lock("0xb")),thread(3,body=wait("0xb")))
        assertEquals(2,s.blockers.first().downstreamWaiters)
        assertEquals("1",s.blockers.first().thread)
        assertEquals("CONTENTION",s.verdict)
    }
    @Test fun feedersToACycleAreCountedOncePerMember() {
        val s = snap(*cycle(),thread(3,body=wait("0x11")+"\n"+lock("0x33")),thread(4,body=wait("0x33")))
        assertTrue(s.blockers.filter { it.inCycle }.all { it.downstreamWaiters == 3 })
    }
    @Test fun independentCyclesRemainSeparate() {
        val second = cycle(3,4).map { it.replace("0x11","0x33").replace("0x22","0x44") }.toTypedArray()
        assertEquals(2,snap(*cycle(),*second).cycles.size)
    }
    @Test fun ordinaryNativeAndQuotedNames() {
        val d = parse(dump(thread(1,"RUNNABLE",name="name \"quoted\""), "\"VM Thread\" tid=0x1 nid=1 runnable\n"))
        assertEquals(1,d.threads.size); assertEquals("name \"quoted\"",d.threads.single().name)
    }
    @Test fun missingFooterAndMultipleDumpsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parse(dump(thread(1)).substringBefore("JNI global")) }
        assertThrows(IllegalArgumentException::class.java) { parse(dump(thread(1))+dump(thread(2))) }
    }
    @Test fun missingStateAndRepeatedIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parse(dump(thread(1).replace("java.lang.Thread.State: BLOCKED",""))) }
        assertThrows(IllegalArgumentException::class.java) { parse(dump(thread(1),thread(1))) }
    }
    @Test fun invalidUtf8Rejected() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { DumpParser.parse("bad",byteArrayOf(0xc3.toByte(),0x28)) }
    }
    @Test fun windowsBomAndLineEndingsAreAccepted() {
        assertEquals("1",parse("\uFEFF"+dump(thread(1)).replace("\n","\r\n")).threads.single().id)
    }
    @Test fun absentOwnableSectionAddsCoverageNote() {
        val s = parse(dump(thread(1)).replace("   Locked ownable synchronizers:","").replace("        - None",""))
        assertTrue(s.notes.any { it.contains("Ownable synchronizers are absent") })
    }
    @Test fun snapshotsGroupStacksWithoutMatchingThreadIdentity() {
        val a = snap(thread(1,"RUNNABLE"))
        val b = Analyzer.snapshot(2,parse(dump(thread(99,"RUNNABLE"),thread(100,"RUNNABLE"))))
        val r = Analyzer.report(listOf(a,b))
        assertEquals(listOf(1,2),r.stacks.single().counts)
    }
    @Test fun longChainUsesIterativeGraphWalk() {
        val threads = (1..10000).map { i ->
            DumpThread(i.toString(),"t$i",if(i==10000) "RUNNABLE" else "BLOCKED",i,emptyList(),
                listOf(HeldLock("lock$i","MONITOR",i)),if(i==10000) null else WaitLock("lock${i+1}","MONITOR",i))
        }
        val s = Analyzer.snapshot(1,Dump("large","unused",0,null,threads,emptyList()))
        assertEquals(9999,s.blockers.first().downstreamWaiters)
    }
    @Test fun htmlEscapesNamesAndFrames() {
        val s = snap(thread(1,"RUNNABLE",name="<script>alert(1)</script>"))
        val html = render(Analyzer.report(listOf(s)))
        assertFalse(html.contains("<script>")); assertTrue(html.contains("&lt;script&gt;"))
    }
    @Test fun existingOutputIsPreserved() {
        val p = Files.createTempDirectory("threadatlas-output-")
        try {
            val marker = Files.writeString(p.resolve("marker.txt"),"keep")
            assertThrows(IllegalArgumentException::class.java) { writeReport(Analyzer.report(listOf(snap(thread(1)))),p) }
            assertEquals("keep",Files.readString(marker))
        } finally { clean(p) }
    }
    @Test fun failurePoliciesPreserveWrittenEvidence() {
        val root = Files.createTempDirectory("threadatlas-policy-")
        try {
            val source = Files.writeString(root.resolve("capture.txt"),dump(thread(1,"RUNNABLE",lock("0xa")),thread(2,body=wait("0xa"))))
            fun code(dir: String, policy: String) = execute(arrayOf("analyze","--dump",source.toString(),"--out",root.resolve(dir).toString(),"--fail-on",policy))
            assertEquals(0,code("normal","deadlock"))
            assertEquals(1,code("strict","contention"))
            assertEquals("CONTENTION",readJson(root.resolve("strict/report.json"),1024*1024)["verdict"].asText())
            Files.writeString(source,dump(*cycle()))
            assertEquals(1,code("deadlock","deadlock"))
        } finally { clean(root) }
    }
}
