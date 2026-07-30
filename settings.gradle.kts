pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "transactiq"

include("authorization-service")
include("event-contract")
include("fraud-contract")
include("fraud-engine")
include("transaction-simulator")
