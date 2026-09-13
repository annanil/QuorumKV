# QuorumKV — Results Analysis

## Experiment Setup

All runs: 50,000 requests, 64 worker threads, 10-key pool, four write ratios (1%, 10%, 50%, 90%).
All results are from AWS EC2 (`t3.micro`, `us-east-1`), 5-node clusters. The server applies
artificial latency: 200 ms per write (sequential per node), 50 ms per read (sequential per node
for R > 1). Raw data: `load-tester/output/latencies_v2-*.csv` and
`load-tester/output/intervals_v2-*.csv` (16 files, one per config × write-ratio); full console
stats per run: `load-tester/output/sweep_log_v2-*.txt`; charts:
`load-tester/output/latency_boxplot_v2-*.png`, `throughput_v2-*.png`, `interval_hist_v2-*.png`.

- **Leader-follower runs:** 5 nodes (1 leader + 4 followers). Configs: `W=5,R=1`, `W=1,R=5`, `W=3,R=3`.
- **Leaderless run:** 5 nodes, all peers, writes routed through any node via ALB. Config: `W=5,R=1`.

---

## 1. Graph Design Rationale

### Latency Boxplot — two side-by-side panels, linear y-axis

Writes and reads are plotted in separate panels, each with its own y-axis that auto-scales to
that operation's range. This is necessary because write latency and read latency differ enough
across configs that a shared axis would compress the faster operation's box into an unreadable
sliver. Each panel shows the standard boxplot (IQR box, median line, 1.5×IQR whiskers, outlier
dots) with a stats annotation (n, mean, median, P99). Stale read count is shown in the read panel.

### Throughput Chart — stacked bar, 1-second buckets

Requests are bucketed into 1-second windows and stacked by operation type. This shows both total
throughput and the write/read split in a single view.

### Interval Histogram — x-axis capped at P95

The read-write interval (time between a PUT and the next GET on the same key) is heavily
right-skewed — most intervals are short but a long tail extends much further. Capping the x-axis
at P95 reveals the shape of the bulk distribution without collapsing it into the leftmost bin.
The stats annotation preserves P99 numerically so no data is hidden.

---

## 2. AWS Results (50,000 requests, 64 threads, 10-key pool)

Source: `load-tester/output/sweep_log_v2-lf-w5r1.txt`, `sweep_log_v2-lf-w1r5.txt`,
`sweep_log_v2-lf-w3r3.txt`, `sweep_log_v2-ll-w5r1.txt`.

### W=5, R=1 (leader-follower)

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale | Failed reads (503) |
|---------|-----------|-----------|-----------|----------|-------|------|
| 1%      | 1117 ms   | 2270 ms   | 134 ms    | 256 ms   | 0 (0.00%) | 0 |
| 10%     | 1088 ms   | 1187 ms   | 130 ms    | 206 ms   | 0 (0.00%) | 0 |
| 50%     | 1086 ms   | 1186 ms   | 154 ms    | 286 ms   | 0 (0.00%) | 0 |
| 90%     | 1092 ms   | 1212 ms   | 147 ms    | 290 ms   | 0 (0.00%) | 0 |

### W=1, R=5 (leader-follower)

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale | Failed reads (503) |
|---------|-----------|-----------|-----------|----------|-------|------|
| 1%      | 282 ms    | 373 ms    | 528 ms    | 622 ms   | 2283 (**4.73%**)  | 1242 |
| 10%     | 281 ms    | 371 ms    | 531 ms    | 618 ms   | 17640 (**39.22%**) | 0 |
| 50%     | 278 ms    | 361 ms    | 531 ms    | 621 ms   | 23706 (**95.17%**) | 0 |
| 90%     | 280 ms    | 378 ms    | 550 ms    | 737 ms   | 5111 (**99.90%**) | 0 |

### W=3, R=3 (leader-follower)

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale | Failed reads (503) |
|---------|-----------|-----------|-----------|----------|-------|------|
| 1%      | 694 ms    | 755 ms    | 349 ms    | 419 ms   | 0 (0.00%) | 1414 |
| 10%     | 690 ms    | 754 ms    | 346 ms    | 403 ms   | 2 (0.00%) | 0 |
| 50%     | 689 ms    | 758 ms    | 339 ms    | 408 ms   | 2 (0.01%) | 0 |
| 90%     | 695 ms    | 752 ms    | 339 ms    | 405 ms   | 3 (0.06%) | 0 |

