# Tree-sitter Native Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace regex-based symbol extraction with Tree-sitter structural parsing for Java, Python, TypeScript/JavaScript, Go, and C — same `ExtractedSymbol` output, dramatically better accuracy.

**Architecture:** `TreeSitterEngine` loads `.scm` query files per language, borrows parsers from a bounded `TSParserPool`, runs queries against parsed ASTs, and maps captures to `ExtractedSymbol` records. `SymbolExtractor` dispatches to Tree-sitter first, falls back to regex for unsupported languages.

**Tech Stack:** tree-sitter-ng (`io.github.bonede:tree-sitter:0.26.6`), pre-compiled native libs in JARs, Java 21

**Spec:** `docs/superpowers/specs/2026-05-25-tree-sitter-design.md`

---

## File Structure

| File | Role |
|------|------|
| `build.gradle.kts` | **Modified.** Replace JNR-FFI with tree-sitter-ng + 6 grammar deps. |
| `src/main/java/com/indexer/indexing/treesitter/TSParserPool.java` | **New.** Bounded pool of native TSParser instances. |
| `src/main/java/com/indexer/indexing/treesitter/TreeSitterEngine.java` | **New.** Loads queries, parses source, maps captures to ExtractedSymbol. |
| `src/main/java/com/indexer/indexing/SymbolExtractor.java` | **Modified.** New constructor, Tree-sitter dispatch, regex fallback. |
| `src/main/java/com/indexer/config/LanguageRegistry.java` | **Modified.** Add `"c"` to `CORE_LANGUAGES`. |
| `src/main/java/com/indexer/Application.java` | **Modified.** Create pool + engine, pass to SymbolExtractor, close on shutdown. |
| `src/main/resources/queries/java.scm` | **New.** |
| `src/main/resources/queries/python.scm` | **New.** |
| `src/main/resources/queries/typescript.scm` | **New.** |
| `src/main/resources/queries/go.scm` | **New.** |
| `src/main/resources/queries/c.scm` | **New.** |
| `src/test/java/com/indexer/indexing/treesitter/TSParserPoolTest.java` | **New.** |
| `src/test/java/com/indexer/indexing/treesitter/TreeSitterEngineTest.java` | **New.** |
| `CLAUDE.md` | **Modified.** |

---

## Tree-sitter-ng API Reference

The library package is `org.treesitter`. Key classes:

```java
import org.treesitter.*;

// Language (from grammar JARs)
TSLanguage java = new TreeSitterJava();

// Parser
TSParser parser = new TSParser();
parser.setLanguage(java);
TSTree tree = parser.parseString(null, sourceCode);
TSNode root = tree.getRootNode();

// Query
TSQuery query = new TSQuery(java, queryString);
TSQueryCursor cursor = new TSQueryCursor();
cursor.exec(query, root);
TSQueryMatch match = new TSQueryMatch();
while (cursor.nextMatch(match)) {
    for (TSQueryCapture capture : match.getCaptures()) {
        String captureName = query.getCaptureNameForId(capture.getIndex());
        TSNode node = capture.getNode();
        String text = source.substring(node.getStartByte(), node.getEndByte());
        int startLine = node.getStartPoint().getRow() + 1; // 0-indexed → 1-indexed
        int endLine = node.getEndPoint().getRow() + 1;
    }
}

// Cleanup
cursor.close();
query.close();
tree.close();
```

**IMPORTANT:** Verify exact API method names against the actual library. The tree-sitter-ng API wraps the C API closely but Java method names may differ slightly (e.g., `parseString` vs `parse`). Read the library source or use IDE auto-complete to confirm.

---

## Capture Convention

Each `.scm` query uses standardized capture names. The `TreeSitterEngine` maps them to `ExtractedSymbol` fields:

| Capture | ExtractedSymbol field | Notes |
|---------|----------------------|-------|
| `@name` | `name` (text content) | Required for every pattern |
| `@node` | `startLine`, `endLine` (line range) | Required for every pattern |
| `@superclass` | `relationships.add(extends)` | Optional |
| `@interface` | `relationships.add(implements)` | Optional |
| `@visibility` | `visibility` (text content) | Optional |
| `@parameters` | `signature` (text content) | Optional |
| `@path` | `name` (for imports only) | Alternative to `@name` for imports |

The **kind** (class, method, import, etc.) is determined by the `@node` capture's Tree-sitter node type. The engine maps node types to kinds per language:

```java
// Java: "class_declaration" → "class", "method_declaration" → "method", etc.
```

This avoids fragile comment-parsing in `.scm` files.

---

