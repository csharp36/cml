# REVIEW — AI Adversarial DRY RUN (NOT independent human validation)

> ⚠️ **STATUS BANNER — READ FIRST.**
> This document was produced by an **AI model (Claude)**, acting as an adversarial reviewer of the
> seven modeling decisions in `REVIEWER-BRIEF.md`. It is **a DRY RUN, not mind-independent
> validation.** The author of the artifacts under review used AI assistance of the same lineage, so
> this reviewer is *correlated* with the author's reasoning and shares its potential blind spots. It
> therefore **does NOT discharge the common-mode-failure gap** that `CROSS-CHECK.md` explicitly flags
> as still open ("a COBOL-literate second reader, or an orthogonal method"). **Do not cite this file as
> the independent COBOL practitioner review.** Its value is narrower: (a) it verifies the cited
> constructs against the real corpus source; (b) it grounds each decision in external IBM COBOL/CICS
> references; (c) it surfaces candidate disagreements a human reviewer should arbitrate — most
> importantly a re-characterization of the decision-#1 "signoff" edge that the brief's own framing gets
> wrong at the source level.
>
> Every source citation below was read directly from the pinned corpus (`59cc6c2`) during this review.

---

## Response table (the brief's seven decisions)

| # | decision | AGREE / DISAGREE | if DISAGREE: how to model it, and impact |
|---|----------|------------------|------------------------------------------|
| 1 | signoff `XCTL` as edge | **DISAGREE (re-characterization)** | The `screen → COSGN00C` edge is **not** a "signoff" — in source it is the **EIBCALEN=0 pseudo-conversational cold-start guard** (no-commarea first-entry / lost-session branch). It is still a real static XCTL edge, so the "an XCTL is an XCTL → one SCC" answer is *literal-static correct*. But the brief's **rationale is wrong**, and under the more defensible decomposition relation (drop the cold-start/lost-session re-entry guard, keep forward + parent-menu navigation) the online tier is **NOT one SCC** and the headline win shrinks. **Impact: LARGE** — this is the dominant driver and the framing is mislabeled. |
| 2 | ddname = file identity | **AGREE (with caveat)** | ddname is the best available identity *for a corpus with no JCL*, and the `CBTRN03C` correction is sound. Caveat: ddname→DSN is resolved per-job in JCL, so ddname is a **proxy** that can both over- and under-couple vs the true catalog DSN. Should be stated as an explicit assumption. **Impact of the caveat: small** for this corpus (the CSD `DSNAME(...)` values corroborate the ddname split). |
| 3 | `VALUE`-through-`MOVE` static target | **AGREE** | Correct COBOL semantics for "what can this dispatch reach." The specific fields are `WORKING-STORAGE` `PIC X(8) VALUE 'LIT'` constants never reassigned from a non-literal; `VALUE` is illegal on a `REDEFINES` subject (so the named fields can't self-overlay), and there is no `INITIALIZE`/`REDEFINES`-overwrite of them in source. **Impact: none** (decision is correct). |
| 4 | exclude commarea passthrough | **AGREE** (for the stated *lower-bound* relation) | Excluding `MOVE CDEMO-FROM-PROGRAM …` is correct as a *floor*. But these are real **caller-back-edges** that matter for decomposition; model them separately as "edge-to-all-known-callers" in a companion *upper-bound* graph. **Impact on current answers: none** (audit shows no closure depends on a ceiling); impact on *completeness of a decomposition map: medium*. |
| 5 | unconditional `OCCURS` harvest | **AGREE** | "All statically-possible targets" (may-reach over-approximation) is the right relation for decomposition recall, and conditional/role edges are a *refinement*, not a correction. Crucially, in **this** corpus the user-type gate is **inert in the regular menu** (all 11 `COMEN02Y` entries are `USRTYPE 'U'`; the admin copybook `COADM02Y` has no usrtype field at all), so even a role-conditional model would not drop any edge here. **Impact: none** for this corpus. |
| 6 | CSD txn→program map | **AGREE** | `DEFINE TRANSACTION(...) PROGRAM(...)` is the authoritative txn→entry binding, and `csd.py` reads it correctly (binds to the `TRANSACTION` block, not to `TRANSID(...)` on `PROGRAM` defs — which is the right CICS precedence). Two latent footguns flagged below (cross-CSD TXN-id collision = last-file-wins; multiple programs claiming the same `TRANSID(CPVD)`), neither of which changes the asked answers. **Impact: small/none** for the asked transactions. |
| 7 | corpus-only node scope | **AGREE (with caveat)** | Dropping LE/DB2/MQ/IMS stubs is right for an *intra-corpus reachability* graph. But `CBLTDLI`(IMS), `MQ*`, and DB2 are **external platform couplings** a decomposition map must surface — model them as edges to a small set of synthetic "platform" nodes rather than silently dropping. **Impact on current reachability answers: none; on decomposition completeness: medium.** |

**Tally: 4 clean AGREE (#2 caveated, #3, #5, #7 caveated), 1 AGREE-for-stated-relation (#4), 1 DISAGREE (#1).**
The single DISAGREE is the highest-stakes one.

---

## Decision 1 — signoff/return `XCTL` as a reachability edge  *(highest stakes)*

### What I verified in source
- **`COSGN00C.cbl:230–240`** (read directly): after credential validation,
  `IF CDEMO-USRTYP-ADMIN` → `EXEC CICS XCTL PROGRAM ('COADM01C')` `ELSE` → `PROGRAM ('COMEN01C')`.
  This is the signon program's **forward, post-authentication dispatch** to a menu — *not* a return edge.
- The `screen → COSGN00C` direction is what the brief calls the "signoff/return." I checked three
  screens and found the **same systematic idiom**, and it is **not** the PF3/signoff action the brief
  describes:
  - **`COUSR01C.cbl:78–80`**: `IF EIBCALEN = 0  MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM  PERFORM
    RETURN-TO-PREV-SCREEN`. The literal `'COSGN00C'` is reached **only when `EIBCALEN = 0`** — i.e. the
    transaction was entered with **no commarea** (cold start / lost pseudo-conversational session).
    The actual **PF3 handler** is `COUSR01C.cbl:93–95`: `WHEN DFHPF3  MOVE 'COADM01C' …` → returns to
    its **parent admin menu**, not to signon.
  - **`COBIL00C.cbl:107–109`**: `IF EIBCALEN = 0  MOVE 'COSGN00C' …`; and **`:128–135`** `WHEN DFHPF3`
    → `MOVE 'COMEN01C'` (parent menu) or the dynamic caller. Same split.
  - **`COMEN01C.cbl:196–203`** `RETURN-TO-SIGNON-SCREEN`: `IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
    MOVE 'COSGN00C'` then `XCTL PROGRAM(CDEMO-TO-PROGRAM)` — again a **default/empty-state guard**, not
    a user signoff.

So the load-bearing cycle is really:
`any screen —(EIBCALEN=0 cold-start guard)→ COSGN00C —(post-auth forward dispatch)→ COMEN01C/COADM01C`.

### External references (IBM CICS semantics)
- `EXEC CICS XCTL` transfers control **permanently with no return** — "similar to a GO TO," the issuing
  program is terminated and the target runs at the same logical level
  ([IBM CICS TS XCTL](https://www.ibm.com/docs/en/cics-ts/6.x?topic=summary-xctl);
  [ibmmainframer XCTL](https://www.ibmmainframer.com/cics-tutorial/cics-xctl-control-command/)). So an
  XCTL *is* a genuine, unconditional control-transfer edge — the brief's "an XCTL is an XCTL" is
  defensible **as graph mechanics**.
- The `IF EIBCALEN = 0` branch is the **canonical pseudo-conversational first-entry test**: "The first
  time you enter the program, EIBCALEN is 0: no commarea has been given to the program. You can test IF
  EIBCALEN = 0 to check for first time execution"
  ([tutorialspoint Pseudo Programming](https://www.tutorialspoint.com/cics/cics_pseudo_programming.htm);
  [mainframegeek, Demystifying COMMAREA](https://mainframegeek.wordpress.com/2011/07/16/demystifying-commarea/)).
  Routing to the signon program when no session commarea is present is the standard "no/expired session
  → re-authenticate" safety branch.
- For reachability semantics, the textbook stance is **may-reach over-approximation**, with **guard
  predicates / conditional edges** as a recognized refinement
  ([Conditional Dyck-CFL Reachability, ESOP'17](https://lingming.cs.illinois.edu/publications/esop2017.pdf);
  [Reps, Program Analysis via Graph Reachability](https://research.cs.wisc.edu/wpis/papers/tr1386.pdf)).

### Strongest refutation attempt (against the brief's framing, both directions)
1. **Against "exclude it":** XCTL is unconditional control transfer; the cold-start edge is a *real*
   statically-present transfer that *can* execute (every screen genuinely can land in COSGN00C on a
   dropped session). A sound may-reach graph must include it. By that standard the brief's *current
   answer* (one SCC) is literally correct, and excluding the edge would make the graph *unsound* as a
   "what can transfer to what" relation. This is the strongest case **for** keeping it.
2. **Against "keep it as the brief frames it":** the brief justifies the edge as a *signoff/return*
   ("Every online screen, on PF3/signoff, does XCTL back to the signon program COSGN00C"). **Source
   refutes that specific claim.** PF3 returns to the **parent menu** (COMEN01C/COADM01C), not to
   COSGN00C; the `→ COSGN00C` literal fires on the **EIBCALEN=0 cold-start / lost-session guard**. So
   the edge that closes the SCC is an *error/re-auth plumbing* edge, which is exactly the category a
   decomposition practitioner is most likely to treat as **infrastructure, not a business dependency**.

### Verdict — DISAGREE (re-characterization; the answer can stand, the rationale cannot)
- The **graph-mechanics answer is correct**: as an unconditional may-reach edge set, screen→COSGN00C→menu
  is real and the online tier *is* one SCC. If the benchmark's relation is "transitive closure of all
  CALL/XCTL/LINK," keep it.
- But the **brief's stated reason is factually wrong**, and that matters for the *decomposition* use
  the benchmark is selling. A mainframe practitioner carving services would almost certainly classify
  the `EIBCALEN=0 → COSGN00C` cold-start/re-auth branch and the `COSGN00C → menu` post-login dispatch as
  **session/navigation infrastructure** (the "login + router" cross-cutting concern), not as evidence
  that, say, the Bill-Pay screen has a business dependency on the User-Delete screen. Under the more
  decomposition-meaningful relation — **forward navigation + parent-menu return, excluding the
  cold-start/lost-session re-entry guard** — the SCC dissolves: screens fan *out* to their feature
  programs and *up* to their immediate menu, but they no longer all reach each other.
- **Net:** this is the load-bearing modeling choice and it rests on a **mislabel**. The honest writeup
  is: "the headline 'one SCC' result depends on counting the pseudo-conversational cold-start/re-auth
  XCTL to the signon router as a reachability edge; that is sound under a literal may-reach relation but
  is navigation/infrastructure under a decomposition relation, and the win narrows substantially if it
  is excluded." The benchmark should **report both** (with-guard SCC vs without-guard DAG-ish tiers),
  not bake the SCC in as *the* answer. The brief already half-flags this in its closing note and in
  `KEY-AUDIT.md` ("A note on the signoff-bridge modeling choice") — but it frames the excluded relation
  as "forward navigation only," whereas the precise, source-grounded distinction is **"exclude the
  EIBCALEN=0/empty-state re-entry guard to the signon router."**

---

## Decision 2 — physical file identity = `ASSIGN` ddname

### What I verified in source
- **`CBTRN03C.cbl:29`** `SELECT TRANSACT-FILE ASSIGN TO TRANFILE`; **`:33`** `SELECT XREF-FILE ASSIGN
  TO CARDXREF`.
- **`CBACT04C.cbl:53`** `SELECT TRANSACT-FILE ASSIGN TO TRANSACT`; **`:34`** `SELECT XREF-FILE ASSIGN
  TO XREFFILE`.
- So the two programs share the **logical** SELECT names `TRANSACT-FILE` / `XREF-FILE` but assign
  **different ddnames** (`TRANFILE`/`CARDXREF` vs `TRANSACT`/`XREFFILE`). The audit's "false-couple on
  logical name" correction is **proven from source**. Confirmed `CBTRN03C` shares ddname `TRANFILE`
  only with `CBTRN01C`/`CBTRN02C`.

### External references
- The `ASSIGN TO` clause "associates the logical file name to the JCL-declared DDNAME"; the ddname is
  the 8-char tag the program uses, while the **DSN is the physical catalog name resolved in JCL**, and
  the two must match for the program to reach the dataset
  ([simotime JCL reference](http://www.simotime.com/jclone01.htm);
  [geekinterview ddname vs dsname](https://www.geekinterview.com/question_details/11134)).

### Strongest refutation attempt
- The DSN-binding lives in **JCL, which is absent from this corpus.** Two jobs can route the same ddname
  to **different** datasets (→ the ddname coupling is a *false positive*), or route **different**
  ddnames to the **same** dataset (→ a *false split / false negative*). Therefore the *truly* canonical
  physical identity is the catalog DSN, and ddname is only a proxy. A purist would say "you keyed on the
  wrong thing — you keyed on the JCL *handle*, not the *dataset*."

### Verdict — AGREE (with an explicit-assumption caveat)
- Given **no JCL in the corpus**, ddname is the **best available** and far better than the logical name
  (which is program-local and demonstrably reused with different bindings). The correction direction is
  right.
- I partially *resolved* the refutation using the corpus's own CSD: `CARDDEMO.CSD` `DEFINE FILE(...)`
  blocks carry `DSNAME(AWS.M2.CARDDEMO.*.VSAM.KSDS)`, and the distinct ddnames there map to distinct
  DSNs (e.g. `TRANSACT`→`...TRANSACT.VSAM.KSDS`, the CARDDEMO group is internally consistent). For VSAM
  files defined to CICS this corroborates that the ddname split is not masking a shared DSN. Batch QSAM
  ddnames (e.g. `TRANFILE`) still depend on JCL not present, so a residual proxy risk remains.
- **Recommendation:** state explicitly "coupling keyed on `ASSIGN` ddname as a proxy for the catalog
  DSN, because JCL is out of corpus; for CICS-defined VSAM files the CSD `DSNAME` corroborates the
  split." Impact of the residual risk on the asked questions: **small.**

---

## Decision 3 — `VALUE 'LIT'`-through-`MOVE` as a static dispatch target

### What I verified in source
- **`COACTVWC.cbl:168–169`** `05 LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'`; used at **`:336`**
  `MOVE LIT-MENUPGM TO CDEMO-TO-PROGRAM` → **`:349–350`** `XCTL PROGRAM(CDEMO-TO-PROGRAM)`.
- **`COPAUS0C.cbl:33–35`**: `05 WS-PGM-AUTH-SMRY … VALUE 'COPAUS0C'`, `WS-PGM-AUTH-DTL VALUE
  'COPAUS1C'`, `WS-PGM-MENU VALUE 'COMEN01C'` — plain `WORKING-STORAGE` constants.
- These are scalar `PIC X(8)` items carrying a single literal `VALUE`, used as XCTL/LINK operands; the
  audit resolves the operand to the literal.

### External references
- The `VALUE` clause assigns the **initial** value of a `WORKING-STORAGE` item
  ([MainframeMaster VALUE clause](https://www.mainframemaster.com/tutorials/cobol/value-clause)).
- **`VALUE` is mutually exclusive with `REDEFINES`** — "you must NOT code the VALUE clause with a
  redefining item"
  ([MainframeMaster VALUE](https://www.mainframemaster.com/tutorials/cobol/value-clause);
  [TutorialBrain REDEFINES](https://www.tutorialbrain.com/mainframe/cobol-redefines/)). So none of these
  `VALUE` fields is itself a REDEFINES subject.

### Strongest refutation attempt (the `REDEFINES`/`INITIALIZE` gotchas the brief asks about)
1. **A *different* field could `REDEFINES` the constant** and overwrite its bytes at runtime → the
   `VALUE` no longer holds when the XCTL fires. *Refuted for these fields:* I found no `REDEFINES` of
   `LIT-MENUPGM`/`WS-PGM-*`, and the const-prop only claims a target if the field is "never reassigned
   from a non-literal," which a REDEFINES-overwrite would have to be detected as. (A human should still
   confirm no overlay exists for each resolved field — this is the residual risk.)
2. **`INITIALIZE` resets the group** → per IBM rules, `INITIALIZE` (without `REPLACING`) sets
   alphanumeric items to `SPACES`, **ignoring `VALUE`**, which would blank the literal. *Refuted in
   practice:* these are dispatch-name constants in `WORKING-STORAGE`; there is no `INITIALIZE` of the
   enclosing group before the XCTL in the inspected programs. Again a per-field check is the residual.
3. **`MOVE` truncation/justification** — `PIC X(8)` exactly fits the 8-char program names; no truncation.

### Verdict — AGREE
Resolving never-reassigned `VALUE 'LIT'` scalars through `MOVE` chains is **correct COBOL "may-dispatch"
semantics**. The classic hazards (REDEFINES overlay, INITIALIZE-to-spaces) are real *in general* but do
not apply to the specific fields (VALUE precludes self-REDEFINES; no INITIALIZE/overlay found). This is
exactly the kind of resolution a sound may-reach extractor should do. Decision is correct; the only
honest footnote is "verified no REDEFINES/INITIALIZE overwrite of each resolved field."

---

## Decision 4 — exclude commarea passthrough targets as runtime-dynamic

### What I verified in source
- **`COACTVWC.cbl:334–339`**: `IF CDEMO-FROM-PROGRAM EQUAL LOW-VALUES OR SPACES  MOVE LIT-MENUPGM
  (=COMEN01C)  ELSE  MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM` — the **same XCTL site** (`:349`) has
  a *literal* branch (kept) and a *commarea-passthrough* branch (excluded).
- **`COBIL00C.cbl:128–135`** PF3: `MOVE 'COMEN01C'` (literal, kept) vs `MOVE CDEMO-FROM-PROGRAM`
  (passthrough, excluded). `CDEMO-FROM-PROGRAM` is an inbound `CARDDEMO-COMMAREA` field set by the
  caller, so it has no in-program literal.

### External references
- COMMAREA carries caller→callee state across pseudo-conversational turns; its contents are whatever the
  *caller* placed there ([mainframegeek COMMAREA](https://mainframegeek.wordpress.com/2011/07/16/demystifying-commarea/)).
  So `CDEMO-FROM-PROGRAM` is genuinely runtime-dynamic from the callee's static viewpoint.

### Strongest refutation attempt
- These passthroughs are precisely the **caller back-edges**: "return to whoever invoked me." For
  *decomposition* they are not noise — they encode that a program is coupled to its **callers**, which
  is exactly the kind of bidirectional coupling that complicates carving a service boundary. Dropping
  them silently *understates* coupling. The right model is **"edge to every known caller"** (computable
  from the inverse of the resolved edge set), not "no edge."

### Verdict — AGREE for the stated *lower-bound* relation; recommend a companion upper bound
- As a **floor** (the benchmark's declared stance), excluding them is correct and the audit shows **no
  closure depends on a ceiling** (every screen those back-edges could re-add is already reachable via
  resolved literals). So the **current answers do not change.**
- But for the *decomposition map* the benchmark is pitched at, the honest enhancement is a parallel
  **may-back-reach** graph that adds `program → {all known callers}` for each commarea-passthrough XCTL.
  This would be the *ceiling* companion to the existing floor. **Impact on current numbers: none;
  completeness of the decomposition story: medium.**

---

## Decision 5 — unconditional `OCCURS` menu harvest vs role-conditional edges

### What I verified in source
- **`COMEN01C.cbl:136–143`**: `IF CDEMO-USRTYP-USER AND CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'` →
  "No access - Admin Only option" (the role gate the brief cites).
- **`COMEN02Y.cpy:25–98`** (read in full): all **11** populated options carry `USRTYPE 'U'`
  (lines 29,35,41,47,53,59,65,72,78,84,90). **None is `'A'`.** So in the regular menu the admin gate at
  `:136` is **structurally unreachable** — no entry can satisfy `USRTYPE = 'A'`. (The comment at
  `COMEN02Y.cpy:69` hints option 8 was *once* "Admin Only" but the active `VALUE` is `'U'`.)
- **`COADM02Y.cpy`** (admin menu, read in full): the OCCURS record is `NUM/NAME/PGMNAME` only — **there
  is no `USRTYPE` field at all** (`:56–59`). The admin menu has no per-option role gate.

### External references
- Standard reachability is **may-reach over-approximation**; role/guard conditions are a *conditional
  edge* refinement layered on top, not a correction to the may-reach set
  ([Conditional Dyck-CFL Reachability, ESOP'17](https://lingming.cs.illinois.edu/publications/esop2017.pdf)).

### Strongest refutation attempt
- "A normal user provably cannot reach admin programs from this menu, so those edges are *conditional*
  and counting them as unconditional reachability over-claims." This is a legitimate critique **of the
  relation in the abstract** — and for a *role-aware* decomposition you'd want the guard recorded.

### Verdict — AGREE
- For decomposition recall, "**all statically-possible targets**" is the correct relation; the role gate
  is a refinement you can *add* (as edge metadata), not a reason to *drop* edges from the may-reach set.
- Decisively for **this corpus**: the refutation has **no teeth** because the regular menu's gate is
  inert (every entry is `'U'`), and the admin menu has no gate field. So whether you model the gate or
  not, **the harvested edge set is identical.** The unconditional harvest is both the simplest and the
  source-accurate choice here. **Impact: none.** (Worth a one-line note in the writeup that the
  user-type gate is present but vacuous in this snapshot, so the benchmark sidesteps the conditional-edge
  question rather than resolving it.)

---

## Decision 6 — CSD `DEFINE TRANSACTION(...) PROGRAM(...)` as the txn→program source

### What I verified in source
- **`CARDDEMO.CSD`**: `DEFINE TRANSACTION(CAUP) PROGRAM(COACTUPC)` (`:306`), `CM00→COMEN01C` (`:399`),
  `CA00→COADM01C` (`:327`), `CC00→COSGN00C` (`:378`), plus all `CT0x/CU0x/CB00/CR00`.
- **`CRDDEMO2.csd`** (auth-ims-db2-mq): `DEFINE TRANSACTION(CPVS) PROGRAM(COPAUS0C)` (`:49`),
  `CPVD→COPAUS1C` (`:39`), `CP00→COPAUA0C` (`:59`).
- **`CRDDEMOD.csd`** (txn-type-db2): `CTLI→COTRTLIC`, `CTTU→COTRTUPC`.
- **`oracle/csd.py`** (read): splits text on `DEFINE`, binds each `DEFINE TRANSACTION(id)` to the first
  `PROGRAM(...)` **inside its own block**, and **unions across all `*.csd` files** (`merged.update`,
  sorted order). It does **not** read `TRANSID(...)` on `DEFINE PROGRAM` blocks.

### External references
- In CICS RDO, the **transaction→program binding is the PCT-equivalent `DEFINE TRANSACTION ... PROGRAM(...)`**
  in the CSD; the `DEFINE PROGRAM` (PPT-equivalent) registers the program but the *transaction's* entry
  program is the one named on the `TRANSACTION` resource
  ([mainframestechhelp DEFINE TRANSACTION](https://www.mainframestechhelp.com/tutorials/cics/define-transaction.htm);
  [mainframegurukul PCT/PPT](http://www.mainframegurukul.com/tutorials/programming/cics/cics-resource-definition.html)).
  So `csc.py` reading the `TRANSACTION` block (and ignoring the convenience `TRANSID(...)` on a `PROGRAM`
  def) matches CICS precedence.

### Strongest refutation attempt (and two latent footguns I found)
1. **TXN-id collisions across CSD files → last-file-wins.** `csd.py` does `merged.update(...)` over CSDs
   in sorted path order. If two CSDs `DEFINE TRANSACTION(X)` with different programs, the result is
   order-dependent and silent. I did not find such a collision among the *asked* transactions, but the
   method is non-robust. (Recommend: assert no conflicting redefinition, or namespace per CSD.)
2. **A program can claim a `TRANSID` that the authoritative `DEFINE TRANSACTION` assigns elsewhere.**
   `CRDDEMO2.csd` has **both** `DEFINE PROGRAM(COPAUS1C) TRANSID(CPVD)` (`:29`) **and**
   `DEFINE PROGRAM(COPAUS2C) TRANSID(CPVD)` (`:36`), while `DEFINE TRANSACTION(CPVD) PROGRAM(COPAUS1C)`
   (`:39`) is authoritative. `csd.py` correctly takes **COPAUS1C** (it ignores `TRANSID` on PROGRAM
   defs) — so the right answer falls out, but only because the parser uses the correct source of truth.
   A naive `TRANSID`-based extractor would have mis-mapped CPVD. Good that this one doesn't.
3. **Transactions defined outside the corpus CSDs** (dynamic `START TRANSID`, JCL-invoked, or
   PCT/PPT-in-other-artifacts) would be missed. For the *asked* transactions all entries are present in
   the corpus CSDs, so this is a scope statement, not an error.

### Verdict — AGREE
The CSD `DEFINE TRANSACTION ... PROGRAM(...)` is the right and (for the asked transactions) complete
authoritative source, and the parser uses the correct CICS precedence (transaction resource over
program-level `TRANSID`). The two footguns (cross-CSD union last-wins; duplicate `TRANSID` claims) are
**latent robustness issues that don't change the asked answers** but should be noted in the method.

---

## Decision 7 — corpus-only node scope (drop LE/DB2/MQ/IMS stubs)

### What I verified in source
- `KEY-AUDIT.md` and `AUDIT.md` enumerate the dropped externals (`CEE3ABD`, `CEEDAYS`, `CBLTDLI`,
  `MQ*`, `MVSWAIT`, `COBDATFT`) and keep `CSUTLDTC` (in-corpus). Batch `CALL` sites in
  `CBTRN02C/CBACT04C/CBTRN03C` resolve to `CEE3ABD` (LE abend) etc. — confirmed these are runtime APIs,
  not corpus programs.

### External references
- `CBLTDLI` is the **IMS DL/I** call interface; `MQOPEN/GET/PUT` are **IBM MQ** APIs; `CEE*` are
  **Language Environment** services — all external platform entry points, not application programs
  (general IBM platform knowledge corroborated across the CICS/COBOL references above).

### Strongest refutation attempt
- For *decomposition*, a dependency on `CBLTDLI` (IMS), `MQ*` (messaging), or DB2 is a **first-class
  external coupling**: a service that talks to IMS or MQ cannot be carved without carrying that platform
  dependency. Silently dropping the edge **erases a real architectural constraint** — arguably the most
  important kind for "what can become its own service."

### Verdict — AGREE (with a decomposition-completeness caveat)
- For the **intra-corpus reachability** relation the benchmark measures (which corpus programs reach
  which), dropping non-corpus leaves is correct and necessary — they aren't nodes and would otherwise
  inflate fan-out with non-programs.
- For a **decomposition map**, the refutation is valid: model the externals as a *small fixed set of
  synthetic platform nodes* (`@IMS`, `@MQ`, `@DB2`, `@LE`) and keep the edges to them, so the map
  surfaces "this program is coupled to the platform." **Impact on current reachability answers: none;
  on the decomposition narrative: medium.** This is a presentation/completeness enhancement, not a bug.

---

## Candidate findings (the high-value outputs — every DISAGREE + ambiguities)

### DISAGREE (1) — the load-bearing one
1. **Decision #1 is a mislabel, and the mislabel is load-bearing.** The `screen → COSGN00C` edge that
   closes the online SCC is **not a PF3/signoff transfer** (as the brief states) — in source it is the
   **`EIBCALEN = 0` pseudo-conversational cold-start / lost-session guard** (`COUSR01C.cbl:78–80`,
   `COBIL00C.cbl:107–109`), while **PF3 actually returns to the parent menu** (`COUSR01C.cbl:93–95` →
   `COADM01C`; `COBIL00C.cbl:128–135` → `COMEN01C`). COSGN00C then forward-dispatches to the menu
   (`COSGN00C.cbl:230–240`). The "one SCC" answer is *sound under a literal may-reach relation*, but a
   decomposition practitioner would treat the cold-start/re-auth + signon-router edges as
   **session/navigation infrastructure**, not business dependencies. **The benchmark should report two
   relations** — with-guard (one SCC, the current headline) and without-the-cold-start-guard (tiers, a
   much smaller win) — and must **stop calling the bridge a "signoff."** This is exactly the
   common-mode blind spot `CROSS-CHECK.md` warned was unadjudicated.

### Caveats that should become explicit assumptions (not disagreements, but findings)
2. **#2 ddname is a JCL-less proxy.** State it. For CICS VSAM files the CSD `DSNAME(...)` corroborates
   the ddname split; batch QSAM ddnames still rest on absent JCL.
3. **#5 the user-type gate is vacuous in this snapshot.** All `COMEN02Y` entries are `'U'`; `COADM02Y`
   has no usrtype field. The benchmark *sidesteps* the conditional-edge question rather than answering
   it — worth saying plainly so the result isn't over-generalized to role-gated menus elsewhere.
4. **#6 parser robustness footguns.** Cross-CSD TXN-id collisions resolve last-file-wins silently
   (`csd.py` `merged.update`), and two `DEFINE PROGRAM` blocks both claim `TRANSID(CPVD)` in
   `CRDDEMO2.csd`; the parser gets the right answer only because it correctly ignores program-level
   `TRANSID`. Neither changes the asked answers; both are latent.
5. **#4/#7 the benchmark measures a *floor* intra-corpus reachability, but is *pitched* as a
   decomposition map.** Two real coupling classes are deliberately dropped: **caller back-edges**
   (commarea passthrough, #4) and **external platform couplings** (IMS/MQ/DB2/LE, #7). Current numbers
   don't depend on them, but a decomposition deliverable arguably should surface both (as a may-back-reach
   companion graph and synthetic platform nodes).

### Where the brief itself is ambiguous / under-specified
- **The word "signoff" in decision #1.** The brief conflates three distinct source idioms — (a) PF3
  return-to-parent-menu, (b) EIBCALEN=0 cold-start/lost-session → signon, (c) COSGN00C post-auth forward
  dispatch — under one "signoff/return" label. The real modeling fork is **(b) specifically**: include
  the cold-start re-entry guard or not. The brief's alternative ("forward navigation only") is *not* the
  right cut either, because PF3 parent-menu returns (a) are legitimate navigation a decomposition map
  should keep. The precise question is: **"is the EIBCALEN=0 re-entry-to-router guard a dependency?"**
- **"data-coupled" (#2) is undefined as to direction/RW.** Sharing a ddname read-only by both vs
  write-by-one/read-by-other are very different couplings for decomposition; the relation treats all
  shared-ddname pairs identically. Not wrong for a coupling *screen*, but under-specified.
- **#5 "all statically-possible targets" vs "all reachable at runtime"** — the brief doesn't say whether
  the benchmark *intends* path-insensitive may-reach (it does, per `AUDIT.md §B`); making that explicit
  would pre-empt the conditional-edge objection.

---

### Bottom line for the benchmark owner
Six of seven decisions are **defensible as written** (with #2/#7 needing an explicit-assumption
footnote and #5 needing a "gate is vacuous here" note). The **one that matters most, #1, is the one I
disagree with** — not because the SCC math is wrong, but because the edge is **mislabeled** and the
label is doing the persuasive work. Re-characterize it as the pseudo-conversational cold-start/re-auth
guard, report the with-/without-guard relations side by side, and the benchmark's claim becomes honest
and still interesting (the *type-resolved* `OCCURS`/`VALUE` dispatch win survives regardless; it's only
the "entire online tier is one blob" headline that is contingent on counting infrastructure edges).
This DRY RUN does **not** substitute for the COBOL-literate human reviewer the cross-check calls for —
it just hands that reviewer a sharpened, source-grounded #1 to arbitrate.
