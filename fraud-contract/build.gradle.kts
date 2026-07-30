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
    api("io.grpc:grpc-protobuf:1.82.1")
    api("io.grpc:grpc-stub:1.82.1")
    api("com.google.protobuf:protobuf-java:3.25.8")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.8"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.82.1"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}
