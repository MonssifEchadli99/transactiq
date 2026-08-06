locals {
  environment = "dev"
  name_prefix = "transactiq-${local.environment}"

  labels = {
    application = "transactiq"
    environment = local.environment
    managed_by  = "terraform"
  }

  services = toset([
    "authorization-service",
    "fraud-engine",
    "case-management-service",
    "case-search-service",
    "investigation-assistant-service",
  ])

  service_account_ids = {
    authorization-service           = "tiq-dev-authorization"
    fraud-engine                    = "tiq-dev-fraud-engine"
    case-management-service         = "tiq-dev-case-mgmt"
    case-search-service             = "tiq-dev-case-search"
    investigation-assistant-service = "tiq-dev-investigation"
  }

  image_uris = {
    for service in local.services : service =>
    "${var.region}-docker.pkg.dev/${var.project_id}/${var.artifact_registry_repository_id}/${service}:${var.image_tag}"
  }

  secret_ids = {
    authorization_database_password = "${local.name_prefix}-authorization-db-password"
    case_database_password          = "${local.name_prefix}-case-db-password"
    kafka_sasl_jaas_config          = "${local.name_prefix}-kafka-sasl-jaas-config"
    openai_api_key                  = "${local.name_prefix}-openai-api-key"
  }

  kafka_environment = {
    TRANSACTIQ_KAFKA_BOOTSTRAP_SERVERS        = var.external_kafka_bootstrap_servers
    SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL = var.external_kafka_security_protocol
    SPRING_KAFKA_PROPERTIES_SASL_MECHANISM    = var.external_kafka_sasl_mechanism
    TRANSACTIQ_ENVIRONMENT                    = local.environment
  }

  kafka_secret_environment = var.external_kafka_security_protocol == "SASL_SSL" ? {
    SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG = {
      secret = google_secret_manager_secret.configuration["kafka_sasl_jaas_config"].secret_id
    }
  } : {}
}

check "service_deployment_prerequisites" {
  assert {
    condition     = !var.deploy_services || length(trimspace(var.external_kafka_bootstrap_servers)) > 0
    error_message = "external_kafka_bootstrap_servers is required when deploy_services is true."
  }

  assert {
    condition     = !var.deploy_services || length(trimspace(var.external_opensearch_url)) > 0
    error_message = "external_opensearch_url is required when deploy_services is true."
  }

  assert {
    condition     = !var.deploy_services || var.image_tag != "0000000000000000000000000000000000000000"
    error_message = "A real Git commit SHA image_tag is required when deploy_services is true."
  }
}
