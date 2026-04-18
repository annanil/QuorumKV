package edu.neu.cs6650.loadtester;

import java.io.File;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadTester {

  public static void main(String[] args) {
    if (args.length == 0) {
      System.out.println("Usage: java -jar load-tester.jar --write-url=<URL> [options]");
      System.out.println("Options:");
      System.out.println("  --write-url=<URL>   URL for writes (leader IP or ALB) [required]");
      System.out.println("  --read-url=<URL>    URL for reads (ALB or any node) [defaults to write-url]");
      System.out.println("  --config=<name>     Config label for output files [default: default]");
      System.out.println("  --requests=<N>      Total number of requests [default: 10000]");
      System.out.println("  --threads=<N>       Worker threads [default: 32]");
      System.out.println("  --write-pct=<0-100> Write percentage [default: 50]");
      System.out.println("  --num-keys=<N>      Key pool size [default: 50]");
      return;
    }

    LoadTestConfig config = LoadTestConfig.fromArgs(args);
    System.out.println("Starting load test: " + config);

    // Create key pool -- small pool = temporal locality
    String[] keys = new String[config.getNumKeys()];
    for (int i = 0; i < config.getNumKeys(); i++) {
      keys[i] = "key-" + i;
    }

    // Separate clients for writes (leader) and reads (ALB/any node)
    KvClient writeClient = new KvClient(config.getWriteUrl());
    KvClient readClient = new KvClient(config.getReadUrl());

    StatsController stats = new StatsController();
    AtomicInteger completedRequests = new AtomicInteger(0);

    int numThreads = config.getNumThreads();
    int totalRequests = config.getTotalRequests();
    int requestsPerThread = totalRequests / numThreads;
    int remainingRequests = totalRequests % numThreads;

    Thread[] threads = new Thread[numThreads];

    System.out.println("Sending " + totalRequests + " requests with " + numThreads + " threads (" + config.getWritePercentage() + "% write, " + (100 - config.getWritePercentage()) + "% reads)");
    System.out.println("Key pool size: " + config.getNumKeys());
    System.out.println("Write URL: " + config.getWriteUrl());
    System.out.println("Read URL: " + config.getReadUrl());

    long startTime = System.currentTimeMillis();

    for (int i = 0; i < numThreads; i++) {
      int threadRequests = requestsPerThread + (i < remainingRequests ? 1 : 0);
      final int threadId = i;

      threads[i] = new Thread(() -> {
        Random random = new Random();

        for (int r = 0; r < threadRequests; r++) {
          // Pick a random key from the small pool
          String key = keys[random.nextInt(keys.length)];
          int roll = random.nextInt(100);

          if (roll < config.getWritePercentage()) {
            // WRITE: send to leader (or ALB for leaderless)
            String value = "v-" + threadId + "-" + r + "-" + System.nanoTime();
            KvClient.PutResult result = writeClient.put(key, value);
            stats.recordWrite(key, result.version, result.latency, result.responseCode);
          } else {
            // READ: send to ALB or any node
            KvClient.GetResult result = readClient.get(key);
            stats.recordRead(key, result.version, result.latency, result.responseCode, result.found);
          }

          int completed = completedRequests.incrementAndGet();
          if (completed % 1000 == 0) {
            System.out.println("Progress: " + completed + "/" + totalRequests);
          }
        }
      }, "worker-" + threadId);

      threads[i].start();
    }

    // Wait for all threads
    for (Thread thread : threads) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    long totalTime = System.currentTimeMillis() - startTime;

    // Ensure output directory exists
    new File("output").mkdirs();

    // Write CSV files
    String label = config.getConfigName();
    stats.writeLatencyCSV("output/latencies_" + label + ".csv");
    stats.writeIntervalCSV("output/intervals_" + label + ".csv");

    // Print statistics
    stats.printStatistics();

    // Final summary
    System.out.println("\n========== FINAL SUMMARY ==========");
    System.out.println("Config:           " + label);
    System.out.println("Total time:       " + totalTime + " ms");
    System.out.println("Total requests:   " + totalRequests);
    System.out.println("Successful:       " + stats.getTotalSuccess());
    System.out.println("Failed:           " + stats.getTotalFailure());
    double throughput = (stats.getTotalSuccess() * 1000.0) / totalTime;
    System.out.printf("Throughput:       %.2f req/sec%n", throughput);
    System.out.println("Stale reads:      " + stats.getStaleReadCount());
    if (stats.getReadSuccess() > 0) {
      double stalePct = (stats.getStaleReadCount() * 100.0) / stats.getReadSuccess();
      System.out.printf("Stale read %%:     %.2f%%%n", stalePct);
    }
    System.out.println("====================================");
  }
}
