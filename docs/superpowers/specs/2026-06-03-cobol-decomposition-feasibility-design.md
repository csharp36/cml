# Can CML Aid COBOL→Java Decomposition? An Oracle-Proxy Feasibility Study (CardDemo)

*Design doc — 2026-06-03. Deliverable of a brainstorming session for the Source Code
Indexer MCP server ("cml"). This document is the **instrument and the decision rule** for a
go/no-go on whether cml adds value to LLM-driven mainframe modernization. A negative verdict
is an acceptable — even expected — outcome.*

---

## Context

cml has been through three controlled experiments against a `grep`/`find` baseline
(`docs/superpowers/results/`). The honest synthesis: for *search* and *feature
implementation*, grep ties or wins and is cheaper. cml's **one decisive, measured win** is
**type-resolved IS-A reachability** — "list every concrete type that is-a `X`, transitively"
— answered via SCIP-backed `get_type_hierarchy` at **F1 0.88 vs grep 0.32** over ~102
queries, validated against an independent compiled-bytecode oracle. The win is *completeness
over a transitive relation grep cannot close by hand*, not speed.

This study asks whether that single proven capability has any bearing on a new domain:
migrating a legacy COBOL mainframe application to Java microservices, using the
[AWS CardDemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo) sample
as the test case.

## The central tension

COBOL has **no type hierarchy** — no classes, no interfaces, no `implements`. The *specific*
differentiator that justifies cml's existence therefore **does not transfer to COBOL at
all.** SCIP-the-format also effectively does not exist for COBOL (no production `scip-cobol`
indexer).

But the *shape* of the win might transfer to a **different relation**. COBOL's hard-to-grep
transitive relations are:

- **Control flow** — `CALL` / `PERFORM` closures: "everything reachable from this
  program/paragraph, transitively," including **dynamic** `CALL identifier` (target name held
  in a variable), which grep cannot resolve.
- **Data lineage / coupling** — which copybook records, VSAM files, and DB2 tables flow
  through which programs: "every program that touches the `ACCTDAT` file / `ACCOUNT-RECORD`."

These are genuine "completeness over a transitive graph" problems that grep fails at —
structurally the *same kind* of problem cml already beat grep on. So the interesting question
is **not** "port SCIP to COBOL." It is:

> **Is there a COBOL call/data-coupling reachability oracle that plays the role SCIP plays
> for Java — and does an LLM agent doing microservice decomposition actually need it?**

## Why decomposition is the right battlefield

Of the five stages of a COBOL→Java migration — (1) inventory, (2) comprehension,
(3) decomposition, (4) translation, (5) equivalence — cml's plausible value concentrates in
**comprehension and decomposition**, is grep-tie territory in translation, and is out of
scope for an index in equivalence.

**Decomposition** is chosen as the anchor because it is the **richest transitive-graph
problem and the closest analog to the proven SCIP win.** Carving a monolith into
microservices is fundamentally *graph clustering* over the COBOL: cluster programs by how
tightly they are coupled through shared data and call edges; boundaries fall along the weak
cuts. The boundary-relevant sub-queries an agent must answer are objective and
oracle-checkable even though the final boundary-drawing is judgment:

- transitive `CALL` closure of a program (incl. dynamic dispatch)
- "every program that reads or writes file/record X"
- "which copybooks/records are shared between candidate service A and service B"
- connected components when programs are clustered by shared data

grep finds *direct* `CALL 'X'` / `COPY Y` string hits but **cannot transitively close them,
cannot resolve dynamic calls, and cannot compute the coupling clustering** — the same
completeness gap cml beat grep on for Java IS-A.

## The make-vs-buy caveat (carried throughout)

Even if the win exists, cml's value-add is **not** "we can compute a COBOL call graph" —
mature COBOL dependency analyzers already exist (ProLeap, Micro Focus, AWS modernization
tooling). cml's only distinct claim would be **MCP-native, token-efficient, agent-queryable
delivery of that graph, integrated with the rest of the index.** Every positive result in
this study must be read against that bar.

## The decision and its bar

This remains a **content / learning engine**, not a product bid (consistent with the
`2026-06-02-keep-or-kill-pilot-design.md` framing). The deliverable is a **feasibility
verdict + evidence**, in the rigor tradition of the prior benchmarks. A win for the index is
a bonus, not a requirement. The bar to "greenlight building real COBOL support into cml"
(Approach B) is exactly one thing: **a decisive, honestly-measured reachability gap that grep
structurally cannot close.**

