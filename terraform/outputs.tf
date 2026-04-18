locals {
  all_db_instances     = concat([aws_instance.leader], aws_instance.followers)
  all_private_node_ips = [for instance in local.all_db_instances : instance.private_ip]
  all_public_node_ips  = [for instance in local.all_db_instances : instance.public_ip]
  all_private_node_urls = [
    for ip in local.all_private_node_ips : "http://${ip}:${var.app_port}"
  ]
}

output "leader_public_ip" {
  value       = aws_instance.leader.public_ip
  description = "Public IP of the leader node."
}

output "leader_private_ip" {
  value       = aws_instance.leader.private_ip
  description = "Private IP of the leader node."
}

output "follower_public_ips" {
  value       = [for follower in aws_instance.followers : follower.public_ip]
  description = "Public IPs of the follower nodes."
}

output "follower_private_ips" {
  value       = [for follower in aws_instance.followers : follower.private_ip]
  description = "Private IPs of the follower nodes."
}

output "all_node_private_urls" {
  value       = local.all_private_node_urls
  description = "Private HTTP URLs for all database nodes."
}

output "leader_follower_urls" {
  value       = local.leader_follower_urls
  description = "Follower URLs suitable for node.follower-urls on the leader."
}

output "leaderless_alb_dns_name" {
  value       = var.enable_leaderless_alb ? aws_lb.leaderless[0].dns_name : null
  description = "DNS name of the ALB used for leaderless mode."
}

output "load_tester_public_ip" {
  value       = aws_instance.load_tester.public_ip
  description = "Public IP of the load tester instance."
}

output "suggested_leader_env" {
  value = {
    NODE_ROLE              = "leader"
    NODE_FOLLOWER_URLS     = join(",", local.leader_follower_urls)
    NODE_WRITE_QUORUM_SIZE = tostring(var.write_quorum_size)
    NODE_READ_QUORUM_SIZE  = tostring(var.read_quorum_size)
  }
  description = "Environment values for leader-follower mode."
}
