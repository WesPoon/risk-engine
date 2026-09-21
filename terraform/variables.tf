variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "ap-southeast-2"
}

variable "project_name" {
  description = "Name prefix used for tagging and resource naming"
  type        = string
  default     = "risk-engine"
}

variable "environment" {
  description = "Deployment environment name (e.g. staging, production)"
  type        = string
  default     = "production"
}

# ---------------------------------------------------------------- #
#  Networking
# ---------------------------------------------------------------- #

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones to spread subnets across"
  type        = number
  default     = 2
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets (ALB, NAT gateway)"
  type        = list(string)
  default     = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets (ECS tasks, RDS)"
  type        = list(string)
  default     = ["10.0.10.0/24", "10.0.11.0/24"]
}

# ---------------------------------------------------------------- #
#  ECS / container
# ---------------------------------------------------------------- #

variable "container_port" {
  description = "Port the risk-engine container listens on"
  type        = number
  default     = 8080
}

variable "task_cpu" {
  description = "Fargate task CPU units"
  type        = string
  default     = "512"
}

variable "task_memory" {
  description = "Fargate task memory (MiB)"
  type        = string
  default     = "1024"
}

variable "desired_count" {
  description = "Number of ECS tasks to run"
  type        = number
  default     = 2
}

variable "image_tag" {
  description = "Docker image tag to deploy (overridden per-deploy by CI with the git SHA)"
  type        = string
  default     = "latest"
}

# ---------------------------------------------------------------- #
#  RDS
# ---------------------------------------------------------------- #

variable "db_name" {
  description = "PostgreSQL database name"
  type        = string
  default     = "riskengine"
}

variable "db_username" {
  description = "Master username for RDS PostgreSQL"
  type        = string
  default     = "riskengine_app"
}

variable "db_instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage" {
  description = "RDS allocated storage in GB"
  type        = number
  default     = 20
}

variable "db_multi_az" {
  description = "Enable Multi-AZ for RDS high availability"
  type        = bool
  default     = true
}

variable "db_backup_retention_days" {
  description = "RDS automated backup retention period in days"
  type        = number
  default     = 7
}

variable "db_deletion_protection" {
  description = "Enable RDS deletion protection"
  type        = bool
  default     = true
}

# ---------------------------------------------------------------- #
#  ALB / TLS
# ---------------------------------------------------------------- #

variable "certificate_arn" {
  description = "ACM certificate ARN for the HTTPS listener. Leave empty to serve plain HTTP on port 80 until a cert is issued."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------- #
#  Secrets (sensitive — pass via terraform.tfvars, -var, or CI secret injection; never commit real values)
# ---------------------------------------------------------------- #

variable "smtp_password" {
  description = "SMTP password for alert emails, stored in Secrets Manager"
  type        = string
  sensitive   = true
  default     = ""
}

variable "symphony_api_key" {
  description = "Symphony API key, stored in Secrets Manager"
  type        = string
  sensitive   = true
  default     = ""
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention period for the ECS log group"
  type        = number
  default     = 30
}
