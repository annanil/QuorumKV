resource "aws_security_group" "app_nodes" {
  name        = "${var.project_name}-app-nodes"
  description = "HTTP access for KV nodes from peers, the load tester, and optionally the ALB."
  vpc_id      = local.vpc_id

  ingress {
    description = "Node-to-node replication traffic"
    from_port   = var.app_port
    to_port     = var.app_port
    protocol    = "tcp"
    self        = true
  }

  ingress {
    description     = "Load tester to app nodes"
    from_port       = var.app_port
    to_port         = var.app_port
    protocol        = "tcp"
    security_groups = [aws_security_group.load_tester.id]
  }

  dynamic "ingress" {
    for_each = var.enable_leaderless_alb ? [aws_security_group.alb[0].id] : []

    content {
      description     = "ALB to app nodes"
      from_port       = var.app_port
      to_port         = var.app_port
      protocol        = "tcp"
      security_groups = [ingress.value]
    }
  }

  ingress {
    description = "SSH access"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidr_blocks
  }

  ingress {
    description = "Direct HTTP access for testing"
    from_port   = var.app_port
    to_port     = var.app_port
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidr_blocks
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.base_tags, { Name = "${var.project_name}-app-nodes" })
}

resource "aws_security_group" "load_tester" {
  name        = "${var.project_name}-load-tester"
  description = "SSH access plus outbound traffic for the load test host."
  vpc_id      = local.vpc_id

  ingress {
    description = "SSH access"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidr_blocks
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.base_tags, { Name = "${var.project_name}-load-tester" })
}

resource "aws_security_group" "alb" {
  count       = var.enable_leaderless_alb ? 1 : 0
  name        = "${var.project_name}-alb"
  description = "Public HTTP access for the leaderless ALB."
  vpc_id      = local.vpc_id

  ingress {
    description = "HTTP from the internet"
    from_port   = var.app_port
    to_port     = var.app_port
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.base_tags, { Name = "${var.project_name}-alb" })
}