### Leaderless, W=5, R=1

| Write % | Write mean | Write P99 | Read mean | Read P99 | Stale | Failed reads (503) |
|---------|-----------|-----------|-----------|----------|-------|------|
| 1%      | 1097 ms   | 1263 ms   | 131 ms    | 156 ms   | 722 (**1.46%**)  | 0 |
| 10%     | 1091 ms   | 1216 ms   | 130 ms    | 209 ms   | 6652 (**14.80%**) | 0 |
| 50%     | 1088 ms   | 1190 ms   | 131 ms    | 210 ms   | 7497 (**30.03%**) | 0 |
| 90%     | 1096 ms   | 1245 ms   | 151 ms    | 337 ms   | 1611 (**31.69%**) | 0 |

**503s only appear at 1% write, and only where R>1** (`W=1,R=5`: 1242; `W=3,R=3`: 1414; `W=5,R=1`
and leaderless: 0 at every ratio). Per-request timestamps in `latencies_v2-lf-w1r5-write1.csv` /
`latencies_v2-lf-w3r3-write1.csv` show every one of these 503s lands in the first 30–37 seconds of
runs that last 412s/524s, decaying monotonically second-by-second (192→169→151→120→70→... for
`W=1,R=5`) and never recurring for the rest of the run — a cold-start connection-pool effect, not
sustained load.

`write1` is always the *first* config `run_sweep.sh` executes against a just-started cluster, so
it's the only time the leader's `PoolingHttpClientConnectionManager` (`AppConfig.java:18-20`:
`maxTotal=200`, `maxPerRoute=50`) has zero pre-established connections to followers. When 64
threads simultaneously start issuing quorum reads (`R>1`, so each needs a follower connection),
demand briefly outpaces how fast the pool can open new connections. This is not a follower-side
timeout or a network round-trip failure — it's the leader's own HTTP client failing *before the
request is even sent*: the calling thread waits past the 2s `connectionRequestTimeout`
(`AppConfig.java:25`) for a pooled connection to become free, then throws `RestClientException`
locally. `leaderRead()`'s catch block (`KvService.java:240-244`) counts this as one fewer follower
read toward the `readQuorum - 1` needed, and if too few followers are reachable in time, throws
`IllegalStateException("503")` → HTTP 503. Once the pool fills with reusable connections (under a
minute), it never recurs — zero failures in `write10/50/90`, which reuse the now-warm pool from
the same still-running server process. `W=5,R=1`'s `readsNeeded = readQuorum - 1 = 0`
(`KvService.java:224`) means a follower-driven read 503 is structurally impossible there
regardless of load — it never opens a follower connection at all.

---

## 3. Config-by-Config Analysis

### W=5, R=1 — Write-Expensive, Read-Cheap, Structurally Immune to Staleness

**Why write is slow (~1086–1117 ms).** The leader sends a PUT to each of 4 followers
sequentially, waiting 200 ms for each ack, then sleeps 200 ms for its own write: 5 × 200 ms =
1000 ms, plus EC2 inter-instance network overhead (~90–120 ms). This is flat across all four
write ratios — the cost is per-write, not dependent on how many other requests are in flight.

**Why read is fast (~130–154 ms) and stale reads are exactly 0.00% at every ratio.**
`KvService.leaderRead()` (`KvService.java:218–246`) takes an authoritative snapshot
(`best = store.get(key)`, line 221) and then loops over `readsNeeded = readQuorum - 1` followers
(line 224, 227). At `R=1`, `readsNeeded = 0`, so the loop body never executes — the snapshot is
returned essentially immediately after being taken. There is no window during which a concurrent
write can invalidate it. This is why the stale rate is not just low but **exactly** 0.00% at all
four write ratios, including 90% write with 45,127 concurrent writes in flight — it is mechanically
incapable of staleness by this route, not merely unlikely to hit it.

