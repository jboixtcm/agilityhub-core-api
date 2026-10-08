# Local rehearsal only: the upstream registry images are no longer public.
FROM golang:1.24.8-bookworm AS build
ADD --checksum=sha256:29db22500374169a43951c7cef09daf19e7291ea5ba00ac10f321371b0a35b32 https://codeload.github.com/minio/mc/tar.gz/refs/tags/RELEASE.2025-08-13T08-35-41Z /tmp/mc.tar.gz
RUN mkdir /source && tar -xzf /tmp/mc.tar.gz -C /source --strip-components=1
WORKDIR /source
RUN CGO_ENABLED=0 go build -buildvcs=false -o /out/mc .
FROM debian:bookworm-slim
COPY --from=build /out/mc /usr/local/bin/mc
ENTRYPOINT ["mc"]
