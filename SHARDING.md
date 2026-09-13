# QuorumKV — Sharding & Migration (Future Reference)

This is real, working code — not aspirational — but it is **not currently a resume claim or
interview-prep material**. It was pulled out of `PLAN.md`/`STUDY_NOTES.md`/`CHEAT_SHEET.md` because
the project's strongest signal (the stale-read finding in `analysis.md`) doesn't depend on it, and
this area has more known rough edges than the core replication work. Kept here for two reasons:
(1) in case it comes up anyway ("what else is in the project?"), and (2) as a starting point if
this gets picked back up for further development later.

---

## Conceptual Foundation — Sharding Is a Different Problem Than Replication

The core of this project (`STUDY_NOTES.md` §3–§4) is about **replication**: many copies of the
*same* data, for durability and availability. Sharding solves a different problem:
**partitioning** — splitting a keyspace too large (or too hot) for one group of machines into
disjoint subsets ("shards"), each owned by an independent group, so throughput scales by adding
groups instead of adding replicas of everything. This project composes both: each "group" (`g0`,
`g1`) is a self-contained 3-node leader-follower cluster — the same replication machinery from
`STUDY_NOTES.md` §3/§4, just scoped to a fraction of the keyspace. The `ShardController` is a third
kind of component layered on top: it holds no key/value data at all — it's a metadata/routing
authority whose only job is answering "which group currently owns shard X?" The same role is
played by MongoDB's config servers, Vitess's topology service, or Kafka's controller — a small,
separately-scaled service clients consult before talking to the data plane.

## Shard Assignment Formula

**Terminology note**: this used to be called "consistent hashing" in this project's docs and an
earlier resume draft, but it isn't a consistent-hash ring (no virtual nodes, no
minimal-remapping-on-topology-change property). It's a fixed-size hash-bucket scheme —
`numShards` is constant, and shards are explicitly reassigned between a small number of groups —
closer to Redis Cluster's hash-slot model. If this ever becomes a resume bullet again, don't use
"consistent hashing" without being ready to correct it precisely, or soften it to "hash-based
shard assignment with live rebalancing."

```
shardId = (key.hashCode() & Integer.MAX_VALUE) % numShards
```

`Integer.MAX_VALUE` mask (`& 0x7FFFFFFF`) ensures non-negative before modulo. This exact formula is
used in three places:
- `KvService.getShardEntries()` — to determine which keys belong to a shard during dump
- `ShardConfig.getShardId()` (load-tester) — for routing
- `ShardConfig.getLeaderUrlForKey()` (load-tester) — for routing

## Initial Config (from `docker-compose.sharded.yml`)

- Group 0 (g0-leader:8080, g0-f1:8081, g0-f2:8082): shards 0, 1
- Group 1 (g1-leader:8083, g1-f1:8084, g1-f2:8085): shards 2, 3
- ShardController: port 8090

Config stored in `AtomicReference<ShardConfig>`. `configVersion` starts at 0, increments on each
successful rebalance.

## Rebalance Logic (`ShardConfigService.rebalance()`)

1. Computes shard count per group.
2. Finds donor (max shards) and recipient (min shards).
3. Skips if already balanced (diff ≤ 1).
4. Picks the highest shard ID from the donor (deterministic).
5. Calls `migrateData()`:
   - GET `donorLeader + "/kv/shard/" + shardId + "?numShards=" + numShards` to dump entries.
   - POST entries to `recipientLeader + "/kv/shard/" + shardId + "/import"`.
6. Updates `groupAssignments`, increments `configVersion`, atomically replaces config.

**Correctness note:** the donor/recipient candidate map must be seeded from every configured group
(`config.getGroups().keySet()`), not just from groups that already own a shard — otherwise a group
owning zero shards never appears as a candidate, `donorId == recipientId` trivially, and a
maximally-imbalanced cluster (e.g. 4-vs-0) is misreported as "already balanced."

## The ShardController's URLs Are Container-Internal, Not Client-Reachable

`GroupConfig.leaderUrl`/`nodeUrls` in the `/config` response are the Docker-internal hostnames
(`http://g0-leader:8080`) the `shard-controller` container uses to reach KV nodes for migration.
They only resolve on the `quorumkv_default` Docker network — not from the host, even though the
same nodes are also reachable from the host via mapped ports. So the same URL field means two
different things to its two consumers: the controller's own inter-container migration calls, and
an external client's routing. Practical consequence: `ShardAwareKvClient` must run as a container on
that network, not as a host process (see `MANUAL.md` Part 5) — otherwise every request fails
silently (swallowed `UnknownHostException` in the retry loop), reporting 0 successful writes/reads
with no visible error.

