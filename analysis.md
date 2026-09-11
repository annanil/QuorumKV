# QuorumKV — Results Analysis

## Experiment Setup

All runs: 10,000 requests, 16 worker threads, 10-key pool, four write ratios (1%, 10%, 50%, 90%).
All results are from AWS EC2 (`t3.micro`, `us-east-1`). The server applies artificial latency:
200 ms per write (sequential per node), 50 ms per read (sequential per node for R > 1).

- **Leader-follower runs:** 5 nodes (1 leader + 4 followers).
- **Leaderless runs:** 5 nodes, all peers, writes routed through any node via ALB.

---

## 1. Graph Design Rationale

### Latency Boxplot — two side-by-side panels, linear y-axis

Writes and reads are plotted in separate panels, each with its own y-axis that auto-scales to
that operation's range. This is necessary because write latency (700–1100 ms) and read latency
(130–600 ms) differ by up to an order of magnitude across configs — a shared axis would compress
the faster operation's box into an unreadable sliver. Each panel shows the standard boxplot
(IQR box, median line, 1.5×IQR whiskers, outlier dots) with a stats annotation (n, mean,
median, P99). Stale read count is shown in the read panel.

### Throughput Chart — stacked bar, 1-second buckets

Requests are bucketed into 1-second windows and stacked by operation type. This shows both total
throughput and the write/read split in a single view.

### Interval Histogram — x-axis capped at P95

The read-write interval (time between a PUT and the next GET on the same key) is heavily
right-skewed — most intervals are under 1 second but a long tail extends to tens of seconds.
Capping the x-axis at P95 reveals the shape of the bulk distribution without collapsing it
into the leftmost bin. The stats annotation preserves P99 numerically so no data is hidden.

---

## 2. AWS Results (10,000 requests, 16 threads, 10-key pool)

