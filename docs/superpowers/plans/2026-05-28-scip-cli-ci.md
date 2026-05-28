# Phase E3: SCIP CLI Wrapper + CI Pipeline Guide Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a portable Bash script for generating and uploading SCIP data, plus copy-paste CI pipeline examples for GitHub Actions, GitLab CI, and generic CI.

**Architecture:** A single self-contained Bash script (`scripts/scip-upload.sh`) handles argument parsing, language detection, SCIP indexer invocation, and HTTP upload. A Markdown guide (`docs/ci-pipeline-guide.md`) provides ready-to-use CI snippets. No server-side changes.

**Tech Stack:** Bash, curl, git

**Spec:** `docs/superpowers/specs/2026-05-28-scip-cli-ci-design.md`

---

### Task 1: Create scip-upload.sh

**Files:**
- Create: `scripts/scip-upload.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
set -euo pipefail

# ------------------------------------------------------------------
# scip-upload.sh — Generate and upload SCIP data to the indexer
#
# Usage:
#   ./scripts/scip-upload.sh --server URL --repo NAME --api-key KEY
#   ./scripts/scip-upload.sh --scip-file path/to/index.scip ...
#
# Flags (also readable from env vars):
#   --server URL       SCIP_SERVER   Indexer base URL
#   --repo NAME        SCIP_REPO     Repository name in the indexer
#   --api-key KEY      SCIP_API_KEY  API key with scipUpload: true
#   --scip-file PATH                 Pre-built .scip file (skip generation)
#   --sha SHA                        Git SHA (default: git rev-parse HEAD)
#   --fail-on-error                  Exit non-zero on upload failure
# ------------------------------------------------------------------

SERVER="${SCIP_SERVER:-}"
REPO="${SCIP_REPO:-}"
API_KEY="${SCIP_API_KEY:-}"
SCIP_FILE=""
SHA=""
FAIL_ON_ERROR=false
GENERATED_FILE=""

usage() {
    echo "Usage: $0 --server URL --repo NAME --api-key KEY [--scip-file PATH] [--sha SHA] [--fail-on-error]"
    echo ""
    echo "Flags (also readable from env vars SCIP_SERVER, SCIP_REPO, SCIP_API_KEY):"
    echo "  --server URL        Indexer base URL (e.g., http://indexer:8080)"
    echo "  --repo NAME         Repository name as registered in the indexer"
    echo "  --api-key KEY       API key with scipUpload: true"
    echo "  --scip-file PATH    Pre-built .scip file (skips auto-generation)"
    echo "  --sha SHA           Git commit SHA (default: git rev-parse HEAD)"
    echo "  --fail-on-error     Exit non-zero on upload failure"
    exit 1
}

cleanup() {
    if [[ -n "$GENERATED_FILE" && -f "$GENERATED_FILE" ]]; then
        rm -f "$GENERATED_FILE"
    fi
}
trap cleanup EXIT

# ------------------------------------------------------------------
# Argument parsing
# ------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --server)     SERVER="$2"; shift 2 ;;
        --repo)       REPO="$2"; shift 2 ;;
        --api-key)    API_KEY="$2"; shift 2 ;;
        --scip-file)  SCIP_FILE="$2"; shift 2 ;;
        --sha)        SHA="$2"; shift 2 ;;
        --fail-on-error) FAIL_ON_ERROR=true; shift ;;
        -h|--help)    usage ;;
        *)            echo "Unknown flag: $1"; usage ;;
    esac
done

# ------------------------------------------------------------------
# Validation
# ------------------------------------------------------------------
if [[ -z "$SERVER" ]]; then
    echo "Error: --server or SCIP_SERVER is required"
    exit 1
fi
if [[ -z "$REPO" ]]; then
    echo "Error: --repo or SCIP_REPO is required"
    exit 1
fi
if [[ -z "$API_KEY" ]]; then
    echo "Error: --api-key or SCIP_API_KEY is required"
    exit 1
fi

if ! command -v curl &>/dev/null; then
    echo "Error: curl is required but not found"
    exit 1
fi

if [[ -z "$SHA" ]]; then
    if ! command -v git &>/dev/null; then
        echo "Error: git is required to determine HEAD SHA (or provide --sha)"
        exit 1
    fi
    SHA=$(git rev-parse HEAD)
fi

# Strip trailing slash from server URL
SERVER="${SERVER%/}"

# ------------------------------------------------------------------
# SCIP file: use provided or auto-generate
# ------------------------------------------------------------------
if [[ -n "$SCIP_FILE" ]]; then
    if [[ ! -f "$SCIP_FILE" ]]; then
        echo "Error: SCIP file not found: $SCIP_FILE"
        exit 1
    fi
    echo "Using pre-built SCIP file: $SCIP_FILE"
else
    # Auto-detect language
    LANG_DETECTED=""
    INDEXER_CMD=""

    if [[ -f "build.gradle" || -f "build.gradle.kts" || -f "pom.xml" ]]; then
        LANG_DETECTED="Java"
        INDEXER_CMD="scip-java"
    elif [[ -f "pyproject.toml" || -f "setup.py" || -f "requirements.txt" ]]; then
        LANG_DETECTED="Python"
        INDEXER_CMD="scip-python"
    elif [[ -f "tsconfig.json" ]]; then
        LANG_DETECTED="TypeScript"
        INDEXER_CMD="scip-typescript"
    fi

    if [[ -z "$LANG_DETECTED" ]]; then
        echo "Error: Could not detect project language."
        echo "Supported languages: Java (build.gradle/pom.xml), Python (pyproject.toml/setup.py/requirements.txt), TypeScript (tsconfig.json)"
        echo "Use --scip-file to upload a pre-built SCIP file for other languages."
        exit 1
    fi

    if ! command -v "$INDEXER_CMD" &>/dev/null; then
        echo "Error: $INDEXER_CMD is required for $LANG_DETECTED projects but not found on PATH."
        echo "Install it from: https://github.com/sourcegraph/scip-${LANG_DETECTED,,}"
        exit 1
    fi

    echo "Detected $LANG_DETECTED project, running $INDEXER_CMD..."
    if ! $INDEXER_CMD index --output index.scip; then
        echo "Error: $INDEXER_CMD failed"
        exit 1
    fi

    SCIP_FILE="index.scip"
    GENERATED_FILE="index.scip"

    if [[ ! -f "$SCIP_FILE" ]]; then
        echo "Error: $INDEXER_CMD did not produce index.scip"
        exit 1
    fi

    FILE_SIZE=$(wc -c < "$SCIP_FILE" | tr -d ' ')
    echo "Generated SCIP file: $SCIP_FILE ($FILE_SIZE bytes)"
fi

# ------------------------------------------------------------------
# Upload
# ------------------------------------------------------------------
echo "Uploading to $SERVER/api/scip/$REPO (sha: $SHA)..."

RESPONSE_FILE=$(mktemp)
HTTP_CODE=$(curl -s -w "%{http_code}" -o "$RESPONSE_FILE" \
    -X POST "${SERVER}/api/scip/${REPO}" \
    -H "Authorization: Bearer ${API_KEY}" \
    -H "X-Git-SHA: ${SHA}" \
    -H "Content-Type: application/x-protobuf" \
    --data-binary "@${SCIP_FILE}" \
    --max-time 120)

RESPONSE_BODY=$(cat "$RESPONSE_FILE")
rm -f "$RESPONSE_FILE"

if [[ "$HTTP_CODE" == "200" ]]; then
    echo "Upload successful!"
    echo "$RESPONSE_BODY" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE_BODY"
    exit 0
else
    echo "Upload failed (HTTP $HTTP_CODE)"
    echo "$RESPONSE_BODY" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE_BODY"
    if [[ "$FAIL_ON_ERROR" == "true" ]]; then
        exit 1
    else
        echo "Warning: SCIP upload failed but --fail-on-error is not set. Continuing."
        exit 0
    fi
fi
```

