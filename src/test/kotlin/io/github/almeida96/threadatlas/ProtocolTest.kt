package io.github.almeida96.threadatlas

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtocolTest {
    private val snapshot = Analyzer.snapshot(1,Dump("fixture","abc",0,null,
        listOf(DumpThread("1","worker","RUNNABLE",1,listOf(Frame("example.Job.run(Job.java:1)",2)),emptyList(),null)),emptyList()))
    private val report = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(Analyzer.report(listOf(snapshot)))
    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
    private val ready = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    private fun serve(vararg messages: String): List<com.fasterxml.jackson.databind.JsonNode> {
        val out = ByteArrayOutputStream()
        Mcp.serve(report,ByteArrayInputStream(messages.joinToString("\n",postfix="\n").toByteArray()),out)
        return out.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { json.readTree(it) }.toList()
    }
    @Test fun initializationRequired() {
        val r = serve("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(-32600,r.single()["error"]["code"].asInt())
    }
    @Test fun corruptAndOversizedFramesDoNotPoisonFollowingRequests() {
        val r = serve("{oops", "x".repeat(1024*1024+1),initialize,ready,"""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals(-32700,r[0]["error"]["code"].asInt())
        assertEquals(-32700,r[1]["error"]["code"].asInt())
        assertEquals(5,r.last()["result"]["tools"].size())
    }
    @Test fun textAndStructuredEvidenceMatch() {
        val r = serve(initialize,ready,"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"thread_detail","arguments":{"snapshot":1,"thread":"1","limit":1}}}""").last()["result"]
        assertFalse(r["isError"].asBoolean())
        assertEquals(r["structuredContent"],json.readTree(r["content"][0]["text"].asText()))
    }
    @Test fun reportCannotBeReboundThroughArguments() {
        val r = serve(initialize,ready,"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"incident_summary","arguments":{"path":"/private"}}}""").last()["result"]
        assertTrue(r["isError"].asBoolean())
    }
    @Test fun paginationRejectsFractionsAndUnknownCapture() {
        for (arguments in listOf("""{"offset":0.5}""", """{"limit":101}""")) {
            val r = serve(initialize,ready,"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_stacks","arguments":$arguments}}""").last()["result"]
            assertTrue(r["isError"].asBoolean())
        }
        val r = serve(initialize,ready,"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"wait_chain","arguments":{"snapshot":2,"thread":"1"}}}""").last()["result"]
        assertTrue(r["isError"].asBoolean())
    }
}