**Write P99 at 1% write (2270 ms) vs other ratios (~1200 ms) — same cold-start effect as the 503s
above, not generic scheduling noise.** Checked directly against `latencies_v2-lf-w5r1-write1.csv`:
all 6 writes over 2000 ms in this run land at the exact same instant — 2.87 seconds into a run
that lasts over 2 minutes — the moment the first burst of write threads all reach out to followers
before any connections are pooled. Every write after that initial burst is ~1100–1300 ms. This
only shows up in `write1`'s P99 because of how small that run's total write count is: 481 writes
means these 6 land inside the top 1% and pull P99 up to 2270ms. The same handful of slow writes in
a run with 10x–100x more total writes (`write10`/`50`/`90`) wouldn't be large enough a fraction to
reach the 99th percentile at all — P99 is specifically designed to be insensitive to that kind of
small-count outlier, which is why it doesn't show up there.

---

### W=1, R=5 — Write-Cheap, Read-Expensive, and the Read Window Is the Whole Story

**Why write is fast (~278–282 ms) and flat across ratios.** W=1 means the leader acknowledges
after its own 200 ms write only, no follower coordination: 200 ms + ~80 ms round-trip ≈ 280 ms.

**Why read is ~530–550 ms — nearly 4x slower than W=5,R=1.** R=5 means the leader must contact
all 4 followers sequentially and wait for each response: 50 ms (own read) + 4 × 50 ms (followers)
+ network overhead ≈ 450–550 ms, matching the observed range closely.

**Why stale reads go from 4.73% to 99.90% as write ratio rises — the central finding of this
project.** This is *not* a quorum-arithmetic failure (`W+R = 6 > N = 5` is satisfied throughout)
and *not* a max-version-selection bug — `leaderRead()`'s comparison at line 237
(`body.getVersion() > best.getVersion()`) is strictly correct and can only raise `best`, never
lower it. The mechanism is timing, not logic:

The snapshot at line 221 is genuinely correct **for the instant it is taken** — but the method
then spends ~450 ms sequentially polling 4 followers before finally returning that same snapshot.
Meanwhile, `StatsController.recordRead()` (`load-tester/.../StatsController.java:54–71`) compares
the read's version against `lastWrittenVersion`, the highest version *any* write to that key has
completed by the time the comparison runs (updated in `recordWrite()` at lines 35–48, specifically
the `compute()` call at line 43). With 64 threads hammering a 10-key pool and `W=1` writes
completing in ~280 ms, several additional writes to the *same* key routinely complete during the
~530 ms the read spends in flight. By the time the read's stale snapshot is compared, a fresher
write already exists — so it's flagged stale, correctly, by a detector that has no way to know the
read's answer was accurate half a second ago.

This scales directly with write concurrency: at 1% write (507 total writes across 50,000
requests, ~414s run), each key is rewritten roughly every 8 seconds on average — far longer than
the 530 ms read window, so only 4.73% of reads get overtaken. At 90% write (44,884 writes over a
~245s run), a hot 10-key pool sees each key rewritten roughly every **55 ms** on average
(44,884 writes ÷ 10 keys ÷ 245.3s), so a 530 ms read window reliably spans **9–10 fresher writes**
to the same key before it returns, pushing the stale rate to 99.90%. **The read is not wrong when
it's taken. It is simply too slow, relative to how fast a hot key gets rewritten, to still be
"current" by the time anyone checks.**

**Verification that this is R-dependent, not W-dependent:** `W=3,R=3` (below) has its own flat,
write-ratio-independent write cost too — every `W` config does, that's not special to `W=1` — but
its read window is shorter (2 follower round-trips instead of 4), and it lands at ≤0.06% stale
even at 90% write. Same read-path mechanism as `R=1` (0%) and `R=5` (up to 99.9%): the staleness
rate tracks read-window length, which is set by `R`, not by `W`.

---

### W=3, R=3 — Balanced Quorum, Small But Real Read Window

**Why write is ~689–695 ms.** W=3 waits for 2 follower acks: 200 ms (own) + 200 ms + 200 ms +
overhead ≈ 690 ms, flat across ratios.

**Why read is ~339–349 ms.** R=3 contacts 2 followers: 50 ms (own) + 50 ms + 50 ms + overhead ≈
340 ms — about 210 ms shorter than `W=1,R=5`'s read window, and it shows: stale reads stay at
0.00%–0.06% across all four ratios instead of climbing toward 100%.

