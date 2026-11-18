package io.github.almeida96.threadatlas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.function.Predicate;

/** A stdio MCP session bound to one report loaded by the operator. */
public final class Mcp {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String LATEST = "2025-11-25";
    private static final int MAX_FRAME = 1024 * 1024;
    private static final int PAGE_LIMIT = 100;
    private static final List<String> TOOLS = List.of("incident_summary", "list_threads", "thread_detail", "wait_chain", "list_stacks");
    private final JsonNode report;
    private final Map<Integer, JsonNode> snapshots = new HashMap<>();
    private final Map<Integer, Map<String, JsonNode>> threads = new HashMap<>();
    private final Map<Integer, Map<String, JsonNode>> edges = new HashMap<>();
    private final Map<Integer, Map<String, JsonNode>> blockers = new HashMap<>();
    private boolean negotiated;
    private boolean ready;

    private Mcp(JsonNode report) {
        if (report.path("formatVersion").asInt() != 1 || !Set.of("DEADLOCK","CONTENTION","NO_CYCLE_OBSERVED").contains(report.path("verdict").asText())
                || !report.path("snapshots").isArray() || report.path("snapshots").isEmpty() || report.path("snapshots").size() > 16
                || !report.path("stacks").isArray()) throw invalid("Expected a ThreadAtlas version 1 report");
        for (JsonNode s : report.get("snapshots")) {
            int id = integer(s, "index", -1, 1, 16);
            if (id < 1 || snapshots.putIfAbsent(id, s) != null || !s.at("/source/threads").isArray()
                    || !s.path("edges").isArray() || !s.path("blockers").isArray() || !s.path("cycles").isArray())
                throw invalid("Invalid snapshot");
            Map<String,JsonNode> ts = new java.util.LinkedHashMap<>(), es = new HashMap<>(), bs = new HashMap<>();
            for (JsonNode t : s.at("/source/threads")) {
                if (!t.path("frames").isArray() || ts.putIfAbsent(string(t,"id"), t) != null) throw invalid("Invalid thread");
            }
            for (JsonNode e : s.get("edges")) {
                if (!ts.containsKey(string(e,"waiter")) || !ts.containsKey(string(e,"owner")) || es.putIfAbsent(string(e,"waiter"), e) != null)
                    throw invalid("Invalid wait edge");
            }
            for (JsonNode b : s.get("blockers")) bs.put(string(b,"thread"), b);
            threads.put(id,ts); edges.put(id,es); blockers.put(id,bs);
        }
        this.report = report;
    }

    public static void serve(JsonNode report, InputStream input, OutputStream output) throws IOException {
        var session = new Mcp(report);
        var stream = new BufferedInputStream(input);
        byte[] frame = new byte[MAX_FRAME];
        int used = 0;
        boolean oversized = false;
        int next;
        while ((next = stream.read()) != -1) {
            if (next == '\n') {
                session.respond(frame, used, oversized, output);
                used = 0;
                oversized = false;
            } else if (used < MAX_FRAME) {
                frame[used++] = (byte) next;
            } else {
                oversized = true;
            }
        }
        if (used != 0 || oversized) session.respond(frame, used, oversized, output);
    }

