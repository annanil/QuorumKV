#!/usr/bin/env bash
# Run a 4-config write-ratio sweep against a running cluster.
#
# Usage:
#   WRITE_URL=http://<leader-ip>:8080 ./run_sweep.sh <config-prefix>
#   WRITE_URL=http://<leader-ip>:8080 READ_URL=http://<alb-dns> ./run_sweep.sh lf-w3r3
#
# Each run produces:
#   output/latencies_<prefix>-write<pct>.csv
#   output/intervals_<prefix>-write<pct>.csv

set -euo pipefail

CONFIG_PREFIX="${1:-run}"
WRITE_URL="${WRITE_URL:?'Set WRITE_URL=http://<leader-ip>:8080'}"
READ_URL="${READ_URL:-$WRITE_URL}"
REQUESTS="${REQUESTS:-10000}"
THREADS="${THREADS:-16}"
NUM_KEYS="${NUM_KEYS:-10}"

JAR="$(dirname "$0")/target/load-tester-1.0-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
  echo "ERROR: JAR not found at $JAR — run: cd load-tester && mvn package -DskipTests"
  exit 1
fi

mkdir -p "$(dirname "$0")/output"

for pct in 1 10 50 90; do
  label="${CONFIG_PREFIX}-write${pct}"
  echo ""
  echo "=== Running: $label (${pct}% writes, $((100 - pct))% reads) ==="
  java -jar "$JAR" \
    --write-url="$WRITE_URL" \
    --read-url="$READ_URL" \
    --config="$label" \
    --requests="$REQUESTS" \
    --threads="$THREADS" \
    --num-keys="$NUM_KEYS" \
    --write-pct="$pct"
done

echo ""
echo "Sweep complete. Results in load-tester/output/"