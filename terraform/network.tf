data "aws_vpc" "default" {
  count   = var.vpc_id == null ? 1 : 0
  default = true
}

data "aws_subnets" "selected" {
  count = length(var.subnet_ids) == 0 ? 1 : 0

  filter {
    name   = "vpc-id"
    values = [local.vpc_id]
  }
}

data "aws_ami" "amazon_linux" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
}

locals {
  vpc_id     = coalesce(var.vpc_id, one(data.aws_vpc.default[*].id))
  subnet_ids = length(var.subnet_ids) > 0 ? var.subnet_ids : sort(data.aws_subnets.selected[0].ids)
  ami_id     = var.ami_id != "" ? var.ami_id : data.aws_ami.amazon_linux.id

  base_tags = merge(
    {
      Project = var.project_name
    },
    var.tags
  )
}
