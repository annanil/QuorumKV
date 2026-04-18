data "aws_subnet" "node" {
  count = var.db_node_count
  id    = local.subnet_ids[count.index % length(local.subnet_ids)]
}

locals {
  leader_name   = "${var.project_name}-leader"
  follower_name = "${var.project_name}-follower"

  # Fixed private IPs — use host .100 in each node's subnet.
  # Stable across redeploys; no two-phase IP handoff needed.
  node_private_ips = [for s in data.aws_subnet.node : cidrhost(s.cidr_block, 100)]

  # Peer URL list for leaderless mode (all 5 nodes, self-filtered at boot via NODE_SELF_URL).
  all_peer_urls = join(",", [
    for ip in local.node_private_ips : "http://${ip}:${var.app_port}"
  ])

  leader_follower_urls = [
    for follower in aws_instance.followers :
    "http://${follower.private_ip}:${var.app_port}"
  ]
}

resource "aws_instance" "leader" {
  ami                         = local.ami_id
  instance_type               = var.db_instance_type
  subnet_id                   = local.subnet_ids[0]
  private_ip                  = local.node_private_ips[0]
  vpc_security_group_ids      = [aws_security_group.app_nodes.id]
  key_name                    = var.key_name
  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/templates/app_node.sh.tftpl", {
    app_image         = var.app_image
    app_port          = var.app_port
    node_role         = var.leaderless ? "leaderless" : "leader"
    follower_urls     = var.leaderless ? "" : join(",", local.leader_follower_urls)
    peer_urls         = var.leaderless ? local.all_peer_urls : ""
    write_quorum_size = var.write_quorum_size
    read_quorum_size  = var.read_quorum_size
  })

  tags = merge(local.base_tags, {
    Name = local.leader_name
    Role = var.leaderless ? "leaderless" : "leader"
  })
}

resource "aws_instance" "followers" {
  count                       = var.db_node_count - 1
  ami                         = local.ami_id
  instance_type               = var.db_instance_type
  subnet_id                   = local.subnet_ids[(count.index + 1) % length(local.subnet_ids)]
  private_ip                  = local.node_private_ips[count.index + 1]
  vpc_security_group_ids      = [aws_security_group.app_nodes.id]
  key_name                    = var.key_name
  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/templates/app_node.sh.tftpl", {
    app_image         = var.app_image
    app_port          = var.app_port
    node_role         = var.leaderless ? "leaderless" : "follower"
    follower_urls     = ""
    peer_urls         = var.leaderless ? local.all_peer_urls : ""
    write_quorum_size = var.write_quorum_size
    read_quorum_size  = var.read_quorum_size
  })

  tags = merge(local.base_tags, {
    Name = "${local.follower_name}-${count.index + 1}"
    Role = var.leaderless ? "leaderless" : "follower"
  })
}

resource "aws_instance" "load_tester" {
  ami                         = local.ami_id
  instance_type               = var.load_tester_instance_type
  subnet_id                   = local.subnet_ids[0]
  vpc_security_group_ids      = [aws_security_group.load_tester.id]
  key_name                    = var.key_name
  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/templates/load_tester.sh.tftpl", {
    project_name = var.project_name
  })

  tags = merge(local.base_tags, {
    Name = "${var.project_name}-load-tester"
    Role = "load-tester"
  })
}
