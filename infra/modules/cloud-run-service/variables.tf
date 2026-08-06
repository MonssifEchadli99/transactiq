variable "project_id" {
  description = "Google Cloud project that owns the Cloud Run service."
  type        = string
}

variable "region" {
  description = "Google Cloud region for the Cloud Run service."
  type        = string
}

variable "name" {
  description = "Cloud Run service name."
  type        = string
}

variable "image" {
  description = "Immutable container image reference."
  type        = string
}

variable "service_account_email" {
  description = "Dedicated runtime service account email."
  type        = string
}

variable "vpc_network" {
  description = "VPC network name used for direct VPC egress."
  type        = string
}

variable "vpc_subnetwork" {
  description = "VPC subnetwork name used for direct VPC egress."
  type        = string
}

variable "vpc_egress" {
  description = "Traffic sent through direct VPC egress."
  type        = string
  default     = "PRIVATE_RANGES_ONLY"

  validation {
    condition     = contains(["PRIVATE_RANGES_ONLY", "ALL_TRAFFIC"], var.vpc_egress)
    error_message = "vpc_egress must be PRIVATE_RANGES_ONLY or ALL_TRAFFIC."
  }
}

variable "ingress" {
  description = "Cloud Run ingress policy."
  type        = string
  default     = "INGRESS_TRAFFIC_ALL"
}

variable "invoker_iam_disabled" {
  description = "Disable the Cloud Run Invoker IAM check. Keep false unless another boundary restricts callers."
  type        = bool
  default     = false
}

variable "container_port" {
  description = "Container ingress port."
  type        = number
  default     = 8080
}

variable "http2" {
  description = "Expose the container port as h2c for end-to-end HTTP/2 or gRPC."
  type        = bool
  default     = false
}

variable "cpu" {
  description = "Container CPU limit."
  type        = string
  default     = "1"
}

variable "memory" {
  description = "Container memory limit."
  type        = string
  default     = "512Mi"
}

variable "cpu_idle" {
  description = "Allow request-based CPU allocation. Set false for background consumers."
  type        = bool
  default     = true
}

variable "min_instances" {
  description = "Minimum number of warm instances."
  type        = number
  default     = 0
}

variable "max_instances" {
  description = "Maximum number of instances."
  type        = number
  default     = 2
}

variable "max_instance_request_concurrency" {
  description = "Maximum concurrent requests per instance."
  type        = number
  default     = 40
}

variable "request_timeout" {
  description = "Maximum request duration."
  type        = string
  default     = "60s"
}

variable "environment_variables" {
  description = "Non-sensitive container environment variables."
  type        = map(string)
  default     = {}
}

variable "secret_environment_variables" {
  description = "Secret Manager references keyed by container environment variable."
  type = map(object({
    secret  = string
    version = optional(string, "latest")
  }))
  default = {}
}

variable "startup_probe_path" {
  description = "HTTP startup probe path. Set null to use a TCP probe."
  type        = string
  default     = "/actuator/health/readiness"
  nullable    = true
}

variable "startup_probe_port" {
  description = "Startup probe port."
  type        = number
  default     = 8080
}

variable "liveness_probe_path" {
  description = "HTTP liveness probe path. Set null to use a TCP probe."
  type        = string
  default     = "/actuator/health/liveness"
  nullable    = true
}

variable "liveness_probe_port" {
  description = "Liveness probe port."
  type        = number
  default     = 8080
}

variable "deletion_protection" {
  description = "Protect the service from accidental Terraform deletion."
  type        = bool
  default     = true
}

variable "labels" {
  description = "Resource labels."
  type        = map(string)
  default     = {}
}
