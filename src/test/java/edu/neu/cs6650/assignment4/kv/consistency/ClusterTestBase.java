package edu.neu.cs6650.assignment4.kv.consistency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared setup for all cluster integration tests.
 * Tests hit the running Docker Compose cluster via HTTP.
 */
abstract class ClusterTestBase {

  static final String LEADER = System.getenv().getOrDefault("LEADER_URL", "http://localhost:8080");
  static final List<String> FOLLOWERS = System.getenv().containsKey("FOLLOWER_URLS")
      ? List.of(System.getenv("FOLLOWER_URLS").split(","))
      : List.of("http://localhost:8081", "http://localhost:8082", "http://localhost:8083", "http://localhost:8084");

  private static final int WAIT_TIMEOUT_SECONDS = 60;

  RestTemplate rest;
  String key;

  // Wait for the leader to be reachable before any test runs.
  // This handles the case where Docker Compose was just started and the JVM
  // hasn't finished booting yet.
  @BeforeAll
  static void waitForCluster() throws InterruptedException {
    RestTemplate probe = new RestTemplate();
    long deadline = System.currentTimeMillis() + (WAIT_TIMEOUT_SECONDS * 1000L);

    System.out.println("Waiting for cluster at " + LEADER + " ...");
    while (System.currentTimeMillis() < deadline) {
      try {
        probe.getForObject(LEADER + "/node/info", Map.class);
        System.out.println("Cluster is up.");
        return;
      } catch (Exception e) {
        Thread.sleep(2000);
        System.out.print(".");
      }
    }

    fail("Cluster did not become reachable within " + WAIT_TIMEOUT_SECONDS + "s. " +
         "Start it with: WRITE_QUORUM=<W> READ_QUORUM=<R> docker compose up --build -d");
  }

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
    key = "test-" + UUID.randomUUID();
  }

  // Fetches /node/info from the leader and asserts the cluster is configured
  // with the expected quorum sizes. Fails fast with a clear message if not.
  @SuppressWarnings("unchecked")
  void assertQuorumConfig(int expectedWrite, int expectedRead) {
    Map<String, Object> info = rest.getForObject(LEADER + "/node/info", Map.class);

    int actualWrite = (int) info.get("writeQuorumSize");
    int actualRead  = (int) info.get("readQuorumSize");

    assertEquals(expectedWrite, actualWrite,
        "Wrong WRITE_QUORUM: expected " + expectedWrite + " but cluster is configured with " + actualWrite +
        ". Restart with: WRITE_QUORUM=" + expectedWrite + " READ_QUORUM=" + expectedRead + " docker compose up -d");
    assertEquals(expectedRead, actualRead,
        "Wrong READ_QUORUM: expected " + expectedRead + " but cluster is configured with " + actualRead +
        ". Restart with: WRITE_QUORUM=" + expectedWrite + " READ_QUORUM=" + expectedRead + " docker compose up -d");
  }
}
