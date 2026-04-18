# CS6650 Assignment 4 — Study Guide

> **Purpose**: Map every core requirement to the exact code that implements it,
> explain the tricky parts, and prep you for the walk-through video.
> All file paths and line numbers reflect the current codebase on `ni-dev`.

---

## 1. The Big Picture: CAP Theorem in Practice

The assignment builds a **leader-follower** replicated KV store (N = 5 nodes)
and shows how different W / R quorum configurations trade off consistency vs
availability / latency.

| Config | W | R | W+R vs N | Guarantee |
|---|---|---|---|---|
| W=5, R=1 | 5 | 1 | 6 > 5 | Strong consistency — all 5 nodes updated before ack |
| W=1, R=5 | 1 | 5 | 6 > 5 | Strong on reads — read quorum always overlaps the (single) write node |
| W=3, R=3 | 3 | 3 | 6 > 5 | Balanced — quorum overlap guaranteed with moderate latency cost |
| W=1, R=1 | 1 | 1 | 2 < 5 | Eventual consistency — no quorum overlap; stale reads are possible |

**Key insight**: When `W + R > N`, every read set overlaps every write set, so
at least one node in any read quorum holds the latest version.

---

## 2. Requirement-to-Code Map

### 2.1 KV API

The server exposes two distinct controller paths.

**`KvController.java`** — `/kv` (per-node, no quorum):

| Endpoint | Lines | Behaviour |
|---|---|---|
| `PUT /kv` | 24–37 | Calls `kvService.put()` — 200 ms delay, replicates to all followers in leader mode |
| `GET /kv` | 40–55 | Calls `kvService.get()` — 50 ms delay, reads local store only |
| `GET /kv/local_read` | 58–73 | Same as GET /kv but semantically "test-only / quorum-read helper" |
| `PUT /kv/internal/write` | 76–94 | Calls `kvService.applyReplicatedWrite()` — accepts version from leader; 200 ms delay |

**`LeaderController.java`** — `/leader/kv` (quorum-aware, load tester target):

| Endpoint | Lines | Behaviour |
|---|---|---|
| `PUT /leader/kv` | 26–43 | Returns 403 if not leader; calls `kvService.leaderWrite(W)` |
| `GET /leader/kv` | 46–62 | Returns 403 if not leader; calls `kvService.leaderRead(R)` |

**The load tester always hits `/leader/kv`**, not `/kv`.

### 2.2 Versioning

`KvService.java` handles version assignment in two places:

| Method | Lines | Role |
|---|---|---|
| `put()` / `leaderWrite()` | 42–74 / 98–142 | Version auto-increments — `existing.getVersion() + 1` or starts at 1 |
| `applyReplicatedWrite()` | 87–94 | Accepts the exact version sent by the leader; no increment |

**Important**: version assignment in `leaderWrite()` is NOT synchronized.
Two concurrent PUTs for the same key could race on the `store.get` / `store.put`
pair (lines 102–110). In practice this is acceptable for the assignment because
`ConcurrentHashMap` guarantees visibility but not atomicity across get+put.

### 2.3 Write Path — Leader

`KvService.leaderWrite()` (lines 98–142):

```
Client -- PUT /leader/kv --> LeaderController (line 36)
                               |
                               1. sleepMillis(200)          # leader's own disk simulation
                               2. version = existing + 1    # or 1 if first write
                               3. store.put(key, value)     # local write
                               4. for url in followers[0 .. W-2]:   # sequential
                               |     PUT /kv/internal/write          # line 126
                               |       follower sleepMillis(200)     # applyReplicatedWrite:89
                               |       follower stores value
                               |       follower returns 201
                               |     acksReceived++
                               5. if W == 1:
                               |     replicateAsync(ALL followers)   # line 138
                               6. return 201 to client
```

| W | Sync followers contacted | Async followers | Approx total latency |
|---|---|---|---|
| W=5 | 4 | 0 | 200 + 4×200 = ~1000 ms |
| W=3 | 2 | 2 (async) | 200 + 2×200 = ~600 ms |
| W=1 | 0 | 4 (async) | 200 ms |

**W=1 async replication** (`replicateAsync`, lines 179–189): all 4 followers
are notified via `CompletableFuture.runAsync()` after the 201 is returned.
This is best-effort — if a follower is down, the exception is swallowed (line 184).

### 2.4 Read Path — Leader

`KvService.leaderRead()` (lines 146–175):

1. **Line 148**: `sleepMillis(50)` — leader's own read delay
2. **Line 149**: `best = store.get(key)` — leader's local value
3. **Lines 155–171**: Loop through R−1 followers, `GET /kv/local_read?key=`
4. **Lines 163–165**: Keep the highest-versioned response as `best`
5. **Line 174**: Return `best` (null → 404, caught in LeaderController line 54)

If fewer than R−1 followers are reachable, throws `IllegalStateException("503")`
which `LeaderController` maps to HTTP 503 (line 60).

### 2.5 Internal Replication Endpoint

