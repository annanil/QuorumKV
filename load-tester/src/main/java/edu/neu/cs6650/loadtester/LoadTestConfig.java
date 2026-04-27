package edu.neu.cs6650.loadtester;

import lombok.Data;

/**
 * Parses CLI arguments into a typed config object.
 * <p>
 * Required: --write-url=<URL>     URL for writes (leader IP in LF mode, ALB in leaderless)
 * --read-url=<URL>      URL for reads (ALB or round-robin target)
 * <p>
 * Optional: --config=<name>       Label for output files (e.g. "lf-w5r1-write50") --requests=<N>
 *     Total requests to send (default: 10000) --threads=<N>         Worker threads (default: 32)
 * --write-pct=<0-100>   Percentage of requests that are writes (default: 50) --num-keys=<N>
 * Key pool size for temporal locality (default: 50)
 */

@Data
public class LoadTestConfig {

  private String writeUrl;
  private String readUrl;
  private String configName = "default";
  private int totalRequests = 10000;
  private int numThreads = 16;
  private int writePercentage = 50;
  private int numKeys = 10;
  private String mode = "leader";
  private String shardControllerUrl = null;
  private boolean migrationTest = false;

  public static LoadTestConfig fromArgs(String[] args) {
    LoadTestConfig config = new LoadTestConfig();
    for (String arg : args) {
      if (arg.startsWith("--write-url=")) {
        config.writeUrl = arg.substring("--write-url=".length());
      } else if (arg.startsWith("--read-url=")) {
        config.readUrl = arg.substring("--read-url=".length());
      } else if (arg.startsWith("--config=")) {
        config.configName = arg.substring("--config=".length());
      } else if (arg.startsWith("--requests=")) {
        config.totalRequests = Integer.parseInt(arg.substring("--requests=".length()));
      } else if (arg.startsWith("--threads=")) {
        config.numThreads = Integer.parseInt(arg.substring("--threads=".length()));
      } else if (arg.startsWith("--write-pct=")) {
        config.writePercentage = Integer.parseInt(arg.substring("--write-pct=".length()));
      } else if (arg.startsWith("--num-keys=")) {
        config.numKeys = Integer.parseInt(arg.substring("--num-keys=".length()));
      } else if (arg.startsWith("--mode=")) {
        config.mode = arg.substring("--mode=".length());
      } else if (arg.startsWith("--shard-controller=")) {
        config.shardControllerUrl = arg.substring("--shard-controller=".length());
      } else if (arg.startsWith("--migration-test=")) {
        config.migrationTest = true;
      }
    }

    if (config.shardControllerUrl == null && config.writeUrl == null) {
      throw new IllegalArgumentException("--write-url is required (or use --shard-controller)");
    }
    if (config.readUrl == null) {
      config.readUrl = config.writeUrl != null ? config.writeUrl : config.shardControllerUrl;
    }

    config.writeUrl = config.writeUrl.replaceAll("/+$", "");
    config.readUrl = config.readUrl.replaceAll("/+$", "");

    return config;
  }

  @Override
  public String toString() {
    return "Config{writeUrl='" + writeUrl + "', readUrl='" + readUrl + "', config='" + configName
        + "', requests=" + totalRequests + ", threads=" + numThreads + ", writePct="
        + writePercentage + ", numKeys=" + numKeys + '}';
  }
}
