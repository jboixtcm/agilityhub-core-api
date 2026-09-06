# API contract changelog

Add one dated line per endpoint change whenever the API changes; regenerate and review `openapi.json` with `bin/openapi-snapshot` (Java 21 and Docker required).

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
