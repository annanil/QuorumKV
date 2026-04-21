package edu.neu.cs6650.kv.consistency;

import edu.neu.cs6650.kv.dto.KvReadResponse;
import edu.neu.cs6650.kv.dto.KvWriteRequest;
import edu.neu.cs6650.kv.dto.KvWriteResponse;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress runner that repeats the three consistency tests many times.
 *
 * Tests 1 & 2 use @RepeatedTest — they should ALWAYS pass (strong consistency with W=5).
 * Test 3 runs in a single loop and asserts that at least some iterations catch stale
 * data, proving the inconsistency window is real.
 *
 * Run all:         ./mvnw test -Dtest=ConsistencyStressTest
 * Run test 3 only: ./mvnw test -Dtest="ConsistencyStressTest#test3*"
 */
class ConsistencyStressTest extends ClusterTestBase {

  private static final int REPEAT_COUNT = 1000;

  // -----------------------------------------------------------------------
  // Test 1 repeated: W=5 leader read should always return the new value.
  // -----------------------------------------------------------------------

  @RepeatedTest(REPEAT_COUNT)
  void test1_leaderReadAfterWrite_alwaysConsistent() {
    assertQuorumConfig(5, 1);

    String key = "stress-t1-" + UUID.randomUUID();

    rest.exchange(
        LEADER + "/leader/kv",
        HttpMethod.PUT,
        new HttpEntity<>(new KvWriteRequest(key, "hello")),
        KvWriteResponse.class
    );

    KvReadResponse readResp = rest.getForObject(
        LEADER + "/leader/kv?key=" + key, KvReadResponse.class);

    assertNotNull(readResp, "Leader should have the value after W=5 write");
    assertEquals("hello", readResp.getValue());
  }

  // -----------------------------------------------------------------------
  // Test 2 repeated: W=5 follower local_read should always return the new value.
  // -----------------------------------------------------------------------

  @RepeatedTest(REPEAT_COUNT)
  void test2_followerLocalReadAfterW5Write_alwaysConsistent() {
    assertQuorumConfig(5, 1);

    String key = "stress-t2-" + UUID.randomUUID();

    rest.exchange(
        LEADER + "/leader/kv",
        HttpMethod.PUT,
        new HttpEntity<>(new KvWriteRequest(key, "hello")),
        KvWriteResponse.class
    );

    for (String follower : FOLLOWERS) {
      KvReadResponse resp = rest.getForObject(
          follower + "/kv/local_read?key=" + key, KvReadResponse.class);

      assertNotNull(resp, follower + " should have the value after W=5 write");
      assertEquals("hello", resp.getValue());
    }
  }

  // -----------------------------------------------------------------------
  // Test 3 loop: W=1 should expose stale reads across many iterations.
  // Run this against a W=1 R=1 cluster.
  // -----------------------------------------------------------------------

  @Test
  void test3_staleReadsDetectedUnderW1() {
    assertQuorumConfig(1, 1);

    AtomicInteger staleCount   = new AtomicInteger(0);
    AtomicInteger totalReads   = new AtomicInteger(0);

    for (int i = 0; i < REPEAT_COUNT; i++) {
      String key   = "stress-t3-" + UUID.randomUUID();
      String value = "v-" + i;

      // Write to leader — W=1 returns immediately, async replication starts.
      rest.exchange(
          LEADER + "/leader/kv",
          HttpMethod.PUT,
          new HttpEntity<>(new KvWriteRequest(key, value)),
          KvWriteResponse.class
      );

      // Immediately local_read each follower — within the async replication window.
      for (String follower : FOLLOWERS) {
        totalReads.incrementAndGet();
        try {
          KvReadResponse resp = rest.getForObject(
              follower + "/kv/local_read?key=" + key, KvReadResponse.class);
          // If a response came back but the value is wrong, that's also stale.
          if (resp == null || !value.equals(resp.getValue())) {
            staleCount.incrementAndGet();
          }
        } catch (HttpClientErrorException e) {
          if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            // 404 means the follower hasn't received the write yet — stale.
            staleCount.incrementAndGet();
          }
        }
      }
    }

    System.out.printf(
        "%nTest 3 results: %d stale reads out of %d total follower reads (%.1f%%)%n",
        staleCount.get(), totalReads.get(),
        100.0 * staleCount.get() / totalReads.get()
    );

    assertTrue(staleCount.get() > 0,
        "Expected at least one stale read across " + REPEAT_COUNT +
        " iterations with W=1, but got none. " +
        "Is the cluster really configured with W=1?");
  }
}
