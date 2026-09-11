# QuorumKV — Hands-On Manual

The goal is not to read about the quorum logic — it is to make the cluster do the things
`analysis.md` and `STUDY_NOTES.md` describe, with your own hands, so the "why" sticks. Companion
to `STUDY_NOTES.md` (the design decisions) and `CHEAT_SHEET.md` (the Q&A bank) — this manual is
the "watch it actually happen" counterpart to both. All paths assume you start from the repo root
(`~/projects/cs6650/QuorumKV`).

---

## Part 0 — Prerequisites

```bash
java -version    # need 17
docker --version # Docker Desktop or equivalent, with Compose v2
```

Three independent Maven modules — you'll build each as needed, not all up front.

---

## Part 1 — Build and Run the Leader-Follower Cluster

```bash
./mvnw clean package -DskipTests
docker compose up --build -d
```

This starts `leader` (host port 8080) and `follower1`–`follower4` (host ports 8081–8084), default
`WRITE_QUORUM=1`, `READ_QUORUM=1`.

Basic write and read (no quorum logic — `PUT /kv` replicates to every follower best-effort):

```bash
curl -X PUT http://localhost:8080/kv -H "Content-Type: application/json" \
  -d '{"key":"foo","value":"bar"}'
# {"key":"foo","version":1}

curl "http://localhost:8080/kv?key=foo"
# {"key":"foo","value":"bar","version":1}
```

Write again and confirm the version increments:

```bash
curl -X PUT http://localhost:8080/kv -H "Content-Type: application/json" \
  -d '{"key":"foo","value":"baz"}'
# version should now be 2
```

