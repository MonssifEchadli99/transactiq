output "artifact_registry_repository_id" {
  description = "Artifact Registry repository ID consumed by CI/CD."
  value       = google_artifact_registry_repository.services.repository_id
}

output "artifact_registry_repository_url" {
  description = "Artifact Registry repository URL without an image name or tag."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.services.repository_id}"
}

output "service_images" {
  description = "Immutable image references expected by the Cloud Run services."
  value       = local.image_uris
}

output "service_deployment_enabled" {
  description = "Whether this plan includes Cloud Run services."
  value       = var.deploy_services
}

output "cloud_run_service_uris" {
  description = "Cloud Run URIs. Values are null during the foundation-only bootstrap."
  value = {
    authorization-service           = try(module.authorization[0].uri, null)
    fraud-engine                    = try(module.fraud_engine[0].uri, null)
    case-management-service         = try(module.case_management[0].uri, null)
    case-search-service             = try(module.case_search[0].uri, null)
    investigation-assistant-service = try(module.investigation_assistant[0].uri, null)
  }
}

output "runtime_service_accounts" {
  description = "Dedicated keyless Cloud Run runtime identities."
  value       = { for service, account in google_service_account.runtime : service => account.email }
}

output "secret_containers" {
  description = "Secret Manager container IDs that require out-of-band versions before service deployment."
  value       = { for name, secret in google_secret_manager_secret.configuration : name => secret.secret_id }
}

output "cloud_sql" {
  description = "Private Cloud SQL connection metadata."
  value = {
    instance_name      = google_sql_database_instance.postgres.name
    connection_name    = google_sql_database_instance.postgres.connection_name
    private_ip_address = google_sql_database_instance.postgres.private_ip_address
    databases = [
      google_sql_database.authorization.name,
      google_sql_database.case_management.name,
    ]
  }
}

output "memorystore_redis" {
  description = "Private Memorystore endpoint used by the fraud engine."
  value = {
    host = google_redis_instance.fraud_velocity.host
    port = google_redis_instance.fraud_velocity.port
  }
}

output "vpc_network" {
  description = "Private VPC used by Cloud Run, Cloud SQL, and Memorystore."
  value       = google_compute_network.transactiq.id
}
