package edu.neu.cs6650.kv.controller;

import edu.neu.cs6650.kv.dto.KvReadResponse;
import edu.neu.cs6650.kv.dto.KvWriteRequest;
import edu.neu.cs6650.kv.dto.KvWriteResponse;
import edu.neu.cs6650.kv.model.VersionedValue;
import edu.neu.cs6650.kv.service.KvService;
import edu.neu.cs6650.kv.dto.ReplicationWriteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kv")
public class KvController {

  private final KvService kvService;

  public KvController(KvService kvService) {
    this.kvService = kvService;
  }

  // Handles writes to the in-memory KV store.
  @PutMapping
  public ResponseEntity<KvWriteResponse> put(@RequestBody KvWriteRequest request) {
    if (request.getKey() == null || request.getKey().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }

    VersionedValue storedValue = kvService.put(request.getKey(), request.getValue());
    KvWriteResponse response = new KvWriteResponse(
        storedValue.getKey(),
        storedValue.getVersion()
    );

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  // Reads the current value for a given key from this node.
  @GetMapping
  public ResponseEntity<KvReadResponse> get(@RequestParam String key) {
    VersionedValue storedValue = kvService.get(key);

    if (storedValue == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    KvReadResponse response = new KvReadResponse(
        storedValue.getKey(),
        storedValue.getValue(),
        storedValue.getVersion()
    );

    return ResponseEntity.ok(response);
  }

  // Returns the value stored on this node only, without any distributed read logic.
  @GetMapping("/local_read")
  public ResponseEntity<KvReadResponse> localRead(@RequestParam String key) {
    VersionedValue storedValue = kvService.get(key);

    if (storedValue == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    KvReadResponse response = new KvReadResponse(
        storedValue.getKey(),
        storedValue.getValue(),
        storedValue.getVersion()
    );

    return ResponseEntity.ok(response);
  }

  // Internal endpoint used by the leader to push a write to this node with a fixed version.
  @PutMapping("/internal/write")
  public ResponseEntity<KvWriteResponse> replicate(@RequestBody ReplicationWriteRequest request) {
    if (request.getKey() == null || request.getKey().isEmpty()) {
      return ResponseEntity.badRequest().build();
    }

    VersionedValue storedValue = kvService.applyReplicatedWrite(
        request.getKey(),
        request.getValue(),
        request.getVersion()
    );

    KvWriteResponse response = new KvWriteResponse(
        storedValue.getKey(),
        storedValue.getVersion()
    );

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }
}
