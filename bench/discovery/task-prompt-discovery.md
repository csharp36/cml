<!-- bench/discovery/task-prompt-discovery.md
     Placeholders substituted by run-discovery-one.sh: {{TASK}}, {{BRANCH_HINT}}, {{WORKTREE}} -->
You are exploring an unfamiliar large Java codebase (Hazelcast) checked out at `{{WORKTREE}}`.

A change is requested:

> {{TASK}}

Your job is **discovery only**. Do NOT implement the change. Do NOT edit any source file.
Investigate the codebase and determine exactly which source files and which symbols
(classes / methods) a developer would need to modify to make this change.

{{BRANCH_HINT}}

When you are confident, write your answer to a file named `ANSWER.json` in the current
working directory, with this exact shape (repo-relative file paths; symbols as
`fully.qualified.ClassName#method`, the `#method` part optional for class-level):

```json
{ "files": ["hazelcast/src/main/java/.../SomeClass.java"],
  "symbols": ["com.hazelcast.x.SomeClass#someMethod"] }
```

Write `ANSWER.json` once, then stop. Your answer is graded on whether the files/symbols
match the actual change surface — favor precision and completeness over guessing.
