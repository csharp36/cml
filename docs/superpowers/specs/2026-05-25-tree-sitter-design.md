# Tree-sitter Native Integration Design

**Date:** 2026-05-25
**Status:** Approved
**Scope:** Replace regex-based symbol extraction with Tree-sitter structural parsing for Java, Python, TypeScript/JavaScript, Go, and C

## 1. Goal

Replace the regex-based `SymbolExtractor` internals with Tree-sitter structural parsing for accurate, language-aware symbol extraction. The public interface (`List<ExtractedSymbol> extract(String source, String language)`) stays the same — downstream consumers (`FileIndexer`, MCP tools, admin API) are unaffected.

## 2. Architecture

```
FileIndexer
    │
    ▼
SymbolExtractor
    │
    ├── TreeSitterEngine (new)
    │     ├── TSParserPool (bounded pool of TSParser instances)
    │     ├── .scm query files per language (loaded once at startup)
    │     └── Maps TSQueryMatch captures → ExtractedSymbol
    │
    └── Regex fallback (existing code, for unsupported languages)
```

**Three new components:**

- **`TSParserPool`** (`com.indexer.indexing.treesitter`) — Bounded pool of `TSParser` instances backed by `ArrayBlockingQueue`. Thread-safe borrow/release. Pool size matches `indexWorkers` config. Implements `Closeable` for clean native resource shutdown.

- **`TreeSitterEngine`** (`com.indexer.indexing.treesitter`) — Core parsing engine. Loads `.scm` query files from classpath at startup, compiles them into `TSQuery` objects. Borrows parsers from the pool, parses source, runs queries, maps captures to `ExtractedSymbol` records. Public method: `List<ExtractedSymbol> parse(String source, String language)`.

- **`.scm` query files** — `src/main/resources/queries/{java,python,typescript,go,c}.scm`. Tree-sitter S-expression queries that capture declarations, imports, signatures, and type relationships per language.

**`SymbolExtractor` changes:** New constructor accepts `TreeSitterEngine`. `extract()` dispatches to Tree-sitter first, falls back to regex if the language isn't supported or parsing throws.

## 3. Library Choice

**`io.github.bonede:tree-sitter` (tree-sitter-ng) v0.26.6**

- Java 8+ compatible (works on Java 21)
- Pre-compiled native binaries embedded in JARs (macOS x86_64/arm64, Linux x86_64/arm64, Windows x86_64) — no C compilation required
- Full Tree-sitter query API (`TSQuery` + `TSQueryCursor`)
- Language grammars as separate Maven Central artifacts
- MIT license, actively maintained

**Thread safety:** `TSParser` and `TSQueryCursor` instances must not be shared across threads. The `TSParserPool` enforces this by lending one parser per thread at a time.

**Dependencies:**
```kotlin
val treeSitterVersion = "0.26.6"
implementation("io.github.bonede:tree-sitter:$treeSitterVersion")
implementation("io.github.bonede:tree-sitter-java:$treeSitterVersion")
implementation("io.github.bonede:tree-sitter-python:$treeSitterVersion")
implementation("io.github.bonede:tree-sitter-javascript:$treeSitterVersion")
implementation("io.github.bonede:tree-sitter-typescript:$treeSitterVersion")
implementation("io.github.bonede:tree-sitter-go:$treeSitterVersion")
implementation("io.github.bonede:tree-sitter-c:$treeSitterVersion")
```

Replaces the existing `com.github.jnr:jnr-ffi:2.2.16` dependency (no longer needed).

## 4. TSParserPool

Bounded pool using `ArrayBlockingQueue<TSParser>`:

- **Construction:** Takes `poolSize` (from `indexWorkers` config). Pre-creates that many `TSParser` instances.
- **`borrow()`:** `queue.poll(30, TimeUnit.SECONDS)`. Blocks up to 30 seconds if all parsers are in use. Throws `IllegalStateException` on timeout (prevents deadlocks from leaked parsers).
- **`release(TSParser)`:** Resets parser state and returns to the queue.
- **`close()`:** Drains the queue, calls `close()` on each `TSParser`. Implements `Closeable`.

Fixed size — never grows or shrinks. If all parsers are borrowed, callers block. This bounds native memory regardless of how many JVM threads exist.

`TSParser` instances are reusable across languages — `parser.setLanguage()` swaps the grammar cheaply without reallocating the parser.

