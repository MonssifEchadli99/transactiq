import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("tools.jackson.core:jackson-databind:3.1.4")

    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.monssifechadli99.transactiq.simulator.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
