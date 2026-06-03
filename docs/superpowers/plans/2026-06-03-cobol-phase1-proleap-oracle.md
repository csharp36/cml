# COBOL Phase 1 — Independent ProLeap Oracle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an independent, ProLeap-backed ground-truth dependency/coupling graph for AWS CardDemo — the oracle against which Phase 3 will benchmark a grep arm vs an MCP-proxy arm — with **dynamic CICS `XCTL`/`CALL` targets resolved via constant propagation** (the capability grep structurally cannot match).

**Architecture:** A standalone Gradle (Kotlin DSL) Java application under `bench/cobolA/oracle/extractor/` depends on `io.github.uwol:proleap-cobol-parser:4.0.0`. It parses each CardDemo program (`CobolSourceFormatEnum.FIXED`, `ignoreSyntaxErrors(true)`, copybook dirs configured), walks the ASG to emit **raw edges** (CALL static/dynamic, CICS XCTL/LINK targets from `getExecCicsText()`, file SELECT/READ/WRITE, EXEC SQL tables, CICS txn entry points) plus a constant-propagation pass that resolves `MOVE 'literal' TO ws-field` → `XCTL PROGRAM(ws-field)` to concrete program names. A Python `oracle/build_oracle.py` consumes the raw-edges JSON and precomputes the query-answer structures (transitive closures, access sets, coupling sets, copybook fan-out) into `oracle/oracle.json`, mirroring `bench/arenaA/oracle/oracle.json`. ProLeap is deliberately chosen because it is **independent of tree-sitter** (reserved for a future Approach B), keeping the oracle honest.

**Tech Stack:** Java 21 (ProLeap `com.github.uwol:proleap-cobol-parser:v2.4.0` via **JitPack** — it is NOT published to Maven Central; the unreleased `4.0.0` pom on `main` misled the initial research. ANTLR runtime bundled), Gradle Kotlin DSL (application plugin), Jackson for JSON, Python 3.13 + pytest for `build_oracle.py`. Corpus: CardDemo pinned at `59cc6c2` (already cloned in Phase 0 at `bench/cobolA/corpus/`).

**Spec:** `docs/superpowers/specs/2026-06-03-cobol-decomposition-feasibility-design.md` (Phase 1 section)
**Upstream research:** ProLeap API confirmed — `CobolParserRunnerImpl().analyzeFile(file, CobolParserParamsImpl)` → `Program` → `getCompilationUnit(name)` → `getProgramUnit()` → `getProcedureDivision().getStatements()`. CICS/SQL exposed as opaque text via `ExecCicsStatement.getExecCicsText()` / `ExecSqlStatement.getExecSqlText()`. `COPY` is erased by the preprocessor → extract from raw source. Nested statements require `CobolBaseVisitor` + `program.getASGElementRegistry().getASGElement(ctx)`.

---

## Design decisions (locked from the discussion)

- **Oracle depth:** resolve dynamic `XCTL`/`CALL` targets via **constant propagation** (intra-program: `MOVE literal TO field` reaching a `PROGRAM(field)`/`CALL field`). Unresolvable targets are recorded as `dynamic→<unresolved>` (honest, not dropped).
- **Independence:** ProLeap only (not tree-sitter). If the spike shows ProLeap can't parse CardDemo acceptably, fall back to **MAPA** or **Koopa** (decided at the spike gate, not now).
- **Graph parity with Phase 0:** the Phase 0 recon already found CardDemo's dynamic dispatch is 100% CICS `XCTL PROGRAM(variable)`. The oracle's job is to resolve those variable targets and to add accurate file/DB2 data-coupling that the Phase 0 regex under-counted (CICS file I/O).

---

## File Structure

