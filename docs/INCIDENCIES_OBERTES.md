# Incidències obertes — registre de defectes

**v1.2 · 24-09-2026** (v1.1 10-09 · v1.0 09-09)

Registre de defectes trobats mentre es desenvolupa i que **no s'obren com a tasca del roadmap ara mateix** (decisió de Jordi, 09-09: primer acabem el desenvolupament, després fem una passada de correccions). Serveix perquè cap troballa es perdi pel camí i perquè la fase de correccions tingui la llista feta.

**Com funciona.** Cada incidència té ID `INC-nn`, la reproducció exacta, què s'espera i on mirar. Quan s'obri com a tasca del roadmap s'hi anota l'ID de la tasca i passa a `resolta` quan l'organitzador la verifica. Res d'això és una tasca `ready`: l'executora no les veu fins que no les obrim.

**Quan es corregeixen.** Fase de correccions al final del desenvolupament, abans de la porta d'E10 (desplegament) — les de gravetat **alta** bloquegen aquella porta, no les etapes intermèdies.

| ID | Data | Àmbit | Títol | Gravetat | Estat |
|---|---|---|---|---|---|
| INC-01 | 09-09 | api | `GET /api/v1/health` retorna `500` amb el `main` actual | **Alta** | **tancada 10-09 (ambiental)**: el `500` venia d'un procés Java local antic a `[::1]:8080` (PID 36366), no del contenidor; `127.0.0.1:8080` → `200 UP` amb BBDD buida i amb `Host: app.agilitycanic.cat`. **E3-T06 ✅** deixa la regressió `T_02_01_INC01_healthNeedsNoTenantLocaleAccountOrSeedData` (health sense tenant/locale/compte/dades) i la checklist amb `curl -4`. Acció de Jordi abans de la porta E0: matar el procés (`lsof -nP -iTCP:8080 -sTCP:LISTEN`) |
| INC-02 | 09-09 | api | Els `500` no deixen cap traça al log | **Alta** | **resolta 10-09 (E3-T06 ✅)**: `INTERNAL_ERROR` 500 amb `traceId` + una línia `ERROR` amb la traça sencera i el mateix `traceId`; les excepcions del framework conserven l'estat (405/406/415 nous al catàleg) |
| INC-03 | 09-09 | api · infra | El healthcheck del compose apunta a l'actuator (8081), no a l'endpoint real | Mitjana | **tancada 10-09 (no confirmada)**: la imatge i el `Dockerfile` ja comprovaven `/api/v1/health`; E3-T06 ✅ fa explícit el healthcheck a `compose.yaml` i `docker-compose.consumer.yml` (endpoint públic, 8080) i prova sana → insana → sana |
| INC-04 | 09-09 | api · infra | `compose.yaml` publica el port `27017` fix | Baixa | **resolta 10-09 (E3-T06 ✅)**: `MONGO_PORT` (amb `MONGODB_PORT` de reserva) a les dues variants del compose, documentat al README |
| INC-05 | 09-09 | docs | La checklist de la porta E0 té la comanda del seed desactualitzada | Baixa | **resolta 09-09** (`E0-fonaments.md` corregit); es manté com a recordatori de procés |
| INC-06 | 09-09 | api · contracte | `TokenResponse.scope` s'omet quan l'abast concedit és buit, però l'OpenAPI el marca `required` (trobat a E1-W04 contra la imatge real; el front ho normalitza a `""`) | Baixa | **resolta 10-09 (E3-T06 ✅)**: `scope: ""` sempre present a la resposta del token (`TokenScopeIT`) |
| INC-07 | 24-09 | api · web (auth) | El `refresh_token` respon `400` de manera intermitent a l'e2e contra el core real | **Alta** (provisional) | oberta — **E3-W04** (pas 6) en captura el cos de l'error; diagnosi pendent |
| INC-08 | 24-09 | api · contracte | Les respostes serialitzen `null` en camps opcionals que l'OpenAPI no declara `nullable` | Mitjana | oberta — el front ho tolera a D11 (E3-W03) i a D2 (E3-W04) |
| INC-09 | 24-09 | api · contracte | `RING_HAS_BOOKINGS.details.bookings[]` té dues formes segons la ruta | Baixa | oberta — el front mostra només `memberName` + `dogName` (E4-W02) |

---