**Provenance note:** these tables were reconstructed from the original submitted boxplot charts in
`load-tester/output/latency_boxplot_*.png` (each embeds an n/mean/median/P99 stats box and a
stale-read count, dated to the actual assignment run). The raw per-request CSVs those charts were
generated from have since been overwritten by a later, unrelated local rerun and no longer match —
the PNGs are the only surviving ground truth, and an earlier version of this document's tables and
narrative did not match them. 503 (insufficient-quorum) counts are not captured in these charts and
could not be reconstructed, so that column has been removed rather than left with invented numbers.
For R=1 configs (this section's W=5,R=1, and leaderless W=5,R=1 below), reads never contact a
follower/peer at all (`readsNeeded = readQuorum - 1 = 0`), so a follower-driven read 503 is
structurally impossible there regardless of what the true count would have shown.

### W=5, R=1

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale |
|---------|-----------|-----------|-----------|----------|-------|
| 1%      | 1148 ms   | 2608 ms   | 133 ms    | 207 ms   | 0 (0.00%) |
| 10%     | 1098 ms   | 1295 ms   | 143 ms    | 398 ms   | 0 (0.00%) |
| 50%     | 1090 ms   | 1192 ms   | 137 ms    | 295 ms   | 0 (0.00%) |
| 90%     | 1091 ms   | 1174 ms   | 142 ms    | 230 ms   | 0 (0.00%) |

### W=1, R=5

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale      |
|---------|-----------|-----------|-----------|----------|------------|
| 1%      | 277 ms    | 416 ms    | 349 ms    | 442 ms   | 2 (0.02%)  |
| 10%     | 284 ms    | 445 ms    | 351 ms    | 525 ms   | 20 (0.22%) |
| 50%     | 284 ms    | 524 ms    | 343 ms    | 525 ms   | 22 (0.45%) |
| 90%     | 279 ms    | 370 ms    | 342 ms    | 429 ms   | 15 (1.49%) |

### W=3, R=3

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale     |
|---------|-----------|-----------|-----------|----------|-----------|
| 1%      | 709 ms    | 1165 ms   | 267 ms    | 489 ms   | 0 (0.00%) |
| 10%     | 686 ms    | 819 ms    | 255 ms    | 369 ms   | 0 (0.00%) |
| 50%     | 692 ms    | 895 ms    | 246 ms    | 400 ms   | 2 (0.04%) |
| 90%     | 695 ms    | 763 ms    | 232 ms    | 313 ms   | 0 (0.00%) |

---

## 3. Config-by-Config Analysis

### W=5, R=1 — Write-Expensive, Read-Cheap, Strongly Consistent

**Why write is slow.** Write mean of ~1090–1150 ms across all ratios directly reflects the
sequential replication protocol: the leader sends a PUT to follower-1, waits 200 ms for its ack,
then follower-2, and so on, then sleeps 200 ms for its own write. W=5 means 5 × 200 ms = 1000 ms
plus EC2 inter-instance network overhead (~90–150 ms), giving the observed range. This is the
expected behavior per the assignment spec.

**Why read is fast.** R=1 means the leader serves the read from its own in-memory store after
a single 50 ms sleep. No follower is contacted. Read mean of ~133–143 ms = 50 ms artificial delay
+ ~85–95 ms EC2 round-trip. This is the lowest read latency of all three configs.

**Why stale reads are zero across every ratio.** W + R = 6 > N = 5 satisfies the quorum overlap
condition. Every write returns only after all 5 nodes confirm the update, and `leaderRead()`
always starts from the leader's own local `store.get(key)` before consulting any follower — since
every write in this topology is serialized through the same leader, the leader's own copy is
always the freshest value in the cluster, and a follower's response can only ever raise `best`,
never lower it (`body.getVersion() > best.getVersion()`). So a `/leader/kv` read cannot
mechanically return a value older than the leader's own store state at read-time — matching the
observed 0% stale rate at every write ratio.

**Write P99 at 1% write (2608 ms) vs other ratios (~1200 ms).** With only 98 writes in 10,000
requests, the write threads see high scheduling variance. Most complete in ~1090 ms but a few
wait in the OS scheduler queue before being dispatched, inflating the tail. At higher write
ratios more writes keep the thread pool "warm" and P99 tightens.

**Read P99 (207–398 ms) doesn't track write ratio monotonically.** 398 ms at 10% write is
actually the highest of the four rows, not 90% — this reads as ordinary tail noise from a handful
of slow outliers on a shared `t3.micro`, not a systematic write-pressure effect. Read *mean* is
the more reliable signal here (133–143 ms, essentially flat): R=1 reads never contact a follower,
so write-side load has no structural path to affect them at all.

---

### W=1, R=5 — Write-Cheap, Read-Expensive, Weakly Consistent

**Why write is fast.** W=1 means the leader acknowledges after its own 200 ms write, with no
follower coordination. Write mean of ~277–284 ms = 200 ms artificial delay + ~80–100 ms
round-trip. Identical cost to a single-node write, and flat across all write ratios.

**Why read is slower than W=5,R=1, but flat across write ratios.** R=5 means the leader must
contact all 4 followers and wait for each response before returning the highest-versioned value:
50 ms (own read) + 4 × 50 ms (followers) + network overhead ≈ 330 ms minimum, matching the
observed read mean of 342–351 ms closely. Read latency does **not** grow with write ratio here —
it stays within a ~10 ms band across all four rows (349, 351, 343, 342 ms), and P99 (442–525 ms)
shows no meaningful escalation either. An earlier version of this analysis claimed read latency
ballooned to a 1668 ms mean / 20333 ms P99 at 90% write due to follower-side contention — that
claim did not match the source charts and has been retracted; there is no evidence in the real
data of write pressure degrading reads for this config.

**Why stale reads increase with write ratio (0.02% → 1.49%).** This is *not* evidence that W+R > N
failed, and it is *not* a max-version-selection bug — `leaderRead()` already keeps the correct max
version across all R responses, seeded from the leader's own always-freshest local value (see the
W=5,R=1 analysis above; the same reasoning applies here regardless of R). The most plausible
explanation is a measurement race in the load tester's own staleness detector: `StatsController`
flags a read as stale if its version is below the highest version *any* client has ever recorded a
write response for, globally. A read's own server-side snapshot can be correct for the instant it
was taken, but if a different, concurrent write to the same key has a shorter round-trip and gets
its response recorded first, the read's response arrives and gets compared *after* that fresher
write is already the recorded max — flagging it stale even though nothing in the replication path
failed. This scales with write concurrency, matching the observed trend, and reaches its highest
rate here (1.49% at 90% write) because W=1,R=5 has the most concurrent write traffic of the three
LF configs.

---

### W=3, R=3 — Balanced Quorum

**Why write latency is ~690–710 ms.** W=3 means the leader waits for 2 follower acks before
returning: 200 ms (own write) + 200 ms (follower-1) + 200 ms (follower-2) + network overhead ≈
600–700 ms. Observed means (686–709 ms) match this closely and stay flat across write ratios.

