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
- A sharding layer with live data migration between replica groups
- End-to-end ownership: AWS deployment via Terraform, CI running full-cluster smoke tests

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
- **Sharding and migration**: understood at the design level (rebalance algorithm, migration
  retry/rollback, the shard-assignment formula). See `STUDY_NOTES.md` §5.
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
  correctness with automated consistency tests across a 5-node AWS EC2 cluster.

• Empirically quantified a known consistency gap in the leaderless topology — quorum overlap
  (W+R>N) does not prevent stale reads under concurrent same-key writes — measuring 0.31%–10.5%
  stale reads scaling with write concurrency, and isolating the root cause to the absence of a
  write-serialization point.

• Extended the system with Dynamo-style sharding: a standalone ShardController assigning shards
  to replica groups via consistent hashing, live shard migration over HTTP between group leaders,
  and versioned config polling for shard-aware clients.

• Built a multithreaded load-testing harness with per-key stale-read detection and latency
  reporting; deployed via Terraform (EC2/ALB/VPC) with a GitHub Actions CI pipeline running
  full-cluster smoke tests.
```

Update these only if the underlying code changes.

**What these bullets claim and don't claim:**
- "Up to 10.5%" is real — from the leaderless W=5,R=1 AWS run at 90% write ratio, in `analysis.md`.
- "Validated correctness with automated consistency tests" refers to the 4 JUnit test classes
  (`W5R1LeaderFollowerTest`, `W1R1LeaderFollowerTest`, `LeaderlessInconsistencyTest`,
  `ConsistencyStressTest`) — real, running tests, not aspirational.
- "Consistent hashing" is the current wording but is imprecise (see Known Gaps below) — the
  actual mechanism is `hash(key) % numShards` with an explicit, mutable shard→group assignment
  map, closer to Redis Cluster's hash-slot model than a consistent-hash ring. Decide before an
  interview whether to keep this wording (and be ready to correct precisely if pressed) or soften
  it to "hash-based shard assignment with live rebalancing."
- No claim of production traffic or real users — this is a benchmarked research/learning system,
  framed as such.

---

## What's explicitly not claimed / known gaps

Name these first if the conversation gets near them — it reads better than being caught out.

- **Not a consistent-hash ring.** No virtual nodes, no minimal-remapping-on-topology-change
  property. Shard count is fixed (`SHARD_NUM_SHARDS`, default 4); rebalancing moves whole shards
  between a small, fixed number of groups.
- **Leaderless write divergence is a known, unresolved limitation, not a bug that was fixed.**
  There's no vector-clock or last-write-wins conflict resolution — the finding in `analysis.md` is
  a diagnosis, not something the system corrects for. If asked "how would you fix it," the honest
  answer involves either adding a serialization mechanism (defeats the point of leaderless) or a
  conflict-resolution scheme (vector clocks, LWW) — neither is implemented.
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
