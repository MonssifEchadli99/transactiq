output "name" {
  description = "Cloud Run service name."
  value       = google_cloud_run_v2_service.this.name
}

output "uri" {
  description = "Cloud Run service URI."
  value       = google_cloud_run_v2_service.this.uri
}

output "id" {
  description = "Cloud Run service resource ID."
  value       = google_cloud_run_v2_service.this.id
}
