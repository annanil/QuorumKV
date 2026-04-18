#!/usr/bin/env python3

import csv
import os
import sys
from collections import defaultdict

try:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
except ImportError:
    print("ERROR: matplotlib not installed. Run: pip3 install matplotlib")
    sys.exit(1)

def read_latency_csv(filepath):
    """Parse latencies_*.csv into list of dicts."""
    records = []
    with open(filepath, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            records.append({
                'start_time': int(row['start_time']),
                'type': row['request_type'],
                'latency': int(row['latency']),
                'code': int(row['response_code']),
                'key': row['key'],
                'version': int(row['version']),
                'stale': row['stale'].lower() == 'true',
            })
    return records

def read_interval_csv(filepath):
    """Parse intervals_*.csv into list of ints."""
    intervals = []
    with open(filepath, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            intervals.append(int(row['read_write_interval_ms']))
    return intervals

def plot_latency_boxplot(records, config_name, output_dir):
    """Boxplot comparing read vs write latency."""
    reads = [r['latency'] for r in records if r['type'] == 'GET' and r['latency'] > 0]
    writes = [r['latency'] for r in records if r['type'] == 'PUT' and r['latency'] > 0]

    if not reads and not writes:
        return
    
    fig, ax = plt.subplots(figsize=(8, 5))
    data = []
    labels = []
    if writes:
        data.append(writes)
        labels.append(f'PUT (n={len(writes)})')
    if reads:
        data.append(reads)
        labels.append(f'GET (n={len(reads)})')

    ax.boxplot(data, labels=labels, showfliers=False)
    ax.set_ylabel('Latency (ms)')
    ax.set_title(f'Latency Distribution -- {config_name}')
    ax.grid(axis='y', alpha=0.3)

    path = os.path.join(output_dir, f'latency_boxplot_{config_name}.png')
    plt.tight_layout()
    plt.savefig(path, dpi=150)
    plt.close()
    print(f'  Saved: {path}')

def plot_throughput(records, config_name, output_dir):
    """Throughput (requests/sec) over time, binned into 1-second windows."""
    if not records:
        return

    min_time = min(r['start_time'] for r in records)
    buckets = defaultdict(int)
    for r in records:
        second = (r['start_time'] - min_time) // 1000
        buckets[second] += 1

    if not buckets:
        return

    max_sec = max(buckets.keys())
    x = list(range(max_sec + 1))
    y = [buckets.get(s, 0) for s in x]

    fig, ax = plt.subplots(figsize=(10, 4))
    ax.bar(x, y, width=1.0, edgecolor='none', alpha=0.7)
    ax.set_xlabel('Time (seconds)')
    ax.set_ylabel('Requests / sec')
    ax.set_title(f'Throughput Over Time -- {config_name}')
    ax.grid(axis='y', alpha=0.3)

    path = os.path.join(output_dir, f'throughput_{config_name}.png')
    plt.tight_layout()
    plt.savefig(path, dpi=150)
    plt.close()
    print(f'  Saved: {path}')


def plot_intervals(intervals, config_name, output_dir):
    """Histogram of read-write intervals for temporal locality analysis."""
    if not intervals:
        return

    fig, ax = plt.subplots(figsize=(8, 5))
    ax.hist(intervals, bins=50, edgecolor='black', alpha=0.7)
    ax.set_xlabel('Interval (ms) between write and subsequent read of same key')
    ax.set_ylabel('Count')
    ax.set_title(f'Read-Write Interval Distribution -- {config_name}')
    ax.grid(axis='y', alpha=0.3)

    path = os.path.join(output_dir, f'interval_hist_{config_name}.png')
    plt.tight_layout()
    plt.savefig(path, dpi=150)
    plt.close()
    print(f'  Saved: {path}')


def main():
    output_dir = 'output'
    filter_config = sys.argv[1] if len(sys.argv) > 1 else None

    latency_files = sorted([f for f in os.listdir(output_dir) if f.startswith('latencies_') and f.endswith('.csv')])

    if not latency_files:
        print("No CSV files found in output/. Run the load tester first.")
        return

    for lf in latency_files:
        config_name = lf.replace('latencies_', '').replace('.csv', '')

        if filter_config and config_name != filter_config:
            continue

        print(f'\nProcessing config: {config_name}')

        records = read_latency_csv(os.path.join(output_dir, lf))
        plot_latency_boxplot(records, config_name, output_dir)
        plot_throughput(records, config_name, output_dir)

        # Stale read count
        stale = sum(1 for r in records if r['stale'])
        total_reads = sum(1 for r in records if r['type'] == 'GET')
        if total_reads > 0:
            print(f'  Stale reads: {stale}/{total_reads} ({stale*100.0/total_reads:.2f}%)')

        # Interval histogram
        interval_file = os.path.join(output_dir, f'intervals_{config_name}.csv')
        if os.path.exists(interval_file):
            intervals = read_interval_csv(interval_file)
            plot_intervals(intervals, config_name, output_dir)

    print('\nDone.')


if __name__ == '__main__':
    main()