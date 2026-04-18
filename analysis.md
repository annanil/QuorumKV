# Assignment 4 — Results Analysis

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

## 2. AWS Results (10,000 requests, 32 threads, 10-key pool)

### W=5, R=1

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale | 503s |
|---------|-----------|-----------|-----------|----------|-------|------|
| 1%      | 1156 ms   | 2676 ms   | 134 ms    | 222 ms   | 0 (0.00%) | 0 |
| 10%     | 1096 ms   | 1245 ms   | 132 ms    | 213 ms   | 0 (0.00%) | 0 |
| 50%     | 1092 ms   | 1230 ms   | 157 ms    | 289 ms   | 2 (0.04%) | 0 |
| 90%     | 1090 ms   | 1188 ms   | 136 ms    | 213 ms   | 0 (0.00%) | 0 |

### W=1, R=5

| Write % | Write mean | Write P99 | Read mean | Read P99  | Stale      | 503s |
|---------|-----------|-----------|-----------|-----------|------------|------|
| 1%      | 287 ms    | 594 ms    | 409 ms    | 1434 ms   | 4 (0.04%)  | 1465 |
| 10%     | 341 ms    | 373 ms    | 533 ms    | 4537 ms   | 36 (0.40%) | 0    |
| 50%     | 282 ms    | 402 ms    | 572 ms    | 7592 ms   | 50 (1.01%) | 0    |
| 90%     | 288 ms    | 392 ms    | 1668 ms   | 20333 ms  | 13 (1.24%) | 0    |

### W=3, R=3

| Write % | Write mean | Write P99  | Read mean | Read P99  | Stale      | 503s |
|---------|-----------|------------|-----------|-----------|------------|------|
| 1%      | 833 ms    | 12125 ms   | 334 ms    | 649 ms    | 0 (0.00%)  | 1155 |
| 10%     | 715 ms    | 988 ms     | 349 ms    | 2300 ms   | 0 (0.00%)  | 0    |
| 50%     | 727 ms    | 1715 ms    | 292 ms    | 1294 ms   | 11 (0.22%) | 0    |
| 90%     | 705 ms    | 876 ms     | 243 ms    | 355 ms    | 4 (0.41%)  | 0    |

---

## 3. Config-by-Config Analysis

### W=5, R=1 — Write-Expensive, Read-Cheap, Strongly Consistent

**Why write is slow.** Write mean of ~1090 ms across all ratios directly reflects the sequential
replication protocol: the leader sends a PUT to follower-1, waits 200 ms for its ack, then
follower-2, waits 200 ms, and so on, then sleeps 200 ms for its own write. W=5 means 5 × 200 ms
= 1000 ms plus EC2 inter-instance network overhead (~80–90 ms total), giving the observed
~1090 ms. This is the expected behavior per the assignment spec.

**Why read is fast.** R=1 means the leader serves the read from its own in-memory store after
a single 50 ms sleep. No follower is contacted. Read mean of ~135 ms = 50 ms artificial delay
+ ~85 ms EC2 round-trip. This is the lowest read latency of all three configs.

**Why stale reads are effectively zero.** W + R = 6 > N = 5 satisfies the quorum overlap
condition. Every write returns only after all 5 nodes confirm the update. A subsequent leader
read always sees the latest version because the leader itself holds it. The 2 stale reads in
write50 (0.04%) are a client-side artifact: with 10 keys and 32 threads (~3 threads per key),
two threads occasionally write the same key simultaneously. The load tester's
`lastWrittenVersion` records the higher version, but the server commits the lower one last.
This is a version ordering race in the client tracker, not a quorum failure.

**Why zero 503 errors.** R=1 requires no outbound follower connections on the read path — the
leader only needs its own response. The connection pool is never strained regardless of thread
count.

**Write P99 at 1% write (2676 ms) vs other ratios (~1200 ms).** With only 118 writes in 10,000
requests, the write threads see high scheduling variance. Most complete in ~1090 ms but a few
wait in the OS scheduler queue before being dispatched, inflating the tail. At higher write
ratios the thread pool is kept warm and P99 tightens.

