# GnuCOBOL cross-check oracle — common-mode independence for the audited graph

**Why this exists.** The COBOL feasibility benchmark's answer key for the grep-hard
strata was hand-audited *by the same person who wrote the ProLeap oracle*, a first-time
COBOL reader. That audit caught two real oracle bugs (the `PROGRAM ('…')` continuation/
space XCTL miss; logical-vs-physical file identity) — but a hand-audit sharing the
author's COBOL mental model is **method-independent, not mind-independent**. It can catch
a place where the two artifacts *diverge*; it structurally cannot catch a place where the
author was *consistently wrong in both*. This is the same common-mode-failure principle
the project applies elsewhere (oracle codec ≠ production decoder).

This cross-check adds a **second, different-lineage extractor** to attack that common
mode at the layer where both observed bugs actually lived: parsing and normalization.

## What it does

1. **`preprocess.sh`** runs GnuCOBOL's preprocessor (`cobc -E`, GnuCOBOL 3.2 — a C
   compiler, independent of ProLeap's ANTLR/Java front end) over all 44 CardDemo programs.
   The compiler does fixed-format column handling, **continuation-line joining**, and
   **`COPY` expansion** — including the `COMEN02Y`/`COADM02Y` `OCCURS` menu tables. Missing
   IBM system copybooks (`DFHAID`, `DFHBMSCA`, `CMQ*`) are stubbed; they define BMS screen
   attributes / MQ structures and carry no program-dispatch targets.
2. **`extract_edges.py`** re-derives, by a fresh implementation that never reads
   `oracle.json`, the resolved control-transfer edges (literal `CALL` + CICS `XCTL`/`LINK`,
   with variable operands resolved via transitive `VALUE`-constant propagation through
   `MOVE` chains and `OCCURS` menu-table harvest) and the `SELECT … ASSIGN TO <ddname>`
   physical file map + DB2 tables.
3. **`diff_oracles.py`** compares the GnuCOBOL oracle against the ProLeap `oracle.json`,
   corpus-filtered to the 44 programs, on three layers. **It is not tuned to converge** —
   it prints whatever disagreement exists.

## Result

| layer | programs in agreement |
|---|---|
| direct control-transfer edges | **44 / 44** |
| transitive call closure | **44 / 44** |
| data coupling (ddname + DB2) | **44 / 44** |

The agreement is non-vacuous: 22 of 44 programs carry ≥1 corpus control edge; `COMEN01C`
resolves to its 12 direct edges and a 22-program closure on both oracles; the bug-2 case
`CBTRN03C` couples to `{CBTRN01C, CBTRN02C}` (and *not* `CBACT04C`/`CBSTM03B`) on both;
the DB2 trio `COTRTLIC/COTRTUPC/COBTUPDT` couple on `CARDDEMO.TRANSACTION_TYPE` on both.

### Discriminating-power control (the important part)

44/44 is only meaningful if the check *could* have failed. Negative control: re-inject
bug #1 by requiring `PROGRAM(` with **no** space (the original ProLeap defect). Result:
`COSGN00C` collapses to `[]` (exactly the original floor-miss), and **21 of 44 programs
then disagree** with ProLeap — the signoff-bridge collapse rippling through every online
closure. So a second toolchain that still had the bug would have flagged 21 programs; the
observed 0 disagreements is a real signal, not an artifact of a powerless test.

## What this validates — and what it does NOT

**Validates (parsing / normalization / extraction-code independence).** Two independent
toolchains — GnuCOBOL's C-based preprocessor + a fresh Python extractor, vs ProLeap's
ANTLR/Java — recover the *same* edge set from the *same* source. The transcription-class
bug the audit caught (the `PROGRAM ('…')` regex miss) is independently confirmed fixed:
a different normalizer that mishandled continuation/spacing would have disagreed, and the
negative control shows the disagreement would have been large. The edges ProLeap reports
are really in the source.

**Does NOT validate (semantic-modeling independence).** The extractor was written by the
same author, *after* reading the audit, so it encodes the same **modeling choices**:
that a signoff `XCTL` (`screen → COSGN00C → re-dispatch`) counts as a reachability edge;
that physical file identity is the `ASSIGN` ddname; that commarea-passthrough targets are
excluded as ceilings. Where those choices are a *shared* misreading of what COBOL
decomposition needs, **both oracles and this cross-check would be consistently, invisibly
wrong together** — exactly the common mode a same-mind check cannot break. The dominant
driver of the benchmark's win (the signoff bridge collapsing the online tier into one SCC)
rests on one such modeling choice, flagged but not independently adjudicated here.

**Net:** this hardens the layer where the two *observed* bugs lived (and proves the test
has the power to see them), but the load-bearing modeling assumptions still rest on a
single COBOL mental model. The honest residual fix for that is a COBOL-literate second
reader, or an orthogonal method (a real CICS-aware compile/trace) — not another extractor
the same author writes.

## Reproduce

```bash
cd bench/cobolA
./oracle2-gnucobol/preprocess.sh           # cobc -E over 44 programs -> normalized/
python3 -m pytest oracle2-gnucobol/test_extract_edges.py -q
python3 oracle2-gnucobol/extract_edges.py  # -> oracle2.json
python3 oracle2-gnucobol/diff_oracles.py   # 44/44 on all three layers
```
Requires `brew install gnucobol` (GnuCOBOL 3.2). Corpus pinned at `59cc6c2`.
