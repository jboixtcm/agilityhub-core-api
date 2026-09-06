# Add an endpoint

Use the task's endpoint/role contract and the [checklist](checklist.md). Java blocks
are verbatim excerpts, with imports and surrounding code in their linked files.

## Route, use case and authorization

1. Put the controller in the owning context's `api` under `/api/v1`. Validate
   request DTOs, call an `application` use case, and map its result to an explicit
   response DTO. Keep persistence and business rules out of the controller.
2. Follow [CONVENCIONS_API](../specs/00-transversal/CONVENCIONS_API.md), including
   `filter=<field>:<op>:<value>` for lists and the documented paging/sort contract.
3. Derive `@PreAuthorize` roles and ownership checks from the task's spec and
   [MATRIU_PERMISOS](../specs/00-transversal/MATRIU_PERMISOS.md). Check method-security
   wiring in the secured slice. Role membership does not establish tenant or
   resource ownership; use `TenantContext.require()` and tenant repositories.
   Do not grant a platform role access to club data without the spec's permission.
4. Add `@RequiresModule` to gated controllers/methods. Both requirements apply
   when class and method are annotated. The MVC interceptor runs before JSON
   decoding/validation and returns `MODULE_DISABLED` (404). Application services
   and workers call `ModuleGuard.require(clubId, module)` explicitly; the
   annotation alone does not guard arbitrary service calls.

The existing module endpoint is a **test probe**, with role checks in its test
security chain. It demonstrates the module annotation, not a production role policy:

Source: [src/test/java/com/agilityhub/core/platform/api/RequiresModuleIT.java](../../src/test/java/com/agilityhub/core/platform/api/RequiresModuleIT.java#L205-L207).

```java
        @GetMapping(GUARD_PATH)
        @RequiresModule(Module.FREE_TRAINING)
        Map<String, String> guarded() { return Map.of("clubId", TenantContext.require()); }
```

## Responses and OpenAPI

This public production endpoint maps `ClubConfig` through `BrandingResponse.from`.
Its controller-level `@SecurityRequirements` marks it public in OpenAPI. Keep bearer security
on protected routes; do not copy that public override onto them.

Source: [src/main/java/com/agilityhub/core/platform/api/BrandingController.java](../../src/main/java/com/agilityhub/core/platform/api/BrandingController.java#L26-L34).

```java
    @GetMapping("/api/v1/branding")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "304", description = "Not modified",
                content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<BrandingResponse> branding(WebRequest request) throws JsonProcessingException {
        return response(BrandingResponse.from(configs.get(TenantContext.require())), request);
    }
```

Document request/response schemas, status codes, headers, parameters and security
with OpenAPI annotations, including empty bodies for statuses such as 304 above.
Check the generated context tag and shared error responses. After **any API
change**, run `bin/openapi-snapshot`, review `docs/openapi/openapi.json`, and include
the snapshot with the endpoint change. Generation uses Testcontainers; local/test
exposes `/api/v1/openapi.json`. A documentation-only task does not regenerate it.

Throw `ApiException(ErrorCode.X)` using [CATALEG_ERRORS](../specs/00-transversal/CATALEG_ERRORS.md).
The shared handler supplies `{code, message, details, traceId}` and localized
messages; do not build an ad-hoc error response. Missing catalog entries require
a proposal in the task report and `roadmap/MESSAGES.md`; never add them silently.
Use the catalog's ca/es/en messages and keep secret values out of details.

## Idempotent POSTs

The current filter activates only for POST with `Idempotency-Key`. It requires a
canonical UUID and authenticated JWT containing account subject and `clubId`.
No header means no replay protection; this filter does not protect anonymous or
global requests without club membership. Follow the endpoint's spec if it needs
a mandatory key or different scope, and report any missing mechanism.

Keys are scoped by club/account for 24 hours. Matching method, target, query,
content type and body replay status/body and selected headers (including original
`Content-Language`); a changed request or concurrent in-progress claim conflicts:

Source: [src/main/java/com/agilityhub/core/shared/api/IdempotencyFilter.java](../../src/main/java/com/agilityhub/core/shared/api/IdempotencyFilter.java#L89-L94).

```java
            if (!record.requestHash().equals(hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "DIFFERENT_REQUEST"));
            }
            if (record.status() == com.agilityhub.core.shared.persistence.IdempotencyRecord.Status.IN_PROGRESS) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of("reason", "IN_PROGRESS"));
            }
```

Keep the application mutation transactional even when a caller omits the key.
The filter joins synchronous request effects and stored response in a Mongo
transaction; it is not an external-side-effect deduplicator. See
[new-event-consumer](new-event-consumer.md) for outbox publication.

## Tests and verification

Use `AbstractIntegrationTest` with `@AutoConfigureMockMvc`. Pick the current spec's
`T_XX_NN_description` IDs; keep assertions on response body, status and side effects.

- Happy path for each allowed role, denied role and unauthenticated caller using
  the endpoint's production security policy. The E0 module probes use a test chain.
- Tenant A/B, JWT/host mismatch, missing membership, another club's resource ID,
  and cleared `TenantContext` after success and failure.
- Module enabled/disabled, including malformed and invalid bodies while disabled;
  class and method requirements together when both apply.
- Validation and domain errors, localization, DTO allowlist, and no persistence
  effects on denied/failed requests. Test replay, changed body/target, concurrency
  and account/tenant key isolation for idempotent POSTs.

References: [RequiresModuleIT](../../src/test/java/com/agilityhub/core/platform/api/RequiresModuleIT.java)
`T_02_09_*`, [PlatformIT](../../src/test/java/com/agilityhub/core/platform/persistence/PlatformIT.java)
`T_02_06_*` / `T_02_07_*`, and
[IdempotencyIT](../../src/test/java/com/agilityhub/core/shared/persistence/IdempotencyIT.java)
`E0_T04_*` / `E0_T08_*`. Run `./mvnw -q verify` and `bin/openapi-snapshot` for an API change.
