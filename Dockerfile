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
# E11-T09 round 2: Maven 3.9's resolver retries a transfer that Maven Central answers with a transient 5xx (by default only
# 429 and 503), so a single 502 does not fail the image job (deploy/test_e11_t09.py MavenRetryTest).
# REL-02: CI passes the commit it builds (GIT_COMMIT); build-info records it and the health returns it.
ARG GIT_COMMIT=unknown
RUN ./mvnw -B -q -Daether.connector.http.retryHandler.count=3 \
    -Daether.connector.http.retryHandler.serviceUnavailable=429,500,502,503,504 -Dgit.commit=${GIT_COMMIT:-unknown} -DskipTests package

# Core starts Java directly; the base image's unused Pebble supervisor brings a separate vulnerable Go runtime (INC-90).
FROM eclipse-temurin:21-jre
RUN rm -f /usr/bin/pebble \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --user-group --create-home agilityhub \
    && install -d -m 0700 -o agilityhub -g agilityhub /app/mailbox /app/exports /app/attachments
WORKDIR /app
COPY --from=build --chown=agilityhub:agilityhub /workspace/target/*.jar app.jar
# INC-40 (E11-T10): each seed under its own name; the local consumer stacks apply club-canic-consumer.yaml.
COPY --chown=agilityhub:agilityhub seeds/club-canic.yaml seeds/club-canic.yaml
COPY --chown=agilityhub:agilityhub seeds/club-canic-demo.yaml seeds/club-canic-demo.yaml
COPY --chown=agilityhub:agilityhub seeds/club-canic-consumer.yaml seeds/club-canic-consumer.yaml
COPY --chown=agilityhub:agilityhub seeds/club-minim.yaml seeds/club-minim.yaml
# E10-T03: the console's catalog-clone source (S17 R-17-08, §13 #5), applied with `club:apply`.
COPY --chown=agilityhub:agilityhub seeds/club-template-default.yaml seeds/club-template-default.yaml
COPY --chown=agilityhub:agilityhub seeds/demo-canic.yaml seeds/demo-canic.yaml
COPY --chown=agilityhub:agilityhub seeds/club-fifo.yaml seeds/club-fifo.yaml
COPY --chown=agilityhub:agilityhub seeds/demo-fifo.yaml seeds/demo-fifo.yaml
COPY --chown=agilityhub:agilityhub seeds/club-perf.yaml seeds/club-perf.yaml
COPY --chown=agilityhub:agilityhub seeds/demo-perf.yaml seeds/demo-perf.yaml
COPY --chown=agilityhub:agilityhub seeds/pages/ seeds/pages/
# REL-02: the same commit on the image itself (`docker image inspect`); declared last so the layers above keep their cache.
ARG GIT_COMMIT=unknown
LABEL org.opencontainers.image.revision=$GIT_COMMIT
USER agilityhub
# CFG-12: a run that names no profile (a bare `docker run`) starts on prod, never on the jar's local default with its test routes
# and provider fakes; without the production configuration it refuses to start. Every Compose stack names its own profile.
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=12 \
    CMD curl -fsS "http://localhost:${SERVER_PORT:-8080}/api/v1/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
