resource "google_sql_database_instance" "postgres" {
  project             = var.project_id
  name                = "${local.name_prefix}-postgres"
  region              = var.region
  database_version    = "POSTGRES_16"
  deletion_protection = var.protect_stateful_resources

  settings {
    tier              = "db-f1-micro"
    availability_type = "ZONAL"
    disk_type         = "PD_SSD"
    disk_size         = 10
    disk_autoresize   = true
    user_labels       = local.labels

    deletion_protection_enabled = var.protect_stateful_resources

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.transactiq.id
    }

    backup_configuration {
      enabled                        = true
      start_time                     = "03:00"
      point_in_time_recovery_enabled = false

      backup_retention_settings {
        retained_backups = 3
        retention_unit   = "COUNT"
      }
    }

    maintenance_window {
      day          = 7
      hour         = 4
      update_track = "stable"
    }
  }

  depends_on = [
    google_project_service.required["sqladmin.googleapis.com"],
    google_service_networking_connection.private_services,
  ]
}

resource "google_sql_database" "authorization" {
  project  = var.project_id
  name     = "transactiq_authorization"
  instance = google_sql_database_instance.postgres.name
}

resource "google_sql_database" "case_management" {
  project  = var.project_id
  name     = "transactiq_case_management"
  instance = google_sql_database_instance.postgres.name
}

# Database users and passwords are intentionally not Terraform resources: a
# password passed to google_sql_user would be retained in Terraform state.
resource "google_redis_instance" "fraud_velocity" {
  project             = var.project_id
  name                = "${local.name_prefix}-fraud-velocity"
  display_name        = "TransactIQ dev fraud velocity"
  region              = var.region
  tier                = "BASIC"
  memory_size_gb      = 1
  redis_version       = "REDIS_7_2"
  authorized_network  = google_compute_network.transactiq.id
  connect_mode        = "PRIVATE_SERVICE_ACCESS"
  auth_enabled        = false
  deletion_protection = var.protect_stateful_resources
  labels              = local.labels

  depends_on = [
    google_project_service.required["redis.googleapis.com"],
    google_service_networking_connection.private_services,
  ]
}
