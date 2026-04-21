package edu.neu.cs6650.kv.dto;

public class ReplicationWriteRequest {
  private String key;
  private String value;
  private int version;

  public ReplicationWriteRequest() {
  }

  public ReplicationWriteRequest(String key, String value, int version) {
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

  public void setKey(String key) {
    this.key = key;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public void setVersion(int version) {
    this.version = version;
  }
}