---

## Approach selected: A — Oracle-proxy benchmark

Of four approaches weighed (A: proxy-oracle benchmark; B: build a real COBOL tier into cml
first; C: end-to-end thin-slice migration; D: desk verdict), **A** is selected because it
answers the only question that matters first — *does a decomposition win even exist for
COBOL, and is it the same shape as the proven win?* — at the lowest cost, and it **defers the
COBOL-parsing risk** by leaning on an existing parser rather than betting the study on getting
tree-sitter-cobol working. If A is positive, B becomes a justified, well-scoped follow-up. If
A is negative, we have an evidenced "not a fit" verdict cheaply.

A's one weakness — "you measured a graph oracle, not cml" — is closed by serving the
precomputed graph through a **thin cml MCP shim** so the agent queries it via MCP exactly as
it would the real tool. We are measuring cml-*shaped* delivery, not just a graph file.

---

## Experiment design

The study slots into the existing `bench/arenaA` harness and reuses its conventions rather
than inventing new ones.

| arenaA piece | COBOL-benchmark analog |
|---|---|
| `oracle/build_oracle.py` (bytecode → `oracle.json`) | `build_oracle.py` (ProLeap parse → COBOL dependency/coupling graph JSON) |
| `questions.jsonl` (`id`, `stratum`, `answer_simple`) | same schema; `stratum` = the 5 query categories |
| `arms/run_grep.py` | grep arm, unchanged in spirit |
| `arms/mcp_call.py` | proxy arm → thin `cobol_reachability` MCP shim |
| `score.py` (P/R/F1, stratified) | reused near-verbatim — name-set F1 by stratum |
| `results/*.md` | the verdict writeup |

New work lives under a sibling directory, `bench/cobolA/`, mirroring `bench/arenaA/`.

### Phase 0 — Reconnaissance gate (cheap, possibly decisive)

Before building anything, characterize CardDemo to confirm a win is even *possible*:

- **Call style:** ratio of static `CALL 'LITERAL'` to dynamic `CALL identifier`. grep handles
  static fine; the oracle's edge grows with dynamic dispatch.
- **Coupling shape:** how much data sharing runs through copybooks / VSAM files / DB2 tables
  vs. local-only programs.
- **Transitivity depth:** are call/data chains deep (≥3 hops) or mostly flat?

**Decision rule:** if CardDemo is overwhelmingly **flat, static, copybook-obvious coupling**,
grep already wins → **stop here with a clean "not a fit" verdict at near-zero cost.**
Otherwise proceed to Phase 1.

*Deliverable:* `bench/cobolA/PHASE0-recon.md` with the three measurements and the gate
decision.

### Phase 1 — Build the independent oracle

Construct the ground-truth dependency/coupling graph from a parser **independent of what a
future Approach B would use** — this keeps both this study and any later B-test honest.

- **Tool:** [ProLeap COBOL parser](https://github.com/uwol/proleap-cobol-parser) (Java /
  ANTLR4 — matches the stack, full data-division + procedure-division ASTs, copybook
  preprocessing). **Not** tree-sitter-cobol; that grammar is reserved for Approach B so the
  oracle never shares a code path with the system that would be under test.
- **Graph nodes:** programs, copybooks, files/records, DB2 tables, CICS transactions.
- **Graph edges:** `CALL` (static + resolvable dynamic), `COPY`, file `READ`/`WRITE`,
  embedded-SQL table access, CICS transaction → program entry points.
