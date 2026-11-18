package io.github.almeida96.threadatlas

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.*

class FilesystemTest {
    @Test fun transientHandleContentionIsRetried() {
        var calls = 0
        val result = retryFilesystem {
            if (++calls < 3) throw AccessDeniedException("fixture")
            "complete"
        }
        assertEquals(3,calls); assertEquals("complete",result)
    }
    @Test fun existingDestinationIsNeverRetried() {
        var calls = 0
        assertThrows(FileAlreadyExistsException::class.java) {
            retryFilesystem { calls++; throw FileAlreadyExistsException("fixture") }
        }
        assertEquals(1,calls)
    }
    @Test fun failedGenerationLeavesNoPartialOutput() {
        val dir = Files.createTempDirectory("threadatlas-failed-")
        try {
            val out = dir.resolve("out")
            assertThrows(IllegalArgumentException::class.java) {
                outputDirectory(out) { stage -> Files.writeString(stage.resolve("partial"),"unfinished"); throw IllegalArgumentException("invalid source") }
            }
            assertFalse(Files.exists(out))
            Files.list(dir).use { assertEquals(0,it.count()) }
        } finally { clean(dir) }
    }
}
