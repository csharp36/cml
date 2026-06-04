#!/usr/bin/env python3
"""Proxy arm: one cobol_reachability MCP round-trip per question, over a single
long-lived server subprocess. Emits JSONL identical in shape to the grep arm.

Usage: python run_proxy.py <oracle.json> <questions.jsonl>  -> JSONL on stdout
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).parent


def main():
    oracle_path, qpath = sys.argv[1], sys.argv[2]
    proc = subprocess.Popen(
        [sys.executable, str(HERE / "cobol_reachability_server.py"), oracle_path],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
    _rpc(proc, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
    proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized",
                                 "params": {}}) + "\n")
    proc.stdin.flush()

    rid = 1
    for line in open(qpath):
        q = json.loads(line)
        rid += 1
        resp = _rpc(proc, {"jsonrpc": "2.0", "id": rid, "method": "tools/call",
                           "params": {"name": "cobol_reachability",
                                      "arguments": {"node": q["node"], "kind": q["kind"]}}})
        payload = json.loads(resp["result"]["content"][0]["text"])
        print(json.dumps({"id": q["id"], "arm": "proxy",
                          "found_simple": payload["found_simple"], "calls": 1}))
    proc.stdin.close()
    proc.wait(timeout=10)


def _rpc(proc, msg):
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("server closed unexpectedly")
        resp = json.loads(line)
        if resp.get("id") == msg["id"]:
            return resp


if __name__ == "__main__":
    main()