### Task 1: Update Build Dependencies

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Replace JNR-FFI with tree-sitter-ng dependencies**

In `build.gradle.kts`, replace:
```kotlin
    // Tree-sitter via JNR-FFI
    implementation("com.github.jnr:jnr-ffi:2.2.16")
```

With:
```kotlin
    // Tree-sitter native parsing
    val treeSitterVersion = "0.26.6"
    implementation("io.github.bonede:tree-sitter:$treeSitterVersion")
    implementation("io.github.bonede:tree-sitter-java:$treeSitterVersion")
    implementation("io.github.bonede:tree-sitter-python:$treeSitterVersion")
    implementation("io.github.bonede:tree-sitter-javascript:$treeSitterVersion")
    implementation("io.github.bonede:tree-sitter-typescript:$treeSitterVersion")
    implementation("io.github.bonede:tree-sitter-go:$treeSitterVersion")
    implementation("io.github.bonede:tree-sitter-c:$treeSitterVersion")
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `./gradlew dependencies --configuration runtimeClasspath 2>&1 | grep bonede | head -10`
Expected: All 7 tree-sitter artifacts resolve. No JNR-FFI in the output.

- [ ] **Step 3: Verify nothing else depends on JNR-FFI**

Run: `grep -r "jnr" src/ --include="*.java"`
Expected: No results — no source code imports JNR-FFI.

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -m "build: replace JNR-FFI with tree-sitter-ng 0.26.6 and language grammars"
```

---

### Task 2: TSParserPool

**Files:**
- Create: `src/test/java/com/indexer/indexing/treesitter/TSParserPoolTest.java`
- Create: `src/main/java/com/indexer/indexing/treesitter/TSParserPool.java`

- [ ] **Step 1: Write TSParserPoolTest**

Create `src/test/java/com/indexer/indexing/treesitter/TSParserPoolTest.java`:

```java
package com.indexer.indexing.treesitter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.treesitter.TSParser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TSParserPoolTest {

    private TSParserPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) pool.close();
    }

    @Test
    void borrowReturnsNonNullParser() {
        pool = new TSParserPool(2);
        TSParser parser = pool.borrow();
        assertThat(parser).isNotNull();
        pool.release(parser);
    }

    @Test
    void releaseAllowsReuse() {
        pool = new TSParserPool(1);
        TSParser first = pool.borrow();
        pool.release(first);
        TSParser second = pool.borrow();
        assertThat(second).isNotNull();
        pool.release(second);
    }

    @Test
    void borrowBlocksWhenPoolExhausted() throws Exception {
        pool = new TSParserPool(1);
        TSParser only = pool.borrow();

        AtomicBoolean timedOut = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Thread blocker = new Thread(() -> {
            started.countDown();
            try {
                // Pool has 1 parser, already borrowed — this should block
                pool.borrow();
            } catch (IllegalStateException e) {
                timedOut.set(true);
            }
        });
        blocker.start();
        started.await();

        // Give the thread time to block, then release
        Thread.sleep(100);
        pool.release(only);
        blocker.join(5000);
        assertThat(timedOut.get()).isFalse();
    }

    @Test
    void closeReleasesAllParsers() {
        pool = new TSParserPool(3);
        pool.close();
        // After close, borrow should fail
        assertThatThrownBy(() -> pool.borrow())
                .isInstanceOf(IllegalStateException.class);
        pool = null; // prevent double-close in tearDown
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.indexing.treesitter.TSParserPoolTest" 2>&1 | tail -20`
Expected: Compilation failure — `TSParserPool` doesn't exist.

- [ ] **Step 3: Implement TSParserPool**

Create `src/main/java/com/indexer/indexing/treesitter/TSParserPool.java`:

```java
package com.indexer.indexing.treesitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSParser;

import java.io.Closeable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded pool of TSParser instances for thread-safe Tree-sitter parsing.
 * Parsers are reusable across languages via setLanguage().
 */
public class TSParserPool implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(TSParserPool.class);
    private static final long BORROW_TIMEOUT_SECONDS = 30;

    private final ArrayBlockingQueue<TSParser> pool;
    private volatile boolean closed = false;

    public TSParserPool(int size) {
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.offer(new TSParser());
        }
        log.info("TSParserPool initialized with {} parsers", size);
    }

    public TSParser borrow() {
        if (closed) {
            throw new IllegalStateException("TSParserPool is closed");
        }
        try {
            TSParser parser = pool.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (parser == null) {
                throw new IllegalStateException(
                        "Timed out waiting for TSParser after " + BORROW_TIMEOUT_SECONDS + "s — possible parser leak");
            }
            return parser;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for TSParser", e);
        }
    }

    public void release(TSParser parser) {
        if (parser != null && !closed) {
            pool.offer(parser);
        }
    }

    @Override
    public void close() {
        closed = true;
        TSParser parser;
        while ((parser = pool.poll()) != null) {
            try {
                parser.close();
            } catch (Exception e) {
                log.debug("Error closing TSParser: {}", e.getMessage());
            }
        }
        log.info("TSParserPool closed");
    }
}
```

