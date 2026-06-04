# Can a reachability oracle help decompose COBOL? A pre-registered CardDemo benchmark

*Findings writeup — 2026-06-03. Source material for a Medium article (part of the
"where the index wins" series). Single-corpus, small-n; every number below is reproducible
from `bench/cobolA/` on branch `feat/cobol-phase2-3`. Corpus: AWS CardDemo pinned at
`59cc6c2`.*

---

## TL;DR

CML's one *proven* differentiator is **type-resolved reachability** — "list every concrete
type that is-a `X`, transitively" — where SCIP beats iterative `grep` decisively on Java
(F1 0.88 vs 0.32). COBOL has no type hierarchy, so that exact win can't transfer. The open
question: does the *shape* of the win — completeness over a graph `grep` structurally cannot
close — carry over to the substrate of COBOL→Java **decomposition**: call/transaction
reachability and data coupling?

We built an oracle-proxy benchmark on **AWS CardDemo** (44 IBM Enterprise COBOL programs):
a ProLeap-derived reachability graph served to an agent through one thin MCP tool
(`cobol_reachability(node, kind)`), versus a fairly-resourced `grep` arm, over **80 questions
across 5 strata**, scored against a **hybrid answer key** (independent hand-audit for the
grep-hard strata; grep-verifiable oracle answers for the static ones). Thresholds were
**pre-registered** before the run.

**Verdict: `AMBIGUOUS`.** On the gating macro-average (strata 1–3) the proxy scored **1.00**
and `grep` **0.523**, a gap of **0.477** — wide, but not a clean greenlight, because the
pre-registered bar requires `grep ≤ 0.45` and `grep` cleared it.

The honest reading: the oracle's advantage is **real but narrow**. It is *decisive* exactly
where we predicted — **dynamic-dispatch reachability** (`call_closure` F1 0.145→1.00,
`txn_reach` 0.212→1.00), the COBOL analog of the Java type-resolution win — and `grep`
keeps pace everywhere else (`data_access` 0.749, `data_coupling` 0.674, `copybook_fan` 1.000),
because those relations are flat literal lookups a fair `grep` user resolves. The
differentiator exists; it just doesn't dominate the *whole* decomposition substrate the way
type resolution dominates "find all implementers" in Java.

