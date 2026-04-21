package edu.neu.cs6650.shardcontroller.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShardDumpResponse {

  private int shardId;
  private List<ShardEntry> entries;
}