**Note:** Verify that `TSParser` has a `close()` method. If not, the cleanup in `close()` may just be discarding references and letting the native finalizer handle it.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.indexer.indexing.treesitter.TSParserPoolTest" 2>&1 | tail -20`
Expected: All 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/indexing/treesitter/TSParserPool.java \
       src/test/java/com/indexer/indexing/treesitter/TSParserPoolTest.java
git commit -m "feat: add TSParserPool — bounded pool of native Tree-sitter parsers"
```

---

### Task 3: Tree-sitter Query Files

**Files:**
- Create: `src/main/resources/queries/java.scm`
- Create: `src/main/resources/queries/python.scm`
- Create: `src/main/resources/queries/typescript.scm`
- Create: `src/main/resources/queries/go.scm`
- Create: `src/main/resources/queries/c.scm`

- [ ] **Step 1: Create queries directory**

Run: `mkdir -p src/main/resources/queries`

- [ ] **Step 2: Create java.scm**

Create `src/main/resources/queries/java.scm`:

```scm
;; Imports
(import_declaration
  (scoped_identifier) @path) @node

;; Class declarations
(class_declaration
  name: (identifier) @name
  superclass: (superclass (type_identifier) @superclass)?
  interfaces: (super_interfaces (type_list (type_identifier) @interface))?
) @node

;; Interface declarations
(interface_declaration
  name: (identifier) @name) @node

;; Enum declarations
(enum_declaration
  name: (identifier) @name) @node

;; Method declarations (inside class bodies)
(class_declaration
  name: (identifier) @parent
  body: (class_body
    (method_declaration
      name: (identifier) @name
      parameters: (formal_parameters) @parameters) @node))

;; Constructor declarations
(class_declaration
  name: (identifier) @parent
  body: (class_body
    (constructor_declaration
      name: (identifier) @name
      parameters: (formal_parameters) @parameters) @node))
```

**IMPORTANT:** These queries are based on the standard tree-sitter-java grammar. If a query fails to compile at runtime, check the actual node types by parsing a Java file and printing the S-expression: `tree.getRootNode().toString()`. Adjust node names accordingly.

- [ ] **Step 3: Create python.scm**

Create `src/main/resources/queries/python.scm`:

```scm
;; Import statements
(import_statement
  name: (dotted_name) @path) @node

;; From imports
(import_from_statement
  module_name: (dotted_name) @path) @node

;; Class definitions
(class_definition
  name: (identifier) @name
  superclasses: (argument_list (identifier) @superclass)?
) @node

;; Function definitions (top-level)
(module
  (function_definition
    name: (identifier) @name
    parameters: (parameters) @parameters) @node)

;; Method definitions (inside class)
(class_definition
  name: (identifier) @parent
  body: (block
    (function_definition
      name: (identifier) @name
      parameters: (parameters) @parameters) @node))

;; Decorated functions
(decorated_definition
  (function_definition
    name: (identifier) @name
    parameters: (parameters) @parameters) @node)
```

- [ ] **Step 4: Create typescript.scm**

Create `src/main/resources/queries/typescript.scm`:

```scm
;; Import declarations
(import_statement
  source: (string) @path) @node

;; Class declarations
(class_declaration
  name: (type_identifier) @name) @node

;; Interface declarations (TypeScript)
(interface_declaration
  name: (type_identifier) @name) @node

;; Function declarations
(function_declaration
  name: (identifier) @name
  parameters: (formal_parameters) @parameters) @node

;; Method definitions inside class
(class_declaration
  name: (type_identifier) @parent
  body: (class_body
    (method_definition
      name: (property_identifier) @name
      parameters: (formal_parameters) @parameters) @node))

;; Exported arrow functions
(export_statement
  (lexical_declaration
    (variable_declarator
      name: (identifier) @name
      value: (arrow_function
        parameters: (formal_parameters) @parameters)) @node))
```

- [ ] **Step 5: Create go.scm**

Create `src/main/resources/queries/go.scm`:

```scm
;; Single import
(import_declaration
  (import_spec
    path: (interpreted_string_literal) @path) @node)

;; Grouped imports
(import_declaration
  (import_spec_list
    (import_spec
      path: (interpreted_string_literal) @path) @node))

