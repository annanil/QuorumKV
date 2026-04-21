package edu.neu.cs6650.kv.controller;

import edu.neu.cs6650.kv.config.NodeProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/node/info")
public class NodeInfoController {

  private final NodeProperties nodeProperties;

  public NodeInfoController(NodeProperties nodeProperties) {
    this.nodeProperties = nodeProperties;
  }

  // Returns this node's role and quorum configuration.
  // Used by integration tests to verify the cluster is started with the correct settings.
  @GetMapping
  public Map<String, Object> info() {
    return Map.of(
        "role", nodeProperties.getRole(),
        "writeQuorumSize", nodeProperties.getWriteQuorumSize(),
        "readQuorumSize", nodeProperties.getReadQuorumSize()
    );
  }
}
