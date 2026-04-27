package edu.neu.cs6650.loadtester;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.neu.cs6650.loadtester.KvClient.GetResult;
import edu.neu.cs6650.loadtester.KvClient.PutResult;
import edu.neu.cs6650.loadtester.shard.ShardConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KV client that fetches the shard config from the ShardController and routes each request to the
 * correct group leader based on consistent hashing. The config is refreshed every 5 seconds in the
 * background so clients transparently follow shard migrations without reconnecting.
 */
public class ShardAwareKvClient {

  private final String shardControllerUrl;
  private final HttpClient httpClient;
  private final Gson gson;
  private final AtomicReference<ShardConfig> config = new AtomicReference<>();
  private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(
      r -> {
        Thread t = new Thread(r, "shard-config-refresher");
        t.setDaemon(true);
        return t;
      });

  private static final int MAX_RETRIES = 3;

  public ShardAwareKvClient(String shardControllerUrl) {
    this.shardControllerUrl = shardControllerUrl.replaceAll("/+$", "");
    this.gson = new Gson();
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public void init() {
    refreshConfig();
    if (config.get() == null) {
      throw new IllegalArgumentException("Could not fetch shard config from " + shardControllerUrl);
    }
    refresher.scheduleAtFixedRate(this::refreshConfig, 5, 5, TimeUnit.SECONDS);
  }

  public void shutdown() {
    refresher.shutdownNow();
  }

  private void refreshConfig() {
    try {
      HttpRequest req = HttpRequest.newBuilder().uri(URI.create(shardControllerUrl + "/config"))
          .GET().timeout(Duration.ofSeconds(5)).build();
      HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        ShardConfig newConfig = gson.fromJson(resp.body(), ShardConfig.class);
        ShardConfig old = config.getAndSet(newConfig);
        if (old == null || old.getConfigVersion() != newConfig.getConfigVersion()) {
          System.out.println(
              "[shard] config refreshed: version=" + newConfig.getConfigVersion() + " numShards="
                  + newConfig.getNumShards());
        }
      }
    } catch (Exception e) {
      System.err.println("[shard] config refresh failed: " + e.getMessage());
    }
  }

  public KvClient.PutResult put(String key, String value) {
    ShardConfig cfg = config.get();
    int shardId = cfg.getShardId(key);
    String leaderUrl = cfg.getLeaderUrlForKey(key);
    if (leaderUrl == null) {
      return new KvClient.PutResult(key, -1, 0, -1, shardId);
    }

    JsonObject body = new JsonObject();
    body.addProperty("key", key);
    body.addProperty("value", value);
    String jsonBody = gson.toJson(body);

    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
      try {
        long start = System.currentTimeMillis();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(leaderUrl + "/leader/kv"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).timeout(Duration.ofSeconds(30))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;
        int code = resp.statusCode();
        int version = -1;
        if (code == 200 || code == 201) {
          try {
            JsonObject r = gson.fromJson(resp.body(), JsonObject.class);
            if (r != null && r.has("version")) {
              version = r.get("version").getAsInt();
            }
          } catch (Exception ignored) {}
        }
        return new PutResult(key, version, latency, code, shardId);
      } catch (Exception e) {
        if (attempt < MAX_RETRIES - 1) {
          try {
            Thread.sleep(100L * (attempt + 1));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new KvClient.PutResult(key, -1, 0, -1, shardId);
          }
        }
      }
    }
    return new KvClient.PutResult(key, -1, 0, -1, shardId);
  }

  public KvClient.GetResult get(String key) {
    ShardConfig cfg = config.get();
    int shardId = cfg.getShardId(key);
    String leaderUrl = cfg.getLeaderUrlForKey(key);
    if (leaderUrl == null) {
      return new GetResult(key, "", -1, 0, -1, false, shardId);
    }

    String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);

    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
      try {
        long start = System.currentTimeMillis();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(leaderUrl + "/leader/kv?key=" + encodedKey))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;
        int code = resp.statusCode();
        if (code == 200) {
          try {
            JsonObject r = gson.fromJson(resp.body(), JsonObject.class);
            String val = r.has("value") ? r.get("value").getAsString() : "";
            int ver = r.has("version") ? r.get("version").getAsInt() : -1;
            return new KvClient.GetResult(key, val, ver, latency, code, true, shardId);
          } catch (Exception e) {
            return new KvClient.GetResult(key, "", -1, latency, code, true, shardId);
          }
        }
        return new KvClient.GetResult(key, "", -1, latency, code, false, shardId);
      } catch (Exception e) {
        if (attempt < MAX_RETRIES - 1) {
          try {
            Thread.sleep(100L * (attempt + 1));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new KvClient.GetResult(key, "", -1, 0, -1, false, shardId);
          }
        }
      }
    }
    return new KvClient.GetResult(key, "", -1, 0, -1, false, shardId);
  }
}