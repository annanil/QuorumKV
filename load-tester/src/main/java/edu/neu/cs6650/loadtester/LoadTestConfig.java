package edu.neu.cs6650.loadtester;

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

public class LoadTestConfig {

  private String writeUrl;
  private String readUrl;
  private String configName = "default";
  private int totalRequests = 10000;
  private int numThreads = 16;
  private int writePercentage = 50;
  private int numKeys = 10;

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
      }
    }

    if (config.writeUrl == null) {
      throw new IllegalArgumentException("--write-url is required");
    }
    if (config.readUrl == null) {
      config.readUrl = config.writeUrl;
    }

    config.writeUrl = config.writeUrl.replaceAll("/+$", "");
    config.readUrl = config.readUrl.replaceAll("/+$", "");

    return config;
  }

  public String getWriteUrl() {
    return writeUrl;
  }

  public String getReadUrl() {
    return readUrl;
  }

  public String getConfigName() {
    return configName;
  }

  public int getTotalRequests() {
    return totalRequests;
  }

  public int getNumThreads() {
    return numThreads;
  }

  public int getWritePercentage() {
    return writePercentage;
  }

  public int getNumKeys() {
    return numKeys;
  }

  @Override
  public String toString() {
    return "Config{writeUrl='" + writeUrl + "', readUrl='" + readUrl + "', config='" + configName
        + "', requests=" + totalRequests + ", threads=" + numThreads + ", writePct="
        + writePercentage + ", numKeys=" + numKeys + '}';
  }
}
