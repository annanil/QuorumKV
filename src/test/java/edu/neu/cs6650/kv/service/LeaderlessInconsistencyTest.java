package edu.neu.cs6650.kv.service;

import edu.neu.cs6650.kv.config.NodeProperties;
import edu.neu.cs6650.kv.dto.ReplicationWriteRequest;
import edu.neu.cs6650.kv.model.VersionedValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Tests the inconsistency window in a leaderless database (N=5, W=5, R=1).
// Replication is sequential, so nodes not yet written to are temporarily stale.
class LeaderlessInconsistencyTest {

  private static final int N = 5; // total nodes
  private static final int W = 5; // write quorum
  private static final int R = 1; // read quorum

  private static final List<String> NODE_URLS = IntStream.range(0, N)
      .mapToObj(i -> "http://node-" + i + ":8080")
      .collect(Collectors.toList());

  private List<KvService> allNodes;

  // true if any read during replication returned a stale or missing value
  private AtomicBoolean inconsistencyDetected;

  @BeforeEach
  void setUp() {
    inconsistencyDetected = new AtomicBoolean(false);

    // one mock RestTemplate per node to intercept replication calls
    List<RestTemplate> restTemplates = IntStream.range(0, N)
        .mapToObj(i -> mock(RestTemplate.class))
        .collect(Collectors.toList());

    // each node knows the URLs of all other nodes as its peers
    allNodes = IntStream.range(0, N)
        .mapToObj(i -> {
          String peerUrls = IntStream.range(0, N)
              .filter(j -> j != i)
              .mapToObj(NODE_URLS::get)
              .collect(Collectors.joining(","));
          return new KvService(nodeProps(peerUrls), restTemplates.get(i));
        })
        .collect(Collectors.toList());

    // wire replication: intercept each put() call and route it to the right KvService.
    // before applying the write, read from a random node to detect inconsistency.
    for (int i = 0; i < N; i++) {
      final int selfIndex = i;
      Map<String, KvService> urlToNode = IntStream.range(0, N)
          .filter(j -> j != selfIndex)
          .boxed()
          .collect(Collectors.toMap(NODE_URLS::get, allNodes::get));

      doAnswer(invocation -> {
        String url = invocation.getArgument(0);
        ReplicationWriteRequest req = invocation.getArgument(1);

        // read from a random node mid-replication — may be stale
        KvService randomNode = allNodes.get(new Random().nextInt(N));
        VersionedValue read = randomNode.get(req.getKey());
        if (read == null || !req.getValue().equals(read.getValue())) {
          inconsistencyDetected.set(true);
          System.out.printf("Inconsistent read: expected '%s', got '%s'%n",
              req.getValue(), read == null ? "null" : read.getValue());
        }

        // apply the write to the target peer
        urlToNode.forEach((nodeUrl, node) -> {
          if (url.startsWith(nodeUrl)) {
            node.applyReplicatedWrite(req.getKey(), req.getValue(), req.getVersion());
          }
        });
        return null;
      }).when(restTemplates.get(i)).put(anyString(), any());
    }
  }

  @Test
  void testInconsistency() {
    // any node can be the write coordinator in a leaderless system
    KvService coordinator = allNodes.get(new Random().nextInt(N));

    // coordinator writes locally and replicates to all W-1 peers sequentially
    VersionedValue written = coordinator.leaderlessWrite("city", "Boston", W);

    // coordinator should have stored the value correctly
    assertNotNull(written);
    assertEquals("Boston", written.getValue());
    assertEquals(1, written.getVersion());

    // reads during sequential replication should have caught at least one stale value
    assertTrue(inconsistencyDetected.get(),
        "Expected at least one inconsistent read during the replication window");

    // after the coordinator ACKs the write, read back from the coordinator
    // the coordinator wrote locally first, so it should always be consistent
    VersionedValue coordinatorRead = coordinator.get("city");
    assertNotNull(coordinatorRead, "coordinator should have the value after ACKing the write");
    assertEquals("Boston", coordinatorRead.getValue(), "coordinator read should be consistent");

    // after the coordinator ACKs the write, read from a different node
    // with W=5 all nodes received the write synchronously, so this should also be consistent
    KvService otherNode = allNodes.stream()
        .filter(n -> n != coordinator)
        .findFirst()
        .get();
    VersionedValue otherRead = otherNode.get("city");
    assertNotNull(otherRead, "other node should have the value after W=5 write");
    assertEquals("Boston", otherRead.getValue(), "other node read should be consistent");
  }

  // --- helpers ---

  private NodeProperties nodeProps(String peerUrls) {
    NodeProperties props = new NodeProperties();
    props.setRole("leaderless");
    props.setPeerUrls(peerUrls);
    props.setWriteQuorumSize(W);
    props.setReadQuorumSize(R);
    return props;
  }
}