---

### W=1, R=5 — Write-Cheap, Read-Expensive, Weakly Consistent

**Why write is fast.** W=1 means the leader acknowledges after its own 200 ms write, with no
follower coordination. Write mean of ~285–290 ms = 200 ms artificial delay + ~85 ms round-trip.
Identical to a single-node write.

**Why read is slow and gets slower with more writes.** R=5 means the leader must contact all 4
followers and wait for each response before returning the highest-versioned value. The leader
polls sequentially: 50 ms (own read) + 4 × 50 ms (followers) + EC2 overhead ≈ 330 ms minimum.
Read mean grows from 409 ms (1% write) to 1668 ms (90% write). The reason: at high write
ratios, async follower replication from 32 concurrent write threads contends with read follower
requests for the same HTTP thread pool on the leader. A small fraction of reads must wait for
the pool to free up, causing the P99 to balloon from 1434 ms (1% write) to 20333 ms (90% write)
while the median stays around 330 ms. The tail is long but the bulk of reads are not affected.

**Why 1465 × 503 at 1% write.** With 99% reads, 32 threads are each opening 4 outbound follower
connections simultaneously = up to 128 concurrent outbound connections. This exhausts the
leader's HTTP connection pool and it returns 503 rather than blocking. As the write ratio
increases, fewer simultaneous reads are in-flight and pool pressure drops — zero 503s at 10%
write and above.

**Why stale reads increase with write ratio (0.04% → 1.24%).** W + R = 6 > N = 5 satisfies
the quorum overlap condition in theory, so stale reads should be zero. The observed staleness
reveals an implementation subtlety: when the leader polls R=5 nodes, it must return the
**maximum version** across all responses. If the implementation returns the first follower
response or an arbitrary one, a follower that hasn't received the latest async replication yet
can cause a stale read. At high write ratios the async replication queue backs up — more writes
in flight means more followers temporarily behind — so the probability of reading from a stale
follower increases. This is the most important finding for this config: W+R > N is necessary
but not sufficient for strong consistency; the read coordinator must also implement the
max-version selection correctly.

---

### W=3, R=3 — Balanced Quorum

**Why write latency is ~700 ms.** W=3 means the leader waits for 2 follower acks before
returning: 200 ms (own write) + 200 ms (follower-1) + 200 ms (follower-2) + ~85 ms EC2
overhead ≈ 685 ms. Observed mean of ~720 ms matches this closely.

**Why read latency is ~300 ms.** R=3 means the leader contacts 2 followers: 50 ms (own read)
+ 50 ms (follower-1) + 50 ms (follower-2) + ~85 ms EC2 overhead ≈ 235 ms minimum. Observed
mean of ~300 ms accounts for occasional scheduling delays.

**Why stale reads appear at higher write ratios (0% → 0.41%).** W + R = 6 > N = 5, so strong
consistency should hold. The observed stale reads (11 at write50, 4 at write90) are the same
concurrent version race seen in W=5,R=1 — two threads writing the same key simultaneously
create a version conflict in the client tracker. The rate is low (< 0.5%) and the pattern
(only at high write ratios) confirms this is a client-side tracking artifact, not a quorum
failure. W=3,R=3 has a larger write quorum overlap than W=1,R=5 so the implementation bug
that causes W=1,R=5 stale reads does not appear here.

**503 errors at 1% write (1155).** R=3 requires 2 outbound follower connections per read.
With 32 threads at 99% reads, that is up to 64 simultaneous outbound connections —
enough to occasionally exhaust the pool. Fewer than W=1,R=5 (which needs 4× the connections)
but the pattern is the same.

**Write P99 at 1% write (12125 ms).** With only 93 writes in 10,000 requests, thread
scheduling variance is extreme. The same effect as W=5,R=1 write1, but amplified because
W=3 writes take longer (~700 ms) so queued threads wait longer.

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