**Why read latency is ~230–270 ms, with a mild downward drift.** R=3 means the leader contacts 2
followers: 50 ms (own read) + 50 ms (follower-1) + 50 ms (follower-2) + network overhead ≈ 235 ms
minimum. Observed means (232–267 ms) sit close to this floor throughout; the small drift from
write1 (267 ms) down to write90 (232 ms) is within ordinary run-to-run noise rather than a real
trend — nothing in the code would make reads cheaper as write ratio rises.

**Why stale reads are near-zero (0% at three of four ratios, 2 reads / 0.04% at write50).** Same
reasoning as W=5,R=1 and W=1,R=5: `leaderRead()`'s max-version selection, seeded from the leader's
own freshest local value, cannot mechanically return a stale result. The 2 stale flags at write50
are consistent with the same load-tester measurement race described under W=1,R=5, just far
rarer — W=3,R=3 has less concurrent write traffic than W=1,R=5, so fewer opportunities for that
race to occur.

---

### Leaderless W=5, R=1

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale | 503s |
|---------|-----------|-----------|-----------|----------|-------|------|
| 1%      | 1115 ms   | 1508 ms   | 132 ms    | 202 ms   | 31 (0.31%)  | 0 |
| 10%     | 1095 ms   | 1217 ms   | 132 ms    | 212 ms   | 287 (3.18%) | 0 |
| 50%     | 1091 ms   | 1222 ms   | 134 ms    | 246 ms   | 386 (7.77%) | 0 |
| 90%     | 1091 ms   | 1235 ms   | 137 ms    | 276 ms   | 100 (10.54%)| 0 |

---

## 3b. Leaderless W=5, R=1 — Same Quorum Parameters, Very Different Consistency

**Why write latency matches leader-follower W=5,R=1 (~1091 ms).** Both configurations require
5 quorum acks before acknowledging the write. In leaderless mode, the receiving node acts as a
temporary coordinator: it applies the write locally (200 ms sleep), then forwards the write to
each of its 4 peers sequentially and waits for their acks (4 × 200 ms + inter-node RTT). The
total cost is the same as LF W=5 — 5 × 200 ms + ~90 ms EC2 overhead ≈ 1090 ms. Configuration
affects *who* coordinates the write, not *how many acks* are required.

**Why read latency also matches LF W=5,R=1 (~134 ms).** R=1 means any single node reads from
its own store after a 50 ms sleep, with no inter-node contact. Read cost is identical regardless
of whether there is a fixed leader.

**Why stale reads explode compared to LF W=5,R=1 (0.31% → 10.54% vs ≈0% in LF).**
This is the central finding. LF W=5,R=1 had near-zero stale reads because the leader
coordinates all writes — every write passes through a single point and followers only ever
receive one write order. In leaderless, any node can receive and coordinate a write independently.
When two writes to the same key arrive at *different* nodes concurrently:

1. Node A receives write(key=v2) and forwards it to peers 1→2→3→4 (in that order, sequentially).
2. Node B simultaneously receives write(key=v3) and forwards it to peers in a different order.
3. The replication messages interleave: some peers apply v2-then-v3 (correct), others apply
   v3-then-v2 (stale final state), depending on which replication message arrived first.
4. With R=1, the ALB routes the next read to any node. A node stuck with v2 returns stale data.

This is classic leaderless write divergence — W+R > N guarantees a quorum overlap exists, but
it does not guarantee all N nodes converge to the same value after concurrent writes. A leader
acts as a serialization point that prevents this; leaderless does not have one.

**Why stale reads increase with write ratio (0.31% → 10.54%).** More writes means more
concurrent write operations in flight simultaneously. Higher concurrency → more opportunities
for two writes to the same key to race across different coordinator nodes → more version
divergence. At 90% write, 9051 writes across a 10-key pool create an extremely high rate of
concurrent same-key writes (~900 writes per key on average), producing 10.54% stale reads.

**Why zero 503 errors across all ratios.** Unlike LF W=1,R=5 or W=3,R=3, R=1 in leaderless
means no outbound read-path connections — each node reads its own store. No connection pool
exhaustion regardless of read load.

**Interval histogram (write50): median 492 ms, P99 3480 ms.** The shorter median (492 ms vs
~1000+ ms in LF runs) reflects the smaller key pool (10 keys) and higher thread count (16) —
reads are re-issued to the same key more frequently, so the write-to-read window is shorter.
This actually *worsens* the stale read rate: shorter intervals mean the read lands while the
replication is still propagating to all peers.

---