## INC-01 · `GET /api/v1/health` retorna `500` amb el `main` actual

**Gravetat**: alta. Bloqueja la **porta E0** (és un punt de la checklist) i, sobretot, el desplegament: és l'endpoint amb què staging i producció comprovaran que el servei és viu.

**Regressió confirmada.** `E0-T02` es va verificar el 06-09 amb aquesta evidència exacta: `docker compose up -d --wait && curl -fsS localhost:8080/api/v1/health` → JSON `UP`. El 09-09 la mateixa comanda falla.

**Reproducció** (09-09, 19:20, Mac de Jordi, `main` amb E1 i E2 fusionades):

```
docker compose down -v
docker compose up -d --build --wait
curl -sS -i localhost:8080/api/v1/health
```

**Resultat**:

```
HTTP/1.1 500
Content-Type: application/json
{"status":500,"error":"Internal Server Error","message":"internal server error","path":"/api/v1/health","timestamp":"2026-09-09T19:20:15.672127"}
```

**Descartat durant el diagnòstic**:

- No és imatge ni volum vells: passa amb `--build` i amb els volums acabats de crear.
- No és Mongo: el contenidor arrenca sa, `rs.status().myState` = `1` i el driver connecta amb `REPLICA_SET_PRIMARY`.
- No és l'arrencada: `Started CoreApplication in 2.578 seconds`, sense cap error als logs.
- No és la resolució de tenant per host desconegut: amb `-H 'Host: app.agilitycanic.cat'` dona el mateix `500`.
- El cos de la resposta és l'**envoltori d'errors propi de l'aplicació** (E0-T04), o sigui que l'excepció la recull el manejador global; no és un error de Tomcat.