## Client Config Refresh

`ShardAwareKvClient` polls `GET /config` every 5 seconds in a daemon thread. When `configVersion`
changes, it prints a log line and updates the `AtomicReference<ShardConfig>`. Requests made during
the brief window between migration completion and config refresh will be routed to the old leader —
they will succeed but may read stale data. This is the same class of problem as DNS TTLs or
service-mesh config propagation delay in real systems.

## Shard Ownership Is a Convention, Not an Enforced Rule

No `KvController` node checks whether a key it's asked to write actually belongs to one of "its"
shards. `dumpShard()`/`getShardEntries()` filter *by the formula* over whatever is locally stored,
but nothing stops a client from writing a shard-2 key straight to `g0-leader` (which is supposed to
hold shards 0,1 only) — it will happily return 201 (see `MANUAL.md` Part 4 for the hands-on repro).
The shard model is entirely enforced by well-behaved clients following the `ShardController`'s
config — never by the storage nodes themselves. That's a real gap between this project and a
production system, which typically rejects or redirects misrouted writes at the storage layer.

## Two Things Migration Does Not Do

**1. It never deletes the donor's copy.** `migrateData()` only dumps and imports — there is no
delete call anywhere in `KvService`/`KvController`. After a migration, the donor still has the
"moved" data on both its leader and followers. This project's "migration" is a **copy**, not a
**move**. A production system either reclaims the donor's copy after a grace period or treats this
as intentional dual-write during cutover — here it's simply never reclaimed.

