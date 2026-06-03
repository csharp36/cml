# Independent Audit — ProLeap COBOL Dependency Oracle (Task B5)

**Subject:** `bench/cobolA/oracle/raw-edges.json` (per-program edges, ProLeap extractor) and
`bench/cobolA/oracle/oracle.json` (transitive closures, data-access, copybook fan, direct edges),
built over the 44-program AWS CardDemo corpus at `bench/cobolA/corpus/`.

**Auditor stance:** skeptical verifier. Every claim below was checked by hand-reading the actual
COBOL source (grep / Read) **independently of the extractor**, and in two cases by re-running the
extractor jar in isolation to reproduce a finding. The oracle's own output was never trusted as
evidence for itself.

**Branch:** `feat/cobol-phase1-proleap`. **Corpus:** AWS CardDemo (44 `.cbl`, 41 `.cpy` in scope).

---

## Method

| Audit task | How verified (independent of extractor) |
|---|---|
| 1. Menu fan-out | Read `app/cpy/COMEN02Y.cpy` + `app/cpy/COADM02Y.cpy` menu tables; read `COMEN01C.cbl`/`COADM01C.cbl` XCTL sites; set-compared the program-name literals against `resolved_dynamic_xctl_link`; confirmed every target exists as a real `.cbl`. |
| 2. Static call chain | `grep "CALL '"` on `CBTRN02C`, `CBACT04C`, `CBTRN03C`, `CBSTM03A`, `CBSTM03B`; compared to `static_calls`. |
| 3. File / DB2 access | `grep -rln` programs that READ/WRITE/SELECT `ACCOUNT-FILE`; read the actual `EXEC SQL` blocks of all 4 DB2 programs; compared to `data_access` and `db2_tables`. |
| 4. Copybook fan | Comment-aware grep (`COPY <name>` with col-7 ≠ `*`) for `CVACT01Y`, `COCOM01Y`, `CVTRA05Y`; compared to `copybook_fan`. |
| 5. Transitive closure | Recomputed `COMEN01C`'s closure by hand from `direct_call_edges`; traced `CSUTLDTC → "CEEDAYS"` in source. |
| 6. Over-connect gate | Read `COPAUS0C`/`COPAUS1C` dispatch; confirmed `CDEMO-TO-PROGRAM` / `WS-PGM-AUTH-FRAUD` are in the unresolved set and did NOT fan out to the menu list. |
| CSD / txn gap | `find` for `.csd`; read `app/csd/CARDDEMO.CSD` transaction→program defines. |

Corpus totals confirmed: **44/44 programs** present in both files, **44 resources**, **66 copybooks
tracked**, **total `unresolved_dynamic_count` = 3** across the whole corpus.

---

## Findings

### 1. Menu fan-out (the marquee claim) — **AGREE (exact)**

**Main menu (`COMEN01C`).** `COMEN02Y.cpy` lines 28–89 declare exactly 11 option program literals
(`COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C, COTRN02C, CORPT00C,
COBIL00C, COPAUS0C`) in the `OCCURS 12`-redefined table. `COMEN01C.cbl` has two distinct dynamic
XCTL sites:
- lines 157 & 185 — `XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))` → the 11 table entries;
- line 202 — `XCTL PROGRAM(CDEMO-TO-PROGRAM)`, where line 199 does `MOVE 'COSGN00C' TO
  CDEMO-TO-PROGRAM` (the only literal reaching that field).

`resolved_dynamic_xctl_link` = the 11 table entries **+ `COSGN00C`** = 12. Set comparison:
**exact match — 0 spurious, 0 missing.**

**Admin menu (`COADM01C`).** `COADM02Y.cpy` declares 6 option literals (`COUSR00C, COUSR01C,
COUSR02C, COUSR03C, COTRTLIC, COTRTUPC`); `COADM01C.cbl:101/166` MOVEs `'COSGN00C'` to
`CDEMO-TO-PROGRAM`. Oracle = those 6 **+ `COSGN00C`** = 7. **Exact match.**

All 18 distinct resolved targets exist as real source files (3 live under the IMS/DB2/MQ and
transaction-type-DB2 sub-trees — `COPAUS0C`, `COTRTLIC`, `COTRTUPC`). **The marquee claim is true.**

