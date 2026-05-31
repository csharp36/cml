---
name: connect-index
description: Connect your current project to the Source Code Indexer MCP server. Detects your repo, verifies it's indexed, checks sync status, and configures Claude Code to use the indexer.
---

## Steps

1. **Detect the repository**

Check if the current directory is a git repo:
- Run `git remote get-url origin` to find the remote URL
- If not a git repo, ask the user for the repository URL

2. **Find the indexer**

Check for indexer connection info in order:
- Environment variable `SOURCE_CODE_INDEXER_URL`
- File `~/.source-code-indexer/client.yaml` (read `indexerUrl` field)
- Ask the user for the indexer URL if neither is found

3. **Verify the repo is indexed**

Use the `get_repo_summary` tool to check if the repo is in the index:
- Extract the repo name from the URL (last path segment, without .git)
- Call the tool with that repo name
- If not found, inform the user and suggest they ask an admin to add it

4. **Check sync status**

Run `git rev-parse HEAD` to get the local HEAD SHA, and `git branch --show-current` to get the current branch name. Then call the `check_sync` tool:
- Pass the repo name, local SHA, and branch name
- If `status` is `in_sync`: continue silently (report in usage guide)
- If `status` is `out_of_sync`: warn the developer:
  ```
  Warning: Your local repo is out of sync with the index.
  Local SHA:   <local_sha>
  Indexed SHA: <indexed_sha> (indexed at <indexed_at>)
  Run 'git pull' to sync, or push your changes to trigger re-indexing.
  ```
- If `status` is `not_indexed`: inform the developer that indexing is still in progress

5. **Configure Claude Code**

Create `.claude/mcp_servers.json` in the project root:

```json
{
  "source-code-indexer": {
    "type": "http",
    "url": "<indexer-url>/mcp"
  }
}
```

6. **Print usage guide**

Display:
```
Connected to source-code-indexer. Your repo "<name>" is indexed
(last updated: <time>, <file_count> files, branch: <branch>).

Try these:
- "What's the structure of this repo?"         → get_directory_tree
- "Find all classes implementing <Interface>"  → find_implementations
- "Search for <concept>"                       → search_code
- "Show me the <ClassName> class"              → get_symbol_detail
- "What imports <Module>?"                     → find_references
- "Is the index healthy?"                      → get_index_health
- "Is my local repo in sync with the index?"   → check_sync
```

Note: The `branch` parameter on any tool accepts **any git ref** — a feature branch, a release **tag**, or a commit **SHA**. When on a feature branch (or to inspect a specific release/build), include `branch: "<ref>"` in subsequent tool calls so the index returns ref-aware results (CML faults the ref in on first query if it isn't already indexed).