**Why the tiny nonzero stale counts appear only at 50%/90% write (2 and 3 reads).** Same mechanism
as `W=1,R=5`, just with a much shorter window: with 690 ms writes (not 280 ms) and only a ~340 ms
read window, a same-key write finishing *during* another read's in-flight window is rare but not
impossible at high write concurrency. This is the mechanism operating at low intensity, not a
different phenomenon — three data points (`R=1`: 0.00% always, `R=3`: ≤0.06%, `R=5`: up to 99.90%)
trace out the same curve at increasing `R`.

---

### Leaderless, W=5, R=1 — A Second, Independent Staleness Mechanism

**Why write/read latency match leader-follower `W=5,R=1` closely (~1088–1097 ms / ~130–151 ms).**
Both configurations require 5 acks before a write returns and use `R=1` for reads (no peer
contact). Leaderless just moves the "who coordinates" role onto whichever node the ALB happened
to route the write to — the cost structure is identical.

**Why stale reads still climb from 1.46% to 31.69%, despite `R=1` (which is 0.00% in
leader-follower).** `KvService.leaderlessRead()` (`KvService.java:183–214`) has the exact same
`readsNeeded = 0` short-circuit as `leaderRead()` at `R=1` — so this is *not* the slow-read-window
mechanism from `W=1,R=5`. It is a completely different failure mode: **any node can act as write
coordinator**, so two concurrent writes to the same key arriving at *different* nodes can be
forwarded to peers in different orders. Some peers apply write-A-then-B (correct final state),
others apply write-B-then-A (stale final state), depending purely on message arrival order. With
`R=1`, the ALB can then route a read to whichever peer ended up with the stale version. A leader
provides a single serialization point that prevents this by construction; leaderless mode has none.

**Why this plateaus around ~30% instead of approaching 100% like `W=1,R=5`.** This mechanism
requires two *specific* writes to the *same* key to race through *different* coordinators closely
enough in time to interleave out of order — a probabilistic collision, bounded well below 100%
even at high write concurrency. `W=1,R=5`'s mechanism, by contrast, is a *deterministic*
consequence of read duration exceeding inter-write time on a hot key, so it can approach 100% once
that inequality holds broadly. Both are real, both scale with write concurrency, but they have
different ceilings because they're different mechanisms.

**Two distinct, independently-confirmed staleness mechanisms — not one:**

| Mechanism | Where it applies | Driven by | Observed range | Ceiling |
|---|---|---|---|---|
| Slow read window racing a hot key | Leader-follower, scales with `R` | Read duration vs. write rate per key | 0.00% (`R=1`) → 99.90% (`R=5`) | Approaches 100% |
| Coordinator write divergence | Leaderless, independent of `R` | Write concurrency, no serialization point | 1.46% → 31.69% | Plateaus well below 100% |

---

## 4. Which Config Performs Best for Each Read/Write Ratio?

### Read-heavy (1% write, 99% read)

**Best: `W=5,R=1`.** Read mean of 134 ms is the lowest of all configs, with a mechanically
guaranteed 0.00% stale rate. The high write cost (~1117 ms) is irrelevant when only ~1% of
50,000 requests are writes.

### Write-heavy (90% write, 10% read)

**Best depends on whether staleness is tolerable.** `W=1,R=5` has the cheapest write (280 ms) but
its read is now 99.90% stale — effectively meaningless as a consistency guarantee at this ratio.
`W=3,R=3` is the better real choice here: write cost (695 ms) is higher than `W=1,R=5` but stale
reads stay at 0.06%, an actual consistency guarantee rather than a nominal one. `W=5,R=1` is the
worst choice for write-heavy — ~1092 ms per write with no read-latency benefit once writes
dominate.

### Balanced (50% write, 50% read)

**Best: `W=3,R=3`.** Write mean 689 ms and read mean 339 ms are both moderate and predictable
(P99 758 ms / 408 ms), with stale reads at just 0.01%. `W=1,R=5` is already at 95.17% stale by
this ratio — not a viable "balanced" choice despite its faster write.

---

