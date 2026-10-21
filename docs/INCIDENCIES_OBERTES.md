# Incidències obertes — registre de defectes

**v4.10 · 21-10-2026** (v4.9 21-10 · v4.8 21-10 · v4.7 21-10 · v4.6 21-10 · v4.5 21-10 · v4.4 21-10 · v4.3 18-10 · v4.2 18-10 · v4.1 18-10 · v4.0 17-10 · v3.9 17-10 · v3.8 17-10 · v3.7 16-10 · v3.6 16-10 · v3.5 16-10 · v3.4 16-10 · v3.3 15-10 · v3.2 15-10 · v3.1 15-10 · v3.0 05-10 · v2.9 01-10 · v2.8 01-10 · v2.7 01-10 · v2.6 01-10 · v2.5 01-10 · v2.4 01-10 · v2.3 30-09 · v2.2 30-09 · v2.1 30-09 · v2.0 30-09 · v1.9 30-09 · v1.8 28-09 · v1.7 28-09 · v1.6 27-09 · v1.5 27-09 · v1.4 26-09 · v1.3 26-09 · v1.2 24-09 · v1.1 10-09 · v1.0 09-09)

Registre de defectes trobats mentre es desenvolupa i que **no s'obren com a tasca del roadmap ara mateix** (decisió de Jordi, 09-09: primer acabem el desenvolupament, després fem una passada de correccions). Serveix perquè cap troballa es perdi pel camí i perquè la fase de correccions tingui la llista feta.

**Com funciona.** Cada incidència té ID `INC-nn`, la reproducció exacta, què s'espera i on mirar. Quan s'obri com a tasca del roadmap s'hi anota l'ID de la tasca i passa a `resolta` quan l'organitzador la verifica. Res d'això és una tasca `ready`: l'executora no les veu fins que no les obrim.

**Quan es corregeixen.** Fase de correccions al final del desenvolupament, abans de la porta d'E11 (el release és E12; revisió global del 26-09) — les de gravetat **alta** bloquegen aquella porta, no les etapes intermèdies.

