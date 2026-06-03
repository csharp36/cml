# COBOL Phase 1B — ProLeap Extractor + Oracle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax. **API-discovery mode:** the ProLeap v2.4.0 ASG API is only partly documented (the upstream research was based on the unreleased 4.0.0; the spike already found `analyzeFile(file, FIXED, params)` takes the format as an arg, not a setter). For every extraction task, the **test is the spec** (a concrete COBOL fixture → exact expected edges). Implement by inspecting the real v2.4.0 ASG with `javap`/`unzip` against the resolved jar, then writing traversal code to make the test pass. Do NOT trust documented method names blindly — verify against the jar.

**Goal:** Turn the validated ProLeap parse (Phase 1A spike: 44/44 CardDemo programs) into an audited ground-truth dependency/coupling graph `oracle.json`, with dynamic CICS `XCTL`/`CALL` targets resolved via constant propagation.

**Architecture:** The Java extractor (`bench/cobolA/oracle/extractor/`) parses each program and emits one `ProgramEdges` record per program to `raw-edges.json`. A Python `build_oracle.py` consumes that and computes transitive closures / access sets / coupling into `oracle.json` (arenaA-style). A human audit (`AUDIT.md`) spot-verifies the graph.

**Tech Stack:** Java 21 + ProLeap `com.github.uwol:proleap-cobol-parser:v2.4.0` (JitPack), Jackson, JUnit 5; Python 3.13 + pytest. Resolved jar:
`~/.gradle/caches/modules-2/files-2.1/com.github.uwol/proleap-cobol-parser/v2.4.0/*/proleap-cobol-parser-v2.4.0.jar`.

**Parent plan / spike:** `docs/superpowers/plans/2026-06-03-cobol-phase1-proleap-oracle.md` + `bench/cobolA/oracle/SPIKE.md`.

---

## The data contract — `ProgramEdges` (locked here; both Java and Python depend on it)

One JSON object per program. `build_oracle.py` consumes exactly these keys:
```json
{
  "program_id": "COMEN01C",
  "static_calls":              ["CSUTLDTC"],
  "dynamic_call_idents":       ["WS-PGM"],
  "resolved_dynamic_calls":    ["CSUTLDTC"],
  "static_xctl_link":          ["COSGN00C"],
  "dynamic_xctl_idents":       ["WS-NEXT-PROG"],
  "resolved_dynamic_xctl_link":["COMEN01C","COADM01C"],
  "unresolved_dynamic_count":  1,
  "copybooks":                 ["COCOM01Y","COMEN01"],
  "files_read":                ["ACCTFILE"],
  "files_written":             ["ACCTFILE"],
  "db2_tables":                ["ACCOUNT"],
  "cics_txn_entry":            []
}
```
`*_idents` are the raw identifier operands (pre-resolution, useful for audit). `resolved_*` are concrete program names produced by constant propagation (B2). `unresolved_dynamic_count` = dynamic CALL+XCTL operands with no reaching literal.

---

## File Structure (Phase 1B adds)
```
bench/cobolA/oracle/extractor/src/main/java/oracle/
  model/ProgramEdges.java        # POJO ↔ JSON (Jackson)
  ProgramExtractor.java          # parse one Program → ProgramEdges (B1)
  ConstantPropagator.java        # MOVE literal→field reaching PROGRAM(field)/CALL field (B2)
  CopyScanner.java               # raw-source COPY regex (B3)
  ExtractorMain.java             # corpus → raw-edges.json (replace spike runner) (B3)
  src/test/java/oracle/
    ProgramExtractorTest.java
    ConstantPropagatorTest.java
    CopyScannerTest.java
  src/test/resources/cobol/      # fixtures: edges.cbl, constprop.cbl, + a copybook
bench/cobolA/oracle/
  graph.py  test_graph.py        # closures/coupling (B4, pure Python TDD)
  build_oracle.py  test_build_oracle.py   # raw-edges.json → oracle.json (B4)
  raw-edges.json  oracle.json    # generated, committed (B3/B4)
  AUDIT.md                       # human audit (B5)
```

---

## Task B1: ProgramExtractor — calls, XCTL/LINK, file I/O, DB2 tables

**Files:** Create `model/ProgramEdges.java`, `ProgramExtractor.java`, `src/test/java/oracle/ProgramExtractorTest.java`, `src/test/resources/cobol/edges.cbl`.

