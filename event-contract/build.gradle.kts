plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.8")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.8"
    }
}

tasks.test {
    useJUnitPlatform()
}
