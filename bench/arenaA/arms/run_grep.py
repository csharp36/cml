#!/usr/bin/env python3
"""grep-iterative closure arm (portable). BFS from the type's simple name: at each step,
grep src/main/java for declarations whose implements/extends clause names a member of the
frontier, extract the declared class, repeat. Uses real grep for the scan; word-boundary
match is verified in Python (BSD grep lacks \\b)."""
import sys, json, subprocess, re
SRC, Q = sys.argv[1], sys.argv[2]
roots = subprocess.run(["find", SRC, "-type", "d", "-path", "*/src/main/java"],
                       capture_output=True, text=True).stdout.split()
decl = re.compile(r'\b(?:class|interface)\s+([A-Za-z0-9_]+)')

def implementers_of(name):
    # coarse grep: declaration lines whose implements/extends clause mentions `name`
    pat = r'(class|interface)[^{]*(implements|extends)[^{]*' + re.escape(name)
    out = subprocess.run(["grep", "-rhoE", pat, *roots, "--include=*.java"],
                         capture_output=True, text=True).stdout
    wb = re.compile(r'\b' + re.escape(name) + r'\b')
    found = []
    for line in out.splitlines():
        # require the name as a whole word in the supers portion
        sup = re.split(r"\b(?:implements|extends)\b", line, maxsplit=1)
        if len(sup) > 1 and wb.search(sup[1]):
            m = decl.search(line)
            if m:
                found.append(m.group(1))
    return found

for line in open(Q):
    q = json.loads(line); seed = q["type_simple"]
    seen=set(); frontier=[seed]; calls=0
    while frontier:
        nxt=[]
        for nm in frontier:
            if nm in seen: continue
            seen.add(nm); calls+=1
            nxt.extend(implementers_of(nm))
        frontier=nxt
    seen.discard(seed)
    print(json.dumps({"id":q["id"],"arm":"grep","found_simple":sorted(seen),"found_fqn":None,"calls":calls}))