- [ ] **Step 1: Create the fixture** `src/test/resources/cobol/edges.cbl` (fixed-format; col 7 blank). It exercises every edge type:
```
000100 IDENTIFICATION DIVISION.
000200 PROGRAM-ID. EDGES.
000300 ENVIRONMENT DIVISION.
000400 INPUT-OUTPUT SECTION.
000500 FILE-CONTROL.
000600     SELECT ACCTFILE ASSIGN TO ACCTDAT
000700        ORGANIZATION IS INDEXED ACCESS IS RANDOM
000800        RECORD KEY IS FD-ACCT-ID FILE STATUS IS WS-ST.
000900 DATA DIVISION.
001000 WORKING-STORAGE SECTION.
001100 01 WS-PGM      PIC X(08).
001200 PROCEDURE DIVISION.
001300     CALL 'STATPGM'.
001400     CALL WS-PGM.
001500     EXEC CICS XCTL PROGRAM('XCTLLIT') END-EXEC.
001600     EXEC CICS LINK PROGRAM(WS-PGM) END-EXEC.
001700     READ ACCTFILE.
001800     WRITE ACCT-REC.
001900     EXEC SQL SELECT ACCT_ID INTO :WS-ID FROM ACCOUNT END-EXEC.
002000     STOP RUN.
```

- [ ] **Step 2: Discover the v2.4.0 ASG API.** Inspect the jar to confirm the real class/method names before writing the test's expected structure or the impl:
```bash
JAR=$(find ~/.gradle/caches -name 'proleap-cobol-parser-v2.4.0.jar' | head -1)
for c in asg.metamodel.procedure.call.CallStatement \
         asg.metamodel.procedure.execcics.ExecCicsStatement \
         asg.metamodel.procedure.execsql.ExecSqlStatement \
         asg.metamodel.procedure.read.ReadStatement \
         asg.metamodel.procedure.ProcedureDivision \
         asg.metamodel.procedure.Statement \
         asg.metamodel.procedure.StatementTypeEnum; do
  echo "== $c =="; javap -classpath "$JAR" "io.proleap.cobol.$c" 2>/dev/null || echo "  (not at this path — grep the jar)"
done
unzip -l "$JAR" | grep -iE 'procedure/(call|execcics|execsql|read|write)/' | head -40
```
Note the real package paths, the `Statement`→type discriminator (`getStatementType()` vs instanceof), how to get a statement's operand value (`getProgramValueStmt()` / `getCall()` / `.getText()`), and how to enumerate statements (`getStatements()` flat + nested via `CobolBaseVisitor`).

- [ ] **Step 3: Write the failing test** `ProgramExtractorTest.java`. Parse the fixture with the spike's confirmed entry point (`new CobolParserRunnerImpl().analyzeFile(file, CobolSourceFormatEnum.FIXED, params)`), run `ProgramExtractor.extract(program)`, and assert (adjust field access only if v2.4.0 forces it — the EXPECTED VALUES are fixed):
```java
ProgramEdges e = new ProgramExtractor().extract(program, "EDGES");
assertEquals("EDGES", e.programId);
assertEquals(Set.of("STATPGM"), new HashSet<>(e.staticCalls));
assertEquals(Set.of("WS-PGM"), new HashSet<>(e.dynamicCallIdents));   // identifier operand, pre-resolution
assertEquals(Set.of("XCTLLIT"), new HashSet<>(e.staticXctlLink));
assertEquals(Set.of("WS-PGM"), new HashSet<>(e.dynamicXctlIdents));
assertTrue(e.filesRead.contains("ACCTFILE"));
assertTrue(e.filesWritten.contains("ACCT-REC") || e.filesWritten.contains("ACCTFILE"));
assertTrue(e.db2Tables.contains("ACCOUNT"));
```
For CICS/SQL, extraction is regex over `getExecCicsText()`/`getExecSqlText()` (per spike: ProLeap exposes these as opaque text). XCTL/LINK regex: `(?i)(?:XCTL|LINK)\s+PROGRAM\(\s*'?([A-Z0-9-]+)'?\s*\)` — quoted ⇒ `staticXctlLink`, bare identifier ⇒ `dynamicXctlIdents`. SQL table regex: `(?i)\bFROM\s+([A-Z0-9_]+)` (plus INTO/UPDATE/JOIN).

- [ ] **Step 4: Run, confirm FAIL** (`./gradlew test` — no `ProgramExtractor` yet).

