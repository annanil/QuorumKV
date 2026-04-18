package edu.neu.cs6650.loadtester;

public class LatencyRecord {

  public static final String CSV_HEADER = "start_time,request_type,latency,response_code,key,version,stale";

  private final long startTime;
  private final String requestType;
  private final long latency;
  private final int responseCode;
  private final String key;
  private final int version;
  private final boolean stale;

  public LatencyRecord(long startTime, String requestType, long latency, int responseCode,
      String key, int version, boolean stale) {
    this.startTime = startTime;
    this.requestType = requestType;
    this.latency = latency;
    this.responseCode = responseCode;
    this.key = key;
    this.version = version;
    this.stale = stale;
  }

  public String toCSV() {
    return startTime + "," + requestType + "," + latency + "," + responseCode + "," + key + ","
        + version + "," + stale;
  }

  public long getLatency() {
    return latency;
  }

  public String getRequestType() {
    return requestType;
  }
}
