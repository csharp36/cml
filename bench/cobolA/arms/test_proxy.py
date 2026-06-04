import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).parent
ORACLE = HERE.parent / "oracle" / "oracle.json"

def _round_trip(node, kind):
    proc = subprocess.Popen(
        [sys.executable, str(HERE / "cobol_reachability_server.py"), str(ORACLE)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    msgs = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
         "params": {"name": "cobol_reachability", "arguments": {"node": node, "kind": kind}}},
    ]
    out, _ = proc.communicate("\n".join(json.dumps(m) for m in msgs) + "\n", timeout=30)
    results = [json.loads(l) for l in out.splitlines() if l.strip()]
    call_resp = next(r for r in results if r.get("id") == 2)
    payload = json.loads(call_resp["result"]["content"][0]["text"])
    return set(payload["found_simple"])

def test_proxy_returns_oracle_call_closure():
    oracle = json.load(open(ORACLE))
    node = next(p for p, c in oracle["transitive_call_closure"].items() if len(c) >= 2)
    expected = set(oracle["transitive_call_closure"][node])
    assert _round_trip(node, "call_closure") == expected

def test_proxy_unknown_node_returns_empty():
    assert _round_trip("NOPE-DOES-NOT-EXIST", "call_closure") == set()
