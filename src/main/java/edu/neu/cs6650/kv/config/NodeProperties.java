package edu.neu.cs6650.kv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "node")
public class NodeProperties {

  private String role = "single";
  private String followerUrls = "";
  private String peerUrls = "";
  private String selfUrl = "";
  private int writeQuorumSize = 1;
  private int readQuorumSize = 1;

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getFollowerUrls() {
    return followerUrls;
  }

  public void setFollowerUrls(String followerUrls) {
    this.followerUrls = followerUrls;
  }

  public String getPeerUrls() {
    return peerUrls;
  }

  public void setPeerUrls(String peerUrls) {
    this.peerUrls = peerUrls;
  }

  public String getSelfUrl() {
    return selfUrl;
  }

  public void setSelfUrl(String selfUrl) {
    this.selfUrl = selfUrl;
  }

  public int getWriteQuorumSize() {
    return writeQuorumSize;
  }

  public void setWriteQuorumSize(int writeQuorumSize) {
    this.writeQuorumSize = writeQuorumSize;
  }

  public int getReadQuorumSize() {
    return readQuorumSize;
  }

  public void setReadQuorumSize(int readQuorumSize) {
    this.readQuorumSize = readQuorumSize;
  }
}
