# Phase 0 — CardDemo Reconnaissance

**Gate decision (script recommendation): `PROCEED`**

## Metrics

- Programs parsed: 44
- Static call/XCTL edges: 34
- Dynamic dispatch edges: 41
- Dynamic share: 54.7%
- Max static call-chain depth: 3
- Resources (copybook/file) shared by >=3 programs: 31

## Gate thresholds

- PROCEED if dynamic share >= 15%, OR max depth >= 3, OR shared-data fan >= 5.

## Signals firing

- dynamic dispatch 55% >= 15%
- call depth 3 >= 3
- shared-data fan 31 >= 5

## Interpretation

PROCEED → a call/data-coupling oracle could plausibly beat grep; build Phase 1.
STOP → grep is sufficient for CardDemo-scale decomposition; verdict: **not a fit**.

## Known Phase 0 limitation

`file_ops` (and the shared-data fan derived from it) captures only bare COBOL file verbs; CICS file I/O (`EXEC CICS READ FILE(...)`) is NOT captured, so the coupling metric under-counts the online/CICS programs. Coupling here is a lower bound — Phase 1 (ProLeap) closes this.
