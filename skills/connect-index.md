---
name: connect-index
description: Connect your current project to the Source Code Indexer MCP server. Detects your repo, verifies it's indexed, and configures Claude Code to use the indexer.
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

4. **Configure Claude Code**

Create `.claude/mcp_servers.json` in the project root:

```json
{
  "source-code-indexer": {
    "type": "sse",
    "url": "<indexer-url>/mcp"
  }
}
```

5. **Print usage guide**

Display:
```
Connected to source-code-indexer. Your repo "<name>" is indexed
(last updated: <time>, <file_count> files).

Try these:
- "What's the structure of this repo?"         → get_directory_tree
- "Find all classes implementing <Interface>"  → find_implementations
- "Search for <concept>"                       → search_code
- "Show me the <ClassName> class"              → get_symbol_detail
- "What imports <Module>?"                     → find_references
- "Is the index healthy?"                      → get_index_health
```
