resource "google_secret_manager_secret" "configuration" {
  for_each = local.secret_ids

  project   = var.project_id
  secret_id = each.value
  labels    = local.labels

  replication {
    auto {}
  }

  annotations = {
    "transactiq.dev/bootstrap" = "value-required-before-service-deployment"
  }

  depends_on = [google_project_service.required["secretmanager.googleapis.com"]]
}

locals {
  secret_access = {
    authorization_database = {
      service = "authorization-service"
      secret  = "authorization_database_password"
    }
    authorization_kafka = {
      service = "authorization-service"
      secret  = "kafka_sasl_jaas_config"
    }
    case_management_database = {
      service = "case-management-service"
      secret  = "case_database_password"
    }
    case_management_kafka = {
      service = "case-management-service"
      secret  = "kafka_sasl_jaas_config"
    }
    case_search_kafka = {
      service = "case-search-service"
      secret  = "kafka_sasl_jaas_config"
    }
    investigation_kafka = {
      service = "investigation-assistant-service"
      secret  = "kafka_sasl_jaas_config"
    }
    investigation_openai = {
      service = "investigation-assistant-service"
      secret  = "openai_api_key"
    }
  }
}

resource "google_secret_manager_secret_iam_member" "runtime" {
  for_each = local.secret_access

  project   = var.project_id
  secret_id = google_secret_manager_secret.configuration[each.value.secret].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime[each.value.service].email}"
}
