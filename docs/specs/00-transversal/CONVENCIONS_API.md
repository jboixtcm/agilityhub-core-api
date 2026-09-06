# Convencions de l'API — `agilityhub-core-api`

**v1.0 · 03-09-2026** · S'aplica a totes les specs S01–S20. El codi és en anglès; els noms d'entitat són els del glossari (`04-arquitectura/MODEL_DADES_PLATAFORMA.md` §0).

## 1. Base, versions i hosts

- Base: `https://core.agilitydoghub.com/api/v1` (staging: `core.staging.agilitydoghub.com`). OAuth2/OIDC a `https://id.agilitydoghub.com` (`/oauth2/token`, `/oauth2/authorize`, `/oauth2/revoke`, `/oauth2/userinfo`, `/connect/logout`, `/.well-known/openid-configuration`, `/.well-known/jwks.json`); `/auth/magic-link` i `/auth/handoff` són API d'aplicació i van sota `/api/v1` (S01 v0.3, 06-09).
- Versió a la ruta (`/v1`). Canvis incompatibles → `/v2` amb període de convivència. Canvis compatibles (camps nous opcionals) no canvien versió.
- OpenAPI 3.1 generat per springdoc a `/api/v1/openapi.json`; el front genera tipus (`packages/api-client`). **El diff de l'OpenAPI es valida a CI** (PLA_BACKEND §9.9).

## 2. Autenticació, tenant i autorització

- `Authorization: Bearer <JWT>` (15 min) + refresh per `/oauth2/token` (`grant_type=refresh_token`). Anònims: només els endpoints marcats `ANON`.
- **Tenant**: el `clubId` surt del claim del JWT o, en endpoints anònims, del host (`X-Club-Host` que Caddy injecta = `Host` original). **Mai** s'accepta `clubId` en path, query o body dels endpoints de club. Els endpoints de plataforma (`/platform/clubs/{clubId}/…`) sí que el porten al path i exigeixen `AGILITYHUB_ADMIN`.
- **Rols** (claim `roles[]`): `MEMBER`, `INSTRUCTOR`, `ADMIN`; plataforma: `AGILITYHUB_ADMIN` (claim `platformRoles[]`). Anotacions: `@PreAuthorize("hasRole('ADMIN')")` al controller + comprovació de pertinença del recurs al servei (un `MEMBER` només veu/escriu el seu abonat, els seus gossos i els del grup familiar).
- **Impersonació**: JWT amb `actorAccountId` + `impersonatedMemberId`; els endpoints d'abonat accepten aquest token com si fos l'abonat; tota escriptura s'audita amb els dos ids i `origin=BACKOFFICE`. Endpoints d'admin **rebutgen** tokens d'impersonació.
- **Mòduls**: `@RequiresModule("FREE_TRAINING")` → `404 {code: MODULE_DISABLED}` si el club no el té actiu.

## 3. Rutes i recursos

Rutes en anglès, plural, kebab-case; accions no CRUD com a sub-recursos verb-nom en POST:

```
/members · /members/{id} · /members/{id}/booking-block · /members/{id}/impersonation-token
/dogs · /family-groups · /levels · /rings · /instructors · /plans · /prices
/week-templates · /week-templates/{id}/classes · /weeks · /weeks/{id}/generation · /weeks/{id}/validation
/class-sessions · /class-sessions/{id}/cancellation · /ring-blocks
/bookings · /bookings/{id}/cancellation · /seat-holds · /waitlist-entries · /waitlist-entries/{id}/claim
/training-slots · /training-bookings · /attendances · /tasks · /attachments
/invoices · /remittances · /remittances/{id}/rollback · /upfront-payments · /pack-balances · /checkout-sessions
/message-templates · /notifications · /me/notification-preferences · /push-subscriptions · /faq-entries
/courses · /rings/{id}/geometry · /placements · /ring-setups · /build-sessions · /obstacle-inventories
/me (compte + membresies + perfil actiu) · /branding (ANON, per host) · /signup (ANON)
/platform/clubs · /platform/clubs/{id}/… (consola AgilityHub)
/public/{clubSlug}/activities · /public/{clubSlug}/plans (ANON, clau d'API del club)
/webhooks/stripe/{clubId} (signatura Stripe)
```

Prefix `/me/...` per als recursos «del qui crida» (abonat/instructor actual): `/me/dogs`, `/me/bookings`, `/me/history`, `/me/home` (agregat de la pantalla 03).

