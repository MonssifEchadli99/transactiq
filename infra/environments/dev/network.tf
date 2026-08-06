resource "google_compute_network" "transactiq" {
  project                 = var.project_id
  name                    = "${local.name_prefix}-network"
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"

  depends_on = [google_project_service.required["compute.googleapis.com"]]
}

resource "google_compute_subnetwork" "cloud_run" {
  project                  = var.project_id
  name                     = "${local.name_prefix}-cloud-run"
  region                   = var.region
  network                  = google_compute_network.transactiq.id
  ip_cidr_range            = "10.20.0.0/24"
  private_ip_google_access = true
}

resource "google_compute_global_address" "private_services" {
  project       = var.project_id
  name          = "${local.name_prefix}-private-services"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.transactiq.id
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.transactiq.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]

  depends_on = [google_project_service.required["servicenetworking.googleapis.com"]]
}

# Authorization routes all egress through the VPC so Cloud Run classifies its
# call to the internal-only fraud engine as internal. NAT preserves access to
# external managed Kafka without opening an inbound path.
resource "google_compute_router" "cloud_run" {
  project = var.project_id
  name    = "${local.name_prefix}-cloud-run-router"
  region  = var.region
  network = google_compute_network.transactiq.id
}

resource "google_compute_router_nat" "cloud_run" {
  project                            = var.project_id
  name                               = "${local.name_prefix}-cloud-run-nat"
  router                             = google_compute_router.cloud_run.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}
