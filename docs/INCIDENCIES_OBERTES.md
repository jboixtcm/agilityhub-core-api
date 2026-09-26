# Incidències obertes — registre de defectes

**v1.4 · 26-09-2026** (v1.3 26-09 · v1.2 24-09 · v1.1 10-09 · v1.0 09-09)

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
| INC-10 | 24-09 | api · RGPD | Les altes rebutjades no tenen retenció: S14 R-14-16 (b) no està implementada | Mitjana | oberta — la purga és d'E11 (retenció i supressió); trobada a la revisió de la porta E3 |
| INC-11 | 26-09 | web | Diferències cosmètiques de les pantalles d'E3 respecte dels mockups (re-execució de la porta, E3-W09) | Baixa | oberta — passada de polit abans del llançament |
| INC-12 | 26-09 | api · proves | Detalls de la revisió d'E5-T22: còpies d'ítems de llista fetes a mà, l'ítem de `/platform/audit-entries` | Baixa | oberta — passada de correccions |
| INC-13 | 26-09 | api · definició de club | Revisió d'E5-T23: les instruccions d'`MANUAL` en blanc passen la validació, la regla R-17-05 només es comprova quan la definició les declara, noms i abast de dos tests | Baixa | oberta — passada de correccions |
| INC-14 | 26-09 | api · fitxers | Revisió d'E5-T24: la ruta signada es reconeix pel camí cru, P9 no neteja els fitxers `DOG_DOCUMENT` orfes de l'ADMIN, el test de T-05-07 no la cobreix sencera | Baixa | oberta — passada de correccions |
| INC-15 | 26-09 | api · migració i reserves | Revisió d'E5-T25: la migració desa un gos sense sexe quan Playoff en porta un de desconegut (S03 l'exigeix); detalls del contracte de reserves | **Mitjana** (el sexe; abans de migrar) · baixa (la resta) | oberta — passada de correccions, abans de cap migració real |

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

**Ja tenim el cos de l'error** (E3-W04 i E3-W05, 24-09, amb el registre de crides de E3-W04):
- T-01-22 (l'app `id` reprèn un flux d'autorització) envia un `refresh_token` **amb** la galeta i rep `400 {"code":"REFRESH_EXPIRED"}` 0,3–0,35 s després d'una rotació correcta. Es va repetir a les dues tasques (`E3-W04/oauth-token-calls.log`, i la línia 26 d'`E3-W05/oauth-token-calls.log`).
- La prova no comprova aquesta crida, i el login següent funciona.
- Els `400 REFRESH_EXPIRED` **sense** galeta són la sonda anònima esperada quan arrenca un context nou; no són l'error.
- Lectura de l'organitzador: sembla un token pare ja rotat presentat per segona vegada, que el core anomena `REFRESH_EXPIRED` en lloc de `REFRESH_REUSED`. Cal confirmar al codi si revoca la família (llavors l'usuari perdria la sessió), i si el marge de reutilització de sota ho resoldria.

Decisió (S01): a la passada de correccions, amb aquesta evidència.

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

## INC-10 · Retenció de les altes rebutjades (RGPD)

**Gravetat**: mitjana. Cap dada es perd ni s'exposa, però les dades d'una alta rebutjada es guarden indefinidament, i la política de privacitat dirà que s'esborren passat `rgpd.rejectedSignupRetentionDays`.

**Origen**: la revisió exhaustiva de la porta E3 (24-09), `backlog/revisio-porta-E3/REVISIO_PORTA_E3.md`, «Routed elsewhere».

**Comportament esperat** (S14 R-14-16, b): el procés mensual de retenció (S15) tracta les altes rebutjades (`leftReason = SIGNUP_REJECTED`) amb `leftAt + rgpd.rejectedSignupRetentionDays ≤ avui` com una supressió (`ErasureRequest{source: RETENTION}`, R-14-15). Amb la decisió E38, una readmissió rebutjada torna al seu motiu de baixa original i **no** entra en aquesta classe.

