# Pla de backend — `agilityhub-core-api`

**v2.0 · 03-09-2026** (substitueix la v1.0) · Referència d'estil: Avanta Spring API (skill `avanta-spring-dev`, adaptada com a `core-api-dev`) · Model: `MODEL_DADES_CANIC.md` v1.6 + `MODEL_DADES_PLATAFORMA.md` · Specs: `specs/S01–S20` · Convencions: `specs/00-transversal/CONVENCIONS_API.md` · Marc: `PLA_DESENVOLUPAMENT.md` v2

## 1. Base tècnica

- **Spring Boot 3.5.x · Java LTS (la d'Avanta) · Maven · MongoDB 7+ (replica set, transaccions)** · Spring Authorization Server (OIDC) · springdoc OpenAPI 3.1 · Testcontainers · JaCoCo · PITest (mòduls crítics).
- Paquet base `com.agilityhub.core`. Context path `/api/v1` (+ `/oauth2`, `/.well-known`, `/connect` per a l'ID; `/webhooks`; `/internal`).
- **Contextos delimitats** (paquets de primer nivell), cadascun amb les capes Avanta `api/ · application/ · domain/ · persistence/`:

```
identity/     Account, Membership, tokens, OIDC clients, grants (S01)
platform/     Club, Parameter(+Catalog), CountryProfile, ClubConfig cache, consola, club-as-code, jobs (S02, S15, S17), audit + exports + RGPD (S14)
clubs/        census (S03, S04, S13) · catalogs (S05) · scheduling (S06) · activities (S07) · bookings (S08) · training (S09) · followup (S10) · messaging (S11)
courses/      Course, Ring geometry, Placement, RingSetup, BuildSession, Inventory, Challenge (S16, S19)
payments/     Invoice, Collection, Remittance, UpfrontPayment, PackBalance, providers SEPA/Stripe/Manual (S12)
migration/    Playoff (S18)
shared/       Money, LocalizedText, TenantContext, repositoris base, outbox, errors, i18n (MessageSource ICU), security helpers
configuration/ · utilities/
```
Regla de dependència: `clubs` → (`identity`, `courses`, `payments`, `platform`) només per interfícies d'`application`; cap context depèn de `clubs`; `shared` no depèn de ningú. Un test d'arquitectura (ArchUnit) ho verifica.

- **Convencions d'entitat** (Avanta): `@Document` amb `id` UUID string, `createdAt`, constructor no-arg protegit + constructor des de DTO amb `InvalidParamException`, `updateContent(dto)`, `toDTO()`, `@Indexed`, subdocuments per a línies/estats, referències per id. **Noms en anglès del glossari** (`MODEL_DADES_PLATAFORMA.md` §0).
- **Secrets**: `application.properties` només amb placeholders; valors per variables d'entorn; claus Stripe dels clubs xifrades a la BBDD (AES-GCM, clau `PLATFORM_KMS_KEY`).
- **Diners**: `Money {amountMinor: long, currency}`; mai `double`. **Temps**: instants UTC; dates de negoci `LocalDate`/`YearMonth`; `ZoneId` del club per a llindars i processos; `Clock` injectable a tot arreu.

## 2. Multi-tenant i configuració (E0, no es toca més)

- `TenantContext` per petició: host (`X-Club-Host`) per als anònims, claim `clubId` per als autenticats (`403 TENANT_MISMATCH` si difereixen). `TenantRepository<T>` injecta `clubId` a totes les queries; `GlobalRepository<T>` només per a `accounts`, `clubs`, `oidc_clients`, `courses` públics, `challenges`, `AgilityHubLevel`.
- Prohibit acceptar `clubId` del client; `/platform/*` sense tenant amb rol `AGILITYHUB_ADMIN`.
- `ClubConfigService.get(clubId)`: club + paràmetres resolts (`ParameterCatalog` defaults ⊕ overrides) + mòduls + perfil de país + tema; cache 60 s invalidada per esdeveniments (S02).
- `@RequiresModule("X")` (aspecte) → `404 MODULE_DISABLED`; els schedulers comproven mòduls abans d'actuar.
- **Club-as-code**: `club:apply` (S17) és l'única via de seed; `club-canic`, `club-minim`, `club-template-default`, `demo-seed` (cens fictici coherent amb els mockups: Laura + Duna/Rock, Marc + Chun-li, Estel instructora…).

## 3. Identitat i seguretat (S01)

- Spring Authorization Server: grants `password`, `refresh_token`, `authorization_code` + PKCE, personalitzats `urn:agilityhub:grant:magic-link` i `urn:agilityhub:grant:handoff`; JWT RS256 amb `kid` rotatiu, JWKS; claims de context de club; impersonació (`imp`, `actorAccountId`, `impersonatedMemberId`) i accés de suport (`support`).
- Autorització: `@PreAuthorize` per rol al controller + pertinença del recurs al servei (MATRIU_PERMISOS); tokens `imp` rebutjats als endpoints ADMIN/INSTRUCTOR.
- Rate limiting (Bucket4j) per IP i compte als públics i a `/oauth2/token`; `SecurityEvent` (S14).
- bcrypt `$2y$` acceptat (importació de Learn) + argon2id per a contrasenyes noves.

## 4. Mòduls i col·leccions (mapa model → codi)

| Context / mòdul | Col·leccions | Spec | Notes clau |
|---|---|---|---|
| `identity` | `accounts`*, `memberships`, `magic_link_tokens` (TTL), `refresh_tokens`, `impersonation_grants`, `oidc_clients`* | S01 | * globals |
| `platform` | `clubs`*, `parameters`, `audit_entries`, `domain_events` (outbox), `job_runs`, `job_locks`, `export_jobs`, `security_events`, `erasure_requests`, `migration_runs`, `idempotency_records` (TTL) | S02, S14, S15, S17, S18 | |
| `clubs/census` | `members`, `dogs`, `family_groups`, `dog_documents`, `inactivity_periods`, `leave_requests`, `saved_views` | S03, S04, S13 | estats, bloqueig, consentiments, `sourceIds` |
| `clubs/catalogs` | `levels`, `rings` (+`geometry`), `instructors`, `plans`, `prices`, `faq_entries` | S05 | lliures per club; `Price` amb vigència i supersessió |
| `clubs/scheduling` | `week_templates`, `weeks`, `class_sessions`, `ring_blocks` | S06 | validació de setmana sencera; anul·lació transaccional |
| `clubs/activities` | `activities`, `activity_registrations` | S07 | blocs de pista sincronitzats |
| `clubs/bookings` | `bookings`, `seat_holds` (TTL), `seat_locks`, `waitlist_entries`, `attendances` | S08, S10 | concurrència: `seat_locks` + transaccions |
| `clubs/training` | `training_bookings` (slots calculats) | S09 | índex únic parcial per slot |
| `clubs/followup` | `tasks`, `attachments`, `followup_items`, `followup_read_marks` | S10 | |
| `clubs/messaging` | `message_templates`, `notifications`, `push_subscriptions` | S11 | matriu canals×públics ∩ preferències ∩ mòduls |
| `courses` | `courses` (club/global), `placements`, `ring_setups`, `build_sessions`, `obstacle_inventories`, `challenges`*, `challenge_attempts` | S16, S19 | model JSON validat amb JSON Schema de `course-core` |
| `payments` | `invoices`, `collections`, `remittances`, `billing_runs`, `billing_simulations`, `upfront_payments`, `pack_balances`, `pending_charges`, `stripe_events`, `billing_locks` | S12 | append-only; `PaymentProvider` SEPA/Stripe/Manual |

## 5. Processos programats (S15)

Marc comú: tick per minut → clubs on toca segons **hora local** → `job_locks` (lease) → execució idempotent → `job_runs` + `SchedulerRun`; interruptor `jobs.<nom>.enabled` per club; simulació (`dryRun`) i execució manual (`POST /jobs/{name}/trigger`); catch-up per finestra; alertes en fallada.

| Procés | Quan (local del club) | Fa |
|---|---|---|
| P1 Obertura d'inscripcions | `bookings.weekOpensAt` (dg 20:00) | `WeekOpened`, `TrainingCounterReset`, N-33 opcional |
| P2 Revisió de classes en risc | `classes.riskReviewTime` (7:30), `+2` dies vista | dia mateix: anul·lació (`ClassAutoCancelled`, N-17 + N-08a); dies següents: `ClassAtRisk` (N-16) |
| P3 Avisos de no presentats | `messaging.noShowNoticeTime` (8:00) | N-19 als marcats el dia abans |
| P4 Recordatoris | cada minut | N-13 segons antelació de l'abonat (classes i entrenaments) |
| P5 Venciments | `jobs.dailyTime` (6:00) | packs (avís/caducitat → S13 baixa prevista), inactivitats (inici/fi), baixes efectives, altes pendents (N-34), cartilles, muntatges caducats |
| P6 Llista d'espera FIFO | cada minut (`waitlist.mode=FIFO`) | `WaitlistExpired` → següent |
| P7 Pagaments pendents | cada minut (`SINGLE_CLASS`) | `PAYMENT_PENDING` caducats |
| P8 Finalització de classes | horari | `ACTIVE → FINISHED` (+ gràcia) |
| P9 Neteja | diari | TTLs, exports, esdeveniments processats, sessions de muntatge obertes, reverificació de dominis |
| P10 Recordatori de remesa | `billing.remittanceReminderDay` | N-41 als admins si no hi ha remesa del mes |

## 6. Integracions (`application/integrations` + `utilities/`)

- **Email transaccional** (E1, ADR-005): `EmailSender` (SendGrid candidat) + webhook de bounces; plantilles Thymeleaf per idioma amb la marca del client/club.
- **SMS Twilio** (E7): `SmsSender`; a tots els telèfons; comptador per club i tall `messaging.sms.monthlyCap`.
- **Web push** (E7): VAPID propi; `PushSubscription`; 410 → baixa.
- **S3** (E2+): prefix per club; URLs signades; MIME/mida per `files.*`; esborrat en pseudonimitzar.
- **Stripe** (E8): `StripePaymentProvider` (Checkout, SetupIntent, PaymentIntent off-session, refunds, webhooks signats per club) + `FakePaymentProvider`.
- **SEPA pain.008** (E8, ADR-006): JAXB generat de l'XSD oficial; validació XSD; golden files.
- **DNS** (E10): resolutor per a la verificació de dominis; `/internal/domains/allowed` per a Caddy.
- **Learn** (E1/R2): importació de comptes; endpoints `/platform/accounts` per a l'adaptador Laravel; client credentials per a recomanacions (R2).
- **Sentry** (back) + logs estructurats (`traceId`, `clubId`, `accountId`) + mètriques dels jobs.

## 7. API i contracte amb el front

`CONVENCIONS_API.md` és la norma (rutes, llistats universals amb `x-filterable`/`x-sortable`/`x-columns`, formats, errors amb `ErrorCode`, idempotència, `version`). OpenAPI 3.1 generat i **validat a CI contra l'esperat** (diff): un canvi de contracte = PR coordinat. Agregats per pantalla (`/me/home`, `/me/bookable-classes`, `/day-grid`, `/dashboard`, `/instructor/day`, `/instructor/week`, `/billing/periods/{period}`) per evitar càlcul al front.

## 8. Ordre d'implementació (segueix les etapes del pla general; paquets = §12 de cada spec)

**E0** — esquelet per contextos · `shared` (Money, LocalizedText, Tenant, repositoris, outbox + dispatcher, errors, i18n) · `platform` (Club, ParameterCatalog complet, CountryProfile ES/GENERIC, ClubConfig, `/branding`, club-as-code + seeds) · `identity` esquelet (grant `password`) · `@RequiresModule` · `@Audited` base · CI (build, tests, JaCoCo, ArchUnit, diff OpenAPI) · Dockerfile + compose · `DEPLOY.md` inicial.
**E1** — `identity` complet (S01 WP-A…E) · `EmailSender` (ADR-005) · N-25/26/27 · `identity:import-learn`.
**E2** — `clubs/census` (S03) · `clubs/catalogs` (S05) · `/parameters` + D11 (S02 WP-B) · auditoria + exports (S14 WP-B/C) · `migration` cens en dry-run (S18 WP-A/B).
**E3** — alta pública i validació (S04) · `/dashboard` (S14 WP-A) · Checkout amb doble.
**E4** — `clubs/scheduling` (S06) · `clubs/activities` manteniment (S07).
**E5** — `clubs/bookings` + `training` (S08, S09) · inscripcions a activitats · marc de jobs + P1/P6/P7/P9 (S15) · k6.
**E6** — `clubs/followup` + assistència (S10) · P3/P8.
**E7** — `clubs/messaging` complet (S11) · SMS/push · P4.
**E8** — `payments` (S12: run, SEPA, Stripe, packs, classe individual, exports) · inactivitat/baixa (S13) · P5/P10 · `migration` facturació (S18 WP-C).
**E9** (fil C, des d'E0) — `courses` (S16 WP-C/D/G) després de WP-16-0 i `course-core`.
**E10** — `platform` consola (S17 WP-B/D).
**E11** — RGPD (S14 WP-D) · hardening · backups · assaig de migració · PITest als mòduls crítics.
**E12** — tall (S18 WP-E) · `DEPLOY.md` definitiu.

## 9. Testing exhaustiu del backend (requisit de qualitat, Jordi 03-09)

Regla d'or: **cap PR es fusiona sense els tests del paquet al mateix PR**; la suite completa passa a CI a cada PR. Cada spec porta els seus `T-xx-nn`: el PR els cita.

1. **Unitaris de domini** (sense Mongo): validacions de constructors/`updateContent`, màquines d'estat completes (Member, Dog, ClassSession, Week, Booking, SeatHold, WaitlistEntry, Attendance, Task, Invoice, Collection, Remittance, BillingRun, UpfrontPayment, PackBalance, InactivityPeriod, LeaveRequest, RingSetup, BuildSession, Account, tokens), càlculs purs (cobertura amb llindars, límits BR-01 per `DOG`/`MEMBER`, proporcionals, quota inicial, quota d'inactivitat, numeració, rounding, caducitat de packs, `placeInRing` és al front), `ParameterCatalog` = document (T-02-03).
2. **Integració amb Testcontainers** (replica set real): un test per **endpoint** (camí feliç + validació + rol permès + **cada** rol denegat + tenant creuat `404` + mòdul-off `404` on escaigui) i per servei amb efectes creuats (anul·lació de classe amb inscrits, consolidació de plaça, generació/validació de setmana, run de facturació amb retrocés, alta → validació → compte, «ha avisat» → alliberament → avís d'espera), amb assert dels **esdeveniments a l'outbox** i de les **AuditEntry**.
3. **Aïllament de tenant**: per a cada mòdul, club A no llegeix ni escriu res del club B; `GlobalRepository` només on toca.
4. **Seguretat**: matriu rol×endpoint (ANON/MEMBER/INSTRUCTOR/ADMIN/AGILITYHUB_ADMIN/`imp`/`support`); impersonació amb claims i auditoria; rate limits; webhooks amb signatura invàlida; tokens rotats/reutilitzats.
5. **Schedulers**: cada procés amb `Clock`, **dos clubs en fusos diferents**, DST (25-10-2026), idempotència (dos cops = un efecte), catch-up i finestra perduda, dry-run.
6. **Concurrència**: última plaça amb executors paral·lels, doble claim, doble confirmació, doble generació de setmana, doble run de facturació, mateix slot d'entrenament, dos instructors desant llista.
7. **SEPA i facturació**: pain.008 contra XSD + golden files byte a byte (`Clock` fixat); immutabilitat (cap `update`/`delete` sobre emesos); retrocés amb numeració; Stripe amb `FakePaymentProvider` i esdeveniments duplicats/fora d'ordre.
8. **Notificacions**: per cada codi N-*, matriu canals×públics ∩ preferències ∩ mòduls; renderitzat en `ca/es/en` (snapshots); proveïdors sempre amb dobles.
9. **Contracte**: diff de l'OpenAPI a CI; `ErrorCode` complet; `AuditContractTest` (tota `AuditAction` coberta, S14); ArchUnit per als contextos.
10. **Mòduls i regles**: per a cada mòdul, un test amb el mòdul off; per a cada regla variant (`waitlist.mode`, `bookings.limitUnit`, `classes.maxInstructorsPerClass`, `levels.enabled`), totes les branques.
11. **i18n**: `LocalizedText` fallback, `Accept-Language`, snapshots de correus/SMS, `MessageSource` complet en tres idiomes (test que falla si falta una clau).

**Llindars i eines**: JaCoCo `domain` + `application` ≥ **85 %** línies / ≥ 80 % branques; `api` ≥ 70 %; build vermell per sota. Els percentatges són el terra: tota regla `R-xx-nn` té almenys un `T-xx-nn` (comprovat per script a CI sobre les specs vs els noms de test). PITest a `bookings`, `payments`, `identity` abans del go-live.

**Fixtures/seed**: `club-canic` (catàlegs + paràmetres) i `demo-seed` (cens fictici coherent amb els mockups) compartits per tests, dev, staging i demos; builders per entitat; **mai** dades ad-hoc repetides.

**Observabilitat**: logs estructurats; auditoria de tota escriptura sensible verificada per test; mètriques de jobs i de la cua d'esdeveniments; Sentry.