- **Human audit:** the corpus is small (~40–50 programs); the generated graph is spot-verified
  by hand, the way the bytecode oracle was independent and trustworthy. Dynamic `CALL`s that
  ProLeap cannot statically resolve are recorded as a known completeness ceiling (analogous to
  grep's collision-precision caveat) rather than silently dropped.

*Deliverable:* `bench/cobolA/oracle/build_oracle.py` + `oracle/oracle.json` +
`oracle/test_build_oracle.py`.

### Phase 2 — Query workload + metrics

~60–100 boundary-relevant decomposition queries with deterministic oracle answers, across
**5 strata**:

1. **Transitive `CALL` closure** of a given program
2. **Data-access set** — which programs read/write a given file or record
3. **Shared-data coupling** between two candidate-service program sets
4. **Copybook fan-out** — which programs include a given copybook
5. **CICS transaction → reachable programs**

Question schema matches `arenaA/questions.jsonl` (`id`, `stratum`, `answer_simple`, plus the
prompt text). Metric: **recall / precision / F1 per query, aggregated by stratum**, identical
to the grep writeup so results are directly comparable in framing. Secondary: cost (turns /
tokens), reusing the existing cost instrumentation.

*Deliverable:* `bench/cobolA/questions.jsonl` + `select_questions.py`.

### Phase 3 — Two arms, same agent

- **grep arm:** the same agent with ripgrep + file reads, **fairly resourced** — multi-step,
  free to manually chase `COPY` and `CALL` chains. The win must come from completeness, not
  from handicapping grep.
- **cml-proxy arm:** the same agent with one thin MCP tool, `cobol_reachability(node, kind)`,
  that serves the Phase-1 precomputed graph (transitive closure / coupling set / access set).
  This is the shim that makes the measurement about cml-shaped delivery.

Both arms run identical queries; answers are name-sets scored against the oracle via reused
`score.py`.

*Deliverable:* `bench/cobolA/arms/` + `results/results.csv`.

### Verdict (pre-registered thresholds)

Thresholds are fixed *before* the run so the verdict cannot be rationalized afterward. Judged
on the macro-average over the transitive strata **1–3** (the relations grep structurally
cannot close; strata 4–5 are reported but not gating):

- **GREENLIGHT B** if **proxy-arm F1 ≥ 0.70** *and* **grep-arm F1 ≤ 0.45** *and* the
  **gap ≥ 0.30**. (The 0.32→0.88 Java result cleared all three by a wide margin; this is a
  deliberately less demanding but still decisive bar for a new domain.)
- **NOT A FIT** if the **gap < 0.15**, in either direction — grep is sufficient for
  CardDemo-scale decomposition and cml adds no structural capability.
- **AMBIGUOUS (0.15 ≤ gap < 0.30)** → no greenlight; report honestly and, if motivated,
  proceed to Phase 4 to test whether the partial substrate gap still yields a materially
  better decomposition before deciding.

*Deliverable:* `docs/superpowers/results/2026-06-XX-cobol-decomposition-findings.md`,
written in the verdict-against-the-thesis voice of the prior results docs.

### Phase 4 — Decomposition quality (conditional on a green Phase 3)

Only if Phase 3 is positive and we want to strengthen the case before committing to B: each
arm produces a **full proposed microservice decomposition** of CardDemo, scored against a
reference set of its natural bounded contexts (**Account, Card, Transaction, Customer/User,
Reporting, Bill-Pay**) via a clustering-similarity metric. This proves the sub-query win
translates to the real task. Deferred by default — it adds subjectivity and effort, and the
verdict does not depend on it.

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| **COBOL parsing is genuinely hard** (copybook expansion, embedded CICS/SQL, dialects) | Use ProLeap on CardDemo (clean IBM Enterprise COBOL, purpose-built as a modernization sample); audit by hand; corpus is small. Phase 0 surfaces parse trouble before heavy investment. |
| **Dynamic `CALL`s** unresolvable by any static tool | Record as a known oracle completeness ceiling; report it explicitly rather than claiming false completeness. CardDemo is predominantly static `CALL`. |
| **The win may not exist** (flat/static corpus) | Phase 0 gate stops the study cheaply with a clean negative verdict. This is an acceptable outcome. |
| **"You measured a graph, not cml"** | Serve the graph through the `cobol_reachability` MCP shim so delivery is cml-shaped. |
| **Make-vs-buy** — analyzers already exist | Carried as an explicit caveat in the verdict; cml's only claim is MCP-native token-efficient agent delivery. |
| **Unfair grep arm** inflating the gap | Resource grep fairly (multi-step, manual chasing); document the harness so the gap is attributable to completeness, not setup. |

## Out of scope

- Building real COBOL support into cml (that is Approach B, gated on this study).
- Stages 4–5 of migration (translation, equivalence) — an index does not help there.
- Paragraph-level intra-program `PERFORM` flow beyond what decomposition queries need.
- Non-CardDemo corpora — generalization is a later question.

## Success criteria for this study

The study succeeds if it produces a **defensible go/no-go on Approach B**, backed by
stratified F1 evidence and an honest accounting of the make-vs-buy caveat — *regardless of
which way the verdict falls.*
