package edu.neu.cs6650.shardcontroller.model;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShardConfig {

  private int configVersion;
  private int numShards;
  // Maps shardId → groupId
  private Map<Integer, Integer> groupAssignments;
  // Maps groupId → groupConfig
  private Map<Integer, GroupConfig> groups;
}
