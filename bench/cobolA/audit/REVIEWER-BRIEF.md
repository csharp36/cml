# COBOL reviewer brief — adjudicate the modeling choices, not the parsing

**Time ask: ~30–45 minutes.** You don't need to read all 44 programs or re-derive any edges.
I need a COBOL/CICS practitioner's judgment on **seven modeling decisions** that a benchmark of mine
rests on. Each has a current assumption and a specific yes/no question. Skip to "How to respond."

## Context in three sentences

I built a static reachability oracle over **AWS CardDemo** (44 IBM Enterprise COBOL programs, pinned
at commit `59cc6c2`) to test whether delivering a call/transaction/data-coupling graph beats `grep`
for the kind of questions you'd ask when **decomposing a COBOL monolith into services**. The oracle's
*extraction* (does it read the edges that are in the source correctly?) is already cross-validated two
ways — a blind hand-audit and an independent GnuCOBOL-based second extractor that agrees 44/44. What
is **not** validated is whether the **relations I defined are the right ones for decomposition** —
and I can't validate that myself, because I'm a first-time COBOL reader and I designed both checks, so
they share my assumptions. That's the gap you'd close.

**What I do NOT need:** verification that specific edges were extracted correctly (covered), or a
full code review. **What I DO need:** "is this the right way to *model* the relation, or would a
mainframe practitioner draw the boundary differently?"

Reference material if you want it: `audit/KEY-AUDIT.md` (the full edge derivation with file:line
citations), `oracle2-gnucobol/CROSS-CHECK.md` (the second-extractor method). Corpus is under
`corpus/app/` once `clone_corpus.sh` has run.

---

## The seven decisions

### 1. (Highest stakes) Does a *signoff/return* `XCTL` count as a reachability edge?

- **Construct.** Every online screen, on PF3/signoff, does `XCTL` back to the signon program
  `COSGN00C`, which then re-dispatches to a menu. Example — `COSGN00C.cbl:231–237`:
  `EXEC CICS XCTL PROGRAM ('COADM01C')` / `PROGRAM ('COMEN01C')`.
- **Our assumption.** An `XCTL` is an `XCTL`: we count it as a control-transfer edge regardless of
  whether it's *forward* navigation or a *signoff/return*. Consequence: `any screen → COSGN00C →
  COMEN01C/COADM01C → every screen`, so the ~23-program online tier becomes **one
  strongly-connected component** — every online program "reaches" every other.
- **The question.** For *decomposition* (deciding service boundaries), is the signoff/return transfer
  a real reachability edge — or is it navigational plumbing that should be **excluded**, leaving only
  forward navigation? **This single choice is the dominant driver of our headline result;** if it's
  wrong, the win is much smaller and the online tier is not one blob.

### 2. Is the *physical file identity* the `ASSIGN` ddname?

- **Construct.** `SELECT TRANSACT-FILE ASSIGN TO TRANFILE` — a program-local *logical* name bound to a
  *ddname*. Two programs may share a logical name but assign different ddnames (real example:
  `CBTRN03C.cbl:29` assigns `TRANSACT-FILE`→`TRANFILE`, while `CBACT04C` assigns its `TRANSACT-FILE`→
  `TRANSACT` — different ddnames).
- **Our assumption.** Two programs are "data-coupled" iff they share a **ddname** (or a DB2 table). We
  treat the ddname as the canonical physical-file identity.
- **The question.** Is the **ddname** the right identity — or, since the ddname→dataset binding is
  itself resolved in **JCL** (not in the COBOL, and not in this corpus), should coupling be keyed on
  the actual **dataset/catalog name**? Could two programs sharing ddname `TRANFILE` in *different jobs*
  legitimately touch *different* datasets (false coupling), or the reverse (same dataset, different
  ddnames — false split)? Is ddname a good enough proxy for a corpus with no JCL?

### 3. Is a never-reassigned `VALUE 'LIT'` field a *static* dispatch target?

- **Construct.** `XCTL PROGRAM(CDEMO-TO-PROGRAM)` where `MOVE LIT-MENUPGM TO CDEMO-TO-PROGRAM` and
  `05 LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'`. Also the `WS-PGM-*` `VALUE 'LIT'` fields in the
  `COPAUS*` programs.
- **Our assumption.** If a field carries a `VALUE 'LIT'` and is never reassigned from a non-literal,
  we resolve its `XCTL`/`CALL` target to that literal (transitive constant propagation through `MOVE`
  chains). The original oracle treated some of these as unresolvable; the audit resolved them.
