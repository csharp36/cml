#!/usr/bin/env python3
"""Neutral ground-truth extraction from a PR's gold patch.
Usage: extract_truth.py <repo> <base_sha> <merge_sha>  ->  prints {"files":[...],"symbols":[...]}
Symbols come from git's built-in java funcname hunk headers — NOT from our index."""
import json, os, re, subprocess, sys

_TEST_RE = re.compile(r"(^|/)src/test/|Test\.java$|IT\.java$|/it/", re.I)

def is_source_file(path):
    return path.endswith(".java") and "/src/main/" in path and not _TEST_RE.search(path)

def _method_name(ctx):
    """Java funcname header is a signature line; the identifier just before '(' is the method."""
    if "(" not in ctx:
        return None
    head = ctx[:ctx.index("(")]
    ids = re.findall(r"[A-Za-z_]\w*", head)
    return ids[-1] if ids else None

def parse_diff(diff_text):
    files, symbols = set(), set()
    cur_file = cur_class = None
    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:].strip()
            cur_file = path if is_source_file(path) else None
            cur_class = os.path.basename(cur_file)[:-5] if cur_file else None
            if cur_file:
                files.add(cur_file)
        elif cur_file and line.startswith("@@"):
            m = re.match(r"@@ .*? @@ ?(.*)", line)
            method = _method_name((m.group(1) if m else "").strip())
            symbols.add(f"{cur_class}#{method}" if method else cur_class)
    return files, symbols

def _enable_java_funcname(repo):
    attrs = os.path.join(repo, ".git", "info", "attributes")
    have = os.path.exists(attrs) and "*.java diff=java" in open(attrs).read()
    if not have:
        with open(attrs, "a") as f:
            f.write("*.java diff=java\n")

def extract(repo, base, merge):
    _enable_java_funcname(repo)
    diff = subprocess.run(["git", "-C", repo, "diff", f"{base}..{merge}"],
                          capture_output=True, text=True, check=True).stdout
    files, symbols = parse_diff(diff)
    return {"files": sorted(files), "symbols": sorted(symbols)}

if __name__ == "__main__":
    repo, base, merge = sys.argv[1], sys.argv[2], sys.argv[3]
    print(json.dumps(extract(repo, base, merge)))
