resource "google_service_account" "runtime" {
  for_each = local.service_account_ids

  project      = var.project_id
  account_id   = each.value
  display_name = "TransactIQ dev ${each.key} runtime"
  description  = "Dedicated keyless runtime identity for ${each.key}."

  depends_on = [google_project_service.required["iam.googleapis.com"]]
}
