# Phase E2: Semantic Query Tools — Design Spec

## Overview

Two new MCP tools that query SCIP data stored in PostgreSQL, plus precision indicators on existing tools. Builds on the E1 SCIP receiving pipeline — no new tables or migrations required.

**Depends on:** Phase E1 (SCIP receiving + storage) for `scip_symbols` and `scip_relationships` tables.

**Gate:** Claude Code can query type hierarchies and symbol references using SCIP data, and existing tools surface SCIP availability to guide tool selection.

**Future phases:** E3 (CLI wrapper + CI pipeline docs for SCIP upload).

---

## Tools Summary

| Tool | Purpose | New/Modified |
|------|---------|-------------|
| `get_type_hierarchy` | Walk implements/extends relationships up and down from a given type | New |
| `get_symbol_references` | Find symbols related to a given symbol (flat list of direct edges) | New |
| `scip_status` field | Repo-level SCIP precision indicator on existing tool responses | Enhancement |

Total tool count: 15 → 17.

No branch support — SCIP tables have no branch column. This is documented in tool descriptions and is consistent with E1's scope boundaries (feature branch SCIP data deferred).

---

## get_type_hierarchy

Recursive traversal of implements/extends relationships from a given type.

### Parameters

| Param | Required | Type | Default | Description |
|-------|----------|------|---------|-------------|
| `repo` | yes | string | — | Repository name |
| `symbol_name` | yes | string | — | Display name (e.g., `PaymentProcessor`) |
| `file_path` | no | string | — | Disambiguate when multiple symbols share a name |
| `kind` | no | string | — | Filter by symbol kind (`Class`, `Interface`, etc.) |
| `direction` | no | string | `both` | `up` (supertypes), `down` (subtypes), or `both` |
| `depth` | no | integer | 3 | Max traversal depth |

### Resolution Flow

1. Query `scip_symbols WHERE repo_id = :repoId AND display_name = :symbolName`
2. If `file_path` provided: add `AND file_path = :filePath`
3. If `kind` provided: add `AND kind = :kind`
4. If multiple matches: use first match, note ambiguity in response
5. If no matches: return empty result with `scip_status`

### Traversal

- **Up:** Follow `scip_relationships WHERE from_symbol = :symbol AND kind IN ('implements', 'extends')` — resolve `to_symbol` recursively up to `depth`
- **Down:** Follow `scip_relationships WHERE to_symbol = :symbol AND kind IN ('implements', 'extends')` — resolve `from_symbol` recursively up to `depth`
- **Both:** Traverse both directions

At each level, enrich resolved symbols with metadata from `scip_symbols` (kind, file_path, start_line, documentation).

Uses indexes: `idx_scip_rel_from` (for up traversal), `idx_scip_rel_to` (for down traversal), `idx_scip_symbols_repo_name` (for initial resolution).

### Response (200)

```json
{
  "symbol": "PaymentProcessor",
  "scip_symbol": "java maven . com/example/PaymentProcessor#.",
  "kind": "Interface",
  "file_path": "src/main/java/com/example/PaymentProcessor.java",
  "line": 5,
  "documentation": "Base interface for payment processing",
  "supertypes": [],
  "subtypes": [
    {
      "symbol": "StripeProcessor",
      "scip_symbol": "java maven . com/example/StripeProcessor#.",
      "kind": "Class",
      "relationship": "implements",
      "file_path": "src/main/java/com/example/StripeProcessor.java",
      "line": 8,
      "subtypes": []
    }
  ],
  "scip_status": "fresh"
}
```

When `direction` is `up`, the `subtypes` key is omitted. When `down`, the `supertypes` key is omitted.

### Error Cases

| Condition | Behavior |
|-----------|----------|
| Symbol not found in SCIP data | Empty result with `scip_status` |
| Repo not found | Error response |
| SCIP data unavailable for repo | Empty result, `scip_status: "unavailable"` |

---

## get_symbol_references

Flat list of symbols related to a given symbol through any SCIP relationship kind. Not recursive — returns direct edges only.

### Parameters

| Param | Required | Type | Default | Description |
|-------|----------|------|---------|-------------|
| `repo` | yes | string | — | Repository name |
| `symbol_name` | yes | string | — | Display name (e.g., `PaymentProcessor`) |
| `file_path` | no | string | — | Disambiguate when multiple symbols share a name |
| `relationship_kind` | no | string | all | Filter: `implements`, `extends`, `references`, or all |
| `direction` | no | string | `inbound` | `inbound` (who references this), `outbound` (what this references), `both` |
| `limit` | no | integer | 50 | Max results |

### Resolution Flow

Same as `get_type_hierarchy`: resolve `symbol_name` to SCIP symbol string via `scip_symbols`, optionally narrowed by `file_path`.

### Query