**2. It never replicates into the recipient group's own followers.** `KvService.
importShardEntries()` does a plain local `store.put()` on whichever node receives the
`/kv/shard/{id}/import` call (the recipient **leader**) — it never calls `replicateAsync`/
`leaderWrite` to push the imported data on to that group's followers. Concretely: right after a
migration, a quorum read (`R=3`) for a migrated key against the new leader can 503 even though the
leader itself holds the correct value — a follower returning 404 for a key it's never seen is
caught by `RestTemplate` as `HttpClientErrorException`, which the quorum-counting loop treats as a
failed read from that follower, not "found nothing" (see `KvService.leaderRead()`, and `MANUAL.md`
Part 4 for the hands-on repro). So immediately after a migration, the recipient group's read-quorum
guarantee is **silently weaker** for migrated keys than `R=3` implies — it recovers only once a
subsequent write to that key re-triggers full replication to the group's followers. This is a
genuine, testable correctness gap worth knowing if this area comes up.

## Bug-Fix History (moved from `CHEAT_SHEET.md`)

**`numShard` vs `numShards` parameter mismatch** (silent data corruption) — `ShardConfigService.
migrateData()` sent `?numShard=N`; `KvController.dumpShard()` binds `@RequestParam(defaultValue="4")
int numShards`. Spring couldn't match, always used the default of 4 — migration silently used the
wrong shard count if `numShards != 4`. Fix: one character, `?numShard=` → `?numShards=`.

**`ShardAwareKvClient` NullPointerException if config not initialized** — `put()`/`get()` called
`config.get().getShardId(key)` with no null check; if `init()` hadn't been called yet, this threw
instead of returning a graceful error. Fix: both methods now begin with a null guard returning a
sentinel result.

**Deployment fixes** (shard-controller specific):
| Fix | What was wrong | What changed |
|---|---|---|
| Port config | Shard-controller defaulted to 8080, conflicting with g0-leader | Added `server.port=8090` to `application.properties` |
| Dockerfile | `docker-compose.sharded.yml` build context had no Dockerfile | Created `shard-controller/Dockerfile` exposing 8090 |
| Root pom dependency | Root pom had compile dep on shard-controller it never used | Removed the dependency |
| CI JAR copy | `mvn install` puts JAR in local Maven repo, not `shard-controller/app.jar` | CI now runs `mvn package` + copies JAR |
| Migration retry/rollback | No retry on HTTP failures; `configVersion` bumped even on failed import | Added 3-attempt retry on dump+import; rollback on exception |

## If This Ever Comes Up: Honest Framing

If asked "is this a production-grade shard migration": no. Real systems (Vitess, MongoDB's chunk
migration) replicate into *all* copies of the new owner before flipping ownership, and explicitly
reclaim or GC the donor's data afterward. This project demonstrates the **routing/indirection
concept** — a versioned config service clients poll, and a donor→recipient data copy gated on
success before the config commits — without solving either of the two hard sub-problems above
(replica catch-up on the recipient side, and reclaiming the donor). That's a precise, defensible
answer — stronger than either overclaiming completeness or calling the whole feature broken.

---

## Quick Reference (from the former cheat sheet)

**Rebalance flow** (`PUT /config/rebalance`):
1. Find donor group (most shards) and recipient group (fewest shards).
2. Skip if diff ≤ 1 (already balanced).
3. Pick highest shard ID from donor (deterministic).
4. Dump entries: `GET donorLeader/kv/shard/{id}?numShards=N`.
5. Import entries: `POST recipientLeader/kv/shard/{id}/import`.
6. Update `groupAssignments`, increment `configVersion`, atomic swap via `AtomicReference`.

**Client discovery**: `ShardAwareKvClient` polls `GET /config` every 5 seconds in a daemon thread.
When `configVersion` changes, it re-routes subsequent requests to the new group leader transparently.

**Replica read routing** (`ShardAwareKvClient`, `replicaReads=true`):
- Constructor: `new ShardAwareKvClient(shardControllerUrl, true)`
- `get()` calls `cfg.getReplicaUrlForKey(key)` instead of `cfg.getLeaderUrlForKey(key)`, then hits
  `GET /kv?key=` (local read) instead of `GET /leader/kv?key=` (quorum read).
- `getReplicaUrlForKey()` picks a random node from `group.getNodeUrls()` using
  `ThreadLocalRandom`; falls back to `leaderUrl` if the list is empty.
- Trade-off: distributes read load across all replica nodes at the cost of potential staleness (no
  version comparison across R nodes — just whatever's local on the picked node).
- `LoadTester` sets `replicaReads=true` when `--read-url` differs from `--shard-controller`.

**Three-phase migration load test** (`MigrationTestRunner`, in the load-tester module):
- Phase 1 — Baseline (30s): steady load, no migration.
- Phase 2 — Migration (60s): rebalance triggered at the start of this phase via
  `PUT /config/rebalance`. Workers continue while migration happens.
- Phase 3 — Post-migration (30s): load continues after migration completes.
- Each phase has its own `StatsController`; workers share an `AtomicInteger phaseId` to select the
  active one. Three CSV files produced: `_baseline.csv`, `_migration.csv`, `_postmig.csv`.

## Former Resume-Bullet Draft (retired, kept for reference)

```
• Extended the system with Dynamo-style sharding: a standalone ShardController assigning shards
  to replica groups via consistent hashing, live shard migration over HTTP between group leaders,
  and versioned config polling for shard-aware clients.
```

Former longer-form talking points (also retired):
- "Extended the cluster with Dynamo-style sharding: a separate ShardController service manages
  shard-to-group assignments using consistent hashing (`hash(key) % N`). Live shard migration pulls
  entries from the donor group leader via HTTP and imports them to the recipient before updating
  the config version that clients poll every 5 seconds."
- "Added replica read routing to `ShardAwareKvClient`: when `replicaReads=true`, reads are directed
  to a random node from the shard's replica list using `ThreadLocalRandom`, hitting the local read
  endpoint to distribute read load across followers at the cost of potential staleness."
- "Designed a three-phase migration load test isolating the latency impact of live shard rebalancing
  — baseline / migration window / post-migration — with separate stats controllers per phase."
- (part of a former "5 bugs fixed" talking point) "...shard migration URL parameter name mismatch,
  and null-safety for uninitialized shard config."

Retired because: the project's strongest bullet (the empirical stale-read finding) doesn't depend
on this at all, and this is the area with the most known rough edges (rebalance donor/recipient
bug already fixed once, "copy not move" semantics, "not really consistent hashing," recipient-side
replication gap after migration). Lower risk/reward than the other three bullets. If picked back up
with more polish (e.g., fixing the recipient-replication gap, adding a real delete-on-donor step),
it could return as a bullet — see "Two Things Migration Does Not Do" above for exactly what would
need to change first.
