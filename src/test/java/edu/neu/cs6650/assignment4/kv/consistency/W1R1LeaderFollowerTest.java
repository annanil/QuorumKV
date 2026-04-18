package edu.neu.cs6650.assignment4.kv.consistency;

import edu.neu.cs6650.assignment4.kv.dto.KvWriteRequest;
import edu.neu.cs6650.assignment4.kv.dto.KvWriteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test 3: Exposing inconsistency with W=1.
 *
 * W=1 means only the leader is updated before responding to the client.
 * Followers are not replicated to at all, so an immediate local_read on any
 * follower should return stale data or 404 (for a first write to that key).
 *
 * Start cluster with: WRITE_QUORUM=1 READ_QUORUM=1 docker compose up --build
 * Run with:           ./mvnw test -Dtest=W1R1LeaderFollowerTest
 */
class W1R1LeaderFollowerTest extends ClusterTestBase {

  /**
   * Test 3: With W=1 the leader returns immediately after writing locally.
   * Followers are never notified, so an immediate local_read should expose
   * the inconsistency — followers return 404 for a key they have never seen.
   */
  @Test
  void test3_followersAreStaleImmediatelyAfterW1Write() {
    assertQuorumConfig(1, 1);

    // Send PUT to the leader. W=1: returns as soon as the leader writes locally.
    rest.exchange(
        LEADER + "/leader/kv",
        HttpMethod.PUT,
        new HttpEntity<>(new KvWriteRequest(key, "hello")),
        KvWriteResponse.class
    );

    // Immediately send local_read to each follower (within 5 × 200ms = 1s window).
    // With W=1 no replication happens, so all followers should be stale (404).
    int staleCount = 0;
    for (String follower : FOLLOWERS) {
      try {
        rest.getForObject(follower + "/kv/local_read?key=" + key, Object.class);
        // If we get here the follower somehow has the value — still acceptable
        // but unexpected with W=1 and no async replication.
      } catch (HttpClientErrorException e) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
          staleCount++;
        }
      }
    }

    assertTrue(staleCount > 0,
        "At least one follower should be stale (404) immediately after a W=1 write. " +
        "Stale followers: " + staleCount + "/" + FOLLOWERS.size());
  }
}
