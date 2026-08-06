locals {
  cloud_run_service_names = [
    "${local.name_prefix}-authorization",
    "${local.name_prefix}-fraud-engine",
    "${local.name_prefix}-case-management",
    "${local.name_prefix}-case-search",
    "${local.name_prefix}-investigation-assistant",
  ]
  cloud_run_service_filter = join(" OR ", [
    for service_name in local.cloud_run_service_names :
    "resource.labels.service_name=\"${service_name}\""
  ])
}

resource "google_monitoring_alert_policy" "cloud_run_errors" {
  count = var.enable_alerts ? 1 : 0

  project               = var.project_id
  display_name          = "TransactIQ dev Cloud Run 5xx responses"
  combiner              = "OR"
  severity              = "ERROR"
  notification_channels = var.alert_notification_channel_ids
  user_labels           = local.labels

  documentation {
    mime_type = "text/markdown"
    content   = "Cloud Run services in the dev region are returning 5xx responses. Check sanitized application logs, dependency health, and the active revision."
  }

  conditions {
    display_name = "5xx response rate is non-zero"

    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"cloud_run_revision\"",
        "resource.labels.location=\"${var.region}\"",
        "(${local.cloud_run_service_filter})",
        "metric.type=\"run.googleapis.com/request_count\"",
        "metric.labels.response_code_class=\"5xx\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "120s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_RATE"
        cross_series_reducer = "REDUCE_SUM"
        group_by_fields      = ["resource.label.service_name"]
      }

      evaluation_missing_data = "EVALUATION_MISSING_DATA_INACTIVE"
    }
  }

  alert_strategy {
    auto_close = "1800s"
  }

  depends_on = [google_project_service.required["monitoring.googleapis.com"]]
}

resource "google_monitoring_alert_policy" "cloud_run_latency" {
  count = var.enable_alerts ? 1 : 0

  project               = var.project_id
  display_name          = "TransactIQ dev Cloud Run p95 latency"
  combiner              = "OR"
  severity              = "WARNING"
  notification_channels = var.alert_notification_channel_ids
  user_labels           = local.labels

  documentation {
    mime_type = "text/markdown"
    content   = "Cloud Run p95 request latency exceeded two seconds. Check dependency latency, instance scaling, and the active revision before changing capacity."
  }

  conditions {
    display_name = "p95 request latency exceeds two seconds"

    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"cloud_run_revision\"",
        "resource.labels.location=\"${var.region}\"",
        "(${local.cloud_run_service_filter})",
        "metric.type=\"run.googleapis.com/request_latencies\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 2000
      duration        = "300s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
        group_by_fields      = ["resource.label.service_name"]
      }

      evaluation_missing_data = "EVALUATION_MISSING_DATA_INACTIVE"
    }
  }

  alert_strategy {
    auto_close = "1800s"
  }

  depends_on = [google_project_service.required["monitoring.googleapis.com"]]
}
