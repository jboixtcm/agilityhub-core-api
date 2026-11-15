# Public logo loading

Published club logos currently require a redirect to a signed URL and disable browser caching. Serve them directly with a five-minute public cache and ETag. Retain tenant and publication checks, bounded storage reads, MIME disposition and the download sandbox policy. Private attachment downloads retain their existing behavior. The generated OpenAPI contract documents binary 200 and conditional 304 responses.

Validation: 97 targeted tests passed (46 platform-console integration, 16 attachment service, 12 OpenAPI, 23 architecture), zero failures/errors/skips. Full official CI and production HTTP verification are required before deployment. The matching lossless WebP brand assets and deployment evidence are maintained in SOFTWARE_CANIC operations.

Commands (all exit 0):

- `./mvnw -q clean test-compile failsafe:integration-test failsafe:verify -Dit.test=PlatformConsoleIT`
- `./mvnw -q -Dtest=AttachmentServiceTest,ArchitectureRules,OpenApiSnapshotTest -Dopenapi.snapshot.update=true test`
- `bin/openapi-snapshot --from-build`
- `./mvnw -q -Dtest=ArchitectureTest test`

The second command does not run ArchitectureRules itself; the final command executes the 23 architecture checks. Counts are recorded in `validation.json`.
