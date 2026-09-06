# API contract changelog

Add one dated line per endpoint change whenever the API changes; regenerate and review `openapi.json` with `bin/openapi-snapshot` (Java 21 and Docker required).

## 2026-09-06 · E1-T06 · Onboarding implementation

- `GET /api/v1/me/onboarding`: active platform-first consent selection, club-scoped policy renewal, and configured prefilled profile fields; existing response shape retained.
- `PUT /api/v1/me/onboarding`: accepts the current required consent and optional profile/image fields; rejects impersonated tokens and preserves the existing validation errors.
- `POST /api/v1/me/onboarding/postpone`: decrements the renewal allowance per policy, club and version; initial acceptance and exhausted allowances return the state unchanged. Rejects impersonated tokens.

## 2026-09-06 · E1-T05 · OIDC implementation

- `GET /.well-known/openid-configuration`: active discovery metadata and public 60-second cache policy.
- `GET /.well-known/jwks.json`, `GET /oauth2/jwks`: two public RSA keys with `kid`, `use`, `alg`; same cache policy.
- `GET /oauth2/authorize`: active cookie-bound login/code flow; optional `nonce` and `max_age`, conditional S256 PKCE for public clients, prompt and UI hints.
- `POST /oauth2/session`: new apps/id continuation contract `{flow}` → `{redirectUrl}`, requiring the flow cookie, same-origin Origin, and a fresh global id-web bearer login. Rotates the host-only Secure/HttpOnly/Lax session cookie.
- `POST /oauth2/token`: authorization-code and confidential-client grants are active; ID tokens follow `openid`, exposed refresh tokens follow `offline_access`; refresh may preserve or narrow scopes.
- `GET /oauth2/userinfo`: active openid-scoped account claims, with profile/email/memberships scope filtering.
- `GET /connect/logout`: active signed ID-token hint validation, exact registered post-logout URI, optional `state`, and browser-cookie revocation.

The existing catalog envelope is retained; protocol validation reasons use
`VALIDATION_ERROR.details.oauth2Error`. See README for apps/id integration and
configuration, including the migration from environment PEM to encrypted Mongo keys.

## 2026-09-06 · E1-T11 · Required properties by default

All Java model properties are required unless explicitly optional (`Optional`, Spring/JSpecify
`@Nullable`, or `@Schema` with `NOT_REQUIRED`/`nullable=true`). Branding, theme colors,
manifest, health, money, discovery and user-info membership schemas now declare their
required fields. Existing conditional/PATCH fields retain their optional status through
explicit annotations, including module-dependent E2 projections. `ApiError.details`,
`OnboardingState.requiredConsent` and `OnboardingField.value` are optional; onboarding
null types remain unchanged. `ApiError.traceId` stays required; validation field errors
remain nested in `details`. SYSTEM webhook callbacks may omit `clubId`. Schemas with
only optional properties publish `required: []`. Routes and runtime serialization are unchanged.


## 2026-09-06 · E2-T01 · S02/S03/S05/S14 contracts

All new operations return the localized `501 NOT_IMPLEMENTED` envelope after authentication,
role, tenant, module and request-shape checks. Implementations belong to the later E2 tasks.
Existing branding, manifest and S01 routes remain active. `/members/{id}/impersonation-token`
retains its S01 controller; its response gains optional `launchUrl` for S03.

Desktop lists use `ListPage<T>` (`items`, `page`, `size`, `totalItems`, `totalPages`,
`appliedFilters`). Catalogs keep S05's unpaginated `{items, totalItems}` shape.
`x-filterable` and `x-sortable` are ordered field-key arrays; `x-filter-operators` records
explicit `contains`/`between` restrictions. `x-columns` contains ordered
`{key, defaultVisible, module?, parameter?}` objects, preserving S03 defaults and gates.
Bounded lists publish empty capability arrays where the spec provides no universal filters.
Only the three E2 desktop lists advertise `x-exportable=true`; the three future-vertical
export routes have empty field allowlists pending their contracts. Role-specific list items
use `anyOf` because the reduced projection structurally overlaps the admin projection.

