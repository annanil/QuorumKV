# Study Guide — What to Read, In What Order

This project has several documents written at different times, plus code that was partly
AI-assisted. This file is the reading path for getting to genuine, defensible understanding —
not for reciting what's written elsewhere.

## What each file is for

| File | Duty | Read it like... |
|---|---|---|
| `PLAN.md` | Why this project is on the resume, the locked bullets, ownership, known gaps | The narrative — read for the story you'd tell in the first 30 seconds, and the traps to avoid |
| `MANUAL.md` | Hands-on: run every cluster topology, reproduce the stale-read finding yourself, trigger a migration | A lab — actually run every command against a real cluster, don't just read them |
| `analysis.md` | The full experiment writeup — every NWR configuration, every number, the root-cause reasoning | Primary source for your strongest bullet — read in full, not summarized |
| `STUDY_NOTES.md` | The "why" behind every design decision, all applied bug fixes, the (unbuilt) Phase 3 plan | The main interview-prep document — read slowly, more than once |
| `CHEAT_SHEET.md` | Quick-reference Q&A bank, resume bullet phrasing, numbers to know | A drill card — use it after the above, to check what you can say without looking |
| `README.md` | Public-facing project description | What a recruiter or interviewer sees before talking to you — make sure nothing in your head contradicts it |

`CHEAT_SHEET.md` and `CHEAT_SHEET.pdf` (and this guide's siblings `STUDY_NOTES.md`,
`STUDY_NOTES.pdf`) are gitignored on purpose — interview-prep material with a literal "resume
bullets" section shouldn't be sitting in the public repo. `PLAN.md` and `MANUAL.md` are fine to
commit; they read as intentional documentation, not coaching notes. Use your judgment before
pushing if that line ever moves.

## Recommended order

**1. `PLAN.md` — 10 minutes.** Get the narrative straight first: why this project, the origin
(coursework → independently extended), what's locked on the resume, and what's explicitly *not*
claimed. The "known gaps" section matters as much as the bullets — knowing where the seams are
before someone else finds them is the whole point of this exercise.

**2. `MANUAL.md` — do it, don't read it (2-3 hours).** Build and run all three cluster topologies,
reproduce the leaderless stale-read divergence yourself with the load tester, trigger a shard
migration. The understanding that comes from watching a 503 happen because you killed a follower
is different from reading that it happens. Do this before `STUDY_NOTES.md` — the "why" sticks
better once you've touched the "what."

**3. `analysis.md` — read start to finish, once, no skimming.** This is the primary source for
your best resume bullet. Every number in `CHEAT_SHEET.md` traces back to this document — if you
only skim it, you'll be reciting a summary of a summary.

**4. `STUDY_NOTES.md` — read twice.**
- First pass: straight through §1–§8 with a cluster still built from the manual.
- Second pass, section by section: before reading each explanation, cover it and try to produce
  it yourself out loud. The gap between what you can say and what's written is what still needs
  work. **Stop at §9** — that's the unbuilt Phase 3 plan, not implemented code; read it only to
  know it's aspirational, not to prepare it as a talking point.

**5. `CHEAT_SHEET.md` — after STUDY_NOTES.** Drill it: cover each answer and try to produce it
before looking. This is the fastest way to find what you can't say yet. Cross-check its "Resume
Talking Points" wording against `PLAN.md`'s locked bullets — `PLAN.md` is the source of truth if
they ever drift.

## If you have extra time, in priority order

1. **Re-run Part 3 of `MANUAL.md` (the leaderless stale-read reproduction) from memory,
   narrating out loud** — this is your highest-value story and the one most likely to get a
   genuine follow-up question.
2. **Audit the "Fixes Applied" list in `STUDY_NOTES.md` §8 against real git history** —
   `git log --oneline`, then `git show <hash>` on 3-4 of the fixes. Confirming the diff yourself
   converts "the notes told me this was a bug" into "I verified this was a bug."
3. **Decide on the "consistent hashing" wording** (see `PLAN.md`, Known Gaps) before it comes up
   in an interview, not during one.
4. **Practice the "how would you fix leaderless divergence" and "what would you add" answers
   unscripted** — see `PLAN.md`'s "What's explicitly not claimed" for the honest starting point.

## Canonical source files (reference these directly when you need to confirm a detail)

```
src/main/java/edu/neu/cs6650/kv/
    service/KvService.java              — all write/read/replication logic
    controller/{Kv,Leader,Leaderless}Controller.java — the three endpoint families
    config/NodeProperties.java          — env var bindings

shard-controller/src/main/java/edu/neu/cs6650/shardcontroller/
    service/ShardConfigService.java     — rebalance() + migrateData()

load-tester/src/main/java/edu/neu/cs6650/loadtester/
    StatsController.java                — stale-read detection, CSV output
    MigrationTestRunner.java            — three-phase migration test
```
