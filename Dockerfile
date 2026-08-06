# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
COPY authorization-service authorization-service
COPY case-management-service case-management-service
COPY case-projection-contract case-projection-contract
COPY case-search-service case-search-service
COPY event-contract event-contract
COPY fraud-contract fraud-contract
COPY fraud-engine fraud-engine
COPY investigation-assistant-service investigation-assistant-service
COPY observability-support observability-support
COPY transaction-simulator transaction-simulator

ARG SERVICE
RUN --mount=type=cache,target=/root/.gradle \
    case "${SERVICE}" in \
      authorization-service|fraud-engine|case-management-service|case-search-service|investigation-assistant-service) ;; \
      *) echo "Unsupported deployable service" >&2; exit 2 ;; \
    esac \
    && chmod +x gradlew \
    && ./gradlew --no-daemon --console=plain ":${SERVICE}:bootJar" \
    && set -- $(find "${SERVICE}/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar') \
    && test "$#" -eq 1 \
    && cp "$1" /workspace/application.jar \
    && test -s /workspace/application.jar

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 transactiq \
    && useradd --system --uid 10001 --gid transactiq --home-dir /app --shell /usr/sbin/nologin transactiq

WORKDIR /app
COPY --from=builder --chown=transactiq:transactiq /workspace/application.jar /app/application.jar

USER 10001:10001
EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
