# Deployment recipes

## Browser hosts and refresh cookies (E1-T13 / A1)

Serve every club app, club admin and identity SPA through its own HTTPS host.
The browser calls relative URLs and keeps the access token in memory. The API
sets `ah_refresh` as a host-only cookie with `HttpOnly; Secure; SameSite=Strict;
Path=/oauth2/token`; its lifetime is the effective `auth.sessionDays` in seconds.
Each successful refresh rotates both the persisted token and the cookie.
`clubs-app`, `clubs-admin` and `id-web` use `token-delivery: COOKIE` in
`core.oidc.clients`; `learn` and `ar-app` retain `BODY` delivery. This is client
configuration, not a catalog parameter. Missing or unknown delivery values fail
startup.

Caddy template for each app host (replace the fictional host and SPA directory):

```caddyfile
app.example.test {
    handle /api/* {
        reverse_proxy core:8080
    }
    @identity path /oauth2/* /.well-known/* /connect/logout
    handle @identity {
        reverse_proxy id:8080
    }
    handle {
        root * /srv/app
        try_files {path} /index.html
        file_server
    }
}
```

`core:8080` and `id:8080` denote private upstream services. With the current modular
monolith both routes can use `core:8080`; `id` is the identity upstream name when
separately routed. Preserve the original `Host`, `Origin`, `Referer`, `Cookie` and
all `Set-Cookie` response headers, including the two logout headers. Do not rewrite
cookie paths or domains. Caddy preserves Host for these HTTP upstreams. On an API
reachable only through the trusted proxy, enable Spring's
`server.forward-headers-strategy=framework` to recognize HTTPS forwarded by Caddy.
The proxy must overwrite client-supplied forwarding headers. Keep the management
port and direct upstream ports private. Register and verify each club host and its
OIDC callback before directing traffic to it.

A browser refresh posts form data `grant_type=refresh_token&client_id=clubs-app`
to `/oauth2/token`, with `credentials: 'same-origin'`. The browser sends the cookie;
it does not read or copy it into the body. The request must supply a same-host
`Origin`, or a same-host `Referer` when Origin is absent. An invalid supplied
Origin cannot fall back to Referer. Tokens remain bound to their registered client
and tenant. Reuse/expiry clears the cookie and returns the existing catalog error.
Magic-link and handoff redemption run on the destination host to create its own
first-party cookie. OIDC code exchange for a COOKIE client likewise runs through
that client's app proxy.

The cookie path intentionally excludes `/oauth2/revoke`. For a COOKIE client,
post `{}` with its bearer access token to that route to revoke the bearer session
and expire the cookie. Explicit `{token}` revocation remains supported for BODY
clients and impersonation. Deleting the current `/api/v1/me/sessions/{id}` clears
the cookie too; deleting another device does not. `/connect/logout` validates the
existing RP logout request and expires both identity and refresh cookies. A server
cannot delete a cookie on another host/device: it rejects that revoked family's
next refresh and clears the cookie on that response.

No credentialed cross-origin CORS is needed for SPAs. The existing CORS policy for
other consumers is retained. This section is a recipe for E0-T13/E10; it does not
publish or change a live Caddy installation.

## Local Vite proxy (E1-W07)

Use the same relative paths in development. An example `vite.config.ts` fragment:

```typescript
const backend = { target: 'http://127.0.0.1:8080', changeOrigin: false }
export default {
  server: {
    host: '127.0.0.1',
    allowedHosts: ['app.example.test'],
    proxy: {
      '/api': backend,
      '/oauth2': backend,
      '/.well-known': backend,
      '/connect/logout': backend,
    },
  },
}
```

Map `app.example.test` to loopback locally and register that host on the fictional
dev club; choose the corresponding admin/identity host for each SPA. Preserve the
browser Host instead of substituting the backend address. Run the API with the
`local` profile. Only plain HTTP under `local` relaxes `Secure`; HTTPS and all
other profiles retain it, and `prod`/`staging` take precedence over `local`.

## Bootstrap and manage platform admins (E1-T13 / A3)

After the intended person's account exists, run on the deployment host:

```sh
bin/core identity:grant-platform-admin operator@example.test
```

Replace the fictional example email with the existing account email. The command
normalizes it, grants `AGILITYHUB_ADMIN` idempotently and audits as SYSTEM; it never
creates an account or prints credentials. Use the deployment's normal environment,
including its Mongo connection and `OIDC_MASTER_KEY`. No real person or platform
admin credential belongs in a seed. Start a fresh login/refresh after a grant to
obtain the updated platform-role claim.

An existing platform admin can use `GET` and `PUT`
`/api/v1/platform/accounts/{id}/platform-roles` with `{ "platformRoles": [...] }`.
Only `AGILITYHUB_ADMIN` is supported. These global routes check the caller's live
stored role, require no club membership and audit changes atomically without a
domain event. Concurrent removals preserve one ACTIVE platform admin; removing
the last returns `409 LAST_PLATFORM_ADMIN`.
