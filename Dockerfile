FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
COPY seeds/club-definition.schema.json seeds/club-definition.schema.json
# Integration tests run on the host with Docker, not inside the image build.
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --user-group --create-home agilityhub \
    && install -d -m 0700 -o agilityhub -g agilityhub /app/mailbox /app/exports /app/attachments
WORKDIR /app
COPY --from=build --chown=agilityhub:agilityhub /workspace/target/*.jar app.jar
COPY --chown=agilityhub:agilityhub seeds/club-canic-consumer.yaml seeds/club-canic.yaml
COPY --chown=agilityhub:agilityhub seeds/club-minim.yaml seeds/club-minim.yaml
COPY --chown=agilityhub:agilityhub seeds/pages/ seeds/pages/
USER agilityhub
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=12 \
    CMD curl -fsS "http://localhost:${SERVER_PORT:-8080}/api/v1/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
