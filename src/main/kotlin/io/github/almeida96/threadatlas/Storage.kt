package io.github.almeida96.threadatlas

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

internal val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
internal fun readJson(p: Path, max: Int): JsonNode = Files.newInputStream(p).use {
    val bytes = it.readNBytes(max + 1)
    require(bytes.size <= max) { "JSON exceeds $max bytes" }
    requireNotNull(json.readTree(bytes)) { "Empty JSON" }
}
internal fun writeJson(p: Path, obj: Any) {
    Files.writeString(p, json.writerWithDefaultPrettyPrinter().writeValueAsString(obj) + "\n", StandardOpenOption.CREATE_NEW)
}
internal fun clean(p: Path) {
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(p).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { f ->
        try { Files.delete(f) } catch (e: AccessDeniedException) {
            if (Files.isSymbolicLink(f) || !f.toFile().setWritable(true)) throw e
            Files.delete(f)
        }
    } }
}
internal fun <T> outputDirectory(path: Path, body: (Path) -> T): T {
    val out = path.toAbsolutePath().normalize()
    require(!Files.exists(out, LinkOption.NOFOLLOW_LINKS)) { "Output already exists: $out" }
    Files.createDirectories(out.parent)
    val staging = Files.createTempDirectory(out.parent, ".threadatlas-")
    try {
        val result = body(staging)
        Files.move(staging, out)
        return result
    } finally { clean(staging) }
}