    private void respond(byte[] frame, int used, boolean oversized, OutputStream output) throws IOException {
        ObjectNode reply;
        if (oversized) {
            reply = error(null, -32700, "Message exceeds 1 MiB");
        } else {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frame, 0, used)).toString();
                JsonNode request = JSON.readTree(text);
                reply = request == null || request.isMissingNode()
                        ? error(null, -32700, "Empty JSON message") : handle(request);
            } catch (JsonProcessingException | CharacterCodingException invalid) {
                reply = error(null, -32700, "Invalid UTF-8 JSON message");
            }
        }
        if (reply == null) return;
        byte[] bytes = JSON.writeValueAsBytes(reply);
        if (bytes.length > MAX_FRAME) {
            bytes = JSON.writeValueAsBytes(error(reply.get("id"), -32603, "Result exceeds 1 MiB. Request a smaller page."));
        }
        output.write(bytes);
        output.write('\n');
        output.flush();
    }

    private ObjectNode handle(JsonNode request) {
        JsonNode id = request.get("id");
        if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").textValue())
                || !request.path("method").isTextual()
                || (id != null && !(id.isIntegralNumber() || id.isTextual()))
                || (id != null && id.toString().length() > 1024)) {
            return error(null, -32600, "Invalid JSON-RPC request");
        }
        String method = request.path("method").textValue();
        if (id == null) {
            if ("notifications/initialized".equals(method) && negotiated) ready = true;
            return null;
        }
        JsonNode params = request.has("params") ? request.get("params") : JSON.createObjectNode();
        if (!params.isObject()) return error(id, -32602, "params must be an object");
        try {
            if ("ping".equals(method)) return success(id, JSON.createObjectNode());
            if ("initialize".equals(method)) {
                if (negotiated) return error(id, -32600, "Session already initialized");
                String version = string(params, "protocolVersion");
                if (!params.path("capabilities").isObject()) throw invalid("Missing client capabilities");
                string(params.path("clientInfo"), "name");
                string(params.path("clientInfo"), "version");
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", "2025-06-18".equals(version) ? version : LATEST);
                result.putObject("serverInfo").put("name", "threadatlas").put("version", "0.1.0");
                result.putObject("capabilities").putObject("tools").put("listChanged", false);
                negotiated = true;
                return success(id, result);
            }
            if (!ready) return error(id, -32600, "Complete initialization first");
            if ("tools/list".equals(method)) {
                fields(params, Set.of("_meta"));
                return success(id, inventory());
            }
            if (!"tools/call".equals(method)) return error(id, -32601, "Method not found");
            fields(params, Set.of("name", "arguments", "_meta"));
            JsonNode args = params.has("arguments") ? params.get("arguments") : JSON.createObjectNode();
            if (!args.isObject()) throw invalid("arguments must be an object");
            String name = string(params, "name");
            if (!TOOLS.contains(name)) return error(id, -32602, "Unknown tool");
            ObjectNode data;
            try {
                data = call(name, args);
            } catch (IllegalArgumentException e) {
                ObjectNode failure = JSON.createObjectNode().put("isError", true);
                failure.putArray("content").addObject().put("type", "text").put("text", e.getMessage());
                return success(id, failure);
            }
            ObjectNode result = JSON.createObjectNode().put("isError", false);
            result.set("structuredContent", data);
            result.putArray("content").addObject().put("type", "text").put("text", data.toString());
            return success(id, result);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }
    }

    private ObjectNode call(String name, JsonNode args) {
        ObjectNode result = JSON.createObjectNode();
        if ("incident_summary".equals(name)) {
            fields(args, Set.of());
            copyFields(report, result, "verdict", "formatVersion");
            ArrayNode rows = result.putArray("snapshots");
            for (JsonNode s : report.get("snapshots")) {
                ObjectNode row = rows.addObject();
                copyFields(s,row,"index","verdict");
                copyFields(s.get("source"),row,"name","sha256","timestamp","notes");
                row.put("threads",s.at("/source/threads").size()).put("ownerLinkedWaits",s.get("edges").size())
                   .put("cycles",s.get("cycles").size()).put("unresolved",s.get("unresolved").size());
            }
            return result;
        }
        int offset = integer(args,"offset",0,0,Integer.MAX_VALUE), limit = integer(args,"limit",25,1,PAGE_LIMIT);
        if ("list_stacks".equals(name)) {
            fields(args,Set.of("offset","limit"));
            return page(result,"stacks",report.get("stacks"), n -> true,offset,limit);
        }
        int snapshot = integer(args,"snapshot",-1,1,16);
        if (!snapshots.containsKey(snapshot)) throw invalid("Unknown snapshot");
        result.put("snapshot",snapshot);
        if ("list_threads".equals(name)) {
            fields(args,Set.of("snapshot","state","nameContains","offset","limit"));
            String state = option(args,"state",Set.of("NEW","RUNNABLE","BLOCKED","WAITING","TIMED_WAITING","TERMINATED"),null);
            String contains = args.has("nameContains") ? string(args,"nameContains") : "";
            ArrayNode rows = JSON.createArrayNode();
            for (JsonNode t : threads.get(snapshot).values()) {
                if (!matches(t,"state",state) || !t.path("name").asText().contains(contains)) continue;
                ObjectNode row = rows.addObject();
                copyFields(t,row,"id","name","state","headerLine");
                JsonNode e = edges.get(snapshot).get(t.get("id").asText());
                if (e == null) row.putNull("owner"); else row.put("owner",e.get("owner").asText());
                JsonNode b = blockers.get(snapshot).get(t.get("id").asText());
                row.put("downstreamWaiters", b == null ? 0 : b.path("downstreamWaiters").asInt())
                   .put("inCycle",b != null && b.path("inCycle").asBoolean());
            }
            return page(result,"threads",rows,n -> true,offset,limit);
        }
        String id = string(args,"thread");
        JsonNode t = threads.get(snapshot).get(id);
        if (t == null) throw invalid("Unknown thread in this capture");
        fields(args,Set.of("snapshot","thread","offset","limit"));
        if ("thread_detail".equals(name)) {
            copyFields(t,result,"id","name","state","headerLine","waiting");
            result.put("heldLockCount",t.path("held").size());
            return page(result,"frames",t.get("frames"),n -> true,offset,limit);
        }
        ArrayNode chain = JSON.createArrayNode();
        Set<String> visited = new java.util.HashSet<>();
        String current = id;
        while (visited.add(current)) {
            JsonNode e = edges.get(snapshot).get(current);
            if (e == null) break;
            chain.add(e); current = e.get("owner").asText();
        }
        result.put("thread",id).put("endThread",current).put("cycle",edges.get(snapshot).containsKey(current) && visited.contains(current));
        return page(result,"edges",chain,n -> true,offset,limit);
    }

    private static boolean matches(JsonNode row, String key, String expected) {
        return expected == null || expected.equals(row.path(key).textValue());
    }

    private static String option(JsonNode args, String key, Set<String> allowed, String fallback) {
        if (!args.has(key)) return fallback;
        String value = string(args, key);
        if (!allowed.contains(value)) throw invalid("Unknown value for " + key);
        return value;
    }

    private static ObjectNode page(ObjectNode result, String key, JsonNode rows, Predicate<JsonNode> filter,
                                   int offset, int limit) {
        ArrayNode items = result.putArray(key);
        int total = 0;
        for (JsonNode row : rows) {
            if (filter.test(row) && total++ >= offset && items.size() < limit) items.add(row);
        }
        return pagination(result, offset, items.size(), total);
    }

    private static ObjectNode pagination(ObjectNode result, int offset, int count, int total) {
        result.put("offset", offset).put("total", total);
        if (offset < total - count) result.put("nextOffset", offset + count);
        else result.putNull("nextOffset");
        return result;
    }

    private static ObjectNode inventory() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode list = result.putArray("tools");
        for (String name : TOOLS) {
            ObjectNode tool = list.addObject().put("name", name);
            tool.put("description", switch(name) {
                case "incident_summary" -> "Read per-capture verdicts, thread and cycle counts, provenance and coverage notes.";
                case "list_threads" -> "Find threads by snapshot, state and case-sensitive name fragment; includes owners and downstream waiter counts.";
                case "thread_detail" -> "Read a thread's state and paginated stack frames with source line numbers.";
                case "wait_chain" -> "Follow observed lock ownership from a thread to a terminal owner or cycle. IDs are scoped to one capture.";
                default -> "Read stack groups and counts across captures. Repeated samples do not establish continuous blocking or CPU activity.";
            });
            tool.putObject("annotations").put("readOnlyHint",true).put("destructiveHint",false).put("idempotentHint",true).put("openWorldHint",false);
            tool.putObject("outputSchema").put("type","object");
            ObjectNode schema = tool.putObject("inputSchema").put("type","object").put("additionalProperties",false);
            ObjectNode props = schema.putObject("properties");
            if (name.equals("incident_summary")) continue;
            props.putObject("offset").put("type","integer").put("minimum",0);
            props.putObject("limit").put("type","integer").put("minimum",1).put("maximum",PAGE_LIMIT);
            if (name.equals("list_stacks")) continue;
            props.putObject("snapshot").put("type","integer").put("minimum",1).put("maximum",16);
            ArrayNode required = schema.putArray("required").add("snapshot");
            if (name.equals("list_threads")) {
                props.putObject("nameContains").put("type","string").put("minLength",1);
                props.putObject("state").put("type","string").putArray("enum").add("NEW").add("RUNNABLE").add("BLOCKED").add("WAITING").add("TIMED_WAITING").add("TERMINATED");
            } else {
                props.putObject("thread").put("type","string").put("minLength",1); required.add("thread");
            }
        }
        return result;
    }

    private static void copyFields(JsonNode source, ObjectNode destination, String... keys) {
        for (String key : keys) if (source.has(key)) destination.set(key, source.get(key));
    }

    private static String string(JsonNode params, String key) {
        JsonNode value = params.path(key);
        if (!value.isTextual() || value.textValue().isEmpty()) throw invalid("Expected a nonempty string for " + key);
        return value.textValue();
    }

    private static int integer(JsonNode node, String key, int fallback, int min, int max) {
        if (!node.has(key)) return fallback;
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw invalid(key + " must be an integer between " + min + " and " + max);
        return value.intValue();
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw invalid("Unexpected parameter");
        });
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode reply = JSON.createObjectNode().put("jsonrpc", "2.0");
        return reply.set("id", id);
    }

    private static ObjectNode success(JsonNode id, ObjectNode data) {
        return envelope(id).set("result", data);
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode reply = envelope(id);
        reply.putObject("error").put("code", code).put("message", message);
        return reply;
    }
}