Confirm replication reached a follower directly (bypassing quorum, straight to that node's store):

```bash
curl "http://localhost:8081/kv/local_read?key=foo"
```

---

## Part 2 — Quorum Writes: Reproduce a 503

Stop the cluster and restart with a write quorum that can't tolerate a dead follower:

```bash
docker compose down
WRITE_QUORUM=5 READ_QUORUM=1 docker compose up --build -d
```

W=5 means every one of the 5 nodes must ACK. Confirm a normal write succeeds:

```bash
curl -X PUT http://localhost:8080/leader/kv -H "Content-Type: application/json" \
  -d '{"key":"foo","value":"bar"}'
# 201, {"key":"foo","version":1}
```

Now kill a follower and try again:

```bash
docker compose stop follower4
curl -i -X PUT http://localhost:8080/leader/kv -H "Content-Type: application/json" \
  -d '{"key":"foo","value":"bar2"}'
# expect HTTP/1.1 503
```

**Trace why in the code** (`KvService.leaderWrite`): with `follower4` down, only 3 followers can
be reached; `acksNeeded = writeQuorum - 1 = 4`; the shortfall check
(`acksReceived + remaining < acksNeeded`) trips before all followers are even tried, because 3
remaining followers can never reach 4 acks. This is the exact code path — trace it line by line
before moving on.

Bring `follower4` back and confirm writes succeed again:

```bash
docker compose start follower4
curl -i -X PUT http://localhost:8080/leader/kv -H "Content-Type: application/json" \
  -d '{"key":"foo","value":"bar3"}'
# expect HTTP/1.1 201
```

Try the basic path (`PUT /kv`, not `/leader/kv`) with `follower4` stopped again — confirm it
still returns 201 even though a follower is down. This is the difference between Path 1 (best-
effort, ignores quorum config) and Path 2 (true quorum) — see `STUDY_NOTES.md` §3. If you can't
explain why these two paths behave differently before reading that section, stop and read it now.

```bash
docker compose down
```

---

## Part 3 — Leaderless Cluster: Reproduce the Stale-Read Finding

This is the most important exercise in this manual — it's the basis of your strongest resume
bullet.

```bash
WRITE_QUORUM=5 READ_QUORUM=1 docker compose -f docker-compose.leaderless.yml up --build -d
```

This starts 5 leaderless peers behind an nginx load balancer on host port 8080 (see
`nginx-leaderless.conf` — round-robins across `node1`–`node5`, none of which expose individual
host ports). Any single write/read pair will look identical to the leader-follower case — the
divergence in `analysis.md` only appears under **concurrent writes to the same key landing on
different coordinator nodes**, which needs real concurrency to trigger. Reproduce it with the
load tester instead of manual curl:

```bash
cd load-tester
mvn clean package -DskipTests -q
java -jar target/load-tester-1.0-SNAPSHOT.jar \
  --write-url=http://localhost:8080 --mode=leaderless \
  --requests=2000 --threads=16 --num-keys=10 --write-pct=90
cd ..
```

Check `load-tester/output/latencies_*.csv` — look at the `stale` column. With a small key pool
(10 keys) and high write concurrency (16 threads, 90% writes), you should see a nonzero stale
count, even though `W=5, R=1` satisfies `W + R > N`.

**Before reading further, answer out loud:** why does this happen here but not in the
leader-follower cluster with the same W/R values? If you can't answer without notes, reread
`analysis.md` §3b now — don't move on until you can.

```bash
docker compose -f docker-compose.leaderless.yml down
```

---

## Part 4 — Sharded Cluster: Trigger a Live Migration

Conceptual background (what sharding is for, the shard formula, routing, and the design gaps this
implementation has) is in `STUDY_NOTES.md` §5 — read that first if you're new to this part. This
section is just the repro steps.

```bash
(cd shard-controller && ./mvnw clean package -DskipTests -q || mvn clean package -DskipTests -q)
docker compose -f docker-compose.sharded.yml up --build -d
```

This starts two 3-node replica groups (`g0-leader:8080`/`g0-f1:8081`/`g0-f2:8082` holding shards
0,1; `g1-leader:8083`/`g1-f1:8084`/`g1-f2:8085` holding shards 2,3) plus `shard-controller:8090`.
Default quorum within each group is `W=3, R=3` (not 1, unlike the other two clusters).

Check the current shard assignment, then write a key to each group directly:

```bash
curl http://localhost:8090/config

curl -X PUT http://localhost:8080/leader/kv -H "Content-Type: application/json" \
  -d '{"key":"alpha","value":"1"}'
curl -X PUT http://localhost:8083/leader/kv -H "Content-Type: application/json" \
  -d '{"key":"beta","value":"1"}'
```

**Demo 1 — shard ownership isn't enforced** (see `STUDY_NOTES.md` §5 "Shard Ownership Is a
Convention, Not an Enforced Rule"):

```bash
curl -X PUT http://localhost:8080/leader/kv -H "Content-Type: application/json" \
  -d '{"key":"zzz-wrong-group-key","value":"oops"}'
# 201 — accepted even though this key's shardId belongs to g1, not g0
```

**Demo 2 — force a real migration.** The default 2-shards-each split makes `/config/rebalance` a
no-op (`2 <= 2 + 1` in the skip check — see `STUDY_NOTES.md` §5 "Rebalance Logic"). This used to
also be broken for a *different* reason even with a skewed split — a group owning zero shards could
never be picked as a recipient at all — but that bug is now fixed and verified live; see
`STUDY_NOTES.md` §5 "Rebalance Logic" for what was wrong and how it was fixed. Tear down and edit
the `shard-controller` service block in `docker-compose.sharded.yml` to skew the split:

```yaml
SHARD_GROUP0_INITIAL_SHARD_IDS: "0,1,2,3"
SHARD_GROUP1_INITIAL_SHARD_IDS: ""
```

`shard-controller`'s `Dockerfile` builds from a plain `app.jar` in that directory, not from
`target/`, so a source change (like the bug fix above) won't reach the container unless you copy
the freshly-built jar over it first:

```bash
docker compose -f docker-compose.sharded.yml down
cd shard-controller && mvn clean package -DskipTests -q && cp target/*.jar app.jar && cd ..
docker compose -f docker-compose.sharded.yml up --build -d
curl http://localhost:8090/config   # confirm group0 owns all 4 shards, group1 owns none

# write a key to g0-leader BEFORE rebalancing, so there's something to migrate.
# "shard3-key" hashes to shardId 3 (Java String.hashCode() & 0x7FFFFFFF % 4) — the shard this
# split always picks to migrate (rebalance() moves the donor's highest shard id, see below).
curl -X PUT http://localhost:8080/leader/kv -H "Content-Type: application/json" \
  -d '{"key":"shard3-key","value":"1"}'

curl -X PUT http://localhost:8090/config/rebalance
docker compose -f docker-compose.sharded.yml logs shard-controller --tail=30
```

Trace `ShardConfigService.rebalance()` + `migrateData()` against what you see in the logs: donor/
recipient selection, the deterministic highest-shard-id pick, the dump/import HTTP calls (each
retried up to 3×), and only-commit-on-success semantics.

```bash
shard-controller-1  | 2026-09-07T18:12:31.410Z  INFO 1 --- [shard-controller] [nio-8090-exec-5] e.n.c.s.service.ShardConfigService       : Migrating shard 3 from group 0 (http://g0-leader:8080) to group 1 (http://g1-leader:8080)
shard-controller-1  | 2026-09-07T18:12:31.516Z  INFO 1 --- [shard-controller] [nio-8090-exec-5] e.n.c.s.service.ShardConfigService       : Migrating 1 entries for shard 3
shard-controller-1  | 2026-09-07T18:12:31.554Z  INFO 1 --- [shard-controller] [nio-8090-exec-5] e.n.c.s.service.ShardConfigService       : Rebalance complete — new configVersion=1
n_ii_l_annaaa@Nis-MacBook-Pro QuorumKV % curl http://localhost:8090/config          
{"configVersion":1,"numShards":4,"groupAssignments":{"0":0,"1":0,"2":0,"3":1}
```

(If you skip the write above, you'll instead see "Shard 3 has no entries to migrate" — still a
valid, if less interesting, run: the config version still bumps, there's just nothing to observe
in Demos 3–4.)

**Demo 3 — migration is a copy, not a move** (see `STUDY_NOTES.md` §5 "Two Things Migration Does
Not Do", #1):

```bash
curl "http://localhost:8081/kv/local_read?key=shard3-key"
# still returns the value — the donor's copy was never deleted
```

**Demo 4 — the recipient's followers don't get the migrated data** (same section, #2):

```bash
curl -i "http://localhost:8083/leader/kv?key=shard3-key"
# likely HTTP/1.1 503 — R=3 needs follower ACKs, but a 404 from a follower that's never seen this
# key is treated as a failed ACK, not "found nothing"

curl "http://localhost:8084/kv/local_read?key=shard3-key"
# 404 — confirms the follower has no copy yet

curl "http://localhost:8083/kv/local_read?key=shard3-key"
# 200 — the leader has it, bypassing quorum entirely
```

```bash
n_ii_l_annaaa@Nis-MacBook-Pro QuorumKV % curl -i  "http://localhost:8083/leader/kv?key=shard3-key" 
HTTP/1.1 503 
Content-Length: 0
Date: Mon, 07 Sep 2026 18:17:48 GMT
Connection: close

n_ii_l_annaaa@Nis-MacBook-Pro QuorumKV % curl -i  "http://localhost:8084/kv/local_read?key=shard3-key"
HTTP/1.1 404 
Content-Length: 0
Date: Mon, 07 Sep 2026 18:18:22 GMT

n_ii_l_annaaa@Nis-MacBook-Pro QuorumKV % curl -i  "http://localhost:8083/kv/local_read?key=shard3-key"
HTTP/1.1 200 
Content-Type: application/json
Transfer-Encoding: chunked
Date: Mon, 07 Sep 2026 18:18:58 GMT

{"key":"shard3-key","value":"1","version":1}% 
```

Revert your `docker-compose.sharded.yml` edits, then:

```bash
docker compose -f docker-compose.sharded.yml down
```

---

## Part 5 — Load Tester and the Three-Phase Migration Test

With the sharded cluster still up (repeat the `docker compose -f docker-compose.sharded.yml up`
from Part 4 if you tore it down):

**Prerequisite 1 — run the load tester as a container on `quorumkv_default`, not on the host.**
`GET /config` returns container-internal hostnames (`http://g0-leader:8080`) that only resolve
inside the Docker network the cluster runs on — not from your host machine. Running
`java -jar load-tester.jar --shard-controller=http://localhost:8090` directly on the host reaches
the ShardController fine (its port is mapped), but every subsequent request to a group leader then
fails silently (swallowed in the client's retry loop, no logged error) — verified live: this
produces a clean-looking run with 0 successful writes/reads in every phase and no visible exception
at all. See `STUDY_NOTES.md` §5 "The Load Tester Must Run Inside the Docker Network" for why. Run
it like this instead:

```bash
cd load-tester
mvn clean package -DskipTests -q
cd ..
docker run --rm --network quorumkv_default \
  -v "$(pwd)/load-tester/target/load-tester-1.0-SNAPSHOT.jar:/app.jar" \
  -v "$(pwd)/load-tester/output:/output" \
  -w / eclipse-temurin:17-jre \
  java -jar /app.jar \
  --shard-controller=http://shard-controller:8090 \
  --requests=5000 --threads=16 --write-pct=50
```

Open the resulting CSV (`load-tester/output/latencies_default.csv`) and find the P50/P99 write and
read latency.

**Prerequisite 2 for the migration test — the same imbalance requirement from Part 4 Demo 2
applies here.** `MigrationTestRunner` calls `/config/rebalance` exactly once, at the start of its
migration phase. A balanced cluster makes that call a no-op, so the skewed
`SHARD_GROUP0_INITIAL_SHARD_IDS`/`SHARD_GROUP1_INITIAL_SHARD_IDS` split from Part 4 must be in
place *before* starting this test, or `_migration` will look identical to `_baseline` for the wrong
reason (no migration happened, not "migration is free"). Also note: `--migration-test=true` used to
be dead code — `LoadTestConfig` set the flag but `LoadTester.main()` never read it, so
`MigrationTestRunner` was fully implemented but never invoked. This is now fixed and verified live —
if any of your recorded results predate this fix, their `_migration` phase measured no real
migration; re-run before citing numbers for the "isolating latency impact of live shard rebalancing"
resume bullet.

```bash
docker run --rm --network quorumkv_default \
  -v "$(pwd)/load-tester/target/load-tester-1.0-SNAPSHOT.jar:/app.jar" \
  -v "$(pwd)/load-tester/output:/output" \
  -w / eclipse-temurin:17-jre \
  java -jar /app.jar \
  --shard-controller=http://shard-controller:8090 \
  --migration-test=true \
  --requests=5000 --threads=16 --write-pct=50
```

This produces three CSVs: `output/latencies_<config>_baseline.csv`,
`..._migration.csv`, `..._postmig.csv` (30s / 60s / 30s phases — see `MigrationTestRunner.java`).
**Verified result on this test setup (1 shard, ~10-key pool):** all three phases completed with 0
failures and 0 stale reads, but write P99 did *not* move (≈613–643ms in every phase) — the
dump/import for one small shard is too brief relative to the 60-second migration window to show up
in aggregate P99. Don't take "no visible latency bump" as the test being broken; it's an honest
result given how little data actually migrates here. To actually see the phase separate from
baseline, you'd need either a much larger per-shard dataset (so the dump/import takes long enough
to matter) or a per-request timestamp analysis isolating just the requests that landed during the
dump/import window, rather than a 60-second aggregate.

```bash
docker compose -f docker-compose.sharded.yml down
```

---

## Part 6 — Run the Test Suite

```bash
WRITE_QUORUM=5 READ_QUORUM=1 docker compose up --build -d
./mvnw test -Dtest=W5R1LeaderFollowerTest
docker compose down

WRITE_QUORUM=1 READ_QUORUM=1 docker compose up --build -d
./mvnw test -Dtest=W1R1LeaderFollowerTest
docker compose down

./mvnw test -Dtest=LeaderlessInconsistencyTest   # no cluster needed, uses mocks
```

Read each test file before running it — know what property it's actually asserting, not just
that it passes.

---

## Part 7 — Read the Code While It's Fresh

With a cluster still running from the exercises above, open these in order and, after each one,
close it and describe the class from memory before moving to the next:

- **`KvService.java`** — all six write/read methods. Can you name the difference between
  `leaderWrite` and `leaderlessWrite` without looking?
- **`NodeProperties.java`** — every env var, what it binds to, and its default.
- **`ShardConfigService.java`** — `rebalance()` end to end: donor/recipient selection, the
  migration call, the retry logic, the rollback-on-failure behavior.
- **`StatsController.java`** (load-tester) — how staleness is actually detected, and its known
  blind spot (single-process only — see `PLAN.md`).

---

## Part 8 — Explain It Out Loud

With nothing running and no notes open, narrate out loud:

1. What happens, step by step, when a client `PUT`s to `/leader/kv` with `W=3` on a 5-node
   cluster — from the controller through `KvService` to the HTTP calls to followers and back.
2. Why the leaderless topology can produce stale reads under the exact same NWR parameters that
   give the leader-follower topology zero stale reads.
3. What a shard rebalance actually does, end to end, including what happens to in-flight requests
   during the propagation window before clients notice the new config version.

The gap between what you can say unprompted and what's written in `CHEAT_SHEET.md` is exactly
what still needs work.

---

## Cleanup — Run This After Every Practice Session

All three compose files (`docker-compose.yml`, `docker-compose.leaderless.yml`,
`docker-compose.sharded.yml`) live in this directory and, since none of them set a custom network
name, they all default to the same project network (`quorumkv_default`). If you tear down only one
while another's containers are still up, `docker compose down` will stop its own containers but
fail to remove the shared network ("Resource is still in use") — and any container still holding a
port (e.g. `g1-leader` on 8083) will collide with the next stack you try to start. Tear down **all
three** every time, even if you think you only ran one:

```bash
docker compose down --remove-orphans
docker compose -f docker-compose.leaderless.yml down --remove-orphans
docker compose -f docker-compose.sharded.yml down --remove-orphans
```

Confirm nothing is left before starting your next session:

```bash
docker ps -a --format '{{.Names}}' | grep quorumkv && echo "WARNING: containers still present" \
  || echo "clean — safe to start fresh"
```
