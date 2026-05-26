# Admin UI Design

**Date:** 2026-05-25
**Status:** Approved
**Scope:** React SPA dashboard for managing the Source Code Indexer via the existing Admin API

## 1. Goal

Build an internal admin dashboard that provides a browser-based interface to the 7 Admin API endpoints: health monitoring, repository management (add/delete/reindex), and event viewing with retry. Served as static files from the existing Javalin server — no separate deployment.

## 2. Architecture

```
Browser ──HTTP──▶ Javalin (port 8080)
                    ├── /admin/ui/*     → Static files (React SPA)
                    ├── /admin/health   → Admin API
                    ├── /admin/repos    → Admin API
                    ├── /admin/events   → Admin API
                    ├── /webhook        → Git hooks
                    └── /mcp            → MCP SSE
```

**Stack:** React (Vite) + TypeScript + shadcn/ui (Tailwind + Radix) + TanStack Query

**Project location:** `admin-ui/` directory at repo root, inside the existing Java project. Built to `admin-ui/dist/`, served by Javalin at `/admin/ui/*`.

**No CORS needed.** In production, same origin. In dev, Vite proxies `/admin/*` to `localhost:8080`.

## 3. Project Structure

```
admin-ui/
  ├── package.json
  ├── vite.config.ts
  ├── tsconfig.json
  ├── tailwind.config.js
  ├── postcss.config.js
  ├── index.html
  ├── src/
  │   ├── main.tsx
  │   ├── index.css
  │   ├── App.tsx               (tab layout + auth token state)
  │   ├── api.ts                (fetch wrapper with bearer token)
  │   ├── components/
  │   │   ├── DashboardTab.tsx
  │   │   ├── RepositoriesTab.tsx
  │   │   ├── EventsTab.tsx
  │   │   └── AddRepoDialog.tsx
  │   ├── lib/
  │   │   └── utils.ts          (shadcn/ui cn utility)
  │   └── __tests__/
  │       ├── api.test.ts
  │       ├── DashboardTab.test.tsx
  │       ├── RepositoriesTab.test.tsx
  │       └── EventsTab.test.tsx
  └── dist/                     (build output)
```

## 4. App Shell & Auth

**Token input:** On first load, the app shows a text field and "Connect" button for the admin bearer token. Token is stored in React state only — not persisted to localStorage (security: re-enter on refresh for an internal admin tool).

**Auth flow:**
- Token entered → stored in state → passed to all API calls via `api.ts`
- Any 401 response → clears token, shows input form again
- Any 503 response → shows "Admin API disabled — no token configured on server"

**Tab layout:** Once authenticated, renders 3 horizontal tabs using shadcn/ui Tabs component:
- **Dashboard** (default active)
- **Repositories**
- **Events**

Each tab is a self-contained component managing its own data fetching via TanStack Query.

## 5. api.ts — Fetch Wrapper

Thin wrapper around `fetch`:

```typescript
async function adminFetch<T>(path: string, options?: RequestInit): Promise<T>
```

- Prepends base URL (empty in production, proxied in dev)
- Adds `Authorization: Bearer <token>` header
- Adds `Content-Type: application/json` for POST/PUT
- Parses JSON response
- Throws typed errors: `UnauthorizedError` (401), `NotFoundError` (404), `ConflictError` (409), `ServiceUnavailableError` (503), `BadRequestError` (400)

Used by all TanStack Query hooks. The token is passed via a React context or closure.

## 6. Dashboard Tab

Data source: `GET /admin/health`, auto-refresh every 10 seconds.

**Top row — Summary cards:**
- Total Repositories (count from `repositories` array length)
- Total Pending Events (`totalPendingEvents`)
- Total Failed Events (`totalFailedEvents`) — red highlight if > 0

**Middle — Repository status table:**
| Column | Source |
|--------|--------|
| Name | `repo_name` |
| Last Indexed SHA | `last_indexed_sha` (truncated 7 chars) |
| Pending Events | `pending_events` |
| Failed Events | `failed_events` |

Rows with failed events highlighted.

**Bottom — Recent failures list:**
- Data from `recentFailures` array (last 10)
- Shows: repo name, error message, timestamp
- Informational only (no retry button here — retry is in the Events tab)

## 7. Repositories Tab

Data source: `GET /admin/repos`, auto-refresh every 5 seconds.

**Top:** "Add Repository" button opens `AddRepoDialog`.

**Table columns:**
| Column | Source |
|--------|--------|
| Name | `name` |
| URL | `url` |
| Branch | `branch` |
| Files | `fileCount` |
| Last Indexed | `lastIndexedAt` (formatted) |
| Status | `status` badge |

**Status badges:**
- `ready` — green
- `cloning` — blue, animated pulse
- `indexing` — yellow, animated pulse
- `error` — red

**Row actions:**
- **Reindex** button → calls `POST /admin/repos/:name/reindex`. Disabled while status is `cloning` or `indexing`.
- **Delete** button → confirmation dialog ("Are you sure? This deletes all indexed data and the clone.") → calls `DELETE /admin/repos/:name`.

### AddRepoDialog