## 5. Which Database for Which Application?

| Config | Consistency (at realistic concurrency) | Best application type |
|---|---|---|
| W=5, R=1 | Strong — mechanically guaranteed, 0.00% stale at every ratio tested | **Read-heavy, write-rare**: configuration stores, feature flags, CDN edge caches, DNS records. The ~1.1s write cost is amortized over many cheap (~135 ms), provably-fresh reads. |
| W=3, R=3 | Strong in practice — ≤0.06% stale even at 64 threads / 50k requests | **General-purpose transactional**: session stores, shopping carts, inventory, financial balances. The short (~340 ms) read window keeps the slow-read-vs-hot-key race rare without paying `W=5`'s full write cost. |
| W=1, R=5 | **Not a reliable strong-consistency choice under real concurrency** — stale rate reaches 99.90% at 90% write | Only appropriate where reads can tolerate being stale by however long the R=5 quorum read takes (~530 ms) relative to write rate on the same key — e.g., low-write-concurrency logging where the same key is rarely rewritten within that window. Not suitable for hot-key workloads despite satisfying `W+R>N` on paper. |
| Leaderless W=5, R=1 | Weak — write divergence under concurrency, independent of R | **Write-heavy with tolerable inconsistency**: activity feeds, analytics counters, recommendation caches — where any node accepting writes and low latency matter more than exact convergence, and 15–32% staleness at real concurrency is acceptable. |

### Summary

The headline result of this project is that **`W+R>N` guarantees quorum overlap, not read
freshness, and the size of the gap between those two things is precisely proportional to how long
a quorum read takes relative to how often the key it's reading gets rewritten.** `R=1` reads are
structurally immune (0.00%, verified at every ratio) because they never leave a window open;
`R=3` reads have a small window (≤0.06%); `R=5` reads have a large one (up to 99.90%). This isn't
a violation of the theoretical guarantee — `leaderRead()`'s max-version logic is provably correct
at the moment it runs — it's that "correct when read" and "still correct by the time anyone
checks" stop being the same moment once the read takes long enough relative to the write rate on
a hot key.

Leaderless mode adds a second, independent failure mode on top of this: even at `R=1` (which is
immune to the read-window problem), staleness reaches 31.69% because any node can coordinate a
write, and concurrent writes to the same key from different coordinators can apply out of order
across replicas with no serialization point to prevent it. The two mechanisms are driven by
different variables (`R` vs. write concurrency) and have different ceilings (100% vs. well under
100%), and this project isolates and confirms both independently rather than treating "leaderless
is less consistent" as one unexamined fact.

The right configuration depends on three axes: the application's read/write ratio, how long a
key stays hot, and whether any staleness at all is tolerable.

---

## 6. Reproducing This Data

```
./deploy.sh                                    # provision 5-node cluster (leader-follower, W/R set via terraform.tfvars)

cd load-tester
WRITE_URL=http://<leader-ip>:8080 MODE=leader REQUESTS=50000 THREADS=64 \
  ./run_sweep.sh v2-lf-w5r1 2>&1 | tee output/sweep_log_v2-lf-w5r1.txt
# repeat with the cluster reconfigured for W=1,R=5 and W=3,R=3

# redeploy with -var="leaderless=true" -var="enable_leaderless_alb=true", then:
WRITE_URL=http://<alb-dns>:8080 MODE=leaderless REQUESTS=50000 THREADS=64 \
  ./run_sweep.sh v2-ll-w5r1 2>&1 | tee output/sweep_log_v2-ll-w5r1.txt

python3 plot_results.py   # regenerate all charts from output/*.csv
```

Each sweep produces 4 write-ratio runs (1/10/50/90%), writing
`output/latencies_<prefix>-write<pct>.csv`, `output/intervals_<prefix>-write<pct>.csv`, and
printing full console stats per run — `tee`'d to `sweep_log_v2-<config>.txt` to preserve them,
since shell scrollback isn't otherwise retrievable. `MODE` selects the endpoint (`leader` honors
W/R quorum via `/leader/kv`; `leaderless` uses `/leaderless/kv`); W/R values themselves are
cluster-side config, not sweep-script arguments — see `run_sweep.sh` for the full option list.
