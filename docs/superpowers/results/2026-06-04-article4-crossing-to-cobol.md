# Does the win cross a language barrier? Taking a type-resolution oracle to 60-year-old COBOL

![On the left, a familiar modern type-graph constellation glows under the index's light. A
hard seam runs down the middle. On the right, the same light falls on a fixed-format COBOL
deck — most of it stays dark, but one bright cluster (a CICS menu's dispatch table) lights up
where grep's flashlight couldn't reach.](article4-hero.jpeg)

*The Java win was type resolution. COBOL has no types — does the* shape *of the win survive the jump?*

*Findings writeup — 2026-06-04. Part 4 of a series. Single-corpus, small-n; every number is
reproducible from `bench/cobolA/` of [github.com/csharp36/cml](https://github.com/csharp36/cml).
Corpus: AWS CardDemo pinned at `59cc6c2`.*

---

## TL;DR

[Article 3](https://medium.com/@csharp36/where-a-semantic-code-index-finally-beats-grep-type-resolved-reachability-fd8a077da688) ended what I'd called a trilogy with one clean result: the semantic index I'd
built beats `grep` decisively on exactly one kind of question — **type-resolved reachability**,
"list every concrete class that *is-a* `X`, transitively" — at recall 0.97 / F1 0.88 in a single
query, versus iterative `grep` at 0.32. That win is made of *types*: it exists because a compiler
resolves edges across files and modules that a text search structurally can't see.

Then a thought I couldn't shake: **COBOL has no type hierarchy at all.** So the exact win can't
transfer — there are no interfaces to find implementers of. But the *shape* of the win might.
Article 3's real lesson wasn't "types are magic"; it was "an index wins when it can close a graph
`grep` is structurally blind to." COBOL has graphs like that too — call graphs, transaction
reachability, data coupling — the very relations you need to carve a mainframe monolith into
services. So I ran a pre-registered benchmark on a language I had **never written a line of**, to
ask one question: does the win cross the language barrier?

The honest answer is **partly.** I built a reachability oracle for **AWS CardDemo** (44 IBM
Enterprise COBOL programs), served it to an agent through one thin MCP tool, and raced it against
a fairly-resourced `grep` over **80 questions in 5 strata**, scored against an **independent
hand-audit**. The verdict, pre-registered before the run, came back **`AMBIGUOUS`**.

![Five strata, grep F1 vs oracle F1. The two dynamic-dispatch strata — call_closure (0.145) and
txn_reach (0.212) — show a chasm to the oracle's 1.000. The three static strata — data_access
(0.749), data_coupling (0.674), copybook_fan (1.000) — show grep keeping pace.](article4-results.jpeg)

The win's shape **does** transfer — but it narrows, and then narrows again under scrutiny. Where
COBOL dispatches dynamically (a CICS menu picking the next program out of a runtime-indexed table),
the oracle is decisive and `grep` falls off a cliff: F1 ~0.15–0.21 → 1.00. Where COBOL is static
(which file a program reads, which copybook it includes), a careful `grep` user keeps pace and the
oracle wins only on *convenience*, not *completeness*. Averaged across the gating strata, that mix
lands at AMBIGUOUS: a real differentiator that doesn't blanket the whole problem. And when I ablate
the corpus's navigation-shell degeneracy (§9), most of the dynamic-dispatch *breadth* turns out to be
one structural fact counted ~21 times — the genuine win is real but concentrated on the ~5 nodes that
actually own the dispatch, and `grep` ties on the navigation leaves. Below is the whole thing, including the part
where an independent audit caught two bugs in my own oracle — and where, because that audit and the
oracle shared one (first-time-COBOL) mind, I brought in a **second oracle built on a different
compiler** to check the first. It agrees 44/44, which says the edges are read correctly; it can't say
the edges were the right ones to count, and I'll be clear about that line.

---

## 1. Why COBOL — and why I had no business touching it

I write Java. My comfort zone is JVM bytecode, Gradle, a type system a compiler checks for me. COBOL
is none of that. It is roughly **sixty years old** (1959), it is written in *fixed-format* columns
where the position of a character on the line is semantically load-bearing, and the first time I
opened a CardDemo program my honest reaction was that it looked less like code than like a tax form
someone had taught to compute.

And yet — and this is the part that surprised me when I started reading about modernization — COBOL
is not a museum piece. It is the **live substrate of the financial world.** The commonly cited
figures (take them as folklore-grade, but directionally real) are that hundreds of billions of lines
of COBOL are still in production, that the majority of the world's banking transactions touch a
COBOL program somewhere in their path, and that ATM networks, card processors, and insurance core
systems still run on mainframes executing code older than most of the engineers now asked to
maintain it. The people who wrote it are retiring. The systems can't.

So there is a real, expensive, industry-wide problem: **decompose the COBOL monolith into something
modern** — Java services, usually — without breaking the bank that's running on it. And the first
thing you need to do that safely is a *map*: if I'm going to peel `CBTRN03C` out into its own
service, what calls it, what does it call, which files does it share with the rest of the estate,
which transactions reach it? That is a **reachability** question. And reachability is exactly the
family of question my index won on in Java.

Which set up a clean, slightly nervy experiment. I am a textbook outsider to this language. If a
tool's advantage only shows up for an expert who already knows where the bodies are buried, it isn't
much of a tool. So: could *I*, knowing essentially nothing about COBOL going in, stand up an oracle
that beats `grep` on the questions that matter for decomposition? And would it beat `grep` for the
right reason — completeness over a graph — or just because I'd hand-tuned it to a corpus I'd peeked
at?

I want to be honest that this outsider framing cuts **both** ways, because it matters for how much
you should trust what follows. On one side it's a guard against hand-tuning: I couldn't have
reverse-engineered the oracle to flatter a corpus I didn't understand. On the other side it raises a
real worry — the validation rests on a hand-audit, and *I wrote the audit too*. A first-time reader
checking his own first-time-reader's extractor is exactly the setup where a shared misunderstanding
slips through unseen. I'll come back to this at the audit, take it seriously, and bring in a second
toolchain to attack it — because it's the load-bearing weakness of the whole study, not a footnote.

## 2. The corpus: AWS CardDemo

You cannot run an honest benchmark on a codebase you can't fully check, and you cannot get real
mainframe code to test on (it's proprietary and locked behind the very institutions that won't let
it out). The compromise is **[AWS CardDemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo)**
— a credit-card management application AWS publishes specifically as a *mainframe modernization
sample.* It is purpose-built to look like the real thing without being anyone's production secret.

What's in it:

- **44 COBOL programs**, fixed-format IBM Enterprise COBOL — the actual dialect, not a teaching
  toy.
- A **CICS online tier**: the interactive side. CICS is IBM's transaction monitor; think of it as
  the mainframe's web server and session layer fused together. CardDemo's online tier is a system of
  menus and full-screen forms — sign on, main menu, account view, card list, transaction add, and an
  admin menu — that a 3270 terminal user navigates.
- A **batch tier**: the overnight jobs that post transactions, compute interest, and write reports.
- **VSAM files** (the mainframe's indexed flat-file storage) and **DB2** tables for persistence,
  plus a small **IMS/MQ** authorization sub-app.

Two properties made it the right test bed. It's **clean enough** that ProLeap — an open-source COBOL
parser — parses **44 of 44** programs without choking, so I could build a real graph from it. (Worth
saying plainly, because it's easy to misread: 44/44 *parsing* is necessary, not sufficient. It means
the front end didn't choke — it says nothing about whether the graph I extracted from the parse is
*correct*. Both bugs I'll describe below were post-parse extraction bugs, sitting downstream of a
perfectly happy 44/44.) And it's **small enough to hand-audit**: 44 programs is a quantity a human
reviewer can read end to end in source, which (spoiler) is what saved the result. I pinned everything
— source index, oracle, and answer key — to a single commit (`59cc6c2`) so there's zero version skew
between the arms.

The honest cost of that cleanliness shows up later as a caveat: CardDemo is *tidier* than a real
50-year-old estate, and one of its structural quirks (a navigationally-complete menu) makes the
oracle's win look slightly bigger than it should. I'll come back to that.

## 3. What makes COBOL hard for `grep`

This is the section I most wanted to write, because it's where "I'd never seen this language"
turned into the actual finding. Most of COBOL is, frankly, *easy* for `grep` — easier than Java in
places. The hard part is concentrated in a few constructs, and understanding them is understanding
the whole result. Four things matter.

**1. Fixed-format and continuation lines.** Classic COBOL ignores columns 1–6 (sequence numbers)
and treats column 7 specially (a `*` there means the line is a comment; a `-` means *this line
continues the previous one, mid-token*). A statement can be split across lines in the middle of a
literal. This alone breaks naive `grep`: the string you're searching for may not exist on any single
line. It's the kind of thing you don't anticipate until a regex that "obviously works" silently
returns nothing — which, foreshadowing, is exactly the bug the audit caught in *my* code.

**2. Copybooks (`COPY`).** COBOL's include mechanism. A `COPY CVTRA05Y` statement textually pastes a
shared *copybook* — usually a record layout or a set of constants — into the program at compile
time. Copybooks are how dozens of programs share a file's field definitions. Finding "who copies
`X`" is a flat literal search; `grep` does it perfectly. (It scored **1.000** in the benchmark.)

**3. `SELECT … ASSIGN` — logical vs. physical file names.** Here's the first place `grep` can be
*subtly* wrong. A COBOL program refers to a file by a *program-local logical name* it makes up
(`SELECT TRANSACT-FILE`), and separately `ASSIGN`s that logical name to a *physical* dataset / ddname
(`ASSIGN TO TRANFILE`). Two programs can both `SELECT TRANSACT-FILE` and yet be assigned to two
*different* physical files — so they are **not** actually sharing data, even though the logical names
match. To answer "which programs share a physical file" correctly, you must resolve the
`ASSIGN`, not the `SELECT`. A determined `grep` user *can* do this (it's still literal text), and in
my benchmark the `grep` arm does exactly that — so it keeps pace. But it's a trap a casual search
walks straight into. (It's also, again foreshadowing, the second bug the audit caught in my oracle —
in the opposite direction.)

**4. The wall: CICS `XCTL` through an `OCCURS`/`VALUE` dispatch table.** This is the one. It is the
COBOL analog of Java's "an interface implemented two modules away," and it is where `grep`
structurally cannot follow.

In the CICS online tier, one program hands control to the next with `EXEC CICS XCTL
PROGRAM('COMEN01C')` — *transfer control to program `COMEN01C`.* When the target is a literal
string like that, `grep` is fine: it sees the name. But CardDemo's menus don't do that. The main
menu, `COMEN01C`, decides where to go based on which option the user typed, and it stores the
candidate program names in a **table** — a COBOL `OCCURS` array of fields, each initialized with a
`VALUE` clause to a program name:

```cobol
01  CDEMO-MENU-OPT.
    05  CDEMO-MENU-OPT-ENTRY OCCURS 10 TIMES.
        10  CDEMO-MENU-OPT-PGMNAME  PIC X(8).
*       ... elsewhere, VALUE clauses load 'COACTVWC', 'COCRDLIC', ...
```

and then dispatches with the *runtime-indexed* element:

```cobol
EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) END-EXEC.
```

There is **no program name on that line.** The target is `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)` — "the
program name at position `WS-OPTION` in the table" — and which name that resolves to depends on data
loaded elsewhere by `VALUE` clauses. To know the set of programs this one menu can reach, you have
to *enumerate the table*: find the `OCCURS`, collect every `VALUE`-initialized program name in it,
and treat each as an edge. That is a static-analysis move. `grep` cannot make it, the same way it
couldn't follow a Java interface that extends another interface across modules.

The number tells the story. Asked "what does `COMEN01C` reach," **`grep` recovers 2 programs** —
the handful of literal/MOVE-resolvable transfers. **The oracle recovers 22** — the rest of the
~23-program online cluster, every screen wired through the dispatch table. Same wall as Article 3,
different language. *That* is the win's shape, and it's the thing I wanted to know would transfer.

One precision, because it's the exact line of the wall (and I'll hold myself to it later). `grep`'s
blindness here is *not* to the program *names* — they sit as plain `VALUE 'COACTVWC'` literals in the
copybook, and `grep "VALUE 'CO"` would list all eleven. The wall is one step in: **binding the
runtime `OCCURS` index to those copybook literals and composing the transitive closure** — knowing
that `CDEMO-MENU-OPT-PGMNAME(WS-OPTION)` *is* those eleven names and then walking onward. That's the
move static analysis makes and text search can't. (The `grep` arm is pre-registered not to harvest
copybook `VALUE` clauses as dispatch targets — that enumeration is precisely the construct under
test, so it's a drawn line, not a handicap.) So when I say "`grep` sees 2 of 22," read it as
*closure-composition*, not *name-recovery* — the stronger and more precise claim.

## 4. The setup: two arms, five strata, pre-registered

The design mirrors Article 3 deliberately. Two **deterministic** arms answer the *same* questions —
no stochastic agent in the loop, because I wanted to measure each delivery mechanism's *structural
ceiling*, not a model's mood on a given day.

- **The `grep` arm** — fairly resourced, not a strawman. It follows literal `CALL`/`XCTL` targets,
  chases `MOVE 'literal' TO var` for variable dispatch, and resolves `SELECT … ASSIGN TO <ddname>`
  so file questions are answered at the physical-file level. The *only* thing it cannot do is
  enumerate an `OCCURS`/`REDEFINES`/`VALUE` dispatch table — which is the construct under test, not a
  handicap I imposed.
- **The `proxy` arm** — the same questions answered through one `cobol_reachability(node, kind)` MCP
  round-trip, backed by a precomputed oracle. The oracle is built by a Java/ProLeap extractor that
  parses all 44 programs into a reachability graph. **One call per question.**

The **5 strata** are the relations that matter for carving a monolith:

![The five strata over AWS CardDemo. 1 call_closure — programs transitively reachable via CALL +
CICS XCTL/LINK (gating; keyed by independent audit). 2 data_access — programs that read/write
physical file R (gating; oracle, grep-verifiable). 3 data_coupling — programs sharing a physical
file or DB2 table (gating; independent audit). 4 copybook_fan — programs that COPY copybook C
(non-gating; oracle). 5 txn_reach — programs reachable when CICS transaction T starts (non-gating;
independent audit).](article4-strata.jpeg)

80 questions total (`call_closure` 21, `data_access` 10, `data_coupling` 13, `copybook_fan` 15,
`txn_reach` 21). The answer key is **hybrid**: the three grep-hard strata (1, 3, 5) are keyed by an
**independent hand-audit** — a reviewer reading the COBOL and CSD by hand and deriving each answer
from source *without consulting the oracle* — while the two grep-tractable static strata (2, 4) use
oracle answers that are themselves grep-verifiable. 55 of the 80 are audit-keyed.

And — this is the part that makes it a real experiment rather than a demo — the **verdict was
pre-registered before the run.** Macro-averaged over the gating strata (1–3): `GREENLIGHT` if proxy
F1 ≥ 0.70 **and** grep F1 ≤ 0.45 **and** gap ≥ 0.30; `NOT_A_FIT` if gap < 0.15; `AMBIGUOUS`
otherwise. I wrote those thresholds down, then ran it, then reported what fell out — including the
unglamorous outcome.

## 5. The audit that moved the result

Here is the part I'm proudest of, and it's a methodology story, not a COBOL one.

I did not trust my own oracle. The answer key for the three grep-hard strata was built by an
**independent source audit** — the reviewer derived every answer from the COBOL by hand, blind to
what the oracle said. On first pass, that audit **disagreed with my ProLeap oracle on 29 of 32
questions.** Twenty-nine out of thirty-two. If I'd graded the proxy arm against the oracle's own
output — the cardinal sin of "never grade a tool with its own data" — I'd have shipped a glowing,
wrong result.

Instead I treated every disagreement as a bug report and chased each one back to source. Two were
**real defects in my oracle:**

1. **A missed control-flow bridge (a completeness bug).** `COSGN00C`, the signon program — the
   application's *entry point* — contains two literal `EXEC CICS XCTL PROGRAM('COMEN01C')` /
   `PROGRAM('COADM01C')` statements. But the program name sat on a *continuation line*, and there was
   a **space before the paren**: `PROGRAM (` not `PROGRAM(`. My extractor's regex required `PROGRAM(`
   with no space, so it captured **zero** edges out of the entry program. The whole online graph hung
   off a node my parser thought was a dead end. (Remember §3's warning about continuation lines and
   regexes that "obviously work"? This was me walking into my own trap.) A second, compounding gap:
   the oracle didn't propagate `VALUE`-initialized constants through `MOVE LIT-X TO target` chains —
   the exact `LIT-MENUPGM VALUE 'COMEN01C'` idiom CardDemo uses — so even some resolvable edges were
   dropped.

2. **Logical vs. physical file identity (a precision bug).** Exactly the `SELECT`/`ASSIGN` trap from
   §3, and I'd fallen for it. My oracle keyed data coupling on the program-local *logical* `SELECT`
   name. But `CBACT04C` and `CBTRN03C` both `SELECT TRANSACT-FILE` / `XREF-FILE` while `ASSIGN`-ing
   them to *different* physical ddnames (`TRANSACT`/`XREFFILE` vs `TRANFILE`/`CARDXREF`). The oracle
   declared them coupled; they touch different files. A genuine false positive.

I fixed both **at the root** — the `XCTL` regex, transitive constant propagation through copy
`MOVE`s, and ddname-based file identity — as principled corrections a correct reachability oracle
*should* have, **not** as patches tuned to make the audit agree. After the fixes, the
independently-derived key and the oracle converged.

Now the honest accounting, because the convergence is the load-bearing claim of the whole study and
the first draft of this article overstated it. Three things you should know, all checkable in the git
history (`bench/cobolA`, `audit/KEY-AUDIT.md`):

- **The disagreement-then-convergence evidence lives on a *frozen* set of 32 questions.** The key for
  those 32 was committed *before* the oracle fixes — genuinely blind. 29 of 32 disagreed; after the
  two fixes, 0 of 32 disagreed. That sequence — frozen key, large disagreement, principled fix, clean
  convergence — is real corroboration, and it's the part I stand behind.
- **The key was then extended from 32 to 55 questions *after* the fixes.** The benchmark you'll see
  scored below uses 55 audited questions; the 23 added ones were derived post-fix, from the
  already-corrected structure. They're consistency checks *inside* the converged model, not fresh
  blind tests — so the "29/32 → 0" signal belongs to the 32, and I shouldn't (and now don't) wave a
  "0 across all 55" banner as if all 55 carried it. (The genuinely ambiguous cases — commarea
  "return to whoever called me" targets — were *excluded* as declared ceilings rather than left as
  residual deltas, which is part of why the remainder looks so clean.)
- **One person wrote both the oracle and the audit — me, the first-time COBOL reader.** So the audit
  is *method*-independent (hand-reading source vs running ProLeap) but not *mind*-independent. It
  caught the two bugs precisely because the two *methods* diverged there. What it structurally cannot
  catch is a modeling choice I got *consistently* wrong in both. And there's a live one: I counted the
  `screen → COSGN00C → re-dispatch` transfer as a reachability edge — which is what collapses the whole
  online tier into one mutually-reachable cluster. I originally called it a "signoff" edge. It mostly
  *isn't*: reading the source, that edge is the `IF EIBCALEN = 0 MOVE 'COSGN00C'` **cold-start /
  lost-session guard** every pseudo-conversational screen carries (`COBIL00C.cbl:107`,
  `COUSR01C.cbl:78`) — session plumbing, not a business dependency — while the real PF3 handler returns
  to the *parent menu* (`MOVE 'COMEN01C'/'COADM01C'`). Both the oracle and my audit treat that
  shell-transit as an edge; their agreement cannot validate the *choice*. And it's load-bearing in a
  specific way I'll show in §9: it's the dominant driver of the win's **breadth** (the degeneracy),
  even though the OCCURS dispatch is the driver of the win's **substance**. If shell-transit is the
  wrong relation for decomposition — and for "what's coupled to this screen," it probably is — both
  artifacts are wrong together, invisibly. This is exactly the common mode a same-mind check can't see.

That last point is the real hole, and "I treated the oracle's answers as a hypothesis to refute" —
true as it is — doesn't close it, because the refuter and the refuted share a skull. So I did the one
thing that actually attacks a shared-mind blind spot: I brought in a second toolchain that doesn't
share my parse.

## 6. The second oracle: a different compiler, to break the common mode

The two bugs the audit caught were both **post-parse extraction** bugs — a regex that missed
`PROGRAM (` with a space, a keying choice that confused logical and physical file names. That's the
layer to attack independently. So I built a second oracle from a deliberately different lineage and
diffed it against the first, *without* tuning it to agree.

The first oracle's front end is **ProLeap** (an ANTLR/Java COBOL parser). The second uses
**GnuCOBOL** (`cobc` — a C compiler, unrelated codebase) as the front end: I run its preprocessor
over all 44 programs to do the fixed-format column handling, continuation-line joining, and `COPY`
expansion — *including* the `OCCURS` menu copybooks — and then a freshly-written extractor (different
code, blind to the first oracle's answers) re-derives the edges from that compiler-normalized text.
The point is that the layer where bug #1 lived — turning fixed-format COBOL with continuation lines
into clean tokens — is now done by an independent compiler, not by my regex. If my normalization had
been quietly wrong, the two would disagree.

They don't. Corpus-filtered to the 44 programs, the two oracles agree on **44/44 programs** on all
three layers that matter — resolved control edges, transitive call closure, and data coupling. The
agreement is rich, not vacuous: `COMEN01C` resolves to its 12 direct edges and a 22-program closure
on both; the bug-2 case `CBTRN03C` couples to `{CBTRN01C, CBTRN02C}` and *not* `CBACT04C` on both.

And — because a test that can't fail proves nothing — I checked that the cross-check has teeth.
Re-inject bug #1 into the second extractor (require `PROGRAM(` with no space) and `COSGN00C`
collapses to zero edges again, and **21 of 44 programs** immediately disagree with the first oracle,
the signon-hub collapse rippling through every closure. So the second toolchain *would* have
shouted if ProLeap still had the bug. It doesn't shout. That independently confirms the two audited
bugs are really fixed — by a parser that shares none of my code.

Here's the part I have to be straight about, though, because it's the same discipline the rest of
this demands: **this hardens the parsing layer, not the modeling layer.** I wrote the second
extractor *after* the audit, knowing the answers, so it encodes the *same* modeling choices —
shell-transit-as-edge, ddname-as-identity, commarea-as-ceiling. Two implementations agreeing that the edges
are correctly read from source is real and worth having; it is *not* a second opinion on whether
those were the right edges to count. The residual fix for *that* — the thing neither a hand-audit nor
a second extractor I write can provide — is a COBOL-literate human reading the relation definitions,
or an orthogonal method like an actual CICS-aware compile-and-trace. I'm flagging the gap rather than
papering over it. ([`oracle2-gnucobol/CROSS-CHECK.md`](https://github.com/csharp36/cml/blob/main/bench/cobolA/oracle2-gnucobol/CROSS-CHECK.md) has the full method and the negative control.)

## 7. Results

Per-stratum means, scored against the hybrid key:

![Per-stratum results. grep F1 vs proxy F1, with n and gating flag. call_closure 0.145 / 1.000,
n=21, gating. data_access 0.749 / 1.000, n=10, gating. data_coupling 0.674 / 1.000, n=13, gating.
copybook_fan 1.000 / 1.000, n=15. txn_reach 0.212 / 1.000, n=21. Verdict AMBIGUOUS: gating
macro-average proxy 1.00, grep 0.523, gap 0.477.](article4-table-result.jpeg)

**Pre-registered verdict** (macro-average over gating strata 1–3):

```json
{ "decision": "AMBIGUOUS", "proxy_f1": 1.0, "grep_f1": 0.523, "gap": 0.477 }
```

The gap is **0.477** — wide. But `grep`'s gating average, **0.523**, *exceeds* the pre-registered
`GREP_MAX` of 0.45. So despite a near-half-point gap, the rules I wrote down in advance say this is
**not** a clean greenlight. I report it as it fell. I did **not** re-tune the gating after the fact —
even though scoring only the two strata `grep` structurally cannot close (`call_closure` +
`txn_reach`) would have handed me a triumphant headline of proxy 1.00 vs `grep` ~0.18. The honest
verdict is the AMBIGUOUS one.

## 8. Where the win is — and where it isn't

**Decisive: dynamic-dispatch reachability.** `call_closure` (grep 0.145) and `txn_reach` (grep
0.212) are the strata defined by the `OCCURS`-table dispatch from §3. This is the `COMEN01C` case —
`grep` recovers 2 of 22 — repeated across the menu system. Enumerating a runtime-indexed
program-name table is the type-resolution-shaped move `grep` can't make and static analysis can.
**The Java win's shape transferred here.** But read that 0.145 with care — a chunk of it is a
navigation artifact, not the OCCURS wall, and §9 takes the stratum apart to show how much. The
*substance* of the win is real; its *breadth* on this corpus is inflated.

**A tie: the static relations.** `data_access` (0.749), `data_coupling` (0.674), `copybook_fan`
(1.000) are flat literal lookups — `SELECT … ASSIGN`, `READ`/`WRITE`, `COPY`. A fairly-resourced
`grep` user resolves them correctly. The oracle is **no more complete** here; it just delivers the
answer in one call instead of several. (`grep`'s sub-1.0 on coupling is CICS file access it doesn't
fully chase, not a structural blindness.) On these relations the oracle's only edge is *convenience*
— and convenience is not the claim.

**Why that averages to AMBIGUOUS.** The pre-registered gating set (strata 1–3) mixes *one* grep-hard
relation with *two* grep-tractable ones. Averaging across them dilutes the dynamic-dispatch win below
the greenlight bar. The differentiator is real; it just doesn't blanket the *whole* decomposition
substrate the way type resolution blankets "find all implementers" in Java. In Java, nearly every
structural question routed through the type graph. In COBOL, only some of the decomposition questions
route through a graph `grep` can't close — and the rest are honest literal lookups.

## 9. Taking the win apart: how much is OCCURS, how much is navigation?

The first draft of this article *flagged* a worry here and left it qualitative; two reviews
(one of them the second-toolchain dry run) pushed me to actually measure it, and the measurement is
the most useful thing in the piece. So here it is.

CardDemo's online tier is a **navigationally-complete menu system**: the menus fan out to every
screen through the `OCCURS` table, and every screen transfers *back* to the shell — to its parent
menu (`MOVE 'COMEN01C'/'COADM01C'`, the PF3 return) and to `COSGN00C` (the `EIBCALEN = 0` cold-start
guard). Graph-theoretically that makes the ~23 online programs **one strongly-connected component**:
from any one you can reach all the others. The consequence is that every `call_closure` answer is
"the whole tier minus the seed" — I measured it: **all 21 closures are size 22, with mean pairwise
Jaccard 0.913.** That's a near-duplicate question set wearing 21 different hats. The win is being
counted ~21 times.

But here's the thing those shell edges are: **navigation, not dependency.** If I'm peeling `COBIL00C`
out into a service, the fact that it can "reach" `COCRDLIC` only by navigating *home through the menu*
does not couple them — the menu hub is precisely where you'd cut. So I ran the obvious experiment:
treat the shell (`COSGN00C` + the two menus) as a **decomposition boundary** — reachable, but
coupling doesn't propagate *through* it — and recompute every closure. (Full method and the per-node
table: [`oracle2-gnucobol/ABLATION.md`](https://github.com/csharp36/cml/blob/main/bench/cobolA/oracle2-gnucobol/ABLATION.md).) Three things fall out, and each answers a question the
qualitative caveat couldn't:

![Two panels. Left, call_closure degeneracy as mean pairwise Jaccard of the 21 answer-sets: 0.913
normal (through the shell) versus 0.242 with the hub ablated — the near-duplicate questions spread
out. Right, grep's F1 on call_closure: 0.145 normal versus 0.438 with the hub ablated, essentially
at the pre-registered 0.45 bar — most of the stratum's gap was the navigation shell, not the OCCURS
wall.](article4-ablation.jpeg)

- **The degeneracy was real and large.** Mean pairwise Jaccard drops **0.913 → 0.242**; closure
  sizes spread from a flat `[22]` to `[1..15]`. The SCC *was* doing most of the work of making the
  questions look numerous.
- **Most of the `call_closure` gap was the shell, not the wall.** `grep`'s F1 on this stratum rises
  from **0.145 to 0.438** under the boundary relation — essentially the pre-registered `grep ≤ 0.45`
  bar. On the **pure-navigation leaf screens** (`COBIL00C`, `COUSR01–03C`, `CORPT00C`, …) `grep`
  *ties* the oracle once you stop counting transit through the shell: those closures are just "the
  shell I return to," which `grep` reads fine.
- **The genuine win survives — concentrated.** It lives on the nodes that actually *own* dynamic
  dispatch: the menus (`COMEN01C`: oracle 15, `grep` 1; `COADM01C`: oracle 7, `grep` 1) and the
  handful of screens that forward-dispatch through `VALUE` literals (`COCRDLIC`, `COACTUPC`,
  `COTRTLIC` — `grep` 0). That's ~5 nodes, not 21.

So the honest revision of §8: the structural win is **real and is the one move `grep` cannot make**,
but on this corpus its *breadth* was a navigation artifact. "Wins a discriminating relation on the
handful of nodes where the OCCURS/VALUE dispatch actually lives" is the claim the data supports —
stronger and narrower than "wins `call_closure` 21 times." (Caveat on the caveat: the boundary I drew
is itself a modeling choice I made knowing the corpus — a defensible decomposition relation, not the
only one, and exactly the kind of call the still-open human COBOL review should adjudicate. The two
facts that *don't* depend on my choice are the Jaccard collapse and that `grep` ties on the
pure-navigation leaves.)

## 10. The honest caveats

I spent three articles refusing to oversell. Not starting now.

- **One small, clean corpus.** 44 programs, single sample. CardDemo is tidy *by construction*; real
  mainframe estates are messier — multiple dialects, copybook sprawl, genuinely dynamic `CALL`s whose
  targets aren't statically knowable at all.
- **SCC degeneracy** (§9), now quantified: the `call_closure` answers are near-uniform (mean pairwise
  Jaccard 0.913); ablating the navigation shell raises `grep`'s F1 on that stratum from 0.145 to 0.438
  and concentrates the surviving win on ~5 dispatch-owning nodes. The breadth of the win was largely
  one structure measured many times.
- **Proxy F1 ≈ 1.0 by construction.** The proxy serves the oracle; its score is a "perfect delivery"
  baseline, not evidence about robustness. The evidence is `grep`'s shortfall and the gap.
- **The oracle's validation is method- and toolchain-independent, but not yet mind-independent**
  (§5–6). A frozen 32-question blind audit (29/32 → 0 after two principled fixes) plus a second
  GnuCOBOL-lineage extractor (44/44 agreement, with a negative control proving it could have caught
  the bug) corroborate that the edges are *correctly extracted from source*. They do **not**
  adjudicate the *modeling* choices (shell-transit-as-edge, ddname-identity) — both checks share my
  COBOL mental model. The hub ablation in §9 is a first probe at one of those choices, but the boundary
  it draws is *also* mine; that residual rests on one head until a COBOL-literate reader weighs in.
- **Thin gating stratum.** `data_access` has only n=10 after physical-file keying.
- **Make-vs-buy.** Commercial and academic COBOL dependency analyzers already exist. This benchmark
  does **not** claim CML resolves COBOL reachability *better* than they do. Its distinct claim is
  narrow: where a reachability graph exists, delivering it through **one MCP call** (1 call/question
  vs `grep`'s multi-step BFS) gives a coding agent a token-efficient, complete answer to a question
  `grep` cannot close. The claim is **MCP-native delivery**, not COBOL parsing.

## 11. So: did the win cross the barrier?

After four experiments, here's the fair answer. The win **crossed — and narrowed.** What transferred
was never "type resolution" (COBOL has no types) but the deeper thing underneath it: *an index earns
its keep exactly where it can close a graph `grep` is structurally blind to.* That reframing is the
durable takeaway, and it travels past this corpus: it turns "should I build a semantic index?" into a
sharper question — *what fraction of my codebase is a graph text can't traverse?* In Java, the answer
is "most of it" (the type graph is everywhere), so the index blankets the codebase. In COBOL, the
answer is "a slice" — **dynamic call and transaction reachability**, the CICS `XCTL`-through-`OCCURS`
dispatch where `grep` sees 2 edges and the truth has 22. There, the oracle is decisive, for the *same
structural reason* it won in Java. Elsewhere — which file, which copybook — the relations are honest
literal lookups where a careful `grep` ties. Same tool, same principle, different *coverage*, because
the languages hide different fractions of themselves from text.

So the pre-registered verdict is **AMBIGUOUS**, and I'm reporting it that way rather than
cherry-picking the two strata that would've read as a rout.

If someone asked me whether to build full COBOL support into the index on this evidence, I'd say
**not yet** — and I'd scope the claim if they pursued it. Pitch any COBOL capability as exactly what
the data shows: **a dynamic call/transaction-reachability oracle, delivered over MCP** — the one
place it decisively beats `grep` — and *not* a replacement for `grep` on static file and copybook
relations, where `grep` keeps up. Two honest next paths:

1. **Validate before committing.** Re-run on a larger, dispatch-heavier corpus — more dynamic `CALL`,
   deeper `XCTL` chains, less menu-hub degeneracy — where transitive reachability is a *discriminating*
   relation. The hub ablation (§9) was the cheap version of this test, run on the graph I already had,
   and it gave a green-ish light to bother: the win *survived* the ablation on the dispatch-owning
   nodes — it just stopped being broad. That's the signal that a non-degenerate corpus is worth
   standing up; if the concentrated win holds there without a navigation shell inflating it, the case
   strengthens a lot.
2. **Scope the claim** as above if it's pursued at all.

Either way, the study did the job it was designed to do: take a win proven in one language, carry it
to a language I'd never written, and find out — cheaply, pre-registered, with an independent audit
that caught my own bugs and a second-compiler cross-check that confirmed the fixes — *exactly* how
much of it survived the trip. Not "the index beats grep at
COBOL." The truer, smaller headline: **the shape of the win crosses the language barrier, but only
where COBOL hides its graph from text — and on this corpus, that's a real edge that doesn't yet earn
a build.**

---

### Acknowledgments

The sharpest improvements in this piece came from adversarial review, and in the spirit of the
mind-independence argument I should say exactly where. Two reviews — both, in the interest of full
disclosure, **other Claude Opus 4.8 instances**, not the human COBOL expert the work still owes
(`audit/REVIEWER-BRIEF.md` is the open ask) — independently converged on the same soft spot in
decision #1 and pushed the result somewhere better:

- A critical read disaggregated the 32-frozen / 23-post-fix audit questions, retired the "0 across
  55" overclaim, and argued that **signoff-as-edge drives the win's *breadth* while the OCCURS
  dispatch drives its *substance*** — which is the distinction §9's hub ablation set out to measure.
- A web-grounded source review caught that the `screen → COSGN00C` edge I'd called a "signoff" is
  mostly the **`EIBCALEN = 0` cold-start / lost-session guard** (`COBIL00C.cbl:107`,
  `COUSR01C.cbl:78`) — navigation plumbing, not a business dependency — and flagged that the corpus's
  user-type menu gate is vacuous in this snapshot (all `COMEN02Y` entries are `'U'`), so the
  conditional-edge question doesn't bite here.

Neither closes the load-bearing gap — they share my model lineage, so their agreement is not the
independent validation a human practitioner would give. But they made the claim narrower, truer, and
better-measured, and that's worth crediting plainly.

---

### Appendix: reproducibility

- Repo: [github.com/csharp36/cml](https://github.com/csharp36/cml). Harness: `bench/cobolA/`.
- Corpus: [AWS CardDemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo) pinned
  at `59cc6c2` (`bench/cobolA/clone_corpus.sh`, `pin.env`).
- Oracle: `oracle/extractor` (Java/ProLeap parses 44/44) → `oracle/build_oracle.py` → `oracle/oracle.json`.
- Independent answer key + provenance (incl. the 32→55 freeze-order): `audit/hard_strata_key.json`, `audit/KEY-AUDIT.md`.
- Second-oracle cross-check (different lineage): `oracle2-gnucobol/` — `preprocess.sh` (GnuCOBOL
  `cobc -E`) → `extract_edges.py` → `diff_oracles.py` (44/44 agreement; negative control 21/44);
  method + limits in `oracle2-gnucobol/CROSS-CHECK.md`.
- Hub-ablation sensitivity analysis (§9): `oracle2-gnucobol/ablate_hub.py` (+ `test_ablate_hub.py`),
  written up in `oracle2-gnucobol/ABLATION.md` (Jaccard 0.913→0.242; grep call_closure F1 0.145→0.438).
- Modeling-review brief for a human COBOL expert: `audit/REVIEWER-BRIEF.md`; the (non-independent) AI
  dry run that motivated the ablation: `audit/REVIEW-AI-DRYRUN.md`.
- Questions: `select_questions.py` → `questions.jsonl` (80 across 5 strata).
- Arms + score: `arms/` (fair-grep vs `cobol_reachability` MCP proxy), `score.py`, `run_all.sh`.
- Full result doc with per-stratum detail: `docs/superpowers/results/2026-06-03-cobol-decomposition-findings.md`.
- Spec: `docs/superpowers/specs/2026-06-03-cobol-decomposition-feasibility-design.md`.

*Earlier in the series:*
[Article 1 — implement a feature](https://medium.com/@csharp36/does-a-semantic-code-index-make-claude-code-a-better-engineer-a-controlled-experiment-c90b193204ee) ·
[Article 2 — locate a change surface](https://medium.com/@csharp36/discovery-benchmark-semantic-index-vs-grep-find-76ee87ce12c1) ·
[Article 3 — type-resolved reachability](https://medium.com/@csharp36/where-a-semantic-code-index-finally-beats-grep-type-resolved-reachability-fd8a077da688)