### 2. Static call chain — **AGREE (exact)**

| Program | Source `CALL 'literal'` | `static_calls` |
|---|---|---|
| `CBTRN02C` | `CEE3ABD` (l.711) | `[CEE3ABD]` ✅ |
| `CBACT04C` | `CEE3ABD` (l.632) | `[CEE3ABD]` ✅ |
| `CBTRN03C` | `CEE3ABD` (l.630) | `[CEE3ABD]` ✅ |
| `CBSTM03A` | `CBSTM03B` ×13 + `CEE3ABD` | `[CBSTM03B, CEE3ABD]` ✅ (correctly deduped) |
| `CBSTM03B` | none | `[]` ✅ |

No invented or missed static calls. (Vendor/runtime stubs such as `CEE3ABD`, `CEEDAYS`, `CBLTDLI`,
`MQOPEN/GET/PUT/CLOSE`, `MVSWAIT` are captured as call targets — correct, though they are external
runtime APIs, not corpus programs.)

### 3. File / DB2 access set — `ACCOUNT-FILE` **AGREE**; `db2_tables` **DISCREPANCY (oracle wrong)**

**File access — AGREE.** `data_access["ACCOUNT-FILE"]` = `[CBACT04C, CBTRN01C, CBTRN02C]`. Independent
`grep -rln ACCOUNT-FILE` returns exactly those 3 programs; each has a `SELECT ACCOUNT-FILE` +
`READ ACCOUNT-FILE` (CBACT04C/CBTRN02C also `REWRITE`). **Exact match.**

**DB2 tables — WRONG.** `db2_tables` for all 4 SQL programs is the single token **`CARDDEMO`**. Reading
the actual `EXEC SQL`:

| Program | Real table(s) in source | Oracle `db2_tables` |
|---|---|---|
| `COTRTLIC` | `CARDDEMO.TRANSACTION_TYPE` | `[CARDDEMO]` ❌ |
| `COTRTUPC` | `CARDDEMO.TRANSACTION_TYPE` | `[CARDDEMO]` ❌ |
| `COBTUPDT` | `CARDDEMO.TRANSACTION_TYPE` | `[CARDDEMO]` ❌ |
| `COPAUS2C` | `CARDDEMO.AUTHFRDS` | `[CARDDEMO]` ❌ |

**Root cause (confirmed in source):** `ProgramExtractor.SQL_TABLE` =
`\b(?:FROM|INTO|UPDATE|JOIN)\s+([A-Z0-9_]+)`. The capture class `[A-Z0-9_]` excludes `.`, so on
`FROM CARDDEMO.TRANSACTION_TYPE` it captures only the **schema qualifier** `CARDDEMO` and stops at
the dot. The result is that the entire DB2 stratum reports the schema name instead of the
schema-qualified table, and **collapses two genuinely distinct tables (`TRANSACTION_TYPE` and
`AUTHFRDS`) into one bogus "table" `CARDDEMO`.** No host-variable false positives were found (the
`INTO :host-var` of FETCH/INSERT starts with `:`, which the regex rejects). **The `db2_tables`
stratum is not trustworthy at table granularity and must be treated as a known defect / Phase-2 fix.**

### 4. Copybook fan — **AGREE (exact, comment-aware)**

| Copybook | Oracle fan | Independent comment-aware grep |
|---|---|---|
| `CVACT01Y` | 14 | 14 ✅ |
| `COCOM01Y` | 21 | 21 ✅ |
| `CVTRA05Y` | 11 | 11 ✅ |

Important verification of correctness: a **naïve** grep for `CVACT01Y` returns 16 programs, but
`COCRDSLC` and `COCRDUPC` carry `*COPY CVACT01Y.` (commented out — `*` in column 7). The oracle
**correctly excludes both**; the comment-aware grep also returns 14. The `CopyScanner` honors COBOL
fixed-format comment lines. No discrepancy.

### 5. Transitive closure (`COMEN01C`) — **AGREE (internally consistent)**

