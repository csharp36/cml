#!/usr/bin/env python3
"""Minimal Streamable-HTTP MCP client: initialize -> session -> tools/call.
Usage: SERVER=.. HZ_READ_KEY=.. mcp_call.py <tool_name> <args_json>
Prints the tool result's first text-content payload (the tool's own JSON) to stdout."""
import os, sys, json, urllib.request

SERVER = os.environ["SERVER"].rstrip("/")
KEY = os.environ.get("HZ_READ_KEY") or os.environ["CI_UPLOAD_KEY"]


def _post(body, session=None):
    headers = {"Authorization": f"Bearer {KEY}", "Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if session:
        headers["mcp-session-id"] = session
    req = urllib.request.Request(SERVER + "/mcp", data=json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.headers.get("mcp-session-id"), r.read().decode()


def _parse(raw):
    raw = raw.strip()
    if not raw:
        return None
    if raw[0] == "{":
        return json.loads(raw)
    out = None
    for line in raw.splitlines():
        if line.startswith("data:"):
            out = json.loads(line[5:].strip())
    return out


def call(tool, args):
    sid, _ = _post({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                    "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                               "clientInfo": {"name": "arenaA", "version": "0"}}})
    _post({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}, session=sid)
    _, raw = _post({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                    "params": {"name": tool, "arguments": args}}, session=sid)
    resp = _parse(raw)
    content = (resp or {}).get("result", {}).get("content", [])
    for c in content:
        if c.get("type") == "text":
            return c["text"]
    return json.dumps(resp)


if __name__ == "__main__":
    print(call(sys.argv[1], json.loads(sys.argv[2])))
