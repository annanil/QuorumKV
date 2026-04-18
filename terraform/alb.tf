resource "aws_lb" "leaderless" {
  count              = var.enable_leaderless_alb ? 1 : 0
  name               = replace(substr("${var.project_name}-alb", 0, 32), "_", "-")
  load_balancer_type = "application"
  subnets            = local.subnet_ids
  security_groups    = [aws_security_group.alb[0].id]

  tags = merge(local.base_tags, { Name = "${var.project_name}-alb" })
}

resource "aws_lb_target_group" "leaderless" {
  count    = var.enable_leaderless_alb ? 1 : 0
  name     = replace(substr("${var.project_name}-tg", 0, 32), "_", "-")
  port     = var.app_port
  protocol = "HTTP"
  vpc_id   = local.vpc_id

  health_check {
    enabled             = true
    path                = "/actuator/health"
    matcher             = "200"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    timeout             = 5
  }

  tags = merge(local.base_tags, { Name = "${var.project_name}-tg" })
}

resource "aws_lb_listener" "leaderless" {
  count             = var.enable_leaderless_alb ? 1 : 0
  load_balancer_arn = aws_lb.leaderless[0].arn
  port              = var.app_port
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.leaderless[0].arn
  }
}

resource "aws_lb_target_group_attachment" "leader" {
  count            = var.enable_leaderless_alb ? 1 : 0
  target_group_arn = aws_lb_target_group.leaderless[0].arn
  target_id        = aws_instance.leader.id
  port             = var.app_port
}

resource "aws_lb_target_group_attachment" "followers" {
  count            = var.enable_leaderless_alb ? length(aws_instance.followers) : 0
  target_group_arn = aws_lb_target_group.leaderless[0].arn
  target_id        = aws_instance.followers[count.index].id
  port             = var.app_port
}