Hand-recomputed from `direct_call_edges`: COMEN01C's 12 direct targets, plus `CSUTLDTC` (reached via
`COACTUPC`/`CORPT00C`/`COTRN02C`) and `CEEDAYS` (reached via `CSUTLDTC` — confirmed
`CSUTLDTC.cbl:116 CALL "CEEDAYS"`). Closure = 14, excludes the root `COMEN01C` itself, includes the
already-direct `COSGN00C`. Back-edges (e.g. `COBIL00C → COMEN01C`) are present in the direct-edge map
and correctly do not create spurious closure members. **Consistent.**

### 6. Over-connect gate (`COPAUS0C` / `COPAUS1C`) — **AGREE on the gate; but see the under-resolution note below**

The 3 (and only 3) unresolved dynamic dispatches in the whole corpus are:
- `COPAUS0C` — `CDEMO-TO-PROGRAM` (count 1)
- `COPAUS1C` — `WS-PGM-AUTH-FRAUD` and `CDEMO-TO-PROGRAM` (count 2)

Neither program's `resolved_dynamic_xctl_link` is non-empty, and **neither fanned out to the menu
list** — the over-connect gate holds. The menu-table dispatch did not bleed into these dispatch-by-
variable programs. ✅

---

## Declared completeness ceiling (honest bounds — read before citing)

### A. The 3 unresolved dynamic dispatches — named, with cause

1. **`COPAUS0C` → `CDEMO-TO-PROGRAM`** (XCTL at l.323 & l.675). In source this field is assigned from
   *variables* — `MOVE WS-PGM-AUTH-SMRY/WS-PGM-MENU/WS-PGM-AUTH-DTL TO CDEMO-TO-PROGRAM`
   (l.192/236/316, themselves `VALUE 'COPAUS0C'/'COMEN01C'/'COPAUS1C'`) — and from one *literal*
   `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM` (l.669).
2. **`COPAUS1C` → `CDEMO-TO-PROGRAM`** (XCTL l.368): assigned only from the variable
   `WS-PGM-AUTH-SMRY` (l.168/185).
3. **`COPAUS1C` → `WS-PGM-AUTH-FRAUD`** (`05 WS-PGM-AUTH-FRAUD ... VALUE 'COPAUS2C'`, l.35; used at
   l.249).

**Why the const-prop can't see them.** The propagator builds `field → {literals}` from (a) direct
`MOVE 'literal' TO field` and (b) `VALUE 'literal'` *initializers of that same field*. It is **not**
transitive across variable→variable moves: `MOVE WS-PGM-MENU TO CDEMO-TO-PROGRAM` carries no literal,
so the `VALUE` on `WS-PGM-MENU` never reaches `CDEMO-TO-PROGRAM`. Cases 2 and 3 are therefore correct,
expected floor behavior — a single-hop const-prop genuinely cannot resolve a value-carrying chain
without alias/copy tracking.

> **Additional finding on case 1 (under-resolution, reproduced):** `COPAUS0C`'s field *does* receive a
> direct literal — `MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM` at l.669, byte-identical to `COMEN01C`'s
> l.199 which **does** resolve. By the documented semantics this should have resolved `COPAUS0C`'s
> `CDEMO-TO-PROGRAM` to at least `{COSGN00C}`. It does not. I reproduced this by running the extractor
> jar on `COPAUS0C` **in isolation** (`DYN_XCTL_RESOLVED=0, DYN_UNRESOLVED=1`) and on `COMEN01C` in
> isolation (`COSGN00C` resolved) — same pattern, divergent result. The l.669 `MOVE` is not folded
> into the field-literals map for this file. Most plausibly ProLeap (run with
> `ignoreSyntaxErrors=true`) drops/omits that statement node while parsing this large IMS/DB2/MQ
> program, so `handleMove` never sees it. **Net effect: `COPAUS0C → COSGN00C` is a missed edge.** This
> is a *floor* error (under-report), consistent with the conservative design, not an over-connect.

So `unresolved_dynamic_count = 3` is the honest *upper bound on what const-prop declined to resolve*;
one of those three (`COPAUS0C`/`CDEMO-TO-PROGRAM`) is additionally an extractor/parse miss rather than
a true semantic dead-end, and the real-but-unresolved edge `COPAUS0C → COSGN00C` is absent from the
graph.

### B. Constant propagation is a flow-insensitive **over-approximation** (by design)