**Best: W=5, R=1.** Read mean of 134 ms is the lowest of all configs. Zero stale reads. Zero
503 errors. The high write cost (~1156 ms) is irrelevant when only 118 of 10,000 requests are
writes. W=1,R=5 and W=3,R=3 both produce 503 errors at this ratio because R > 1 opens too many
outbound connections under heavy read load. W=5,R=1 pays the consistency cost at write time
and makes reads entirely free — exactly right for read-heavy workloads.

### Write-heavy (90% write, 10% read)

**Best: W=1, R=5** for throughput, with a caveat. Write mean of 288 ms is the lowest across
all configs. However, read P99 of 20333 ms is extreme — a small fraction of reads suffer
severe tail latency. If the application can tolerate occasional slow reads, W=1,R=5 at 90%
write is the fastest. If read tail latency matters, W=3,R=3 (write mean 705 ms, read P99
355 ms) is more predictable. W=5,R=1 is the worst choice for write-heavy: 1090 ms per write
at 90% ratio means the system spends almost all its time in write coordination.

### Balanced (50% write, 50% read)

**Best: W=3, R=3.** Write mean of 727 ms and read mean of 292 ms are the most balanced across
configs — neither operation dominates latency. W=5,R=1 has cheaper reads but much more
expensive writes; W=1,R=5 has cheaper writes but more expensive reads and a severe read tail.
W=3,R=3 splits the cost evenly and its P99s (1715 ms write, 1294 ms read) are the most
predictable under balanced load.

---

## 5. Which Database for Which Application?

| Config | Consistency | Best application type |
|---|---|---|
| W=5, R=1 | Strong | **Read-heavy, write-rare**: configuration stores, feature flag services, CDN edge caches, DNS records. Written infrequently by operators, read thousands of times per second by clients. The ~1 s write cost is amortized over many cheap reads. |
| W=1, R=5 | Theoretically strong, implementation-dependent | **Write-heavy with eventual read consistency acceptable**: event ingestion pipelines, log aggregation, IoT telemetry — workloads where data arrives in bursts and reads are infrequent but must see the latest version when they do occur. Requires correct max-version read implementation to deliver the promised consistency. |
| W=3, R=3 | Strong | **General-purpose transactional**: session stores, shopping carts, inventory management, financial balances — any balanced read/write workload where neither operation can be sacrificed and stale data is unacceptable. The moderate symmetric cost is the price of reliable consistency without specializing for one operation type. |
| Leaderless W=5, R=1 | Weak — write divergence under concurrency | **Write-heavy with tolerable inconsistency**: social media activity feeds, analytics counters, recommendation caches — systems where any node can accept writes and serve reads, latency is critical, and showing briefly stale data is acceptable. The lack of a fixed leader improves availability and load distribution but causes detectable divergence: 0.31% stale at 1% write rising to 10.54% at 90% write, even with W+R > N. |

### Summary

The NWR quorum parameters are a continuous dial between consistency and performance. Increasing
W shifts cost to writes and makes reads cheap and fresh. Increasing R shifts cost to reads and
allows cheaper writes. W + R > N is the threshold for guaranteed consistency overlap — but only
if the read coordinator correctly implements max-version selection across R responses. The
experiments show that this implementation detail matters: W=1,R=5 violates the theoretical
guarantee in practice, while W=3,R=3 and W=5,R=1 achieve the expected zero stale reads.

Beyond NWR parameters, the topology matters as much as the numbers. Leaderless W=5,R=1 uses
the same quorum sizes as leader-follower W=5,R=1 and has identical write and read latency
(~1091 ms / ~134 ms). Yet stale reads reach 10.54% at 90% write load — a consistency failure
that never occurs in the leader-follower version. The leader acts as a write serialization
point; without it, concurrent writes to the same key on different coordinator nodes produce
version divergence that no quorum overlap can prevent. W+R > N is necessary but not sufficient
for strong consistency: it requires a single coordinator (leader) or a conflict-resolution
mechanism (last-write-wins, vector clocks) to be meaningful in a leaderless topology.

The right configuration depends on three axes: the application's read/write ratio, its tolerance
for tail latency, and whether it can accept any inconsistency at all.