```
bench/cobolA/oracle/
  extractor/                       # standalone Gradle (Kotlin DSL) Java app
    settings.gradle.kts
    build.gradle.kts               # proleap 4.0.0 + jackson; application plugin
    gradlew, gradle/               # wrapper (generated)
    src/main/java/oracle/
      ExtractorMain.java           # CLI: args = corpus dir + copybook dir; emits raw-edges JSON
      ProgramExtractor.java        # parse one Program → ProgramEdges (CALL/XCTL/file/SQL)
      ConstantPropagator.java      # MOVE literal→field reaching PROGRAM(field)/CALL field
      CopyScanner.java             # raw-source COPY regex (COPY erased by preprocessor)
      model/ProgramEdges.java      # POJO serialized to JSON
    src/test/java/oracle/
      ProgramExtractorTest.java    # parse small COBOL fixtures, assert extracted edges
      ConstantPropagatorTest.java
    src/test/resources/cobol/      # tiny COBOL + copybook fixtures
  build_oracle.py                  # raw-edges JSON → closures/coupling → oracle.json
  graph.py                         # transitive closure / access set / coupling (pure, TDD)
  test_graph.py
  test_build_oracle.py
  raw-edges.json                   # extractor output over CardDemo (committed)
  oracle.json                      # final oracle (committed)
  AUDIT.md                         # human spot-audit of the generated graph
```

---

# PHASE 1A — SPIKE (de-risk ProLeap on CardDemo) — DETAILED

The spike answers one question: **does ProLeap parse CardDemo well enough to be the oracle?** If yes, proceed to the extractor roadmap. If no, switch tools at the gate.

## Task 1: Scaffold the standalone Gradle extractor module

**Files:**
- Create: `bench/cobolA/oracle/extractor/settings.gradle.kts`
- Create: `bench/cobolA/oracle/extractor/build.gradle.kts`
- Create: `bench/cobolA/oracle/extractor/src/main/java/oracle/ExtractorMain.java` (stub)
- Modify: `bench/cobolA/.gitignore` (add Gradle build dirs)

- [ ] **Step 1: Add Gradle ignores** — append to `bench/cobolA/.gitignore`:
```
oracle/extractor/.gradle/
oracle/extractor/build/
```

- [ ] **Step 2: `settings.gradle.kts`:**
```kotlin
rootProject.name = "cobol-oracle-extractor"
```

- [ ] **Step 3: `build.gradle.kts`:**
```kotlin
plugins {
    application
    java
}
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }   // ProLeap is only on JitPack
}
dependencies {
    implementation("com.github.uwol:proleap-cobol-parser:v2.4.0")  // JitPack; NOT on Maven Central
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
application { mainClass = "oracle.ExtractorMain" }
tasks.test { useJUnitPlatform() }
```

- [ ] **Step 4: Stub `ExtractorMain.java`** so the module builds before logic exists:
```java
package oracle;

public final class ExtractorMain {
    public static void main(String[] args) {
        System.err.println("usage: ExtractorMain <corpusDir> <copybookDir>");
    }
}
```

- [ ] **Step 5: Generate the Gradle wrapper and confirm the module builds and resolves ProLeap.**
Run: `cd bench/cobolA/oracle/extractor && gradle wrapper && ./gradlew build`
Expected: BUILD SUCCESSFUL; ProLeap 4.0.0 + Jackson resolve from Maven Central. If ProLeap fails to resolve, STOP and report (coordinates/version drift) — do not work around silently.

- [ ] **Step 6: Commit** (wrapper jar + scripts included so CI/others can build):
```bash
git add bench/cobolA/.gitignore bench/cobolA/oracle/extractor/settings.gradle.kts \
        bench/cobolA/oracle/extractor/build.gradle.kts \
        bench/cobolA/oracle/extractor/gradlew bench/cobolA/oracle/extractor/gradlew.bat \
        bench/cobolA/oracle/extractor/gradle bench/cobolA/oracle/extractor/src
git commit -m "bench(cobolA): scaffold ProLeap extractor Gradle module"
```

## Task 2: Parse-all spike runner + CardDemo parse-success report

**Files:**
- Modify: `bench/cobolA/oracle/extractor/src/main/java/oracle/ExtractorMain.java`

