# Phase E1: SCIP Receiving + Storage — Design Spec

## Overview

Upload endpoint for receiving SCIP (Sourcegraph Code Intelligence Protocol) protobuf files from CI/CD pipelines. Parses type-resolved definitions and relationships into PostgreSQL for future semantic query tools. The indexer is a *receiver*, not a producer — build environments are the CI pipeline's concern.

**Depends on:** Phase B (Identity/AuthZ) for API key authentication, Phase C (Audit) for upload auditing.

**Gate:** A CI pipeline can upload a `.scip` file, the data is stored and validated, `get_index_health` reports SCIP staleness status, and the upload is audited.

**Future phases:** E2 (semantic query tools: get_call_graph, get_type_hierarchy, precision indicators) and E3 (CLI wrapper + docs) build on this.

---

## Data Model

### Flyway Migration V4

Two new tables plus columns on `repositories`.

**`scip_symbols`** — Type-resolved symbol definitions from SCIP:

| Column | Type | Notes |
|--------|------|-------|
| `id` | `SERIAL PRIMARY KEY` | |
| `repo_id` | `INT NOT NULL REFERENCES repositories(id)` | |
| `scip_symbol` | `TEXT NOT NULL` | SCIP globally unique symbol string |
| `display_name` | `VARCHAR(512)` | Human-readable name |
| `kind` | `VARCHAR(32)` | Method, Class, Interface, Field, etc. |
| `documentation` | `TEXT` | Extracted Javadoc/docstring |
| `file_path` | `VARCHAR(1024) NOT NULL` | File where defined |
| `start_line` | `INT` | Definition start |
| `end_line` | `INT` | Definition end |
| `upload_sha` | `VARCHAR(64) NOT NULL` | Git SHA this data was built from |
| `uploaded_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

Unique constraint: `(repo_id, scip_symbol)`.
Indexes: `(repo_id, file_path)`, `(repo_id, display_name)`.

**`scip_relationships`** — Type-resolved edges between SCIP symbols:

| Column | Type | Notes |
|--------|------|-------|
| `id` | `SERIAL PRIMARY KEY` | |
| `repo_id` | `INT NOT NULL REFERENCES repositories(id)` | |
| `from_symbol` | `TEXT NOT NULL` | SCIP symbol of caller/subtype |
| `to_symbol` | `TEXT NOT NULL` | SCIP symbol of callee/supertype |
| `kind` | `VARCHAR(32) NOT NULL` | calls, implements, extends, overrides, references |
| `file_path` | `VARCHAR(1024)` | Where the relationship occurs |
| `line` | `INT` | Line of the occurrence |

Indexes: `(repo_id, to_symbol, kind)`, `(repo_id, from_symbol, kind)`.

**`repositories` table additions:**

| Column | Type | Notes |
|--------|------|-------|
| `scip_sha` | `VARCHAR(64)` | Git SHA of last SCIP upload |
| `scip_uploaded_at` | `TIMESTAMPTZ` | When SCIP data was last uploaded |

---

## Protobuf Build Integration

**Gradle protobuf plugin** compiles `scip.proto` from Sourcegraph's canonical schema.

```kotlin
plugins {
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.29.3")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
}
```

Proto file placed at `src/main/proto/scip.proto`. Generated classes provide `com.sourcegraph.scip.Index`, `Document`, `Occurrence`, `SymbolInformation`, `Relationship`, etc.

---

## ScipParser

Extraction logic in `com.indexer.scip.ScipParser`.

**Input:** Parsed `Index` protobuf message.

**Extracts:**
1. **Symbols with definitions** — Walk each `Document`, find `Occurrence` entries with `SymbolRole.Definition` role. Map SCIP symbol string → file path + range.
2. **Symbol metadata** — From `SymbolInformation`: kind, documentation, display name.
3. **Relationships** — From `Relationship` messages on each `SymbolInformation`: calls, implements, extends, overrides.

**Output:** `ScipParseResult` record containing `List<ScipSymbolRow>` and `List<ScipRelationshipRow>` — flat structures ready for bulk insert.

**What we skip:** Reference occurrences (90%+ of SCIP data volume). Tree-sitter already handles structural references. The unique value of SCIP is type-resolved definitions and relationships.

---

## Upload Endpoint

**`POST /api/scip/{repoName}`**

**Request:**
- `Content-Type: application/x-protobuf`
- `Authorization: Bearer <api-key>` (key must have `scipUpload: true`)
- `X-Git-SHA: <sha>` — git commit SHA the SCIP data was built from
- Body: raw SCIP protobuf bytes

**Validation chain (fail-fast):**

1. **Auth** — API key with `scipUpload: true`. 401 if missing/invalid, 403 if key lacks permission.
2. **Repo exists** — `repoName` must match an indexed repository. 404 if not.
3. **Size limit** — Body max 50MB (configurable). 413 if exceeded.
4. **SHA header** — `X-Git-SHA` required. 400 if missing.
5. **Protobuf parse** — Deserialize as SCIP `Index`. 400 if malformed.
6. **Cross-ref check** — At least one document's `relative_path` must match a file in the repo's index. 422 if zero overlap.

**Processing (synchronous):**

1. Parse SCIP `Index`, extract symbols and relationships via `ScipParser`
2. Within a transaction: delete existing SCIP data for repo, bulk insert new data
3. Update `repositories.scip_sha` and `repositories.scip_uploaded_at`
4. Return 200 with summary JSON

**Response (200):**
```json
{
  "repo": "payments-api",
  "sha": "abc123",
  "symbols": 1247,
  "relationships": 3891,
  "documents_processed": 156
}
```

**Replacement semantics:** Each upload replaces all SCIP data for the repo. Delete-then-insert in one transaction. SCIP files represent complete semantic state at a SHA — no incremental merge.

---

## ScipApi

New class `com.indexer.scip.ScipApi` registered on HttpServer at `/api/scip/*`.

**Constructor:** `ScipApi(ApiKeyAuthenticator authenticator, RepositoryDao repositoryDao, ScipService scipService, AuditSink auditSink)`

**Auth pattern:** Reads `Authorization: Bearer` header, authenticates via `ApiKeyAuthenticator`, checks `caller.scipUpload()`. Same pattern as AdminApi but with a different permission flag.

**Route:** `POST /api/scip/{repoName}` → `handleUpload(Context ctx)`

---

## ScipService

Orchestrates the upload flow: validation, parsing, storage.

**Constructor:** `ScipService(RepositoryDao repositoryDao, FileDao fileDao, Jdbi jdbi)`

**`processUpload(String repoName, String gitSha, byte[] scipBytes)`:**
1. Look up repo by name (404 if not found)
2. Parse protobuf → `Index`
3. Cross-ref check: match document paths against indexed files
4. Extract via `ScipParser`
5. Transaction: delete old SCIP data, bulk insert, update repo columns
6. Return `ScipUploadResult` record

---

## Config Extension

**`ApiKeyEntry` gains `scipUpload` boolean:**

Same flow as `auditReader`:
- `IndexerConfig.McpAuthConfig.ApiKeyEntry` → add `boolean scipUpload`
- `ConfigLoader.parseMcpAuth` → parse `scipUpload` from YAML
- `ApiKeyAuthenticator.ApiKeyConfig` → add `boolean scipUpload`
- `CallerIdentity` → add `boolean scipUpload` field

Factory defaults: all false except none — SCIP upload is always opt-in via config.

```yaml
auth:
  apiKeys:
    - key: ${CI_UPLOAD_KEY}
      id: ci-pipeline
      name: CI Pipeline
      scipUpload: true
```

---

## Staleness Tracking

Determined by comparing `repositories.scip_sha` to `repositories.last_indexed_sha`:

| Condition | Status | Meaning |
|-----------|--------|---------|
| `scip_sha == last_indexed_sha` | fresh | Semantic matches structural |
| `scip_sha != last_indexed_sha` | stale | Semantic is behind |
| `scip_sha IS NULL` | unavailable | No SCIP data uploaded |

Exposed in `get_index_health` — add `scip_sha` and `scip_status` fields to per-repo health output.

---

## Error Handling

| Scenario | Response | Audit action |
|----------|----------|-------------|
| Invalid API key | 401 | scip:authFailure |
| Key lacks `scipUpload` | 403 | scip:upload denied |
| Repo not found | 404 | scip:upload error |
| Body exceeds size limit | 413 | scip:upload error |
| Missing X-Git-SHA | 400 | scip:upload error |
| Protobuf parse failure | 400 | scip:upload error |
| Zero file overlap | 422 | scip:upload error |
| DB insert failure | 500 | scip:upload error |
| Success | 200 | scip:upload success |

---

## Scope Boundaries

**In scope:**
- Flyway V4 migration (scip_symbols, scip_relationships, repositories columns)
- Gradle protobuf plugin + scip.proto compilation
- ScipParser (extract definitions + relationships)
- ScipService (orchestrate upload flow)
- ScipApi (HTTP endpoint with auth + audit)
- Config extension: `scipUpload` flag on API keys + CallerIdentity
- Staleness tracking in get_index_health
- Upload auditing

**Out of scope (deferred to E2/E3):**
- get_call_graph MCP tool
- get_type_hierarchy MCP tool
- Precision indicators on existing tools
- CLI wrapper script
- CI pipeline documentation/examples
- Feature branch SCIP data
- Incremental/delta SCIP uploads