**Pendent de comprovar** (no s'ha arribat a fer): aplicar el seed i repetir. La base de dades era buida en totes les proves, i el `club:apply` va fallar per `SEED_PASSWORD` (vegeu INC-05):

```
SEED_PASSWORD='Test1234!' ./bin/core club:apply seeds/club-canic.yaml
curl -sS -i localhost:8080/api/v1/health
```

**Hipòtesi**: `/api/v1/health` passa pel filtre de tenant (R-02-01) i peta quan no hi ha cap club a la base de dades, en lloc de saltar-se'l. Un endpoint de salut no hauria de dependre de dades de negoci: ha de respondre encara que la base de dades sigui buida — és la primera cosa que es crida en un desplegament nou.

**Comportament esperat**: `200` amb `UP` sempre que el servei i Mongo estiguin vius, amb independència del host i del contingut de la base de dades. Si es decideix que ha de ser sensible al tenant, aleshores host desconegut → `404 UNKNOWN_HOST` (R-02-01), mai `500`.

**On mirar**: el filtre/interceptor de tenant i la llista de rutes exemptes · el controlador de `/api/v1/health` · `E0-T02` (definició de l'endpoint), `E0-T05` (tenant), `E0-T04` (envoltori d'errors).

**Nota de procés**: la CI és verda amb 601 tests i, tot i així, l'aplicació composada falla al seu endpoint més bàsic. Els tests no cobreixen el camí «compose aixecat + petició des de fora». Val la pena afegir un test d'humo del compose a la CI quan es corregeixi.

---

## INC-02 · Els `500` no deixen cap traça al log

**Gravetat**: alta. Sense traça, qualsevol incidència a staging o producció és indepurable.

**Reproducció**: provocar l'INC-01 i buscar l'excepció:

```
docker compose logs api 2>&1 | grep -iE "exception|caused by" | head -20
```

**Resultat**: cap línia. Els logs salten de l'arrencada a la inicialització del `DispatcherServlet` i no registren res de la petició que ha retornat `500`.

**Comportament esperat**: el manejador global d'errors (E0-T04) ha de registrar a `ERROR` la traça completa de qualsevol excepció no controlada, amb l'identificador de correlació que ja retorna al cos si n'hi ha. Els errors de negoci esperats (4xx) poden quedar-se a `DEBUG`/`WARN`, però un `500` mai és silenciós.

**On mirar**: `@RestControllerAdvice` / `ErrorHandler` d'E0-T04 · configuració de logging del perfil `local`.

---

## INC-03 · El healthcheck del compose apunta a l'actuator, no a l'endpoint real

**Gravetat**: mitjana.

**Evidència**: amb `/api/v1/health` retornant `500`, `docker compose up --wait` reporta `Container agilityhub-core-api-api-1  Healthy`. Els logs mostren l'actuator al port **8081** («Exposing 3 endpoints beneath base path '/actuator'»), que és intern del contenidor.

**Per què importa**: un healthcheck que no toca el que fan servir els clients dona falsos verds. Ha estat exactament el cas d'avui: el compose deia «sa» mentre l'API no responia. A staging això vol dir desplegar una versió trencada sense adonar-se'n.

**Comportament esperat**: el healthcheck del servei `api` ha de comprovar `GET /api/v1/health` al port 8080 (l'actuator pot quedar-se com a comprovació addicional).

**On mirar**: `compose.yaml`, servei `api`, secció `healthcheck` · `E0-T02`.

---

## INC-04 · `compose.yaml` publica el port `27017` fix

**Gravetat**: baixa (fricció de desenvolupament, no defecte de producte).

**Evidència**: `docker compose up` falla amb `ports are not available: ... 127.0.0.1:27017: bind: address already in use` en qualsevol màquina amb un Mongo local (al Mac de Jordi, el `mongodb-community` de Homebrew com a `LaunchDaemon`).

**Comportament esperat**: `${MONGO_PORT:-27017}:27017`, així qui tingui un Mongo local arrenca amb `MONGO_PORT=27018 docker compose up`. Dues línies.

**Solució temporal**: aturar el Mongo local (`sudo launchctl bootout system/homebrew.mxcl.mongodb-community`) o un overlay fora del repo amb `ports: !override`.

**On mirar**: `compose.yaml` · `E0-T02` · `README.md` («Run locally»).

---

## INC-05 · La checklist de la porta E0 té la comanda del seed desactualitzada

**Gravetat**: baixa (documentació).

**Evidència**: `E0-fonaments.md`, punt de la porta E0, diu `bin/core club:apply seeds/club-canic.yaml`. Executat el 09-09 respon `Set SEED_PASSWORD or omit accounts[].password for passwordless accounts`: `E1-T09` va afegir els comptes ficticis al `club:apply` i la variable `SEED_PASSWORD`, i la checklist no es va actualitzar.

**Comportament esperat**: `SEED_PASSWORD='...' ./bin/core club:apply seeds/club-canic.yaml`. Corregit a `E0-fonaments.md` el 09-09; queda apuntat aquí com a recordatori que **quan una tasca canvia una comanda, cal repassar les checklists que la citen**.

---

## INC-06 · `TokenResponse.scope` absent quan l'abast és buit (contracte)

**Gravetat**: baixa (el front ho normalitza; només afecta clients generats estrictes).

**Reproducció** (E1-W04, 09-09, imatge `ghcr.io/jboixtcm/agilityhub-core-api:main`): `POST /oauth2/token` amb `grant_type=password` per a un compte sense abasts concedits → el cos JSON no porta la clau `scope`; `docs/openapi/openapi.json` declara `TokenResponse.scope` com a `required`.

**Comportament esperat**: serialitzar sempre `scope` (`""` quan és buit), o bé declarar-lo opcional a l'snapshot i regenerar el client del web (`packages/api-client`). Preferible el primer (no trenca cap consumidor).

**On mirar**: serialització de la resposta del token a `identity` (E1-T02/E1-T13) i `OpenApiSnapshotTest`.

## INC-07 · `refresh_token` intermitent amb `400` (e2e contra el core real)

**Gravetat**: alta provisional. Si la causa és una rotació perduda, un usuari real perd la sessió, perquè la reutilització d'un token ja rotat revoca tota la família.

**Reproducció** (E3-W03 ronda 2, 24-09, imatge `9e3a9c6`): `pnpm e2e:core`, etapa 1, 2 de 6 execucions:
- `12a`: T-03-42, `loginMember`. El `refresh_token` que segueix el login respon `400`.
- `12e`: T-01-20, `restoreAtRoute`. El `refresh_token` en carregar la ruta respon `400`.

Les mateixes proves passen a les altres execucions, i la ronda no va tocar cap codi d'autenticació. Logs a `agilityhub-core-web/roadmap/evidence/E3-W03/12a-e2e-core-failed.log` i `12e-e2e-core-failed.log`. Cap dels dos logs porta el cos de la resposta.

**Hipòtesi**: el core rota el refresh token a cada ús i tracta la reutilització d'un token ja rotat com un robatori (`RefreshTokenRepository.rotate` + revocació de la família).
- Si el navegador avorta un refresc que el core ja ha processat (una navegació, una pestanya tancada, l'app mòbil en segon pla), la galeta nova no arriba mai.
- El refresc següent porta el token vell → `400` i sessió revocada.
- No és una cursa dins d'una pestanya: `AuthClient.refresh` ja fa *single-flight*.

**Comportament esperat**: un refresc avortat no fa perdre la sessió. L'opció habitual és un marge curt de reutilització (10–30 s):
- dins del marge, el token pare ja rotat s'accepta un cop més i emet un fill nou, sense revocar la família (el *reuse interval* d'Auth0, el *grace period* d'Okta);
- fora del marge, la reutilització continua revocant la família.

Decisió (S01) quan tinguem el cos de l'error.

**On mirar**:
- `identity/persistence/RefreshTokenRepository.java` (`rotate`, `revokeFamily`) i el `refresh_token` grant del core;
- `packages/auth/src/auth-client.ts` (`refresh`, i `startSlidingRefresh`, que refresca en `focus`);
- l'`oauth-token-calls.log` que deixa E3-W04 a cada execució de `pnpm e2e:core`.

## INC-08 · `null` en camps opcionals no `nullable` (contracte)

**Gravetat**: mitjana. El client generat tipa aquests camps com a `T | undefined`, i un `null` trenca el front allà on compara amb `undefined`. A E3-W03 va amagar files de D11.

**Reproducció** (E3-W03 ronda 2, imatge `9e3a9c6`):
- `GET /parameters` → `module: null` als paràmetres sense mòdul;
- `GET /members/{id}/signup` → `member.plan: null`, `member.planId: null`, `paymentMethod.channel: null`, `maskedAccount: null`.

Captures: `agilityhub-core-web/roadmap/evidence/E3-W03/d11-signup-parameter-core.json` i `d2-signup-view-core.json`. L'OpenAPI declara aquests camps opcionals, no `nullable`.

**Comportament esperat**: un camp opcional absent s'omet, i `null` surt només on l'esquema diu `nullable`. És el patró que l'API ja fa servir registre a registre: `@JsonInclude(NON_NULL)`, i `ALWAYS` + `types = {"string","null"}` per als camps que admeten `null`.

Solució probable:
- inclusió `NON_NULL` per defecte a l'`ObjectMapper` de les respostes HTTP, sense tocar els *payloads* d'auditoria de R-14-10, que volen `null` per a l'absent;
- una prova que validi les respostes de les IT contra `docs/openapi/openapi.json`.

**On mirar**: la configuració de Jackson, `CatalogResponses`, `CensusResponses`, les vistes de signup (S04) i `OpenApiSnapshotTest`.

## INC-09 · `RING_HAS_BOOKINGS` amb dues formes de `details` (contracte)

**Gravetat**: baixa. Avui el front només en mostra `memberName` + `dogName`, que surten a totes dues formes. És una incoherència amb la regla 1 de `CATALEG_ERRORS.md` §3 (un codi = un significat), i un client que tipi els `details` es trobaria camps diferents per al mateix codi.

**Reproducció** (lectura del codi i de l'snapshot, 24-09, verificació d'E4-W02):
- les rutes d'S05 i S06 (`PATCH /rings/{id}`, `POST`/`PATCH /class-sessions`, `POST`/`PATCH /ring-blocks`) envien `bookings[]` = `{id, ringId, from, to, memberName, dogName}` (`TrainingConflictPort.Booking`, `RingTrainingBookings.Booking`);
- l'OpenAPI publica `RingHasBookingsDetails` (S09) amb `bookings[]` = `SlotOccupant {bookingId, memberName, dogName}`.

**Comportament esperat**: una sola forma per al codi, publicada a l'OpenAPI i usada per totes les rutes. Suggeriment: `{bookingId, ringId, from, to, memberName, dogName}`, amb `ringId`, `from` i `to` opcionals si S09 no els té.

**On mirar**: `TrainingContracts.RingHasBookingsDetails`, `TrainingConflictPort`, `RingTrainingBookings`, `RingBlockService.resolve` i `ClassSessionService`.
