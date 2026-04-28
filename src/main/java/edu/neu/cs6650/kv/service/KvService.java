package edu.neu.cs6650.kv.service;

import edu.neu.cs6650.kv.dto.ShardEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import edu.neu.cs6650.kv.config.NodeProperties;
import edu.neu.cs6650.kv.model.VersionedValue;
import edu.neu.cs6650.kv.dto.KvReadResponse;
import edu.neu.cs6650.kv.dto.ReplicationWriteRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class KvService {

  private static final Logger log = LoggerFactory.getLogger(KvService.class);

  private final NodeProperties nodeProperties;
  private final RestTemplate restTemplate;

  // In-memory storage for the current process. Data is lost when the app stops.
  private final Map<String, VersionedValue> store = new ConcurrentHashMap<>();

  public KvService(NodeProperties nodeProperties, RestTemplate restTemplate) {
    this.nodeProperties = nodeProperties;
    this.restTemplate = restTemplate;
  }

  public VersionedValue get(String key) {
    // Simulates read latency for the local node.
    sleepMillis(50);

    return store.get(key);
  }

  public VersionedValue put(String key, String value) {
    // Simulates write latency for the local node.
    sleepMillis(200);

    VersionedValue storedValue = store.compute(key, (k, existing) ->
        existing == null
            ? new VersionedValue(k, value, 1)
            : new VersionedValue(k, value, existing.getVersion() + 1));

    // In leader mode, forward the write to all configured followers.
    if ("leader".equalsIgnoreCase(nodeProperties.getRole())) {
      ReplicationWriteRequest request = new ReplicationWriteRequest(
          storedValue.getKey(),
          storedValue.getValue(),
          storedValue.getVersion()
      );

      for (String followerUrl : getFollowerUrlList()) {
        try {
          restTemplate.put(followerUrl + "/kv/internal/write", request);
        } catch (RestClientException e) {
          log.warn("Replication to {} failed for key={} v={}: {}", followerUrl,
              storedValue.getKey(), storedValue.getVersion(), e.getMessage());
        }
      }
    }

    return storedValue;
  }

  private void sleepMillis(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Thread was interrupted during artificial delay.", e);
    }
  }

  // Applies a replicated write from another node using the exact incoming version.
  public VersionedValue applyReplicatedWrite(String key, String value, int version) {
    // Simulates write latency for replicated updates on this node.
    sleepMillis(200);

    VersionedValue replicatedValue = new VersionedValue(key, value, version);
    store.put(key, replicatedValue);
    return replicatedValue;
  }

  // Writes locally (auto-incrementing version) then replicates to W-1 followers.
  // Throws IllegalStateException("503") if quorum cannot be reached.
  public VersionedValue leaderWrite(String key, String value, int writeQuorum) {
    sleepMillis(200);

    VersionedValue storedValue = store.compute(key, (k, existing) ->
        existing == null
            ? new VersionedValue(k, value, 1)
            : new VersionedValue(k, value, existing.getVersion() + 1));

    List<String> followers = getFollowerUrlList();
    ReplicationWriteRequest request = new ReplicationWriteRequest(
        storedValue.getKey(), storedValue.getValue(), storedValue.getVersion());

    int acksNeeded = writeQuorum - 1;
    int acksReceived = 0;
    int i = 0;

    // synchronously replicate until W is satisfied
    for (; i < followers.size() && acksReceived < acksNeeded; i++) {
      try {
        restTemplate.put(followers.get(i) + "/kv/internal/write", request);
        acksReceived++;
      } catch (RestClientException e) {
        // Only fail if remaining followers cannot make up the shortfall.
        int remaining = followers.size() - i - 1;
        if (acksReceived + remaining < acksNeeded) {
          throw new IllegalStateException("503");
        }
      }
    }

    // asynchronously replicate to any followers not yet written to
    if (i < followers.size()) {
      replicateAsync(followers.subList(i, followers.size()), request);
    }

    return storedValue;
  }

  // Writes locally then replicates to peers following the NWR framework.
  // Synchronously waits for W-1 peer ACKs before returning, then asynchronously
  // replicates to any remaining peers for eventual consistency.
  // Throws IllegalStateException("503") if W ACKs cannot be reached.
  public VersionedValue leaderlessWrite(String key, String value, int writeQuorum) {
    sleepMillis(200); // local write latency

    VersionedValue storedValue = store.compute(key, (k, existing) ->
        existing == null
            ? new VersionedValue(k, value, 1)
            : new VersionedValue(k, value, existing.getVersion() + 1));

    // build replication request with the committed version
    ReplicationWriteRequest request = new ReplicationWriteRequest(
        storedValue.getKey(), storedValue.getValue(), storedValue.getVersion());

    List<String> peers = getPeerUrlList();
    int acksNeeded = writeQuorum - 1; // local write already counts as 1
    int acksReceived = 0;
    int i = 0;

    // synchronously replicate until W is satisfied
    for (; i < peers.size() && acksReceived < acksNeeded; i++) {
      try {
        restTemplate.put(peers.get(i) + "/kv/internal/write", request);
        acksReceived++;
      } catch (RestClientException e) {
        // Only fail if remaining peers cannot make up the shortfall.
        int remaining = peers.size() - i - 1;
        if (acksReceived + remaining < acksNeeded) {
          throw new IllegalStateException("503");
        }
      }
    }

    // asynchronously replicate to any peers not yet written to
    if (i < peers.size()) {
      replicateAsync(peers.subList(i, peers.size()), request);
    }

    return storedValue;
  }

  // Reads locally then fetches from R-1 peers, returning the highest-versioned value.
  // This node acts as the read coordinator. Every node (local + peers) sleeps 50ms on read.
  // Throws IllegalStateException("503") if quorum cannot be reached.
  public VersionedValue leaderlessRead(String key, int readQuorum) {
    sleepMillis(50); // local read latency

    VersionedValue best = store.get(key); // local read counts as 1

    List<String> peers = getPeerUrlList();
    int readsNeeded = readQuorum - 1; // local read already counts as 1
    int readsReceived = 0;

    for (String url : peers) {
      if (readsReceived >= readsNeeded) {
        break; // quorum satisfied, stop early
      }
      try {
        sleepMillis(50); // simulates propagation delay to peer
        ResponseEntity<KvReadResponse> resp = restTemplate.getForEntity(
            url + "/kv/local_read?key=" + key, KvReadResponse.class);
        readsReceived++;
        KvReadResponse body = resp.getBody();
        if (body != null && (best == null || body.getVersion() > best.getVersion())) {
          best = new VersionedValue(body.getKey(), body.getValue(), body.getVersion()); // keep highest version
        }
      } catch (RestClientException e) {
        // Only fail if remaining peers cannot satisfy quorum.
        if (readsReceived < readsNeeded) {
          throw new IllegalStateException("503");
        }
      }
    }

    return best;
  }

  // Reads locally then fetches from R-1 followers, returning the highest-versioned value.
  // Throws IllegalStateException("503") if quorum cannot be reached.
  public VersionedValue leaderRead(String key, int readQuorum) {
    // Local read counts as 1.
    sleepMillis(50);
    VersionedValue best = store.get(key);

    List<String> followers = getFollowerUrlList();
    int readsNeeded = readQuorum - 1;
    int readsReceived = 0;

    for (String url : followers) {
      if (readsReceived >= readsNeeded) {
        break;
      }
      try {
        ResponseEntity<KvReadResponse> resp = restTemplate.getForEntity(
            url + "/kv/local_read?key=" + key, KvReadResponse.class);
        readsReceived++;
        KvReadResponse body = resp.getBody();
        if (body != null && (best == null || body.getVersion() > best.getVersion())) {
          best = new VersionedValue(body.getKey(), body.getValue(), body.getVersion());
        }
      } catch (RestClientException e) {
        if (readsReceived < readsNeeded) {
          throw new IllegalStateException("503");
        }
      }
    }

    return best;
  }

  // Sends a write to every follower in the background without blocking the caller.
  // Used by W=1 to achieve eventual consistency after returning to the client.
  private void replicateAsync(List<String> followers, ReplicationWriteRequest request) {
    CompletableFuture.runAsync(() -> {
      for (String url : followers) {
        try {
          restTemplate.put(url + "/kv/internal/write", request);
        } catch (RestClientException e) {
          // Best-effort — if a follower is down we skip it.
        }
      }
    });
  }

  // Returns all entries in the local store whose key hashes to shardId/
  public List<ShardEntry> getShardEntries(int shardId, int numShards) {
    List<ShardEntry> result = new ArrayList<>();
    for (Map.Entry<String, VersionedValue> e : store.entrySet()) {
      if (((e.getKey().hashCode() & Integer.MAX_VALUE) % numShards) == shardId) {
        VersionedValue v = e.getValue();
        result.add(new ShardEntry(e.getKey(), v.getValue(), v.getVersion()));
      }
    }
    return result;
  }

  // Bulk-imports shard entries received during migration, applying each as a replicated write.
  public void importShardEntries(List<ShardEntry> entries) {
    for (ShardEntry e : entries) {
      VersionedValue existing = store.get(e.getKey());
      if (existing == null || e.getVersion() > existing.getVersion()) {
        store.put(e.getKey(), new VersionedValue(e.getKey(), e.getValue(), e.getVersion()));
      }
    }
  }

  // Parses the configured follower URLs into a clean list.
  private List<String> getFollowerUrlList() {
    if (nodeProperties.getFollowerUrls() == null || nodeProperties.getFollowerUrls().isBlank()) {
      return List.of();
    }

    return Arrays.stream(nodeProperties.getFollowerUrls().split(","))
        .map(String::trim)
        .filter(url -> !url.isEmpty())
        .collect(Collectors.toList());
  }

  // Parses the configured peer URLs into a clean list.
  private List<String> getPeerUrlList() {
    if (nodeProperties.getPeerUrls() == null || nodeProperties.getPeerUrls().isBlank()) {
      return List.of();
    }

    String selfUrl = nodeProperties.getSelfUrl();
    return Arrays.stream(nodeProperties.getPeerUrls().split(","))
        .map(String::trim)
        .filter(url -> !url.isEmpty())
        .filter(url -> selfUrl == null || selfUrl.isBlank() || !url.equals(selfUrl))
        .collect(Collectors.toList());
  }
}
