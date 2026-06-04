"""
Pure graph functions over the edges-map produced by the extractor.

edges_map: dict[str, dict]
    program_id -> edges-object with keys:
      static_calls, resolved_dynamic_calls,
      static_xctl_link, resolved_dynamic_xctl_link,
      copybooks, files_read, files_written, db2_tables
"""
from __future__ import annotations
from collections import deque, defaultdict


def call_edges(prog_obj: dict) -> set[str]:
    """Return the full set of call/transfer targets for one program object.

    Union of:
      static_calls
      resolved_dynamic_calls
      static_xctl_link
      resolved_dynamic_xctl_link
    """
    return (
        set(prog_obj.get("static_calls", []))
        | set(prog_obj.get("resolved_dynamic_calls", []))
        | set(prog_obj.get("static_xctl_link", []))
        | set(prog_obj.get("resolved_dynamic_xctl_link", []))
    )


def transitive_call_closure(edges_map: dict[str, dict], start: str) -> set[str]:
    """BFS over call_edges from *start*, returning every reachable program ID.

    Cycle-safe.  The start program itself is excluded from the result.
    Programs not in edges_map are included as reachable (they are called)
    but have no outgoing edges — their call targets are not explored.
    """
    visited: set[str] = set()
    queue: deque[str] = deque()

    # Seed from start's direct neighbours. Exclude a direct self-transfer
    # (a program may XCTL to itself, e.g. a "redisplay current screen" path):
    # by definition the start is never a member of its own closure.
    for target in call_edges(edges_map.get(start, {})):
        if target != start and target not in visited:
            visited.add(target)
            queue.append(target)

    while queue:
        node = queue.popleft()
        for target in call_edges(edges_map.get(node, {})):
            if target not in visited and target != start:
                visited.add(target)
                queue.append(target)

    return visited


def _resources(prog_obj: dict) -> set[str]:
    """All file/db2 resources accessed by a program (read + write + db2)."""
    return (
        set(prog_obj.get("files_read", []))
        | set(prog_obj.get("files_written", []))
        | set(prog_obj.get("db2_tables", []))
    )


def data_access_set(edges_map: dict[str, dict], resource: str) -> set[str]:
    """Return the set of program_ids whose data resources include *resource*."""
    return {
        pid
        for pid, obj in edges_map.items()
        if resource in _resources(obj)
    }


def copybook_fan(edges_map: dict[str, dict]) -> dict[str, set[str]]:
    """Return a mapping of copybook -> set of program_ids that COPY it."""
    fan: dict[str, set[str]] = {}
    for pid, obj in edges_map.items():
        for cb in obj.get("copybooks", []):
            fan.setdefault(cb, set()).add(pid)
    return fan


def shared_data_coupling(
    edges_map: dict[str, dict],
    set_a: set[str],
    set_b: set[str],
) -> set[str]:
    """Return resources (files/db2) accessed by >=1 program in BOTH set_a and set_b."""
    resources_a: set[str] = set()
    for pid in set_a:
        if pid in edges_map:
            resources_a |= _resources(edges_map[pid])

    resources_b: set[str] = set()
    for pid in set_b:
        if pid in edges_map:
            resources_b |= _resources(edges_map[pid])

    return resources_a & resources_b


def data_coupling_neighbors(edges_map: dict[str, dict]) -> dict[str, set[str]]:
    """Return {program: set of OTHER programs sharing >=1 file/DB2 resource}.

    Copybooks are deliberately excluded — shared record layouts are stratum 4;
    stratum 3 is coupling through a mutable data store, which is what resists a
    clean service split.
    """
    res_to_progs: dict[str, set[str]] = defaultdict(set)
    for pid, obj in edges_map.items():
        for res in _resources(obj):
            res_to_progs[res].add(pid)

    neighbors: dict[str, set[str]] = {pid: set() for pid in edges_map}
    for group in res_to_progs.values():
        for pid in group:
            neighbors[pid] |= group - {pid}
    return neighbors
