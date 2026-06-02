import re, subprocess, sys, json, zipfile
from collections import defaultdict

_DECL = re.compile(r'\b(?:class|interface)\s+([\w.$]+)(?:<[^>]*>)?'
                   r'(?:\s+extends\s+([\w.$,<>\s]+?))?(?:\s+implements\s+([\w.$,<>\s]+?))?\s*\{')

def parse_javap_decl(line):
    m = _DECL.search(line)
    if not m:
        return None, []
    fqn = m.group(1)
    supers = []
    for grp in (m.group(2), m.group(3)):
        if grp:
            for t in grp.split(','):
                t = re.sub(r'<[^>]*>', '', t).strip()
                if t:
                    supers.append(t)
    return fqn, supers

def transitive_closure(child_to_parents, target):
    parent_to_children = defaultdict(set)
    for c, ps in child_to_parents.items():
        for p in ps:
            parent_to_children[p].add(c)
    seen, stack = set(), [target]
    while stack:
        cur = stack.pop()
        for child in parent_to_children.get(cur, ()):
            if child not in seen:
                seen.add(child); stack.append(child)
    return seen

def build_graph_from_jar(jar_path, javap="javap"):
    names = []
    with zipfile.ZipFile(jar_path) as z:
        for n in z.namelist():
            if n.endswith('.class') and '$' not in n:
                names.append(n[:-6].replace('/', '.'))
    graph = {}
    for i in range(0, len(names), 200):
        batch = names[i:i+200]
        out = subprocess.run([javap, "-cp", jar_path, "-p", *batch],
                             capture_output=True, text=True).stdout
        for line in out.splitlines():
            if (' class ' in line or ' interface ' in line) and line.rstrip().endswith('{'):
                fqn, supers = parse_javap_decl(line)
                if fqn:
                    graph[fqn] = supers
    return graph

if __name__ == "__main__":
    jar = sys.argv[1]
    graph = build_graph_from_jar(jar)
    simple = defaultdict(set)
    for fqn in graph:
        simple[fqn.split('.')[-1]].add(fqn)
    out = {"graph": graph, "simple_name_index": {k: sorted(v) for k, v in simple.items()}}
    json.dump(out, open("oracle.json", "w"))
    print(f"types={len(graph)} colliding_simple_names="
          f"{sum(1 for v in simple.values() if len(v) > 1)}")