**On mirar**: el procés de retenció d'S15 i `ErasureExecutor` d'S14, quan s'implementin (E11).

---

## INC-11 · Diferències cosmètiques de les pantalles d'E3 respecte dels mockups

**Gravetat**: baixa. Cap no afecta el funcionament: són diferències visuals que la re-execució de l'auditoria de la porta E3 (E3-W09, 26-09) ha llistat, i que l'organitzador deixa per a una passada de polit abans del llançament. Els defectes de debò de la mateixa auditoria van a la tasca E4-W12.

**Origen**: `roadmap/evidence/E3-W09/screens.md` (web) i la revisió `roadmap/reviews/E3-W09-20260926-0118-claude.md` (#6).

**Llista**:
- 17: el sexe triat és un botó ple; el mockup el marca amb vora i «✓».
- 18: la nota «Grup trobat…» no té la ✓.
- 19: les opcions d'inici són botons de ràdio natius (el mockup té cercles de marca); en mode afegir gos, «tria quan vols començar:» encapçala una sola opció.
- 16: el camp del DNI/NIE no queda alineat amb el del passaport, perquè l'etiqueta del passaport ocupa dues línies.
- 13: el sexe en minúscula («mascle»); el fons del diàleg «＋ DOC.» només cobreix els primers 844 px de la captura de pàgina sencera.
- 01: «Recupera-la» no es veu com un enllaç, i «Encara no hi ets? Apunta-t'hi →» és tot taronja (el mockup té la pregunta en gris).
- D1 a 1280 px: les etiquetes dels KPI en negreta i en una línia pròpia; les files de risc i de preinscripcions ocupen dues línies o més; la llegenda del gràfic no té mostres de color; el fons de la barra lateral s'acaba abans del final de la pàgina.
- D2: el xip de WhatsApp en una línia pròpia; «Modalitat i tarifa» és un select natiu (el mockup té una píndola taronja); la icona dels botons (✎, ✓) i la de l'avís d'imatge queden damunt del text.

**On mirar**: les captures d'`roadmap/evidence/E3-W09/` al costat de `docs/pantalles/`.

---

## INC-12 · Detalls de la revisió d'E5-T22 (api)

**Gravetat**: baixa. No afecten el funcionament ni el contracte publicat: són deute de proves i de documentació. La revisió independent d'E5-T22 (26-09) els ha trobat, i l'organitzador els deixa per a la passada de correccions, perquè E5-T22 era l'últim seguiment d'E5. Els menors de la mateixa revisió van a la tasca E5-T24.

**Origen**: `roadmap/reviews/E5-T22-20260926-1457-claude.md` (api), detalls #8 i #9.

**Llista**:
- **Còpies d'ítems de llista fetes a mà.** Els registres d'ítem de llista nous copien a mà altres tipus, i cal mantenir cada còpia al dia a mà. Només la conformitat amb l'snapshot de `ListFieldsContractIT` en detectaria una deriva. Les parelles:
  - `ClassSessionListItem` i `ClassSession`;
  - `RingBlockListItem` i `RingBlock`;
  - `MemberInstructorListItem` i `MemberInstructorView`;
  - `ClassBookingItem` i `BookingListItem`.

  Proposta: una prova unitària que compari els noms de propietat de cada parella.
- **L'ítem de `/platform/audit-entries`.** `AuditEntryListItem` també és l'ítem d'aquesta operació, que encara és només contracte i ja no accepta `fields`. L'operació documenta, doncs, ítems que només exigeixen `id`. És inofensiu fins que s'implementi l'operació. Proposta: dir-ho a la descripció de l'operació, o donar-li un ítem sencer quan s'implementi.

**On mirar**: `SchedulingContracts`, `BookingContracts`, `InstructorContracts`, `AuditContracts.java` (prop de `:102`), `ListFieldsContractIT`.

---

## INC-13 · Detalls de la revisió d'E5-T23 (api, definició de club)

**Gravetat**: baixa. El seed del Cànic és correcte, i cap pantalla ni cap abonat no en surt afectat. Són casos límit d'una definició de club que escrigui la plataforma, i deute de proves. La revisió independent d'E5-T23 (26-09) els ha trobat, i l'organitzador els deixa per a la passada de correccions.

**Origen**: `roadmap/reviews/E5-T23-20260926-1530-claude.md` (api), el menor #1 i els detalls #2–#4.

**Llista**:
- **Instruccions en blanc.** La comprovació de l'idioma per defecte (S17 R-17-05, «`MANUAL`: instruccions amb `defaultLocale` obligatori») només mira que la clau hi sigui (`ClubDefinitionWriter.java:129-133`). Una definició amb `MANUAL.instructions: {ca: " ", es: "…"}` passa l'esquema i l'escriptor, i es desa tal com és. Aleshores:
  - qui llegeix en català no veu cap instrucció a la pantalla 19 ni a N-01, perquè `CensusClubSettings.manualInstructions("ca")` torna `null` i un text en blanc no cau a un altre idioma;
  - l'export deixa fora el text en blanc, i tornar a aplicar aquest export falla amb `REQUIRED`. Es trenca el viatge d'anada i tornada de R-17-01.

  Correcció: comprovar que el text no sigui en blanc, o un `"pattern": "\\S"` a `localizedText` de l'esquema. Un cas a `ClubDefinitionCodecTest` o a `ClubDefinitionsIT`.
- **Quan es comprova R-17-05.** Només quan la definició declara `instructions`. Dos casos passen:
  - una definició que canvia `club.defaultLocale` sense declarar `instructions` conserva les desades, que potser no tenen el nou idioma per defecte;
  - s'accepta un idioma fora de `club.locales` (per exemple `en` al Cànic, que és ca/es), mentre que les pàgines el refusen.

  Correcció: validar les instruccions resultants de la fusió, no el node declarat.
- **Noms i abast de dos tests** (AGENTS, regla 5):
  - `ClubDefinitionsIT.T_04_14_T_02_12_canicSeedCarriesItsCashPaymentInstructions` cita T-04-14 i no n'afirma res. Proposta: `R_04_10_T_02_12_T_17_01_…`;
  - `SignupSecurityFixesIT.R_04_19_R_04_06_aSubmissionRefusesAKeyRemovedFromTheReusedDogsOwnCard`: la meitat de `POST /me/dogs/signup` no aïlla la clau treta, perquè qualsevol clau que el gos reutilitzat ja tingui respon el mateix `400`. Proposta: afirmar-ho també amb la clau que es conserva, o dir-ho al Javadoc.

**On mirar**: `ClubDefinitionWriter.java`, `seeds/club-definition.schema.json` (`localizedText`, `manualPaymentProvider`), `ClubDefinitionMapper.instructions()`, `CensusClubSettings.manualInstructions`.

---

## INC-14 · Detalls de la revisió d'E5-T24 (api, fitxers)

**Gravetat**: baixa. Avui no fallen: són defensa en profunditat, una neteja que falta i un test amb un nom més ampli del que prova. La revisió independent d'E5-T24 (26-09) els ha trobat, i l'organitzador els deixa per a la passada de correccions. Els menors de la mateixa revisió van a la tasca E5-T26.

**Origen**: `roadmap/reviews/E5-T24-20260926-1636-claude.md` (api), detalls #4, #5 i #6.

**Llista**:
- **El camí de les rutes signades.** `SignedFileRequests` (prop de `:19`–`:25`) decideix el `permitAll`, que no es llegeixi el bearer i que la ruta no tingui tenant a partir del `getRequestURI()` cru. `RateLimitFilter` fa servir, a propòsit, el camí descodificat i sense `;` amb què encamina Spring. Avui és segur, perquè `StrictHttpFirewall` refusa `%2F`, `%2E` i `;`. Per coherència, cal fer servir el mateix camí amb `UrlPathHelper`.
- **P9 i els fitxers de l'ADMIN.** P9 només neteja els grants `SIGNUP_DOCUMENT` (S15 R-15-19). Per això un fitxer `DOG_DOCUMENT` que l'ADMIN puja a D2 i no s'arriba a desar, o el d'una readmissió rebutjada, no s'esborra mai. Proposta: que P9 també netegi els grants `DOG_DOCUMENT` orfes, amb el mateix termini.
- **T-05-07.** `SignupPlansFollowUpIT.R_05_19_T_05_07_…` només comprova el `priceLabel` de Teràpia en `ca` i `es`. T-05-07 demana les línies de preu dels tres tipus de pla i «sense preu» en `ca`, `es` i `en`. Cal ampliar-lo o dir quin test cobreix la resta.

**On mirar**: `SignedFileRequests.java`, `RateLimitFilter` (prop de `:28`), el procés P9 (`S15 R-15-19`), `AttachmentService.claimDogDocument`, `SignupPlansFollowUpIT.java` (prop de `:118`).

---

## INC-15 · Revisió d'E5-T25: el sexe dels gossos migrats i detalls del contracte de reserves (api)

**Gravetat**: **mitjana** per al sexe: s'ha de corregir abans de cap migració real (E11/E12). Baixa per a la resta. Avui no afecta res: encara no s'ha migrat cap club, i les dades locals són de demostració.

**Origen**: `roadmap/reviews/E5-T25-20260926-1724-claude.md` (api), el menor #1 (la pregunta 1 de l'informe) i els detalls #2–#5.

**Llista**:
- **El sexe d'un gos migrat.**
  - **El problema:** `PlayoffPlanner` (prop de `:297`) només reconeix «femella» i «mascle», i qualsevol altre valor de «Sexe del gos» es desa com a `null`. S03 diu que `sex` és obligatori (`MALE`·`FEMALE`). El contracte publica `HoldDog.sex` com a obligatori, i ara `Booking.dog` i `WaitlistEntry.dog` el fan servir per a l'article en català («amb la Duna»).
  - **Decisió de l'organitzador (26-09):** el contracte es manté estricte. Un gos amb el sexe buit o desconegut és un problema **bloquejant** del pla de migració. El pla els llista tots d'una vegada, i s'arreglen a Playoff, a l'export o amb un mapatge a `MappingConfig` abans d'aplicar. Mai es desa un gos sense sexe.
  - **Correcció:** el codi de la incidència del pla, més un test amb una fila de sexe desconegut.
- **Un gos que falta fa caure tot un llistat.** A `BookingViews.java:115-122` i `:131`, un gos que falta abans donava `dogName: null`, i ara llança `IllegalStateException`, és a dir, un 500 fora del catàleg d'errors (AGENTS, regla 9). En un llistat del personal com `GET /class-sessions/{id}/waitlist-entries`, una sola entrada dolenta faria caure tot D4/D12. Avui res no esborra gossos. Proposta: mantenir l'invariant, però registrar-ho al log i que els llistats del personal ho tolerin.
- **`BookedBy.self` llegit per l'ADMIN.** Amb el seu propi token, l'ADMIN que llegeix una reserva que va fer impersonant rep `self: true` juntament amb `viaClub: true`. Correcció: dir a la descripció de l'esquema que el camp és per a les pantalles de l'abonat, o fixar el cas del personal a l'IT.
- **La clau d'idempotència del `claim`.** La ruta del `claim` (`BookingsController.java:280-283`, R-08-15) també pren un intercanvi, i la clau hi ha de ser la mateixa que la de R-08-08, amb un UUID per cos. La descripció no ho diu.
- **Noms de tests** (AGENTS, regla 5). `S08MemberFlowContractTest` fa servir T-08-42 i T-08-27 per a comprovacions que no són les d'aquests tests. Les esmenes d'S08 del 26-09 no tenen T-ids propis. Proposta: afegir-los a S08 §12 i canviar els noms.

**On mirar**: `PlayoffPlanner.java`, `MappingConfig`, `BookingViews.java`, `BookingsController.java`, `S08MemberFlowContractTest.java`, `MemberFlowContractIT.java`.
