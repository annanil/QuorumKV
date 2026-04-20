#!/usr/bin/env python3
"""Assert that a latency CSV meets equality thresholds.

Usage:
  python3 check_results.py <csv-file> [--max-error-pct=<float>]

Exits 0 on pass, 1 on failure.

Columns: start_time,request_type,latency,response_code,key,version,stale
"""
import csv
import sys

MAX_ERROR_PCT_DEFAULT = 0.0

def parse_args():
  csv_file = None
  max_error_pct = MAX_ERROR_PCT_DEFAULT
  for arg in sys.argv[1:]:
    if arg.startswith("--max-error-pct="):
      max_error_pct = float(arg.split("=", 1)[1])
    elif not arg.startswith("--"):
      csv_file = arg
  if csv_file is None:
    print("Usage: check_results.py <csv-file> [--max-error-pct=<float>]")
    sys.exit(2)
  return csv_file, max_error_pct

def main():
  csv_file, max_error_pct = parse_args()

  total = 0
  errors = 0

  try:
    with open(csv_file, newline="") as f:
      reader = csv.DictReader(f)
      for row in reader:
        total += 1
        code = int(row["response_code"])
        if code >= 500 or code == -1:
          errors += 1
  except FileNotFoundError:
    print(f"ERROR: file not found: {csv_file}")
    sys.exit(1)
  except (KeyError, ValueError) as e:
    print(f"ERROR: malformed CSV ({e})")
    sys.exit(1)

  if total == 0:
    print("ERROR: CSV has no data rows")
    sys.exit(1)

  error_pct = (errors / total) * 100
  print(f"Total request : {total}")
  print(f"5xx / timeout : {errors}  ({error_pct:.2f}%)")
  print(f"Threshold     : {max_error_pct:.2f}%")

  if error_pct > max_error_pct:
    print(f"FAIL: error rate {error_pct:.2f}% exceeds thresholds {max_error_pct}")
    sys.exit(1)

  print("PASS")

if __name__ == "__main__":
  main()