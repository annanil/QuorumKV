# Load Tester Dev Log

## 2026-04-03 — Code review + plot_results.py

### What was done
- Full code review of load tester module (LoadTester, KvClient, StatsController, LoadTestConfig, LatencyRecord)
- Identified issues (see below)
- Created `plot_results.py` for graph generation from CSV output

### Issues found in review
1. **Failed requests write latency=0 to CSV** — console stats filter these out (`latency > 0`), but CSV rows remain. Be aware when graphing; `plot_results.py` filters them.
2. **Stale detection race window** — small false-positive window between `compute()` and `get()` in concurrent threads. Acceptable noise for the assignment; documented in README.
3. **404 reads counted as success** — inflates success count early in test when keys haven't been written yet. Not a bug per se (server responded correctly), but worth noting in analysis.
4. **Latency includes server-side artificial delays** — 50ms reads, 200ms writes baked into KvService. Graphs will reflect this; it's by design for the assignment.
5. **`plot_results.py` was missing** — referenced in README but never created. Now added.

### Dependencies on teammates
- David (leader-follower quorum): needed for `lf-w5r1`, `lf-w1r5`, `lf-w3r3` experiment configs
- Emily (leaderless): needed for `leaderless` config
- Neither blocks local testing against Alan's single-node scaffold

### Next steps
- [ ] Local end-to-end dry run (single node, verify CSV + graphs)
- [ ] Prepare run script for full 16-run experiment matrix on EC2
- [ ] Run experiments once David/Emily merge their work
- [ ] Final graph analysis for report
