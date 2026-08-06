module "fraud_engine" {
  count  = var.deploy_services ? 1 : 0
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "${local.name_prefix}-fraud-engine"
  image                 = local.image_uris["fraud-engine"]
  service_account_email = google_service_account.runtime["fraud-engine"].email
  vpc_network           = google_compute_network.transactiq.name
  vpc_subnetwork        = google_compute_subnetwork.cloud_run.name

  # The current raw gRPC client does not attach a Cloud Run identity token.
  # This service therefore disables the invoker check only behind internal
  # ingress; all public-facing services retain the IAM check.
  ingress              = "INGRESS_TRAFFIC_INTERNAL_ONLY"
  invoker_iam_disabled = true
  http2                = true
  container_port       = 8080
  cpu_idle             = true
  min_instances        = 0
  max_instances        = 2

  environment_variables = {
    FRAUD_ENGINE_GRPC_PORT       = "8080"
    FRAUD_ENGINE_MANAGEMENT_PORT = "8081"
    SPRING_DATA_REDIS_HOST       = google_redis_instance.fraud_velocity.host
    TRANSACTIQ_REDIS_PORT        = tostring(google_redis_instance.fraud_velocity.port)
    TRANSACTIQ_ENVIRONMENT       = local.environment
  }

  # Cloud Run can probe only the declared ingress port. The fraud engine
  # therefore uses TCP probes on its h2c/gRPC port; Actuator remains available
  # inside the container on the separate management port for diagnostics.
  startup_probe_path  = null
  startup_probe_port  = 8080
  liveness_probe_path = null
  liveness_probe_port = 8080
  labels              = local.labels

  depends_on = [google_artifact_registry_repository.services]
}

module "authorization" {
  count  = var.deploy_services ? 1 : 0
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "${local.name_prefix}-authorization"
  image                 = local.image_uris["authorization-service"]
  service_account_email = google_service_account.runtime["authorization-service"].email
  vpc_network           = google_compute_network.transactiq.name
  vpc_subnetwork        = google_compute_subnetwork.cloud_run.name
  vpc_egress            = "ALL_TRAFFIC"
  cpu_idle              = false
  min_instances         = 1
  max_instances         = 2

  environment_variables = merge(local.kafka_environment, {
    AUTHORIZATION_DB_URL            = "jdbc:postgresql://${google_sql_database_instance.postgres.private_ip_address}:5432/${google_sql_database.authorization.name}"
    AUTHORIZATION_DB_USERNAME       = "transactiq_authorization_app"
    TRANSACTIQ_FRAUD_GRPC_HOST      = trimprefix(module.fraud_engine[0].uri, "https://")
    TRANSACTIQ_FRAUD_GRPC_PORT      = "443"
    TRANSACTIQ_FRAUD_GRPC_PLAINTEXT = "false"
  })

  secret_environment_variables = merge(local.kafka_secret_environment, {
    AUTHORIZATION_DB_PASSWORD = {
      secret = google_secret_manager_secret.configuration["authorization_database_password"].secret_id
    }
  })

  labels = local.labels

  depends_on = [
    google_artifact_registry_repository.services,
    google_compute_router_nat.cloud_run,
    google_secret_manager_secret_iam_member.runtime,
  ]
}

module "case_management" {
  count  = var.deploy_services ? 1 : 0
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "${local.name_prefix}-case-management"
  image                 = local.image_uris["case-management-service"]
  service_account_email = google_service_account.runtime["case-management-service"].email
  vpc_network           = google_compute_network.transactiq.name
  vpc_subnetwork        = google_compute_subnetwork.cloud_run.name
  cpu_idle              = false
  min_instances         = 1
  max_instances         = 2

  environment_variables = merge(local.kafka_environment, {
    CASE_MANAGEMENT_DB_URL      = "jdbc:postgresql://${google_sql_database_instance.postgres.private_ip_address}:5432/${google_sql_database.case_management.name}"
    CASE_MANAGEMENT_DB_USERNAME = "transactiq_case_management_app"
  })

  secret_environment_variables = merge(local.kafka_secret_environment, {
    CASE_MANAGEMENT_DB_PASSWORD = {
      secret = google_secret_manager_secret.configuration["case_database_password"].secret_id
    }
  })

  labels = local.labels

  depends_on = [
    google_artifact_registry_repository.services,
    google_secret_manager_secret_iam_member.runtime,
  ]
}

module "case_search" {
  count  = var.deploy_services ? 1 : 0
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "${local.name_prefix}-case-search"
  image                 = local.image_uris["case-search-service"]
  service_account_email = google_service_account.runtime["case-search-service"].email
  vpc_network           = google_compute_network.transactiq.name
  vpc_subnetwork        = google_compute_subnetwork.cloud_run.name
  cpu_idle              = false
  min_instances         = 1
  max_instances         = 2

  environment_variables = merge(local.kafka_environment, {
    TRANSACTIQ_OPENSEARCH_URL = var.external_opensearch_url
  })
  secret_environment_variables = local.kafka_secret_environment
  labels                       = local.labels

  depends_on = [
    google_artifact_registry_repository.services,
    google_secret_manager_secret_iam_member.runtime,
  ]
}

module "investigation_assistant" {
  count  = var.deploy_services ? 1 : 0
  source = "../../modules/cloud-run-service"

  project_id            = var.project_id
  region                = var.region
  name                  = "${local.name_prefix}-investigation-assistant"
  image                 = local.image_uris["investigation-assistant-service"]
  service_account_email = google_service_account.runtime["investigation-assistant-service"].email
  vpc_network           = google_compute_network.transactiq.name
  vpc_subnetwork        = google_compute_subnetwork.cloud_run.name
  cpu_idle              = false
  min_instances         = 1
  max_instances         = 2
  memory                = "1Gi"

  environment_variables = merge(local.kafka_environment, {
    TRANSACTIQ_OPENSEARCH_URL = var.external_opensearch_url
  })

  secret_environment_variables = merge(local.kafka_secret_environment, {
    OPENAI_API_KEY = {
      secret = google_secret_manager_secret.configuration["openai_api_key"].secret_id
    }
  })

  labels = local.labels

  depends_on = [
    google_artifact_registry_repository.services,
    google_secret_manager_secret_iam_member.runtime,
  ]
}
