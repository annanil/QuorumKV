package edu.neu.cs6650.loadtester;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rubs a three-phase load test to measure the latency impact of live shard migration.
 *
 * Phase 1 — baseline (30s):       steady load, no migration
 * Phase 2 — migration (60s):      rebalance triggered at the start if this phase;
 *
 * Phase 3 — post-migration (30s): load continues after migration completes
 *
 * Per-phase P99 latency is printed at the end to quantify the migration window impact.
 */
public class MigrationTestRunner {

  private static final long BASELINE_MS  = 30_000;
  private static final long MIGRATION_MS = 60_000;
  private static final long POST_MIG_MS  = 30_000;

  private final ShardAwareKvClient client;
  private final StatsController    baselineStats  = new StatsController();
  private final StatsController    migrationStats = new StatsController();
  private final StatsController    postMigStats   = new StatsController();
  private final LoadTestConfig     config;
  private final String             shardControllerUrl;
  private final HttpClient         httpClient;

  public MigrationTestRunner(ShardAwareKvClient client, LoadTestConfig config, String shardControllerUrl) {
    this.client = client;
    this.config = config;
    this.shardControllerUrl = shardControllerUrl.replaceAll("/+$", "");
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public void run() throws InterruptedException {
    String[] keys = new String[config.getNumKeys()];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = "key-" + i;
    }

    int numThreads = config.getNumThreads();
    ExecutorService pool = Executors.newFixedThreadPool(numThreads);
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger phaseId = new AtomicInteger(0); // 0=baseline, 1=migration, 2=post

    // Phase tracking: each worker picks the active StatsController by phase
    StatsController[] phases = {baselineStats, migrationStats, postMigStats};

    CountDownLatch started = new CountDownLatch(numThreads);
    for (int i = 0; i < numThreads; i++) {
      pool.submit(() -> {
        Random rng = new Random();
        started.countDown();
        while (running.get()) {
          String key = keys[rng.nextInt(keys.length)];
          StatsController stats = phases[phaseId.get()];
          if (rng.nextInt(100) < config.getWritePercentage()) {
            KvClient.PutResult r = client.put(key, "v-" + System.nanoTime());
            stats.recordWrite(key, r.version, r.latency, r.responseCode, r.shardId);
          } else {
            KvClient.GetResult r = client.get(key);
            stats.recordRead(key, r.version, r.latency, r.responseCode, r.found, r.shardId);
          }
        }
      });
    }

    started.await();
    System.out.println("\n[migration-test] Phase 1: baseline (" + BASELINE_MS / 1000 + "s)");
    Thread.sleep(BASELINE_MS);

    System.out.println("[migration-test] Phase 2: triggering rebalance…");
    phaseId.set(1);
    triggerRebalance();
    Thread.sleep(MIGRATION_MS);

    System.out.println("[migration-test] Phase 3: post-migration (" + POST_MIG_MS / 1000 + "s)");
    phaseId.set(2);
    Thread.sleep(POST_MIG_MS);

    running.set(false);
    pool.shutdown();
    try {
      pool.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }

    System.out.println("\n========== MIGRATION TEST RESULTS ==========");
    printPhase("Baseline   ", baselineStats);
    printPhase("Migration  ", migrationStats);
    printPhase("Post-migr. ", postMigStats);
    System.out.println("============================================");

    // Write combined CSV
    String label = config.getConfigName();
    new java.io.File("output").mkdirs();
    baselineStats.writeLatencyCSV("output/latencies_" + label + "_baseline.csv");
    migrationStats.writeLatencyCSV("output/latencies_" + label + "_migration.csv");
    postMigStats.writeLatencyCSV("output/latencies_" + label + "_postmig.csv");
  }

  private void triggerRebalance() {
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(shardControllerUrl + "/config/rebalance"))
          .PUT(HttpRequest.BodyPublishers.noBody())
          .timeout(Duration.ofSeconds(30))
          .build();
      HttpResponse<String> resp = httpClient.send(req, BodyHandlers.ofString());
      System.out.println("[migration-test] rebalance response: HTTP " + resp.statusCode());
    } catch (Exception e) {
      System.err.println("[migration-test] rebalance failed: " + e.getMessage());
    }
  }

  private void printPhase(String label, StatsController stats) {
    System.out.println("\n--- " + label + " ---");
    System.out.println("  Writes OK : " + stats.getWriteSuccess());
    System.out.println("  Reads  OK : " + stats.getReadSuccess());
    System.out.println("  Failures  : " + stats.getTotalFailure());
    System.out.println("  Stale     : " + stats.getStaleReadCount());
    stats.printStatistics();
  }
}
