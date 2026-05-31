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

## Any size — one unified flow

The server caps a single upload request at 50 MB, and large repositories can produce much bigger
SCIP indexes (a full index for a large Java monorepo can exceed 480 MB). **You do not need
size-specific CI logic.** `scip-upload.sh` checks the file size and branches itself: files at or
under the part cap upload single-shot; larger files are split at `Document` boundaries into valid
sub-indexes and uploaded through the multi-part session API. The split parts stage invisibly and are
promoted atomically on completion, so a failed run never leaves a partially-typed commit visible to
queries.

The one requirement for size-independence: **always make the splitter jar available** so the large
path can fire when needed. Passing it is harmless for small files (the single-shot path never
references it), so set it unconditionally:

```bash
./scripts/scip-upload.sh --scip-file build/index.scip \
  --server https://indexer.internal:8080 \
  --repo your-repo-name \
  --api-key "$SCIP_API_KEY" \
  --splitter-jar /opt/indexer/indexer.jar    # or set SCIP_SPLITTER_JAR
```

Build the jar with `./gradlew shadowJar` (→ `build/libs/indexer.jar`), download it as a pinned
release asset, or use the indexer container image. cml's own `.github/workflows/scip-upload.yml`
demonstrates this unified setup: it builds the jar with `shadowJar` and exports `SCIP_SPLITTER_JAR`,
so the same upload step works whether the index is 5 MB or 500 MB.

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
