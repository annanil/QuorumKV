package edu.neu.cs6650.shardcontroller.controller;

import edu.neu.cs6650.shardcontroller.model.ShardConfig;
import edu.neu.cs6650.shardcontroller.service.ShardConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config")
public class ShardConfigController {

  private final ShardConfigService service;

  public ShardConfigController(ShardConfigService service) {
    this.service = service;
  }

  // Returns the current shard configuration (configVersion, numShards, groupAssignments, groups).
  // Clients poll this to learn which group owns which key range.
  @GetMapping
  public ResponseEntity<ShardConfig> getConfig() {
    return ResponseEntity.ok(service.getConfig());
  }

  // Triggers a rebalance: moves one shard from the most-loaded group to the least-loaded group,
  // migrates its data via HTTP, then bumps configVersion.
  @PutMapping("/rebalance")
  public ResponseEntity<ShardConfig> rebalance() {
    return ResponseEntity.ok(service.rebalance());
  }
}
