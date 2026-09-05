# S19 — Integració amb AgilityHub Learn (R2: SSO, selector de productes, contingut per nivell, challenges)

**Etapa:** R2 (després del go-live del Cànic); a R1 només el que s'indica com a «preparat a R1» · **Mòduls:** `LEARN_LINK` (R1), `COURSES` (challenges) · **Pantalles:** entrada «Aprèn amb AgilityHub» a l'app (R1, sense mockup), selector de productes a `apps/id` i a les barres de Clubs/Learn (R2), bloc «Contingut recomanat» a 13 (R2), pantalla de challenge (R2) · **Model:** PLATAFORMA §1 (ACCOUNT, MEMBERSHIP), §3 (`Level.agilityhubLevel`), §5 (CHALLENGE/ATTEMPT) · ADR-010, ADR-013 · VISIO §3/§5/§6 · **Estat:** esborrany (03-09-2026) — **contracte a validar quan s'obri R2; requereix revisar el codi de Learn (`AH_LearnPlatform`)** · **Versió:** 0.2 (05-09: revisió del codi de Learn, §14)

## 1. Propòsit i abast

Defineix la **cola** entre Clubs i Learn: (1) **identitat compartida** (R1: mateixes credencials via importació + adaptador de login, S01; R2: **SSO real** per redirecció OIDC amb sessió a `id.*` i «Continua amb AgilityHub» a Learn), (2) **selector de productes** «estil Google» a totes les apps, (3) **contingut de Learn recomanat pel nivell del gos** dins Clubs (`Level.agilityhubLevel` → rutes d'aprenentatge), (4) **challenges**: Learn publica un recorregut + criteris; el club el munta (S16); l'alumne registra l'intent des de Clubs o Learn; el resultat torna a Learn. Tot per **API del core** (font de veritat de recorreguts i challenges, ADR-013) i **cap dada del club** surt cap a Learn llevat del que l'alumne comparteix explícitament.

| Fora d'abast | On viu |
|---|---|
| Mecanismes d'identitat, importació de comptes, adaptador de login (fase 1) | S01 |
| Model i API de recorreguts, rings, muntatge | S16 |
| Definició pedagògica dels challenges (què es demana, com es valida) | Learn (producte) — aquí el contracte tècnic |
| Facturació creuada (descomptes club ↔ Learn), model comercial | R4 |

## 2. Pantalles i rutes

| Pantalla | App | Ruta | Rol | Comportament | Release |
|---|---|---|---|---|---|
| «Aprèn amb AgilityHub» | clubs | entrada al menú de 12 (i 6a icona? no: es manté «Info») | MEMBER | Enllaç a `https://learn.agilitydoghub.com/?utm_source=clubs&club={slug}` amb `login_hint={email}`; si el mòdul `LEARN_LINK` és actiu. Text del club editable (`learn.linkText`, proposta). | **R1** |
| Selector de productes | id · clubs · clubs-admin · Learn | icona de graella a la barra | compte | `GET /me/products` → `[{key: LEARN|CLUBS|CLUBS_ADMIN, url, label, enabled}]` segons membresies i productes actius del compte; obrir un producte = navegar amb la sessió d'`id.*` (OIDC) → cap login. | R2 |
| «Continua amb AgilityHub» | Learn (Vue) | botó al login de Learn | ANON | Flux authorization code + PKCE contra `id.*`; Learn crea/actualitza el seu usuari local per `agilityhub_account_id` (`sub`) i fa `Auth::login`. El login clàssic de Learn es manté un temps (fase 1) i després es retira. | R2 |
| Contingut recomanat (13) | clubs | bloc a la fitxa del gos | MEMBER | «Per al nivell de la Duna (D → Intermedi): 3 lliçons recomanades» → `GET /learn/recommendations?agilityhubLevel=…` (proxy del core cap a l'API de Learn, cache 1 h) amb títol, miniatura i enllaç profund a Learn (SSO). Si el nivell del club no té `agilityhubLevel` → bloc ocult. | R2 |
| Challenge | clubs · Learn | `/challenges/:id` | MEMBER | Fitxa: recorregut (visor 2D, S16), criteris (`rules`), vigència, «Muntat a la pista {ring}» si el club té un `RingSetup` actiu del `courseId`; [REGISTRA UN INTENT] → temps, faltes, vídeo (pujada S3 o enllaç) → `POST /challenges/{id}/attempts`; historial d'intents propis. Learn mostra el mateix per API. | R2 |
| Consola: challenges | clubs-admin (consola) | `/consola/challenges` | AGILITYHUB_ADMIN | CRUD de `Challenge` sobre recorreguts `PUBLIC` d'AgilityHub (S16 R-16-14), estat `DRAFT/PUBLISHED/CLOSED`. | R2 |

## 3. Entitats i camps

- `Account.externalIds.learnUserId` (S01) · `Membership` (S01) · `Level.agilityhubLevel` (S05).
- **`Challenge`** (global): `courseId` (PUBLIC), `title/description: LocalizedText`, `agilityhubLevel`, `discipline`, `rules {maxFaults, timeLimitS?, mustBeClean, videoRequired, attemptsPerDog}`, `validFrom/validTo`, `status`, `createdByAccountId`.
- **`ChallengeAttempt`** (`clubId`): `challengeId`, `memberId`, `dogId`, `accountId`, `ringSetupId?` (si es corre en un muntatge registrat), `timeMs`, `faults`, `videoUrl?`, `notes`, `submittedAt`, `status` (`SUBMITTED · VALIDATED · REJECTED`), `validatedByAccountId?`, `score?` (calculat per `rules`).
- **`LearnRecommendation`** (no persistit; resposta de Learn): `{lessonId, title, thumbnailUrl, url, level, durationMin}`.
- Learn (Laravel) guanya `users.agilityhub_account_id` (S01) i, a R2, una taula `challenge_results` **només com a cache** del core (o consulta directa per API — a decidir amb el codi de Learn).

## 4. Regles de negoci

| Regla | Enunciat | Release |
|---|---|---|
| **R-19-01 Un compte, cap duplicat** | L'email és la clau a tots dos productes (S01 R-01-01). Un usuari de Learn que s'apunta a un club reutilitza el compte (S04 R-04-22); un abonat de club que entra a Learn per «Continua amb AgilityHub» obté un usuari local de Learn creat al vol amb `sub`. Mai dues contrasenyes: Learn no guarda hash nou després de la fase 2 (`password = null`). | R1/R2 |
| **R-19-02 SSO** | Sessió al domini `id.*` (cookie httpOnly) creada en qualsevol login OIDC; Clubs passa del token endpoint directe (R1) a `authorize` + PKCE amb `prompt=none` per a login silenciós (R2, S01 R-01-11); logout global via `/connect/logout` amb `post_logout_redirect_uri` de cada producte (front-channel). L'app AR igual (R3). | R2 |
| **R-19-03 Selector de productes** | `GET /me/products` calcula: `LEARN` (sempre, URL de Learn), `CLUBS` per cada membresia `ACTIVE` (URL del host principal del club, `app: clubs`), `CLUBS_ADMIN` per cada membresia amb ADMIN/INSTRUCTOR, `CONSOLE` per `AGILITYHUB_ADMIN`. Multi-club: una entrada per club (nom + logo). | R2 |
| **R-19-04 Dades que viatgen** | Cap a Learn només: `sub`, `email`, `name`, `locale`, `memberships[{clubId, clubName, roles}]` (scope `memberships`, consentit al primer login OIDC amb pantalla de consentiment de `apps/id`), i `agilityhubLevel` dels gossos si l'usuari activa «Comparteix el nivell dels meus gossos amb Learn» (preferència al compte, `Account.sharing.dogLevels`, per defecte **off**). Cap dada del cens (DNI, IBAN, assistència) surt mai. Learn cap al core: recomanacions i challenges (públics). | R2 |
| **R-19-05 Mapatge de nivells** | `Level.agilityhubLevel` (S05) és opcional; Clubs demana recomanacions amb el nivell del gos només si hi ha mapatge; l'escala AgilityHub es fixa amb Learn abans de R2 (proposta: `FOUNDATIONS · BEGINNER · INTERMEDIATE · ADVANCED · COMPETITION`; si Learn usa una altra, es canvia l'enum amb migració — cap literal al codi). | R2 |
| **R-19-06 Challenges** | Publicació: `AGILITYHUB_ADMIN` crea el `Challenge` sobre un `Course PUBLIC`; els clubs el veuen a la biblioteca (xip «Challenge») i poden muntar-lo (S16); l'alumne registra un intent **des del club** (Clubs) o **des de casa** (Learn, `ringSetupId = null`); `rules.attemptsPerDog` per challenge; `score` calculat al core; validació (si `videoRequired`) per l'equip AgilityHub o automàtica (`mustBeClean` i `timeLimit`); l'intent és de la parella (memberId + dogId) i del compte; visibilitat: l'alumne veu els seus; el club veu els dels seus abonats fets al club (`ringSetupId` seu); Learn mostra classificacions públiques **només amb nom de gos + inicials** llevat que l'usuari activi `Account.sharing.leaderboardName`. | R2 |
| **R-19-07 Contracte d'API Learn → core** | Learn és un client OIDC confidencial amb scopes `challenges:read`, `attempts:write`, `recommendations:serve`; el core exposa `GET /challenges`, `GET /challenges/{id}`, `GET /challenges/{id}/leaderboard`, `POST /challenges/{id}/attempts` (amb `sub` de l'usuari via token d'usuari, no de servei), i consumeix `GET {LEARN_API}/recommendations?level=&locale=` (servei a servei amb client credentials) — tot versionat a `/api/v1`. | R2 |
| **R-19-08 Preparat a R1** | `LEARN_LINK` (enllaç), `Level.agilityhubLevel`, `Account.externalIds.learnUserId`, importació + adaptador de login (S01), `Course.visibility PUBLIC` + `/platform/courses` (S16), reserves d'API `/challenges*` (`501`), client OIDC `learn` al seed. Cap altra feina a R1. | R1 |
| **R-19-09 Mòduls** | `LEARN_LINK` off → cap entrada ni bloc de recomanacions; `COURSES` off → cap challenge visible al club (els alumnes els poden fer igualment des de Learn). | — |

## 5. Estats i transicions

`Challenge`: `DRAFT → PUBLISHED → CLOSED` (per `validTo` o manual). `ChallengeAttempt`: `SUBMITTED → VALIDATED | REJECTED` (automàtic si no cal vídeo). Federació de Learn: `FASE_1 (login invisible) → FASE_2 (OIDC) → FASE_3 (login clàssic retirat)`.

## 6. API

| Mètode | Ruta | Rol | Descripció | Release |
|---|---|---|---|---|
| GET | `/me/products` | compte | R-19-03 | R2 |
| GET | `/learn/recommendations?dogId=` | MEMBER | R-19-05 (proxy amb cache; `LEARN_LINK`) | R2 |
| GET | `/challenges` · `/challenges/{id}` · `/challenges/{id}/leaderboard` | compte | R-19-06 (`501` a R1) | R2 |
| POST | `/challenges/{id}/attempts` · GET `/me/challenge-attempts` | MEMBER (token d'usuari des de Clubs o Learn) | R-19-06; `Idempotency-Key` | R2 |
| GET · POST · PATCH | `/platform/challenges` | AGILITYHUB_ADMIN | consola | R2 |
| PATCH | `/me/sharing` | compte | `{dogLevels, leaderboardName}` | R2 |
| Learn | `GET /api/v1/recommendations?level=&locale=` (a Learn) | client credentials del core | R-19-07 | R2 |

## 7. Esdeveniments

R2: `ChallengePublished`, `ChallengeClosed`, `ChallengeAttemptSubmitted{challengeId, clubId?, dogId}`, `ChallengeAttemptValidated`, `AccountSharingChanged`. R1: cap.

## 8. Notificacions

R2 (propostes): N-44 «Nou challenge disponible» (CLUB_NEWS opcional per a alumnes amb nivell mapejat), N-45 «Intent validat» (PERSONAL → APP+EMAIL). R1: cap.

## 9. Paràmetres i mòduls

Propostes: `learn.linkText` (localizedText, «Aprèn amb AgilityHub»), `learn.recommendationsTtlMinutes` (60), `learn.baseUrl` (plataforma). Mòduls: `LEARN_LINK`, `COURSES`.

## 10. i18n i localització

`Account.locale` compartit: canviar-lo a Clubs canvia Learn (Learn llegeix `locale` del `id_token`/userinfo); challenges amb `LocalizedText` en ca/es/en; Learn manté els seus idiomes.

## 11. Criteris d'acceptació i tests (R2; a R1 només T-19-01)

- T-19-01 (R-19-08) R1: `LEARN_LINK` on → entrada amb `login_hint`; off → absent; `/challenges` → `501`; client `learn` al seed amb `password` grant.
- T-19-02 (R-19-02) login a Learn per OIDC → sessió `id.*` → obrir Clubs amb `prompt=none` → entrat sense pantalla; logout global tanca els dos.
- T-19-03 (R-19-03) compte amb 2 clubs + admin → 4 entrades al selector.
- T-19-04 (R-19-04) `userinfo` sense `memberships` si no s'ha consentit; `dogLevels` off → recomanacions sense nivell (genèriques).
- T-19-05 (R-19-06) intent des del club amb `ringSetupId` del muntatge actiu; `attemptsPerDog` superat → `409`; `score` calculat; leaderboard amb inicials.
- T-19-06 (R-19-07) Learn amb scopes insuficients → `403`; `recommendations` amb el doble de Learn i cache.

**Cobertura addicional (traçabilitat regla → test)**
- T-19-07 (R-19-01) usuari de Learn (`learnUserId`) que s'apunta al Cànic → cap compte nou (S04 T-04-17); abonat del club que entra a Learn per OIDC → usuari local creat amb `agilityhub_account_id = sub`; després de la fase 2, `users.password` de Learn és `null`.
- T-19-08 (R-19-05, R-19-09) gos amb nivell sense `agilityhubLevel` → bloc de recomanacions absent; amb mapatge → petició a Learn amb `level=INTERMEDIATE`; `LEARN_LINK` off → cap entrada ni bloc; `COURSES` off → challenges absents del club però visibles a Learn.

## 12. Paquets de feina

| Paquet | Repo | Depèn de | Lliurable |
|---|---|---|---|
| WP-19-A (R1) | core + clubs | S01, S05, S16 | R-19-08 complet; T-19-01 |
| WP-19-B Revisió de Learn | `AH_LearnPlatform` | accés al codi | inventari d'auth (social? sense contrasenya?), API existent, model d'usuari, idiomes; ajust d'S01 R-01-12 i d'aquesta spec (v0.2) |
| WP-19-C SSO + selector (R2) | core `identity` + `apps/id` + Learn (Vue/Laravel) | WP-19-B | R-19-02/03; T-19-02/03 |
| WP-19-D Recomanacions (R2) | core + Learn API | WP-19-C | R-19-04/05; T-19-04/06 |
| WP-19-E Challenges v1 (R2) | core `courses` + clubs + Learn | S16, WP-19-C | R-19-06/07; T-19-05 |

## 13. Dubtes oberts

| # | Dubte | Qui | Assumpció |
|---|---|---|---|
| 1 | Escala AgilityHub definitiva (noms, nombre de nivells) | Jordi (Learn) | 5 nivells de R-19-05 |
| 2 | Learn té API pública pròpia o cal crear `recommendations`? | revisió de Learn | cal crear-la a Laravel |
| 3 | Validació de vídeos dels challenges: manual (equip) o automàtica | Jordi | manual si `videoRequired` |
| 4 | Classificacions públiques i RGPD (inicials per defecte) | Jordi (legal) | inicials + nom del gos |
| 5 | Retirar el login clàssic de Learn (fase 3): quan | Jordi | 3 mesos després de la fase 2 |

**Propostes**: paràmetres `learn.*`; mòdul cap de nou; esdeveniments i notificacions de §7/§8; `Account.sharing {dogLevels, leaderboardName}`; scopes OIDC `challenges:read`, `attempts:write`, `recommendations:serve`, `memberships`.

## 14. Revisió del codi de Learn (WP-19-B, 05-09-2026) — el domini real de Learn i els ajustos (v0.2)

| Fet verificat a `agilityhub-api` | Conseqüència per a la integració |
|---|---|
| **Challenges** (`cha_challenges`): unitat d'aprenentatge amb vídeo (`video_file`, introducció), `category Grand · Garden`, dificultat `EASY · MEDIUM · HARD` (afegit 06-2026), `trainer_id`, `designer_id`, seqüències (`cha_challenge_sequences` = vídeos/passos), skills, tips, **obstacles amb quantitats** (`cha_challenge_obstacles` → `sys_obstacles {name, icon}`), tags, `code_membresis` (visibilitat per pla); progrés per usuari a `use_users_challengers` | El **challenge pedagògic viu a Learn**. El core hi afegeix la part de camp: `Challenge {learnChallengeId, courseIds[], rules, validFrom/To}` + `ChallengeAttempt` (§6). Cap duplicació de vídeos ni seqüències |
| **Course maps** (`cou_course_maps`): `title, description, picture, link (fitxer del plànol), size GRAND · GARDEN, measures (mides, text), level_difficulty EASY · MEDIUM · HARD, designer_id, code_membresis`; relació N—M amb challenges (`cha_challenge_course_map`); tracking `use_users_course_maps` | Un course map de Learn = un **`Course` del core** amb `ownerType AGILITYHUB`, `source IMAGE` (o `SMARTER` si el fitxer és un `.txt` de Smarter), `sizeCategory GRAND · GARDEN`, `agilityhubLevel` = `level_difficulty`, `externalIds.learnCourseMapId`. Importació per job (R2) o manualment des de la consola (S16 `/platform/courses`) |
| Escala de dificultat real: **`EASY · MEDIUM · HARD`** (challenges i course maps) + categoria **`Grand · Garden`** (mida de pista) | **`AgilityHubLevel` = `EASY · MEDIUM · HARD`** (S05, PLATAFORMA §3) — substitueix la proposta `FOUNDATIONS…COMPETITION`; `Course.sizeCategory` nou (S16) |
| **Training paths** (`training_paths`, recursius) agrupen challenges/skills/tips; **plans** (`plans`, `user_plan_licenses`, Stripe subscriptions) amb `code_membresis` a cada contingut | Recomanacions (R-19-05): Learn exposa `GET /api/v1/recommendations?level=&locale=` → llista de challenges/course maps del nivell **respectant `code_membresis`** de l'usuari (cal implementar-ho a Laravel: no existeix). Clubs no coneix plans de Learn |
| API a `app.agilitydoghub.com/api/v1/*` (prefixos a `RouteServiceProvider`), JWT propi, `role:` middleware; cap endpoint públic de recomanacions ni de challenges per a tercers | R-19-07: client credentials del core cap a Learn = nou middleware a Laravel (`X-Core-Token` o JWT de servei) — 1 dia |
| Front en **6 idiomes** (`de en es fr no pt`, fallback `es`), sense català | `Account.locale` pot ser qualsevol dels 7 de producte; Learn ignora `ca` (cau a `es`); Clubs ignora `fr/de/no/pt` (cau a `club.defaultLocale`) |
| `users.role` (`user·admin·anonymous·coach·designer`), mode convidat local, `stripe_customer_id`, `membresis` | `userinfo` no exposa res de Learn; els rols `coach/designer` poden mapejar-se a R2 a `Course.ownerType ACCOUNT` (dissenys de jutges/dissenyadors) |
| Deploy manual SSH, front compilat dins l'API (`front/`), dos camins de dades al front (Pinia + vue-query) | El botó «Continua amb AgilityHub» (R2) toca `features/auth/LoginForm.vue` + `stores/auth/auth.js` (token a `localStorage.auth`) |

**Ajustos**: R-19-05 → escala `EASY · MEDIUM · HARD`; R-19-06 → `Challenge.learnChallengeId` obligatori (el core no crea challenges «propis» a R2; ho fa Learn i el core hi penja recorreguts i intents); R-19-07 → afegir a Learn `GET /api/v1/recommendations` i `GET /api/v1/challenges/{id}/course-maps` per a la importació; nou WP-19-F «Importació de course maps → `Course`» (R2). **Dubtes tancats**: §13-2 (cal crear l'API a Learn: sí); §13-1 (escala: la de Learn).

## Canvis

- 03-09-2026 · v0.1 · esborrany inicial (contracte R2) a partir de VISIO, ADR-010/013 i S01/S05/S16.
- 05-09-2026 · v0.2 · §14: domini real de Learn (challenges de vídeo, course maps com a fitxers, dificultat EASY/MEDIUM/HARD, Grand/Garden, training paths, plans), escala AgilityHub = la de Learn, `Challenge.learnChallengeId`, API de recomanacions a crear a Laravel.
