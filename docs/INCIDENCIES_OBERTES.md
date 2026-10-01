# Incidències obertes — registre de defectes

**v2.8 · 01-10-2026** (v2.7 01-10 · v2.6 01-10 · v2.5 01-10 · v2.4 01-10 · v2.3 30-09 · v2.2 30-09 · v2.1 30-09 · v2.0 30-09 · v1.9 30-09 · v1.8 28-09 · v1.7 28-09 · v1.6 27-09 · v1.5 27-09 · v1.4 26-09 · v1.3 26-09 · v1.2 24-09 · v1.1 10-09 · v1.0 09-09)

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
| INC-07 | 24-09 | api · web (auth) | El `refresh_token` respon `400` de manera intermitent a l'e2e contra el core real | **Alta** (provisional) | **mitigada 01-10 (E7-W06)**: la caiguda de T-04-34 venia d'una lectura de `/branding` encallada en arrencar després d'una càrrega completa, no de la renovació; l'arrencada ja no s'hi encalla si hi ha memòria cau. Queda obert quin salt la retenia (E7-W07). INC-36 continua a E11-T02 |
| INC-08 | 24-09 | api · contracte | Les respostes serialitzen `null` en camps opcionals que l'OpenAPI no declara `nullable` | Mitjana | oberta — el front ho tolera a D11 (E3-W03) i a D2 (E3-W04) |
| INC-09 | 24-09 | api · contracte | `RING_HAS_BOOKINGS.details.bookings[]` té dues formes segons la ruta | Baixa | oberta — el front mostra només `memberName` + `dogName` (E4-W02) |
| INC-10 | 24-09 | api · RGPD | Les altes rebutjades no tenen retenció: S14 R-14-16 (b) no està implementada | Mitjana | oberta — la purga és d'E11 (retenció i supressió); trobada a la revisió de la porta E3 |
| INC-11 | 26-09 | web | Diferències cosmètiques de les pantalles d'E3 respecte dels mockups (re-execució de la porta, E3-W09) | Baixa | oberta — passada de polit abans del llançament |
| INC-12 | 26-09 | api · proves | Detalls de la revisió d'E5-T22: còpies d'ítems de llista fetes a mà, l'ítem de `/platform/audit-entries` | Baixa | oberta — passada de correccions |
| INC-13 | 26-09 | api · definició de club | Revisió d'E5-T23: les instruccions d'`MANUAL` en blanc passen la validació, la regla R-17-05 només es comprova quan la definició les declara, noms i abast de dos tests | Baixa | oberta — passada de correccions |
| INC-14 | 26-09 | api · fitxers | Revisió d'E5-T24: la ruta signada es reconeix pel camí cru, P9 no neteja els fitxers `DOG_DOCUMENT` orfes de l'ADMIN, el test de T-05-07 no la cobreix sencera | Baixa | oberta — passada de correccions |
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
| INC-31 | 26-09 | api (privacitat) | Dues formes d'IBAN: en clar (entrat per l'api) i xifrat (migrat); cap tasca d'E8 no ho sabia | Mitjana | oberta — E8-T01 (`BankAccountVault`, E43) + E11-T02 (xifrar els IBAN en clar abans del llançament, A37 ✓ Jordi 27-09) |
| INC-32 | 26-09 | api (alta) | Rebutjar una alta amb un checkout obert no tanca la sessió del proveïdor; un pagament tardà es perd | Mitjana | **resolta 30-09 (E5-T28 ✅, pas 4)**; els camins d'error nous del checkout, E5-T30 |
| INC-33 | 26-09 | api (alta) | D1 i el llistat d'abonats jutgen una readmissió pendent per la fitxa de baixa, no per la sol·licitud (E38) | Baixa | **resolta 30-09 (E5-T28 ✅, pas 7)** |
| INC-34 | 26-09 | api (planificació) | Es pot crear una classe `DRAFT` solta mentre es valida la setmana; `409` no declarats a les rutes de validació i de classe | Baixa | **resolta 30-09 (E5-T28 ✅, pas 5)** |
| INC-35 | 26-09 | api (cens) | El `PATCH` d'un gos actiu desa el xip sense normalitzar | Baixa | **resolta 30-09 (E5-T28 ✅, pas 6)** |
| INC-36 | 26-09 | api (autenticació) | Causa d'INC-07: reutilitzar un refresh token ja rotat revoca el fill viu, sense cap gràcia, i dues renovacions concurrents competeixen a `Account.sessionSequence` | **Alta** (= INC-07) | oberta — E11-T02 (`auth.refreshReuseGraceSeconds` = 30) |
| INC-37 | 26-09 | api (identitat, menors) | Menors d'identitat: esdeveniments, auditoria, temps de resposta, importació de Learn, rotació de claus | Baixa | oberta — E11-T02 |
| INC-38 | 26-09 | api (cens i catàlegs, menors) | Menors de cens i catàlegs: emmascarament de les exportacions, exportació asíncrona amb filtre numèric, auditoria sense `entityLabel`, … | Baixa | oberta — E11-T02 (la regla del grup familiar, a E8-T02) |
| INC-39 | 26-09 | api (alta, menors) | Menors d'alta: rutes de D10 no congelades durant una readmissió, codi mort, … | Baixa | oberta — E11-T02 (part a E8-T04) |
| INC-40 | 26-09 | api (planificació, reserves i processos, menors) | Menors: text del risc, finestra de bloqueig no alineada, `instructorIds` buit, nivell inactiu, cursa de l'slug, … | Baixa | oberta — E11-T02 · E11-T03 |
| INC-41 | 26-09 | web (fonaments i pantalles, menors) | Menors del web: `apps/id` en fallar la renovació, logout amb bearer refusat, pàgina en blanc si `/branding` falla, esquemes de `pending.json` ja publicats, tema `auto`, … | Baixa (la llista de traspàs, un botó mort i el router: Mitjana) | oberta — E4-W16 pas 12 · E8-W03 · E11-W02 (el router, A38 ✓ Jordi 27-09) |
| INC-42 | 26-09 | api · migració i reserves | Revisió d'E5-T25: la migració desa un gos sense sexe quan Playoff en porta un de desconegut (S03 l'exigeix); detalls del contracte de reserves | **Mitjana** (el sexe; abans de migrar) · baixa (la resta) | oberta — passada de correccions, abans de cap migració real |
| INC-43 | 27-09 | api · fitxers | Revisió d'E5-T26: l'IT de les descàrregues locals no descarrega cap `DOG_PHOTO` ni `ACTIVITY_IMAGE` i els seus camins de reserva no tenen test; les descàrregues locals no admeten `Range` | Baixa | oberta — passada de correccions |
| INC-44 | 27-09 | web · api (entrenaments) | Preguntes d'E5-W02: sense graella a 24 i D12 per a una pista sense entrenament lliure o amb `FREE_TRAINING` desactivat; la cel·la «classe» mostra l'hora de la fila, no la de la classe | Baixa | oberta — passada de correccions |
| INC-45 | 28-09 | api (seguretat, contracte) | Pregunta d'E7-T01: cap test comprova, per a totes les operacions, que la seguretat publicada a l'OpenAPI és la que s'aplica (avui ho fa cada IT de contracte per a les seves rutes) | Baixa | oberta — E11-T03 (pas 1) |
| INC-46 | 28-09 | api (processos) | Nits de la ronda 2 d'E6-T04: el recompte de [Simula] de P3 i la seva traça sense límit, una escombrada en bloc sense ús ni guarda de mòdul, i la lectura de P8 a cada minut | Baixa | oberta — passada de correccions |
| INC-47 | 28-09 | api (transaccions, comú) | Una ruta amb clau que no és a la llista de rutes amb transacció pròpia d'`IdempotencyFilter` s'executa dins la transacció del filtre, i un conflicte d'escriptura de Mongo hi acaba en 500 (revisions d'E6-T03, rondes 3 i 4) | Mitjana | oberta — passada de correccions (E11-T02); les rutes d'E6-T03, **fetes a la seva ronda 5 (30-09, E6-T03 ✅)** |
| INC-48 | 30-09 | api (seguiment) | Nits de la ronda 5 d'E6-T03: l'estat que es desa per a la resposta repetida d'una clau és una còpia escrita a mà del `@ResponseStatus` de cada ruta, i un Javadoc mal tallat | Baixa | oberta — passada de correccions |
| INC-49 | 30-09 | api (consola, idempotència) | `IdempotencyFilter` pren el club del `clubId` del JWT i respon `NO_MEMBERSHIP` sense: una ruta de consola amb clau (`POST /platform/clubs/{clubId}/jobs/{name}/trigger`) cridada amb un token de plataforma queda refusada abans del handler (nota 2 d'E5-T29, llegida al codi) | Mitjana | oberta — per a E10 (S17, consola) |
| INC-50 | 30-09 | api (processos, proves) | Nits de la ronda 2 d'E5-T29: un reintent d'un llançament manual pot deixar l'execució sense `JOB_TRIGGERED` si la primera escriptura de l'auditoria va fallar; la vida de 24 h de la clau és definida dues vegades; l'etiqueta T-09-30 dels tests de cerca no té cap asserció de tenant | Baixa | oberta — passada de correccions |
| INC-51 | 01-10 | api (seguiment, contracte) | Menors de la revisió d'E6-T06 (la cerca de D14 sense projecció i amb llistes `$in` sense límit, les proves d'aïllament de tenant de la cerca i dels recomptes, `FOLLOWUP.searchable`, l'etiqueta d'un abonat esborrat als valors del filtre, els scripts de l'evidència) i la pregunta 1 d'E6-W05 (`POST /tasks` pot respondre `409 INVALID_STATE` sense declarar-lo) | Baixa | oberta — E11-T02 |
| INC-52 | 01-10 | api (missatgeria, menors) | Menors de la revisió d'E7-T05: `claimAccepted` amb dos predicats sobre el mateix array, l'acceptació que no es torna a marcar mentre l'assentament falla més de 2 minuts, l'evidència del pas 4, la fila 11 de la taula sense prova, el rebot d'una adreça compartida, i detalls | Baixa | oberta — E11-T02 |
| INC-53 | 01-10 | api (contracte i seed, petits) | Preguntes d'api d'E6-W04, E7-W05 i E5-W05: `TaskItem.doneBy`, `UploadUrl.uploadUrl` relatiu, els comptadors de simulació de P8, el comptador d'adjunts de `FollowupItem`, un límit superior per a `read-all`, les etiquetes amb espai final de `/bookings/filter-values`, `isoWeekStart` a l'ítem de P1, el «Berta» del seed, la resta de codis d'S10 que han passat a 422, els camps obligatoris de `Member` que l'esborrament anul·la, i les claus d'idempotència de `POST /jobs/{name}/trigger` i de la llista d'espera | Baixa | oberta — E11-T02 (el punt 10, E11-T01) |

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

**Origen**: les preguntes d'E6-W04 (Q1–Q3), d'E7-W05 (Q3) i de la ronda 2 d'E5-W05 (R2-Q1, R2-Q2); verificació de l'organitzador de l'1-10 (decisió E82). El punt 10, de la suposició A8 d'E7-W06 (decisió E85) i de la pregunta Q3 d'E7-W07. Els punts 11 i 12, de les preguntes Q1 i Q2 d'E7-W07 (decisió E86).

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

**On mirar**: els informes d'E6-W04 i d'E7-W05 i la ronda 2 d'E5-W05 (web).

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
