# QuorumKV — Plan

Started as a CS6650 (NEU, Distributed Systems) coursework assignment; extended independently
afterward with sharding, live migration, a load-testing harness, CI, and an AWS deployment. Full
ownership — see "Origin and ownership" below for exactly what that means and how to state it.

---

## Why this project

Most student projects describe a database. This one builds the replication mechanism itself and
then breaks it on purpose to find where the theory and the implementation diverge. That's the
differentiator: `analysis.md` documents a real finding — the leaderless topology produces up to
10.5% stale reads under concurrent writes even when `W + R > N` is satisfied, because quorum
overlap guarantees a read *contacts* a node with the latest write, not that all replicas
*converge* without a serialization point. That's a genuine distributed-systems insight, not a
tutorial result.

For a backend/distributed-systems-flavored SWE interview, this project demonstrates:
- Quorum-based replication (NWR) implemented from scratch in two topologies
- Empirical characterization of a consistency/availability tradeoff, including a correctness bug
  found through systematic experimentation, not code review
- End-to-end ownership: AWS deployment via Terraform, CI running full-cluster smoke tests

(The project also includes a sharding layer with live data migration — real code, not currently
a resume claim. See `SHARDING.md` if it comes up or for future development.)

---

## Origin and ownership

The original README listed a 4-person team (course requirement — CS6650 assignments are
submitted as teams). All 26 commits in this repo are under a single git author. This project is
claimed as full personal ownership: the design and implementation, including everything beyond
the graded baseline (ShardController, live migration, load tester, CI, Terraform/AWS deployment),
is yours to describe as "designed and implemented," not "contributed to" or "co-designed."

If asked directly whether this was a team assignment: yes, it originated as one for a course, and
you can say so plainly — the extended, resume-facing version is independent work built on top of
that starting point. There's no need to volunteer the team framing unprompted, but don't deny it
if asked; the honest answer is more credible than a story with a gap in it.

---

## Settled scope

- **Core quorum mechanics (write/read paths, NWR theory, the leaderless stale-read finding)**:
  deeply understood, defensible cold. This is the load-bearing material — see `STUDY_NOTES.md`
  §2–4 and `analysis.md` in full.
- **Load tester, CI, Terraform**: understood at the "why this exists and what it proves" level,
  code-deep on request. See `STUDY_NOTES.md` §6–7.
- **AI-assisted development**: substantial parts of the post-coursework code (sharding, migration
  retry logic, CI pipeline, several bug fixes) were built with Claude Code's help. This is not
  something to hide — if asked, say so. What matters is that every design decision below is one
  you can explain and defend, regardless of who typed it.

---

## Resume bullets (locked)

```
• Designed and implemented a distributed in-memory key-value store supporting leader-follower
  and leaderless (Dynamo-style) replication with tunable NWR quorum consistency; validated
  correctness with an automated JUnit consistency test suite across a 5-node cluster.

• Empirically quantified a consistency gap in NWR quorum reads: W+R>N guarantees quorum overlap
  but not read freshness under concurrency. Isolated two independent root causes via controlled
  AWS load tests: read-quorum size (0% stale reads at R=1 vs. 99.9% at R=5) and the absence of
  a write-serialization point in leaderless mode (up to 31.7% stale).

• Built a multithreaded load-testing harness with per-key stale-read detection and latency
  reporting; deployed via Terraform (EC2/ALB/VPC) with a GitHub Actions CI pipeline running
  full-cluster smoke tests.
```

Update these only if the underlying code changes.

**What these bullets claim and don't claim:**
- The "0% vs. 99.9%" and "31.7%" figures come from the project's AWS load test
  (`analysis.md` §2–§5 and `STUDY_NOTES.md` §4c) run specifically to isolate whether staleness
  scales with read-quorum size. Bullet wording deliberately omits thread/request-count specifics
  (64 threads, 50,000 requests) — those are methodology detail, not the finding; know them if
  asked (`analysis.md` §1), but they don't belong in the bullet itself.
- The R=1/R=5 contrast is mechanistic, not just observed: `R=1` reads skip the follower-polling
  loop entirely (`KvService.leaderRead()`, `readsNeeded = readQuorum - 1 = 0`), so they're
  structurally immune regardless of concurrency — confirmed by exactly 0.00% stale reads at every
  write ratio, not just "low."
- "Validated correctness with an automated JUnit consistency test suite" refers to the 4 JUnit
  test classes (`W5R1LeaderFollowerTest`, `W1R1LeaderFollowerTest`, `LeaderlessInconsistencyTest`,
  `ConsistencyStressTest`) — real, running tests, not aspirational. **These run against a local
  Docker Compose cluster, not AWS** (`STUDY_NOTES.md` §2b: "a live, already-running Docker
  cluster"; §7: "correctness is tested locally"). Bullet 1's "5-node cluster" is that local Docker
  cluster; AWS only enters via bullet 2's load-test data — don't attribute AWS to the JUnit suite.
- No claim of production traffic or real users — this is a benchmarked research/learning system,
  framed as such.

---

## What's explicitly not claimed / known gaps

Name these first if the conversation gets near them — it reads better than being caught out.

- **Leaderless write divergence is a known, unresolved limitation, not a bug that was fixed.**
  There's no vector-clock or last-write-wins conflict resolution — the finding in `analysis.md` is
  a diagnosis, not something the system corrects for. If asked "how would you fix it," the honest
  answer involves either adding a serialization mechanism (defeats the point of leaderless) or a
  conflict-resolution scheme (vector clocks, LWW) — neither is implemented.
- **The R=5 stale-read finding (leader-follower) is also a diagnosis, not a fix.** No read-repair,
  no client-side retry-until-fresh, no bounded-staleness read option was added — the system still
  returns the value it captured at the start of the read, however old that's become by the time it
  returns. A real fix would mean either speeding up quorum reads (parallel instead of sequential
  follower polling) or accepting bounded staleness explicitly (e.g., returning a version/timestamp
  so the caller can detect and retry) — neither is implemented here.
- **Stale-read detection has a known blind spot.** `StatsController` only tracks versions written
  by the load tester's own process; a restart or a second writer would make measured staleness a
  lower bound, not an exact figure.
- **Phase 3 (event log / Kafka simulation) was never built.** `STUDY_NOTES.md` §9 documents it as
  a plan only. Do not use the "Phase 3 bullet" in `CHEAT_SHEET.md` — it's explicitly marked
  aspirational and must not appear on the resume unless the code exists.
- **No frontend or visualization** — don't say "visualized" anything.
- **CI runs at W=1,R=1** (weak consistency) purely to avoid cold-Docker-network quorum timeouts;
  it is a smoke test, not a correctness test. Correctness is verified locally via the JUnit suite.

---

## Explicitly out of scope

- Conflict resolution (vector clocks, last-write-wins) for leaderless divergence
- Automatic/triggered rebalancing (current rebalance is manually invoked via `PUT /config/rebalance`)
- Durability — everything is in-memory; a node restart loses its data
- The Phase 3 event-log extension (documented as a plan, not implemented)
- Authentication/authorization on any endpoint
