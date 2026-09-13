# QuorumKV — Old Analysis (Archived)

This is the first AWS load-test run performed for this project (16 worker threads, 10,000
requests, 10-key pool). It has been superseded by the run documented in `analysis.md` and is kept
here only as a record — not needed to understand or discuss the project.

Source: `load-tester/output/latency_boxplot_lf-*.png`, `latency_boxplot_ll-*.png` (original,
non-`v2`-prefixed files). Raw CSVs from this run were later overwritten by an unrelated local
test and no longer exist; these summary numbers were reconstructed from the surviving PNG charts.

| Config | Write mean (1%→90%) | Read mean (1%→90%) | Stale % (1%→90%) |
|---|---|---|---|
| W=5, R=1 | 1148→1091 ms | 133→142 ms | 0.00% → 0.00% |
| W=1, R=5 | 277→279 ms | 349→342 ms | 0.02% → 1.49% |
| W=3, R=3 | 709→695 ms | 267→232 ms | 0.00% → 0.00% (0.04% at 50%) |
| Leaderless W=5, R=1 | 1115→1091 ms | 132→137 ms | 0.31% → 10.54% |
