#!/usr/bin/env python3
"""Thin stdio JSON-RPC (MCP-subset) server exposing one tool, cobol_reachability(node, kind),
backed by oracle.json. Implements initialize / tools/list / tools/call. Line-delimited JSON.

Usage: cobol_reachability_server.py <oracle.json>
"""
import json
import sys

KIND_TO_KEY = {
    "call_closure": "transitive_call_closure",
    "data_access": "data_access",
    "data_coupling": "data_coupling",
    "copybook_fan": "copybook_fan",
    "txn_reach": "txn_reach",
}

TOOL = {
    "name": "cobol_reachability",
    "description": "Return the set of CardDemo programs reachable/related to a node under a "
                   "given relation kind, from the precomputed ProLeap oracle.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "node": {"type": "string"},
            "kind": {"type": "string", "enum": list(KIND_TO_KEY)},
        },
        "required": ["node", "kind"],
    },
}


def _lookup(oracle, node, kind):
    table = oracle.get(KIND_TO_KEY.get(kind, ""), {})
    return sorted(table.get(node, []))


def main():
    oracle = json.load(open(sys.argv[1]))
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = json.loads(line)
        method, rid = req.get("method"), req.get("id")
        if method == "initialize":
            _send(rid, {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}},
                        "serverInfo": {"name": "cobol-reachability", "version": "0"}})
        elif method == "tools/list":
            _send(rid, {"tools": [TOOL]})
        elif method == "tools/call":
            args = req["params"]["arguments"]
            found = _lookup(oracle, args["node"].upper(), args["kind"])
            payload = {"node": args["node"].upper(), "kind": args["kind"], "found_simple": found}
            _send(rid, {"content": [{"type": "text", "text": json.dumps(payload)}]})
        elif rid is not None:
            _send(rid, None, error={"code": -32601, "message": f"method not found: {method}"})


def _send(rid, result, error=None):
    msg = {"jsonrpc": "2.0", "id": rid}
    if error:
        msg["error"] = error
    else:
        msg["result"] = result
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


if __name__ == "__main__":
    main()