- [ ] **Step 5: Implement `model/ProgramEdges.java`** (plain public-field POJO with `@JsonProperty` snake_case names matching the contract; lists initialised empty) **and `ProgramExtractor.java`** to pass the test, using the real v2.4.0 API found in Step 2. Walk `getProcedureDivision().getStatements()` (+ a `CobolBaseVisitor` if nested statements are missed); classify CALL operand literal-vs-identifier via the value-stmt type; regex the CICS/SQL text; read file-control + READ/WRITE for file edges.

- [ ] **Step 6: Run, confirm PASS** (`./gradlew test`).

- [ ] **Step 7: Commit** `git add` the four files; `git commit -m "bench(cobolA): ProgramExtractor — CALL/XCTL/file/DB2 edges from ProLeap ASG"`.

---

## Task B2: ConstantPropagator — resolve dynamic targets

**Files:** Create `ConstantPropagator.java`, `ConstantPropagatorTest.java`, `src/test/resources/cobol/constprop.cbl`. Modify `ProgramExtractor.java` (invoke the propagator to fill `resolved_*` + `unresolved_dynamic_count`).

- [ ] **Step 1: Fixture** `constprop.cbl` — a field MOVEd two different literals on two paths, used in XCTL; and a field never MOVEd a literal:
```
000100 IDENTIFICATION DIVISION.
000200 PROGRAM-ID. CPROP.
000300 DATA DIVISION.
000400 WORKING-STORAGE SECTION.
000500 01 WS-NEXT  PIC X(08).
000600 01 WS-UNK   PIC X(08).
000700 PROCEDURE DIVISION.
000800     MOVE 'COMEN01C' TO WS-NEXT.
000900     MOVE 'COADM01C' TO WS-NEXT.
001000     EXEC CICS XCTL PROGRAM(WS-NEXT) END-EXEC.
001100     MOVE WS-FOO TO WS-UNK.
001200     EXEC CICS XCTL PROGRAM(WS-UNK) END-EXEC.
001300     STOP RUN.
```

- [ ] **Step 2: Failing test** `ConstantPropagatorTest.java`:
```java
ProgramEdges e = new ProgramExtractor().extract(program, "CPROP");
assertEquals(Set.of("COMEN01C","COADM01C"), new HashSet<>(e.resolvedDynamicXctlLink)); // both literal sources
assertEquals(1, e.unresolvedDynamicCount);    // WS-UNK never got a literal
```

- [ ] **Step 3: confirm FAIL.**

- [ ] **Step 4: Implement `ConstantPropagator`** — flow-insensitive over-approximation (sufficient and honest for the oracle): collect every `MOVE '<lit>' TO <field>` and `<field> ... VALUE '<lit>'` initializer into `field → {literals}`; for each dynamic CALL/XCTL identifier operand, resolve to that field's literal set (program names = 1–8 char `[A-Z0-9]` tokens). Empty set ⇒ contributes 1 to `unresolved_dynamic_count`. Wire into `ProgramExtractor` so `extract()` fills `resolved_dynamic_calls`, `resolved_dynamic_xctl_link`, `unresolved_dynamic_count`. (Extract MOVE/VALUE literals by walking the ASG `MoveStatement`s + data-description `VALUE` clauses, or — if the v2.4.0 ASG makes MOVE operand text awkward — regex the program's normalised source for `MOVE\s+'([^']+)'\s+TO\s+(\S+?)\.?$` keyed per field. Pick whichever the jar makes reliable; the test pins the result either way.)

- [ ] **Step 5: confirm PASS** (B1 + B2 tests).

- [ ] **Step 6: Commit** `bench(cobolA): constant propagation resolves dynamic XCTL/CALL targets`.

---

## Task B3: CopyScanner + corpus emission → `raw-edges.json`

**Files:** Create `CopyScanner.java`, `CopyScannerTest.java`. Replace `ExtractorMain.java` (spike runner → corpus emitter). Generate+commit `raw-edges.json`.

- [ ] **Step 1: CopyScanner test + impl.** COPY is erased by ProLeap's preprocessor, so scan raw source. Fixed-format aware (reuse the Phase-0 column rule: skip col-7 `*`/`/`; code is cols 8–72). Test: a source string with `COPY COCOM01Y.` and a commented `* COPY NOPE` ⇒ `Set.of("COCOM01Y")`. Regex: `(?im)^.{6}[ ]\s*COPY\s+([A-Z0-9-]+)` applied to raw lines (or reuse Phase-0 normalize). Impl to pass.

- [ ] **Step 2: Replace `ExtractorMain`** to: discover `.cbl` + copybook dirs (as the spike does), and for each program build a `ProgramEdges` via `ProgramExtractor.extract()` + `CopyScanner` (copybooks), collect into a `List<ProgramEdges>`, and Jackson-serialize to the path given by arg 2 (default `../../oracle/raw-edges.json`). Keep `ignoreSyntaxErrors(true)`. Print a one-line summary (programs, total edges).

