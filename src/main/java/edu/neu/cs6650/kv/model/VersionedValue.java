package edu.neu.cs6650.kv.model;

public class VersionedValue {
  private final String key;
  private final String value;
  private final int version;

  public VersionedValue(String key, String value, int version) {
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