- [ ] **Step 2: Make executable**

Run: `chmod +x scripts/scip-upload.sh`

- [ ] **Step 3: Verify script syntax**

Run: `bash -n scripts/scip-upload.sh && echo "Syntax OK"`
Expected: `Syntax OK`

- [ ] **Step 4: Test help output**

Run: `./scripts/scip-upload.sh --help`
Expected: Usage message listing all flags.

- [ ] **Step 5: Test missing required args**

Run: `./scripts/scip-upload.sh 2>&1; echo "exit: $?"`
Expected: Error about missing `--server`, exit code 1.

- [ ] **Step 6: Commit**

```bash
git add scripts/scip-upload.sh
git commit -m "feat: add SCIP upload CLI wrapper script"
```

---

### Task 2: Create CI Pipeline Guide

**Files:**
- Create: `docs/ci-pipeline-guide.md`

- [ ] **Step 1: Write the guide**

```markdown
# CI Pipeline Guide — SCIP Upload

This guide shows how to add SCIP semantic indexing to your CI pipeline. After your build and tests pass, the pipeline generates a SCIP file and uploads it to the Source Code Indexer. This enables type-resolved queries (`get_type_hierarchy`, `get_symbol_references`) in Claude Code.

## Prerequisites

1. **The indexer is running** and your repository is registered (via `config.yaml` or the admin API).

2. **An API key with `scipUpload: true`** is configured:

   ```yaml
   # In the indexer's config.yaml
   auth:
     apiKeys:
       - key: ${CI_UPLOAD_KEY}
         id: ci-pipeline
         name: CI Pipeline
         scipUpload: true
   ```

3. **A SCIP indexer** is installed for your language:

   | Language | Indexer | Install |
   |----------|---------|---------|
   | Java | `scip-java` | `coursier install scip-java` |
   | Python | `scip-python` | `pip install scip-python` |
   | TypeScript | `scip-typescript` | `npm install -g @sourcegraph/scip-typescript` |

4. **The upload script** is in your repo at `scripts/scip-upload.sh` (copy from the indexer project).

## GitHub Actions

Add this step after your build/test steps:

```yaml
name: CI

on:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      # ... your existing build and test steps ...

      - name: Install SCIP indexer
        run: |
          # For Java:
          curl -fL https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz | gzip -d > cs && chmod +x cs
          ./cs install scip-java && rm cs
          echo "$HOME/.local/share/coursier/bin" >> $GITHUB_PATH
          # For Python: pip install scip-python
          # For TypeScript: npm install -g @sourcegraph/scip-typescript

      - name: Upload SCIP index
        run: ./scripts/scip-upload.sh
        env:
          SCIP_SERVER: ${{ secrets.SCIP_SERVER }}
          SCIP_REPO: ${{ github.event.repository.name }}
          SCIP_API_KEY: ${{ secrets.SCIP_API_KEY }}
```

**Setting up secrets:** Go to your repo's Settings > Secrets and variables > Actions, and add:
- `SCIP_SERVER` — your indexer URL (e.g., `https://indexer.internal:8080`)
- `SCIP_API_KEY` — the API key value

Upload failure does not block the pipeline by default. Add `--fail-on-error` if you want it to.

## GitLab CI

Add this stage to your `.gitlab-ci.yml`:

```yaml
stages:
  - build
  - test
  - scip-upload

# ... your existing build and test stages ...

scip-upload:
  stage: scip-upload
  image: ubuntu:22.04
  before_script:
    # For Java:
    - apt-get update && apt-get install -y curl git
    - curl -fL https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz | gzip -d > /usr/local/bin/cs && chmod +x /usr/local/bin/cs
    - cs install scip-java
    - export PATH="$HOME/.local/share/coursier/bin:$PATH"
    # For Python: pip install scip-python
    # For TypeScript: npm install -g @sourcegraph/scip-typescript
  script:
    - ./scripts/scip-upload.sh
  variables:
    SCIP_SERVER: $SCIP_SERVER
    SCIP_REPO: $CI_PROJECT_NAME
    SCIP_API_KEY: $SCIP_API_KEY
  allow_failure: true
  only:
    - main
```

