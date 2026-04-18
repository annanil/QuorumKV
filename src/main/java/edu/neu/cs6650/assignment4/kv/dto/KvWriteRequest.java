package edu.neu.cs6650.assignment4.kv.dto;

public class KvWriteRequest {
  private String key;
  private String value;

  public KvWriteRequest() {
  }

  public KvWriteRequest(String key, String value) {
    this.key = key;
    this.value = value;
  }

  public String getKey() {
    return key;
  }

  public String getValue() {
    return value;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public void setValue(String value) {
    this.value = value;
  }
}
