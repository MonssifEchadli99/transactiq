terraform {
  # The manual deployment workflow supplies the bucket and prefix. No remote
  # state identifier is committed to the repository.
  backend "gcs" {}
}