- [ ] **Step 1: Implement a parse-only run** that walks the corpus, parses every `.cbl`/`.CBL` with `ignoreSyntaxErrors(true)`, and reports per-file success/failure + a sample statement-type histogram. Use the confirmed ProLeap API:
```java
package oracle;

import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.asg.runner.impl.CobolParserRunnerImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public final class ExtractorMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: ExtractorMain <corpusDir> <copybookDir>"); System.exit(2); }
        File corpus = new File(args[0]);
        File copybooks = new File(args[1]);
        List<Path> cbls;
        try (Stream<Path> s = Files.walk(corpus.toPath())) {
            cbls = s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".cbl"))
                    .sorted().collect(Collectors.toList());
        }
        int ok = 0, fail = 0;
        List<String> failures = new ArrayList<>();
        for (Path p : cbls) {
            CobolParserParams params = new CobolParserParamsImpl();
            ((CobolParserParamsImpl) params).setFormat(CobolSourceFormatEnum.FIXED);
            ((CobolParserParamsImpl) params).setCopyBookDirectories(List.of(copybooks));
            ((CobolParserParamsImpl) params).setCopyBookExtensions(List.of("cpy", "CPY"));
            ((CobolParserParamsImpl) params).setIgnoreSyntaxErrors(true);
            try {
                Program prog = new CobolParserRunnerImpl().analyzeFile(p.toFile(), params);
                if (prog != null && !prog.getCompilationUnits().isEmpty()) ok++;
                else { fail++; failures.add(p.getFileName() + " (empty model)"); }
            } catch (Throwable t) {
                fail++; failures.add(p.getFileName() + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        }
        System.out.printf("PARSED ok=%d fail=%d total=%d%n", ok, fail, cbls.size());
        failures.forEach(f -> System.out.println("FAIL " + f));
    }
}
```
(Note: `analyzeFile` throws `IOException`; copybook dir must be CardDemo's `.cpy` directory — locate it under `corpus/` in Step 2.)

- [ ] **Step 2: Find CardDemo's COBOL + copybook directories** and run the spike:
```bash
cd bench/cobolA
find corpus -iname '*.cpy' | head -1   # note the copybook dir (e.g. corpus/app/cpy)
find corpus -iname '*.cbl' | head -1   # note the program dir (e.g. corpus/app/cbl)
cd oracle/extractor
./gradlew -q run --args="../../corpus ../../corpus/<copybook-dir>"
```
Capture the `PARSED ok=.. fail=..` line and the FAIL list.

- [ ] **Step 3: Record the spike result** in `bench/cobolA/oracle/SPIKE.md`: parse success rate, the failure list with error classes, and a recommendation. Commit:
```bash
git add bench/cobolA/oracle/extractor/src/main/java/oracle/ExtractorMain.java bench/cobolA/oracle/SPIKE.md
git commit -m "bench(cobolA): ProLeap parse-all spike on CardDemo + success report"
```

## ⛔ SPIKE GATE — decide before building the extractor

Read `bench/cobolA/oracle/SPIKE.md`:
- **Parse success high (≥ ~90% of programs yield a non-empty model):** ProLeap is viable → return to writing-plans, expand the Phase 1B roadmap below into bite-sized TDD tasks, continue.
- **Parse success poor / systemic CICS or dialect failures even with `ignoreSyntaxErrors`:** switch the oracle tool. Re-run the brainstorming/spec step to choose **MAPA** (purpose-built for IBM COBOL + CICS XCTL/LINK + DB2 call-trees) or **Koopa** (tolerant of CICS/SQL fragments), then re-plan the extractor against that tool. The downstream `build_oracle.py` / `graph.py` / `oracle.json` contract is tool-agnostic and survives the swap.

---

# PHASE 1B+ — ROADMAP (expand into bite-sized TDD tasks only after the SPIKE GATE passes)

Deliberately not broken into steps yet — the spike must confirm the real ASG shape first (the research gives the API but it has not been run against CardDemo). Each becomes its own task block on return to writing-plans. Confirmed ProLeap API references are inlined so expansion is fast.

### B1 — ProgramExtractor: static/dynamic CALL + CICS XCTL/LINK + file I/O + DB2 tables
- Walk `pd.getStatements()` (+ `CobolBaseVisitor` for nested) per `ProgramUnit`.
- **CALL:** `((CallStatement) stmt).getProgramValueStmt()` → literal ⇒ static target; identifier ⇒ dynamic (feed to B2).
- **CICS XCTL/LINK:** `((ExecCicsStatement) stmt).getExecCicsText()` → regex `(?:XCTL|LINK)\s+PROGRAM\(([^)]+)\)`; literal ⇒ static target; bare identifier ⇒ dynamic (feed to B2).
- **File I/O:** `FileControlEntry.getSelectClause()` (logical name) + `getAssignClause()` (DD/dataset); `ReadStatement.getFileCall()` / `WriteStatement.getRecordName()` for access edges.
- **DB2:** `((ExecSqlStatement) stmt).getExecSqlText()` → table names (regex now; JSQLParser if precision needed).
- TDD against tiny COBOL fixtures in `src/test/resources/cobol/` parsed by ProLeap (`ProgramExtractorTest`).

### B2 — ConstantPropagator: resolve dynamic targets (the differentiator)
- Intra-program: collect `MOVE 'literal' TO field` (and `VALUE 'literal'` initializers) per working-storage field; when a `PROGRAM(field)` / `CALL field` uses that field, resolve to the literal program name(s). Multiple possible literals ⇒ multiple resolved edges. No reaching literal ⇒ `dynamic→<unresolved>`.
- TDD: fixture `MOVE 'COMEN01C' TO WS-PGM. ... XCTL PROGRAM(WS-PGM)` ⇒ resolved edge to `COMEN01C`; a field never MOVEd a literal ⇒ unresolved.

### B3 — CopyScanner + ExtractorMain JSON emission
- `COPY` is erased by the preprocessor → regex raw source per program (reuse the Phase 0 fixed-format approach) for copybook edges.
- `ExtractorMain` assembles per-program `ProgramEdges` (program_id, static_calls, resolved_dynamic_calls, unresolved_dynamic_count, copybooks, files_read, files_written, db2_tables, cics_txn_entry) and serializes the whole corpus to `bench/cobolA/oracle/raw-edges.json` via Jackson.
- Run over CardDemo; commit `raw-edges.json`.

### B4 — build_oracle.py + graph.py: closures → oracle.json
- Python, mirroring `bench/arenaA/oracle/`. `graph.py` (pure, TDD): transitive CALL/XCTL closure (over resolved edges), data-access sets (program↔file/DB2), shared-data coupling sets, copybook fan-out. `build_oracle.py`: read `raw-edges.json` → compute structures → write `oracle.json` with a `simple_name_index`-style layout reusable by Phase 3 `score.py`.
- `test_graph.py`, `test_build_oracle.py`.

### B5 — Human audit (AUDIT.md)
- Spot-verify the generated graph against a hand-read sample of CardDemo (e.g. the main menu `COMEN01C`/`COADM01C` XCTL fan-out, the `ACCTDAT`/`CARDDAT` file access sets). Record discrepancies and the declared completeness ceiling (any `dynamic→<unresolved>` residue, CICS file-I/O coverage now that ProLeap captures it). This is the independence/trust step that makes the oracle citable in the eventual write-up.

### Exit of Phase 1
`oracle.json` exists, audited, with resolved dynamic XCTL targets → unblocks Phase 2 (query workload) and Phase 3 (two-arm benchmark).

---

## Self-Review notes
- **Spec coverage:** Phase 1 of the spec (independent ProLeap oracle, human-audited, dynamic-target resolution) is covered — spike (1A) gates the build; B1–B5 implement extraction, the locked constant-propagation decision (B2), and the audit. Downstream contract (`oracle.json`) matches arenaA conventions for Phase 3 reuse.
- **No fabricated unrun API in committed steps:** only Phase 1A (scaffold + parse-all spike) is given as executable steps, using the exact ProLeap entry points confirmed by research. Detailed traversal/extraction code (B1–B5) is deferred until the spike confirms the real ASG, avoiding guessed API in bite-sized form.
- **Type consistency:** `ProgramEdges` field names (program_id, static_calls, resolved_dynamic_calls, unresolved_dynamic_count, copybooks, files_read, files_written, db2_tables, cics_txn_entry) are the contract between the Java extractor (B3) and `build_oracle.py` (B4); to be locked when B3 is expanded.
- **Fallback path:** if the spike fails, the tool swap (MAPA/Koopa) is explicit and the Python downstream is tool-agnostic.
