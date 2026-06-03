# Where a semantic code index finally beats grep: type-resolved reachability

![A grep flashlight catches one small cluster of code on the left, blocked by darkness beyond it;
on the right, the index lights the whole connected type graph as a glowing constellation.](article3-hero.png)

*Findings writeup — 2026-06-03. Part 3 of a series. Single-repo, small-n; every number is
reproducible from `bench/arenaA/` on `main` of [github.com/csharp36/cml](https://github.com/csharp36/cml).*

---

## TL;DR

I built a [PostgreSQL-backed semantic code index](https://github.com/csharp36/cml) and spent two
articles failing to show it beats plain `grep`. [Article 1](https://medium.com/@csharp36/does-a-semantic-code-index-make-claude-code-a-better-engineer-a-controlled-experiment-c90b193204ee)
(implement a real feature): the index cost more and we'd measured the wrong phase. [Article 2](https://medium.com/@csharp36)
(locate a PR's change surface): a tie, and `grep` was cheaper. The honest running verdict was *"nice
project, not a clear win."*

This time I tested the one thing the index is actually built for and the first two experiments never
exercised: **type-resolved reachability** — *"list every concrete class that is a `X`, directly or
through inheritance."* Across 12 such questions on a 2-million-line codebase, scored against an
**independent compiled-bytecode oracle**:

| arm | how it works | recall | F1 | queries per question |
|---|---|---|---|---|
| **semantic index (SCIP)** | compiler-grade type graph | **0.97** | **0.88** | **1** |
| tree-sitter index (transitive) | parsed `implements`/`extends`, name-matched | 0.50 | 0.50 | 1 |
| `grep`-iterative | BFS of `grep` over source | 0.32 | 0.32 | **102** (max 481) |

A clean, monotone result: **grep < tree-sitter < SCIP.** The index roughly **triples grep's recall
at one-hundredth of the calls**, and wins on *every* question and *both* task flavors — including the
"lexical" ones that should favor grep. After two null results, this is the arena where the baby is,
in fact, not ugly. But the win is narrower and weirder than I expected, and getting there required
fixing a bug that explains the first two articles. Details below, including the parts that argue
against me.

---

## 1. Why the first two articles couldn't have found this

Here is the uncomfortable thing I discovered before running a single graded query: **in both prior
experiments, the index was competing with one hand tied behind its back.** The index has two ways to
answer a structural question:

1. **Tree-sitter parsing** — fast, language-aware, but it only sees what a *parser* sees: the literal
   `class Foo implements Bar` text in each file.
2. **SCIP** (Source Code Intelligence Protocol) — a *compiler-grade* symbol graph, produced by
   `scip-java` actually compiling the project. This is the type-resolved layer: it knows that
   `class C extends AbstractB` and `AbstractB implements I` means `C` is an `I`, across files and
   modules, through generics, the way the compiler does.

SCIP is the entire reason the project exists. And when I went to test it, **the SCIP tables were
empty.** They'd been truncated after an earlier session, and — the detail that matters — the CI step
that was supposed to upload SCIP had silently failed because it couldn't reach the indexer. So both
prior benchmarks measured the index *without* its differentiator. Worse, the one structural tool that
did have data, `find_implementations`, turned out to be a flat, direct-only lookup:

```sql
WHERE related_name = :typeName AND kind = 'implements'
```

That is, almost exactly what `grep "implements X"` does. No wonder the index kept tying grep: **for
structural questions, it had been *being* grep.** The fair fight had never happened.

So before I could run the experiment, the experiment forced two fixes (this keeps happening — Article
2's harness surfaced a real overlay bug too):

- I taught `find_implementations` to walk the inheritance graph transitively (a recursive query over
  the `implements ∪ extends` edges it already stored). On `DataSerializable`, direct lookup returned
  **89** implementers; the transitive walk returned **989**.
- I fixed the multi-part SCIP upload so a 507 MB index could actually land. It now does:
  **232,101 symbols / 107,762 relationships.** The CI-reachability failure that had kept SCIP empty
  is the recurring villain of this whole series, and it's finally dead.

Only now were all three approaches actually on the board.

## 2. The test: who really implements X?

**The question.** For a given interface or base class, name *every concrete type that is-a it,
transitively.* This is a question developers (and coding agents) ask constantly — "what are all the
`Operation`s?", "everything that's `DataSerializable`?" — and it's exactly where text search hits a
wall: the answer is defined by the *type graph*, not by any shared string.

**The corpus.** [Hazelcast](https://github.com/hazelcast/hazelcast) at one pinned commit
(`7af6ddea`, ~2 M LOC). One commit for everything — the source index, the SCIP upload, and the oracle
— so there's zero version skew.

**The three arms**, each answering the same 12 questions:

- **`grep`-iterative** — the strong-grep baseline. Not a single `grep`: a breadth-first search. Find
  the direct implementers with `grep`, then `grep` for anything extending *those*, and so on until no
  new types appear. This is what a determined developer with no index would actually do.
- **tree-sitter (CTE)** — the index's new transitive `find_implementations`, walking the parsed
  `implements`/`extends` graph. One query.
- **SCIP** — the index's `get_type_hierarchy`, walking the compiler-resolved graph. One query.

**The 12 questions** were picked mechanically from the codebase, not hand-chosen: 8 *structural*
(deep hierarchies where most implementers are indirect, e.g. `Tenantable` — 1 direct implementer,
534 transitive) and 4 *lexical* controls (shallow, where most implementers literally say
`implements X`, e.g. `MutatingOperation` — 99 of 121 direct). The lexical ones are grep's home turf,
included on purpose.

**The oracle (this is the important part).** Ground truth comes from the **compiled bytecode** — I
ran `javap` over the 9,547 `.class` files the build produced and read the actual JVM type graph. This
is independent of all three arms: it's not grep's text, not tree-sitter's parse, and *not* SCIP's
`scip-java` output. Never grade a tool with its own data. Scoring is precision / recall / F1 of each
arm's answer set against this bytecode truth.

**What those three numbers mean.** Each arm returns a *set* of class names; the oracle is the *true*
set. Compare them and you get three standard measures:

- **Precision** — of the types the arm *returned*, what fraction are actually implementers? It
  punishes false positives (naming a class that isn't really a subtype). Precision 0.65 means a third
  of the answers were wrong.
- **Recall** — of *all* the types that truly implement X, what fraction did the arm *find*? It
  punishes misses. Recall 0.32 means it found under a third of the real implementers and silently
  dropped the rest. **This is the completeness measure**, and — spoiler — it's the whole story here.
- **F1** — the harmonic mean of the two, `2·P·R / (P+R)`. A single 0–1 score that's only high when
  precision *and* recall are both high; it collapses toward whichever is worse, so you can't game it
  by being complete-but-sloppy or precise-but-narrow.

A find-everything-and-guess strategy scores high recall but low precision; a return-only-the-one-you're-sure-of
strategy does the reverse. F1 is the honest summary, and it's the column to watch in the tables below.

## 3. The two alignment traps (or: why the first numbers lied)

Two methodology bugs nearly produced a bogus result, both worth stating because they generalize.

**Scope.** My first run had SCIP *overshooting* the oracle — finding *more* implementers than
existed, which is impossible if the oracle is true. The cause: SCIP and the tree-sitter index include
**test code**; the oracle (built from `target/classes`) was **main code only**. SCIP wasn't wrong, it
was answering a bigger question. Once every arm and the oracle were filtered to the same scope
(`/src/main/`), the mirage vanished. *An apples-to-apples comparison has to pin scope as carefully as
it pins the commit.*

**Naming.** Hazelcast's implementers are heavily *nested* (`SomeService$SomeOperation`). My oracle
first excluded inner classes and the arms named them inconsistently. Fixing the oracle to include
nested types and normalizing names (SCIP's `…/Outer#Inner#` ⇄ javap's `Outer$Inner`) was the
difference between a blunt comparison and a sharp one.

The payoff of getting this right: on `DataSerializable`, the index's SCIP answer matched the
independent bytecode oracle at **F1 0.97** (precision 0.955, recall 0.986). When your tool reproduces
the compiler's own view of a 989-type hierarchy to within 2%, you can trust the harness.

## 4. The result

Means across the 12 questions, scored against bytecode truth:

| arm | precision | recall | **F1** | calls / question |
|---|---|---|---|---|
| **SCIP** | 0.861 | **0.972** | **0.877** | 1 |
| tree-sitter (CTE) | 0.666 | 0.496 | 0.499 | 1 |
| `grep`-iterative | 0.650 | 0.316 | 0.324 | 102 (max 481) |

Broken out by task flavor:

| stratum | `grep` | tree-sitter | SCIP |
|---|---|---|---|
| structural (8) | 0.245 | 0.379 | **0.828** |
| lexical (4) | 0.482 | 0.741 | **0.975** |

![Two bar panels, recall and F1, across the three arms. grep-iterative is lowest (recall 0.32, F1
0.32), tree-sitter in the middle (0.50 / 0.50), SCIP far ahead (recall 0.97, F1 0.88) — a clean
monotone staircase. The F1 panel is annotated: grep takes 102 queries (max 481) per question, SCIP
takes 1.](figure-arenaA-recall-f1.png)

SCIP is the best-or-tied on **11 of 12** questions; it never loses one. `grep` wins **zero**. On
several deep hierarchies the source-parse arms post an outright **0.0** — they find the interface and
essentially nothing under it — while SCIP scores 0.8–1.0. And critically, **SCIP wins the lexical
controls too** (0.975 vs grep's 0.482): even when the task names a concrete token, the *transitive*
answer still needs the type graph grep can't see, so grep finds the direct hits and misses the tail.

The cost gap is its own headline. The index answers in **one** query. `grep`-iterative averages
**102** passes per question and up to **481** on the big hierarchies — two orders of magnitude more
work *for the worst accuracy on the board.*

## 5. The finding flipped my hypothesis

I went in expecting SCIP to win on **precision** — to resolve the name collisions (two different
`Factory` classes in different packages) that a name-matched approach conflates. That's not what
happened. All three arms are *similarly* precise. SCIP wins on **recall** — on *completeness.*

The reason is clarifying. `grep` and tree-sitter both reconstruct the hierarchy from what a reader
sees in source: the `implements`/`extends` clause on each declaration. That misses every edge the
*compiler* resolves and the *reader* can't cheaply follow — an interface that extends another
interface two modules away, a generic supertype, an implements-clause reached only through an
abstract base. Those aren't edge cases in a mature Java codebase; they're most of the graph. SCIP
carries the compiler's resolved edges, so its recall is ~0.97 where the source-parse approaches sit
at ~0.3–0.5.

That's the whole thesis of a semantic index, finally measured: **it doesn't search text faster, it
answers questions text can't.**

## 6. The honest caveats

I've spent two articles refusing to oversell; I'm not going to start now.

- **n = 12, one repo, one commit, one language.** This is a direction, not a law. The effect is large
  and monotone, which is encouraging, but it is not a population study.
- **My oracle is the compiler's truth.** That's the right ground truth for "who implements X" — but
  SCIP is *also* compiler-derived (via different tooling: `javap` vs `scip-java`), so the two share a
  worldview. `grep` and tree-sitter are the genuinely independent lower-bound arms. A skeptic should
  read this as "SCIP reproduces the compiler; source-parsing can't" rather than "SCIP is magic."
- **This is one question type.** Articles 1 and 2 stand: for *general* discovery and for
  *implementing* a feature, `grep` ties or wins, and the index is a signal to use *alongside* it, not
  a replacement. The win here is specifically **type-resolved reachability** — implementers,
  subtypes, the transitive structural questions. That's a real and common need, but it is not "the
  index beats grep at everything."
- **`grep`-iterative is one baseline.** A cleverer developer might prune the BFS. But the recall
  ceiling is structural: text can't resolve types, so a smarter grep strategy trades cost for the
  same wall.
- **SCIP has to actually be populated.** The entire result rests on the upload path working — the
  exact thing that failed silently for two articles. The capability is only as real as the pipeline
  that feeds it.

## 7. So: is the baby ugly?

After three experiments, the fair answer is: **it was being judged in the wrong pageant.** On grep's
home court — local, single-repo, text-findable code — grep is unbeatable and the index is dead weight.
The index earns its existence in the one place grep is *structurally blind*: questions defined by the
type graph rather than by a string. There, it doesn't edge grep out; it roughly triples its recall at
a hundredth of the cost, and reproduces the compiler to within 2%.

If I were writing the project's README today, I'd stop selling it as "faster code search" and start
selling it as what the data actually shows: **a type-resolution oracle** — *who implements this, what
are its subtypes, what's the hierarchy* — answered in one call across a codebase too big to hold in
your head. That's the keep decision, and it's the end of the trilogy: not "the index makes Claude a
better engineer," but "here is the specific, measurable question where it does."

---

### Appendix: reproducibility

- Repo: [github.com/csharp36/cml](https://github.com/csharp36/cml), `main`. Harness: `bench/arenaA/`.
- Oracle: `bench/arenaA/oracle/build_oracle.py` (`javap` over the compiled jar → type graph).
- Questions: `bench/arenaA/select_questions.py` → `questions.jsonl` (mechanical, 8 structural / 4 lexical).
- Arms: `bench/arenaA/arms/{run_grep.py, run_index_cte.sh, run_index_scip.sh}` via `mcp_call.py`.
- Score + run: `bench/arenaA/score.py`, `bench/arenaA/run_all.sh`. Raw: `bench/arenaA/results/`.
- Full result doc with per-question tables: `docs/superpowers/results/2026-06-02-arenaA-graded-results.md`.
- The transitive `find_implementations` change: `QueryExecutor.findImplementations(..., transitive)`
  (+ `FindImplementationsTransitiveTest`).
