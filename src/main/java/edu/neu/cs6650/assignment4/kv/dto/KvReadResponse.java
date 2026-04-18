package edu.neu.cs6650.assignment4.kv.dto;

public class KvReadResponse {
  private String key;
  private String value;
  private int version;

  public KvReadResponse(String key, String value, int version) {
    this.key = key;
    this.value = value;
    this.version = version;
  }

  public String getKey() {
    return key;
  }

  public String getValue() {
    return value;
  }

  public int getVersion() {
    return version;
  }
}