A dispatch like `XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))` resolves to **all 11** menu
programs, not the one a given run selects. The oracle reports the *union of every program reachable
through that operand*, not a path-sensitive answer. This is **completeness over precision** — exactly
the "type-resolution oracle" framing: recall is the metric this corpus is built to defend, and the
resolved sets are an upper bound on the true per-run target. Cite it for "which programs *can* this
dispatch reach," never "which one it *will* reach."

### C. Missing copybooks (minimal effect)

- **Vendor / system macros** referenced but never resolvable: `DFHAID`, `DFHBMSCA` (CICS), and the MQ
  series `CMQV, CMQODV, CMQGMOV, CMQPMOV, CMQMDV, CMQTML`. These are IBM-supplied and are never part
  of application source; ProLeap emits "could not find copy book" warnings (non-fatal). They appear as
  leaves in `copybook_fan` but carry no app structure — no effect on the call graph.
- **`CSSTRPFY` / `CSUTLDWY`:** the task framed these as "not in this snapshot," but both **do** exist
  as `app/cpy/CSSTRPFY.cpy` and `app/cpy/CSUTLDWY.cpy`. The accurate statement is that **no program
  actively `COPY`s either** (verified: zero non-comment `COPY` sites), so their correct absence from
  `copybook_fan` is *expected*, not a gap. Effect: none.

### D. `cics_txn_entry` is empty — real gap, with the source identified

`cics_txn_entry` is `[]` for every program, by design: the CICS transaction→program binding lives in
the **CSD/RDO**, not in COBOL. That mapping exists in the corpus at:

- **`bench/cobolA/corpus/app/csd/CARDDEMO.CSD`** — contains `DEFINE TRANSACTION(CAUP) ...
  PROGRAM(COACTUPC)` style entries (the online transaction stratum), plus the 3 variant CSDs
  `app/app-transaction-type-db2/csd/CRDDEMOD.csd`, `app/app-vsam-mq/csd/CRDDEMOM.csd`,
  `app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd`.

This is the follow-up source for a transaction→program stratum (Phase 2/3); **currently a genuine
gap** in the oracle, not derivable from the COBOL alone.

---

## Verdict

**Trustworthy as a ground-truth benchmark target — for the strata it was built to cover — with two
named caveats.**

**Reliable (verified exact against source):**
- Static `CALL 'literal'` edges.
- Static + resolved-dynamic **XCTL/LINK control flow**, including the marquee CICS menu fan-out
  (`COMEN01C`/`COADM01C`, exact, 0 spurious / 0 missing) and the OCCURS-table dispatch resolution.
- **Copybook fan** (comment-aware, exact on all spot checks).
- File-level **`data_access`** (record/FD granularity).
- **Transitive call closures** (internally consistent with the direct-edge graph).
- The **over-connect gate** holds: variable-based dispatch in the COPAUS programs did not fan out to
  the menu list; corpus-wide unresolved count is exactly the declared 3.

**Lower bound (under-reports; safe to trust a present edge, do not assume completeness):** resolved
dynamic dispatch. Const-prop is single-hop, so variable→variable chains (`COPAUS1C`'s two operands)
stay unresolved by design, **and** at least one true literal edge (`COPAUS0C → COSGN00C`) is missed
due to an apparent ProLeap parse omission in a large IMS/MQ file. Treat resolved-dynamic edges as a
**floor**.

**Upper bound (over-approximates):** any *resolved* dynamic dispatch is the union of all reachable
targets, not the run-time selection. Trust it for reachability/recall, not for "the" target.

**Known-wrong stratum — do not cite at table granularity:** `db2_tables`. The schema-qualified table
regex captures only the schema (`CARDDEMO`), losing the table name and merging distinct tables
(`TRANSACTION_TYPE`, `AUTHFRDS`). Fix the `SQL_TABLE` pattern to span `schema.table` before relying on
this field.

**Gap (not wrong, just absent):** `cics_txn_entry` — the transaction→program binding lives in
`app/csd/CARDDEMO.CSD` and is a Phase-2/3 addition.

Net: a strong, citable oracle for **call/XCTL reachability, copybook fan, file access, and transitive
closures** (the differentiator strata), with `db2_tables` flagged as a defect and the txn-entry layer
flagged as a known future stratum.
