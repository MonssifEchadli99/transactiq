locals {
  plain_environment = {
    for name, value in var.environment_variables : name => {
      value  = value
      secret = null
    }
  }
  secret_environment = {
    for name, reference in var.secret_environment_variables : name => {
      value  = null
      secret = reference
    }
  }
  environment = merge(local.plain_environment, local.secret_environment)
}

resource "google_cloud_run_v2_service" "this" {
  project              = var.project_id
  name                 = var.name
  location             = var.region
  ingress              = var.ingress
  invoker_iam_disabled = var.invoker_iam_disabled
  deletion_protection  = var.deletion_protection

  template {
    service_account                  = var.service_account_email
    timeout                          = var.request_timeout
    max_instance_request_concurrency = var.max_instance_request_concurrency

    scaling {
      min_instance_count = var.min_instances
      max_instance_count = var.max_instances
    }

    vpc_access {
      egress = var.vpc_egress

      network_interfaces {
        network    = var.vpc_network
        subnetwork = var.vpc_subnetwork
        tags       = ["transactiq-cloud-run"]
      }
    }

    containers {
      image = var.image

      ports {
        name           = var.http2 ? "h2c" : "http1"
        container_port = var.container_port
      }

      resources {
        limits = {
          cpu    = var.cpu
          memory = var.memory
        }
        cpu_idle          = var.cpu_idle
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = local.environment
        content {
          name  = env.key
          value = env.value.value

          dynamic "value_source" {
            for_each = env.value.secret == null ? [] : [env.value.secret]
            content {
              secret_key_ref {
                secret  = value_source.value.secret
                version = value_source.value.version
              }
            }
          }
        }
      }

      startup_probe {
        initial_delay_seconds = 0
        timeout_seconds       = 2
        period_seconds        = 5
        failure_threshold     = 24

        dynamic "http_get" {
          for_each = var.startup_probe_path == null ? [] : [var.startup_probe_path]
          content {
            path = http_get.value
            port = var.startup_probe_port
          }
        }

        dynamic "tcp_socket" {
          for_each = var.startup_probe_path == null ? [1] : []
          content {
            port = var.startup_probe_port
          }
        }
      }

      liveness_probe {
        initial_delay_seconds = 10
        timeout_seconds       = 2
        period_seconds        = 10
        failure_threshold     = 3

        dynamic "http_get" {
          for_each = var.liveness_probe_path == null ? [] : [var.liveness_probe_path]
          content {
            path = http_get.value
            port = var.liveness_probe_port
          }
        }

        dynamic "tcp_socket" {
          for_each = var.liveness_probe_path == null ? [1] : []
          content {
            port = var.liveness_probe_port
          }
        }
      }
    }
  }

  labels = var.labels
}