| ID | Data | Àmbit | Títol | Gravetat | Estat |
|---|---|---|---|---|---|
| INC-01 | 09-09 | api | `GET /api/v1/health` retorna `500` amb el `main` actual | **Alta** | **tancada 10-09 (ambiental)**: el `500` venia d'un procés Java local antic a `[::1]:8080` (PID 36366), no del contenidor; `127.0.0.1:8080` → `200 UP` amb BBDD buida i amb `Host: app.agilitycanic.cat`. **E3-T06 ✅** deixa la regressió `T_02_01_INC01_healthNeedsNoTenantLocaleAccountOrSeedData` (health sense tenant/locale/compte/dades) i la checklist amb `curl -4`. Acció de Jordi abans de la porta E0: matar el procés (`lsof -nP -iTCP:8080 -sTCP:LISTEN`) |
| INC-02 | 09-09 | api | Els `500` no deixen cap traça al log | **Alta** | **resolta 10-09 (E3-T06 ✅)**: `INTERNAL_ERROR` 500 amb `traceId` + una línia `ERROR` amb la traça sencera i el mateix `traceId`; les excepcions del framework conserven l'estat (405/406/415 nous al catàleg) |
| INC-03 | 09-09 | api · infra | El healthcheck del compose apunta a l'actuator (8081), no a l'endpoint real | Mitjana | **tancada 10-09 (no confirmada)**: la imatge i el `Dockerfile` ja comprovaven `/api/v1/health`; E3-T06 ✅ fa explícit el healthcheck a `compose.yaml` i `docker-compose.consumer.yml` (endpoint públic, 8080) i prova sana → insana → sana |
| INC-04 | 09-09 | api · infra | `compose.yaml` publica el port `27017` fix | Baixa | **resolta 10-09 (E3-T06 ✅)**: `MONGO_PORT` (amb `MONGODB_PORT` de reserva) a les dues variants del compose, documentat al README |
| INC-05 | 09-09 | docs | La checklist de la porta E0 té la comanda del seed desactualitzada | Baixa | **resolta 09-09** (`E0-fonaments.md` corregit); es manté com a recordatori de procés |
| INC-06 | 09-09 | api · contracte | `TokenResponse.scope` s'omet quan l'abast concedit és buit, però l'OpenAPI el marca `required` (trobat a E1-W04 contra la imatge real; el front ho normalitza a `""`) | Baixa | **resolta 10-09 (E3-T06 ✅)**: `scope: ""` sempre present a la resposta del token (`TokenScopeIT`) |
| INC-07 | 24-09 | api · web (auth) | El `refresh_token` respon `400` de manera intermitent a l'e2e contra el core real | **Alta** (provisional) | **resolta 18-10** (la causa, INC-36, a E11-T02 ✅; E134) · **mitigada 01-10 (E7-W06)**: la caiguda de T-04-34 venia d'una lectura de `/branding` encallada en arrencar després d'una càrrega completa, no de la renovació; l'arrencada ja no s'hi encalla si hi ha memòria cau. Queda obert quin salt la retenia (E7-W07). INC-36 continua a E11-T02 |
| INC-08 | 24-09 | api · contracte | Les respostes serialitzen `null` en camps opcionals que l'OpenAPI no declara `nullable` | Mitjana | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-09 | 24-09 | api · contracte | `RING_HAS_BOOKINGS.details.bookings[]` té dues formes segons la ruta | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-10 | 24-09 | api · RGPD | Les altes rebutjades no tenen retenció: S14 R-14-16 (b) no està implementada | Mitjana | **resolta 18-10 (E11-T01 ✅; E134)** |
| INC-11 | 26-09 | web | Diferències cosmètiques de les pantalles d'E3 respecte dels mockups (re-execució de la porta, E3-W09) | Baixa | oberta — passada de polit abans del llançament |
| INC-12 | 26-09 | api · proves | Detalls de la revisió d'E5-T22: còpies d'ítems de llista fetes a mà, l'ítem de `/platform/audit-entries` | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-13 | 26-09 | api · definició de club | Revisió d'E5-T23: les instruccions d'`MANUAL` en blanc passen la validació, la regla R-17-05 només es comprova quan la definició les declara, noms i abast de dos tests | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-14 | 26-09 | api · fitxers | Revisió d'E5-T24: la ruta signada es reconeix pel camí cru, P9 no neteja els fitxers `DOG_DOCUMENT` orfes de l'ADMIN, el test de T-05-07 no la cobreix sencera | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-15 | 26-09 | api · web (autenticació) | «Entra com l'abonat»: `launchUrl` sempre `null` (E17); el web obre l'origen de l'admin amb el JWT al fragment, i `apps/clubs` no té cap consumidor de `/entrar?handoff=` | **Alta** | **resolta 30-09**: web E4-W16 ✅ i api E5-T27 ✅ (`launchUrl`); l'escenari del core real d'E4-W18 la prova sense branques «pendent» |
| INC-16 | 26-09 | api (autenticació) | Els comptes amb dos rols (abonat i instructor o admin) reben `403` a totes les rutes d'abonat; `E5ContractIT` ho fixa | **Alta** | **resolta 30-09 (E5-T27 ✅, pas 1, E41)** |
| INC-17 | 26-09 | api (cens) | El `PATCH` SEPA→SEPA del mètode de pagament esborra l'IBAN desat quan no s'envia; T-03-13 ho fixa | **Alta** | **resolta 30-09 (E5-T28 ✅, pas 1, E42)** |
| INC-18 | 26-09 | web (autenticació) | Una sessió impersonada es renova en tornar a la pestanya amb un token que no es pot renovar: cau, o passa a ser la sessió d'abonat de l'admin | **Alta** | **resolta 30-09 (E4-W16 ✅)** |
| INC-19 | 26-09 | web (autenticació) | Una fallada transitòria de la renovació (sense xarxa, 5xx) tanca la sessió de l'abonat; la cookie es renova a cada focus | Mitjana | **resolta 30-09 (E4-W16 ✅)** |
| INC-20 | 26-09 | web (build) | El món de mocks (MSW) va dins dels bundles de producció i a la precàrrega del service worker | Mitjana | **resolta 30-09 (E4-W16 ✅)** |
| INC-21 | 26-09 | web · api (exportacions) | Les exportacions en cua (`202`) no es poden baixar des del calaix: la llista no porta l'enllaç i un `<a href>` no pot enviar el bearer | Mitjana | **resolta 30-09 (E4-W16 ✅)** |
| INC-22 | 26-09 | web | El diàleg «＋ DOC.» queda desactivat després de la primera pujada | Mitjana | **resolta 30-09 (E4-W16 ✅)** |
| INC-23 | 26-09 | api · web (contracte) | `Idempotency-Key` s'ignora a les rutes PUT i DELETE que el declaren (el filtre de l'api i el middleware del web només miren POST) | Mitjana | **resolta 30-09**: web E6-W01 ✅ i api E5-T27 ✅ (pas 4) |
| INC-24 | 26-09 | api · web (autenticació) | La recuperació de contrasenya no es pot acabar si el compte ja en té: es demana `current` després d'un enllaç `RESET` | Mitjana | **web i api resoltes 30-09** (E4-W16 ✅, E5-T27 ✅ pas 3); queda el `purpose` de l'enllaç, api E5-T29 pas 10 i web E5-W05 pas 21 |
| INC-25 | 26-09 | api (catàleg) | El valor per defecte de `messaging.email.fromName` és el literal «Club Agility Cànic» (marca blanca) | Mitjana | **resolta 30-09 (E5-T28 ✅, pas 2, E48)** |
| INC-26 | 26-09 | web | La pantalla 13 mostra un comptador en lloc de les files de tasques (R-03-18) | Mitjana | **resolta 30-09 (E4-W16 ✅)** |
| INC-27 | 26-09 | web | D10 sense `BILLING` perd «Bloqueja les reserves», «Inactivitat», «Baixa» i «Tota l'auditoria ›» (R-03-30) | Mitjana | **resolta 30-09 (E4-W16 ✅)** |
| INC-28 | 26-09 | web | D8 «Nou preu» proposa un 21 % d'impost per defecte (el Cànic no aplica IVA; una constant de país al codi) | Mitjana | **resolta 30-09 (E4-W16 ✅)** |
| INC-29 | 26-09 | web | D7: [DESA] amb `STALE_VERSION` recarrega el formulari i perd les edicions de l'admin | Mitjana | **resolta 30-09 (E4-W16 ✅)** |
| INC-30 | 26-09 | api (SEPA) | Els abonats nous reben un `mandateRef` de 39 caràcters (pain.008 en permet 35), i el seed de demostració no té mandats | Mitjana | **resolta 30-09 (E5-T28 ✅, pas 3, E43)** |
| INC-31 | 26-09 | api (privacitat) | Dues formes d'IBAN: en clar (entrat per l'api) i xifrat (migrat); cap tasca d'E8 no ho sabia | Mitjana | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-32 | 26-09 | api (alta) | Rebutjar una alta amb un checkout obert no tanca la sessió del proveïdor; un pagament tardà es perd | Mitjana | **resolta 30-09 (E5-T28 ✅, pas 4)**; els camins d'error nous del checkout, E5-T30 |
| INC-33 | 26-09 | api (alta) | D1 i el llistat d'abonats jutgen una readmissió pendent per la fitxa de baixa, no per la sol·licitud (E38) | Baixa | **resolta 30-09 (E5-T28 ✅, pas 7)** |
| INC-34 | 26-09 | api (planificació) | Es pot crear una classe `DRAFT` solta mentre es valida la setmana; `409` no declarats a les rutes de validació i de classe | Baixa | **resolta 30-09 (E5-T28 ✅, pas 5)** |
| INC-35 | 26-09 | api (cens) | El `PATCH` d'un gos actiu desa el xip sense normalitzar | Baixa | **resolta 30-09 (E5-T28 ✅, pas 6)** |
| INC-36 | 26-09 | api (autenticació) | Causa d'INC-07: reutilitzar un refresh token ja rotat revoca el fill viu, sense cap gràcia, i dues renovacions concurrents competeixen a `Account.sessionSequence` | **Alta** (= INC-07) | **resolta 18-10 (E11-T02 ✅, ronda 2; E134)** |
| INC-37 | 26-09 | api (identitat, menors) | Menors d'identitat: esdeveniments, auditoria, temps de resposta, importació de Learn, rotació de claus | Baixa | **resolta 21-10 (E11-T09 ✅ ronda 2; E152)** |
| INC-38 | 26-09 | api (cens i catàlegs, menors) | Menors de cens i catàlegs: emmascarament de les exportacions, exportació asíncrona amb filtre numèric, auditoria sense `entityLabel`, … | Baixa | **resolta 21-10 (E11-T10 ✅; E151)**; el punt 11 queda per a E12 com deia la tasca |
| INC-39 | 26-09 | api (alta, menors) | Menors d'alta: rutes de D10 no congelades durant una readmissió, codi mort, … | Baixa | **resolta 21-10 (E11-T10 ✅; E151)**, llevat del punt 2 (la procedència de l'efectiu d'una fila parcial que va al checkout) → E11-T17 (E149) |
| INC-40 | 26-09 | api (planificació, reserves i processos, menors) | Menors: text del risc, finestra de bloqueig no alineada, `instructorIds` buit, nivell inactiu, cursa de l'slug, … | Baixa | **resolta 21-10 (E11-T10 ✅; E151)**, llevat del punt 10 (N-06 + N-47 per a una reserva feta pel club) → E11-T17 (E150); els menors (auditoria abans de publicar, `seat_locks` antics, auditoria d'un reordenament) també a E11-T17 |
| INC-41 | 26-09 | web (fonaments i pantalles, menors) | Menors del web: `apps/id` en fallar la renovació, logout amb bearer refusat, pàgina en blanc si `/branding` falla, esquemes de `pending.json` ja publicats, tema `auto`, … | Baixa (la llista de traspàs, un botó mort i el router: Mitjana) | oberta — E4-W16 pas 12 · E8-W03 · E11-W02 (el router, A38 ✓ Jordi 27-09; la llista completa, a la ronda 2, E132) |
| INC-42 | 26-09 | api · migració i reserves | Revisió d'E5-T25: la migració desa un gos sense sexe quan Playoff en porta un de desconegut (S03 l'exigeix); detalls del contracte de reserves | **Mitjana** (el sexe; abans de migrar) · baixa (la resta) | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-43 | 27-09 | api · fitxers | Revisió d'E5-T26: l'IT de les descàrregues locals no descarrega cap `DOG_PHOTO` ni `ACTIVITY_IMAGE` i els seus camins de reserva no tenen test; les descàrregues locals no admeten `Range` | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-44 | 27-09 | web · api (entrenaments) | Preguntes d'E5-W02: sense graella a 24 i D12 per a una pista sense entrenament lliure o amb `FREE_TRAINING` desactivat; la cel·la «classe» mostra l'hora de la fila, no la de la classe | Baixa | **resolta 21-10**: api E11-T10 ✅; web E11-W02 ✅ ronda 3 (`classStartsAtLocal` → «18:50 classe» a 08/24; D12 no dibuixa cel·les, només selectors: decisió de disseny) |
| INC-45 | 28-09 | api (seguretat, contracte) | Pregunta d'E7-T01: cap test comprova, per a totes les operacions, que la seguretat publicada a l'OpenAPI és la que s'aplica (avui ho fa cada IT de contracte per a les seves rutes) | Baixa | oberta — E11-T03 (pas 1) |
| INC-46 | 28-09 | api (processos) | Nits de la ronda 2 d'E6-T04: el recompte de [Simula] de P3 i la seva traça sense límit, una escombrada en bloc sense ús ni guarda de mòdul, i la lectura de P8 a cada minut | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-47 | 28-09 | api (transaccions, comú) | Una ruta amb clau que no és a la llista de rutes amb transacció pròpia d'`IdempotencyFilter` s'executa dins la transacció del filtre, i un conflicte d'escriptura de Mongo hi acaba en 500 (revisions d'E6-T03, rondes 3 i 4) | Mitjana | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-48 | 30-09 | api (seguiment) | Nits de la ronda 5 d'E6-T03: l'estat que es desa per a la resposta repetida d'una clau és una còpia escrita a mà del `@ResponseStatus` de cada ruta, i un Javadoc mal tallat | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-49 | 30-09 | api (consola, idempotència) | `IdempotencyFilter` pren el club del `clubId` del JWT i respon `NO_MEMBERSHIP` sense: una ruta de consola amb clau (`POST /platform/clubs/{clubId}/jobs/{name}/trigger`) cridada amb un token de plataforma queda refusada abans del handler (nota 2 d'E5-T29, llegida al codi) | Mitjana | **resolta 17-10 (E10-T04 ✅, ronda 2; E128)** |
| INC-50 | 30-09 | api (processos, proves) | Nits de la ronda 2 d'E5-T29: un reintent d'un llançament manual pot deixar l'execució sense `JOB_TRIGGERED` si la primera escriptura de l'auditoria va fallar; la vida de 24 h de la clau és definida dues vegades; l'etiqueta T-09-30 dels tests de cerca no té cap asserció de tenant | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-51 | 01-10 | api (seguiment, contracte) | Menors de la revisió d'E6-T06 (la cerca de D14 sense projecció i amb llistes `$in` sense límit, les proves d'aïllament de tenant de la cerca i dels recomptes, `FOLLOWUP.searchable`, l'etiqueta d'un abonat esborrat als valors del filtre, els scripts de l'evidència) i la pregunta 1 d'E6-W05 (`POST /tasks` pot respondre `409 INVALID_STATE` sense declarar-lo) | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-52 | 01-10 | api (missatgeria, menors) | Menors de la revisió d'E7-T05: `claimAccepted` amb dos predicats sobre el mateix array, l'acceptació que no es torna a marcar mentre l'assentament falla més de 2 minuts, l'evidència del pas 4, la fila 11 de la taula sense prova, el rebot d'una adreça compartida, i detalls | Baixa | **resolta 18-10 (E11-T02 ✅; E134)**, llevat del punt 2 (la fila 7 de la taula d'E7-T05, de l'organitzador) |
| INC-53 | 01-10 | api (contracte i seed, petits) | Preguntes d'api d'E6-W04, E7-W05 i E5-W05: `TaskItem.doneBy`, `UploadUrl.uploadUrl` relatiu, els comptadors de simulació de P8, el comptador d'adjunts de `FollowupItem`, un límit superior per a `read-all`, les etiquetes amb espai final de `/bookings/filter-values`, `isoWeekStart` a l'ítem de P1, el «Berta» del seed, la resta de codis d'S10 que han passat a 422, els camps obligatoris de `Member` que l'esborrament anul·la, i les claus d'idempotència de `POST /jobs/{name}/trigger` i de la llista d'espera | Baixa | **resolta 18-10 (E11-T02 ✅ i, el punt 10, E11-T01 ✅; E134)**, llevat del punt 13 (els noms es/en de les plantilles, que revisa Jordi) |
| INC-54 | 01-10 | api (comunicacions, menors) | Menors d'E7-T07: `bin/e7-smoke-test.py` a la CI, la resposta de la llista permesa enregistrada d'una execució real, els canals comparats sense ordre, noms de proves, i els formats en anglès de `ClubFormats` (R-11-05) | Baixa | **resolta 18-10 (E11-T02 ✅; E134)**, llevat del punt 2 (una execució real del proveïdor, al llançament) |
| INC-55 | 01-10 | web (preferències, mocks) | Menors de la ronda 2 d'E7-W07: una negativa de l'api ha de treure només la preferència que nomena, i una tria que ja ha rebut el seu `2xx` ha de sortir de la safata; un sol magatzem de reserves per als mons del mock | Baixa | **resolta 18-10 (E11-W02 r1/r2)**, llevat del punt 1, **reobert 21-10 (E154)**: `preferences-saver.ts:499` — una negativa descarta una preferència aliena quan el recompte de camps coincideix → E11-W05 pas 0 |
| INC-56 | 05-10 | web (e2e del nucli, rigor) | Menors de la ronda 2 d'E7-W03: l'asserció del correu N-13 (`SKIPPED_BY_PREFERENCE` exacte), la sonda `TEMPLATE_MANDATORY` incondicional, la restauració de la plantilla dins d'un `finally`, i les captures de notificacions preses després de l'edició de la plantilla | Baixa | oberta — E11-W02 |
| INC-57 | 15-10 | api (tauler, menors) | Menors de la revisió d'E8-T13: una sola constant per al propietari `SYSTEM` de les vistes desades, les escriptures de baixes de `CensusMigrationService` sense el ganxo `changed(...)` (i l'informe que diu «totes»), `LifecycleMenuIT` sota `bookings.api` | Baixa | **resolta 18-10 (E11-T02 ✅; E134)**; el punt 3 es queda com està (E132) |
| INC-58 | 15-10 | api (pagaments, proves) | Menors de la ronda 6 d'E8-T12: un oracle independent per a la cota inferior de les intervencions, l'avís d'intervenció quan el deute recalculat ja és 0, lectures de més a `intentLedger()`, un cas de la matriu | Mitjana | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-59 | 15-10 | web (facturació, proves) | Menors de la ronda 2 d'E8-W07: la prova vermella de D6 encara depèn de l'ordre d'execució, l'espia de `setTimeout` sense `clearTimeout`, un literal inventat per confirmar | Baixa | oberta — passada de correccions |
| INC-60 | 15-10 | web (recorreguts) | Menors de la ronda 2 d'E9-W02: el transport per defecte dels esdeveniments de muntatge (per a E9-W04), noms de proves, set etiquetes inventades per confirmar, petits | Baixa | oberta — passada de correccions |
| INC-61 | 15-10 | api (recorreguts, menors) | Menors d'E9-T02: el llistat de `GET /courses` es fa en memòria (límit conegut a R1), el `slug` del recinte pot xocar i donar un 500 (i A4 hauria de venir del perfil de país), noms de proves | Baixa | **resolta 18-10 (E11-T02 ✅; E134)**; el punt 1 és el límit conegut d'R1, i A4 continua sent el paper per defecte (E132) |
| INC-62 | 16-10 | api · web (recorreguts) | Menors d'E9-T05 i dues peticions d'api d'E9-W03: la projecció del soci porta el nom d'un recorregut privat previst (R-16-16), la prova de `COURSE_NOT_VISIBLE` en registrar un muntatge, el full de muntatge anterior orfe i el d'una col·locació moguda, el nivell d'AgilityHub sense traduir a N-31, `S3AttachmentStorage.read` sense prova, `CourseListItem.updatedAt` i `dismantledReason` (i, al web, la data de D18 i «Substituït») | Mitjana | **web resolta 21-10 (E11-W02 ✅ ronda 3; E154)**: D18 amb «Últim canvi», D16 «Substituït» només per `REPLACED` |
| INC-63 | 16-10 | api (sessions de muntatge, menors) | Menors de la ronda 2 d'E9-T06: el límit d'enviadors refusa fluxos sans per sobre d'uns 64 subscriptors per instància, la prova del temps d'escriptura només llegeix la configuració, el `connection-timeout` de 20 s és global (també per a les pujades), l'estil del ganxo de proves, la prova dels dos `finish` només cobreix un camí | Baixa | **resolta 18-10 (E11-T02 ✅; E134)** |
| INC-64 | 16-10 | api · infra (consola, dominis) | Un domini `clubs-admin` verificat rep el certificat, però Caddy hi serveix l'app `clubs` fins que s'afegeix a `CLUB_ADMIN_HOSTS` i es recarrega Caddy | Baixa | **resolta 21-10 (E11-T09 ✅ ronda 2; E152)**: un host d'admin verificat serveix l'app d'admin via `forward_auth` de Caddy i `/internal/domains/app`, sense `CLUB_ADMIN_HOSTS` |
| INC-65 | 17-10 | api (consola, menors) | Menors de la ronda 2 d'E10-T04: el patró del filtre de suport no inclou `HEAD` (l'interceptor ho para, però cap prova ho cobreix), la comprovació del club d'`IdempotencyFilter` pot respondre `TENANT_MISMATCH` si ja hi ha un altre tenant obert, i `@SupportRestricted` escrita amb el nom complet a 22 handlers | Baixa | **resolta 21-10 (E11-T08 ✅ ronda 2; E138)** |
| INC-66 | 17-10 | api (consola i dominis, menors) | Menors de la ronda 2 d'E10-T03: el reintent per JNDI llença els registres `A` que troba (una fallada de consulta que compta com a absència), el pas a pas de `DEPLOY.md` per als dominis de confiança (la primera opció no funciona i falta com recuperar-ne un de `BROKEN`) i `ClubRepository.save`, que depèn de la transacció de qui el crida | Mitjana | **resolta 21-10 (E11-T08 ✅ ronda 2; E138)** |
| INC-67 | 17-10 | api (seguretat i límits de peticions, menors) | Menors d'E11-T06: cap prova garanteix que cada operació anònima d'escriptura tingui una ruta de límit; el compte de `STRIPE_WEBHOOK` només fa servir la IP (la ràfega d'un club pot endarrerir els esdeveniments de pagament d'un altre, que Stripe reintenta); `CHECKOUT_STATUS` compta per IP i no per compte | Mitjana | **resolta 21-10 (E11-T08 ✅ ronda 2; E138)**; dos menors (la mida de les memòries cau per ruta i la consulta dels clubs coneguts sense Mongo) a INC-74 |
| INC-68 | 17-10 | api (preus, dades) | Les dates de vigència d'un `Price` es desen com a dates BSON quan s'escriu sencer i com a cadenes quan s'actualitza (informe d'E11-T07); una consulta per data podria no trobar el preu vigent | Alta | **resolta 21-10 (E11-T08 ✅ ronda 2; E138)**: dates de vigència convertides a dia, bessons d'inici retirats o reportats, `PRICE_OVERLAP` a la facturació; els menors (els ajudants sense tenant, l'oferta pública amb un bessó) a INC-74 |
| INC-69 | 18-10 | api (recorreguts, menors) | Menors d'E9-T07: la regla de quin recorregut mostra un muntatge és escrita tres vegades, la col·locació del soci quan el recorregut s'ha esborrat, el nom d'una prova i el diagrama de les sessions de muntatge d'S16 §5 | Baixa | **resolta 21-10 (E11-T09 ✅; E152)** |
| INC-70 | 18-10 | api (Spring Boot 4, menors) | Menors d'E11-T07: la prova de l'escaneig exigeix que no hi hagi cap excepció, els pedaços de Jackson de la mateixa línia (2.21.7 i 3.1.7), una línia de `DEPLOY.md` sobre els fitxers de Compose i una sobre Micrometer | Baixa | **resolta 21-10 (E11-T09 ✅; E152)** |
| INC-71 | 18-10 | api (proves) | `BuildSessionsIT.T_16_10_theQueryTokenIsAcceptedOnlyByTheEventStream` falla de tant en tant: llegeix el `Content-Type` de l'SSE abans que estigui fixat quan diverses compilacions comparteixen el contenidor | Baixa | **resolta 21-10 (E11-T09 ✅; E152)**: la prova espera el `Content-Type` de l'SSE (20 execucions verdes) |
| INC-72 | 18-10 | web (recorreguts, menors) | Menors d'E9-W06: el pas normal d'`e9-core` no s'ha executat en verd amb el codi final (i depèn del diumenge), el plànol del soci no fa servir `resolvedObstacles`, el gestor MSW de `GET /rings/:id` no mira el rol, i dos detalls | Baixa | oberta — E11-W03 (E134) |
| INC-73 | 18-10 | web (perfil, impersonació) | L'app dels socis encara mostra l'edició del nom i de l'idioma durant una impersonació, i l'API ja refusa `PATCH /me` a aquest token (E11-T09) | Baixa | **resolta 21-10 (E11-W01 ✅ ronda 2; E151)** |
| INC-74 | 21-10 | api (límits de peticions, preus, RGPD, menors) | Menors de la verificació d'E11-T08 ronda 2 i d'E11-T11: les memòries cau dels límits ara són per ruta i la suma no té límit (×21), la consulta dels clubs coneguts fa un 500 si Mongo cau, un pla amb un preu bessó encara s'ofereix públicament, els ajudants d'arrencada dels preus són públics i sense tenant, `named()` mira camps que no existeixen, la regla de `StoredDays` és per desplaçament i no per zona, `CATALEG_ERRORS` S12 sense `PRICE_OVERLAP`; el camí de cancel·lació d'una supressió sense IT, el text «sense perfil» del vault, `filesCleaned` no compta els fitxers de l'`afterCommit`, `graceSiblingAt` no mapat, un nom de prova i la columna «Idempotent» | Baixa | oberta — E11-T13 ronda 2 (E156; els menors a E11-T19) |
| INC-75 | 21-10 | web (estats, consola, auditoria, diners, menors) | Menors de la ronda 2 d'E11-W02 i restes d'INC-41: els chunks mandrosos de la consola sense boundary ni càrrega, 21 sense estat buit (i 22/tasques sense `EmptyState`), la restauració del desplaçament en recarregar, el text del boundary en fallar un chunk, el límit superior del rang d'auditoria, un canal de pagament desconegut mostra la clau, noms de proves sense T-xx-nn, la prova de sondeig d'E8-W01 amb rellotge real; i d'INC-41: els diners dividits per 100 a 40 llocs (`fmtMinor`), «7:30» a dues etiquetes (`reviewTime` de l'api, E11-T13), la confirmació `RING_HAS_BOOKINGS` sense text, «DNI», la forma neutra d'`OTHER`, el registre d'`errors` | Baixa | oberta — E11-W05 (E144) |
| INC-76 | 21-10 | web (rendiment) | La home amb sessió (03, `/inici`) triga 4,0–4,2 s (TTI) amb la build de producció: el camí carrega ≈330 kB en cinc onades seqüencials des de l'únic chunk `screens` (A38). Un chunk per pestanya amb les fonts precarregades el baixaria; no es fa abans del llançament (E142: la fita de 3 s es demana a la 01 i la 03 queda amb un pressupost de 4,5 s) | Baixa | oberta — després del llançament |
| INC-77 | 21-10 | api (facturació, migració) — **auditoria del 21-10** | Cadena de diners: un abonat que passa de pack a mensual sense `nextInvoiceDate` no es factura mai (F1, crítica); el grup d'un titular que es dona de baixa no es factura el darrer mes (F2, crítica); els abonats en efectiu migrats es facturen de nou al desembre (F3); un mes saltat per `NO_BANK_ACCOUNT` no es recupera (F4); la remesa es pot retrocedir després de la data de cobrament (F5); una fila desconeguda de `rebuts.csv`/`packs.csv` anul·la tota la càrrega del dia D (F6); referències de mandat de 44 caràcters (F7); identificador de creditor sense mod-97 (F8); packs migrats `CLOSED` que mai caduquen (F9); `period` dels rebuts manuals (F11); i tres fets per al runbook (F10) | **Alta** | oberta — E11-T15 ronda 2 (E156) |
| INC-78 | 21-10 | api (RGPD, accés de suport) — auditoria del 21-10 | La supressió deixa el nom de l'actor a les reserves i inscripcions d'altres abonats (`bookedBy`/`cancelledBy`/`registeredBy.displayName` només es buiden quan `memberId` és el suprimit; `attendances.markedBy` ho fa bé) i els `familyGroupClaim.holderName/dogName` que altres sol·licitants van escriure (S1, major); els registres `FAILED`/`PENDING` de l'outbox i `job_runs.items.detail…dogName` conserven dades (S2); un token de suport pot demanar o cancel·lar una supressió (S3); els escriptors de reserves no reintenten `DuplicateKey` (S4) | Mitjana | oberta — E11-T16 ronda 2 (E156) |
| INC-79 | 21-10 | web (accés, sessió, estats) — auditoria del 21-10 | Els enllaços profunds dels correus (`/espera/:id`, `/reserves/:id`, `/preinscripcions/:id`) no sobreviuen a l'inici de sessió (W1, major); un administrador sense rol MEMBER amb la tria recordada acaba a `/inici` amb dos 403 (W2, major); tres pestanyes que renoven alhora revoquen tota la família de tokens (W3, major); el «Menú d'usuari» del back-office és mort i no hi ha «Tanca la sessió» (W4); `Account.locale` no s'aplica en un segon dispositiu (W5); `<html lang>` fix (W6); instants en UTC a D10 (W7); la 30 sense reintent (W8); `PRICE_OVERLAP`, `READMISSION_PENDING`, `CURRENCY_MISMATCH` i tres codis de l'alta sense text ni ruta de camp (W9–W12); una ruta sense rol pinta un `main` buit (W13) | Alta | oberta — E11-W06 (E145) |
| INC-80 | 21-10 | web (back-office, rendiment, empaquetat) — auditoria del 21-10, menors | Els enllaços de la barra lateral del back-office són càrregues de document senceres (una rotació de token per clic; A38 només a l'app dels socis) (W14); «Entra com l'abonat» obre la pestanya després de l'`await` (bloquejador de Safari) (W15); `mockServiceWorker.js` publicat a producció (W16); el pressupost de JS de `clubs-admin` a 1,2 % del límit (W17) | Baixa | oberta — E11-W05 (W15) · després del llançament (W14, W16, W17) |
| INC-81 | 21-10 | web (consola, RGPD, mocks, menors) | Menors de les verificacions d'E10-W03 ronda 3 i d'E11-W01 ronda 2: els mocks dels accessos de suport no aïllen el club (cap prova pot cobrir una concessió d'un altre club → 404); `ParametersTab` no actualitza la fila des de la resposta del PUT si la relectura falla; l'e2e deixa un domini verificat al «club mínim»; `MemberPrivacyCard` no posa `erasureLoading` quan l'estat passa a LEFT en memòria; `DataExportCard` sobreescriu el paquet desat amb un `jobId` de l'URL abans de llegir-lo; el mock `GET /exports` respon 200 a un token MEMBER (el core, 403); el codi del mock de `PATCH /me` impersonat s'ha d'alinear amb l'snapshot; `ExportsDrawer` manté «Preparant l'exportació…» quan la feina ja és READY | Baixa | oberta — E11-W05 |
| INC-82 | 21-10 | api (índexs, desplegament) | `IndexOperations.alterIndex` (→ `collMod`) a `ImpersonationGrantRepository` i `SecurityEventRepository` per pujar un TTL existent: l'usuari de producció (`deploy/mongo-health.js`, només `readWrite`) no pot executar `collMod` → el Core no arrenca sobre un volum autenticat amb l'índex antic o quan canvia una retenció. Latent (no hi ha producció encara). També: `deploy/test_e11_t09.py` no corre a la CI; l'`ask` del `forward_auth` porta `X-Forwarded-Uri`; la descàrrega del wrapper de Maven no reintenta | Mitjana | oberta — E11-T16 ronda 2 (E156: la guarda TTL per clau) |
| INC-83 | 21-10 | api i web (menors de les verificacions del 21-10, segona tanda) | Api: `ActivityTransactions.transientConflict` no reintenta `DuplicateKey` com `TransactionRetries.conflict` (E11-T16 F6); `CensusErasure.erased()` carrega documents sencers cada mes (F5); cursa teòrica del drop+create de l'índex TTL amb dues instàncies (una sola `core`); `GET /members/{id}/signup` `proposals.priceId` = el primer bessó (només admin; D2 refusa amb 409). Web: `packages/api-client/src/generated/schema.d.ts` sense el contracte d'E11-T15 (→ E11-W07); D2 `planOptions` deixa triar el segon bessó (el core respon `409 PRICE_OVERLAP` des d'E11-T13 r2; D2 el mostra amb nom de pla a E11-W06/W08); els dos presets «AgilityHub» (api `#2563EB`/system-ui vs web `--ah-product-*`) discrepen fins a E11-T19 (E157) | Baixa | oberta — E11-T19 / E11-W07 / E11-W08 (les parts assignades) · després del llançament (la resta) |

---

## INC-51 · Menors de la revisió d'E6-T06 i el `409` no declarat de `POST /tasks` (api)

**Gravetat**: baixa. Res no falla avui a l'escala d'un club.

**Origen**: `roadmap/reviews/E6-T06-20260930-2241-claude.md` (api) i la pregunta 1 de l'informe d'E6-W05 (web); verificació de l'organitzador de l'1-10 (decisió E80).

**Què cal fer**:
1. **La cerca de D14** (`FollowupCensusAdapter`, `FollowupService.search`, `TaskRepository.idsContaining`): cada `q` fa quatre exploracions amb regex sense àncora i sense projecció (els documents d'abonat sencers), i els ids trobats entren al `$match` com a llistes `$in` sense límit; es repeteix a cada pàgina i a cada `filter-values`. Proposta: una sola consulta de gossos amb `$or` (nom i nota), projeccions només d'`_id` (i del nom per als abonats), i una INC nova si D14 creix (índex o cerca de text).
2. **Aïllament de tenant a les dades** (AGENTS regla 4): les proves noves de la cerca i dels recomptes no comproven que el club `s10f-b` (gos «Aliè», tasca «Aliena») en quedi fora. Una asserció a cada prova.
3. **`FOLLOWUP.searchable`** llista etiquetes (`memberName`, `dogName`, `text`) que no són camps de `followup_items`: un conjunt de dades sense `withSearch` buscaria en camps inexistents. Fallar de seguida, o un Javadoc que ho digui.
4. **L'etiqueta d'un abonat esborrat** als valors del filtre `memberId` és l'id intern (com a `/training-bookings/filter-values`). Proposta: una etiqueta neutra i traduïda a totes dues rutes.
5. **L'evidència**: els scripts que generen els resums dels logs `08` i `11` no són al repo, i l'`exit` dels logs `07` i `10` queda fora del fitxer.
6. **`POST /tasks`** pot respondre `409 INVALID_STATE` (una pujada caducada, `AttachmentService.claim`) i el contracte només hi declara `IDEMPOTENCY_KEY_REUSED`. Proposta: declarar-lo i donar-li un `details.reason` propi (per exemple `UPLOAD_EXPIRED`), perquè el client el distingeixi de `READMISSION_PENDING`.

**On mirar**: la revisió citada; `FollowupIT` (línies 810-918); `AttachmentService.java:145`, `TaskService.java:54`.

---

## INC-52 · Menors de la revisió d'E7-T05 (api, missatgeria)

**Gravetat**: baixa. Cap enviament es duplica en el funcionament normal; els casos de sota necessiten una caiguda llarga de la base de dades.

**Origen**: `roadmap/reviews/E7-T05-20261001-0047-claude.md` (api); verificació de l'organitzador de l'1-10 (decisió E81).

**Què cal fer**:
1. **`claimAccepted`** (`NotificationRepository.java:146`) filtra `deliveries` dues vegades (un `$exists` a dalt i un `$elemMatch`) i escriu amb `deliveries.$`: la posició pot sortir del primer predicat i agafar el lloguer d'una altra entrega acceptada. Un sol predicat (`acceptedAt` dins l'`$elemMatch`, i un índex `sparse`), amb una prova de dues entregues acceptades, una amb el lloguer viu i l'altra vençut.
2. **Una acceptació que no s'ha pogut marcar** (`NotificationDispatcher.java:335-375`): si els cinc intents de `markAccepted` fallen i l'assentament continua fallant més de 2 minuts, el lloguer original venç i `claimDue` torna a enviar el missatge. `settleWaiting` ha d'escriure primer la marca (i renovar el lloguer). Corregir la fila 7 de la taula d'E7-T05.
3. **L'evidència del pas 4**: la prova «falla abans» s'atura en una línia que el pont de compilació fa fallar sempre; cal comprovar el mapa d'ús desat (`smsMonthKey`, `smsSentMonth`) i `firstCapNotice` abans, i tornar-la a executar amb el codi antic.
4. **La fila 11** de la taula de camins d'error (el *hook* `sent` del propietari) no té prova.
5. **Una adreça compartida que rebota** (decisió E81, R-11-08): la comprovació de cada intent ha de mirar l'adreça (`membersWithEmail(address)`), no només el contacte del destinatari.
6. **Detalls**: `dispatch()` fa un `claimAccepted` per notificació fins i tot per a les acabades de desar (només cal al sondeig de 5 s); `markAccepted` sense `clubId`; `ClubSmsUsage.reserve` retorna el document sencer (cal projectar `usage.smsMonthKey`); els noms de les proves noves, amb els id T-11-07, T-11-09, T-11-10 i T-11-11.

**On mirar**: la revisió citada; `roadmap/tasks/E7-T05.md` (la taula de camins d'error).

---

## INC-53 · Petits buits del contracte i del seed que ha trobat el web (api)

**Gravetat**: baixa. El web hi té una alternativa a cada cas.

**Origen**: les preguntes d'E6-W04 (Q1–Q3), d'E7-W05 (Q3) i de la ronda 2 d'E5-W05 (R2-Q1, R2-Q2); verificació de l'organitzador de l'1-10 (decisió E82). El punt 10, de la suposició A8 d'E7-W06 (decisió E85) i de la pregunta Q3 d'E7-W07. Els punts 11 i 12, de les preguntes Q1 i Q2 d'E7-W07 (decisió E86). Els punts 13–16, de les propostes del pas 5 d'E7-W03 (decisió E89).

**Què cal fer**:
1. **`TaskItem.doneBy`** a `GET /me/dogs`, perquè la pantalla 13 digui «feta per {nom} el {data}», com la 26.
2. **`UploadUrl.uploadUrl`** (i l'`url` d'un adjunt) és de tipus `uri`, però el core en retorna un camí relatiu: o `uri-reference`, o una URL absoluta.
3. **P8**: els comptadors de la simulació porten el nom de les accions (`WOULD_SWEEP`, `WOULD_FINISH`) i els de l'execució real són `swept` i `finished` (E68). Un sol joc de noms.
4. **`FollowupItem`** no porta el nombre d'adjunts que mostra el clip del mockup de D14.
5. **`POST /me/notifications/read-all`** amb un límit superior opcional (l'instant en què es va llegir la pantalla 11), perquè un reenviament des de 03 no marqui com a llegit un avís que l'abonat no ha vist.
6. **`GET /bookings/filter-values?field=memberId`**: les etiquetes acaben amb un espai («Rita Fictici013 ») quan no hi ha segon cognom.
7. **L'ítem `Week` de P1** porta `isoWeekStart` (el que ja té `WeekOpened`), perquè el web enllaci la setmana que l'api ha obert sense calcular-la (R-15-11).
8. **El seed de demostració**: «Berta» (la instructora 0) no queda lligada sempre al mateix compte (`instructor@`, `instructor.2@`, `instructor.3@` segons l'execució), tot i que el README del seed promet un repartiment fix.
9. **Els codis d'S10 que han passat a 422**: el core només s'ha comprovat amb `ATTENDANCE_NOTIFIED_FINAL`, `TASK_ALREADY_DONE` i `TASK_NOT_DONE`; E7-W03 prova la resta.
10. **Un abonat esborrat** (R-14-15) té la data de naixement i l'adreça anul·lades, però el contracte de `Member` les declara obligatòries. O són `nullable` per a un abonat esborrat, o l'api diu què n'envia. Ho resol E11-T01, que fa l'esborrament; mentrestant, el mock del web segueix el contracte publicat. E11-T01 respon també les preguntes d'E7-W07 (Q3):
    - quins `409` responen `POST /members/{id}/erasure` (S14 §6 hi afegeix `MEMBER_ERASED`) i `data-export`;
    - què respon `impersonation-token` per a un abonat esborrat i de baixa: `MEMBER_ERASED` o `MEMBER_NOT_ACTIVE`;
    - si un abonat SEPA esborrat porta `accountMissing = true`;
    - si la fitxa d'un abonat esborrat llista els seus gossos;
    - si les seves preferències són `{}` o els valors per defecte.
11. **`POST /jobs/{name}/trigger`** accepta `Idempotency-Key` (E75), però l'OpenAPI no la declara (S15 §6 hi diu «no»). Cal declarar-la, opcional, perquè el web l'envia (CONVENCIONS_API §7).
12. **`POST /waitlist-entries`**: si es perd el `201`, el reintent respon `409 ALREADY_ON_WAITLIST`. Cal declarar-hi `Idempotency-Key` (el filtre ja accepta una clau a tot `POST`) i provar que un reintent amb la mateixa clau rep el mateix `201`. Cal dir també quina guarda evita dues entrades simultànies del mateix gos (R-08-12 no en diu cap). El web l'adopta quan l'api la declari.

13. **El nom d'una plantilla del catàleg** (`MessageTemplateListItem.name`) és la font del títol, amb ICU i variables («{kind, select, …} (N-13)», «[[dog_name_article]] puja de nivell!»), i D9 l'escriu tal qual. L'api ha de tornar el nom de producte del catàleg (`CATALEG_NOTIFICACIONS`, columna «Nom»), i per a una plantilla `CUSTOM`, el nom que li ha donat el club.
14. **N-08a i la pista:** el mockup 11 escriu «· Central», però `ring_name` no és una variable d'N-08a. Si s'hi afegeix, la fila del catàleg, el seed i S11 §8 canvien en el mateix commit. Fins aleshores s'accepta la diferència amb el mockup.
15. **Els comptadors de P4:** la simulació compta `WOULD_REMIND` i l'execució real `classReminders` i `trainingReminders`. Un sol joc de noms, com al punt 3 per a P8.
16. **El registre d'avisos** no mostra el `batchId` d'un comunicat: «un sol lot» només es veu pel nombre de files. Les files d'un comunicat han de portar el seu `batchId`.
17. **La revisió de la imatge publicada** (E7-W03 ronda 2, 05-10): la imatge `:main` del registre no porta l'etiqueta `org.opencontainers.image.revision`, i `/health` no diu quina revisió serveix. Cal afegir l'etiqueta al `docker build` de la CI i la revisió a la resposta de `/health`, perquè el web pugui enregistrar contra quin commit de l'api ha passat les proves. Ho fa la ronda 2 d'E11-T04 (`deploy/` i la publicació de la CI).

**On mirar**: els informes d'E6-W04, d'E7-W05 i d'E7-W03, i la ronda 2 d'E5-W05 (web).

---

## INC-54 · Menors d'E7-T07 (api, comunicacions)

**Gravetat**: baixa.

**Origen**: la revisió d'E7-T07 (`roadmap/reviews/E7-T07-20261001-1747-claude.md`); verificació de l'organitzador de l'1-10 al vespre (decisió E89).

**Què cal fer**:
1. **`python3 bin/e7-smoke-test.py` a `ci.yml`**, al costat de `bin/security-scan-test.py`. Ara només s'executa en local, i un canvi a `bin/e7-smoke` podria tornar a exigir l'SMS sense que ningú ho vegi.
2. **La resposta de la llista permesa, enregistrada d'una execució real:** `bin/e7-smoke` amb credencials de Twilio fictícies i un `SMOKE_SMS_TO` fictici. La guarda refusa l'SMS dels abonats del seed abans de cap crida a Twilio, i l'execució ha d'arribar a `real_sms_step()`. La resposta enregistrada substitueix la que es va escriure a mà.
3. **`bin/e7-smoke:52`** compara els canals sense ordre (`sorted`).
4. **Noms de les proves** amb l'identificador de la spec (`AuditWriterTest`, `AuditedAspectTest`; AGENTS regla 5).
5. **Els formats de `ClubFormats` en anglès (R-11-05):** el dia de la setmana va davant del dia («Thursday 8», «Sat 17»), i tota variable d'hora s'escriu igual («9:30»), també `[[changes]]`. Cal actualitzar les expectatives de `NotificationActionsIT`.

**On mirar**: l'informe d'E7-T07 i la seva revisió.

---

## INC-55 · Menors de la ronda 2 d'E7-W07 (web, preferències i mocks)

**Gravetat**: baixa. Són casos extrems poc probables.

**Origen**: la revisió de la ronda 2 d'E7-W07 (`roadmap/reviews/E7-W07-20261001-1704-claude.md`); verificació de l'organitzador de l'1-10 al vespre (decisió E89).

**Què cal fer**:
1. **Una negativa de l'api treu només la preferència que nomena:** `INVALID_REMINDER_OPTION` → `reminderMinutesBefore`, `CHANNEL_NOT_ALLOWED` → push, i si no, `details.field`. Tot el cos només quan la negativa no es pot lligar a cap preferència. Avui una tria guardada viatja amb el valor refusat i es perd. Cal una prova amb un cos mixt.
2. **Una tria guardada que ja ha rebut el seu `2xx`** (enviada sola i res en camí des d'aleshores) surt de la safata. Avui torna a sortir com a pendent si falla una petició posterior, i es pot tornar a enviar sobre un canvi fet en un altre lloc.
3. **Un sol magatzem de reserves** per als mons del mock (R2-A6, acceptada de moment): avui els inscrits es reparteixen a la primera lectura d'un món del personal, i el món de l'abonat no compta aquestes reserves.

**On mirar**: `packages/api-client/src/preferences-saver.ts` i `mocks/fixtures/bookings.ts`.

---

## INC-56 · Menors de la ronda 2 d'E7-W03 (web, e2e del nucli)

**Gravetat**: baixa. La porta E7 del web està tancada amb altres assercions de les mateixes execucions; aquests punts fan la prova més estricta, no canvien el producte.

**Origen**: la revisió de la ronda 2 d'E7-W03 (`roadmap/reviews/E7-W03-20261005-0820-codex.md`); verificació de l'organitzador del 05-10 al matí (decisió E90).

**Què cal fer** (`apps/clubs/e2e/core/e7-core.spec.ts`):
1. **El correu d'N-13 amb la preferència apagada** s'ha d'assertar com a `SKIPPED_BY_PREFERENCE` exacte, no com a «no és `SENT`»; i `settledDetail` no ha de caure en silenci a un valor per defecte quan la fila no hi és.
2. **La sonda de `TEMPLATE_MANDATORY`** ha de ser incondicional: la plantilla d'N-05 és obligatòria per catàleg, així que la prova asserta `mandatory === true` i després el `422` quan s'intenta desactivar. Avui la sonda només s'executa si la lectura prèvia diu `mandatory`.
3. **La restauració de la plantilla editada al pas 5** va dins d'un `finally`, perquè una asserció fallida no deixi la plantilla del club del seed canviada per a la resta de la suite.
4. **Les captures de notificacions (ca/es)** s'han de prendre després de l'edició de la plantilla, perquè mostrin el text editat.

**On mirar**: l'informe de la ronda 2 d'E7-W03 i la seva revisió.

---

## INC-57 · Menors de la revisió d'E8-T13 (api, tauler)

**Gravetat**: baixa.

**Origen**: `roadmap/reviews/E8-T13-20261015-1316-claude.md` (api), troballes #2, #3 i #5; verificació de l'organitzador del 15-10 (decisió E112).

**Llista**:
- **El propietari `SYSTEM`, dues vegades.** El literal `"SYSTEM"` és a `SavedViewData` i a `SavedViewService`, i el seeder i el predicat es poden separar. Proposta: una sola constant (`SavedViewData.SYSTEM_OWNER`).
- **Escriptures de baixes sense invalidació.** `CensusMigrationService` desa `LeaveRequest` amb `leaves.save`/`leaves.insert` sense passar pel ganxo `changed(...)`, de manera que l'informe d'E8-T13 («totes les escriptures») diu massa. L'efecte és petit: les sol·licituds migrades no són `PENDING` i la cache dura 30 s (T-14-29 demana menys de 60 s). Proposta: passar-les pel mateix ganxo.
- **`LifecycleMenuIT` al paquet de reserves.** Prova cens, vistes desades i tauler, però viu a `bookings.api` per heretar `BookingFixtures`. Proposta: moure-la al costat de `DashboardIT` o a `census.api`.

**On mirar**: `SavedViewData.java:8`, `SavedViewService.java:26`, `CensusMigrationService.java:37,44`, `LifecycleMenuIT.java`.

---

## INC-58 · Menors de la ronda 6 d'E8-T12 (api, pagaments)

**Gravetat**: mitjana (proves de diners).

**Origen**: `roadmap/reviews/E8-T12-20261015-1740-claude.md` (api), troballes #2–#5; verificació de l'organitzador del 15-10 (decisió E113).

**Llista**:
- **Un oracle independent per a la cota inferior.** `sharedInterventionLowerBound` i `…round6_point2_generatedClaimsEqualTheirRecomputedDue` recalculen el deute amb la mateixa `PaymentIntentLedger.withoutReservation(id).freeFor(id)` que la ronda 6 va canviar, i conserven les reclamacions de les altres files. Proven que les reclamacions són un punt fix de l'assignació, no que l'assignació sigui correcta, i no veuen un error d'ordre. Proposta: que l'oracle ordeni ell mateix les reclamacions (per `compensationInterventionAt`) i les resti una a una.
- **L'avís quan el deute ja és 0.** A la primera entrada d'`intervene`, `upfront.intervention(...)` i `warnIntervention(...)` van abans de recalcular la reclamació: si un reemborsament pendent del tauler ja cobreix la fila, el registre demana un admin per a un pagament que no deu res. Proposta: avisar només si el deute recalculat és més gran que 0, i comentar l'obligació que només ve del camí de reserva (`operations.byKey("refund:"+id)`).
- **Lectures de més.** `intentLedger()` fa un `findOne` d'`upfront.compensation(id)` per fila quan `forIntent` ja ha carregat els documents, i corre diverses vegades per esdeveniment.
- **Un cas de la matriu.** Falta `1200,a,pending`.

**On mirar**: `CapturedPaymentInvariantsIT.java:803-804,1262-1299`, `PaymentIntentLedgerTest`, `PaymentRefunds.java:126,158-162,255-257,320`.

---

## INC-59 · Menors de la ronda 2 d'E8-W07 (web, facturació)

**Gravetat**: baixa.

**Origen**: `roadmap/reviews/E8-W07-20261015-1743-claude.md` (web), troballes #1–#3; verificació de l'organitzador del 15-10 (decisió E113).

**Llista**:
- **La prova vermella de D6 encara depèn de l'ordre d'execució.** L'asserció `periodReads === 2` s'executa abans que el component hagi aplicat la lectura 3; el codi antic només falla perquè el seu bucle passa de llarg abans que `waitFor` el mostregi. Proposta: esperar que la lectura 3 s'hagi aplicat (un marcador que la UI pinti i `findBy…`, o retenir-la darrere una tercera promesa) abans de l'asserció.
- **L'espia de `setTimeout`** retorna l'id constant `5_000` i no substitueix `clearTimeout`, que pot cancel·lar un temporitzador real amb aquest id. Proposta: espiar també `clearTimeout` o retornar un id únic.
- **Literal inventat, per confirmar per Jordi**: l'etiqueta i l'ajuda de `courses.defaultWarningThresholdM` («Llindar d'avís de col·locació», «Distància mínima… genera un avís») no surten de cap maqueta ni de l'especificació.

**On mirar**: `apps/clubs-admin/src/billing/BillingPage.test.tsx:1489-1504,1550-1556`, `packages/i18n/src/locales/{ca,es,en}/admin-settings.json:395`.

---

## INC-60 · Menors de la ronda 2 d'E9-W02 (web, recorreguts)

**Gravetat**: baixa.

**Origen**: `roadmap/reviews/E9-W02-20261015-1942-claude.md` (web), troballes #1 i #3–#9; verificació de l'organitzador del 15-10 (decisió E113). La #2 (escriptures de marcadors que es trepitgen) és a E9-W03.

**Llista**:
- **Esdeveniments de muntatge sense transport per defecte** (per a E9-W04): `subscribeBuildObstacleStatuses` llança `CORE_API_BUILD_EVENTS_NOT_CONFIGURED` si l'app no injecta `subscribeBuildEvents`; falta l'enquesta de `GET /build-sessions/{id}` acordada. Proposta: enquestar `listBuildObstacleStatuses` a interval, emetre només els canvis i aturar-la en netejar, amb una prova amb temporitzadors falsos.
- **Noms de proves**: les de la ronda 2 es diuen `#N R-16-xx …` i han de portar el `T-16-nn` corresponent (`force` → T-16-07, `finish` → T-16-10, `STALE_VERSION` de geometria → T-16-06, noms de regles i format local → T-16-22).
- **Literals inventats, per confirmar per Jordi**: les set etiquetes `courseWarningRule.*` d'`enums.json`. A més, `CourseWarningRule` es defineix a mà a `course-texts.ts` en lloc de derivar-se de `WarningRuleId` de course-core.
- **Petits**: `#courseUploads` no es buida mai; la variant `danger` del botó fa servir `text-primary-fg`; [Desa] queda actiu després d'un `409 STALE_VERSION` a `RingDoorEditor`; l'arnès e2e escriu PNG a `roadmap/evidence/E9-W02` a cada `pnpm e2e`; tres detalls de la captura fosca.

**On mirar**: `packages/api-client/src/core-api-store.ts:283,406,683-691`, `packages/api-client/src/core-api-store.payloads.test.ts`, `packages/course-ui/tests/*.tsx`, `packages/i18n/src/locales/{ca,es,en}/enums.json:360`, `packages/i18n/src/course-texts.ts:5`, `packages/course-ui/src/components/ui/Button.tsx:18`, `packages/course-ui/src/components/RingDoorEditor.tsx:446-480`, `packages/course-ui/e2e/harness.spec.ts`.

---

## INC-61 · Menors d'E9-T02 (api, recorreguts)

**Gravetat**: baixa.

**Origen**: `roadmap/reviews/E9-T02-20261015-2131-claude.md` (api), troballes #5, #8 i #9; verificació de l'organitzador del 15-10 (decisió E115).

**Llista**:
- **El llistat en memòria.** Cada `GET /courses` carrega tots els recorreguts del club, tots els `PUBLIC` de la biblioteca i de tots els comptes, i totes les muntades actives, i després filtra, ordena i pagina en memòria (`CourseListing`). A R1 va bé; creix amb la biblioteca global. Proposta: baixar `owner`, `discipline` i `updatedAt` a Mongo.
- **El recinte del primer ring.** En desar la primera geometria, el recinte es crea amb el `slug` del club sota l'índex únic global `venue_slug`: si ja existeix (un recinte de soci, per exemple), surt un `DuplicateKeyException` (500) que desfà una geometria vàlida; dues primeres desades alhora poden xocar a `venue_club`. A més, `PaperSize.A4` hauria de venir del perfil de país.
- **Noms de proves** que no són de l'id de l'especificació (`T_16_06_calibrations…`, `T_16_03_editsAreInPlace…`, `T_16_03_aCourseShownByAnActiveSetup…`).

**On mirar**: `CourseLibrary.java:89-95`, `GlobalCourseRepository.java:22-29`, `RingGeometryService.java:119-124`.

---

## INC-62 · Menors d'E9-T05 i dues peticions d'api d'E9-W03 (api i web, recorreguts)

**Gravetat**: mitjana, per la fuita del primer punt; la resta, baixa.

**Origen**: `roadmap/reviews/E9-T05-20261016-0945-claude.md` (api), troballes #2, #3, #4 i #9 i el «Not checked»; la hipòtesi 7 de l'informe d'E9-T05; `roadmap/reviews/E9-W03-20261016-0949-claude.md` (web), troballa #15, i la pregunta 3 de l'informe d'E9-W03; verificació de l'organitzador del 16-10 (decisió E119).

**Llista**:
- **El nom d'un recorregut privat previst** (R-16-16). `RingSetupService.plannedCourseName` llegeix el recorregut amb `live()`, sense visibilitat: un soci que llegeix `GET /class-sessions/{id}` veu el nom d'un recorregut privat que la classe té previst. S'espera: la projecció del soci no porta el nom d'un recorregut que no pot llegir (ni `CLUB` ni `PUBLIC`) si no el mostra un muntatge actiu.
- **La prova de `COURSE_NOT_VISIBLE`.** Registrar un muntatge des d'una col·locació comprova la visibilitat del recorregut (decisió E119, com `BuildSessionService.start`), però cap prova ho cobreix: un INSTRUCTOR que no pot llegir el recorregut privat d'un company → `COURSE_NOT_VISIBLE`.
- **El full de muntatge anterior** (R-16-10). Adjuntar-ne un de nou substitueix `buildSheetFileKey`, però el fitxer anterior queda lligat a `Placement:{id}` i no s'allibera mai. S'espera: una prova amb una segona clau, i el fitxer anterior alliberat (o netejat per P9).
- **El full desat d'una col·locació moguda** (revisió de la ronda 2 d'E9-W03, decisió E121): un `PUT /placements/{id}` que canvia la transformació de la col·locació esborra `buildSheetFileKey` i allibera el fitxer, perquè el full desat ja no hi correspon.
- **El nivell d'AgilityHub a N-31** surt com el valor de l'enum (`MEDIUM`). S'espera l'etiqueta traduïda (les de D18, quan Jordi les confirmi).
- **`S3AttachmentStorage.read`** no té cap prova contra S3 o MinIO; només hi ha la del camí local.
- **`seeds/README.md`**: una línia que digui que les col·locacions del seed no porten els avisos del client (`warnings: []`).
- **`CourseListItem.updatedAt`**: la llista D18 ha de poder mostrar la data (S16 §2). Al web, la columna, quan l'api la publiqui.
- **El motiu del desmuntatge**: el muntatge porta `dismantledReason` (`REPLACED · MANUAL · RING_INACTIVE`) o `replacedBySetupId`, perquè D16 no hagi d'inferir «Substituït» de les hores. Al web, D16 el fa servir.

**On mirar**: `RingSetupService.java:80, 283`, `BuildSheetService.java:31`, `PlacementsIT:261`, `S3AttachmentStorage.java`, `seeds/README.md`, `CourseListItem`; al web, `CoursesPage.tsx` i `RingGeometryPage.tsx:679-681`.

---

## INC-63 · Menors de la ronda 2 d'E9-T06 (api, sessions de muntatge)

**Gravetat**: baixa. A l'escala d'avui (un club, pocs mòbils per sessió) no passa.

**Origen**: `roadmap/reviews/E9-T06-20261016-2155-claude.md` (api), troballes #1–#5; verificació de l'organitzador del 16-10 (decisió E123).

**Llista**:
- **El límit d'enviadors** (`BuildSessionStreams`, `ThreadPoolExecutor(0, 64, …, SynchronousQueue)`): amb més d'uns 64 subscriptors sans en una instància, el batec de 20 s ja no troba fil i tanca fluxos sans («no sender»). S'espera: una ràfega espera en una cua acotada, i només es refusa o es tanca un flux quan els enviadors bloquejats més de `WRITE_TIMEOUT` arriben al límit; una prova amb `SENDERS + 1` lectors sans i un batec, sense cap flux tancat. El Javadoc i el «Known limit» ho han de dir bé.
- **La prova del temps d'escriptura** (`T_16_10_aStalledStreamHoldsItsSenderNoLongerThanTheWriteTimeout`) només llegeix dues propietats del connector: o es reanomena pel que comprova o es fa amb un sòcol real que deixa de llegir.
- **`server.tomcat.connection-timeout: 20s` és global**: també limita la inactivitat del cos de qualsevol petició (per exemple, les pujades de fitxers des d'una xarxa mòbil lenta). S'ha d'escriure com a límit conegut (DEPLOY) o aplicar-lo només als fluxos.
- **El ganxo de proves `pauses`** de `BuildSessionService`: un import normal, i els noms dels punts compartits entre el servei i la prova.
- **La prova dels dos `finish` alhora** només cobreix el camí del muntatge nou (`activate`); falta el del refresc.

**On mirar**: `BuildSessionStreams.java:71,122,233-235`, `BuildSessionsIT.java:646,705-711`, `application.yml:76-80`, `BuildSessionService.java:57,163,188,274`.

---

## INC-64 · Un domini d'administració verificat no serveix l'app d'administració (api i infra, consola)

**Gravetat**: baixa. A R1, cada club té com a molt un host d'administració propi, i el pas manual queda documentat (ronda 2 d'E10-T04).

**Origen**: `roadmap/reviews/E10-T04-20261016-2330-claude.md` (api), troballa #2; verificació de l'organitzador del 16-10 (decisió E123).

**El problema**: en verificar un domini `clubs-admin` a la consola, Caddy n'emet el certificat (`/internal/domains/allowed`), però tria l'SPA amb la llista estàtica `CLUB_ADMIN_HOSTS`: el host nou rep l'app `clubs` fins que algú edita la variable i recrea Caddy (`docs/DEPLOY.md`).

**S'espera**: que l'app d'un domini verificat es triï sola, per exemple amb una pista `app` de `/internal/domains/allowed` o un emparellament dinàmic de hosts.

**On mirar**: `deploy/Caddyfile:87`, `docs/DEPLOY.md:351,408`, `PlatformDomains.java`.

---

## INC-65 · Menors de la ronda 2 d'E10-T04 (api, consola)

**Gravetat**: baixa. No es filtra res: l'interceptor `@SupportRestricted` refusa el token de suport dins el handler, i la consola corre al host del producte, on no hi ha cap tenant obert.

**Origen**: `roadmap/reviews/E10-T04-20261017-1102-claude.md` (api), troballes #3, #4 i #5; verificació de l'organitzador del 17-10 (decisió E128). Les troballes #1 (el diagrama d'S17 §5) i #2 (la llista de rutes de `CONVENCIONS_API.md`) les ha corregit l'organitzador.

**El problema**:
1. `SupportAccessService.RESTRICTED` només posa `GET|POST` per a les exportacions. Spring MVC també envia `HEAD` als handlers `GET`, i cap prova cobreix `HEAD /api/v1/members/export` amb un token de suport.
2. `IdempotencyFilter` comprova que el club existeix amb `ClubLocaleSettingsProvider.settings(clubId)`, que obre `TenantContext.open(clubId)`. Si la petició ja té obert un altre tenant (una crida de consola amb el `Host` d'un club), respon `TENANT_MISMATCH` en lloc de deixar passar la petició sense clau.
3. `@com.agilityhub.core.shared.application.SupportRestricted` s'escriu amb el nom complet a 22 handlers.

**S'espera**: `HEAD` al patró, o un cas `HEAD` a `SupportAccessIT`; la comprovació del club sense obrir cap tenant, o bé un comentari i una prova amb el `Host` d'un club; un `import`.

**On mirar**: `identity/application/SupportAccessService.java:49`, `shared/api/IdempotencyFilter.java:202`, els controladors amb `@SupportRestricted`.

---

## INC-66 · Menors de la ronda 2 d'E10-T03 (api, consola i dominis)

**Gravetat**: mitjana pel punt 1, que és el mateix tipus d'error que va arreglar la ronda 2 (E125: una fallada del nostre resolutor no pot comptar com un registre perdut), en un camí més estret. Els punts 2 i 3 són menors.

**Origen**: `roadmap/reviews/E10-T03-20261017-1203-claude.md` (api), troballes #1, #2 i #3; verificació de l'organitzador del 17-10 (decisió E130).

**El problema**:
1. `JndiDomainDns`: quan el resolutor de la JVM llança `UnknownHostException`, l'adaptador demana `A` a JNDI però en llença la resposta i sempre retorna `[]`. Si JNDI troba adreces, la recomprovació ho compta com un `FAILED` real i no com a `SKIPPED`.
2. `docs/DEPLOY.md`, «Trusted domains before the DNS cutover»: la primera opció diu que es verifiquin (passos 2 i 3), però un domini de confiança ja neix `VERIFIED` i `PlatformDomains.check()` el refusa amb `INVALID_STATE`. Tampoc diu com es recupera un domini de confiança que ja és `BROKEN`: amb `platform.domainRecheckDays = 0`, P9 no el restaura mai.
3. `ClubRepository.save` llegeix el club desat i després el desa. Només és atòmic dins una transacció: les escriptures de la consola ho són, però una crida sense transacció podria trepitjar una recomprovació de P9.

**S'espera**: (1) retornar els valors `A` de JNDI, o llançar `LookupFailed` si els dos resolutors no coincideixen, amb un cas nou a `T_17_03_R_17_03_anAddressLookupTellsNoAddressFromNoAnswer`; (2) que la primera opció digui «comprova amb `dig` que els registres hi són abans que toqui la primera recomprovació», i una línia per al domini que ja és `BROKEN` (torna amb [Verifica]); (3) documentar que `save` depèn de la transacció de qui el crida, o fer la fusió atòmica.

**On mirar**: `platform/persistence/JndiDomainDns.java:56-63`, `docs/DEPLOY.md`, `platform/persistence/ClubRepository.java:151-155`, `PlatformDomains.java:123`.

---

## INC-67 · Menors d'E11-T06 (api, seguretat i límits de peticions)

**Gravetat**: mitjana. El punt 2 toca els pagaments: no es perd cap esdeveniment, perquè Stripe els reintenta, però un club pot endarrerir els cobraments d'un altre. Cal arreglar-ho abans del llançament.

**Origen**: `roadmap/reviews/E11-T06-20261017-1242-claude.md` (api), troballes #3, #4 i #5; verificació de l'organitzador del 17-10 (decisió E130).

**El problema**:
1. `AnonymousRateLimitsTest` comprova una llista escrita a mà. `SecurityInventoryIT` recorre les 430 operacions publicades, però només en comprova el 401: una ruta anònima d'escriptura que s'afegeixi més endavant sense quota passaria les proves.
2. `RateLimitFilter`: el compte de `STRIPE_WEBHOOK` només fa servir la IP. Stripe envia els webhooks de tots els clubs des del mateix grup d'adreces, així que la ràfega d'un club (una remesa, per exemple) pot fer que els esdeveniments de pagament d'un altre rebin un 429.
3. `RateLimitFilter`: `CHECKOUT_STATUS` també compta per IP per als socis i administradors autenticats, i els socis darrere el mateix NAT del club comparteixen 120 peticions per minut.

**S'espera**: (1) a `SecurityInventoryIT`, cada operació publicada anònima que no sigui `GET` té una `RateLimits.Route`, o és en una llista curta i documentada d'excepcions; (2) la clau del compte és `clubId:ip`, com a les rutes `SIGNUP_*`; (3) amb un token, el subjecte és el compte (`jwt.getName()`), com diu CONVENCIONS_API §9 (per IP i per compte).

**On mirar**: `shared/api/RateLimitFilter.java:51,91,92`, `AnonymousRateLimitsTest`, `SecurityInventoryIT`.

---

## INC-68 · Les dates de vigència d'un `Price` es desen amb tipus diferents segons el camí d'escriptura (api, preus)

**Gravetat**: alta fins que es demostri el contrari. Si alguna consulta filtra per `validFrom` o `validTo` amb un sol tipus, MongoDB no compara dates amb cadenes, i una factura podria agafar un altre preu o cap.

**Origen**: l'informe d'E11-T07 (§ «Found along the way», punt 2); decisió E131.

**El problema**: un `Price` escrit sencer (`PriceRepository.insert` o qualsevol `save`) desa `validFrom` i `validTo` com a dates BSON, tot i el `@Field(targetType = STRING)`; una actualització parcial els desa com a cadenes. Passa igual amb Spring Boot 3.5 i 4.1.

**S'espera**: un sol tipus (el que diu el model), una prova que un preu escrit de cada manera s'aplica bé en una data de facturació, i la conversió del que ja estigui desat de l'altra manera.

**On mirar**: `PriceRepository`, la classe `Price` i les consultes del preu vigent a la facturació.

---

## INC-69 · Menors d'E9-T07 (api, recorreguts)

**Gravetat**: baixa. Avui les tres còpies de la regla coincideixen; el risc és que se separin més endavant.

**Origen**: `roadmap/reviews/E9-T07-20261018-0815-claude.md` (api), troballes #2, #5, #6 i #8 (i la pregunta 2 de l'executor); verificació de l'organitzador del 18-10 (decisió E132).

**El problema**:
1. `RingSetupService.shown()` repeteix la regla de `shownCourse()` («el recorregut de la col·locació; si no, el del muntatge»), i `CourseAccess.shownBySetup` i `RingSetupRepository.showsCourse` la decideixen per separat. El límit d'R-16-16 depèn que les tres còpies no se separin.
2. Quan el recorregut de la col·locació s'ha esborrat, `courseId` és `null` i `hasViewer` és `false`, però `placement` encara porta la transformació i els `resolvedObstacles` d'un recorregut que el soci ja no pot llegir.
3. `RingGeometryIT.T_16_06_anInstructorReadsTheRingAndOnlyAnAdminWritesIt` cita T-16-06, que prova una altra regla.
4. El diagrama de la sessió de muntatge d'S16 §5 encara diu `OPEN · FINISHED`, i els estats de sessió del codi són uns altres.

**S'espera**: (1) `shown()` crida `shownCourse()`, i una prova lliga les altres dues a la mateixa regla; (2) `placement = null` quan el recorregut mostrat és `null`; (3) un nom de prova que no citi T-16-06; (4) el diagrama amb els estats del codi (E109).

**On mirar**: `courses/application/RingSetupService.java:200-241`, `CourseAccess`, `RingSetupRepository`, `RingGeometryIT.java:164`, S16 §5.

## INC-70 · Menors d'E11-T07 (api, Spring Boot 4)

**Gravetat**: baixa. Cap d'aquestes troballes no bloqueja: l'escaneig no té cap CRITICAL corregible.

**Origen**: `roadmap/reviews/E11-T07-20261018-0814-claude.md` (api), troballes #3, #4, #5 i #6, i la pregunta 5c de l'executor; verificació de l'organitzador del 18-10 (decisió E132).

**El problema**:
1. `bin/security-scan-test.py` exigeix que la llista d'excepcions actives sigui exactament `[]`: una excepció futura que compleixi la política no es podria afegir sense esborrar la prova, i la prova de validesa de les excepcions ja no comprova res.
2. Jackson 3 (3.1.5) arriba amb `spring-boot-starter-webmvc`, i Jackson 2.21.5 i 3.1.5 tenen troballes HIGH corregides a 2.21.7 i 3.1.7.
3. `docs/DEPLOY.md` diu que els dos fitxers de Compose no canvien, però `deploy/compose.prod.local.yaml` ha canviat (`SPRING_MONGODB_URI`).
4. El pas 3 d'E11-T07 parlava de l'*starter* de Micrometer; les mètriques arriben amb `spring-boot-starter-actuator`, i l'informe no ho diu.

**S'espera**: (1) la prova comprova només que cap excepció activa nomena `spring-webmvc` 6.2.19, CVE-2026-47884 o CVE-2026-47890; (2) les dues línies de Jackson fixades als pedaços, i l'escaneig de la imatge sense aquestes troballes; (3) i (4) una línia a `DEPLOY.md` per a cadascuna.

**On mirar**: `bin/security-scan-test.py:69-79`, `pom.xml`, `docs/DEPLOY.md:571-572`.

## INC-71 · Una prova inestable de les sessions de muntatge (api, proves)

**Gravetat**: baixa. Només fa fallar una execució de tant en tant, però pot tornar vermella la CI.

**Origen**: la pregunta 7 de l'informe d'E11-T01 (els seus registres `10` i `11`); verificació de l'organitzador del 18-10 (decisió E132).

**El problema**: `BuildSessionsIT.T_16_10_theQueryTokenIsAcceptedOnlyByTheEventStream:328` va fallar un cop: va llegir el `Content-Type` de l'SSE abans que estigués fixat, mentre tres compilacions compartien el contenidor. Sola, passa.

**S'espera**: la prova espera les capçaleres del flux, i 20 execucions seguides passen.

**On mirar**: `BuildSessionsIT.java:328`.

## INC-72 · Menors d'E9-W06 (web, recorreguts)

**Gravetat**: baixa. El visor funciona; el risc és un `e2e:core` que falla en diumenge i un plànol del soci que no coincideix amb la pista si el recorregut s'edita després de col·locar-lo.

**Origen**: `roadmap/reviews/E9-W06-20261018-1315-claude.md` (web), troballes #1, #2, #4, #5 i #6; verificació de l'organitzador del 18-10 (decisió E134).

**El problema**:
1. Les sis execucions d'`e2e:core` d'E9-W06 porten `E9_FORCE_BARE_RING_UNDO=1`: el pas normal d'`e9-core` no s'ha executat en verd sobre el codi final, i una classe puntual del mateix diumenge respon `422 CLASS_NOT_BOOKABLE`.
2. El plànol del soci dibuixa el recorregut viu (`GET /courses/{id}`) amb la transformació desada, no els `resolvedObstacles` de la col·locació: si el recorregut s'ha editat després, el soci veu el nou a la posició antiga, sense cap avís.
3. El gestor MSW de `GET /rings/:id` torna la pista sencera a qualsevol rol.
4. Detalls: les entrades de turbo no inclouen els ajudants d'`e9-core`, i el viewBox del plànol talla els números d'una col·locació que surt de la pista.

**S'espera**: (1) una execució normal en un dia feiner, i la dependència del diumenge resolta o anotada; (2) el plànol del soci amb `resolvedObstacles`; (3) el gestor per rol (la projecció de lectura per a l'INSTRUCTOR, `403` per al soci); (4) els dos detalls.

**On mirar**: `e2e/core/e9-core.spec.ts:1187-1240`, `apps/clubs/src/courses/viewer-model.ts:62-102`, `SetupViewerPage.tsx:309-322`, `packages/api-client/src/mocks/handlers.ts:3604-3609`, `turbo.json:19-28`.

## INC-73 · L'app dels socis ofereix editar el perfil durant una impersonació (web)

**Gravetat**: baixa. Només passa en una impersonació: el canvi falla amb un error en lloc de no oferir-se.

**Origen**: `roadmap/reviews/E11-T09-20261018-1457-claude.md` (api), troballa #5; verificació de l'organitzador del 18-10 (decisió E136).

**El problema**: des d'E11-T09 (INC-37, punt 5), l'API refusa `PATCH /me` a un token d'impersonació (S01 §6), però l'app dels socis encara mostra l'edició del nom i de l'idioma quan el compte està impersonat. És una acció morta (regla 3 d'AGENTS).

**S'espera**: amagar l'edició del nom i de l'idioma durant una impersonació, amb una prova. Ho fa E11-W03.

**On mirar**: el perfil de l'app dels socis (`apps/clubs/src/profile/`) i S01 §6 (`PATCH /me`).

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
- 10 a 375 px (captura d'E4-W15 al core real): el títol de la classe del seed «Obed. u…» acaba amb punts suspensius (afegit el 27-09).

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

## INC-42 · Revisió d'E5-T25: el sexe dels gossos migrats i detalls del contracte de reserves (api)

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

---

## INC-43 · Detalls de la revisió d'E5-T26 (api, fitxers)

**Gravetat**: baixa. Avui no fallen. La revisió independent d'E5-T26 (26-09) els ha trobat. El menor de la mateixa revisió (un SVG es mostrava `inline` a l'origen de l'api) i el detall de CORS van a la tasca E5-T27 (pas 8, E61).

**Origen**: `roadmap/reviews/E5-T26-20260926-1759-claude.md` (api), detalls #3 i #6.

**Llista**:
- **Tests de les descàrregues locals.** `SignupSecurityFixesIT.CONVENCIONS_API_5_localDownloadsAnswerTheStoredTypeAndNameAndShowImagesInline` fa servir un PNG de `DOG_DOCUMENT`: no descarrega cap `DOG_PHOTO` ni cap `ACTIVITY_IMAGE` per `/attachments/files/{id}`. Els dos camins de reserva tampoc no tenen test: un tipus desat que no es pot llegir respon `application/octet-stream`, i el comodí `image/*` (que `validate()` accepta com a tipus) respon `attachment`.
- **`Range` a les descàrregues locals.** Les descàrregues locals no responen a les peticions HTTP `Range`. Un `ACTIVITY_DOCUMENT` `video/mp4` (el permet `files.allowedTypes`) no es reprodueix a Safari des de la pila local, que és la que funciona fins al release (A31). A S3 no passa.

**On mirar**: `AttachmentsController.download`, `AttachmentService.openLocal`, `SignupSecurityFixesIT.java`.

---

## INC-44 · Preguntes de l'informe d'E5-W02 (web i api, entrenaments)

**Gravetat**: baixa. Avui funciona amb una alternativa: els formularis de 24 i D12 ofereixen selectors d'hora i l'api valida el tram.

**Origen**: `roadmap/tasks/E5-W02.md` (web), preguntes 1–3 de l'informe; verificació de l'organitzador del 27-09 (decisió E67).

**Llista**:
- **Pistes sense entrenament lliure a 24 i D12.** `GET /training-slots?ringId=` només inclou les pistes amb `active ∧ allowsFreeTraining`, també per a l'instructor (`TrainingSlotService.bookableRings`). Per això Petita i Cadells (el mockup 24 reserva Petita) no tenen graella, i el formulari ofereix els selectors d'hora. Proposta: una projecció del personal que inclogui les pistes actives sense entrenament lliure (classes i bloquejos, sense capacitat).
- **`FREE_TRAINING` desactivat.** `/training-slots` respon 404, i cap ruta que l'instructor pugui llegir porta `club.openingHours`: 24 i D12 no tenen graella. Proposta: la mateixa projecció sense el mòdul, o l'horari a `/branding`.
- **«18:50 classe».** Una cel·la ocupada per una classe mostra l'hora de la fila, no la de la classe (S09 §13-3). Proposta: `SlotCell.classStartsAtLocal`.

**On mirar**: `TrainingSlotService.java` (api), `apps/clubs/src/training/*`, `apps/clubs/src/instructor/RingBlockPage.tsx`, `apps/clubs-admin/src/training/RingBlockCard.tsx` (web).

---

## INC-45 · Comprovació global de la seguretat publicada (api)

**Gravetat**: baixa. Avui cada IT de contracte ho comprova per a les seves rutes (per exemple, `E7ContractIT` per a les 21 d'S11).

**Origen**: `roadmap/tasks/E7-T01.md` (api), pregunta de la ronda 2; verificació de l'organitzador del 28-09.

**Proposta**: un sol test que, per a cada operació de l'OpenAPI publicat, compari el requisit de seguretat efectiu (el `security` de l'operació o, si no en té, el del document) amb una crida sense token: 401 exactament a les rutes amb bearer. `/oauth2/token` n'és l'excepció, perquè respon 401 per l'autenticació del client i no per la falta de bearer.

**On mirar**: `E7ContractIT.WP_11_A_everyOperationPublishesTheAuthenticationItEnforces` (el patró), `docs/openapi/openapi.json`.

---

## INC-46 · Nits de la ronda 2 d'E6-T04 (api, processos)

**Gravetat**: baixa. Avui funcionen.

**Origen**: `roadmap/reviews/E6-T04-20260928-1212-claude.md` (api), troballes #3–#5; verificació de l'organitzador del 28-09 (decisió E70).

**Llista**:
- **El recompte de [Simula] de P3.** P3 és un sol ítem per club i dia, i la simulació compta ítems: `WOULD_NOTIFY: 1` per a quatre avisos, mentre que l'execució real diu `{notices: 4, late: 1}` (R-15-08, «pla = efectes»). A més, `detail.attendances` d'aquest ítem no té límit (per exemple, en una recuperació després d'una aturada llarga), i el límit de 500 ítems de la traça ja no el cobreix. Proposta: en simulació, registrar `notices` i `late` del pla amb el comptador del context, i limitar o resumir `detail.attendances`.
- **L'escombrada en bloc.** `WaitlistService.sweepStarted(Instant)` ja no té cap crida de producció, només tests, i no té la guarda de `WAITLIST` del camí de P8 (R-15-03). Proposta: treure-la o posar-hi la mateixa guarda.
- **La lectura de P8 a cada minut.** `WaitlistEntryRepository.liveAll()` llegeix totes les entrades vives del club a cada tic (cada minut i club), també les de classes de setmanes endavant, i les ordena en memòria; no hi ha cap índex `{clubId, state}` declarat. Proposta: partir de les classes `ACTIVE` d'S06 ja començades i amb `counters.waiting > 0` (més les entrades la còpia de les quals diu que la classe ha començat, per a les classes esborrades) i llegir només les seves entrades.

**On mirar**: `NoShowNoticesJob.java:37`, `WaitlistService.java:186`, `WaitlistEntryRepository.java:65`.

---

## INC-47 · Un conflicte d'escriptura de Mongo pot acabar en 500 (api, comú)

**Gravetat**: mitjana. Només passa amb dues peticions simultànies sobre el mateix document, però llavors l'usuari veu un error intern en lloc del codi de l'especificació.

**Origen**: revisions d'E6-T03, rondes 3 i 4 (`roadmap/reviews/E6-T03-20260928-1028-codex.md` i `…-1534-codex.md`, api); verificacions de l'organitzador del 28-09.

**El problema**: `IdempotencyFilter` obre una transacció per a cada petició amb clau, llevat de les rutes d'una llista (l'alta, S07, S08, S09, el full d'assistència, les observacions). A les altres, el servei s'uneix a la transacció del filtre: els seus propis reintents (`FollowupTransactions`, `TransactionRetries`) no s'executen, i un `WriteConflict` o un `TransientTransactionError` arriba al gestor d'errors genèric, que respon `500 INTERNAL_ERROR`. La llista s'ha d'anar ampliant ruta per ruta.

**Proposta**:
- Xarxa de seguretat: el gestor d'errors respon `409 STALE_VERSION` (que el client pot reintentar) a qualsevol error transitori de transacció que li arribi, mai 500.
- Estudiar si el camí per defecte del filtre pot reintentar la petició sencera (el cos ja es desa, la resposta també), després de revisar quines rutes fan crides externes dins la transacció.
- Les rutes d'E6-T03 es corregeixen ara, a la seva ronda 5.
- Les rutes de les sessions de muntatge amb clau (revisió d'E9-T04, #1) es corregeixen a E9-T06 (decisió E118).

**On mirar**: `IdempotencyFilter.java` (la llista i el camí per defecte), `FollowupTransactions.java`, `TransactionRetries.java`, el gestor d'errors global.

---

## INC-48 · Nits de la ronda 5 d'E6-T03 (api, seguiment)

**Gravetat**: baixa. Avui funcionen.

**Origen**: `roadmap/reviews/E6-T03-20260930-1238-claude.md` (api), troballes #2 i #4; verificació de l'organitzador del 30-09 (decisió E73).

**Llista**:
- **L'estat de la resposta repetida.** A `TasksController`, `AttachmentsController` i `FollowupController`, l'estat que es desa amb la clau (`keyed(201|200, …)`, `keyedNoContent` = 204) és una segona còpia, escrita a mà, del `@ResponseStatus` de la ruta. Si mai divergeixen, una clau repetida respon un estat diferent del primer (CONVENCIONS_API §7). Proposta: llegir-lo del `@ResponseStatus`, o una asserció de resposta repetida per a cada ruta amb clau.
- **El Javadoc de `FollowupTransactions`** (línies 15–19) es talla a mitja frase. Proposta: refer-ne l'ajust.

**On mirar**: `TasksController.java:210,239,254`, `AttachmentsController.java:55,77`, `FollowupController.java:120`, `FollowupTransactions.java:15-19`.

---

## INC-49 · Les rutes de consola amb clau i el club del filtre d'idempotència (api)

**Gravetat**: mitjana. Avui no passa: la consola (S17) és d'E10.

**Origen**: la nota 2 de l'informe d'E5-T29 (llegida al codi, no executada); verificació de l'organitzador del 30-09 (decisió E75).

**Reproducció**: `POST /platform/clubs/{clubId}/jobs/{name}/trigger` amb `Idempotency-Key` i un token de plataforma (sense `clubId`) → `IdempotencyFilter` busca el club al JWT i respon `NO_MEMBERSHIP` abans que la ruta el llegeixi del camí.

**S'espera**: a les rutes `/platform/clubs/{clubId}/…`, el filtre pren el club del camí, després que l'autorització de la plataforma l'accepti (E62), i la clau s'hi guarda amb aquest club.

**On mirar**: `shared/api/IdempotencyFilter.java` (el club de la clau), `platform/application/jobs/JobAdminService.java` (`platformTrigger`).

---

## INC-50 · Nits de la ronda 2 d'E5-T29 (api, processos)

**Gravetat**: baixa.

**Origen**: `roadmap/reviews/E5-T29-20260930-1939-claude.md` (api), troballes #1–#3; verificació de l'organitzador del 30-09 (decisió E76).

**Llista**:
- **El `JOB_TRIGGERED` d'un reintent.** Al camí sense transacció (`NOT_SUPPORTED`), l'auditoria s'escriu després que l'execució es desi. Si aquesta escriptura falla, la resposta és un 500 i la clau s'allibera; el reintent respon l'execució, que es queda sense l'entrada. Proposta: al reintent, escriure l'entrada si no n'hi ha cap per a aquell `runId`.
- **La vida de la clau, dues vegades.** `IdempotentOperation.KEY_LIFETIME` i les 24 h escrites a mà d'`IdempotencyRepository` (índex TTL i `claim`). Proposta: una sola constant.
- **T-09-30 sense tenant.** Els tests de cerca del registre i dels bloquejos porten l'id T-09-30 però no comproven que no surti res d'un altre club. Proposta: una asserció amb una reserva i un bloqueig d'un altre club que coincideixin amb el `q`.

**On mirar**: `JobAdminService.java:124-128`, `IdempotentOperation.java:11`, `IdempotencyRepository.java:25,32`, `TrainingRegisterContractIT`.

---

## INC-15…INC-41 · Revisió global del 26-09

**Origen**: la revisió global que Jordi va demanar el 26-09 («verificación en detalle de todo lo realizado» i «verificar el roadmap y todas las tareas futuras»): tretze auditories independents del codi (api E0–E5, web E0–E4) i del roadmap. L'informe és `backlog/revisio-26-09/REVISIO_GLOBAL_26-09.md`. El §2 hi té el registre complet, amb l'evidència fitxer:línia, i `reports/` té les tretze auditories.

**Com es corregeixen** (decisió E51, avançar correccions; A33 ✓ Jordi 27-09): els deu majors es corregeixen ara, a les tasques E5-T27 i E5-T28 (api) i E4-W16 (web), abans que la resta d'E6, d'E8 i d'E5-W04. La resta espera la passada de correccions (E11-T02 i E11-W02), o va plegada dins la tasca d'E8 que toca el mateix codi.

**Numeració:** la revisió va reservar INC-15…INC-41. La incidència de la revisió d'E5-T25 que s'havia escrit com a INC-15 el mateix dia és ara **INC-42**.

## INC-74 · Menors de la verificació d'E11-T08 (ronda 2) i d'E11-T11 (api)

**Gravetat**: baixa. Cap d'ells mou diners ni trenca una prova; són acabats de les dues tasques verificades el 21-10 (E138, E139).

**Origen**: la revisió independent d'E11-T08 ronda 2 (sessió del núvol, 21-10) i la verificació d'E11-T11 contra `roadmap/reviews/E11-T11-20261018-1554-claude.md`.

**El problema** (fitxer:línia a la verificació de cada tasca):
1. `RateLimits`: una memòria cau per ruta amb `maximumSize(100 000)` cadascuna → 21 rutes × 525 B/cub ≈ 1,1 GB en el pitjor cas; abans el límit total eren 52 MB.
2. `RateLimitFilter` → `ClubConfigService.known` → `clubs.ids()` sense captura: si Mongo cau, el webhook d'Stripe respon 500 des del filtre en lloc de caure a `unknown:ip`.
3. Amb un preu bessó reportat (dos `CURRENT` del mateix concepte), `SignupPlanData.from` i `OfferViews.current` encara ofereixen el pla amb un dels dos preus (la facturació refusa, l'oferta no). **Decisió E138**: el pla no s'ofereix públicament i D2 refusa amb `PRICE_OVERLAP`.
4. `PriceRepository.normaliseValidityDates()` i `legacyStartTwins()` són públics i llegeixen tots els clubs; la retirada d'un bessó es registra a INFO; `named()` mira `collections.priceId` i `pack_balances.priceId`, que no existeixen (les referències reals: `members.priceId`, `invoices.lines.priceId`, `pending_charges.priceId`); el pas a pas de `DEPLOY.md` compta els mateixos camps.
5. `StoredDays`: la regla depèn del desplaçament UTC de qui escriu, no de la zona («Pacific/Auckland» només val a l'hivern); el Javadoc i la prova diuen «zona».
6. `CATALEG_ERRORS.md`: la fila S12 no llista `PRICE_OVERLAP` (S12 §6 sí).
7. RGPD: el camí `cancelAccountRequest → cleanAfterCommit` només té la comprovació amb Mockito; cap IT deixa un fitxer pendent abans del `DELETE`.
8. `DEPLOY.md` i el Javadoc de `BankAccountVault.seal` diuen que una execució sense perfil no arrenca; amb `spring.profiles.default: local` arrenca com a `local`. **Decisió E139**: és així per disseny; es corregeix el text i la prova.
9. `ErasuresJob.filesCleaned` no compta els fitxers que esborra l'`afterCommit`.
10. S01 §3 llista `graceSiblingAt` i el `RefreshToken` no el mapa; `ErasureFilesCleanupTest…OtherStatesAreLeftAlone` ja no descriu la prova; `CONVENCIONS_API.md` §7 no defineix la columna «Idempotent».

**S'espera**: ho fa E11-T13 (els passos 1–10), amb una prova per punt.

**On mirar**: `shared/application/RateLimits.java`, `shared/api/RateLimitFilter.java`, `platform/application/ClubConfigService.java`, `clubs/catalogs/persistence/PriceRepository.java`, `shared/domain/StoredDays.java`, `clubs/catalogs/application/SignupPlanData.java`, `platform/application/privacy/*`, `payments/application/BankAccountVault.java`.

## INC-75 · Menors de la ronda 2 d'E11-W02 i restes d'INC-41 (web)

**Gravetat**: baixa. Estats i textos; cap flux crític.

**Origen**: la revisió independent d'E11-W02 ronda 2 (sessió del núvol, 21-10; E142) i la llista de punts d'INC-41 que E11-W02 va deixar oberts amb motiu.

**El problema**:
- (a) `apps/clubs-admin/src/App.tsx` (`ConsolePage`, `CoursesRoute`): chunks mandrosos sota `Suspense fallback={null}` sense boundary — després d'un desplegament, un `import()` que falla desmunta tota la consola (el mateix defecte que la ronda 2 va corregir a l'app dels socis) i no hi ha estat de càrrega.
- (b) 21 `AttendancePage` pinta un `<ul>` buit sense `EmptyState`; 22 `StudentCardPage` i el tauler de tasques fan servir un `<p>`; `/espera/:id`, `/entrenaments/:id`, `/activitats/:id`, 21/22/tasques no són a la matriu d'estats.
- (c) `scroll.ts` posa `scrollRestoration = "manual"` sempre: en recarregar (F5) una pantalla desplaçada, s'obre a dalt.
- (d) `ScreenBoundary`: un chunk que no es pot carregar diu «No hi ha connexió amb el servidor»; una fallada de pintat mostra `errors:INTERNAL_ERROR` (vosaltres, codi de referència) al costat de «Torna-ho a provar» (tu).
- (e) `AuditPage`: el límit superior del rang és `l'endemà 00:00` i el `between` de l'api és inclusiu (CONVENCIONS_API §4).
- (f) `CensusListPage`: un `PaymentMethodView.channel` fora de CASH/TRANSFER/BIZUM pinta la clau i18n.
- (g) les proves de la ronda 2 duen «E11-W02 round 2 (review #n)» i no T-xx-nn; (h) la prova de sondeig `R-12-13` d'E8-W01 depèn del rellotge real.
- D'INC-41: els diners es divideixen per 100 a 40 llocs (cal `fmtMinor(amountMinor, currency, locale)` a `packages/i18n`); «7:30» a `enums:cancellationReason.RISK_REVIEW` i `admin-scheduling:calendar.selected.exempt` (E11-T13 exposa `ClassCancellation.reviewTime`; el paràmetre `classes.riskReviewTime` per al xip); la confirmació `RING_HAS_BOOKINGS` de D16 sense text; «DNI» a 28/D5/D2 (→ «Document d'identitat»); la forma neutra d'`OTHER` a 11 claus; el registre d'`errors` (vosaltres/usted) contra el tu/tú de la resta.

**S'espera**: ho fa E11-W05, amb una prova per punt i els textos que el Jordi aprovi (E11-W05 pas 6).

**On mirar**: `apps/clubs/src/{states.test.tsx,ScreenBoundary.tsx,scroll.ts}`, `apps/clubs-admin/src/{App.tsx,audit/AuditPage.tsx,census/CensusListPage.tsx}`, `packages/i18n/src/locales/*/{enums,errors,census,admin-census}.json`.

## INC-76 · La home amb sessió (03) no arriba al TTI de 3 s (web)

**Gravetat**: baixa. Mesura d'E11-W02 ronda 2 (log 82): 3 987–4 184 ms a `/inici` amb la build de producció i una sessió simulada per HTTP; la 01 (visitant) dona 2 874–2 981 ms i la PWA puntua 100.

**Origen**: E11-W02 ronda 2, punt 10; decisió E142.

**El problema**: el camí de la 03 carrega ≈330 kB en cinc onades seqüencials des de l'únic chunk `screens` (decisió A38). Tres experiments (logos com a URL, el chunk de pantalles i la sessió arrencats amb `/branding`, tots els espais de noms en una onada) no van guanyar res.

**S'espera**: un chunk per pestanya amb les fonts precarregades, mesurat igual. Després del llançament; la fita de 3 s es demana a la 01 i la 03 queda amb un pressupost de 4,5 s com a línia de base (E142).

**On mirar**: `apps/clubs/src/App.tsx` (el chunk `screens`), `vite.config.ts`, `roadmap/evidence/E11-W02/82-*.log`.

## INC-77 · La cadena de diners abans del llançament — auditoria del 21-10 (api)

**Gravetat**: alta. Dues troballes són crítiques (F1 i F2: abonats que no es facturen mai, sense cap incidència visible); quatre són majors (F3–F6); la resta, menors.

**Origen**: auditoria independent de l'organitzador (sessió del núvol, 21-10, E145) de S12/S13/S18 contra el codi de `main` `8d73ad2a`, amb les regles comprovades amb sondes jshell sobre les classes compilades. Detall complet (fitxer:línia, escenari, prova que ha de fallar primer) a la tasca E11-T15.

**El problema** (resum):
- **F1 (crítica)** `MemberPlanChangeService.change` no escriu mai `nextInvoiceDate`: un abonat de pack (data nul·la) que passa a mensual (el flux del 40 %) queda `NOT_DUE` a totes les execucions i no es factura mai; no hi ha cap ruta d'administració per posar-li la data.
- **F2 (crítica)** `InvoicingRules.month` assigna els membres del grup al titular sempre que el titular és ACTIVE; si el mes del titular queda exclòs (`AFTER_LEAVE`), els membres surten `BILLED_VIA_HOLDER` i ningú els factura aquell mes.
- **F3 (major)** La migració posa `nextInvoiceDate = 1 del mes següent al tall` a tots els mensuals, també als d'efectiu que ja han pagat el semestre: al desembre se'ls emet un rebut (o dos si el tall és d'octubre).
- **F4 (major)** Un mensual no-efectiu factura només el mes M: un mes saltat (`NO_BANK_ACCOUNT`) no es recupera mai.
- **F5 (major)** Es pot retrocedir una remesa `GENERATED` després de la data de cobrament (si l'administrador no l'ha marcat «enviada»): el banc ja ha cobrat i la següent execució en torna a emetre una → doble cobrament; i P5i no liquida mai.
- **F6 (major, dia D)** Una fila de `rebuts.csv`/`packs.csv` amb estat/data/pla desconeguts és `ERROR` i «Validation failed; no changes applied»: no es carrega res (R-18-11b diu que la qualitat de dades mai refusa).
- **F7–F9, F11 (menors)** referències de mandat de 44 caràcters; identificador de creditor sense mod-97; packs migrats `CLOSED` que no caduquen; `period` del rebut manual.
- **F10 (runbook)** l'execució de facturació el dia 26 o després; la sèrie de numeració és l'any d'emissió (el gener de 2027 s'emet el 25-12-2026 amb sèrie 2026: avisar la gestoria); dies TARGET a Nadal.

**S'espera**: E11-T15 (fil B, després d'E11-T10), amb les decisions E146 (ruta `PUT /members/{id}/next-invoice-date` auditada + fitxer «pagat fins» a la migració) i E147 (bloquejador `COLLECTION_DATE_PASSED` + avís a D6 el dia abans del cobrament; «Marca com a enviada» segueix sent explícita). La porta E11 (E11-T12) l'espera. El runbook d'E12-T02 recull F10, el `--cut-over` del mes de llançament i l'assaig amb els `rebuts.csv`/`packs.csv` reals.

**On mirar**: `payments/domain/InvoicingRules.java`, `payments/application/MemberPlanChangeService.java`, `payments/application/{BillingRunService,BillingExpirations,RemittanceService}.java`, `migration/application/{PlayoffPlanner,PlayoffBillingPlanner,PlayoffImportService}.java`, `payments/domain/SepaDirectDebits.java`, `PackBalanceService.java`.

## INC-78 · Supressió de dades i accés de suport — auditoria del 21-10 (api)

**Gravetat**: mitjana (S1 és major: una supressió deixa un nom de pila).

**Origen**: la mateixa auditoria (E145), part de reserves/identitat/RGPD. Detall a E11-T16.

**El problema**: (S1) `BookingErasureRepository` i `ActivityErasureRepository` només buiden `bookedBy/cancelledBy/registeredBy.displayName` quan `memberId` és el suprimit: la reserva d'un altre abonat feta pel suprimit (grup familiar) o l'anul·lació feta per un instructor-abonat conserven el seu nom; també els `familyGroupClaim.holderName/dogName` que altres sol·licitants van escriure sobre el titular suprimit. (S2) L'outbox ja esborra els registres `PUBLISHED` del subjecte, però no els `FAILED` ni els `PENDING`, i `job_runs.items.detail…dogName` queda 90 dies (les cinc darreres execucions, per sempre). (S3) Un token de suport pot `POST`/`DELETE /members/{id}/erasure`; només `DELETE /support-accesses/{id}` porta `@SupportRestricted`. (S4) `BookingTransactions` no reintenta `DuplicateKey` (amb els carrils locals no pot passar).

**S'espera**: E11-T16 (fil D, després d'E11-T13). La porta E11 l'espera.

**On mirar**: `clubs/bookings/persistence/BookingErasureRepository.java:28-31`, `clubs/activities/persistence/ActivityErasureRepository.java:26-28`, `identity/application/SupportAccessService.java:49-53`, `identity/api/CurrentUserFilter.java:55-57`, `clubs/bookings/application/BookingTransactions.java:52-57`.

## INC-79 · Accés, sessió i estats del web — auditoria del 21-10 (web)

**Gravetat**: alta (tres majors que un abonat o un administrador trobarà la primera setmana).

**Origen**: auditoria independent de l'organitzador (E145) del web `main` `10644b87` contra el contracte `openapi.next.json` (= api `8d73ad2a`). Detall a E11-W06.

**El problema**: (W1) `RequireAuth` envia a `/entrar` sense adreça de retorn: l'enllaç profund d'N-15 «AGAFA LA PLAÇA», d'N-04 o de la D2 d'N-01 acaba a `/inici`. (W2) Un administrador sense rol MEMBER que ha marcat «Recorda la meva tria» → `/inici` amb `GET /me/home` i `/me/bookable-classes` a 403 (el mock MSW no comprova el rol). (W3) Tres pestanyes que renoven alhora dins del marge de 30 s → `REFRESH_REUSED` i tota la família revocada. (W4) El «Menú d'usuari» del back-office no fa res i no hi ha «Tanca la sessió». (W5) `Account.locale` no s'aplica en un segon dispositiu. (W6) `<html lang="ca">` fix. (W7) D10 formata instants en UTC. (W8) La 30 formata amb un `Intl` propi i no té reintent. (W9–W11) Els codis nous del contracte (`PRICE_OVERLAP`, `READMISSION_PENDING` a D10, `CURRENCY_MISMATCH`, `proposals.planId` nul) no tenen text ni destí. (W12) Tres codis de l'alta surten al pas de pagament. (W13) Una ruta sense el rol pinta un `main` buit.

**S'espera**: E11-W06 (fil C, després d'E10-W02 i abans d'E11-W03, que fa els E2E).

**On mirar**: `packages/auth/src/{session.tsx,auth-client.ts}`, `apps/clubs/src/App.tsx`, `apps/clubs-admin/src/App.tsx`, `apps/clubs-admin/src/census/CensusRecordPage.tsx`, `apps/clubs/src/InfoPage.tsx`, `apps/clubs/src/SignupPage.tsx`.

## INC-80 · Menors del back-office i de l'empaquetat — auditoria del 21-10 (web)

**Gravetat**: baixa.

**Origen**: la mateixa auditoria (E145), punts W14–W17 exclosos d'E11-W06.

**El problema**: (W14) la barra lateral del back-office són `<a href>` plans: cada clic recarrega el document i fa una rotació de token (A38 només es va aplicar a l'app dels socis); (W15) «Entra com l'abonat» obre la pestanya després de l'`await` i Safari la pot bloquejar; (W16) `mockServiceWorker.js` es publica a producció; (W17) el pressupost de JS de `clubs-admin` és a 661,9/670 kB.

**S'espera**: W15 a E11-W05; W14, W16 i W17 després del llançament (W14 és una millora de rendiment que canvia la navegació del back-office: no abans de l'assaig).

**On mirar**: `packages/ui/src/components.tsx:645-651`, `apps/clubs-admin/src/App.tsx:1005-1008`, `apps/clubs-admin/src/census/CensusRecordPage.tsx:1074-1085`, `apps/clubs/public/mockServiceWorker.js`, `.size-limit.json`.

## INC-81 · Menors de les verificacions d'E10-W03 (ronda 3) i d'E11-W01 (ronda 2) (web)

**Gravetat**: baixa. Cap afecta un flux crític; són acabats i cobertura de proves.

**Origen**: les verificacions de l'organitzador del 21-10 (revisors propis + la revisió del kit, E151).

**El problema**:
- (a) `packages/api-client/src/mocks/console-handlers.ts:1351-1373`: el `DELETE` d'un accés de suport no compara el club de qui crida amb el de la concessió, i `POST …/support-access` torna el mateix token per a tots els clubs; cap prova pot exercir el camí «concessió d'un altre club → 404» (S17 R-17-10). No és un forat de producció.
- (b) `apps/clubs-admin/src/console/tabs/ParametersTab.tsx:140-143`: si la relectura després d'un `PUT` 200 falla, la fila manté el valor anterior al costat de «Desat» fins que el reintent funciona; cal pintar la fila amb la resposta del `PUT`.
- (c) `e2e/core/e10-core.spec.ts:484-489`: cada execució deixa un domini verificat no principal al «club mínim» (es pot eliminar amb [Elimina] després del `/branding`, E123).
- (d) `apps/clubs-admin/src/census/MemberPrivacyCard.tsx:185-206`: `erasureLoading` només s'inicialitza; si l'abonat passa a LEFT en memòria, «Suprimeix les dades (RGPD)» surt abans que `GET …/erasure` respongui.
- (e) `apps/clubs/src/profile/DataExportCard.tsx:73-77`: el `jobId` de l'URL sobreescriu el paquet desat abans de llegir-lo; un 404 esborra un paquet vàlid. Desar l'id de l'URL només després d'una lectura bona.
- (f) `packages/api-client/src/mocks/handlers.ts:2863`: `GET /exports` respon 200 a un token MEMBER; el core respon 403 (ajustar la prova «list leaves it out»).
- (g) El mock de `PATCH /me` amb impersonació respon `IMPERSONATION_DENIED`; l'snapshot adoptat diu `403 FORBIDDEN`: alinear-ho quan E11-W06 adopti l'snapshot.
- (h) `apps/clubs-admin/src/audit/ExportsDrawer.tsx:165-169` (E8-W04): l'avís «Preparant l'exportació…» es manté quan la feina seguida ja és READY.

**S'espera**: ho fa E11-W05, amb una prova per punt.

**On mirar**: els fitxers citats.

## INC-82 · Els canvis de TTL fan servir `collMod`, que l'usuari de producció no té (api)

**Gravetat**: mitjana. Latent: no hi ha cap volum de producció encara; una instal·lació nova crea l'índex de zero. Bloqueja el dia que es pugi un volum autenticat anterior al 18-10 o que es canviï una retenció.

**Origen**: verificació de la ronda 2 d'E11-T09 (revisor propi, 21-10; E152).

**El problema**: `ImpersonationGrantRepository.ensureIndexes` (≈ 62-63) i `SecurityEventRepository` (≈ 22-23) pugen un índex TTL existent amb `IndexOperations.alterIndex` → `collMod`. `deploy/mongo-health.js` crea l'usuari de l'aplicació amb `readWrite` i prou, i a MongoDB 7 `readWrite` té `createIndex`/`dropIndex` però no `collMod` (`dbAdmin`). La IT nova d'E11-T09 ho prova sobre un Mongo sense autenticació. També: `deploy/test_e11_t09.py` (la guarda AST de la prova de Compose, el reintent de Maven, els tests de Caddy d'INC-64) només corre al Mac de l'executora, no a la CI.

**S'espera**: E11-T16 (passos 5 i 6): drop + create en lloc d'`alterIndex` als dos repositoris, amb una IT sobre un Mongo amb control d'accés i un usuari `readWrite`; `deploy/test_e11_t09.py` a la feina `tests` de la CI.

**On mirar**: `identity/persistence/ImpersonationGrantRepository.java`, `platform/persistence/SecurityEventRepository.java`, `deploy/mongo-health.js`, `.github/workflows/ci.yml:36-37`.

## INC-83 · Menors de les verificacions del 21-10, segona tanda (api i web)

**Gravetat**: baixa. Cap bloqueja el llançament.

**Origen**: verificacions d'E11-T15, E11-T13, E11-T16, E10-W02 i E11-W04 (revisors propis del núvol + revisions del kit, 21-10; E155–E157).

**Api**: (1) `ActivityTransactions.transientConflict` manté la forma «112 + TransientTransactionError» mentre `BookingTransactions` ja delega a `TransactionRetries.conflict` (E11-T16 pas 4); alinear-lo. (2) `CensusErasure.erased()` llegeix els `Member` sencers dels esborrats del club a cada escombrada mensual; `CensusRepository.matching(Criteria, fields…)` en té prou amb vuit camps. (3) Dues instàncies arrencant alhora podrien xocar al drop + create de l'índex TTL (`IndexNotFound` 27 a la segona); teòric amb una sola `core`. (4) `GET /members/{id}/signup` proposa `priceId` = el primer bessó quan el pla en té dos (només ho veu l'admin; D2 refusa amb `409 PRICE_OVERLAP`).

**Web**: (5) els tipus generats no porten el contracte d'E11-T15 (`PUT /members/{id}/next-invoice-date`, `period`, `submissionDue`, `NO_NEXT_INVOICE_DATE`, `COLLECTION_DATE_PASSED`) → E11-W07. (6) D2 `planOptions` llista els dos preus d'un pla bessó i deixa enviar el segon; el core respon 409 des d'E11-T13 r2 i D2 n'ha de dir el pla (E11-W06 ho fa a D6; E11-W08 a D2). (7) El `/branding` de producte i el tema del club plantilla (`#2563EB`, system-ui, 8 px) no coincideixen amb el preset «AgilityHub» del web (`#1d1d1f`, Montserrat): fins a E11-T19 la consola arrenca blava i un club creat «amb el tema AgilityHub» surt fosc.

**On mirar**: `clubs/activities/application/ActivityTransactions.java`, `platform/application/privacy/CensusErasure.java`, `identity/persistence/ImpersonationGrantRepository.java`, `clubs/signup/application/SignupService.java` (`proposals`); web `packages/api-client/src/generated/schema.d.ts`, `apps/clubs-admin/src/dashboard/SignupReviewPage.tsx`, `packages/ui/src/tokens.css`.
