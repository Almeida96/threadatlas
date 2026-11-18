"""Read ThreadAtlas's report with the official MCP Python SDK and compare its pages to disk."""

import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def call(client, name, **arguments):
    result = await client.call_tool(name, arguments)
    assert not result.isError, result.content
    assert json.loads(result.content[0].text) == result.structuredContent
    return result.structuredContent


async def compare_pages(client, tool, key, expected, **filters):
    # Both ends exercise offset handling without scanning a large report one row at a time.
    offsets = sorted({0, max(0, len(expected) - 1), len(expected)})
    for offset in offsets:
        page = await call(client, tool, offset=offset, limit=1, **filters)
        assert page[key] == expected[offset:offset + 1]
        assert page["total"] == len(expected) and page["offset"] == offset
        assert page["nextOffset"] == (offset + 1 if offset + 1 < len(expected) else None)


async def check(install_dir, report_path):
    before = report_path.read_bytes()
    report = json.loads(before)
    lib = install_dir / "lib"
    if not list(lib.glob("*.jar")):
        raise ValueError(f"Missing distribution JARs in {lib}")
    java_home = os.environ.get("JAVA_HOME")
    executable = "java.exe" if os.name == "nt" else "java"
    java = str(Path(java_home) / "bin" / executable) if java_home else shutil.which(executable)
    if not java:
        raise RuntimeError("A Java 21 runtime is required")
    server = StdioServerParameters(command=java, args=[
        "-cp", str(lib / "*"), "io.github.almeida96.threadatlas.MainKt", "mcp", "--report", str(report_path),
    ])
    async with stdio_client(server) as (reader, writer), ClientSession(reader, writer) as client:
        assert (await client.initialize()).serverInfo.name == "threadatlas"
        await client.send_ping()
        tools = (await client.list_tools()).tools
        assert {t.name for t in tools} == {"incident_summary","list_threads","thread_detail","wait_chain","list_stacks"}
        assert all(t.annotations.readOnlyHint and not t.annotations.openWorldHint for t in tools)
        summary = await call(client,"incident_summary")
        assert summary["verdict"] == report["verdict"]
        await compare_pages(client,"list_stacks","stacks",report["stacks"])
        for s in report["snapshots"]:
            idx = s["index"]
            edges = {e["waiter"]: e for e in s["edges"]}
            blockers = {b["thread"]: b for b in s["blockers"]}
            expected = []
            for t in s["source"]["threads"]:
                row = {k:t[k] for k in ("id","name","state","headerLine")}
                row.update(owner=edges[t["id"]]["owner"] if t["id"] in edges else None,
                    downstreamWaiters=blockers.get(t["id"],{}).get("downstreamWaiters",0),
                    inCycle=blockers.get(t["id"],{}).get("inCycle",False))
                expected.append(row)
            await compare_pages(client,"list_threads","threads",expected,snapshot=idx)
            chosen = [r for r in expected if "warehouse-" in r["name"]]
            await compare_pages(client,"list_threads","threads",chosen,snapshot=idx,nameContains="warehouse-")
            for t in s["source"]["threads"]:
                if "warehouse-" not in t["name"]:
                    continue
                await compare_pages(client,"thread_detail","frames",t["frames"],snapshot=idx,thread=t["id"])
                chain, seen, current = [], set(), t["id"]
                while current not in seen and current in edges:
                    seen.add(current)
                    edge = edges[current]
                    chain.append(edge)
                    current = edge["owner"]
                await compare_pages(client,"wait_chain","edges",chain,snapshot=idx,thread=t["id"])
                result = await call(client,"wait_chain",snapshot=idx,thread=t["id"])
                assert result["cycle"] == (current in seen)
        assert (await client.call_tool("incident_summary",{"path":"other.json"})).isError
        assert (await client.call_tool("thread_detail",{"snapshot":16,"thread":"unknown"})).isError
        assert (await client.call_tool("list_stacks",{"limit":101})).isError
    assert report_path.read_bytes() == before
    print(f"{report['verdict']}: all five MCP tools match JSON evidence, including chains and paginated stacks")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    asyncio.run(asyncio.wait_for(check(args.install_dir.resolve(), args.report.resolve()), timeout=60))