- **Inbound:** `scip_relationships WHERE repo_id = :repoId AND to_symbol = :symbol` — uses `idx_scip_rel_to`
- **Outbound:** `scip_relationships WHERE repo_id = :repoId AND from_symbol = :symbol` — uses `idx_scip_rel_from`
- **Both:** Union of both queries

If `relationship_kind` provided, add `AND kind = :kind`.

Each related symbol is enriched with metadata from `scip_symbols` via a join or separate lookup (kind, file_path, documentation).

### Response (200)

```json
{
  "symbol": "PaymentProcessor",
  "scip_symbol": "java maven . com/example/PaymentProcessor#.",
  "kind": "Interface",
  "file_path": "src/main/java/com/example/PaymentProcessor.java",
  "references": [
    {
      "symbol": "StripeProcessor",
      "scip_symbol": "java maven . com/example/StripeProcessor#.",
      "kind": "Class",
      "relationship": "implements",
      "file_path": "src/main/java/com/example/StripeProcessor.java",
      "line": 8,
      "direction": "inbound"
    },
    {
      "symbol": "PayPalProcessor",
      "scip_symbol": "java maven . com/example/PayPalProcessor#.",
      "kind": "Class",
      "relationship": "implements",
      "file_path": "src/main/java/com/example/PayPalProcessor.java",
      "line": 3,
      "direction": "inbound"
    }
  ],
  "total": 2,
  "scip_status": "fresh"
}
```

### Distinction from get_type_hierarchy

`get_type_hierarchy` is a recursive tree limited to implements/extends. `get_symbol_references` is a flat capped list covering all relationship kinds (implements, extends, references). Use type hierarchy for "show me the class tree," use symbol references for "who uses this?"

---

## Precision Indicators

### scip_status Field

A top-level `scip_status` field added to existing tool responses. Tells the LLM whether SCIP semantic data is available for the queried repo, guiding it toward semantic tools when useful.

### Derivation

Same logic as `get_index_health`:

| Condition | Status |
|-----------|--------|
| `scip_sha = last_indexed_sha` | `fresh` |
| `scip_sha != last_indexed_sha` | `stale` |
| `scip_sha IS NULL` | `unavailable` |

### Helper Method

`getScipStatus(String repoName)` in `QueryExecutor`:

```sql
SELECT scip_sha, last_indexed_sha FROM repositories WHERE name = :repo
```

Returns `"fresh"`, `"stale"`, or `"unavailable"`. One lightweight query per tool call.

### Tools That Get scip_status

| Tool | Rationale |
|------|-----------|
| `search_symbols` | Tells LLM semantic tools are available for deeper queries |
| `get_symbol_detail` | Natural follow-up point to semantic tools |
| `find_implementations` | SCIP has better implementation data |
| `find_references` | SCIP has reference data |
| `get_repo_summary` | Overview tool, natural place to surface SCIP availability |

Tools like `search_code`, `search_files`, `get_file_summary`, `get_directory_tree` are structural/text-oriented — `scip_status` would be noise.

---

## Implementation Components

### QueryExecutor Changes

New methods:
- `getTypeHierarchy(String repo, String symbolName, String filePath, String kind, String direction, int depth)` → `Map<String, Object>`
- `getSymbolReferences(String repo, String symbolName, String filePath, String relationshipKind, String direction, int limit)` → `Map<String, Object>`
- `getScipStatus(String repoName)` → `String` (helper, used by both new tools and existing tools)

Private helpers:
- `resolveScipSymbol(Handle handle, int repoId, String symbolName, String filePath, String kind)` → resolves display name to SCIP symbol row(s). Shared by both new tools.
- `traverseHierarchy(Handle handle, int repoId, String scipSymbol, String direction, int depth, int currentDepth)` → recursive hierarchy builder

### McpServerBootstrap Changes

Two new tool definitions + handlers:
- `getTypeHierarchyTool()` + `handleGetTypeHierarchy()`
- `getSymbolReferencesTool()` + `handleGetSymbolReferences()`

Each registered in both `buildServer()` and `startHttp()`.

### Existing Tool Modifications

Add `result.put("scip_status", getScipStatus(repo))` to:
- `searchSymbols()`
- `getSymbolDetail()`
- `findImplementations()`
- `findReferences()`
- `getRepoSummary()`

---

## Scope Boundaries

**In scope:**
- `get_type_hierarchy` MCP tool (recursive implements/extends traversal)
- `get_symbol_references` MCP tool (flat relationship list)
- `scip_status` precision indicator on 5 existing tools
- Shared SCIP symbol resolution helper
- Tool registration in McpServerBootstrap
- Unit tests for new QueryExecutor methods

**Out of scope (deferred):**
- Call graph tool (requires storing reference occurrences — future phase)
- Feature branch SCIP data
- Cross-repo symbol references (SCIP symbols are globally unique but queries are repo-scoped)
- Per-result precision annotations (opted for repo-level indicator)
- CLI wrapper and CI pipeline documentation (E3)
