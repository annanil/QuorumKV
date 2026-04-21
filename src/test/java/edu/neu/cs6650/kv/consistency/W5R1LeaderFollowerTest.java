package edu.neu.cs6650.kv.consistency;

import edu.neu.cs6650.kv.dto.KvReadResponse;
import edu.neu.cs6650.kv.dto.KvWriteRequest;
import edu.neu.cs6650.kv.dto.KvWriteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests 1 & 2: Strong consistency with W=5.
 *
 * W=5 means the leader waits for all 4 follower ACKs before returning,
 * so every node is guaranteed to have the data by the time the client gets a response.
 *
 * Start cluster with: WRITE_QUORUM=5 READ_QUORUM=1 docker compose up --build
 * Run with:           ./mvnw test -Dtest=W5R1LeaderFollowerTest
 */
class W5R1LeaderFollowerTest extends ClusterTestBase {

  /**
   * Test 1: Basic smoke test — after the leader ACKs a write, reading from
   * the same leader should return the new value.
   */
  @Test
  void test1_leaderReadAfterWrite() {
    assertQuorumConfig(5, 1);

    // Send PUT to the leader and wait for acknowledgment.
    rest.exchange(
        LEADER + "/leader/kv",
        HttpMethod.PUT,
        new HttpEntity<>(new KvWriteRequest(key, "hello")),
        KvWriteResponse.class
    );

    // Read from the same leader — should return the new value.
    KvReadResponse readResp = rest.getForObject(
        LEADER + "/leader/kv?key=" + key, KvReadResponse.class);

    assertNotNull(readResp);
    assertEquals("hello", readResp.getValue());
  }

  /**
   * Test 2: W=5 guarantees all followers are updated before the leader ACKs.
   * Reading via local_read on any follower should immediately return the new value.
   */
  @Test
  void test2_followerLocalReadAfterW5Write() {
    assertQuorumConfig(5, 1);

    // Send PUT to the leader and wait for acknowledgment.
    // W=5: the leader will not respond until all 4 followers have ACKed.
    rest.exchange(
        LEADER + "/leader/kv",
        HttpMethod.PUT,
        new HttpEntity<>(new KvWriteRequest(key, "hello")),
        KvWriteResponse.class
    );

    // Read from every follower via local_read — all should have the new value.
    for (String follower : FOLLOWERS) {
      ResponseEntity<KvReadResponse> resp = rest.getForEntity(
          follower + "/kv/local_read?key=" + key, KvReadResponse.class);

      assertEquals(HttpStatus.OK, resp.getStatusCode(),
          follower + " should have the key after W=5 write");
      assertEquals("hello", resp.getBody().getValue(),
          follower + " should return the new value after W=5 write");
    }
  }
}
