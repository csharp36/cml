#!/usr/bin/env python3
"""Sanitize a raw git merge/commit message into a leak-free discovery task statement.

The agent must NOT be handed identifiers it can use to look the answer up:
- "Merge pull request #N from user/branch-name" lines carry the PR number and branch
  name (direct keys for `git log --all | grep`).
- bare `#N` PR/issue tokens.
- explicit source paths / fully-qualified dotted class names (point straight at the answer).

Pure merge-plumbing messages (a "Merge branch ... into ..." with no human description)
sanitize to "" and the caller should DROP that instance — it was never a real task.

Usage as a filter: reads JSONL on stdin, rewrites each object's "task" via sanitize(),
drops objects whose task becomes empty, writes JSONL to stdout. Reports dropped count
to stderr.
"""
import re
import sys
import json

_MERGE_LINE = re.compile(
    r"^\s*Merge\s+(pull request|remote-tracking branch|branch)\b.*$",
    re.IGNORECASE,
)
_PR_TOKEN = re.compile(r"\(?#\d+\)?")
_SRC_PATH = re.compile(r"[A-Za-z0-9_/.-]+/src/(main|test)/[A-Za-z0-9_/.]+\.java")
_DOTTED_FQN = re.compile(r"\b([a-z]+\.)+[A-Z][A-Za-z0-9_]+\b")


def sanitize(message: str) -> str:
    """Return a leak-free task description, or "" if nothing meaningful remains."""
    lines = []
    for line in (message or "").splitlines():
        if _MERGE_LINE.match(line):
            continue  # drop merge-plumbing subject lines entirely
        lines.append(line)
    text = "\n".join(lines)
    text = _PR_TOKEN.sub("", text)
    text = _SRC_PATH.sub("<a source file>", text)
    text = _DOTTED_FQN.sub("<a class>", text)
    # collapse blank lines / trailing space introduced by removals
    text = "\n".join(ln.rstrip() for ln in text.splitlines() if ln.strip())
    text = text.strip()
    # too short to be a real task (e.g. leftover punctuation/asterisks)
    if len(re.sub(r"[^A-Za-z0-9]", "", text)) < 6:
        return ""
    return text


def _filter_stream(inp, outp) -> int:
    dropped = 0
    for raw in inp:
        if not raw.strip():
            continue
        obj = json.loads(raw)
        clean = sanitize(obj.get("task", ""))
        if not clean:
            dropped += 1
            continue
        obj["task"] = clean
        outp.write(json.dumps(obj) + "\n")
    return dropped


if __name__ == "__main__":
    n = _filter_stream(sys.stdin, sys.stdout)
    print(f"dropped {n} instance(s) with no usable task", file=sys.stderr)
