# Phase E3: SCIP CLI Wrapper + CI Pipeline Guide — Design Spec

## Overview

A portable Bash script for generating and uploading SCIP data to the indexer, plus copy-paste CI pipeline examples. No server-side changes — this is purely client-side tooling and documentation.

**Depends on:** Phase E1 (SCIP upload endpoint at `POST /api/scip/{repoName}`).

**Gate:** A developer can add SCIP upload to their CI pipeline in under 10 minutes using the script and docs.

---

## Deliverables

| File | Purpose |
|------|---------|
| `scripts/scip-upload.sh` | Portable Bash script: detect language, run SCIP indexer, upload to server |
| `docs/ci-pipeline-guide.md` | Copy-paste CI examples for GitHub Actions, GitLab CI, and generic CI |

---

## scripts/scip-upload.sh

### Usage

```bash
# Auto-detect language and upload
./scripts/scip-upload.sh --server http://indexer:8080 --repo payments-api --api-key "$KEY"

# Upload a pre-built SCIP file
./scripts/scip-upload.sh --server http://indexer:8080 --repo payments-api --api-key "$KEY" --scip-file build/index.scip

# All flags also readable from env vars
SCIP_SERVER=http://indexer:8080 SCIP_REPO=payments-api SCIP_API_KEY="$KEY" ./scripts/scip-upload.sh
```

### Flags

| Flag | Env var | Required | Default | Description |
|------|---------|----------|---------|-------------|
| `--server` | `SCIP_SERVER` | yes | — | Indexer base URL (e.g., `http://indexer:8080`) |
| `--repo` | `SCIP_REPO` | yes | — | Repository name as registered in the indexer |
| `--api-key` | `SCIP_API_KEY` | yes | — | API key with `scipUpload: true` |
| `--scip-file` | — | no | auto-generate | Path to pre-built `.scip` file (skips generation) |
| `--sha` | — | no | `git rev-parse HEAD` | Git SHA to tag the upload with |
| `--fail-on-error` | — | no | false | Exit non-zero on upload failure |

### Language Detection

When `--scip-file` is not provided, the script auto-detects the project language and runs the corresponding SCIP indexer. Detection is first-match from the current working directory:

| Marker files | Language | Indexer command |
|-------------|----------|----------------|
| `build.gradle`, `build.gradle.kts`, or `pom.xml` | Java | `scip-java` |
| `pyproject.toml`, `setup.py`, or `requirements.txt` | Python | `scip-python` |
| `tsconfig.json` | TypeScript | `scip-typescript` |

If no marker is found, the script exits with an error listing supported languages and the `--scip-file` fallback.

### SCIP Indexer Invocation

Each indexer is called with its standard CLI to produce an `index.scip` file in the current directory:

- **Java:** `scip-java index --output index.scip`
- **Python:** `scip-python index --output index.scip`
- **TypeScript:** `scip-typescript index --output index.scip`

If the indexer binary is not found on `$PATH`, the script exits with an actionable error message including install instructions.

### Upload

```bash
curl -s -w "%{http_code}" -o response.json \
  -X POST "${SERVER}/api/scip/${REPO}" \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "X-Git-SHA: ${SHA}" \
  -H "Content-Type: application/x-protobuf" \
  --data-binary @index.scip
```

On success (HTTP 200): print summary from the JSON response (symbols, relationships, documents processed).

On failure: print the HTTP status code and error message from the response body.

### Exit Behavior

By default, upload failures print a warning but exit 0. SCIP upload should never block CI — the code already built and tests already passed. The `--fail-on-error` flag overrides this to exit non-zero.

Generation failures (indexer not found, indexer crashes) always exit non-zero regardless of the flag — if the user configured SCIP generation, they want to know if it broke.

### Dependencies

The script checks for required tools at startup:
- `curl` — required always
- `git` — required unless `--sha` is provided
- `scip-java`, `scip-python`, or `scip-typescript` — required unless `--scip-file` is provided

Missing dependencies produce clear error messages with install hints.

### Structure

The script is a single self-contained Bash file (~150 lines). No external dependencies beyond the SCIP indexers themselves. Structure:

1. Argument parsing (flags + env var fallbacks)
2. Validation (required params, dependency checks)
3. Language detection (if no `--scip-file`)
4. SCIP generation (run indexer)
5. Upload (curl)
6. Result reporting (success/failure)
7. Cleanup (remove generated `index.scip` if script generated it)

---

## docs/ci-pipeline-guide.md

### Structure

1. **Prerequisites** — what to install, how to configure an API key with `scipUpload: true`
2. **GitHub Actions** — complete workflow step with secrets setup instructions
3. **GitLab CI** — complete stage with variables setup, `allow_failure: true`
4. **Generic CI** — shell commands for Jenkins, CircleCI, or any CI that runs Bash
5. **Pre-built SCIP files** — how to use `--scip-file` when the build system already produces SCIP output
6. **Troubleshooting** — common errors mapped to fixes

### CI Examples

Each example shows:
- How to install the SCIP indexer for the project's language
- How to invoke `scripts/scip-upload.sh` with the right env vars
- How to set up secrets/variables in the CI platform
- A note that upload failure doesn't block the pipeline by default

### Troubleshooting Section

| Error | Cause | Fix |
|-------|-------|-----|
| 401 Unauthorized | Bad API key | Check `SCIP_API_KEY` value and key config in server's `config.yaml` |
| 403 Forbidden | Key lacks `scipUpload` permission | Add `scipUpload: true` to the key in `config.yaml` |
| 404 Not Found | Repo not registered | Add repo via admin API or config before uploading SCIP data |
| 413 Payload Too Large | SCIP file exceeds 50MB limit | Exclude test/vendor files from SCIP indexing |
| 422 Unprocessable Entity | No SCIP document paths match indexed files | Ensure SCIP was generated from the correct repo checkout |

---

## Scope Boundaries

**In scope:**
- `scripts/scip-upload.sh` with language detection, generation, upload, and error handling
- `docs/ci-pipeline-guide.md` with GitHub Actions, GitLab CI, and generic examples
- CLAUDE.md updates referencing the new script and docs

**Out of scope:**
- Go or C language support in the CLI wrapper (easy to add later)
- Docker image for the CLI wrapper
- Platform-level tooling for managing SCIP across many repos
- Server-side changes
- Automated testing of the shell script (manual verification is sufficient for a ~150-line script)
