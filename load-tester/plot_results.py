#!/usr/bin/env python3
"""
Generate graphs from load tester CSV output.

Usage:
    python3 plot_results.py                     # process all configs in output/
    python3 plot_results.py lf-w5r1-write50     # specific config only

Produces per config in output/:
    latency_boxplot_<config>.png   — read vs write latency distribution
    throughput_<config>.png        — requests/sec over time (1-second buckets)
    interval_hist_<config>.png     — read-write interval distribution
"""

import sys
import os
import glob
import csv
import matplotlib
matplotlib.use("Agg")  # headless — no display needed on EC2
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker

OUTPUT_DIR = "output"


def load_latencies(config):
    """Load latencies CSV, filtering out failed requests (latency <= 0)."""
    path = os.path.join(OUTPUT_DIR, f"latencies_{config}.csv")
    reads, writes = [], []
    all_records = []
    stale_count = 0
    total_reads = 0

    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            latency = int(row["latency"])
            if latency <= 0:
                continue
            req_type = row["request_type"]
            start_time = int(row["start_time"])
            stale = row["stale"].strip().lower() == "true"

            record = {
                "start_time": start_time,
                "request_type": req_type,
                "latency": latency,
                "response_code": int(row["response_code"]),
                "stale": stale,
            }
            all_records.append(record)

            if req_type == "GET":
                reads.append(latency)
                total_reads += 1
                if stale:
                    stale_count += 1
            elif req_type == "PUT":
                writes.append(latency)

    return reads, writes, all_records, stale_count, total_reads


def load_intervals(config):
    """Load read-write intervals CSV."""
    path = os.path.join(OUTPUT_DIR, f"intervals_{config}.csv")
    intervals = []
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            intervals.append(int(row["read_write_interval_ms"]))
    return intervals


def percentile(data, pct):
    s = sorted(data)
    idx = min(int(pct / 100 * len(s)), len(s) - 1)
    return s[idx]


def _draw_box(ax, data, label, color, stale_text=None):
    """Draw a single boxplot panel with stats annotation."""
    bp = ax.boxplot([data], tick_labels=[label], patch_artist=True, showfliers=True,
                    flierprops={"marker": ".", "markersize": 3, "alpha": 0.4,
                                "markerfacecolor": color, "markeredgecolor": color},
                    medianprops={"color": "black", "linewidth": 1.5})
    bp["boxes"][0].set_facecolor(color)
    bp["boxes"][0].set_alpha(0.65)

    s = sorted(data)
    n = len(s)
    med = s[n // 2]
    mn  = sum(data) / n
    p99 = s[min(int(0.99 * n), n - 1)]

    stats = (f"n = {n:,}\n"
             f"mean   {mn:.0f} ms\n"
             f"median {med} ms\n"
             f"P99    {p99} ms")
    ax.text(0.97, 0.97, stats, transform=ax.transAxes, ha="right", va="top",
            fontsize=8, family="monospace",
            bbox=dict(boxstyle="round,pad=0.4", facecolor="white", alpha=0.85))

    if stale_text:
        ax.text(0.97, 0.03, stale_text, transform=ax.transAxes, ha="right", va="bottom",
                fontsize=8, color="red" if "0.0%" not in stale_text else "gray",
                bbox=dict(boxstyle="round,pad=0.3", facecolor="white", alpha=0.85))

    ax.set_ylabel("Latency (ms)")
    ax.yaxis.set_major_formatter(ticker.ScalarFormatter())


def plot_latency_boxplot(config, reads, writes, stale_count, total_reads):
    """Two side-by-side panels: left = write latency, right = read latency.
    Each panel uses its own linear y-axis so both distributions fill the space."""
    if not writes and not reads:
        return

    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    fig.suptitle(f"Latency Distribution — {config}", fontsize=11, y=1.01)

    stale_pct = stale_count / total_reads * 100 if total_reads > 0 else 0
    stale_text = f"Stale reads: {stale_count} ({stale_pct:.1f}%)" if total_reads > 0 else None

    if writes:
        _draw_box(axes[0], writes, f"Write (n={len(writes)})", "#4C72B0")
        axes[0].set_title("Write Latency", fontsize=10)
    else:
        axes[0].set_visible(False)

    if reads:
        _draw_box(axes[1], reads, f"Read (n={len(reads)})", "#55A868", stale_text=stale_text)
        axes[1].set_title("Read Latency", fontsize=10)
    else:
        axes[1].set_visible(False)

    plt.tight_layout()
    out = os.path.join(OUTPUT_DIR, f"latency_boxplot_{config}.png")
    fig.savefig(out, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"  -> {out}")


def plot_throughput(config, all_records):
    """Throughput (requests/sec) over time in 1-second buckets."""
    if not all_records:
        return

    fig, ax = plt.subplots(figsize=(10, 5))

    t0 = min(r["start_time"] for r in all_records)
    # Bucket into 1-second windows
    read_buckets = {}
    write_buckets = {}
    for r in all_records:
        sec = (r["start_time"] - t0) // 1000
        if r["request_type"] == "GET":
            read_buckets[sec] = read_buckets.get(sec, 0) + 1
        else:
            write_buckets[sec] = write_buckets.get(sec, 0) + 1

    max_sec = max(
        max(read_buckets.keys(), default=0),
        max(write_buckets.keys(), default=0),
    )
    seconds = list(range(max_sec + 1))
    read_counts = [read_buckets.get(s, 0) for s in seconds]
    write_counts = [write_buckets.get(s, 0) for s in seconds]

    ax.bar(seconds, write_counts, label="Writes", color="#4C72B0", alpha=0.7, width=0.9)
    ax.bar(seconds, read_counts, bottom=write_counts, label="Reads",
           color="#55A868", alpha=0.7, width=0.9)

    ax.set_xlabel("Time (seconds)")
    ax.set_ylabel("Requests / sec")
    ax.set_title(f"Throughput Over Time — {config}")
    ax.legend()
    ax.xaxis.set_major_locator(ticker.MaxNLocator(integer=True))

    plt.tight_layout()
    out = os.path.join(OUTPUT_DIR, f"throughput_{config}.png")
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"  -> {out}")