**Setting up variables:** Go to your project's Settings > CI/CD > Variables, and add `SCIP_SERVER` and `SCIP_API_KEY` (mark the API key as masked).

## Generic CI (Jenkins, CircleCI, etc.)

For any CI system that can run Bash:

```bash
# 1. Install the SCIP indexer for your language (once per CI image)
# Java:   coursier install scip-java
# Python: pip install scip-python
# TypeScript: npm install -g @sourcegraph/scip-typescript

# 2. Run the upload script
export SCIP_SERVER="https://indexer.internal:8080"
export SCIP_REPO="your-repo-name"
export SCIP_API_KEY="your-api-key"
./scripts/scip-upload.sh

# Or with explicit flags:
./scripts/scip-upload.sh \
  --server https://indexer.internal:8080 \
  --repo your-repo-name \
  --api-key "$SCIP_API_KEY"
```

## Pre-Built SCIP Files

If your build system already produces a `.scip` file (e.g., via a Gradle plugin), skip auto-generation:

```bash
./scripts/scip-upload.sh --scip-file build/output/index.scip \
  --server https://indexer.internal:8080 \
  --repo your-repo-name \
  --api-key "$SCIP_API_KEY"
```

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `401 Unauthorized` | Invalid API key | Check `SCIP_API_KEY` matches the key in the indexer's `config.yaml` |
| `403 Forbidden` | Key lacks permission | Add `scipUpload: true` to the API key entry in `config.yaml` |
| `404 Not Found` | Repo not registered | Add the repo via admin API or `config.yaml` before uploading |
| `413 Payload Too Large` | SCIP file > 50MB | Exclude test/vendor directories from SCIP indexing |
| `422 Unprocessable Entity` | No file paths match | Ensure SCIP was generated from the same repo checkout the indexer has |
| `Could not detect project language` | No marker file found | Use `--scip-file` with a pre-built SCIP file |
| `scip-java not found` | Indexer not installed | Install: `coursier install scip-java` |
```

- [ ] **Step 2: Commit**

```bash
git add docs/ci-pipeline-guide.md
git commit -m "docs: add CI pipeline guide for SCIP upload"
```

---

### Task 3: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add SCIP CLI section**

After the SCIP Upload API section in `CLAUDE.md`, add:

```markdown
## SCIP CLI Wrapper

A portable Bash script for generating and uploading SCIP data from CI pipelines.

### Usage

```bash
# Auto-detect language and upload
./scripts/scip-upload.sh --server http://indexer:8080 --repo my-repo --api-key "$KEY"

# Upload a pre-built SCIP file
./scripts/scip-upload.sh --scip-file build/index.scip --server http://indexer:8080 --repo my-repo --api-key "$KEY"
```

Supports Java (`scip-java`), Python (`scip-python`), and TypeScript (`scip-typescript`) auto-detection. Use `--scip-file` for other languages.

See `docs/ci-pipeline-guide.md` for GitHub Actions, GitLab CI, and generic CI examples.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add SCIP CLI wrapper section to CLAUDE.md"
```

---

### Implementation Notes

**Task dependency chain:** Task 1 (script) is independent. Task 2 (CI guide) references the script path but doesn't depend on it existing. Task 3 (CLAUDE.md) is independent. All three can be done in any order.

**Script portability:** The script uses `#!/usr/bin/env bash` and `set -euo pipefail`. It avoids bashisms beyond what `set -euo pipefail` requires. The only non-POSIX dependency is `[[ ]]` test syntax, which is available in Bash 3+ (macOS default).

**JSON pretty-printing:** The script uses `python3 -m json.tool` as a best-effort JSON formatter for the upload response. If `python3` isn't available, the raw response is printed. This avoids adding `jq` as a dependency.

**SCIP indexer install commands:** The CI examples show language-specific install commands. These are current as of May 2025 but may change — the guide notes which indexer is needed, not just the install command.

**No automated tests for the script:** The spec explicitly marks this out of scope. The script is ~150 lines of straightforward Bash — manual verification with `bash -n` (syntax check), `--help`, and missing-arg tests is sufficient.