Modal form with fields:
- **URL** (text input, required)
- **Branch** (text input, defaults to "main")
- **Auth Type** (select: `ssh-key`, `token`)
- **Auth properties** (dynamic based on type):
  - `ssh-key` → Key Path text input
  - `token` → Token text input

Submit calls `POST /admin/repos`. On 202: close dialog, list refreshes automatically. On 409: show "Repository already exists" error inline.

## 8. Events Tab

Data source: `GET /admin/events`, auto-refresh every 10 seconds.

**Filter bar** (horizontal row):
- Repository (text input, optional)
- Status (select: All, pending, processing, completed, failed)
- Since (date/time input, optional)
- Limit (number input, default 50)

Filters apply on change (debounced 300ms), updating TanStack Query params.

**Table columns:**
| Column | Source |
|--------|--------|
| ID | `id` |
| Repository | `repoName` |
| Event Type | `eventType` |
| Status | `status` badge |
| Error | `errorMessage` (truncated, tooltip on hover) |
| Created | `createdAt` (formatted) |

**Status badges:** Same color scheme as repos — gray (pending), blue (processing), green (completed), red (failed).

**Row actions:** Failed rows have a **Retry** button → calls `POST /admin/events/:id/retry`. On success, list refreshes.

No pagination — `limit` param caps results (default 50).

## 9. Static File Serving

**HttpServer.java change:** Add static file handler to serve `admin-ui/dist/` at `/admin/ui/*`.

Javalin supports this via:
```java
app.staticFiles.add(staticFiles -> {
    staticFiles.hostedPath = "/admin/ui";
    staticFiles.directory = "admin-ui/dist";
    staticFiles.location = Location.EXTERNAL;
});
```

The SPA needs a fallback: any request to `/admin/ui/*` that doesn't match a static file should serve `index.html` (for client-side routing, though we're using tabs not routes). Javalin can handle this with a `get("/admin/ui/*", ctx -> ctx.redirect("/admin/ui/"))` fallback or by configuring the static files handler appropriately.

**Vite base path:** Set `base: "/admin/ui/"` in `vite.config.ts` so all asset paths are correct when served from a subpath.

## 10. Build & Deployment

**Development:**
```bash
# Terminal 1: Java server
export DB_PASSWORD=changeme ADMIN_TOKEN=dev-token
./gradlew run

# Terminal 2: Vite dev server
cd admin-ui && npm run dev
# Opens at http://localhost:5173/admin/ui/
```

Vite proxies `/admin/*` to `http://localhost:8080`.

**Production build:**
```bash
cd admin-ui && npm ci && npm run build
# Output: admin-ui/dist/
```

**Docker:** Multi-stage build adds a Node stage:
```dockerfile
FROM node:20-alpine AS ui-build
WORKDIR /ui
COPY admin-ui/package*.json ./
RUN npm ci
COPY admin-ui/ ./
RUN npm run build

FROM eclipse-temurin:21-jre-alpine
# ... existing Java build ...
COPY --from=ui-build /ui/dist /app/admin-ui/dist
```

**docker-compose.yml:** No changes — UI served from existing `indexer` service.

## 11. Testing

**Unit tests (Vitest + Testing Library):**
- **`api.test.ts`** — Verify bearer token attachment, 401 handling, 503 handling, JSON parsing
- **`DashboardTab.test.tsx`** — Mock API, verify summary cards render counts, verify failures list
- **`RepositoriesTab.test.tsx`** — Mock API, verify table renders repos, verify delete confirmation, verify add form validation
- **`EventsTab.test.tsx`** — Mock API, verify filter controls, verify retry button

**Manual integration test:** Start Java server + Vite dev server, enter token, verify all tabs work against real API.

No E2E framework for this phase — internal admin tool, manual verification sufficient.

## 12. Files Changed

| File | Change |
|------|--------|
| `admin-ui/package.json` | **New.** |
| `admin-ui/vite.config.ts` | **New.** |
| `admin-ui/tsconfig.json` | **New.** |
| `admin-ui/tailwind.config.js` | **New.** |
| `admin-ui/postcss.config.js` | **New.** |
| `admin-ui/index.html` | **New.** |
| `admin-ui/src/main.tsx` | **New.** |
| `admin-ui/src/index.css` | **New.** |
| `admin-ui/src/App.tsx` | **New.** |
| `admin-ui/src/api.ts` | **New.** |
| `admin-ui/src/components/DashboardTab.tsx` | **New.** |
| `admin-ui/src/components/RepositoriesTab.tsx` | **New.** |
| `admin-ui/src/components/EventsTab.tsx` | **New.** |
| `admin-ui/src/components/AddRepoDialog.tsx` | **New.** |
| `admin-ui/src/lib/utils.ts` | **New.** |
| `admin-ui/src/__tests__/api.test.ts` | **New.** |
| `admin-ui/src/__tests__/DashboardTab.test.tsx` | **New.** |
| `admin-ui/src/__tests__/RepositoriesTab.test.tsx` | **New.** |
| `admin-ui/src/__tests__/EventsTab.test.tsx` | **New.** |
| `src/main/java/com/indexer/server/HttpServer.java` | **Modified.** Static file serving. |
| `Dockerfile` | **Modified.** Node build stage. |
| `CLAUDE.md` | **Modified.** Document Admin UI. |
