package io.github.almeida96.threadatlas

import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.HexFormat

internal object Analyzer {
    fun analyze(paths: List<Path>): Report {
        require(paths.size in 1..16) { "Provide 1–16 captures in chronological order" }
        var total = 0L
        val snapshots = paths.mapIndexed { i, p ->
            val dump = DumpParser.file(p)
            total += dump.bytes
            require(total <= 64L * 1024 * 1024) { "Total input exceeds 64 MiB" }
            snapshot(i + 1, dump)
        }
        return report(snapshots)
    }

    fun snapshot(index: Int, dump: Dump): Snapshot {
        val owners = mutableMapOf<Pair<String, String>, MutableList<Pair<DumpThread, HeldLock>>>()
        dump.threads.forEach { t -> t.held.forEach { lock -> owners.getOrPut(lock.id to lock.kind) { mutableListOf() }.add(t to lock) } }
        val gaps = mutableListOf<Gap>()
        val edges = dump.threads.mapNotNull { t ->
            val w = t.waiting ?: return@mapNotNull null
            val candidates = owners[w.id to w.kind].orEmpty()
            if (w.kind == "MONITOR" && t.state != "BLOCKED" ||
                w.kind == "SYNCHRONIZER" && t.state !in setOf("WAITING", "TIMED_WAITING")) {
                gaps += Gap(t.id, w.id, "WAIT_STATE_MISMATCH"); return@mapNotNull null
            }
            if (candidates.isEmpty()) {
                if (w.kind == "MONITOR") gaps += Gap(t.id, w.id, "OWNER_NOT_OBSERVED")
                return@mapNotNull null
            }
            if (candidates.size > 1 || candidates.single().first.id == t.id) {
                gaps += Gap(t.id, w.id, if (candidates.size > 1) "AMBIGUOUS_OWNERSHIP" else "SELF_OWNERSHIP")
                return@mapNotNull null
            }
            val (owner, held) = candidates.single()
            Edge(t.id, owner.id, w.id, w.kind, w.line, held.line)
        }
        val byWaiter = edges.associateBy { it.waiter }
        val byId = dump.threads.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val cycles = mutableListOf<Cycle>()
        // Each thread has at most one outgoing wait edge, so path walks find every cycle without recursion.
        for (start in byWaiter.keys) {
            if (start in visited) continue
            val positions = linkedMapOf<String, Int>()
            var current: String? = start
            while (current != null && current !in visited && current !in positions) {
                positions[current] = positions.size
                current = byWaiter[current]?.owner
            }
            if (current != null && current in positions) {
                val ids = positions.keys.drop(positions.getValue(current)).toMutableList()
                val first = ids.indices.minBy { ids[it].toLong() }
                val rotated = ids.drop(first) + ids.take(first)
                val kind = if (rotated.any { byId.getValue(it).state == "TIMED_WAITING" }) "TIMED_CYCLE" else "DEADLOCK"
                cycles += Cycle("cycle-${cycles.size + 1}", kind, rotated, rotated.map { byWaiter.getValue(it) })
            }
            visited += positions.keys
        }
        // Peel acyclic leaves, accumulating the number of waiting descendants at each owner.
        val incoming = dump.threads.associate { it.id to 0 }.toMutableMap()
        val counts = dump.threads.associate { it.id to 0 }.toMutableMap()
        edges.forEach { incoming[it.owner] = incoming.getValue(it.owner) + 1 }
        val direct = incoming.toMap()
        val leaves = ArrayDeque(incoming.filterValues { it == 0 }.keys)
        while (leaves.isNotEmpty()) {
            val thread = leaves.removeFirst()
            val owner = byWaiter[thread]?.owner ?: continue
            counts[owner] = counts.getValue(owner) + counts.getValue(thread) + 1
            incoming[owner] = incoming.getValue(owner) - 1
            if (incoming[owner] == 0) leaves.add(owner)
        }
        val inCycle = cycles.flatMap { it.threads }.toSet()
        cycles.forEach { cycle ->
            val affected = cycle.threads.sumOf { counts.getValue(it) } + cycle.threads.size - 1
            cycle.threads.forEach { counts[it] = affected }
        }
        val blockers = direct.filterValues { it > 0 }.keys.map { Blocker(it, direct.getValue(it), counts.getValue(it), it in inCycle) }
            .sortedWith(compareByDescending<Blocker> { it.downstreamWaiters }.thenBy { it.thread.toLong() })
        val verdict = when { cycles.any { it.kind == "DEADLOCK" } -> "DEADLOCK"; edges.isNotEmpty() -> "CONTENTION"; else -> "NO_CYCLE_OBSERVED" }
        return Snapshot(index, dump, verdict, edges, cycles, blockers, gaps)
    }

    fun report(snapshots: List<Snapshot>): Report {
        val groups = linkedMapOf<Pair<String, List<String>>, IntArray>()
        snapshots.forEachIndexed { i, snap -> snap.source.threads.forEach { t ->
            val key = t.state to t.frames.take(8).map { it.text }
            groups.getOrPut(key) { IntArray(snapshots.size) }[i]++
        } }
        val stacks = groups.map { (key, counts) ->
            val identity = json.writeValueAsBytes(listOf(key.first, key.second))
            StackGroup(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity)),
                key.first, key.second, counts.toList())
        }.sortedWith(compareByDescending<StackGroup> { it.counts.count { n -> n > 0 } }.thenByDescending { it.counts.sum() }.thenBy { it.id })
        val verdict = when {
            snapshots.any { it.verdict == "DEADLOCK" } -> "DEADLOCK"
            snapshots.any { it.verdict == "CONTENTION" } -> "CONTENTION"
            else -> "NO_CYCLE_OBSERVED"
        }
        return Report(1, verdict, snapshots, stacks)
    }
}
