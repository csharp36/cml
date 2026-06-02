#!/usr/bin/env python3
"""Pre-warm the any-ref overlay for a base sha via a proper MCP Streamable-HTTP
handshake (initialize -> initialized -> tools/call search_symbols@branch).

A raw tools/call POST is rejected by the transport (needs Accept: text/event-stream
+ application/json and an mcp-session-id from initialize), so the old curl-based
prewarm silently warmed nothing. This does the real handshake and returns the
fault-in latency.

Usage: prewarm.py <base_sha> [url] [bearer]
Exit 0 on a non-error tools/call result, 1 otherwise.
"""
import sys, json, time, urllib.request

URL = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080/mcp"
BEARER = sys.argv[3] if len(sys.argv) > 3 else "bench-upload-key"
BASE = sys.argv[1]

HDR = {
    "Authorization": f"Bearer {BEARER}",
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}


def _parse(body: bytes):
    """Body may be empty (202 for notifications), a JSON object, or an SSE stream."""
    text = body.decode("utf-8", "replace").strip()
    if not text:
        return {}
    if text.startswith("{"):
        return json.loads(text)
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            payload = line[5:].strip()
            if payload:
                return json.loads(payload)
    raise ValueError(f"no JSON in response: {text[:200]}")


def post(body: dict, session: str | None):
    hdr = dict(HDR)
    if session:
        hdr["mcp-session-id"] = session
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=hdr, method="POST")
    resp = urllib.request.urlopen(req, timeout=180)
    sid = resp.headers.get("mcp-session-id")
    return resp, _parse(resp.read()), sid


def main() -> int:
    # 1. initialize
    init = {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "prewarm", "version": "1"},
        },
    }
    _, _, sid = post(init, None)
    if not sid:
        print(f"  no session id for {BASE}", file=sys.stderr)
        return 1
    # 2. initialized notification (no id)
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    # 3. tools/call search_symbols @ branch -> triggers overlay fault-in
    call = {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": "search_symbols",
                   "arguments": {"repo": "hazelcast", "name": "Map", "branch": BASE}},
    }
    t0 = time.time()
    _, res, _ = post(call, sid)
    dt = time.time() - t0
    if "error" in res:
        print(f"  WARN warm {BASE[:12]} error: {res['error'].get('message','?')[:80]} ({dt:.1f}s)")
        return 1
    print(f"  warmed {BASE[:12]} in {dt:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
