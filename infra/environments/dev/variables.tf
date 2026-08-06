variable "project_id" {
  description = "Google Cloud project ID. Supply it at plan time; no project is embedded in source."
  type        = string

  validation {
    condition     = length(trimspace(var.project_id)) > 0
    error_message = "project_id must not be empty."
  }
}

variable "region" {
  description = "Region for regional resources."
  type        = string
  default     = "europe-west1"
}

variable "artifact_registry_repository_id" {
  description = "Artifact Registry Docker repository ID."
  type        = string
  default     = "transactiq-services"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,62}$", var.artifact_registry_repository_id))
    error_message = "artifact_registry_repository_id must be a valid lowercase repository ID."
  }
}

variable "image_tag" {
  description = "Immutable 40-character Git commit SHA used for every service image."
  type        = string
  default     = "0000000000000000000000000000000000000000"

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be a lowercase 40-character Git commit SHA."
  }
}

variable "deploy_services" {
  description = "Create Cloud Run services only after images and required Secret Manager versions exist."
  type        = bool
  default     = false
}

variable "external_kafka_bootstrap_servers" {
  description = "Comma-separated external managed Kafka bootstrap servers. Credentials must not be included."
  type        = string
  default     = ""

  validation {
    condition = (
      var.external_kafka_bootstrap_servers == "" ||
      (!strcontains(var.external_kafka_bootstrap_servers, "@") && !strcontains(var.external_kafka_bootstrap_servers, "://"))
    )
    error_message = "external_kafka_bootstrap_servers must contain host:port entries without a URI scheme or credentials."
  }
}

variable "external_kafka_security_protocol" {
  description = "Kafka security protocol configured by the external managed Kafka provider."
  type        = string
  default     = "SASL_SSL"

  validation {
    condition     = contains(["SASL_SSL", "SSL"], var.external_kafka_security_protocol)
    error_message = "external_kafka_security_protocol must be SASL_SSL or SSL."
  }
}

variable "external_kafka_sasl_mechanism" {
  description = "Kafka SASL mechanism; ignored when the security protocol is SSL."
  type        = string
  default     = "PLAIN"

  validation {
    condition     = contains(["PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"], var.external_kafka_sasl_mechanism)
    error_message = "external_kafka_sasl_mechanism must be PLAIN, SCRAM-SHA-256, or SCRAM-SHA-512."
  }
}

variable "external_opensearch_url" {
  description = "HTTPS endpoint for externally managed OpenSearch. Do not embed credentials."
  type        = string
  default     = ""

  validation {
    condition = (
      var.external_opensearch_url == "" ||
      (startswith(var.external_opensearch_url, "https://") && !strcontains(var.external_opensearch_url, "@"))
    )
    error_message = "external_opensearch_url must be empty or use HTTPS without embedded credentials."
  }
}

variable "enable_alerts" {
  description = "Create baseline Cloud Run error and latency alert policies."
  type        = bool
  default     = true
}

variable "alert_notification_channel_ids" {
  description = "Existing Cloud Monitoring notification channel IDs. Empty keeps incidents visible in Monitoring only."
  type        = list(string)
  default     = []
}

variable "protect_stateful_resources" {
  description = "Enable deletion protection for Cloud SQL and Memorystore. Disable deliberately before dev teardown."
  type        = bool
  default     = true
}