;; Struct type declarations
(type_declaration
  (type_spec
    name: (type_identifier) @name
    type: (struct_type)) @node)

;; Interface type declarations
(type_declaration
  (type_spec
    name: (type_identifier) @name
    type: (interface_type)) @node)

;; Function declarations
(function_declaration
  name: (identifier) @name
  parameters: (parameter_list) @parameters) @node

;; Method declarations (with receiver)
(method_declaration
  receiver: (parameter_list
    (parameter_declaration
      type: (_) @parent))
  name: (field_identifier) @name
  parameters: (parameter_list) @parameters) @node
```

- [ ] **Step 6: Create c.scm**

Create `src/main/resources/queries/c.scm`:

```scm
;; Include directives
(preproc_include
  path: (_) @path) @node

;; Function definitions
(function_definition
  declarator: (function_declarator
    declarator: (identifier) @name
    parameters: (parameter_list) @parameters)) @node

;; Struct declarations (with body)
(struct_specifier
  name: (type_identifier) @name
  body: (field_declaration_list)) @node

;; Enum declarations
(enum_specifier
  name: (type_identifier) @name) @node

;; Typedef declarations
(type_definition
  declarator: (type_identifier) @name) @node
```

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/queries/
git commit -m "feat: add Tree-sitter .scm query files for Java, Python, TypeScript, Go, C"
```

---

### Task 4: TreeSitterEngine

**Files:**
- Create: `src/test/java/com/indexer/indexing/treesitter/TreeSitterEngineTest.java`
- Create: `src/main/java/com/indexer/indexing/treesitter/TreeSitterEngine.java`

- [ ] **Step 1: Write TreeSitterEngineTest**

Create `src/test/java/com/indexer/indexing/treesitter/TreeSitterEngineTest.java`:

```java
package com.indexer.indexing.treesitter;

import com.indexer.indexing.ExtractedSymbol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeSitterEngineTest {

    private static TSParserPool pool;
    private static TreeSitterEngine engine;

    @BeforeAll
    static void setUp() {
        pool = new TSParserPool(2);
        engine = new TreeSitterEngine(pool);
    }

    @AfterAll
    static void tearDown() {
        pool.close();
    }

    @Test
    void parsesJavaClassAndMethods() {
        String source = """
                package com.example;
                import java.util.List;
                public class Calculator implements MathOps {
                    public int add(int a, int b) { return a + b; }
                    private void reset() { }
                }
                """;
        List<ExtractedSymbol> symbols = engine.parse(source, "java");
        assertThat(symbols).isNotNull();
        assertThat(symbols).anyMatch(s -> s.kind().equals("import"));
        assertThat(symbols).anyMatch(s -> s.name().equals("Calculator") && s.kind().equals("class"));
        assertThat(symbols).anyMatch(s -> s.name().equals("add") && s.kind().equals("method"));
        assertThat(symbols).anyMatch(s -> s.name().equals("reset") && s.kind().equals("method"));
    }

    @Test
    void parsesJavaTypeRelationships() {
        String source = """
                public class MyService implements Runnable {
                    public void run() {}
                }
                """;
        List<ExtractedSymbol> symbols = engine.parse(source, "java");
        var myService = symbols.stream()
                .filter(s -> s.name().equals("MyService"))
                .findFirst().orElseThrow();
        assertThat(myService.relationships())
                .anyMatch(r -> r.relatedName().equals("Runnable") && r.kind().equals("implements"));
    }

    @Test
    void parsesPythonClassAndFunctions() {
        String source = """
                import os
                from pathlib import Path
                class Animal(Base):
                    def speak(self):
                        pass
                def helper():
                    pass
                """;
        List<ExtractedSymbol> symbols = engine.parse(source, "python");
        assertThat(symbols).isNotNull();
        assertThat(symbols).anyMatch(s -> s.kind().equals("import"));
        assertThat(symbols).anyMatch(s -> s.name().equals("Animal") && s.kind().equals("class"));
        assertThat(symbols).anyMatch(s -> s.name().equals("speak") && s.kind().equals("method"));
        assertThat(symbols).anyMatch(s -> s.name().equals("helper") && s.kind().equals("function"));
    }

    @Test
    void parsesTypeScriptClassAndFunctions() {
        String source = """
                import { Component } from '@angular/core';
                export class AppComponent {
                    getName(): string { return "app"; }
                }
                function helper() {}
                """;
        List<ExtractedSymbol> symbols = engine.parse(source, "typescript");
        assertThat(symbols).isNotNull();
        assertThat(symbols).anyMatch(s -> s.kind().equals("import"));
        assertThat(symbols).anyMatch(s -> s.name().equals("AppComponent") && s.kind().equals("class"));
        assertThat(symbols).anyMatch(s -> s.name().equals("helper") && s.kind().equals("function"));
    }

    @Test
    void parsesGoStructsAndFunctions() {
        String source = """
                package main
                import "fmt"
                type Calculator struct {
                    value int
                }
                func (c *Calculator) Add(a, b int) int {
                    return a + b
                }
                func main() {
                    fmt.Println("hello")
                }
                """;
        List<ExtractedSymbol> symbols = engine.parse(source, "go");
        assertThat(symbols).isNotNull();
        assertThat(symbols).anyMatch(s -> s.kind().equals("import"));
        assertThat(symbols).anyMatch(s -> s.name().equals("Calculator") && s.kind().equals("class"));
        assertThat(symbols).anyMatch(s -> s.name().equals("Add") && s.kind().equals("method"));
        assertThat(symbols).anyMatch(s -> s.name().equals("main") && s.kind().equals("function"));
    }

    @Test
    void parsesCFunctionsAndStructs() {
        String source = """
                #include <stdio.h>
                #include "myheader.h"
                typedef int MyInt;
                struct Point {
                    int x;
                    int y;
                };
                int add(int a, int b) {
                    return a + b;
                }
                """;
        List<ExtractedSymbol> symbols = engine.parse(source, "c");
        assertThat(symbols).isNotNull();
        assertThat(symbols).anyMatch(s -> s.kind().equals("import"));
        assertThat(symbols).anyMatch(s -> s.name().equals("Point") && s.kind().equals("class"));
        assertThat(symbols).anyMatch(s -> s.name().equals("add") && s.kind().equals("function"));
    }

    @Test
    void returnsNullForUnsupportedLanguage() {
        List<ExtractedSymbol> symbols = engine.parse("code", "cobol");
        assertThat(symbols).isNull();
    }

    @Test
    void handlesEmptySource() {
        List<ExtractedSymbol> symbols = engine.parse("", "java");
        assertThat(symbols).isNotNull();
        assertThat(symbols).isEmpty();
    }
}
```

