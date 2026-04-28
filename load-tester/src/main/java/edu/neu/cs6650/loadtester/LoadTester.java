package edu.neu.cs6650.loadtester;

import java.io.File;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadTester {

  public static void main(String[] args) {
    if (args.length == 0) {
      printUsage();
      return;
    }

    LoadTestConfig config = LoadTestConfig.fromArgs(args);
    System.out.println("Starting load test: " + config);

    if (config.getShardControllerUrl() != null) {
      boolean replicaReads = config.getReadUrl() != null
          && !config.getReadUrl().equals(config.getShardControllerUrl());
      ShardAwareKvClient shardClient =
          new ShardAwareKvClient(config.getShardControllerUrl(), replicaReads);
      shardClient.init();
      try {
        runSharded(config, shardClient);
      } finally {
        shardClient.shutdown();
      }
      return;
    }

    runDirect(config);
  }

  private static void runDirect(LoadTestConfig config) {
    String[] keys = buildKeyPool(config.getNumKeys());
    KvClient writeClient = new KvClient(config.getWriteUrl(), config.getMode());
    KvClient readClient = new KvClient(config.getReadUrl(), config.getMode());
    StatsController stats = new StatsController();
    AtomicInteger completedRequests = new AtomicInteger(0);
    int numThreads = config.getNumThreads();
    int totalRequests = config.getTotalRequests();
    int requestsPerThread = totalRequests / numThreads;
    int remainingRequests = totalRequests % numThreads;

    System.out.println("Sending " + totalRequests + " requests with " + numThreads + " threads ("
        + config.getWritePercentage() + "% write, " + (100 - config.getWritePercentage()) + "% reads)");
    System.out.println("Key pool size: " + config.getNumKeys());
    System.out.println("Write URL: " + config.getWriteUrl());
    System.out.println("Read URL:  " + config.getReadUrl());

    long startTime = System.currentTimeMillis();
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < numThreads; i++) {
      int threadRequests = requestsPerThread + (i < remainingRequests ? 1 : 0);
      final int threadId = i;
      threads[i] = new Thread(() -> {
        Random random = new Random();
        for (int r = 0; r < threadRequests; r++) {
          String key = keys[random.nextInt(keys.length)];
          if (random.nextInt(100) < config.getWritePercentage()) {
            String value = "v-" + threadId + "-" + r + "-" + System.nanoTime();
            KvClient.PutResult result = writeClient.put(key, value);
            stats.recordWrite(key, result.version, result.latency, result.responseCode, result.shardId);
          } else {
            KvClient.GetResult result = readClient.get(key);
            stats.recordRead(key, result.version, result.latency, result.responseCode, result.found, result.shardId);
          }
          int done = completedRequests.incrementAndGet();
          if (done % 1000 == 0) {
            System.out.println("Progress: " + done + "/" + totalRequests);
          }
        }
      }, "worker-" + threadId);
      threads[i].start();
    }

    joinAll(threads);
    printSummary(config, stats, System.currentTimeMillis() - startTime);
  }

  private static void runSharded(LoadTestConfig config, ShardAwareKvClient client) {
    String[] keys = buildKeyPool(config.getNumKeys());
    StatsController stats = new StatsController();
    AtomicInteger completedRequests = new AtomicInteger(0);
    int numThreads = config.getNumThreads();
    int totalRequests = config.getTotalRequests();
    int requestsPerThread = totalRequests / numThreads;
    int remainingRequests = totalRequests % numThreads;

    System.out.println("Sending " + totalRequests + " requests via ShardAwareKvClient ("
        + config.getWritePercentage() + "% write)");
    System.out.println("Shard controller: " + config.getShardControllerUrl());

    long startTime = System.currentTimeMillis();
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < numThreads; i++) {
      int threadRequests = requestsPerThread + (i < remainingRequests ? 1 : 0);
      final int threadId = i;
      threads[i] = new Thread(() -> {
        Random random = new Random();
        for (int r = 0; r < threadRequests; r++) {
          String key = keys[random.nextInt(keys.length)];
          if (random.nextInt(100) < config.getWritePercentage()) {
            KvClient.PutResult result = client.put(key, "v-" + threadId + "-" + r);
            stats.recordWrite(key, result.version, result.latency, result.responseCode, result.shardId);
          } else {
            KvClient.GetResult result = client.get(key);
            stats.recordRead(key, result.version, result.latency, result.responseCode, result.found, result.shardId);
          }
          int done = completedRequests.incrementAndGet();
          if (done % 1000 == 0) {
            System.out.println("Progress: " + done + "/" + totalRequests);
          }
        }
      }, "shard-worker-" + threadId);
      threads[i].start();
    }

    joinAll(threads);
    printSummary(config, stats, System.currentTimeMillis() - startTime);
  }

  private static String[] buildKeyPool(int numKeys) {
    String[] keys = new String[numKeys];
    for (int i = 0; i < numKeys; i++) {
      keys[i] = "key-" + i;
    }
    return keys;
  }

  private static void joinAll(Thread[] threads) {
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void printSummary(LoadTestConfig config, StatsController stats, long totalTime) {
    new File("output").mkdirs();
    String label = config.getConfigName();
    stats.writeLatencyCSV("output/latencies_" + label + ".csv");
    stats.writeIntervalCSV("output/intervals_" + label + ".csv");
    stats.printStatistics();
    System.out.println("\n========== FINAL SUMMARY ==========");
    System.out.println("Config:         " + label);
    System.out.println("Total time:     " + totalTime + " ms");
    System.out.println("Total requests: " + config.getTotalRequests());
    System.out.println("Successful:     " + stats.getTotalSuccess());
    System.out.println("Failed:         " + stats.getTotalFailure());
    System.out.printf("Throughput:     %.2f req/sec%n", stats.getTotalSuccess() * 1000.0 / totalTime);
    System.out.println("Stale reads:    " + stats.getStaleReadCount());
    if (stats.getReadSuccess() > 0) {
      System.out.printf("Stale read %%:   %.2f%%%n",
          stats.getStaleReadCount() * 100.0 / stats.getReadSuccess());
    }
    System.out.println("====================================");
  }

  private static void printUsage() {
    System.out.println("Usage: java -jar load-tester.jar --write-url=<URL> [options]");
    System.out.println("       java -jar load-tester.jar --shard-controller=<URL> [options]");
    System.out.println("Options:");
    System.out.println("  --write-url=<URL>        URL for writes (leader or ALB) [required unless --shard-controller]");
    System.out.println("  --read-url=<URL>         URL for reads [defaults to write-url]");
    System.out.println("  --shard-controller=<URL> ShardController URL; enables shard-aware routing");
    System.out.println("  --config=<name>          Config label for output files [default: default]");
    System.out.println("  --requests=<N>           Total number of requests [default: 10000]");
    System.out.println("  --threads=<N>            Worker threads [default: 16]");
    System.out.println("  --write-pct=<0-100>      Write percentage [default: 50]");
    System.out.println("  --num-keys=<N>           Key pool size [default: 10]");
  }
}
