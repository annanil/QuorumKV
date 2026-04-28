variable "region" {
  description = "AWS region for the assignment infrastructure."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Name prefix applied to AWS resources."
  type        = string
  default     = "quorum-kv"
}

variable "vpc_id" {
  description = "Existing VPC to use. If null, the default VPC in the selected region is used."
  type        = string
  default     = null
}

variable "subnet_ids" {
  description = "Subnets for EC2 instances and the ALB. If empty, Terraform uses all default subnets in the selected VPC."
  type        = list(string)
  default     = []
}

variable "key_name" {
  description = "Optional EC2 key pair name for SSH access."
  type        = string
  default     = null
}

variable "allowed_cidr_blocks" {
  description = "CIDR blocks allowed to SSH to the EC2 instances."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "app_port" {
  description = "HTTP port exposed by the KV service."
  type        = number
  default     = 8080
}

variable "db_node_count" {
  description = "Number of database nodes required by the assignment."
  type        = number
  default     = 5

  validation {
    condition     = var.db_node_count == 5
    error_message = "This assignment infrastructure expects exactly 5 database nodes."
  }
}

variable "db_instance_type" {
  description = "Instance type for the KV service nodes."
  type        = string
  default     = "t3.micro"
}

variable "load_tester_instance_type" {
  description = "Instance type for the load tester box."
  type        = string
  default     = "t3.micro"
}

variable "enable_leaderless_alb" {
  description = "Whether to create an ALB targeting all database nodes for leaderless mode."
  type        = bool
  default     = true
}

variable "app_image" {
  description = "Container image to run on the KV nodes. Leave empty to provision hosts without starting the app."
  type        = string
  default     = ""
}

variable "write_quorum_size" {
  description = "Write quorum size passed to the KV service environment."
  type        = number
  default     = 1
}

variable "read_quorum_size" {
  description = "Read quorum size passed to the KV service environment."
  type        = number
  default     = 1
}

variable "peer_urls" {
  description = "Optional peer URL string for leaderless mode. Leave empty to configure later."
  type        = string
  default     = ""
}

variable "leaderless" {
  description = "Set to true to deploy all nodes in leaderless mode with static IPs and auto-wired peer URLs."
  type        = bool
  default     = false
}

variable "ami_id" {
  description = "Optional AMI override. Leave empty to use the latest Amazon Linux 2023 AMI."
  type        = string
  default     = ""
}

variable "tags" {
  description = "Extra tags applied to all resources."
  type        = map(string)
  default     = {}
}