- **The question.** Is resolving `VALUE`-initialized constants through `MOVE` chains correct COBOL
  semantics for "what can this dispatch to" — or are there reinitialization / `REDEFINES` / `INITIALIZE`
  gotchas that make this unsafe in general?

### 4. Are commarea *passthrough* targets correctly **excluded** as runtime-dynamic?

- **Construct.** `MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM` then `XCTL PROGRAM(CDEMO-TO-PROGRAM)` —
  i.e. "return to whoever called me," where `CDEMO-FROM-PROGRAM` is an inbound `CARDDEMO-COMMAREA`
  field set by the caller. Likewise `RETURN-TO-PREV-SCREEN` passthroughs.
- **Our assumption.** These have no in-program literal target, so we **exclude** them and declare them
  a "ceiling" (our reachability answers are a lower bound on these specific runtime back-edges).
- **The question.** Is excluding them the right call for decomposition — or do these caller-provided
  back-edges materially change service boundaries (they connect a program back to its *callers*), such
  that they should be modeled as edges-to-all-known-callers rather than dropped?

### 5. Should the `OCCURS` menu dispatch be **role-conditional**?

- **Construct.** `COMEN01C` dispatches via `XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))` over an
  `OCCURS` table of 11 program names. But each entry also has a user-type, and the code gates on it —
  `COMEN01C.cbl:137`: `… CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'` (admin-only options).
- **Our assumption.** We harvest **all** program-name literals in the table as reachable from the menu,
  **unconditionally** — ignoring the user-type guard.
- **The question.** For decomposition, is "all statically-possible targets" the right relation (so
  unconditional harvest is correct) — or does the user-type gate mean some edges are **conditional**
  (a normal user can't reach admin programs from this menu) and shouldn't be counted as unconditional
  reachability?

### 6. Is the CICS **transaction → entry-program** map derived correctly?

- **Construct.** We parse `corpus/app/csd/CARDDEMO.CSD` (plus variant CSDs) to map each CICS
  transaction id (e.g. `CM00`, `CA00`, `CAUP`, `CPVS`) to its entry program, then compute
  `txn_reach(T) = {entry} ∪ closure(entry)`.
- **Our assumption.** The CSD `DEFINE TRANSACTION(...) PROGRAM(...)` entries are the authoritative
  txn→program map, and the corpus CSD files are complete for the transactions we ask about.
- **The question.** Is the CSD the right and complete source for transaction entry points in this
  corpus, or are there transactions defined elsewhere (e.g. dynamically, or in JCL/PCT/PPT artifacts
  not present) that we'd miss?

### 7. Is *corpus-only* node scope the right boundary?

- **Construct.** We drop external runtime stubs from the graph: `CEE3ABD`, `CEEDAYS`, `CBLTDLI`,
  `MQ*`, `MVSWAIT`, `COBDATFT`, `CSUTLDTC` is kept (it's in-corpus).
- **Our assumption.** Only the 44 corpus programs are nodes; Language Environment / DB2 / MQ / IMS
  runtime entry points are not reachability targets for decomposition.
- **The question.** For carving services, is dropping those external calls correct — or does a
  dependency on `CBLTDLI` (IMS) / `MQ*` (messaging) / DB2 represent an **external coupling** that a
  decomposition map should surface as an edge to "the platform," not silently drop?

---

## How to respond

For each decision, one line is enough:

| # | decision | AGREE / DISAGREE | if DISAGREE: how would you model it, and does it change the answers a lot or a little? |
|---|----------|------------------|----------------------------------------------------------------------------------------|
| 1 | signoff `XCTL` as edge | | |
| 2 | ddname = file identity | | |
| 3 | `VALUE`-through-`MOVE` static target | | |
| 4 | exclude commarea passthrough | | |
| 5 | unconditional `OCCURS` harvest | | |
| 6 | CSD txn→program map | | |
| 7 | corpus-only node scope | | |

**The one I care about most is #1** — it's the difference between "the win is large" and "the win is
narrow," and it's a pure modeling judgment a practitioner can make in seconds that I cannot make
credibly. If you only answer one, answer that one.

Anything you mark DISAGREE becomes a real finding: it means a same-mind blind spot the hand-audit and
the second extractor both missed, which is exactly what this review exists to catch.