def plot_interval_histogram(config, intervals):
    """Histogram of read-write intervals, x-axis capped at P95 to show the bulk of data."""
    if not intervals:
        print(f"  -> (no intervals data for {config})")
        return

    fig, ax = plt.subplots(figsize=(8, 5))

    intervals_sorted = sorted(intervals)
    n = len(intervals_sorted)
    mean_val = sum(intervals_sorted) / n
    median_val = intervals_sorted[n // 2]
    p95_val = intervals_sorted[min(int(0.95 * n), n - 1)]
    p99_val = intervals_sorted[min(int(0.99 * n), n - 1)]

    clipped = sum(1 for v in intervals if v > p95_val)
    plot_data = [v for v in intervals if v <= p95_val]

    ax.hist(plot_data, bins=50, color="#DD8452", alpha=0.8, edgecolor="white", linewidth=0.5)
    ax.set_xlabel("Interval (ms)")
    ax.set_ylabel("Count")

    title = f"Read-Write Interval Distribution — {config}"
    if clipped > 0:
        title += f"\n(x-axis capped at P95={p95_val}ms; {clipped} tail value(s) not shown)"
    ax.set_title(title, fontsize=10)

    stats_text = f"n={n}\nmean={mean_val:.0f}ms\nmedian={median_val}ms\nP99={p99_val}ms"
    ax.text(0.98, 0.98, stats_text, transform=ax.transAxes, ha="right", va="top",
            fontsize=9, family="monospace",
            bbox=dict(boxstyle="round,pad=0.4", facecolor="white", alpha=0.8))

    plt.tight_layout()
    out = os.path.join(OUTPUT_DIR, f"interval_hist_{config}.png")
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"  -> {out}")


def process_config(config):
    """Generate all 3 graphs for one config."""
    latency_file = os.path.join(OUTPUT_DIR, f"latencies_{config}.csv")
    if not os.path.exists(latency_file):
        print(f"Skipping {config}: {latency_file} not found")
        return

    print(f"\nProcessing config: {config}")
    reads, writes, all_records, stale_count, total_reads = load_latencies(config)
    plot_latency_boxplot(config, reads, writes, stale_count, total_reads)
    plot_throughput(config, all_records)

    interval_file = os.path.join(OUTPUT_DIR, f"intervals_{config}.csv")
    if os.path.exists(interval_file):
        intervals = load_intervals(config)
        plot_interval_histogram(config, intervals)


def discover_configs():
    """Find all config names from latencies_*.csv files in output/."""
    pattern = os.path.join(OUTPUT_DIR, "latencies_*.csv")
    configs = []
    for path in glob.glob(pattern):
        basename = os.path.basename(path)
        # latencies_<config>.csv -> <config>
        config = basename.removeprefix("latencies_").removesuffix(".csv")
        configs.append(config)
    return sorted(configs)


def main():
    if not os.path.isdir(OUTPUT_DIR):
        print(f"No '{OUTPUT_DIR}/' directory found. Run the load tester first.")
        sys.exit(1)

    if len(sys.argv) > 1:
        configs = sys.argv[1:]
    else:
        configs = discover_configs()

    if not configs:
        print(f"No latency CSV files found in {OUTPUT_DIR}/. Run the load tester first.")
        sys.exit(1)

    print(f"Found configs: {configs}")
    for config in configs:
        process_config(config)

    print("\nDone.")


if __name__ == "__main__":
    main()
