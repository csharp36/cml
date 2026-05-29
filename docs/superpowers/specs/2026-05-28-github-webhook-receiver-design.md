# GitHub Webhook Receiver — Design Spec

**Date:** 2026-05-28
**Status:** Approved (design)
**Topic:** Keep the main-branch index in sync with GitHub when changes merge to `main`.

## Problem

When a developer works locally, opens a PR, and merges to `main` on GitHub, the CML index
server is never told that `main` advanced. The next time the developer pulls `main`, their
local repo is ahead of the index — `check_sync` reports `out_of_sync` and query results are
stale until the next server restart (boot is currently the only thing that fetches and
catches `main` up).

The fetch-and-reindex machinery already exists: when any event lands in the PostgreSQL
queue, the poller (`Application` event handler) does, for a main-branch event,
`gitOps.fetch()` → `gitOps.fastForward()` (or `resetToRemote()` on force-push) →
`indexingPipeline.incrementalIndex()`. The only missing piece is a **trigger** that enqueues
an event when `main` changes on GitHub.

## Goal

Add a GitHub hosted webhook receiver that, on a verified push to a repo's configured branch,
enqueues an indexing event. The existing poller performs the actual fetch + incremental
index. No new indexing path is introduced.

## Non-Goals

- Feature-branch indexing on push (feature branches continue to index via the existing
  query-time fault-in path).
- `pull_request` event handling.
- Replacing or changing the existing `/webhook` endpoint used by locally installed git hooks.
- Polling or CI-triggered alternatives (considered and rejected for this deployment — see
  "Approaches considered").

## Decisions (locked)

| Decision | Choice |
|---|---|
| Deployment target | Shared / deployed CML server, reachable inbound from GitHub |
| Trigger mechanism | GitHub hosted webhook (`push` event) |
| Secret storage | **Per-repo** `webhookSecret` in config |
| Branch scope | The repo's **configured branch only** (e.g. `main`) |
| Response model | **202 Accepted, async** — verify + enqueue, poller does the work |
| Endpoint routing | **Per-repo path:** `POST /webhook/github/{repoName}` |
| Repo matching | By **repo name** (path param → `extractRepoName(url)`) |
| Missing secret | **Reject (401)** — fail-closed |
| `ping` / non-push events | Verify signature, return **200**, no-op |

## Approaches considered

- **A. GitHub hosted webhook (chosen).** GitHub POSTs a `push` event on merge-to-main.
  Event-driven and near-instant. Requires CML to be inbound-reachable from GitHub — satisfied
  by the shared-server deployment.
- **B. CI job calls CML.** A GitHub Action on push-to-main curls an authenticated CML
  endpoint. Reuses API-key auth but still needs inbound reachability and adds CI config.
- **C. Polling.** CML periodically fetches origin and enqueues on HEAD change. No inbound
  connectivity needed, but adds latency and a scheduled task. Best for localhost-only setups,
  which is not this deployment.

## Architecture

### Config & secret plumbing

Add an optional `webhookSecret` to each repository entry. Consistent with the project
convention that credentials live in config (with `${ENV}` substitution), never the database.

```yaml
repositories:
  - url: git@github.com:csharp36/cml.git
    branch: main
    auth:
      type: ssh-key
      keyPath: ~/.ssh/id_ed25519
    webhookSecret: ${CML_WEBHOOK_SECRET}   # new, optional
```

- `IndexerConfig.RepositoryConfig` gains a `webhookSecret` field:
  `(url, branch, auth, webhookSecret)`. The compact constructor leaves it nullable (optional).
- `ConfigLoader.parseRepository` reads `webhookSecret`. `${ENV}` is already resolved by the
  existing `resolveEnvVars` pass.
- At boot, build a `Map<String,String>` of `repoName → webhookSecret` keyed by
  `RepositoryManager.extractRepoName(url)`, and pass it to the HTTP layer. Repos without a
  secret are absent from the map → deliveries for them are rejected (fail-closed).

### Endpoint & request flow

New route: **`POST /webhook/github/{repoName}`**. The existing `/webhook` (local git hooks)
is untouched.

1. Resolve `{repoName}` → secret (config map) **and** clone path (`repositoryDao.findByName`).
   - Repo not known/configured → **404**.
   - Repo known but no secret in the map → **401** (fail-closed).
2. **Verify before parsing the body.** Read the **raw body bytes** (`ctx.bodyAsBytes()`).
   Compute HMAC-SHA256 over those bytes with the repo's secret and constant-time compare
   (`MessageDigest.isEqual`) against the `X-Hub-Signature-256` header (format
   `sha256=<hex>`).
   - Missing header → **401**. Mismatch → **401** (+ audit log).