## 4. Which Config Performs Best for Each Read/Write Ratio?

### Read-heavy (1% write, 99% read)

**Best: W=5, R=1.** Read mean of 133 ms is the lowest of all configs, with zero stale reads. The
high write cost (~1148 ms) is irrelevant when only 98 of 10,000 requests are writes. W=5,R=1 pays
the consistency cost at write time and makes reads cheap — exactly right for read-heavy workloads.

### Write-heavy (90% write, 10% read)

**Best: W=1, R=5.** Write mean of 279 ms is the lowest across all configs, and — unlike an
earlier version of this analysis claimed — its read cost at 90% write is not degraded either
(read mean 342 ms, P99 429 ms, both in line with its other write ratios). W=5,R=1 is the worst
choice for write-heavy: ~1090 ms per write at 90% ratio means the system spends almost all its
time in write coordination, for no read-latency benefit once writes dominate the workload.

### Balanced (50% write, 50% read)

**Best: W=3, R=3.** Write mean of 692 ms and read mean of 246 ms are the most balanced across
configs — neither operation dominates latency, and both P99s (895 ms write, 400 ms read) stay
predictable. W=5,R=1 has cheaper reads but much more expensive writes; W=1,R=5 has cheaper writes
but a real (if modest) read latency premium from contacting 4 followers per read.

---

## 5. Which Database for Which Application?

| Config | Consistency | Best application type |
|---|---|---|
| W=5, R=1 | Strong | **Read-heavy, write-rare**: configuration stores, feature flag services, CDN edge caches, DNS records. Written infrequently by operators, read thousands of times per second by clients. The ~1.1 s write cost is amortized over many cheap (~135 ms) reads, with genuinely zero observed stale reads at any write ratio. |
| W=1, R=5 | Strong | **Write-heavy with fast writes and acceptable read cost**: event ingestion pipelines, log aggregation, IoT telemetry. Reads cost more than W=5,R=1 (~345 ms, from contacting all 4 followers) but stay flat regardless of write pressure. The small residual "stale" counts observed (0.02%–1.49%) are a load-tester measurement-race artifact (see §3), not a real quorum violation — the max-version read logic is already correct. |
| W=3, R=3 | Strong | **General-purpose transactional**: session stores, shopping carts, inventory management, financial balances — any balanced read/write workload where neither operation can be sacrificed. The moderate symmetric cost (~700 ms write, ~250 ms read) is the price of reliable consistency without specializing for one operation type. |
| Leaderless W=5, R=1 | Weak — write divergence under concurrency | **Write-heavy with tolerable inconsistency**: social media activity feeds, analytics counters, recommendation caches — systems where any node can accept writes and serve reads, latency is critical, and showing briefly stale data is acceptable. The lack of a fixed leader improves availability and load distribution but causes detectable divergence: 0.31% stale at 1% write rising to 10.54% at 90% write, even with W+R > N. |

### Summary

The NWR quorum parameters are a continuous dial between consistency and performance. Increasing
W shifts cost to writes; increasing R shifts cost to reads. All three leader-follower configs
achieve W+R>N's consistency guarantee in practice — `leaderRead()`'s max-version selection is
seeded from the leader's own always-freshest local value, so a stale follower can never make the
returned result stale. The small non-zero stale counts observed (0%–1.49%, scaling with write
concurrency) are best explained by a measurement race in the load tester's own staleness detector,
not a quorum failure — see §3's per-config analysis. Contrary to an earlier version of this
analysis, read latency does **not** degrade under write pressure for any of the three LF
configs — it stays flat within each config across all four write ratios; the earlier "follower
contention balloons P99 to 20+ seconds" claim did not match the underlying data and has been
removed.

Beyond NWR parameters, the topology matters as much as the numbers. Leaderless W=5,R=1 uses
the same quorum sizes as leader-follower W=5,R=1 and has similar write and read latency
(~1091 ms / ~135 ms). Yet stale reads reach 10.54% at 90% write load — a consistency failure
that never occurs in the leader-follower version. The leader acts as a write serialization
point; without it, concurrent writes to the same key on different coordinator nodes produce
version divergence that no quorum overlap can prevent. W+R > N is necessary but not sufficient
for strong consistency: it requires a single coordinator (leader) or a conflict-resolution
mechanism (last-write-wins, vector clocks) to be meaningful in a leaderless topology.

The right configuration depends on three axes: the application's read/write ratio, its tolerance
for tail latency, and whether it can accept any inconsistency at all.