## 5. TreeSitterEngine

### Initialization

Constructor takes `TSParserPool` and loads query files:

1. Scans classpath for `queries/{language}.scm` files
2. For each file found, pairs the query text with the corresponding `TSLanguage` instance (from the grammar JARs)
3. Stores as `Map<String, LanguageConfig>` where `LanguageConfig` holds the `TSLanguage` and compiled query text
4. Languages with missing or invalid `.scm` files are skipped with a warning log — regex fallback handles them

**Note on TSQuery lifecycle:** `TSQuery` objects hold native memory and are not thread-safe. They are created fresh per `parse()` call from the stored query text string, then closed after use. The query text (a Java String) is loaded once at startup and reused — this is cheap since `TSQuery` compilation from text is fast (~microseconds).

### `parse(String source, String language)` method

1. Look up `LanguageConfig`. Return `null` if unsupported (signals regex fallback).
2. Borrow `TSParser` from pool.
3. `parser.setLanguage(config.tsLanguage())`
4. `TSTree tree = parser.parseString(null, source)`
5. Create `TSQuery` from the language's query text and `TSLanguage`
6. Create `TSQueryCursor`, execute against tree root
7. Iterate matches, map captures to `ExtractedSymbol` records
8. Close tree, query, cursor. Release parser to pool (in `finally` block).
9. Return `List<ExtractedSymbol>`.

### Capture naming convention

Query patterns use these capture names:

| Capture | Purpose | Example |
|---------|---------|---------|
| `@symbol.name` | Symbol identifier node | `Calculator` |
| `@symbol.node` | Full declaration node (for line range) | Entire class body |
| `@import.path` | Import path text | `java.util.List` |
| `@parent.name` | Enclosing class/struct for methods | `Calculator` |
| `@superclass` | Extended class name | `BaseClass` |
| `@interface` | Implemented interface name | `Serializable` |
| `@visibility` | Visibility modifier | `public` |
| `@parameters` | Parameter list node | `(int a, int b)` |
| `@static` | Static modifier presence | `static` |

Each query pattern is preceded by a comment tag: `; kind: class`, `; kind: method`, `; kind: import`, etc. The engine reads these to set `ExtractedSymbol.kind`.

## 6. Query Files

### `queries/java.scm`

Captures:
- Import declarations (`import_declaration`)
- Class declarations with modifiers, superclass, interfaces (`class_declaration`)
- Interface declarations (`interface_declaration`)
- Enum declarations (`enum_declaration`)
- Method declarations with modifiers, parameters (`method_declaration`)
- Constructor declarations (`constructor_declaration`)

### `queries/python.scm`

Captures:
- Import statements (`import_statement`, `import_from_statement`)
- Class definitions with base classes (`class_definition`)
- Function definitions (`function_definition`)
- Decorated functions (`decorated_definition` wrapping `function_definition`)

### `queries/typescript.scm`

Captures:
- ES6 import declarations (`import_statement`)
- Class declarations with heritage (`class_declaration`)
- Interface declarations (`interface_declaration`)
- Function declarations (`function_declaration`)
- Method definitions within classes (`method_definition`)
- Exported arrow functions (`lexical_declaration` with `arrow_function`)

### `queries/go.scm`

Captures:
- Import declarations, single and grouped (`import_declaration`, `import_spec`)
- Struct type declarations (`type_declaration` with `struct_type`)
- Interface type declarations (`type_declaration` with `interface_type`)
- Function declarations (`function_declaration`)
- Method declarations with receiver (`method_declaration`)

### `queries/c.scm`

Captures:
- `#include` directives (`preproc_include`)
- Function definitions (`function_definition`)
- Struct declarations (`struct_specifier` with body)
- Typedef declarations (`type_definition`)
- Enum declarations (`enum_specifier`)

## 7. SymbolExtractor Changes

```java
// New constructor
public SymbolExtractor(TreeSitterEngine engine) {
    this.engine = engine;
}

// Existing no-arg constructor (backward compatible, used by tests)
public SymbolExtractor() {
    this.engine = null;
}

public List<ExtractedSymbol> extract(String source, String language) {
    if (engine != null) {
        try {
            List<ExtractedSymbol> result = engine.parse(source, language);
            if (result != null) return result;
            // null means unsupported language — fall through to regex
        } catch (Exception e) {
            log.warn("Tree-sitter parse failed for {}, falling back to regex: {}", language, e.getMessage());
        }
    }
    return extractWithRegex(source, language);
}
```

