package edu.neu.cs6650.loadtester;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class KvClient {

  private final HttpClient httpClient;
  private final String baseUrl;
  private final Gson gson;
  private static final int MAX_RETRIES = 5;

  public KvClient(String baseUrl) {
    this.baseUrl = baseUrl;
    this.gson = new Gson();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();
  }

  public PutResult put(String key, String value) {
    JsonObject body = new JsonObject();
    body.addProperty("key", key);
    body.addProperty("value", value);
    String jsonBody = gson.toJson(body);

    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
      try {
        long startTime = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/kv"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        long latency = System.currentTimeMillis() - startTime;
        int statusCode = response.statusCode();

        int version = -1;
        if (statusCode == 201 || statusCode == 200) {
          try {
            JsonObject respJson = gson.fromJson(response.body(), JsonObject.class);
            if (respJson != null && respJson.has("version")) {
              version = respJson.get("version").getAsInt();
            }
          } catch (Exception ignored) {
          }
        }

        return new PutResult(key, version, latency, statusCode);

      } catch (Exception e) {
        if (attempt < MAX_RETRIES - 1) {
          try {
            Thread.sleep(100L * (attempt + 1));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new PutResult(key, -1, 0, -1);
          }
        }
      }
    }
    return new PutResult(key, -1, 0, -1);
  }

  public GetResult get(String key) {
    String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);

    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
      try {
        long startTime = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/kv?key=" + encodedKey))
            .header("Content-Type", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        long latency = System.currentTimeMillis() - startTime;
        int statusCode = response.statusCode();

        if (statusCode == 200) {
          try {
            JsonObject respJson = gson.fromJson(response.body(), JsonObject.class);
            String respValue = respJson.has("value") ? respJson.get("value").getAsString() : "";
            int version = respJson.has("version") ? respJson.get("version").getAsInt() : -1;
            return new GetResult(key, respValue, version, latency, statusCode, true);
          } catch (Exception e) {
            return new GetResult(key, "", -1, latency, statusCode, true);
          }
        } else if (statusCode == 404) {
          return new GetResult(key, "", -1, latency, statusCode, false);
        } else {
          return new GetResult(key, "", -1, latency, statusCode, false);
        }
      } catch (Exception e) {
        if (attempt < MAX_RETRIES - 1) {
          try {
            Thread.sleep(100L * (attempt + 1));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new GetResult(key, "", -1, 0, -1,false);
          }
        }
      }
    }
    return new GetResult(key, "", -1, 0, -1, false);
  }

  // -- Result types --

  public static class PutResult {

    public final String key;
    public final int version;
    public final long latency;
    public final int responseCode;

    public PutResult(String key, int version, long latency, int responseCode) {
      this.key = key;
      this.version = version;
      this.latency = latency;
      this.responseCode = responseCode;
    }
  }

  public static class GetResult {

    public final String key;
    public final String value;
    public final int version;
    public final long latency;
    public final int responseCode;
    public final boolean found;

    public GetResult(String key, String value, int version, long latency, int responseCode,
        boolean found) {
      this.key = key;
      this.value = value;
      this.version = version;
      this.latency = latency;
      this.responseCode = responseCode;
      this.found = found;
    }
  }
}
