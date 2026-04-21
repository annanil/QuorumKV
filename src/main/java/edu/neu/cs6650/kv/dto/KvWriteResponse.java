package edu.neu.cs6650.kv.dto;

public class KvWriteResponse {
  private String key;
  private int version;

  public KvWriteResponse(String key, int version) {
    this.key = key;
    this.version = version;
  }

  public String getKey() {
    return key;
  }

  public int getVersion() {
    return version;
  }
}