**Note on kind naming:** Go structs and C structs map to kind `"class"` (not `"struct"`) for consistency with how `FileIndexer` and MCP tools query symbols. The regex extractor already does this. Tree-sitter-extracted symbols must use the same kind vocabulary as the regex extractor.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.indexing.treesitter.TreeSitterEngineTest" 2>&1 | tail -20`
Expected: Compilation failure — `TreeSitterEngine` doesn't exist.

- [ ] **Step 3: Implement TreeSitterEngine**

Create `src/main/java/com/indexer/indexing/treesitter/TreeSitterEngine.java`:

```java
package com.indexer.indexing.treesitter;

import com.indexer.indexing.ExtractedSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Tree-sitter parsing engine. Loads .scm query files per language,
 * borrows parsers from a pool, and maps query captures to ExtractedSymbol.
 */
public class TreeSitterEngine {

    private static final Logger log = LoggerFactory.getLogger(TreeSitterEngine.class);

    private final TSParserPool parserPool;
    private final Map<String, LanguageConfig> languages = new HashMap<>();

    private record LanguageConfig(TSLanguage tsLanguage, String queryText,
                                   Map<String, String> nodeTypeToKind) {}

    public TreeSitterEngine(TSParserPool parserPool) {
        this.parserPool = parserPool;
        registerLanguage("java", new TreeSitterJava(), javaNodeKinds());
        registerLanguage("python", new TreeSitterPython(), pythonNodeKinds());
        registerLanguage("javascript", new TreeSitterJavascript(), typescriptNodeKinds());
        registerLanguage("typescript", new TreeSitterTypescript(), typescriptNodeKinds());
        registerLanguage("go", new TreeSitterGo(), goNodeKinds());
        registerLanguage("c", new TreeSitterC(), cNodeKinds());
        log.info("TreeSitterEngine initialized with {} languages: {}", languages.size(), languages.keySet());
    }

    /**
     * Parse source code and extract symbols using Tree-sitter.
     * Returns null if the language is not supported (signals regex fallback).
     */
    public List<ExtractedSymbol> parse(String source, String language) {
        LanguageConfig config = languages.get(language.toLowerCase());
        if (config == null) return null;
        if (source == null || source.isEmpty()) return List.of();

        TSParser parser = parserPool.borrow();
        try {
            parser.setLanguage(config.tsLanguage());
            TSTree tree = parser.parseString(null, source);
            try {
                return extractSymbols(source, tree, config);
            } finally {
                tree.close();
            }
        } catch (Exception e) {
            log.error("Tree-sitter parse failed for {}: {}", language, e.getMessage(), e);
            throw e;
        } finally {
            parserPool.release(parser);
        }
    }

    private List<ExtractedSymbol> extractSymbols(String source, TSTree tree, LanguageConfig config) {
        List<ExtractedSymbol> symbols = new ArrayList<>();
        TSNode root = tree.getRootNode();

        TSQuery query = new TSQuery(config.tsLanguage(), config.queryText());
        TSQueryCursor cursor = new TSQueryCursor();
        try {
            cursor.exec(query, root);
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                ExtractedSymbol symbol = processMatch(source, match, query, config);
                if (symbol != null) {
                    symbols.add(symbol);
                }
            }
        } finally {
            cursor.close();
            query.close();
        }

        return symbols;
    }

    private ExtractedSymbol processMatch(String source, TSQueryMatch match,
                                          TSQuery query, LanguageConfig config) {
        String name = null;
        String kind = null;
        int startLine = 0;
        int endLine = 0;
        String parentName = null;
        String visibility = null;
        boolean isStatic = false;
        String signature = null;
        List<ExtractedSymbol.Relationship> relationships = new ArrayList<>();

        for (TSQueryCapture capture : match.getCaptures()) {
            String captureName = query.getCaptureNameForId(capture.getIndex());
            TSNode node = capture.getNode();
            String text = source.substring(node.getStartByte(), node.getEndByte());

            switch (captureName) {
                case "name" -> name = text;
                case "path" -> name = text.replace("\"", ""); // strip quotes from import paths
                case "node" -> {
                    startLine = node.getStartPoint().getRow() + 1;
                    endLine = node.getEndPoint().getRow() + 1;
                    kind = config.nodeTypeToKind().getOrDefault(node.getType(), null);
                }
                case "parent" -> parentName = text.replace("*", "").trim(); // strip pointer for Go
                case "superclass" -> relationships.add(
                        new ExtractedSymbol.Relationship(text, "extends"));
                case "interface" -> relationships.add(
                        new ExtractedSymbol.Relationship(text, "implements"));
                case "visibility" -> visibility = extractVisibility(text);
                case "parameters" -> signature = name + text;
                case "static" -> isStatic = true;
            }
        }

        if (name == null || kind == null) return null;

        // For imports, use "import" as kind regardless of node type
        if ("path".equals(getFirstCaptureName(match, query))) {
            kind = "import";
        }

        return new ExtractedSymbol(name, kind, signature != null ? signature : name,
                startLine, endLine, parentName, visibility, isStatic, relationships);
    }

    private String getFirstCaptureName(TSQueryMatch match, TSQuery query) {
        TSQueryCapture[] captures = match.getCaptures();
        if (captures.length > 0) {
            return query.getCaptureNameForId(captures[0].getIndex());
        }
        return "";
    }

    private String extractVisibility(String text) {
        if (text.contains("public")) return "public";
        if (text.contains("private")) return "private";
        if (text.contains("protected")) return "protected";
        return null;
    }

    // -----------------------------------------------------------------------
    // Language registration
    // -----------------------------------------------------------------------

    private void registerLanguage(String name, TSLanguage tsLanguage,
                                   Map<String, String> nodeTypeToKind) {
        String queryText = loadQueryFile(name);
        if (queryText == null) {
            log.warn("No query file found for language: {} — will use regex fallback", name);
            return;
        }
        try {
            // Validate query compiles
            TSQuery testQuery = new TSQuery(tsLanguage, queryText);
            testQuery.close();
            languages.put(name, new LanguageConfig(tsLanguage, queryText, nodeTypeToKind));
        } catch (Exception e) {
            log.error("Failed to compile query for {}: {}", name, e.getMessage());
            throw new RuntimeException("Tree-sitter query compilation failed for " + name, e);
        }
    }

    private String loadQueryFile(String language) {
        String path = "/queries/" + language + ".scm";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load query file {}: {}", path, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Node type → kind mappings per language
    // -----------------------------------------------------------------------

    private static Map<String, String> javaNodeKinds() {
        return Map.of(
                "import_declaration", "import",
                "class_declaration", "class",
                "interface_declaration", "interface",
                "enum_declaration", "enum",
                "method_declaration", "method",
                "constructor_declaration", "method"
        );
    }

    private static Map<String, String> pythonNodeKinds() {
        return Map.of(
                "import_statement", "import",
                "import_from_statement", "import",
                "class_definition", "class",
                "function_definition", "function",
                "decorated_definition", "function"
        );
    }

    private static Map<String, String> typescriptNodeKinds() {
        return Map.ofEntries(
                Map.entry("import_statement", "import"),
                Map.entry("class_declaration", "class"),
                Map.entry("interface_declaration", "interface"),
                Map.entry("function_declaration", "function"),
                Map.entry("method_definition", "method"),
                Map.entry("lexical_declaration", "function"),
                Map.entry("variable_declarator", "function"),
                Map.entry("export_statement", "function")
        );
    }

    private static Map<String, String> goNodeKinds() {
        return Map.of(
                "import_declaration", "import",
                "import_spec", "import",
                "type_declaration", "class",
                "type_spec", "class",
                "function_declaration", "function",
                "method_declaration", "method"
        );
    }

    private static Map<String, String> cNodeKinds() {
        return Map.of(
                "preproc_include", "import",
                "function_definition", "function",
                "struct_specifier", "class",
                "enum_specifier", "enum",
                "type_definition", "type_alias"
        );
    }
}
```