- [ ] **Step 3: Run over CardDemo** `./gradlew -q run --args="../../corpus ../../raw-edges.json"`; sanity-check `raw-edges.json` has 44 program objects and that `resolved_dynamic_xctl_link` is non-empty for menu programs (e.g. `COMEN01C`, `COADM01C`). Cross-check the resolved XCTL count against Phase-0's 31 dynamic XCTL edges (resolved + unresolved should be in that ballpark).

- [ ] **Step 4: Commit** code + `raw-edges.json`: `bench(cobolA): emit CardDemo raw-edges.json (ProLeap + const-prop + copy scan)`.

---

## Task B4: `graph.py` + `build_oracle.py` → `oracle.json`

**Files:** Create `bench/cobolA/oracle/graph.py`, `test_graph.py`, `build_oracle.py`, `test_build_oracle.py`. Generate+commit `oracle.json`. (Pure-Python TDD, mirroring `bench/arenaA/oracle/`.)

- [ ] **Step 1: `graph.py` (TDD, pure).** Functions over the program→edges map:
  - `transitive_call_closure(edges, program)` — BFS over `static_calls ∪ resolved_dynamic_calls ∪ static_xctl_link ∪ resolved_dynamic_xctl_link`.
  - `data_access_set(edges, resource)` — programs whose `files_read|files_written|db2_tables` include resource.
  - `shared_data_coupling(edges, a_set, b_set)` — resources shared between two program sets.
  - `copybook_fan(edges)` — copybook → programs.
  Tests assert each on a small synthetic edges dict (concrete expected sets). Cycle-safe BFS (visited set).

- [ ] **Step 2: `build_oracle.py` (TDD).** Read `raw-edges.json` → build the program→edges map → precompute, for every program/resource, the answer sets above → write `oracle.json` with a layout Phase 3 `score.py` can consume (per-query `answer_simple` style + a `programs`/`resources` index). `test_build_oracle.py` runs it on a 2-program fixture json and asserts the closure/coupling fields. Then run on the real `raw-edges.json` → commit `oracle.json`.

- [ ] **Step 3: Commit** `bench(cobolA): build_oracle.py — closures/coupling → oracle.json`.

---

## Task B5: Human audit → `AUDIT.md`

**Files:** Create `bench/cobolA/oracle/AUDIT.md`.

- [ ] **Step 1: Spot-verify** the generated graph against hand-read CardDemo (independent of the extractor): e.g. open `corpus/app/cbl/COMEN01C.cbl` and confirm its resolved XCTL fan-out in `oracle.json` matches the `MOVE '...' TO ... / XCTL PROGRAM(...)` sites; confirm an account program's `ACCTDAT`/`ACCOUNT` access set; confirm a copybook's fan matches `grep -rl 'COPY <name>'`.
- [ ] **Step 2: Record** in `AUDIT.md`: sampled programs, agreement/discrepancies, and the **declared completeness ceiling** — any `unresolved_dynamic_count` residue, the missing vendor/app copybooks (`DFH*`/`CMQ*`, `CSSTRPFY`, `CSUTLDWY`) and their effect, and the `cics_txn_entry` gap if not populated (transaction→program lives in the CSD, not COBOL — note as out-of-scope or a follow-up CSD-scan task). This is the independence/trust step that makes the oracle citable.
- [ ] **Step 3: Commit** `bench(cobolA): human audit of ProLeap oracle (AUDIT.md)`.

---

## Exit of Phase 1B
`oracle.json` exists, audited, with resolved dynamic XCTL targets → unblocks Phase 2 (query workload) and Phase 3 (two-arm benchmark).

## Self-Review notes
- **Contract-first:** `ProgramEdges` keys are fixed once (top) and are the only coupling between Java (B1–B3) and Python (B4); both reference the same names.
- **API-discovery honesty:** no fabricated v2.4.0 traversal code is presented as final — each Java task gives concrete fixtures + expected edges (the spec) and directs the implementer to discover the real ASG via `javap`. This mirrors the spike, which found a real API deviation.
- **Const-propagation = the locked differentiator** (B2), flow-insensitive over-approximation, with unresolved targets counted honestly rather than dropped.
- **Known gaps surfaced, not hidden:** missing vendor copybooks and the CSD-based `cics_txn_entry` are recorded in B5's AUDIT.md as the declared completeness ceiling.