Audit responses publish all 59 action names from S14 R-14-09 without expanding the
implemented audit-writer action set.

Canonical `ErrorCode` statuses win over narrative examples: `MEMBER_NOT_ACTIVE`,
`EXPORT_EXPIRED`, `EXPORT_LIMIT`, `ORDER_INCOMPLETE` and `DATA_EXPORT_TOO_SOON` are 422;
`INVALID_API_KEY` is 403. No error enum or catalog additions were necessary.
`FAMILY_GROUP_MEMBER_TAKEN` maps to `FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP`;
uncatalogued `MEMBER_ERASED` maps to `INVALID_STATE` (see roadmap/MESSAGES.md).
The S05 `/rings/{id}/geometry` row is explicitly a link to S16 and is outside this contract.

`GET /public/{clubSlug}/plans` uses an `X-Api-Key` security scheme and slug-based context,
including requests from an external host. Postal lookup supports anonymous club-host context.
Global account-erasure and platform routes do not require a club claim. All admin-only
contracts deny impersonated tokens; future ownership checks remain necessary in services.

- `GET /api/v1/members`: E2 contract; protected.
- `GET /api/v1/members/filter-values`: E2 contract; protected.
- `GET /api/v1/members/{id}`: E2 contract; protected.
- `GET /api/v1/members/{id}/overview`: E2 contract; protected.
- `PATCH /api/v1/members/{id}`: E2 contract; protected.
- `PATCH /api/v1/members/{id}/payment-method`: E2 contract; protected.
- `POST /api/v1/members/{id}/booking-block`: E2 contract; protected.
- `DELETE /api/v1/members/{id}/booking-block`: E2 contract; protected.
- `POST /api/v1/members/{id}/access-resend`: E2 contract; protected.
- `PUT /api/v1/members/{id}/roles`: E2 contract; protected.
- `GET /api/v1/dogs`: E2 contract; protected.
- `GET /api/v1/dogs/filter-values`: E2 contract; protected.
- `GET /api/v1/dogs/{id}`: E2 contract; protected.
- `PATCH /api/v1/dogs/{id}`: E2 contract; protected.
- `PATCH /api/v1/dogs/{id}/level`: E2 contract; protected.
- `PATCH /api/v1/dogs/{id}/free-training`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/transfer`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/deactivation`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/reactivation`: E2 contract; protected.
- `PUT /api/v1/dogs/{id}/photo`: E2 contract; protected.
- `GET /api/v1/dogs/{id}/documents`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/documents`: E2 contract; protected.
- `DELETE /api/v1/dogs/{id}/documents/{docId}/files/{fileId}`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/documents/reminder`: E2 contract; protected.
- `POST /api/v1/family-groups`: E2 contract; protected.
- `GET /api/v1/family-groups/{id}`: E2 contract; protected.
- `PUT /api/v1/family-groups/{id}`: E2 contract; protected.
- `DELETE /api/v1/family-groups/{id}`: E2 contract; protected.
- `GET /api/v1/me/family-group`: E2 contract; protected.
- `GET /api/v1/me/dogs`: E2 contract; protected.
- `PUT /api/v1/me/dogs/{id}/photo`: E2 contract; protected.
- `POST /api/v1/me/dogs/{id}/documents`: E2 contract; protected.
- `PUT /api/v1/me/dogs/{id}/instructor-note`: E2 contract; protected.
- `GET /api/v1/me/profile`: E2 contract; protected.
- `PATCH /api/v1/me/profile`: E2 contract; protected.
- `GET /api/v1/levels`: E2 contract; protected.
- `POST /api/v1/levels`: E2 contract; protected.
- `GET /api/v1/levels/{id}`: E2 contract; protected.
- `PATCH /api/v1/levels/{id}`: E2 contract; protected.
- `DELETE /api/v1/levels/{id}`: E2 contract; protected.
- `PUT /api/v1/levels/order`: E2 contract; protected.
- `GET /api/v1/rings`: E2 contract; protected.
- `POST /api/v1/rings`: E2 contract; protected.
- `GET /api/v1/rings/{id}`: E2 contract; protected.
- `PATCH /api/v1/rings/{id}`: E2 contract; protected.
- `DELETE /api/v1/rings/{id}`: E2 contract; protected.
- `PUT /api/v1/rings/order`: E2 contract; protected.
- `GET /api/v1/plans`: E2 contract; protected.
- `POST /api/v1/plans`: E2 contract; protected.
- `GET /api/v1/plans/{id}`: E2 contract; protected.
- `PATCH /api/v1/plans/{id}`: E2 contract; protected.
- `DELETE /api/v1/plans/{id}`: E2 contract; protected.
- `PUT /api/v1/plans/order`: E2 contract; protected.
- `GET /api/v1/faq-entries`: E2 contract; protected.
- `POST /api/v1/faq-entries`: E2 contract; protected.
- `PATCH /api/v1/faq-entries/{id}`: E2 contract; protected.
- `DELETE /api/v1/faq-entries/{id}`: E2 contract; protected.
- `PUT /api/v1/faq-entries/order`: E2 contract; protected.
- `GET /api/v1/faq-entries/filter-values`: E2 contract; protected.
- `GET /api/v1/instructors`: E2 contract; protected.
- `POST /api/v1/instructors`: E2 contract; protected.
- `PATCH /api/v1/instructors/{id}`: E2 contract; protected.
- `DELETE /api/v1/instructors/{id}`: E2 contract; protected.
- `GET /api/v1/administrators`: E2 contract; protected.
- `POST /api/v1/administrators`: E2 contract; protected.
- `PATCH /api/v1/administrators/{membershipId}`: E2 contract; protected.
- `DELETE /api/v1/administrators/{membershipId}`: E2 contract; protected.
- `GET /api/v1/prices`: E2 contract; protected.
- `POST /api/v1/prices`: E2 contract; protected.
- `PATCH /api/v1/prices/{id}`: E2 contract; protected.
- `DELETE /api/v1/prices/{id}`: E2 contract; protected.
- `GET /api/v1/public/{clubSlug}/plans`: E2 contract; anonymous.
- `GET /api/v1/club`: E2 contract; protected.
- `GET /api/v1/parameters`: E2 contract; protected.
- `GET /api/v1/parameters/{key}`: E2 contract; protected.
- `GET /api/v1/parameters/{key}/history`: E2 contract; protected.
- `PUT /api/v1/parameters/{key}`: E2 contract; protected.
- `DELETE /api/v1/parameters/{key}`: E2 contract; protected.
- `PUT /api/v1/club/modules/{module}`: E2 contract; protected.
- `PUT /api/v1/club/opening-hours`: E2 contract; protected.
- `PUT /api/v1/club/holidays`: E2 contract; protected.
- `GET /api/v1/country-profile/postal-codes/{code}`: E2 contract; anonymous.
- `GET /api/v1/platform/parameter-catalog`: E2 contract; protected.
- `GET /api/v1/audit-entries`: E2 contract; protected.
- `GET /api/v1/audit-entries/filter-values`: E2 contract; protected.
- `GET /api/v1/audit-entries/{id}`: E2 contract; protected.
- `GET /api/v1/members/{id}/audit-entries`: E2 contract; protected.
- `GET /api/v1/members/{id}/consents`: E2 contract; protected.
- `POST /api/v1/members/{id}/erasure`: E2 contract; protected.
- `GET /api/v1/members/{id}/erasure`: E2 contract; protected.
- `DELETE /api/v1/members/{id}/erasure`: E2 contract; protected.
- `POST /api/v1/accounts/{id}/erasure`: E2 contract; protected.
- `GET /api/v1/platform/audit-entries`: E2 contract; protected.
- `GET /api/v1/platform/erasure-requests`: E2 contract; protected.
- `GET /api/v1/platform/security-events`: E2 contract; protected.
- `GET /api/v1/saved-views`: E2 contract; protected.
- `GET /api/v1/saved-views/{id}`: E2 contract; protected.
- `POST /api/v1/saved-views`: E2 contract; protected.
- `PUT /api/v1/saved-views/{id}`: E2 contract; protected.
- `DELETE /api/v1/saved-views/{id}`: E2 contract; protected.
- `GET /api/v1/exports`: E2 contract; protected.
- `GET /api/v1/exports/{id}`: E2 contract; protected.
- `POST /api/v1/members/{id}/data-export`: E2 contract; protected.
- `POST /api/v1/me/data-export`: E2 contract; protected.
- `GET /api/v1/members/export`: E2 contract; protected.
- `GET /api/v1/dogs/export`: E2 contract; protected.
- `GET /api/v1/audit-entries/export`: E2 contract; protected.
- `GET /api/v1/invoices/export`: E2 contract; protected.
- `GET /api/v1/activity-registrations/export`: E2 contract; protected.
- `GET /api/v1/notifications/export`: E2 contract; protected.
- `GET /api/v1/dashboard`: E2 contract; protected.
- `GET /api/v1/dashboard/counters`: E2 contract; protected.

## 2026-09-06 · E1-T03 Round 2 · webhook signature error

- `POST /webhooks/email/sendgrid`: missing or invalid signatures now return HTTP 401 with `code = WEBHOOK_SIGNATURE_INVALID` and empty `details`, superseding the initial `UNAUTHENTICATED` workaround below. Signature failures are recorded using the existing `WEBHOOK_SIGNATURE_INVALID` security event.

## 2026-09-06 · E1-T01 Round 2 · S01 v0.3 corrections

- `GET /api/v1/me/onboarding`: replace the previous state with required `pending`, `postponeRemaining`, nullable `requiredConsent {policy: PLATFORM|CLUB, version, url}`, and `fields[] {key, value: string|null, required}`.
- `PUT /api/v1/me/onboarding`: require `consentAccepted` and `consentVersion`; optional `fields {name?, locale?, phone?}` and `imageConsent`; return the corrected state and document `VALIDATION_ERROR`, `LOCALE_NOT_SUPPORTED` (400) and `CONSENT_VERSION_OUTDATED` (422).
- `POST /api/v1/me/onboarding/postpone`: new authenticated account route with no body and the corrected state as its 200 contract. Like GET/PUT onboarding, returns standard 501 until E1-T06.
- `POST /api/v1/auth/magic-link`: replaces the root `/auth/magic-link` route, remains public with optional club host, and shares the configured authentication IP quota with `/oauth2/token`; 429 includes `Retry-After`.
- `GET /api/v1/me`, `PATCH /api/v1/me`: account uses `MeAccount` with required `hasPassword` and `onboardingPending`, plus optional `emailVerifiedAt` (date-time); membership gains optional `gender` (`MALE|FEMALE|OTHER`). The provisioning `AccountSummary` is unchanged. Current bootstrap derives password presence and returns `onboardingPending=false`; verification time and Member gender are contract fields pending service/model support.

The two approved onboarding parameters are already registered with their documented defaults by E1-T03. No new error codes, events, notifications or parameter keys are introduced in this round. These corrections supersede the initial E1-T01 route/onboarding assumptions below.

## 2026-09-06 · E1-T03 email webhook

- `POST /webhooks/email/sendgrid`: public ECDSA-authenticated JSON event array with timestamp/signature headers; no bearer or request tenant required. Returns empty 200 for processed/replayed/irrelevant events, 400 for malformed signed JSON, and 401 `UNAUTHENTICATED` with `details.reason = WEBHOOK_SIGNATURE_INVALID` for missing/invalid signatures. The existing catalog assigns `WEBHOOK_SIGNATURE_INVALID` to 400; alignment is proposed in `roadmap/MESSAGES.md` without changing the catalog.

## 2026-09-06 · E1-T01 identity contract

- `POST /oauth2/token`: typed `TokenRequest` / `TokenResponse`, five grants and their form fields in `x-grants`; optional refresh/ID tokens and required scope. E0 password/refresh remain active for the public club clients; pending grants return 501.
- `GET /.well-known/openid-configuration`: public global OIDC discovery schema; 501 until E1-T05.
- `GET /.well-known/jwks.json`, `GET /oauth2/jwks`: typed public JWK schemas and MVC contract signatures; existing security filters still serve both aliases.
- `GET /oauth2/authorize`: public authorization-code/PKCE parameters and 302 redirect contract; 501 until E1-T05.
- `POST /oauth2/revoke`: authenticated, idempotent JSON `{token}` revocation with empty 200 response; pending implementation returns 501.
- `GET /oauth2/userinfo`: openid-scoped account claims and optional scoped memberships; 501 until E1-T05.
- `GET /connect/logout`: ID-token hint and post-logout redirect parameters, 302 contract; 501 until E1-T05.
- `POST /auth/magic-link`: public `{email, purpose, client_id, redirect_uri?}`, neutral empty 202 contract; 501 until E1-T02.
- `POST /api/v1/auth/handoff`: authenticated club context, `{targetClientId}` and 201 `HandoffResponse`; 501 until E1-T04.
- `GET /api/v1/me`: R-01-15 `Me` schema replaces `modules` with `features`, adds platformRoles, clubId, profiles, activeProfile, instructorId, rememberProfile and optional impersonation. Existing club bootstrap is mapped to this schema; global/impersonated bootstrap returns 501 until E1 implementation.
- `PATCH /api/v1/me`: optional locale/name, updated `Me` response; pending implementation returns 501.
- `PUT /api/v1/me/password`: `{current?, new, repeat}`, empty 200, denies impersonation; pending implementation returns 501.
- `PUT /api/v1/me/profile`: `{activeProfile, remember}`, fresh access token, club context required; pending implementation returns 501.
- `GET /api/v1/me/sessions`: bounded array of public device sessions without credential material; pending implementation returns 501.
- `DELETE /api/v1/me/sessions/{id}`: account-owned session revocation, empty 200; pending implementation returns 501.
- `GET /api/v1/me/onboarding`: `OnboardingState` with requested fields and current privacy policy; 501 until E1-T06.
- `PUT /api/v1/me/onboarding`: optional name/locale/phone, required privacy acceptance/version and optional image consent; updated `OnboardingState`, 501 until E1-T06. Field names are explicit task assumptions pending organizer confirmation.
- `POST /api/v1/members/{id}/impersonation-token`: same-club ADMIN, `{reason?}`, 201 token/expiry; denies nested impersonation, returns 501 until E1-T04.
- `POST /api/v1/platform/accounts`: `PlatformAccountRequest`, 201 public account/ID; global platform admin or learn audience with accounts:write scope, pending implementation returns 501.
- `PUT /api/v1/accounts/{id}/password`: write-only passwordHash and empty 200; same global authorization, pending implementation returns 501.

All pending methods use `501 NOT_IMPLEMENTED` in the shared localized `ApiError` envelope. No ErrorCode additions: all 17 S01 codes already exist. Canonical catalog statuses win over conflicting narrative examples (`REFRESH_EXPIRED` is 400). OAuth/magic-link routes use the identity root; application routes use `/api/v1` per CONVENCIONS_API §1.

## 2026-09-06 · E0-T12 baseline

- `GET /api/v1/health`: public global health, application version and build time.
- `GET /api/v1/branding`: public host-scoped club branding, modules, locales and signup settings; supports ETag / 304.
- `GET /api/v1/manifest.webmanifest`: public host-scoped PWA manifest (`application/manifest+json`); supports ETag / 304.
- `GET /api/v1/me`: bearer-protected account, active membership and enabled modules; MEMBER / INSTRUCTOR / ADMIN / AGILITYHUB_ADMIN.
- `POST /oauth2/token`: form-encoded password or rotating refresh grant for a host-selected club and public client; shared ApiError failures.
- `GET /oauth2/jwks`: public global RSA verification key set.
- `GET /.well-known/jwks.json`: public global alias for the RSA verification key set.
- `GET /api/v1/openapi.json`: OpenAPI 3.1 contract replaces `/v3/api-docs` in local/test; generation remains disabled in staging/prod.

All operations declare their bounded context and shared `ApiError` responses; protected operations inherit the `bearer` security requirement. Fixed server URLs and recursively sorted object keys make the snapshot independent of test ports, locale and build time. No list endpoints exist yet; future lists must declare `x-filterable` / `x-sortable` per `CONVENCIONS_API.md` §4.
