package edu.neu.cs6650.loadtester;

import lombok.Data;

@Data
public class LatencyRecord {

  public static final String CSV_HEADER = "start_time,request_type,latency,response_code,key,version,stale,shard_id";

  private final long startTime;
  private final String requestType;
  private final long latency;
  private final int responseCode;
  private final String key;
  private final int version;
  private final boolean stale;
  private final int shardId;

  public String toCSV() {
    return startTime + "," + requestType + "," + latency + "," + responseCode + "," + key + ","
        + version + "," + stale + "," + shardId;
  }
}