Existing regex code moves into `extractWithRegex()` (private method rename, no logic changes). All existing tests continue to pass via the no-arg constructor.

## 8. Application Wiring

In `Application.java`, after creating `SymbolExtractor` and before building `FileIndexer`:

```java
var parserPool = new TSParserPool(config.server().indexWorkers());
var treeSitterEngine = new TreeSitterEngine(parserPool);
var symbolExtractor = new SymbolExtractor(treeSitterEngine);
```

Shutdown hook closes the pool:
```java
if (parserPool != null) parserPool.close();
```

## 9. Adding New Languages

To add support for a new language (e.g., Rust):

1. Add Maven dependency: `implementation("io.github.bonede:tree-sitter-rust:0.26.6")`
2. Create `src/main/resources/queries/rust.scm` with Tree-sitter query patterns
3. Register file extensions in `LanguageRegistry` (`.rs` → `"rust"`)
4. `TreeSitterEngine` auto-discovers the new query file at startup

No code changes to `SymbolExtractor`, `FileIndexer`, or any other component.

## 10. Error Handling

- **Tree-sitter parse failure:** Log warning, fall back to regex. Never crash.
- **Query compilation failure:** Skip that language at startup, log warning. Regex handles it.
- **Pool exhaustion (all parsers borrowed for 30+ seconds):** `IllegalStateException` thrown. Caller (FileIndexer) catches and logs, file is skipped (existing behavior for indexing failures).
- **Native library not found:** Tree-sitter-ng auto-extracts from JAR. If extraction fails (e.g., read-only filesystem), `TreeSitterEngine` constructor fails and `SymbolExtractor` operates in regex-only mode.
- **Segfault in native code:** Mitigated by bounded pool (limits blast radius) and per-parser isolation (no shared state between threads).

## 11. Testing

### Unit Tests

- **`TSParserPoolTest`**: Borrow returns non-null, release makes parser available again, borrow on empty pool blocks, close drains all parsers. No database.

- **`TreeSitterEngineTest`**: Parse real source snippets for all 5 languages. Each test provides a short inline source string and asserts correct `ExtractedSymbol` output:
  - Java: class with method, implements, import
  - Python: class with base, function, import
  - TypeScript: class, function, ES6 import
  - Go: struct, function, method with receiver, import
  - C: function, struct, typedef, `#include`

### Existing Tests

- **`SymbolExtractorTest`**: Unchanged — exercises regex path via no-arg constructor. Serves as regression baseline.
- **`IntegrationSmokeTest`**: Validates full pipeline end-to-end with Tree-sitter after wiring in Application.java.

## 12. Files Changed

| File | Change |
|------|--------|
| `build.gradle.kts` | **Modified.** Replace JNR-FFI with tree-sitter-ng + 6 language grammar deps + tree-sitter-c. |
| `src/main/java/com/indexer/indexing/treesitter/TSParserPool.java` | **New.** Bounded parser pool. |
| `src/main/java/com/indexer/indexing/treesitter/TreeSitterEngine.java` | **New.** Query loading, parsing, capture mapping. |
| `src/main/java/com/indexer/indexing/SymbolExtractor.java` | **Modified.** New constructor, Tree-sitter dispatch, regex rename. |
| `src/main/java/com/indexer/config/LanguageRegistry.java` | **Modified.** Add `.c`/`.h` → `"c"`. |
| `src/main/java/com/indexer/Application.java` | **Modified.** Create pool + engine, pass to SymbolExtractor. |
| `src/main/resources/queries/java.scm` | **New.** |
| `src/main/resources/queries/python.scm` | **New.** |
| `src/main/resources/queries/typescript.scm` | **New.** |
| `src/main/resources/queries/go.scm` | **New.** |
| `src/main/resources/queries/c.scm` | **New.** |
| `src/test/java/com/indexer/indexing/treesitter/TSParserPoolTest.java` | **New.** |
| `src/test/java/com/indexer/indexing/treesitter/TreeSitterEngineTest.java` | **New.** |
| `CLAUDE.md` | **Modified.** Update tech stack, supported languages. |
