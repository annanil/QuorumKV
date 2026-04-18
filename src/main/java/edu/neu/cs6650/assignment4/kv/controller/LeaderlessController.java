package edu.neu.cs6650.assignment4.kv.controller;

import edu.neu.cs6650.assignment4.kv.config.NodeProperties;
import edu.neu.cs6650.assignment4.kv.dto.KvReadResponse;
import edu.neu.cs6650.assignment4.kv.dto.KvWriteRequest;
import edu.neu.cs6650.assignment4.kv.dto.KvWriteResponse;
import edu.neu.cs6650.assignment4.kv.model.VersionedValue;
import edu.neu.cs6650.assignment4.kv.service.KvService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/leaderless/kv")
public class LeaderlessController {

  private final KvService kvService;
  private final NodeProperties nodeProperties;

  public LeaderlessController(KvService kvService, NodeProperties nodeProperties) {
    this.kvService = kvService;
    this.nodeProperties = nodeProperties;
  }

  @PutMapping
  public ResponseEntity<KvWriteResponse> write(@RequestBody KvWriteRequest request) {
    if (request.getKey() == null || request.getKey().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }

    try {
      VersionedValue stored = kvService.leaderlessWrite(request.getKey(), request.getValue(), nodeProperties.getWriteQuorumSize());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(new KvWriteResponse(stored.getKey(), stored.getVersion()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
  }

  @GetMapping
  public ResponseEntity<KvReadResponse> read(@RequestParam String key) {
    try {
      VersionedValue stored = kvService.leaderlessRead(key, nodeProperties.getReadQuorumSize());
      if (stored == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }
      return ResponseEntity.ok(
          new KvReadResponse(stored.getKey(), stored.getValue(), stored.getVersion()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
  }
}