**CRITICAL NOTES for the implementing engineer:**

1. **Verify import paths.** The tree-sitter-ng classes may be in package `org.treesitter` or `io.github.bonede.treesitter`. Use your IDE to confirm.

2. **Verify language class names.** `TreeSitterJava`, `TreeSitterPython`, etc. may be named differently (e.g., `TreeSitterJavascript` vs `TreeSitterJavaScript`). Check the actual classes in the grammar JARs.

3. **Verify TSQueryMatch API.** The `match.getCaptures()` method may return a `TSQueryCapture[]` array or a `List`. The `query.getCaptureNameForId()` method is standard in tree-sitter but verify it exists in tree-sitter-ng.

4. **Verify text extraction.** `source.substring(node.getStartByte(), node.getEndByte())` assumes byte offsets correspond to character offsets for ASCII/UTF-8 source. For pure ASCII source code this is fine. For Unicode identifiers, use proper byte-to-char conversion.

5. **Query tuning.** If a query fails to compile, the error message from `TSQuery` will indicate which pattern has the problem. Comment out failing patterns, get the others working, then fix the failing ones using `tree.getRootNode().toString()` to see the actual AST structure.

6. **Python method vs function.** The engine maps `function_definition` to `"function"`. When a function is inside a class (matched by the parent pattern in the query), the `parentName` will be set, and `FileIndexer` already handles this — it stores the `parentName` and the kind stays `"function"` or `"method"`. If the existing tests expect `"method"` for class methods, adjust the kind based on whether `parentName` is set.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.indexer.indexing.treesitter.TreeSitterEngineTest" 2>&1 | tail -30`
Expected: All 8 tests pass. If queries fail to compile, debug by printing `tree.getRootNode().toString()` for a sample source and adjusting the `.scm` files.

