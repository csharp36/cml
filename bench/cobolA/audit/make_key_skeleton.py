#!/usr/bin/env python3
"""Emit the audited-strata questions as a skeleton for independent hand-verification.

For each call_closure/data_coupling/txn_reach question it prints the node, the prompt,
and the CURRENT oracle answer as a *candidate to be checked against source* — NOT as the
key. The human verifies each against COBOL/CSD source and writes the final answer into
hard_strata_key.json, recording evidence in KEY-AUDIT.md.
"""
import json

AUDITED = {"call_closure", "data_coupling", "txn_reach"}

if __name__ == "__main__":
    skeleton = {}
    for line in open("../questions.jsonl"):
        q = json.loads(line)
        if q["stratum"] in AUDITED:
            skeleton[q["id"]] = {
                "node": q["node"],
                "stratum": q["stratum"],
                "oracle_candidate": q["answer_simple"],
                "audited_answer": None,
            }
    print(json.dumps(skeleton, indent=2))