`KvController.replicate()` (lines 76–94) handles `PUT /kv/internal/write`.
It calls `kvService.applyReplicatedWrite()` (lines 87–94), which:
- Sleeps 200 ms (follower's disk simulation)
- Stores the value with the **exact version** sent by the leader (overwrites any prior value)

There is no separate internal controller class — the `/kv/internal/write` route
lives in the same `KvController` as the client-facing `/kv` endpoints.

### 2.6 Async Propagation and the Inconsistency Window

For W=1, `replicateAsync()` (lines 179–189) runs in a background thread:

```java
// KvService.java:179-189
private void replicateAsync(List<String> followers, ReplicationWriteRequest request) {
    CompletableFuture.runAsync(() -> {
        for (String url : followers) {
            try {
                restTemplate.put(url + "/kv/internal/write", request);
            } catch (RestClientException e) {
                // Best-effort — if a follower is down we skip it.
            }
        }
    });
}
```

The client receives 201 before any follower is updated. A `local_read` on a
follower during this window returns 404 (key not found) or an old version.
Each follower takes ~200 ms to process its internal write, and replication is
sequential, so the inconsistency window for the last follower is ~800 ms.

---

## 3. Configuration System

Configuration lives in **`NodeProperties.java`** (uses Spring
`@ConfigurationProperties(prefix = "node")`):

| Property | Default | Docker env var |
|---|---|---|
| `role` | `"single"` | `NODE_ROLE` |
| `followerUrls` | `""` | `NODE_FOLLOWER_URLS` |
| `writeQuorumSize` | `1` | `NODE_WRITE_QUORUM_SIZE` |
| `readQuorumSize` | `1` | `NODE_READ_QUORUM_SIZE` |

Spring maps `NODE_ROLE` → `node.role` automatically (env var override of
application.properties). The same Docker image runs as leader or follower —
only the env vars differ.

**`docker-compose.yml`** (single file for all configs):

```yaml
# Leader
NODE_ROLE: leader
NODE_FOLLOWER_URLS: "http://follower1:8080,...,http://follower4:8080"
NODE_WRITE_QUORUM_SIZE: ${WRITE_QUORUM:-1}   # override at launch time
NODE_READ_QUORUM_SIZE:  ${READ_QUORUM:-1}

# Followers
NODE_ROLE: follower                           # no follower URLs needed
```

Set quorum at cluster startup:
```bash
WRITE_QUORUM=5 READ_QUORUM=1 docker compose up --build
```

---

## 4. Unit Tests

Tests are **integration tests** — they require the Docker Compose cluster to be
running and hit it via HTTP. Base class `ClusterTestBase.java` handles setup.

### ClusterTestBase (shared setup)

- `LEADER` = env `LEADER_URL` or `http://localhost:8080` (line 20)
- `FOLLOWERS` = env `FOLLOWER_URLS` split by `,`, or `localhost:8081–8084` (lines 21–23)
- `waitForCluster()` (lines 33–52): polls `GET /node/info` up to 60 s before any test runs
- `assertQuorumConfig(W, R)` (lines 63–75): fetches `/node/info` and fails fast if quorum is misconfigured

### W5R1LeaderFollowerTest

Start cluster with: `WRITE_QUORUM=5 READ_QUORUM=1 docker compose up --build`

**Test 1** `test1_leaderReadAfterWrite` (lines 30–47):
```
PUT /leader/kv  →  GET /leader/kv  →  assert value matches
```
W=5 guarantees all nodes are updated before 201, so the leader has the value.

**Test 2** `test2_followerLocalReadAfterW5Write` (lines 53–76):
```
PUT /leader/kv  →  for each follower: GET /kv/local_read  →  assert 200 + correct value
```
W=5 means all 4 followers ACKed before the leader returned, so all are current.

### W1R1LeaderFollowerTest

Start cluster with: `WRITE_QUORUM=1 READ_QUORUM=1 docker compose up --build`

**Test 3** `test3_followersAreStaleImmediatelyAfterW1Write` (lines 31–60):
```
PUT /leader/kv  →  immediately: for each follower: GET /kv/local_read
→  assert at least 1 follower returns 404
```
W=1 means the leader returns 201 after only its own 200 ms write. Async replication
starts in the background, but each follower takes ~200 ms — so immediate
`local_read` hits the inconsistency window and at least one follower returns 404.

---

## 5. Load Tester Design

### 5.1 Temporal Locality

Small key pool (default 50 keys). With 64 threads all picking randomly from 50
keys, the probability that a read hits a recently-written key is high. This
makes stale reads detectable.

Key pool creation: **`LoadTester.java` lines 27–30**
```java
String[] keys = new String[config.getNumKeys()];
for (int i = 0; i < config.getNumKeys(); i++) {
    keys[i] = "key-" + i;
}
```
Key selection per request: **line 62**
```java
String key = keys[random.nextInt(keys.length)];
```

### 5.2 Write vs Read Decision

**Line 65**: `if (roll < config.getWritePercentage())` — random 0–99 vs
`--write-pct`. Writes go to `writeClient` (leader URL), reads go to `readClient`
(same URL in our setup).

### 5.3 Stale Read Detection

**`StatsController.java`** (not StatsCollector):

- `lastWrittenVersion` (`ConcurrentHashMap<String, Integer>`, line 29): highest
  version seen per key across all threads
- On write (line 43): `compute()` keeps the max version — safe against concurrent
  writers racing on the same key
- On read (lines 63–68): if `version < lastWrittenVersion.get(key)` → stale,
  increment `staleReadCount`

### 5.4 Read-Write Interval Tracking

- `lastWriteTimestamp` (`ConcurrentHashMap<String, Long>`, line 31): epoch ms
  of last write per key
- On read (lines 72–76): `interval = now - lastWriteTimestamp.get(key)` → added
  to `readWriteIntervals` queue → written to `intervals_<config>.csv`

### 5.5 CSV Output

| File | Content |
|---|---|
| `output/latencies_<config>.csv` | Per request: start_time, PUT/GET, latency ms, response code, key, version, stale flag |
| `output/intervals_<config>.csv` | One row per read that followed a prior write on the same key |

---

## 6. Tricky Parts and Common Pitfalls

### 6.1 Leader Sleeps Before Sending to Followers

The assignment spec describes the leader sleeping "after" sending to followers,
but the actual code in `leaderWrite()` (line 99) sleeps first, then sends to
followers. Both the leader and each follower sleep 200 ms — the order within the
leader's own execution does not change the total observed latency.

### 6.2 W=1 Still Replicates — Asynchronously

W=1 does NOT mean followers never get the data. `replicateAsync()` fires
immediately after the 201 is returned. The inconsistency window is the time
between the 201 and the moment each follower processes its internal write (~200 ms
per follower, sequentially in the background thread).

### 6.3 Follower Writes Return 403 to Clients

`LeaderController.write()` (line 28) returns 403 if `nodeProperties.getRole()`
is not `"leader"`. Followers accept writes only on `/kv/internal/write`.

### 6.4 503 Errors Are Quorum Failures, Not Connection Pool Exhaustion

`LeaderController.write()` (line 41) and `.read()` (line 60) catch
`IllegalStateException("503")` thrown by `leaderWrite` / `leaderRead` when
fewer than W−1 / R−1 followers are reachable. Under high concurrency (e.g.,
W=1,R=5 with 64 threads each opening 4 follower connections), the server's
outbound HTTP connection pool gets exhausted — causing `RestClientException` on
follower calls, which triggers the 503 path.

### 6.5 Version Assignment Is Not Atomic

`leaderWrite()` uses an unguarded `store.get()` + `store.put()` (lines 102–110).
Two concurrent writes to the same key can both read `version=2` and both write
`version=3`, skipping version 3 for one of them. This is a known gap — acceptable
for the assignment, similar to Cassandra's last-writer-wins without compare-and-swap.

### 6.6 local_read Has the Same 50 ms Delay

`GET /kv/local_read` calls `kvService.get()` (line 60), which has `sleepMillis(50)`
(KvService line 37). It is "local" in the sense that it bypasses quorum — not in
the sense that it skips the artificial delay.

---

## 7. Walk-Through Video Talking Points

Suggested flow:

1. **Config & bootstrap** — `NodeProperties.java` + `docker-compose.yml`: one
   image, role determined by `NODE_ROLE` env var
2. **Write path** — trace a `PUT /leader/kv` through `LeaderController.write()` →
   `KvService.leaderWrite()` → sync follower loop → `applyReplicatedWrite()` on
   each follower
3. **Read path** — trace a `GET /leader/kv` through `LeaderController.read()` →
   `KvService.leaderRead()` → R−1 follower `local_read` calls → max-version selection
4. **Async propagation** — show `replicateAsync()`, explain the inconsistency
   window for W=1 and why Test 3 catches it
5. **Tests** — demo Test 3 timing: PUT returns in ~200 ms, followers need another
   ~200 ms each, so immediate `local_read` hits the window
6. **Load tester** — staleness tracking in `StatsController`, key pool for
   temporal locality, write-pct flag

---

## 8. Load Test Matrix (16 runs)

4 quorum configs × 4 write ratios. Fixed parameters: `--requests=10000 --threads=64 --num-keys=50`

| Config | 1% write | 10% write | 50% write | 90% write |
|---|---|---|---|---|
| W=1, R=1 | run | run | run | run |
| W=5, R=1 | run | run | run | run |
| W=1, R=5 | run | run | run | run |
| W=3, R=3 | run | run | run | run |

See `README.md` → "Full Experiment Matrix" for the exact commands.

### What to Expect

| Config | Write latency | Read latency | Stale reads | 503 errors |
|---|---|---|---|---|
| W=1, R=1 | ~286 ms | ~159 ms | ~0.5% (W+R < N) | 0 |
| W=5, R=1 | ~1123 ms | ~149 ms | 0% (strong) | 0 |
| W=1, R=5 | ~288 ms | ~688 ms | ~0.4% (impl gap) | high (connection pool) |
| W=3, R=3 | ~697 ms | ~248 ms | 0% (strong) | moderate |

Numbers above are AWS EC2 (`t3.micro`) results at 50% write ratio.