- [ ] **Step 5: Run full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/indexing/treesitter/TreeSitterEngine.java \
       src/test/java/com/indexer/indexing/treesitter/TreeSitterEngineTest.java
git commit -m "feat: add TreeSitterEngine — structural parsing with query-based symbol extraction"
```

---

### Task 5: SymbolExtractor Changes

**Files:**
- Modify: `src/main/java/com/indexer/indexing/SymbolExtractor.java`

- [ ] **Step 1: Add TreeSitterEngine integration to SymbolExtractor**

Read `src/main/java/com/indexer/indexing/SymbolExtractor.java`. Make these changes:

Add import at the top:
```java
import com.indexer.indexing.treesitter.TreeSitterEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add fields after the class declaration:
```java
    private static final Logger log = LoggerFactory.getLogger(SymbolExtractor.class);
    private final TreeSitterEngine engine;
```

Add new constructor (keep existing no-arg behavior):
```java
    public SymbolExtractor() {
        this.engine = null;
    }

    public SymbolExtractor(TreeSitterEngine engine) {
        this.engine = engine;
    }
```

Replace the `extract` method:
```java
    public List<ExtractedSymbol> extract(String source, String language) {
        if (source == null || source.isBlank()) return List.of();

        if (engine != null) {
            try {
                List<ExtractedSymbol> result = engine.parse(source, language);
                if (result != null) return result;
                // null means unsupported language — fall through to regex
            } catch (Exception e) {
                log.warn("Tree-sitter parse failed for {}, falling back to regex: {}",
                        language, e.getMessage());
            }
        }

        return extractWithRegex(source, language);
    }

    private List<ExtractedSymbol> extractWithRegex(String source, String language) {
        return switch (language.toLowerCase()) {
            case "java" -> extractJava(source);
            case "python" -> extractPython(source);
            case "typescript", "javascript" -> extractTypeScript(source);
            case "go" -> extractGo(source);
            default -> List.of();
        };
    }
```

