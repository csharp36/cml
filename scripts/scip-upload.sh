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
PARTS_DIR=""
SPLITTER_JAR="${SCIP_SPLITTER_JAR:-}"              # path to the indexer jar providing `scip-split`
MAX_PART_BYTES="${SCIP_MAX_PART_BYTES:-47185920}"  # 45 MiB — must stay under the 50 MB server cap

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
    if [[ -n "$PARTS_DIR" && -d "$PARTS_DIR" ]]; then
        rm -rf "$PARTS_DIR"
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
        --splitter-jar) SPLITTER_JAR="$2"; shift 2 ;;
        --max-part-bytes) MAX_PART_BYTES="$2"; shift 2 ;;
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
# Upload helpers
# ------------------------------------------------------------------
# POST one file; echo "HTTP_CODE" on the first line, response body on the rest.
post_file() {
    local url="$1" file="$2"
    local rf; rf=$(mktemp)
    local code
    code=$(curl -s -w "%{http_code}" -o "$rf" \
        -X POST "$url" \
        -H "Authorization: Bearer ${API_KEY}" \
        -H "X-Git-SHA: ${SHA}" \
        -H "Content-Type: application/x-protobuf" \
        --data-binary "@${file}" \
        --max-time 300)
    echo "$code"
    cat "$rf"
    rm -f "$rf"
}

# Extract a JSON string field via python3 (already a dependency of this script).
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }

FILE_BYTES=$(wc -c < "$SCIP_FILE" | tr -d ' ')

# ------------------------------------------------------------------
# Single-shot path (file fits under the part cap) — unchanged behavior
# ------------------------------------------------------------------
if [[ "$FILE_BYTES" -le "$MAX_PART_BYTES" ]]; then
    echo "Uploading to $SERVER/api/scip/$REPO (sha: $SHA, ${FILE_BYTES} bytes)..."
    RESPONSE_FILE=$(mktemp)
    HTTP_CODE=$(curl -s -w "%{http_code}" -o "$RESPONSE_FILE" \
        -X POST "${SERVER}/api/scip/${REPO}" \
        -H "Authorization: Bearer ${API_KEY}" \
        -H "X-Git-SHA: ${SHA}" \
        -H "Content-Type: application/x-protobuf" \
        --data-binary "@${SCIP_FILE}" \
        --max-time 300)
    RESPONSE_BODY=$(cat "$RESPONSE_FILE"); rm -f "$RESPONSE_FILE"
    if [[ "$HTTP_CODE" == "200" ]]; then
        echo "Upload successful!"
        echo "$RESPONSE_BODY" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE_BODY"
        exit 0
    fi
    echo "Upload failed (HTTP $HTTP_CODE)"
    echo "$RESPONSE_BODY" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE_BODY"
    [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
    echo "Warning: SCIP upload failed but --fail-on-error is not set. Continuing."
    exit 0
fi

# ------------------------------------------------------------------
# Multi-part path (file exceeds the part cap)
# ------------------------------------------------------------------
echo "SCIP file is ${FILE_BYTES} bytes (> ${MAX_PART_BYTES}); splitting into parts..."

if [[ -z "$SPLITTER_JAR" ]]; then
    echo "Error: file exceeds --max-part-bytes and no --splitter-jar / SCIP_SPLITTER_JAR provided."
    echo "Provide the indexer jar so the file can be split (java -jar <jar> scip-split ...)."
    [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
    exit 0
fi

PARTS_DIR=$(mktemp -d)
if ! java -jar "$SPLITTER_JAR" scip-split "$SCIP_FILE" --max-bytes "$MAX_PART_BYTES" --out "$PARTS_DIR"; then
    echo "Error: scip-split failed"
    [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
    exit 0
fi

PART_FILES=( "$PARTS_DIR"/part-*.scip )
NUM_PARTS=${#PART_FILES[@]}
echo "Split into ${NUM_PARTS} parts. Initializing upload session..."

INIT_OUT=$(curl -s -X POST "${SERVER}/api/scip/${REPO}/uploads" \
    -H "Authorization: Bearer ${API_KEY}" \
    -H "X-Git-SHA: ${SHA}" \
    -H "X-Scip-Parts: ${NUM_PARTS}" --max-time 60)
UPLOAD_ID=$(echo "$INIT_OUT" | json_field uploadId)
if [[ -z "$UPLOAD_ID" ]]; then
    echo "Error: failed to initialize upload session: $INIT_OUT"
    [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
    exit 0
fi
echo "Session ${UPLOAD_ID} open. Uploading ${NUM_PARTS} parts..."

PART_NUM=0
for pf in "${PART_FILES[@]}"; do
    PART_NUM=$((PART_NUM + 1))
    OUT=$(post_file "${SERVER}/api/scip/${REPO}/uploads/${UPLOAD_ID}/parts/${PART_NUM}" "$pf")
    CODE=$(echo "$OUT" | head -n1)
    if [[ "$CODE" != "200" ]]; then
        echo "Error: part ${PART_NUM}/${NUM_PARTS} failed (HTTP ${CODE}): $(echo "$OUT" | tail -n +2)"
        curl -s -X DELETE "${SERVER}/api/scip/${REPO}/uploads/${UPLOAD_ID}" \
            -H "Authorization: Bearer ${API_KEY}" --max-time 60 >/dev/null || true
        [[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
        exit 0
    fi
    echo "  part ${PART_NUM}/${NUM_PARTS} ok"
done

echo "Finalizing session ${UPLOAD_ID}..."
COMPLETE_FILE=$(mktemp)
COMPLETE_CODE=$(curl -s -w "%{http_code}" -o "$COMPLETE_FILE" \
    -X POST "${SERVER}/api/scip/${REPO}/uploads/${UPLOAD_ID}/complete" \
    -H "Authorization: Bearer ${API_KEY}" --max-time 120)
COMPLETE_BODY=$(cat "$COMPLETE_FILE"); rm -f "$COMPLETE_FILE"
if [[ "$COMPLETE_CODE" == "200" ]]; then
    echo "Multi-part upload successful!"
    echo "$COMPLETE_BODY" | python3 -m json.tool 2>/dev/null || echo "$COMPLETE_BODY"
    exit 0
fi
echo "Complete failed (HTTP $COMPLETE_CODE): $COMPLETE_BODY"
[[ "$FAIL_ON_ERROR" == "true" ]] && exit 1
echo "Warning: SCIP upload failed but --fail-on-error is not set. Continuing."
exit 0
