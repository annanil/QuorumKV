package edu.neu.cs6650.loadtester;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class StatsController {

  private final AtomicInteger readSuccess = new AtomicInteger(0);
  private final AtomicInteger readFailure = new AtomicInteger(0);
  private final AtomicInteger writeSuccess = new AtomicInteger(0);
  private final AtomicInteger writeFailure = new AtomicInteger(0);

  private final AtomicLong totalReadLatency = new AtomicLong(0);
  private final AtomicLong totalWriteLatency = new AtomicLong(0);

  private final AtomicInteger staleReadCount = new AtomicInteger(0);

  private final ConcurrentLinkedQueue<LatencyRecord> latencyRecords = new ConcurrentLinkedQueue<>();

  // Stale detection: track the highest version we've written per key
  private final ConcurrentHashMap<String, Integer> lastWrittenVersion = new ConcurrentHashMap<>();
  // Interval tracking: track when each key was last written
  private final ConcurrentHashMap<String, Long> lastWriteTimestamp = new ConcurrentHashMap<>();
  // Collected intervals for CSV output
  private final ConcurrentLinkedQueue<Long> readWriteIntervals = new ConcurrentLinkedQueue<>();

  public void recordWrite(String key, int version, long latency, int responseCode) {
    long now = System.currentTimeMillis();
    boolean success = (responseCode == 201 || responseCode == 200);

    if (success) {
      writeSuccess.incrementAndGet();
      totalWriteLatency.addAndGet(latency);
      if (version >= 0) {
        lastWrittenVersion.compute(key,
            (k, existing) -> (existing == null || version > existing) ? version : existing);
      }
      lastWriteTimestamp.put(key, now);
    } else {
      writeFailure.incrementAndGet();
    }

    latencyRecords.add(new LatencyRecord(now, "PUT", latency, responseCode, key, version, false));
  }

  public void recordRead(String key, int version, long latency, int responseCode, boolean found) {
    long now = System.currentTimeMillis();
    boolean success = (responseCode == 200 || responseCode == 404);
    boolean stale = false;

    if (success) {
      readSuccess.incrementAndGet();
      totalReadLatency.addAndGet(latency);

      // Stale detection
      if (found && version >= 0) {
        Integer lastVersion = lastWrittenVersion.get(key);
        if (lastVersion != null && version < lastVersion) {
          stale = true;
          staleReadCount.incrementAndGet();
        }
      }

      // Interval tracking
      Long lastWrite = lastWriteTimestamp.get(key);
      if (lastWrite != null) {
        long interval = now - lastWrite;
        readWriteIntervals.add(interval);
      }
    } else {
      readFailure.incrementAndGet();
    }

    latencyRecords.add(new LatencyRecord(now, "GET", latency, responseCode, key, version, stale));
  }

  public void writeLatencyCSV(String filename) {
    try (PrintWriter writer = new PrintWriter((new FileWriter(filename)))) {
      writer.println(LatencyRecord.CSV_HEADER);
      for (LatencyRecord record : latencyRecords) {
        writer.println((record.toCSV()));
      }
      System.out.println("Latency CSV written to: " + filename);
    } catch (IOException e) {
      System.err.println("Error writing latency CSV: " + e.getMessage());
    }
  }

  public void writeIntervalCSV(String filename) {
    try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
      writer.println("read_write_interval_ms");
      for (Long interval : readWriteIntervals) {
        writer.println(interval);
      }
      System.out.println("Interval CSV written to: " + filename);
    } catch (IOException e) {
      System.err.println("Error writing interval CSV: " + e.getMessage());
    }
  }

  public void printStatistics() {
    List<Long> readLatencies = new ArrayList<>();
    List<Long> writeLatencies = new ArrayList<>();

    for (LatencyRecord record : latencyRecords) {
      if ("GET".equals(record.getRequestType()) && record.getLatency() > 0) {
        readLatencies.add(record.getLatency());
      } else if ("PUT".equals(record.getRequestType()) && record.getLatency() > 0) {
        writeLatencies.add(record.getLatency());
      }
    }

    System.out.println("\n========== LOAD TEST STATISTICS ==========");

    System.out.println("\n--- WRITE (PUT) Statistics ---");
    System.out.println("  Successful: " + writeSuccess.get());
    System.out.println("  Failed:     " + writeFailure.get());
    if (!writeLatencies.isEmpty()) {
      printLatencyStats(writeLatencies);
    }

    System.out.println("\n--- READ (GET) Statistics ---");
    System.out.println("  Successful: " + readSuccess.get());
    System.out.println("  Failed:     " + readFailure.get());
    if (!readLatencies.isEmpty()) {
      printLatencyStats(readLatencies);
    }

    System.out.println("\n--- Staleness ---");
    System.out.println("  Stale reads: " + staleReadCount.get());
    if (readSuccess.get() > 0) {
      double stalePct = (staleReadCount.get() * 100.0) / readSuccess.get();
      System.out.printf("  Stale read %%: %.2f%%%n", stalePct);
    }

    List<Long> intervals = new ArrayList<>(readWriteIntervals);
    if (!intervals.isEmpty()) {
      Collections.sort(intervals);
      System.out.println("\n--- Read-Write Interval Statistics ---");
      System.out.println("  Count:  " + intervals.size());
      System.out.println("  Mean:   " + mean(intervals) + " ms");
      System.out.println("  Median: " + percentile(intervals, 50) + " ms");
      System.out.println("  P99:    " + percentile(intervals, 99) + " ms");
      System.out.println("  Min:    " + intervals.get(0) + " ms");
      System.out.println("  Max:    " + intervals.get(intervals.size() - 1) + " ms");
    }

    System.out.println("\n==========================================");
  }

  private void printLatencyStats(List<Long> latencies) {
    Collections.sort(latencies);
    System.out.println("  Count:  " + latencies.size());
    System.out.println("  Mean:   " + mean(latencies) + " ms");
    System.out.println("  Median: " + percentile(latencies, 50) + " ms");
    System.out.println("  P99:    " + percentile(latencies, 99) + " ms");
    System.out.println("  Min:    " + latencies.get(0) + " ms");
    System.out.println("  Max:    " + latencies.get(latencies.size() - 1) + " ms");
  }

  private long mean(List<Long> values) {
    long sum = 0;
    for (Long v : values) {
      sum += v;
    }
    return sum / values.size();
  }

  private long percentile(List<Long> sortedValues, int pct) {
    int index = (int) Math.ceil(pct / 100.0 * sortedValues.size()) - 1;
    index = Math.max(0, Math.min(index, sortedValues.size() - 1));
    return sortedValues.get(index);
  }

  // -- Getters for final summary --

  public int getStaleReadCount() {
    return staleReadCount.get();
  }

  public int getTotalSuccess() {
    return readSuccess.get() + writeSuccess.get();
  }

  public int getTotalFailure() {
    return readFailure.get() + writeFailure.get();
  }

  public int getReadSuccess() {
    return readSuccess.get();
  }

  public int getWriteSuccess() {
    return writeSuccess.get();
  }
}