The existing regex methods (`extractJava`, `extractPython`, etc.) are unchanged — they're now called via `extractWithRegex`.

- [ ] **Step 2: Verify existing tests still pass**

Run: `./gradlew test --tests "com.indexer.indexing.SymbolExtractorTest" 2>&1 | tail -20`
Expected: All 4 tests pass — they use `new SymbolExtractor()` (no-arg), so they exercise the regex path.

- [ ] **Step 3: Verify full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/indexer/indexing/SymbolExtractor.java
git commit -m "feat: SymbolExtractor dispatches to Tree-sitter first, regex fallback"
```

---

### Task 6: LanguageRegistry + Application Wiring

**Files:**
- Modify: `src/main/java/com/indexer/config/LanguageRegistry.java`
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Add "c" to CORE_LANGUAGES**

In `src/main/java/com/indexer/config/LanguageRegistry.java`, update the `CORE_LANGUAGES` set:

```java
    public static final Set<String> CORE_LANGUAGES = Set.of(
            "java", "python", "typescript", "javascript", "go", "c"
    );
```

- [ ] **Step 2: Wire TreeSitterEngine into Application.java**

Read `src/main/java/com/indexer/Application.java`. Add imports:

```java
import com.indexer.indexing.treesitter.TSParserPool;
import com.indexer.indexing.treesitter.TreeSitterEngine;
```

Add a field:
```java
    private TSParserPool parserPool;
```

Replace the SymbolExtractor creation (around line 68). Change:
```java
            var symbolExtractor = new SymbolExtractor();
```
to:
```java
            parserPool = new TSParserPool(config.server().indexWorkers());
            var treeSitterEngine = new TreeSitterEngine(parserPool);
            var symbolExtractor = new SymbolExtractor(treeSitterEngine);
```

Update the shutdown method to close the parser pool:
```java
    public void shutdown() {
        log.info("Shutting down...");
        if (poller != null) poller.stop();
        if (executor != null) executor.shutdownNow();
        if (adminService != null) adminService.shutdown();
        if (mcpServer != null) mcpServer.stop();
        if (httpServer != null) httpServer.stop();
        if (parserPool != null) parserPool.close();
        if (dbManager != null) dbManager.close();
        log.info("Shutdown complete");
    }
```

Note: `parserPool.close()` runs after HTTP server stops (no more incoming requests) but before DB closes.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run full test suite**

Run: `./gradlew test 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/config/LanguageRegistry.java \
       src/main/java/com/indexer/Application.java
git commit -m "feat: wire Tree-sitter into Application — C in CORE_LANGUAGES, pool in shutdown"
```

---

### Task 7: Update Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update CLAUDE.md**

Read `CLAUDE.md`. Make these updates:

1. In the project description at the top, change `Tree-sitter (JNI)` to `Tree-sitter (native via tree-sitter-ng)`.

2. Update the tech stack line to include the 5 languages: `Tree-sitter structural parsing (Java, Python, TypeScript/JavaScript, Go, C)`

3. If there's a section about supported languages, add C (`.c`, `.h` files).

4. Update any reference to "regex-based" parsing to note that Tree-sitter is now the primary parser with regex as fallback for unsupported languages.

- [ ] **Step 2: Run final build**

Run: `./gradlew build 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for Tree-sitter native integration and C language support"
```

---

## Verification Checklist

After all tasks are complete, verify:

1. `./gradlew build` passes (all tests green)
2. `./gradlew test --tests "*TreeSitterEngine*"` — all language tests pass
3. `./gradlew test --tests "*SymbolExtractor*"` — existing regex tests still pass
4. `./gradlew test --tests "*TSParserPool*"` — pool lifecycle tests pass
5. No references to `jnr-ffi` in `build.gradle.kts` or source code
6. `src/main/resources/queries/` contains 5 `.scm` files
7. Application starts successfully (Tree-sitter engine initializes without errors)
8. `LanguageRegistry.CORE_LANGUAGES` includes `"c"`

## Debugging Tips

If `.scm` queries fail to compile:
1. Parse a sample file and print the AST: `System.out.println(tree.getRootNode().toString())`
2. Compare node type names in the AST output with those in your `.scm` file
3. Tree-sitter playground (web) can also help visualize the grammar

If tests fail with unexpected symbol kinds:
1. Check the `nodeTypeToKind` mapping in `TreeSitterEngine` — the `@node` capture's node type must match a key in the map
2. Nested patterns (methods inside classes) may produce matches at different levels — print `match.getPatternIndex()` to debug
