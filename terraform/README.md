# Terraform Notes

This Terraform directory provisions the assignment baseline:

- 1 leader EC2 instance
- 4 follower EC2 instances
- 1 load tester EC2 instance
- security groups for node-to-node traffic, load testing, SSH, and the ALB
- optional ALB targeting all 5 database nodes for leaderless mode

## Before apply

1. Build and push the Docker image for this project to Docker Hub or ECR.
2. Copy `terraform.tfvars.example` to `terraform.tfvars`.
3. Update `app_image` and optionally `key_name`.

## Typical workflow

```bash
cd terraform
terraform init
terraform plan
terraform apply
```

## Important note

If `app_image` is empty, Terraform still provisions the infrastructure and installs Docker, but it does not start the KV container. That keeps the infra reusable for your teammates even if the image is not published yet.
