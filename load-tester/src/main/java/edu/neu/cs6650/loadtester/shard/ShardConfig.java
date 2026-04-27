package edu.neu.cs6650.loadtester.shard;

import java.util.Map;
import lombok.Data;

@Data
public class ShardConfig {

  private int configVersion;
  private int numShards;
  // shardId (as String key in JSON) → groupId
  private Map<String, Integer> groupAssignments;
  // groupId (as String key in JSON) → GroupConfig
  private Map<String, GroupConfig> groups;

  // Returns the leader URL for the group that owns the given key.
  public String getLeaderUrlForKey(String key) {
    int shardId = (key.hashCode() & Integer.MAX_VALUE) % numShards;
    Integer groupId = groupAssignments.get(String.valueOf(shardId));
    if (groupId == null) { return null; }
    GroupConfig group = groups.get(String.valueOf(groupId));
    return group == null ? null : group.getLeaderUrl();
  }

  public int getShardId(String key) {
    return (key.hashCode() & Integer.MAX_VALUE) % numShards;
  }
}
