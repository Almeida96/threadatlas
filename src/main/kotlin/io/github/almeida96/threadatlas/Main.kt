package io.github.almeida96.threadatlas

import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal fun writeReport(report: Report, out: Path) = outputDirectory(out) { stage ->
    writeJson(stage.resolve("report.json"), report)
    Files.writeString(stage.resolve("report.html"), render(report))
}
internal fun captureDemo(out: Path) = outputDirectory(out) { stage ->
    val javaBin = Path.of(System.getProperty("java.home"), "bin")
    val ext = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    val java = javaBin.resolve("java$ext")
    val jcmd = javaBin.resolve("jcmd$ext")
    require(Files.isRegularFile(jcmd)) { "demo needs a JDK 21 with jcmd, not only a JRE" }
    for (mode in listOf("deadlock", "convoy", "idle")) {
        val dir = Files.createDirectory(stage.resolve(mode))
        val proof = dir.resolve("mxbean.json")
        val log = dir.resolve("fixture.log")
        val proc = ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
            "io.github.almeida96.threadatlas.WarehouseJvm", mode, proof.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!Files.exists(proof)) {
                check(proc.isAlive && System.nanoTime() < deadline) { "Demo JVM failed to start: ${Files.readString(log).take(2000)}" }
                Thread.sleep(50)
            }
            val captures = (1..2).map { i ->
                val file = dir.resolve("capture-$i.txt")
                val errors = dir.resolve("jcmd-$i.log")
                val capture = ProcessBuilder(jcmd.toString(), proc.pid().toString(), "Thread.print", "-l")
                    .redirectOutput(file.toFile()).redirectError(errors.toFile()).start()
                try {
                    check(capture.waitFor(20, TimeUnit.SECONDS) && capture.exitValue() == 0) { "jcmd capture failed" }
                } finally { if (capture.isAlive) { capture.destroyForcibly(); capture.waitFor(5, TimeUnit.SECONDS) } }
                retryFilesystem { Files.delete(errors) }
                file
            }
            writeReport(Analyzer.analyze(captures), dir.resolve("analysis"))
        } finally {
            proc.destroyForcibly()
            check(proc.waitFor(10, TimeUnit.SECONDS)) { "Demo JVM did not terminate" }
            proc.outputStream.close(); proc.inputStream.close(); proc.errorStream.close()
        }
    }
}
private const val HELP = """ThreadAtlas 0.1.0
  threadatlas analyze --dump FILE [--dump FILE ...] --out NEW_DIR [--fail-on deadlock|contention]
  threadatlas demo --out NEW_DIR
  threadatlas mcp --report REPORT.json
Read complete HotSpot platform-thread dumps from jcmd Thread.print -l.
Exit codes: 0 completed, 1 selected finding observed, 2 input or execution error."""
private class Args(raw: List<String>, allowed: Set<String>) {
    val items = mutableMapOf<String, MutableList<String>>()
    init {
        var i = 0
        while (i < raw.size) {
            val key = raw[i++]
            require(key in allowed && i < raw.size && !raw[i].startsWith("--")) { "Unknown option or missing value: $key" }
            val values = items.getOrPut(key) { mutableListOf() }
            require(values.isEmpty() || key == "--dump") { "Repeated option: $key" }
            values += raw[i++]
        }
    }
    fun path(k: String) = Path.of(requireNotNull(items[k]?.singleOrNull()) { "Missing $k" }).toAbsolutePath().normalize()
}
internal fun execute(args: Array<String>): Int {
    when (args.firstOrNull()) {
        null, "--help", "-h" -> println(HELP)
        "--version" -> println("ThreadAtlas 0.1.0")
        "analyze" -> {
            val a = Args(args.drop(1), setOf("--dump", "--out", "--fail-on"))
            val gate = a.items["--fail-on"]?.singleOrNull() ?: "deadlock"
            require(gate in setOf("deadlock", "contention")) { "Unknown failure policy" }
            val r = Analyzer.analyze(a.items["--dump"].orEmpty().map { Path.of(it) })
            writeReport(r, a.path("--out"))
            println("${r.verdict}: ${r.snapshots.size} capture(s); ${a.path("--out")}/report.html")
            return if (r.verdict == "DEADLOCK" || gate == "contention" && r.verdict == "CONTENTION") 1 else 0
        }
        "demo" -> {
            val out = Args(args.drop(1), setOf("--out")).path("--out")
            captureDemo(out); println("Captured deadlock, convoy and idle JVMs. Open $out/deadlock/analysis/report.html")
        }
        "mcp" -> Mcp.serve(readJson(Args(args.drop(1), setOf("--report")).path("--report"), 96 * 1024 * 1024), System.`in`, System.out)
        else -> error("Unknown command; use --help")
    }
    return 0
}
fun main(args: Array<String>): Unit = exitProcess(try { execute(args) } catch (e: Exception) {
    System.err.println("threadatlas: ${e.message ?: e.javaClass.simpleName}"); 2
})