3. Inspect `X-GitHub-Event`.
   - `ping` or any value other than `push` → **200**, no-op (signature already verified).
4. Parse the push payload (`ref`, `before`, `after`). Derive branch from `ref`
   (`refs/heads/<branch>`).
   - Branch != repo's configured branch → **200**, no-op.
   - `after` is all zeros (branch deletion) → **200**, no-op.
   - Malformed JSON → **400**.
5. Otherwise enqueue an event via `eventDao.insert(repoName, repoPath, eventType="github_push",
   previousSha=before, currentSha=after, branch)`, where `repoPath` is the repo's clone path
   from the DB lookup. Then call `eventDao.notifyNewEvent()` and return **202** with the event id.

The existing poller then processes the event: fetch → fast-forward (reset on force-push) →
incremental index. No new indexing code.

### Error matrix

| Condition | Response |
|---|---|
| Unknown / unconfigured repo in path | 404 |
| Repo has no `webhookSecret` configured | 401 (fail-closed) |
| Missing `X-Hub-Signature-256` | 401 |
| Signature mismatch | 401 (+ audit log) |
| `ping` or any non-`push` event | 200 (no-op) |
| Push to a non-configured branch | 200 (no-op) |
| Branch deletion (`after` = all zeros) | 200 (no-op) |
| Valid push to configured branch | 202 (enqueued) |
| Malformed JSON body | 400 |

## Components

Each unit has one clear purpose and a well-defined interface:

- **`GitHubWebhookVerifier`** — pure HMAC-SHA256 verification: `(rawBody: byte[], secret:
  String, signatureHeader: String) → boolean`. No I/O; constant-time compare. Fully
  unit-testable in isolation.
- **`GitHubPushPayload`** — Jackson DTO record (`ref`, `before`, `after`,
  nested `repository.name`), annotated `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **Handler** — `handleGitHubWebhook(Context)` method on `HttpServer`, mirroring the existing
  `handleWebhook`: routing param, lookups, verify, parse, enqueue. Holds the
  `repoName → secret` map and reuses `repositoryDao` / `eventDao`.

### Touched files

- `IndexerConfig.RepositoryConfig` — add `webhookSecret` field.
- `ConfigLoader.parseRepository` — parse `webhookSecret`.
- `Application` — build the `repoName → secret` map at boot; pass it into `HttpServer`.
- `HttpServer` — register `POST /webhook/github/{repoName}` and add `handleGitHubWebhook`;
  accept the secret map (and, optionally, an `AuditSink` for auth-failure logging,
  consistent with `ScipApi`).

## Security

- HMAC-SHA256 via `javax.crypto.Mac` ("HmacSHA256") keyed with the per-repo secret.
- Verification uses the **raw request bytes**, not a re-serialized body.
- Constant-time comparison (`MessageDigest.isEqual`) of the computed digest against the
  header value.
- Fail-closed throughout: no secret, missing header, or any verification failure → 401.
- Auth failures are logged via the existing `AuditSink` pattern (as in `ScipApi`), plus
  structured logging.
- The endpoint never reveals whether a repo exists vs. lacks a secret beyond the documented
  status codes; both unauthenticated outcomes return 401/404 without payload detail.

## Testing

- **Unit — `GitHubWebhookVerifierTest`:** valid signature passes; tampered body fails; wrong
  secret fails; missing header fails; malformed (`sha256=` prefix absent / non-hex) fails.
- **Integration (Javalin, mirroring `AdminIntegrationTest`):**
  - valid push to configured branch → 202 and an event row is enqueued;
  - `ping` event → 200, no event enqueued;
  - push to a non-configured branch → 200, no event;
  - unknown repo in path → 404;
  - configured repo without a secret → 401;
  - bad signature → 401.

## Documentation

Add a short "GitHub webhook" subsection to `README.md` and `CLAUDE.md`:

- The `webhookSecret` config field.
- GitHub setup: repo → Settings → Webhooks → Add webhook; Payload URL
  `https://<cml-host>/webhook/github/<repoName>`; Content type `application/json`;
  Secret = the value of `webhookSecret`; events = "Just the push event".
- Note that the receiver only acts on pushes to the repo's configured branch and returns
  202; indexing completes asynchronously via the event queue.

## Rollout / compatibility

- Purely additive: a new endpoint and an optional config field. Existing `/webhook`,
  the event queue, and the poller are unchanged.
- Repos without a `webhookSecret` are unaffected (their webhook deliveries, if any, are
  rejected; their indexing continues to rely on boot catch-up and query-time fault-in).