**Rutes afegides el 03-09 per les specs** (mateixes convencions; el detall és al §6 de cada spec):
```
S01  /auth/handoff · /me/sessions · /me/profile · /me/password · /platform/accounts · /accounts/{id}/password · /oauth2/userinfo · /connect/logout
S02  /manifest.webmanifest · /club · /club/modules/{module} · /club/opening-hours · /club/holidays · /parameters/{key}/history · /country-profile/postal-codes/{code} · /platform/parameter-catalog
S03  /members/{id}/booking-block · /members/{id}/access-resend · /members/{id}/impersonation-token · /members/{id}/payment-method · /dogs/{id}/level · /dogs/{id}/free-training · /dogs/{id}/transfer · /dogs/{id}/documents · /me/dogs/{id}/instructor-note · /members/filter-values
S04  /signup/identity-checks · /signup/family-group-lookups · /signup/upload-urls · /signup/towns · /members/{id}/signup · /members/{id}/validation?dryRun= · /members/{id}/rejection · /me/dogs/signup
S05  /administrators · /public/{clubSlug}/plans
S06  /weeks/{id}/calendar · /weeks/generation-candidates · /class-sessions/{id}/cancellation-preview · /class-sessions/{id}/risk-exemption · /coverage · /day-grid
S07  /activities/{id}/publication · /activities/{id}/cancellation · /activities/{id}/ring-conflicts · /activities/{id}/cancellation-preview · /activity-registrations · /me/activities · /public/{clubSlug}/activities
S08  /me/home · /me/bookable-classes · /seat-holds · /bookings/{id}/cancellation · /waitlist-entries/{id}/claim · /me/bookings
S09  /training-slots · /training-bookings/{id}/cancellation · /me/training-summary · /ring-blocks
S10  /instructor/day · /instructor/week (+ /export) · /class-sessions/{id}/attendance · /dogs/{id}/instructor-card · /dogs/{id}/observations · /tasks/{id}/completion · /tasks/{id}/reopening · /followup · /followup/unread-count · /followup/{id}/read · /followup/read-all · /me/history
S11  /message-templates/{id}/preview · /message-templates/{id}/send · /me/notifications · /me/notification-preferences · /members/{id}/notification-preferences · /push-subscriptions · /faq-entries · /email-unsubscribes · /webhooks/email/{provider}
S12  /billing/periods/{period} · /billing/simulations · /billing/runs · /billing/runs/{id}/card-charges · /billing/runs/{id}/rollback · /remittances/{id}/file · /remittances/{id}/submission · /invoices/{id}/payment · /invoices/payments · /invoices/{id}/failure · /invoices/{id}/retry · /invoices/{id}/refund · /invoices/{id}/cancellation · /invoices/{id}/document · /me/invoices · /upfront-payments · /pack-balances · /pack-balances/{id}/adjustments · /members/{id}/pending-charges · /checkout-sessions · /members/{id}/card-setup-link · /me/card-setup · /billing/exports · /webhooks/stripe/{clubId}
S13  /me/inactivity-periods · /inactivity-periods · /inactivity-periods/{id}/decision · /inactivity-periods/{id}/termination · /me/leave-requests · /leave-requests · /leave-requests/{id}/decision · /members/{id}/leave · /members/{id}/planned-leave · /members/{id}/reactivation
S14  /dashboard · /dashboard/counters · /audit-entries · /members/{id}/audit-entries · /exports/{id} · /{recurs}/export · /members/{id}/data-export · /me/data-export · /members/{id}/consents · /members/{id}/erasure · /platform/security-events
S15  /jobs · /jobs/{name}/runs · /jobs/{name}/trigger · /jobs/{name}/switch · /platform/jobs/overview
S16  /courses · /courses/{id}/duplicate · /courses/{id}/copy-to-club · /courses/upload-urls · /rings/{id}/geometry · /rings/{id}/marker-sheet · /rings/{id}/setups · /placements · /placements/{id}/build-sheet · /ring-setups · /ring-setups/{id}/dismantle · /ring-setups/{id}/renewal · /me/ring-setups · /build-sessions · /build-sessions/{id}/obstacles/{obstacleId} · /build-sessions/{id}/finish · /build-sessions/{id}/events (SSE) · /obstacle-inventories/{scope} · /platform/courses · /challenges* (R2)
S17  /platform/clubs · /platform/clubs/{id}/{definition|domains|theme|modules|payment-providers|parameters|catalog-clone|admins|status|support-access|public-api-key|usage|checklist} · /platform/accounts/{id}/platform-roles · /platform/club-templates · /internal/domains/allowed
S18  /platform/clubs/{id}/migrations (opcional)
S19  /me/products · /learn/recommendations · /challenges/{id}/attempts · /me/challenge-attempts · /platform/challenges · /me/sharing (R2)
```
Els codis d'error de totes les specs són a `CATALEG_ERRORS.md`.

## 4. Llistats: paginació, filtre universal, ordenació, vistes

Tots els llistats d'escriptori (D5, D15, D6, D7, D9, D14…) comparteixen el mateix contracte:

```
GET /members?page=0&size=50&sort=lastName,asc&sort=firstName,asc
    &q=text lliure
    &filter=status:eq:ACTIVE&filter=planId:in:p1,p2&filter=nextInvoiceDate:lte:2026-10-01
    &fields=id,number,fullName,dogs,status
```
- `filter=<camp>:<op>:<valor>` repetible; operadors `eq, ne, in, nin, lt, lte, gt, gte, contains, startsWith, exists, between`. Els camps filtrables/ordenables de cada recurs es declaren a l'OpenAPI (`x-filterable`, `x-sortable`) i el back **rebutja** la resta amb `400 INVALID_FILTER`.
- `GET /{recurs}/filter-values?field=planId` → valors possibles amb recompte (per al botó de filtre universal).
- Resposta: `{ items: [...], page, size, totalItems, totalPages, appliedFilters: [...] }`.
- Vistes desades: `/saved-views` (`listKey`, `name`, `columns[]`, `filters[]`, `sort`, `shared`).
- Exports: `GET /{recurs}/export?format=xlsx|pdf&…mateixos paràmetres` → fitxer (job síncron fins a 5.000 files; per sobre, `202` + `/exports/{jobId}`).

## 5. Formats

- **Dates**: instants en ISO-8601 UTC (`2026-10-05T18:30:00Z`); dates de negoci `YYYY-MM-DD`; hores locals `HH:mm`; mesos `YYYY-MM`. L'API **també** retorna, on cal per a la UI, la data/hora local del club (`startsAtLocal`) i el `timeZone` del club a `/branding`.
- **Money**: `{ "amountMinor": 6000, "currency": "EUR" }` (mai decimals en JSON).
- **LocalizedText**: a lectura, l'API retorna **el text resolt** al `locale` de l'usuari (`name: "…"`) **i** el mapa complet quan el recurs és editable pel backoffice (`nameI18n: {ca, es}`); a escriptura s'envia el mapa.
- **Ids**: UUID v4 com a string. **Enums**: UPPER_SNAKE_CASE, valors documentats a l'OpenAPI.
- **Fitxers**: pujada per **URL signada** (`POST /attachments/upload-url` → `{uploadUrl, fileKey}`; després `POST /attachments` amb `fileKey`); descàrrega per URL signada de curta durada. Límits per paràmetre (`files.maxSizeMb`, tipus MIME permesos).

## 6. Errors

```json
{ "code": "BOOKING_LIMIT_REACHED", "message": "Ja tens 2 classes aquesta setmana", "details": { "limit": 2, "current": 2 }, "traceId": "…" }
```
- `400` validació (`VALIDATION_ERROR` + `fieldErrors[{field, code, message}]`) · `401` no autenticat · `403` sense permís · `404` no trobat **o mòdul desactivat** · `409` conflicte de negoci (límits, plaça plena, estat invàlid) · `422` regla de negoci no complerta amb dades vàlides · `429` rate limit.
- **Tots els codis** de negoci viuen a `ErrorCode` (enum) i tenen missatge localitzat al back (`messages_{locale}.properties`) — el front tradueix per `code` i cau al `message` del back.
- Els errors de límits de reserva porten `details` suficients perquè la pantalla 06/29 mostri el cas (reserves vives anul·lables, etc.).

## 7. Idempotència i concurrència

- Mutacions que poden repetir-se per reintents del mòbil (`POST /bookings`, `/training-bookings`, `/waitlist-entries/{id}/claim`, `/checkout-sessions`) accepten `Idempotency-Key` (UUID del client, 24 h); la segona crida retorna la mateixa resposta.
- Recursos editables al backoffice porten `version` (optimistic locking, `409 STALE_VERSION`).
- Transaccions Mongo als fluxos: reserva amb SeatHold, consolidació de plaça, anul·lació de classe amb inscrits, validació de setmana, generació i retrocés de remesa, alta→validació.

## 8. Esdeveniments, notificacions i temps real

- Tota mutació de negoci emet esdeveniments del `CATALEG_ESDEVENIMENTS.md` a l'outbox (mateixa transacció). Les notificacions **no** s'envien des dels services: les envia el consumidor de l'outbox segons `CATALEG_NOTIFICACIONS.md`.
- Temps real (opcional a R1, obligatori per a sessions de muntatge live): `GET /build-sessions/{id}/events` (SSE). La resta de pantalles fan *polling* curt o refetch en focus.

## 9. Seguretat bàsica

- Rate limit per IP i per compte a `ANON` i a `/oauth2/token`; CORS només per als hosts de club registrats + `id.*`; headers de seguretat (HSTS, CSP per a les SPAs); logs estructurats amb `traceId`, `clubId`, `accountId`; secrets només en variables d'entorn; claus Stripe xifrades a la BBDD.
- Webhooks Stripe: verificació de signatura + idempotència per `event.id` (`stripe_events`).

## 10. Tests mínims per endpoint (DoD, PLA_BACKEND §9)

Camí feliç · validació (`400`) · autorització per rol (`403`) i tenant creuat (`404`) · mòdul desactivat (`404`) quan escaigui · estat invàlid (`409/422`) · esdeveniments emesos (assert a l'outbox) · contracte OpenAPI actualitzat.
