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
