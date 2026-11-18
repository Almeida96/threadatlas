"""Compare ThreadAtlas findings with the independent JVM management API."""
import argparse
import hashlib
import json
import re
from pathlib import Path


def check(root):
    expected = {"deadlock": "DEADLOCK", "convoy": "CONTENTION", "idle": "NO_CYCLE_OBSERVED"}
    for mode, verdict in expected.items():
        folder = root / mode
        report = json.loads((folder / "analysis/report.json").read_bytes())
        proof = json.loads((folder / "mxbean.json").read_bytes())
        assert report["verdict"] == verdict, mode
        native = {t["id"]: t for t in proof["threads"]}
        for snapshot in report["snapshots"]:
            dump = folder / snapshot["source"]["name"]
            data = dump.read_bytes()
            lines = data.decode("utf-8-sig").splitlines()
            assert snapshot["source"]["sha256"] == hashlib.sha256(data).hexdigest()
            actual_ids = {tid for c in snapshot["cycles"] if c["kind"] == "DEADLOCK" for tid in c["threads"]}
            assert actual_ids == set(proof["deadlockedIds"]), (mode, actual_ids, proof["deadlockedIds"])
            actual = {t["id"]: t for t in snapshot["source"]["threads"]}
            for tid, t in native.items():
                assert actual[tid]["name"] == t["name"] and actual[tid]["state"] == t["state"]
            edge_map = {e["waiter"]: e for e in snapshot["edges"]}
            for tid, t in native.items():
                if t["ownerId"] != "-1":
                    assert edge_map[tid]["owner"] == t["ownerId"]
                else:
                    assert tid not in edge_map
            for edge in snapshot["edges"]:
                def has_lock(line):
                    return any(hex(int(h, 16)) == edge["lock"] for h in re.findall(r"<(0x[0-9a-fA-F]+)>", line))
                assert has_lock(lines[edge["waitLine"] - 1])
                assert has_lock(lines[edge["ownerLine"] - 1])
            if mode == "convoy":
                owner = next(tid for tid, t in native.items() if t["name"] == "warehouse-slow-owner")
                blocker = next(b for b in snapshot["blockers"] if b["thread"] == owner)
                assert blocker["directWaiters"] == blocker["downstreamWaiters"] == 4
            assert not snapshot["unresolved"]
        print(f"{mode}: {len(report['snapshots'])} captures match ThreadMXBean IDs, states and owner relationships")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--demo", required=True, type=Path)
    args = parser.parse_args()
    check(args.demo.resolve())
