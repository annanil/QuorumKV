package edu.neu.cs6650.shardcontroller.service;

import edu.neu.cs6650.shardcontroller.config.ShardProperties;
import edu.neu.cs6650.shardcontroller.model.GroupConfig;
import edu.neu.cs6650.shardcontroller.model.ShardConfig;
import edu.neu.cs6650.shardcontroller.model.ShardDumpResponse;
import edu.neu.cs6650.shardcontroller.model.ShardImportRequest;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ShardConfigService {

  private static final Logger log = LoggerFactory.getLogger(ShardConfigService.class);

  private final ShardProperties props;
  private final RestTemplate restTemplate;
  private final AtomicReference<ShardConfig> currentConfig = new AtomicReference<>();

  public ShardConfigService(ShardProperties props, RestTemplate restTemplate) {
    this.props = props;
    this.restTemplate = restTemplate;
  }

  @PostConstruct
  public void init() {
    Map<Integer, Integer> assignments = new HashMap<>();
    for (int sid : parseIds(props.getGroup0InitialShardIds())) {
      assignments.put(sid, 0);
    }
    for (int sid : parseIds(props.getGroup1InitialShardIds())) {
      assignments.put(sid, 1);
    }

    Map<Integer, GroupConfig> groups = new HashMap<>();
    groups.put(0,
        new GroupConfig(0, props.getGroup0LeaderUrl(), parseUrls(props.getGroup0NodeUrls())));
    groups.put(1, new GroupConfig(1, props.getGroup1LeaderUrl(), parseUrls(props.getGroup1NodeUrls())));

    currentConfig.set(new ShardConfig(0, props.getNumShards(), assignments, groups));
    log.info("ShardController initialized: {} shards across {} groups", props.getNumShards(), groups.size());
  }

  public ShardConfig getConfig() {
    return currentConfig.get();
  }

  // Moves one shard from the most-loaded group to the least-loaded group,
  // mitigating its data via HTTP before updating the config version.
  public synchronized ShardConfig rebalance() {
    ShardConfig config = currentConfig.get();

    // Seed every configured group, including ones that currently own zero shards — otherwise a
    // group with no shards never appears as a rebalance candidate and can never receive one.
    Map<Integer, List<Integer>> groupToShards = new HashMap<>();
    for (Integer groupId : config.getGroups().keySet()) {
      groupToShards.put(groupId, new ArrayList<>());
    }
    for (Map.Entry<Integer, Integer> e : config.getGroupAssignments().entrySet()) {
      groupToShards.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
    }

    int donorId = groupToShards.entrySet().stream().max(Comparator.comparingInt(e -> e.getValue().size())).orElseThrow().getKey();
    int recipientId = groupToShards.entrySet().stream().min(Comparator.comparingInt(e -> e.getValue().size())).orElseThrow().getKey();

    if (donorId == recipientId || groupToShards.get(donorId).size() <= groupToShards.get(recipientId).size() + 1) {
      log.info(("Cluster is already balanced — no migration needed"));
      return config;
    }

    // Pick the highest shard ID from the donor so behavior is deterministic
    int shardToMigrate = groupToShards.get(donorId).stream().max(Integer::compare).orElseThrow();

    GroupConfig donor = config.getGroups().get(donorId);
    GroupConfig recipient = config.getGroups().get(recipientId);

    log.info("Migrating shard {} from group {} ({}) to group {} ({})", shardToMigrate, donorId, donor.getLeaderUrl(), recipientId, recipient.getLeaderUrl());

    try {
      migrateData(shardToMigrate, config.getNumShards(), donor.getLeaderUrl(), recipient.getLeaderUrl());
    } catch (Exception e) {
      log.error("Migration of shard {} failed — config unchanged: {}", shardToMigrate, e.getMessage());
      throw new IllegalStateException("Shard migration failed, cluster state unchanged", e);
    }

    Map<Integer, Integer> newAssignments = new HashMap<>(config.getGroupAssignments());
    newAssignments.put(shardToMigrate, recipientId);

    ShardConfig newConfig = new ShardConfig(config.getConfigVersion() + 1, config.getNumShards(), newAssignments, config.getGroups());
    currentConfig.set(newConfig);

    log.info("Rebalance complete — new configVersion={}", newConfig.getConfigVersion());
    return newConfig;
  }

  private void migrateData(int shardId, int numShards, String donorLeader, String recipientLeader) {
    String dumpUrl = donorLeader + "/kv/shard/" + shardId + "?numShards=" + numShards;
    ShardDumpResponse dump = fetchWithRetry(dumpUrl);

    if (dump == null || dump.getEntries() == null || dump.getEntries().isEmpty()) {
      log.info("Shard {} has no entries to migrate", shardId);
      return;
    }

    log.info("Migrating {} entries for shard {}", dump.getEntries().size(), shardId);
    String importUrl = recipientLeader + "/kv/shard/" + shardId + "/import";

    int attempts = 0;
    while (true) {
      try {
        restTemplate.postForObject(importUrl, new ShardImportRequest(dump.getEntries()), void.class);
        return;
      } catch (Exception e) {
        if (++attempts >= 3) throw e;
        log.warn("Import attempt {} for shard {} failed, retrying: {}", attempts, shardId, e.getMessage());
      }
    }
  }

  private ShardDumpResponse fetchWithRetry(String url) {
    int attempts = 0;
    while (true) {
      try {
        return restTemplate.getForObject(url, ShardDumpResponse.class);
      } catch (Exception e) {
        if (++attempts >= 3) throw e;
        log.warn("Dump fetch attempt {} failed, retrying: {}", attempts, e.getMessage());
      }
    }
  }

  private List<Integer> parseIds(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
        .map(Integer::parseInt).collect(Collectors.toList());
  }

  private List<String> parseUrls(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }
}
