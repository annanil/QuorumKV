package edu.neu.cs6650.shardcontroller.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShardEntry {

  private String key;
  private String value;
  private int version;
}
