# API contract changelog

Add one dated line per endpoint change whenever the API changes; regenerate and review `openapi.json` with `bin/openapi-snapshot` (Java 21 and Docker required).

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
