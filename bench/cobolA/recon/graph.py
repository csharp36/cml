"""Static call-graph depth and shared-data coupling metrics (Phase 0)."""
from collections import defaultdict


def build_call_graph(facts_by_program: dict) -> dict:
    """Union static CALL and static CICS XCTL/LINK edges into adjacency sets."""
    g = {}
    for pid, f in facts_by_program.items():
        g[pid] = set(f.get("static_calls", set())) | set(f.get("static_xctl_link", set()))
    # ensure every referenced node exists as a key
    for succs in list(g.values()):
        for s in succs:
            g.setdefault(s, set())
    return g


def max_chain_depth(graph: dict) -> int:
    """Longest simple-path node count. Cycle-safe via on-stack tracking + memo."""
    memo = {}

    def dfs(node, stack):
        if node in stack:            # cycle: stop expanding this branch
            return 0
        if node in memo:
            return memo[node]
        stack.add(node)
        best = 0
        for nxt in graph.get(node, ()):  # depth of the subtree below `node`
            best = max(best, dfs(nxt, stack))
        stack.remove(node)
        memo[node] = 1 + best
        return memo[node]

    return max((dfs(n, set()) for n in graph), default=0)


def shared_data_fan(facts_by_program: dict, threshold: int = 3) -> dict:
    copy_to = defaultdict(set)
    file_to = defaultdict(set)
    for pid, f in facts_by_program.items():
        for c in f.get("copybooks", set()):
            copy_to[c].add(pid)
        for fl in f.get("file_ops", set()):
            file_to[fl].add(pid)
    at_or_above = sum(1 for s in copy_to.values() if len(s) >= threshold) + \
                  sum(1 for s in file_to.values() if len(s) >= threshold)
    return {
        "copybook_to_programs": {k: v for k, v in copy_to.items()},
        "file_to_programs": {k: v for k, v in file_to.items()},
        "resources_at_or_above_threshold": at_or_above,
    }