A second result is methodological, and it's the part we're proudest of: **the independent
audit caught two real bugs in our own oracle before we shipped a verdict** (see "The audit
that moved the result").

---

## What we measured

**Corpus.** [AWS CardDemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo)
— a purpose-built mainframe modernization sample: 44 COBOL programs, fixed-format IBM
Enterprise COBOL, a CICS online tier (menus + screens), a batch tier, VSAM files, DB2, and
an IMS/MQ authorization sub-app. Clean enough that ProLeap parses 44/44; small enough to
hand-audit.

**The 5 strata** (the relations that matter for carving a monolith into services):

| # | stratum | question | gating? | key source |
|---|---------|----------|---------|------------|
| 1 | `call_closure` | every program transitively reachable from `X` via CALL + CICS XCTL/LINK | **yes** | independent audit |
| 2 | `data_access` | every program that reads/writes physical file `R` | **yes** | oracle (grep-verifiable) |
| 3 | `data_coupling` | every program sharing a physical file/DB2 table with `X` | **yes** | independent audit |
| 4 | `copybook_fan` | every program that `COPY`s copybook `C` | no | oracle (grep-verifiable) |
| 5 | `txn_reach` | every program reachable when CICS transaction `T` starts | no | independent audit |

80 questions total (`call_closure` 21, `data_access` 10, `data_coupling` 13, `copybook_fan`
15, `txn_reach` 21); 55 are keyed by independent hand-audit, 25 by grep-verifiable oracle
answers.

**Two arms, same questions, deterministic** (both are reproducible scripts, not stochastic
agents — this measures the *structural ceiling* of each delivery mechanism):

- **`grep` arm** — fairly resourced: follows literal `CALL`/`XCTL` targets, chases
  `MOVE 'literal' TO var` for variable dispatch, and resolves `SELECT … ASSIGN TO <ddname>`
  so file questions are answered at the physical-file level. The only thing it *cannot* do
  is enumerate an `OCCURS`/`REDEFINES`/`VALUE` dispatch table — which is the structural
  construct under test, not a handicap.
- **`proxy` arm** — the same questions answered through one `cobol_reachability(node, kind)`
  MCP round-trip backed by the precomputed oracle. One call per question.

**Pre-registered verdict** (fixed before the run; macro-averaged over gating strata 1–3):
`GREENLIGHT` if proxy F1 ≥ 0.70 **and** grep F1 ≤ 0.45 **and** gap ≥ 0.30; `NOT_A_FIT` if
gap < 0.15; `AMBIGUOUS` otherwise.

---

## The audit that moved the result

The benchmark's answer key for the three grep-hard strata was built by an **independent
source audit** — a reviewer reading the COBOL/CSD by hand, deriving each answer from the
source *without consulting the oracle*. On first pass that audit **disagreed with our
ProLeap oracle on 29 of 32 questions.**

Rather than trust the oracle, we treated the disagreement as a bug report and chased every
delta to source. Two were real oracle defects:

1. **A missed control-flow bridge (completeness).** `COSGN00C` (the signon program, the
   application's entry) contains two *literal* `EXEC CICS XCTL PROGRAM('COMEN01C')` /
   `PROGRAM('COADM01C')` statements — but with the program name on a continuation line and a
   space before the paren (`PROGRAM (`). Our extractor's regex required `PROGRAM(` with no
   space, so it captured **zero** edges for the entry program. Combined with a second gap
   (no transitive propagation of `VALUE`-initialized constant fields through `MOVE LIT-X TO
   target` chains — the exact `LIT-MENUPGM VALUE 'COMEN01C'` idiom CardDemo uses), the oracle
   silently under-reported the online call graph.

2. **Logical vs. physical file identity (precision).** The oracle keyed data coupling on the
   program-local logical `SELECT` name. But `CBACT04C` and `CBTRN03C` both `SELECT
   TRANSACT-FILE` / `XREF-FILE` while `ASSIGN`-ing them to *different* physical ddnames
   (`TRANSACT`/`XREFFILE` vs `TRANFILE`/`CARDXREF`). The oracle coupled them; they touch
   different files. A genuine false positive.

We fixed both at the root — the XCTL regex, transitive constant propagation through copy
MOVEs, and ddname-based file identity — as principled corrections that a correct reachability
oracle *should* have, **not** tuned to the audit. After the fixes the independently-derived
key and the oracle **converged to 0 residual deltas across all 55 audited questions.**

That convergence is the validation, and it is meaningful *precisely because the pre-fix audit
disagreed 29/32*. The proxy arm therefore scores ~1.0 on the audited strata not by
construction but because the oracle was independently confirmed correct. (The load-bearing
numbers in the verdict are consequently `grep`'s F1 and the gap, not the proxy's.)

---

## Results

| stratum | grep F1 | proxy F1 | n | gating |
|---------|:------:|:--------:|:-:|:------:|
| `call_closure` | **0.145** | 1.000 | 21 | ● |
| `data_access`  | 0.749 | 1.000 | 10 | ● |
| `data_coupling`| 0.674 | 1.000 | 13 | ● |
| `copybook_fan` | 1.000 | 1.000 | 15 | |
| `txn_reach`    | **0.212** | 1.000 | 21 | |

*(Per-stratum means are `score.py`'s mean-then-round outputs, reproducible via
`run_all.sh`. Averaging the already-rounded `results.csv` column can differ by ±0.001 —
e.g. `txn_reach` reads 0.211 that way; neither value is gating.)*

**Pre-registered verdict (macro-avg over gating strata 1–3):**

```json
{ "decision": "AMBIGUOUS", "proxy_f1": 1.0, "grep_f1": 0.523, "gap": 0.477 }
```

`grep`'s gating average (0.523) exceeds the pre-registered `GREP_MAX` of 0.45, so despite a
0.477 gap this is not a clean `GREENLIGHT`. **We report it as it fell — we did not re-tune the
gating post-hoc**, even though judging only the strata `grep` structurally cannot close
(`call_closure` + `txn_reach`) would have produced a decisive headline (proxy 1.00 vs grep
~0.18).

---

## Where the win is — and where it isn't

**Where the oracle is decisive: dynamic-dispatch reachability.** On `call_closure`,
`grep` scores 0.145; on `txn_reach`, 0.212. Concrete case: from the main menu `COMEN01C`,
`grep` recovers **2** programs (the literal/MOVE-resolvable transfers); the oracle recovers
**22** — the rest of the ~23-program online cluster. The 11 main-menu screens and 6 admin screens are dispatched
through an `OCCURS`-table of program-name literals indexed by a runtime option
(`XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))`). Enumerating that table is exactly the
type-resolution-shaped move `grep` cannot make and static analysis can. This *is* the Java
win's shape, transferred to COBOL.

**Where `grep` keeps pace: the static relations.** `data_access` (0.749), `data_coupling`
(0.674), and `copybook_fan` (1.000) are flat literal lookups — `SELECT … ASSIGN`, `COPY`,
`READ`/`WRITE` — that a fairly-resourced `grep` user resolves correctly. The oracle is no more
complete here; it's just delivered in one call instead of several. (`grep`'s sub-1.0 on
coupling comes from CICS file access it doesn't fully chase, not from a structural blindness.)

**Why that yields `AMBIGUOUS`.** The pre-registered gating set (strata 1–3) mixes one
grep-hard relation (`call_closure`) with two grep-tractable ones (`data_access`,
`data_coupling`). Averaging across them dilutes the dynamic-dispatch win below the
greenlight bar. The differentiator is real; it simply doesn't blanket the *whole*
decomposition substrate the way type resolution blankets "find all implementers" in Java.

---

## A structural finding about CardDemo

CardDemo's **online tier is a navigationally-complete menu system**: every screen returns to
the menu hub (PF3 → signoff → `COSGN00C` → `COMEN01C`/`COADM01C`), and the menus fan back out
to every screen. The result is a single ~23-program strongly-connected component — so
`call_closure`/`txn_reach` answers are nearly identical across all online programs. That makes
transitive reachability a **low-information** discriminator on this corpus (many near-duplicate
questions), and it's the main reason to be cautious about over-reading the wide gap. The
oracle still wins those questions outright — `grep` can't traverse the SCC because every hub
edge runs through an `OCCURS` table — but "wins a degenerate relation" is weaker evidence than
"wins a discriminating one." We flag it rather than hide it.

---

## Make-vs-buy caveat (carried, as pre-registered)

COBOL dependency analyzers already exist (commercial and academic). This benchmark does **not**
claim CML resolves COBOL reachability *better* than they do — only that, where a reachability
graph exists, delivering it through one MCP call (`calls = 1`/question for the proxy vs a
multi-step BFS for `grep`) gives an LLM agent token-efficient, complete answers to questions
`grep` cannot close. CML's distinct claim is **MCP-native delivery**, not COBOL parsing.

---

## Honest limits

- **One small corpus.** 44 programs, single sample. CardDemo is clean by construction; real
  mainframe estates are messier (dialects, copybook sprawl, dynamic `CALL`).
- **SCC degeneracy.** As above, `call_closure`/`txn_reach` are near-uniform on this corpus;
  the wide gap rests substantially on one repeated structure (the menu dispatch).
- **Proxy F1 ≈ 1.0 by construction.** The proxy serves the (independently-validated) oracle;
  its score is a "perfect delivery" baseline, not evidence about delivery robustness. The
  evidence lives in `grep`'s shortfall and the gap.
- **Thin gating stratum.** `data_access` has only n=10 after physical-file keying.
- **Declared completeness ceiling.** A handful of genuinely dynamic targets (commarea-passed
  `CDEMO-FROM-PROGRAM` "return to caller") are excluded from the key — neither arm is charged
  for them.

---

## Verdict and next step

On this evidence we **do not greenlight** building full COBOL support into CML (the original
"Approach B") as a general decomposition aid. The win is genuine but **narrow**: a
*dynamic-dispatch / transaction-reachability oracle*, not an across-the-board advantage over
`grep` on the decomposition substrate.

Two honest paths:

1. **Validate before committing.** Re-run on a larger, dispatch-heavier corpus (more dynamic
   `CALL`, deeper XCTL chains, less menu-hub degeneracy) where transitive reachability is a
   *discriminating* relation. If the `call_closure`/`txn_reach` gap survives on a
   non-degenerate graph, the case strengthens.
2. **Scope the claim if pursued.** Pitch any COBOL capability as a **"dynamic call/transaction
   reachability oracle, delivered over MCP"** — the one place it decisively beats `grep` —
   and explicitly *not* as a replacement for `grep` on static file/copybook relations, where
   `grep` ties.

Either way, the study did what it was designed to do: reach an evidenced, pre-registered
verdict — including the unglamorous `AMBIGUOUS` one — cheaply, and catch our own oracle's bugs
on the way.

---

*Reproduce:* `cd bench/cobolA && ./clone_corpus.sh && ./run_all.sh`. Oracle build:
`oracle/extractor` (Java/ProLeap) → `oracle/build_oracle.py` → `oracle/oracle.json`.
Independent key + provenance: `audit/hard_strata_key.json`, `audit/KEY-AUDIT.md`.
Spec: `docs/superpowers/specs/2026-06-03-cobol-decomposition-feasibility-design.md`.
