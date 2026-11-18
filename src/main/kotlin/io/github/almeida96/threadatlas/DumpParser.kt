package io.github.almeida96.threadatlas

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.security.MessageDigest
import java.util.HexFormat

internal object DumpParser {
    private val head = Regex("""^"(.*)"\s+#([0-9]+)\b.*\btid=0x[0-9a-fA-F]+\b.*""")
    private val state = Regex("^\\s*java\\.lang\\.Thread\\.State: (NEW|RUNNABLE|BLOCKED|WAITING|TIMED_WAITING|TERMINATED)(?:\\s.*)?$")
    private val monitor = Regex("^\\s*- (locked|waiting on|waiting to lock|waiting to re-lock in wait\\(\\)) <(0x[0-9a-fA-F]+)>.*$")
    private val park = Regex("^\\s*-\\s+parking to wait for\\s+<(0x[0-9a-fA-F]+)>.*$")
    private val ownable = Regex("^\\s*- <(0x[0-9a-fA-F]+)>.*$")
    private fun lock(id: String) = "0x" + id.drop(2).lowercase().trimStart('0').ifEmpty { "0" }

    fun file(path: Path): Dump {
        require(Files.isRegularFile(path)) { "Expected a thread dump file: $path" }
        val data = Files.newInputStream(path).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(data.size <= 16 * 1024 * 1024) { "Dump exceeds 16 MiB" }
        return parse(path.fileName.toString(), data)
    }
    fun parse(name: String, bytes: ByteArray): Dump {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val lines = text.lines()
        require(lines.size <= 250000 && lines.all { it.length <= 16384 }) { "Thread dump text limits exceeded" }
        val start = lines.indexOfFirst { it.startsWith("Full thread dump ") }
        val end = lines.indexOfFirst { it.startsWith("JNI global refs:") }
        require(start >= 0 && end > start && lines.count { it.startsWith("Full thread dump ") } == 1) {
            "Expected one complete HotSpot Thread.print -l dump, ending with JNI global refs"
        }
        val threads = mutableListOf<DumpThread>()
        var cursor = start + 1
        var withOwnable = 0
        while (cursor < end) {
            val h = head.matchEntire(lines[cursor])
            if (h == null) {
                require(!Regex("""^".*"\s+#""").containsMatchIn(lines[cursor])) { "Unrecognized Java thread header at line ${cursor + 1}" }
                cursor++; continue
            }
            val headerLine = cursor + 1
            val threadName = h.groupValues[1]
            val id = h.groupValues[2]
            require(id.toLongOrNull()?.let { it > 0 } == true && threadName.length <= 1024) { "Invalid thread identity" }
            var status: String? = null
            var wait: WaitLock? = null
            val released = mutableSetOf<String>()
            val held = mutableListOf<HeldLock>()
            val frames = mutableListOf<Frame>()
            var sync = false
            cursor++
            while (cursor < end && !lines[cursor].startsWith('"')) {
                val s = lines[cursor]
                val n = cursor + 1
                state.matchEntire(s)?.let {
                    require(status == null) { "Multiple thread states at line $n" }; status = it.groupValues[1]
                }
                if (s.trimStart().startsWith("at ")) {
                    require(frames.size < 512) { "Thread $id exceeds 512 frames" }
                    frames += Frame(s.trim().removePrefix("at "), n)
                }
                if (s.trim() == "Locked ownable synchronizers:") { sync = true; withOwnable++ }
                monitor.matchEntire(s)?.let {
                    val objectId = lock(it.groupValues[2])
                    when (it.groupValues[1]) {
                        "locked" -> held += HeldLock(objectId, "MONITOR", n)
                        "waiting on" -> released += objectId
                        else -> {
                            require(wait == null) { "Multiple active waits for thread $id" }
                            wait = WaitLock(objectId, "MONITOR", n)
                            if (it.groupValues[1].startsWith("waiting to re-lock")) released += objectId
                        }
                    }
                }
                park.matchEntire(s)?.let {
                    require(wait == null) { "Multiple active waits for thread $id" }
                    wait = WaitLock(lock(it.groupValues[1]), "SYNCHRONIZER", n)
                }
                if (sync) ownable.matchEntire(s)?.let { held += HeldLock(lock(it.groupValues[1]), "SYNCHRONIZER", n) }
                cursor++
            }
            require(status != null) { "Missing Java state for thread $id at line $headerLine" }
            require(threads.size < 20000) { "Dump exceeds 20,000 Java threads" }
            threads += DumpThread(id, threadName, status, headerLine, frames,
                held.filterNot { it.kind == "MONITOR" && it.id in released }.distinctBy { it.id to it.kind }, wait)
        }
        require(threads.isNotEmpty() && threads.map { it.id }.toSet().size == threads.size) { "Missing or duplicate Java thread IDs" }
        val notes = mutableListOf<String>()
        if (withOwnable == 0) notes += "Ownable synchronizers are absent. Capture with Thread.print -l to inspect ReentrantLock ownership."
        notes += "Platform threads only. RUNNABLE does not establish CPU use; parked threads without a known owner are not lock edges."
        val timestamp = lines.take(start).lastOrNull { Regex("^\\d{4}-\\d{2}-\\d{2} .*").matches(it) }
        return Dump(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.size, timestamp, threads, notes)
    }
}
