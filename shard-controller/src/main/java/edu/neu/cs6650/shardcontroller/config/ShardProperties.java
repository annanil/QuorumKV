package edu.neu.cs6650.shardcontroller.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "shard")
public class ShardProperties {

  private int numShards = 4;

  private String group0LeaderUrl = "http://localhost:8080";
  private String group0NodeUrls = "http://localhost:8080";
  private String group0InitialShardIds = "0,1";

  private String group1LeaderUrl = "http://localhost:8083";
  private String group1NodeUrls = "http://localhost:8083";
  private String group1InitialShardIds = "2,3";
}
