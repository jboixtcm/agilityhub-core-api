# Registre de decisions pendents — per a Jordi (i preguntes per al Josep)

**v1.5 · 09-09-2026** (v1.4 + **exports reals de Playoff**: B7 i B28 tancades, mapatge de camps complet a `MAPATGE_CAMPS_PLAYOFF.md`, camp nou `Dog.handlerName`, i **5 preguntes noves per al Josep, B29–B33**) · v1.4 · 08-09-2026 (v1.3 + **respostes del Josep a les 21 preguntes del correu** → part B tancada; 11 respostes canvien l'assumpció i **2 superen decisions teves del 05-09**: cobrament el **dia 1 del mes facturat** i **efectiu només per semestres** → decideix a **A28** i **A29**) · v1.3 · 06-09-2026 (v1.2 + **respostes de Jordi a tota la part A i a la part E** — criteri declarat: *qualitat per davant del temps de desenvolupament*; canvis destacats: A1 proxy same-site, A3 admin `jboix@agilitydoghub.com` + gestió via API, A5 nivells 100 % locals sense escala de plataforma, A9 AR condicionada a validació, A13 backup diari, A16a 6 anys; Part E confirmada sencera) · v1.2 · 06-09-2026 (v1.1 + Part E: decisions de contracte preses per l'organitzador durant el primer dia de desenvolupament, per revisar) · v1.1 · 05-09-2026 (v1.0 + respostes inline de Jordi a `PENDENTS_DESENVOLUPAMENT.md` §3–4: 4 h, efectiu mensual, sense IVA, cobrament l'últim dia del mes, gamificació R2 OK, DNS al deploy) · Consolida els §13 de les 20 specs, `PENDENTS_DESENVOLUPAMENT.md` i les revisions de codi (Learn, web-planner). Cada punt diu **on hi ha el conflicte o el dubte**, **què recomano** (i per què) i **què canvia si tries una altra cosa**. El desenvolupament **ja pot avançar** amb la recomanació aplicada: totes les specs la tenen incorporada com a assumpció.

**Com respondre**: edita aquest fitxer i escriu a la línia `Decisió Jordi:` un `OK` o el canvi que vulguis (una frase). Els punts de la part B són per al Josep: si ja saps la resposta, escriu-la; si no, deixa `→ Josep` i els agruparé en un correu. En la propera sessió llegeixo aquest fitxer i actualitzo specs i catàlegs.

---

## Part A — Decisions de producte i de plataforma (Jordi)

### A1 · Sessió a la PWA: on guardem el refresh token
- **Conflicte**: `clubs.*`/`app.agilitycanic.cat` i `core.*` són dominis diferents → una cookie httpOnly no pot viatjar sense `SameSite=None` i tercers. Alternativa: proxy `same-site` (`app.agilitycanic.cat/api` → core) a cada àlies de club.
- **Recomanació**: refresh token **xifrat amb WebCrypto** al storage del navegador (clau no exportable), access token només en memòria; el proxy same-site queda com a millora d'E11 si es vol treure el token del storage. Cost mínim, funciona a iOS PWA.
- **Si canvies**: proxy per host a Caddy per a cada club (configuració per domini) i cookies; +2 dies a E1 i a E10.
- **Decisió Jordi:** (06-09) **Proxy same-site + cookies httpOnly** — criteri qualitat: el refresh token no ha de ser accessible des de JS (amb WebCrypto un XSS encara pot *usar* la clau). Com que A11 posa Caddy davant de cada host de club, la ruta `/api/*` → core és configuració genèrica, no per club. Es retira l'emmagatzematge WebCrypto amb una tasca de correcció; E18 (pont OIDC) es manté.

### A2 · Comprovar contrasenyes compromeses (HIBP k-anonymity) a R1
- **Recomanació**: **sí** (`auth.checkCompromisedPasswords = true`): és una crida externa barata i evita contrasenyes trencades; només s'aplica a contrasenyes noves (les de Learn no es toquen).
- **Decisió Jordi:** OK (06-09)

### A3 · Comptes importats de Learn: rols i idioma inicials
- **Fet verificat**: Learn té `users.role` (`user·admin·anonymous·coach·designer`), cap columna d'idioma, 6 idiomes al front (`de en es fr no pt`, fallback `es`), ~1.000 usuaris.
- **Recomanació**: importar tots els comptes amb `locale = es` (canviable), `externalIds.learnRole` informatiu, **cap `AGILITYHUB_ADMIN` automàtic** — només els comptes que tu indiquis (el teu i el de qui gestioni la consola); el compte convidat (`anonymous@…`) **no** s'importa. Els `coach/designer` quedaran per a R2 (dissenys de recorreguts personals).
- **Decisió Jordi:** (06-09) Admin inicial: **`jboix@agilitydoghub.com`** (només aquest). Requisit afegit: **s'ha de poder afegir/treure administradors de plataforma fàcilment via API** (`POST /platform/accounts` + gestió del rol `AGILITYHUB_ADMIN`). La resta de la recomanació, OK (cap admin automàtic dels rols de Learn, `anonymous@…` no s'importa, `locale = es`).

### A4 · Onboarding dels comptes importats i migrats
- **Ja decidit per tu (05-09)**: pantalla «Completa el teu perfil» al primer accés, amb casella obligatòria de la política de privacitat i dades bàsiques opcionals (S01 §14, S18). Ho confirmo aquí: **OK per defecte**.
- **Decisió Jordi:** OK (06-09)

### A5 · Escala AgilityHub de nivells = la dificultat real de Learn
- **Conflicte**: jo havia proposat `FOUNDATIONS…COMPETITION`; Learn ja usa **`EASY · MEDIUM · HARD`** a challenges i course maps (+ categoria `Grand · Garden` per a la mida de pista).
- ~~**Recomanació**: `Level.agilityhubLevel ∈ {EASY, MEDIUM, HARD}` i `Course.sizeCategory ∈ {GRAND, GARDEN}`; mapatge proposat del Cànic: Cadells/A/B → EASY · C/D/E → MEDIUM · F/G → HARD (es pot canviar a D11). Si Learn amplia l'escala, es migra l'enum (cap literal al codi).~~
- **Decisió Jordi:** (06-09) **No es fa — no mesclem temes.** Els nivells són catàleg **100 % local i configurable de cada club** (el SaaS permet crear-los i gestionar-los, D11); **cap** `Level.agilityhubLevel` ni mapatge amb l'escala de Learn. `EASY·MEDIUM·HARD` i `GRAND·GARDEN` queden **només al domini de cursos/Learn** com a metadades del recorregut. Els rings dels clubs no tenen a veure amb els dels challenges del Hub; l'únic punt de contacte possible (facilitar el muntatge d'una pista d'un challenge) queda per a R2+ si mai interessa. **Supersedeix** l'`agilityhubLevel` del 03-09 (part D). Cal corregir S05 i `MODEL_DADES_PLATAFORMA.md`.

### A6 · Recuperar el web-planner sencer (no només `course-core`)
- **Fet verificat**: el planner és molt més madur del que suposàvem (fase 12d, 21 fitxers de test, esquema Supabase complet, `PlannerStore` amb dues implementacions, `BuildSessionExportV1` per a l'app **Unity**, marcadors **AprilTag**, calibratge, sessions live, calendari, esdeveniments amb jutges).
- **Recomanació**: **recuperar-ho tot** implementant `CoreApiStore` (tercera implementació de `PlannerStore`) contra el core i movent pàgines i components React a `apps/clubs-admin` + `packages/course-ui`; el core reprodueix l'esquema (S16 §14.2) i serveix `BuildSessionExportV1` perquè l'app Unity segueixi funcionant. Reescriure només la capa Next (rutes, `supabase-server`, Resend). Guanyem mesos de feina feta; E9 passa de «construir» a «portar».
- **Si canvies** (només `course-core` + UI nova): E9 més llarga i l'app Unity trencada fins que es reescrigui el seu client.
- **Acció teva**: muntar l'**arrel** de `agilityhub-course-builder` (packages `course-core`, `shared-types`, `ui` i les apps Unity/Quest) i dir-me si Supabase té **dades reals** a migrar.
- **Decisió Jordi:** (06-09) **OK, recuperar-ho tot.** Queden pendents les dues accions: muntar l'arrel del repo i confirmar si Supabase té dades reals.

### A7 · Un club = un venue
- **Conflicte**: el planner treballa amb *venues* (sales, camps) que poden no ser clubs; el nostre model només tenia pistes del club.
- **Recomanació**: entitat `Venue` al core; **en crear un club es crea el seu venue** automàticament (les pistes de D16 són els rings del venue); venues sense club (sales de competició, jutges) a **R2**.
- **Decisió Jordi:** OK (06-09)

### A8 · Recorreguts: detalls tècnics amb impacte de producte
- (a) Edició in-place amb historial de 10 versions (recom.) vs versions immutables · (b) PDF del full de muntatge i dels marcadors generat al **client** i desat a S3 (recom.) vs al servidor · (c) persistir `resolvedObstacles` de cada col·locació (recom. sí: l'AR i les consultes els necessiten).
- **Decisió Jordi:** OK a les tres (06-09; b es manté al client perquè és el codi madur i testejat del planner que recuperem — revisar si mai hi ha inconsistències de render)

### A9 · AR/VR: la direcció ja presa
- **Fet verificat**: Unity + AprilTag (D-037) amb export `BuildSessionExportV1`, i un placeholder de **Quest** (VR). El spike S20 passa a ser una prova de camp amb l'app existent connectada al core; WebXR només com a pla B.
- **Recomanació**: confirmar-ho i fixar la **tolerància de col·locació** per a entrenament (proposo 30 cm de mitjana, 60 cm màxim — tu ets jutge) i el dispositiu objectiu (iPhone recent + 1 Android de gamma mitjana).
- **Decisió Jordi:** (06-09) OK a la direcció (Unity + AprilTag), a la tolerància 30/60 cm i als dispositius, **però amb matís**: l'app AR de muntar pistes **no és segura que es faci** — idealment sí, però **s'ha de validar que funciona** (S20 és un go/no-go real; no sobreinvertir-hi abans). El que es farà **segur** és **veure la pista col·locada al ring (hall) per planificar un entrenament o una competició** (placement + visualització, S16): això és prioritari i no depèn de l'AR.

### A10 · Accés de suport de plataforma («Entra com a administrador del club») a R1
- **Recomanació**: **sí**, amb restriccions (token de 60 min, tot auditat com `PLATFORM_SUPPORT`, sense IBAN complets ni exports); és la manera de donar suport al segon club sense demanar credencials.
- **Decisió Jordi:** OK (06-09)

### A11 · Certificats per als dominis dels clubs
- **Recomanació**: Caddy **on-demand TLS** amb `ask` al core (només emet certificats per a hosts verificats) — zero operació per club. Alternativa: wildcard + CNAME (no cobreix dominis propis dels clubs).
- **Decisió Jordi:** OK (06-09; el mateix Caddy serveix el proxy `/api/*` d'A1)

### A12 · Canvi de versió dels textos legals
- **Recomanació** (coherent amb el que has dit per als migrats): en publicar una versió nova, **pop-up amb casella** al següent accés; es pot posposar fins a 3 vegades (paràmetre) i després bloqueja. Els consentiments guarden versió i data.
- **Decisió Jordi:** OK (06-09)

### A13 · Backups: setmanal → diari quan la facturació entri a producció
- **Recomanació**: **diari** (mateix script, canvia el cron). Vas decidir setmanal el 03-09; ho torno a posar sobre la taula pel risc amb remeses.
- **Decisió Jordi:** (06-09) **Diari** — supersedeix el setmanal del 03-09.

### A14 · Compra de packs i pagaments des de l'app amb Stripe a R1
- **Recomanació**: **cap pantalla a R1** (el Cànic no usa Stripe); l'endpoint `POST /checkout-sessions {upfrontPaymentIds}` queda preparat i la classe individual `PAY_TO_BOOK` es construeix a E8 per als altres clubs.
- **Decisió Jordi:** OK (06-09)

### A15 · Mandats SEPA a la migració
- **Recomanació**: si Playoff exporta referència i data de mandat, **es migren** i les remeses van com a `RCUR`; si no, mandats nous `{club}-{número}-1` amb data d'alta i **cal parlar amb el banc** (`FRST` o comunicació). Pregunta B6 al Josep/Playoff.
- **Decisió Jordi:** OK (06-09; pendent de B6)

### A16 · Retenció i supressió (RGPD)
- (a) `rgpd.retentionYearsAfterLeave`: proposo **6 anys** per al perfil ES (Codi de Comerç, art. 30) en lloc dels 5 de la spec · (b) el paquet de dades de l'abonat inclou les observacions dels instructors (dret d'accés) i l'IBAN emmascarat (recom.) · (c) en suprimir, els noms dels gossos es pseudonimitzen (recom.) · (d) supressió d'un compte federat amb Learn: es tramita manualment a Learn (recom.) · (e) l'abonat demana la supressió al club, no des de l'app, a R1 (recom.).
- **Decisió Jordi:** OK a tot, amb (a) = **6 anys** (06-09)

### A17 · Exportacions i tauler
- (a) Els instructors **no** exporten llistats a R1 (403) · (b) avís de preinscripcions antigues: el mockup D1 diu «més de **2** dies» i el catàleg 7 → recom. **2** (mana el mockup; és un paràmetre) · (c) entrada «Auditoria» al menú Configuració (recom. sí).
- **Decisió Jordi:** OK (06-09)

### A18 · Processos automàtics
- (a) targeta «Processos automàtics» a D11 amb interruptor, [Simula] i [Executa ara] per a l'ADMIN (recom. sí; el Josep valida els literals) · (b) execució manual síncrona (recom.) · (c) `FINISHED` cada minut amb 15 min de gràcia (recom.) · (d) retencions: esdeveniments 90 dies, execucions 90, Stripe 400, exports 7 (recom.) · (e) també s'executen en clubs `ONBOARDING` (recom. sí, per a demos).
- **Decisió Jordi:** OK (06-09)

### A19 · Semàntica de la «setmana de reserva»
- **Conflicte**: si la setmana de reserva comença **diumenge 20:00**, la «setmana vinent» sempre té 1 plaça reservable i «Properament» és la d'aquí a dues setmanes; el literal de la pantalla 29 («Podràs reservar per a la setmana vinent a partir de diumenge a les 20 h») queda una mica imprecís.
- **Recomanació**: mantenir la semàntica (és la que descriu el Josep) i **ajustar el literal** de 29 a «Podràs reservar aquesta classe a partir de diumenge {dia} a les 20 h» (el back envia la data exacta). Pregunta de literal al Josep (B1).
- **Decisió Jordi:** OK (06-09)

### A20 · Detalls de reserves que afecten l'alumne
- (a) plaça de la llista d'espera: **hold + confirmació en 30 s** (qui toca primer i confirma) en lloc de «primer que confirma» estricte (recom.) · (b) N-04 també al membre del grup familiar que ha fet la reserva (recom. sí) · (c) baixa amb data futura: la data de baixa **inclosa** com a activa; les classes posteriors no són reservables (recom.) · (d) «Properament» només la setmana W2 (recom.).
- **Decisió Jordi:** OK (06-09)

### A21 · Activitats
- (a) tipus = enum de producte + etiqueta lliure (recom.) · (b) un esborrany **no** bloqueja pistes; es bloquegen en publicar, amb diàleg de conflictes que pot anul·lar classes (recom.) · (c) llista d'espera d'activitat amb promoció automàtica FIFO (recom.) · (d) **N-32d** «Activitat modificada pel club» amb SMS quan canvia data/hora/lloc amb inscrits (recom. acceptar: regla del Josep).
- **Decisió Jordi:** OK (06-09)

### A22 · Planificació
- (a) classes en diumenge per a altres clubs → R2 (recom.) · (b) esborrat físic de franges/classes de plantilla permès amb auditoria (recom.) · (c) l'instructor veu D3/D4 en lectura (recom.) · (d) descripció automàtica amb una sola forma «B+C» / «D i sup.» (els mockups en barregen tres) (recom.) · (e) l'alumne mai veu recomptes a la pantalla 10 (el mockup mostra «Teràpia 1 g») (recom.).
- **Decisió Jordi:** OK (06-09)

### A23 · Mòduls autoservei a D11
- **Recomanació**: el club pot activar/desactivar sol `FAQ`, `PUSH`, `LEARN_LINK`; la resta només des de la consola (canvien facturació o dades).
- **Decisió Jordi:** OK (06-09)

### A24 · Migració des de Playoff
- (a) baixes de fa més de 5 anys **no** es migren (RGPD, recom.) · (b) rebuts històrics en sèrie `PLAYOFF` separada (recom.) · (c) 24 mesos de rebuts (B8 amb comptabilitat) · (d) tall un diumenge abans de les 20:00, reserves futures no migrades (B9).
- **Decisió Jordi:** OK (06-09; c i d pendents de B8/B9)

### A25 · Learn
- (a) retirar el login clàssic de Learn 3 mesos després de la fase 2 (recom.) · (b) classificacions públiques amb nom del gos + inicials (recom.) · (c) validació manual dels vídeos de challenges (recom.) · (d) cal **crear** a Laravel `GET /api/v1/recommendations` i l'endpoint de course maps (R2; fet verificat: no existeixen).
- **Decisió Jordi:** OK (06-09)

### A26 · Textos legals (esborranys a `05-desenvolupament/legal/`)
- Generats: política de l'AgilityHub ID, plantilla de política del club, autorització d'imatge (casella + aclariment + clàusula), normes del club (plantilla editable com a `ClubPage`). **Recomanació**: revisió per un advocat abans d'E12; tu omples els marcadors `[…]`.
- **Decisió Jordi:** OK (06-09; pendent l'advocat i els marcadors)

### A28 · Data de cobrament de la remesa: el Josep diu **dia 1 del mes facturat** (supera la teva decisió del 05-09)
- **Conflicte**: el 05-09 vas decidir «remesa l'**últim dia del mes** facturat» (`billing.sepa.collectionDayOfMonth = 0`). El Josep (08-09) respon: «la quota d'octubre es cobra l'**1 d'octubre**», és a dir el **primer dia del mes que es factura** (cobrament per avançat, com fa el club avui).
- **Recomanació**: **aplicar el que diu el Josep** (és l'operativa real del club i encaixa amb la resta del sistema: la regla del dia 25 de l'inactivitat i de les altes és justament el tall per entrar a la remesa del mes següent). Semàntica nova del paràmetre: `billing.sepa.collectionDayOfMonth` = dia **del mes facturat** (`1–28`; `0` = últim dia); Cànic **1**. Conseqüència operativa: la remesa del mes M es genera i es presenta **el mes M−1** (≈ dia 25–27; SEPA CORE exigeix ≥ 2 dies hàbils abans de `ReqdColltnDt`), que és exactament el que ja fa la spec (R-12-06: «generació 25-08 per a setembre»).
- **Si canvies**: tornar a `0` (últim dia del mes facturat) és un canvi de paràmetre, sense codi; però el club cobraria un mes vençut en lloc d'avançat i el primer mes de cada abonat quedaria descobert.
- **Decisió Jordi:** (aplicat provisionalment el que diu el Josep; confirma-ho)

### A29 · Efectiu: el Josep diu **mai mensual, sempre per semestres naturals** (supera la teva decisió del 05-09)
- **Conflicte**: el 05-09 vas decidir «efectiu **mensual**» (`billing.cashInvoicing = MONTHLY`) i vam deixar el text de semestres de la pantalla 19 per revisar. El Josep (08-09): «**no hi ha l'opció de pagament mensual en efectiu**; és sempre per la primera fracció en mesos i després per semestres naturals».
- **Recomanació**: **aplicar el que diu el Josep** — el mecanisme ja existia a la spec (R-12-05 `SEMESTER`: un rebut amb `k` línies mensuals, `k` = mesos fins a final del semestre natural, i després semestres complets el gener i el juliol). Canvia només el valor del Cànic (`billing.cashInvoicing = SEMESTER`) i **el text de la pantalla 19 es manté** tal com és (parla de semestres: era correcte).
- **Si canvies**: `MONTHLY` és un canvi de paràmetre; el text de 19 s'hauria de reescriure amb el Josep.
- **Decisió Jordi:** (aplicat provisionalment el que diu el Josep; confirma-ho)

### A27 · Accions que només pots fer tu (no són decisions, són bloquejos)
- Muntar l'arrel de `agilityhub-course-builder` (A6) · confirmar dades reals a Supabase · llista d'admins de plataforma (A3) · compte SendGrid i domini verificat (E1) · compte Twilio (E7) · compte Stripe de test (E8) · XSD pain.008 i banc (E8; SEPA **genèric**, sense dependre de CaixaBank — Jordi 05-09) · exports de Playoff + llista d'equip (E2/E12) · DNS del Cànic (E10; «el generarem al deploy» — Jordi 05-09) · reunió amb qui porta la comptabilitat (format d'export, E8).
- **Playoff — resposta a la teva pregunta («què necessites? vols accés de nou?»)**: no cal accés a Playoff ara. Per a E2 només necessito, de cada export (abonats, gossos, tipologies/quotes, rebuts, mandats), **els noms de columna i 3–5 files anonimitzades** (o l'export sencer fora del Dropbox: el `migration:anonymize` en treu les fixtures i l'original no es guarda). Per a E11/E12 caldrà l'export complet en lectura, fet pel Josep el dia del tall, i mai copiat al projecte. La llista d'instructors i administradors (noms + correu) sí que la necessito abans d'E2 per als seeds i les invitacions.
- **Estat:** (06-09) Repos `agilityhub-core-api`/`-web` creats ✅ i A3 resolt ✅. Segueixen pendents: arrel de `agilityhub-course-builder` + dades de Supabase (A6) · SendGrid + domini verificat (E1) · llista d'instructors/admins per als seeds (E2) · exports Playoff amb columnes + files anonimitzades (E2) · Twilio (E7) · Stripe test (E8) · XSD pain.008/banc (E8) · reunió comptabilitat (E8) · DNS (E10) · advocat (E12).

---

## Part B — Respostes del Josep (rebudes el 08-09-2026) ✅ les 21 del correu, tancades · **B29–B33 noves (09-09), pendents**

Correu enviat el 06-09 (`CORREU_JOSEP_PENDENTS_B.md`, 21 preguntes amb el valor per defecte entre parèntesis) i respost el 08-09. Llegenda: **✅** confirma l'assumpció (res a tocar) · **🔄** canvia l'assumpció (spec i catàleg actualitzats el 08-09) · **⚠️** supera una decisió teva anterior → **A28**/**A29**.

| # | Pregunta (núm. al correu) | Resposta del Josep | Efecte aplicat el 08-09 |
|---|---|---|---|
| B1 | Literal de la pantalla 29 (19) | Sí | ✅ «Podràs reservar aquesta classe a partir de diumenge {dia} a les 20 h» (A19 tancat) |
| B2 | Efectiu: mensual o semestres? (8) | **No hi ha pagament mensual en efectiu**: sempre la primera fracció en mesos i després **semestres naturals** | 🔄⚠️ `billing.cashInvoicing = SEMESTER` (S12 R-12-05); el text de semestres de la pantalla 19 **es manté**. Supera «efectiu mensual» (Jordi 05-09) → **A29** |
| B3 | IVA | resolt per Jordi 05-09 (sense IVA) | — |
| B4 | Data de cobrament de la remesa (7) | **La quota d'octubre es cobra l'1 d'octubre** | 🔄⚠️ `billing.sepa.collectionDayOfMonth = 1` amb semàntica nova = dia **del mes facturat**; la remesa es genera el mes anterior (≈ dia 25). Supera «últim dia del mes» (Jordi 05-09) → **A28** |
| B5 | `FRST` o `RCUR`? (6) | **Recurrent per a tot** | ✅ `billing.sepa.useFrst = false` (S12 R-12-12) |
| B6 | Playoff exporta referència i data del mandat? (1) | **No ho tenim: cal fer mandat nou** | ✅ (branca ja prevista a A15) mandats nous per a tothom `{clubSlug}-{memberNumber}-1`, `mandateSignedAt` = data del tall, comunicació al banc; amb tot `RCUR` no cal seqüència `FRST` (S18 R-18-09) |
| B7 | Tipologies vives i mapatge (2) | Excel d'abonats per tipologia + per nivell + export complet de socis (rebuts 09-09) | ✅ **tancat**: `MappingConfig` v4 de S18 amb les 10 tipologies vives i els seus recomptes, i taula completa de les 51 columnes a **`MAPATGE_CAMPS_PLAYOFF.md`**. En queden 3 caps solts → **B30, B31, B32** |
| B8 | 24 mesos de rebuts? Impagats? (3) | **24 mesos correcte; impagats pendents també, però no n'hi ha cap** | ✅ `history.receiptsMonths = 24`; la branca d'impagats es manté per a altres clubs |
| B9 | Tall en diumenge abans de les 20:00 (4) | **Sí** | ✅ sense migració de reserves futures |
| B10 | Teràpia: qui posa la quota de manteniment? (9) | **L'alta en tarifa Teràpia implica cobrament mensual de manteniment automàtic fins que es canviï de tarifa** | 🔄 passa a ser propietat de la **modalitat** (`Plan.billingMode = MAINTENANCE`), no un commutador de la fitxa: `Member.billingMode` es retira (S05, S12 R-12-02, S03 D10 en lectura) |
| B11 | Caducitat de pack i retorn de sessions (10) | **Caduca l'11-11; les sessions no fetes en aquella data (inicials o retornades per una anul·lació) es perden** | ✅ data · 🔄 cap retorn ni pròrroga després de la caducitat (S12 R-12-23, S08 R-08-17) |
| B12 | Inactivitat: canvis, packs, entrada (11) | **Els canvis s'apliquen sols** · **el pack no té quota mensual: no té sentit demanar inactivitat; en arribar a la data final venç** · **per continuar cal fer-se abonat, amb 40 % de descompte en l'entrada si es ve d'un pack de 10** | ✅ canvis automàtics · 🔄 els plans `PACK` **no poden demanar inactivitat** (`422 INACTIVITY_NOT_APPLICABLE`, S13 R-13-02) · 🔄 nou `billing.packToMemberEntryDiscountPercent = 40` amb `minPackSessions = 10` (S05 R-05-18b) · ✅ reactivació sense entrada |
| B13 | Alta: segon gos i readmissió (12) | **En afegir un segon gos es paga l'entrada i la quota addicional del gos (normalment 50 %) del mes en curs; si la inscripció es fa abans del dia 25 i és per al mes següent, només es paga l'entrada** | 🔄 S04 R-04-14 (mode «afegir gos»: `ENTRY_FEE` + `ADDITIONAL_DOG_FEE` del mes en curs, o només `ENTRY_FEE` amb efecte l'1 del mes següent si avui ≤ `billing.upfrontCutoffDay = 25`). Readmissió/xip/«compte no informat»: sense resposta → assumpció vigent |
| B14 | «Notes als instructors» a l'alta | no preguntat | assumpció vigent (camp opcional a 17) |
| B15 | Límit de 3 entrenaments/setmana (13) | **El límit s'aplica pels dies de la sessió, no per quan es reservi** | ✅ S09 (ja era així) |
| B16 | Assistència editable fins a l'endemà (14) | **Sí** | ✅ S10 |
| B17 | Revisió de risc només a les 7:30 (15) | **Com a procés planificat, només a les 7:30. Però una anul·lació en termini que deixi la classe amb un sol alumne ha d'avisar instructors i administrador i no fer res més** | ✅ procés · 🔄 **nou avís immediat N-54** «Classe amb un sol alumne» (S15 R-15-12b; cap anul·lació automàtica) |
| B18 | Comunicats immediats i només a actius (18) | **Sí** | ✅ S11 |
| B19 | Cens: canvi de nivell amb reserves futures (16) | **Es mantenen i no cal avisar** | 🔄 S03 R-03-12: es treu `warnings.futureBookingsOutsideLevel` i qualsevol avís a l'admin (N-09 al propietari es manté) |
| B20 | Activitats: fins quan es pot anul·lar (17) | **Fins a l'hora d'inici de l'activitat** | 🔄 `activities.cancelDeadline = EVENT_START` (S07 R-07-09) |
| B21 | Comunicats / matriu de 12 | no preguntat | assumpció vigent |
| B22 | FAQ, normes del club i textos de modalitats (20) | **Més endavant; de moment text provisional i ja els informaran des de la mateixa eina** | 🔄 seeds amb text provisional marcat (`[Text pendent — el club l'omplirà des de Paràmetres]`) i **ítem de sortida a E12**: cap text provisional a producció (S05, `legal/NORMES_CLUB_PLANTILLA.md`) |
| B23 | Festius (21) | **Ho hem de poder definir des de dins del propi sistema** | 🔄 ja previst: `club.holidays` editable a D11 (S02 R-02-09) → **cap llista al seed** i el dubte 3 de S02 §13 es tanca «llista mantinguda des de D11, sense importació de calendari» |
| B24 | Pantalles sense mockup | «els detalls petits i els textos de les pantalles noves els veuràs a l'entorn de proves» | ✅ es validen a staging |
| B25 | Literals proposats | idem | ✅ es validen a staging |
| B26 | Pendents antics (consells fixats, WhatsApp) | no preguntat | assumpció vigent |
| B27 | Dispositiu objectiu per a l'AR | resolt per Jordi 06-09 | — |
| B28 | **Llista d'instructors i administradors** (5) | **Rebuda i confirmada** (Jordi 09-09): 3 administradors i 4 instructors | ✅ **no es copia en aquesta carpeta** (Dropbox + dades personals). S'ha preparat el fitxer de seed `staff.canic.yaml` i s'ha enviat a Jordi per posar-lo al repo `agilityhub-core-api` (`seed/clubs/canic/`), que és on el llegirà `club:apply` (E2-T12) |

### Preguntes noves sortides dels exports reals (09-09) — per al Josep

De l'anàlisi dels tres exports del 07-09 (`MAPATGE_CAMPS_PLAYOFF.md`). Cap bloqueja el desenvolupament: totes tenen assumpció aplicada.

| # | Pregunta | Assumpció aplicada | Si respon diferent |
|---|---|---|---|
| B29 | **Foto** (145 altes en tenen): la foto de la fitxa és **del gos** o de la persona? | del gos → `Dog.photoFileKey` (és on el producte la mostra: 13, D13, llistes de classe) | passa a foto de l'abonat (camp nou a `Member`) |
| B30 | **«Quota reduïda»** (4 abonats): quina modalitat és i què paga? | `ABONAT` amb una tarifa pròpia «Quota reduïda» (import a confirmar); mentrestant queden **sense modalitat** amb avís | modalitat pròpia al catàleg |
| B31 | **«Familiar Abonat/curs»** (3 abonats): és el mateix que «familiar abonat» o una cosa diferent (un curs)? | és `ABONAT_FAMILIAR`; mentrestant **sense modalitat** amb avís | modalitat pròpia |
| B32 | **Nivell «Pendent»** (6 gossos): creem un nivell «Pendent» al catàleg (visible, sense classes assignades, fins que se'ls avaluï)? | sí, nivell `PENDENT` al catàleg del club (no reservable perquè cap classe el té) | els deixem sense nivell (i no poden reservar fins que en tinguin) |
| B33 | **Emails compartits** (~20 altes comparteixen adreça amb un altre abonat): són famílies? | un compte per adreça: el primer se la queda i la resta es migren **sense compte**, amb proposta de grup familiar per revisar | ens passa la llista de qui va amb qui i es creen els grups directament |

També per al Josep, abans del tall (no és una decisió, és feina de dades): **26 abonats amb domiciliació i sense IBAN** i **16 gossos sense número de xip**; l'informe de migració els llistarà per número d'abonat.

## Part C — Assumpcions de detall (acceptades si no dius res)

Tècniques o d'UI menor; cadascuna és al §13 de la spec indicada amb el seu raonament. Marca `✗` davant de les que vulguis discutir.

- S01: `Account.name` es sincronitza amb el nom de l'abonat només si el compte té una sola membresia · «Tanca la sessió» com a fila al final de 12 · enllaç màgic d'admins d'escriptori amb la mateixa validesa (15 min) · pantalles d'`apps/id` amb marca AgilityHub.
- S02: la fila «Nivell mínim · D» de D11 passa a resum de lectura «Nivells amb entrenament lliure: D · E · F · G» · ajuda «S'aplica a partir d'ara» als paràmetres · host de club amb app equivocada → 404.
- S03: «Nou abonat» obre el formulari d'alta dins del backoffice · fitxa del gos al backoffice amb els blocs de D13 · telèfons sencers (només IBAN i DNI emmascarats) · email de login independent del de contacte · tasques fetes visibles 30 dies · llicències com a text lliure amb suggeriments · `GET /country-profile/postal-codes/{code}`.
- S04: literal del pas 18 proposat «Revisa el nom del responsable i el del gos» · la resposta «verificació enviada» pot revelar que un DNI existeix (acceptat amb rate limit) · pantalla «Sol·licitud enviada» mínima · mana el paràmetre `signup.text.imageConsent` sobre el camp de CLUB · enllaç de benvinguda de 7 dies.
- S05: targeta «Nivells» dins D11 amb el patró de D16 · administradors com a perfil dins la membresia (`/administrators`) · tot import facturable és un `Price` amb vigència · `Plan.dogsIncluded` + `% familiar` per proposar la tarifa · paleta de colors de nivell proposada · desactivar una pista amb classes de plantilla = incoherència a D3 · sense avís «ara ets instructor» a R1.
- S06: cobertura amb ocupació real a D4 no es mostra a R1 · xip «exempta de la revisió» a la targeta de classe · bloqueig sobre una classe es refusa (409).
- S07: bloc d'activitats de 04 filtrat pel gos seleccionat (la inscripció és per persona) · files d'activitat sense gos · finestra de muntatge/desmuntatge informativa · imatges públiques per redirecció signada.
- S08: «ja ha fet classe» = feta o anul·lada tard la mateixa setmana · «ha avisat» de l'instructor → avís sense SMS · 30 min per completar un pagament de classe individual.
- S09: les classes en esborrany bloquegen slots · graella fixa de 30 min des de l'obertura · reserva/bloqueig d'instructor amb els motius de 24 · pantalla d'escriptori «Entrenaments» = llistat universal · tall matí/tarda a les 14:00 (front).
- S10: passar llista des de les 00:00 del dia · el moment que compta per a «ha avisat» és el del desat · anul·lacions tardanes no surten a 21 · mètrica d'entrenaments per gos · cercador d'alumnes = llistat de gossos · reobrir tasques només instructor/admin · l'alumne veu només les tasques dels seus gossos · anul·lades futures a l'històric.
- S11: textos per a instructors/admin de producte (no editables) · entrar a 11 marca-ho tot llegit · plantilles desactivables i plantilles pròpies amb SMS · sense callback d'entrega de Twilio a R1 · entrada «Aprèn amb AgilityHub» a 12 · log de notificacions es conserva (pseudonimitzat en suprimir).
- S12: pantalla de remeses i 12/rebuts amb el design system · botó 2 de D6 per a clubs sense SEPA «GENERA ELS REBUTS (I COBRA LES TARGETES)».
- S13: «inactiva fins {últim dia del mes}» · pàgina «Inactivitats i baixes» i calaixos de D10 · un període demanat i no decidit caduca en silenci amb auditoria.
- S14: salutació del tauler per franja horària sense nom · pantalles d'auditoria/exports amb el patró D5 · `riskNotice` a la classe per saber a qui s'ha avisat.
- S15: N-17 també amb 0 inscrits · no es reactiva una classe anul·lada per la revisió (es crea de nou) · baixa efectiva l'endemà de la data · comptador d'SMS per mes local.
- S16: «Registra què hi ha muntat» accessible amb una icona a la capçalera de pista (20/23/24) · l'alumne veu el recorregut sencer si el club ho permet.
- S17: club plantilla `club-template-default` mantingut al seed.
- S01 (06-09, `apps/id`): els literals de les pantalles sense mockup (`packages/i18n/src/locales/ca/id.json`: login, enllaç, contrasenya, compte, productes) són una proposta per al Josep; les etiquetes de rol «Alumnat / Instrucció / Administració» es substituiran pels perfils amb gènere quan arribi `membership.gender`.
- S01 (06-09): `/products` mostra enllaços estàtics a Learn i Clubs a R1 (spec §2) fins que `userinfo.memberships[]` porti `clubName`/`appUrl` (E1-T05).
- S03 (06-09, D5/D15): la vista desada per defecte es guarda com a preferència del navegador fins que el contracte d'E2-T01 exposi `isDefault`; la columna de nivell sempre visible al Cànic (`levels.enabled`); les columnes de facturació/grup familiar/entrenament/packs depenen del mòdul.
- S17/S02 (06-09): el **tema genèric AgilityHub** (club mínim, `apps/id`) usa una paleta neutra blava/clara provisional (`#2563EB` sobre fons clar) fins que Jordi doni la paleta de marca; el tema del Cànic surt dels mockups (fosc + taronja `#E26A2A`, Montserrat).

---

## Part E — Decisions de contracte preses per l'organitzador el 06-09 (dia 1 de desenvolupament) — revisa-les amb calma

Cadascuna ja és aplicada a la spec/catàleg (Dropbox i `docs/` dels dos repos) i al codi verificat. Marca `✗` davant de la que vulguis canviar: la desfaré amb una tasca de correcció.

**Revisió Jordi (06-09): OK a totes 21, cap ✗.** Nota: A1 (proxy) canvia el *context* d'E18 però el pont OIDC i l'anell de claus es mantenen; el que es corregeix és l'emmagatzematge del token al front (tasca de correcció d'A1).

| # | Decisió | On | Per què |
|---|---|---|---|
| E1 | Codis d'error sense estat explícit: `_EXISTS/_TAKEN/_IN_USE/_LOCKED/_OVERLAP/_CONFLICT/ALREADY_*` → 409, la resta → 422; nou codi transversal `IDEMPOTENCY_KEY_REUSED` (409, `details.reason = DIFFERENT_REQUEST · IN_PROGRESS`) | `CATALEG_ERRORS.md` §3 regla 0, §transversals | l'executora ho va demanar a E0-T04; sense regla, cada spec triava un estat diferent |
| E2 | Auditoria **dins la mateixa transacció** que el canvi (S14 R-14-09), noms `entityType/entityId/changes[].path`; `SecurityEvent` a `platform`; retenció `security.eventRetentionDays = 90` (catàleg) | E0-T07, E0-T11 | el text de la tasca (afterCommit, 365 dies) contradeia la spec/catàleg: mana la spec |
| E3 | 14 valors per defecte nous al catàleg (textos d'alta, `leave.reasons`, `census.dogDocumentTypes`, `messaging.email.fromAddress = no-reply@agilitydoghub.com`, `replyTo = ""`, `sms.senderId = AgilityHub`, `signup.rateLimit`, `learn.baseUrl`) | `CATALEG_PARAMETRES.md` | claus que les specs citaven sense valor |
| E4 | Contracte `/branding`: instantània pública `{club {slug, name, city?}, theme {colors…, fontFamily, radius, ringPalette, logoUrl, logoDarkUrl, markUrl, mode}, locales, defaultLocale, …}` sense id de club; `club.city` afegit el 06-09 (peu de la pantalla 01) | E0-T05, E1-T02 pas 9, web E1-W05 | el front no ha de conèixer ids; el peu «{club} · {població}» ha de ser del contracte, no un literal |
| E5 | **White-label estricte**: cap literal del club al codi ni als fitxers d'idioma (nom, població, «CÀNIC AGILITY / escola canina», «Club Agility …»); la marca és **imatge** (`theme.logoUrl/logoDarkUrl/markUrl`); s'ha generat `03-disseny/marca/logo_complet_fons_fosc.png` | web `AGENTS.md` §2, E1-W05 | els mockups són la instància del Cànic d'un producte genèric |
| E6 | Rutes del front: `/entrar`, `/activacio`, `/perfil-acces` (pantalles 01/02/03b); `apps/id`: `/login`, `/magic-link` (+ àlies `/magic`), `/set-password`, `/account`, `/products`, `/logout` | S01 §2 | segons spec (el text de la tasca deia `/acces`) |
| E7 | Hosts: només OAuth2/OIDC (`/.well-known/*`, `/oauth2/*`, `/connect/logout`) a `id.agilitydoghub.com`; `/auth/magic-link` i `/auth/handoff` sota `core.*/api/v1` | S01 §6 v0.3, `CONVENCIONS_API` §1 | són API d'aplicació, no OAuth2 |
| E8 | `GET /me` (R-01-15) afegeix `account.hasPassword`, `account.emailVerifiedAt?`, `account.onboardingPending` i `membership.gender?` (`MALE·FEMALE·OTHER`, de `Member.gender`) | S01 v0.3 | 02 (bloc de contrasenya opcional, badge «Compte activat»), R-01-05 (`current` condicional) i l'ICU de gènere de 02/03b ho necessiten |
| E9 | Contracte d'onboarding (§14, A12): `GET /me/onboarding → OnboardingState {pending, postponeRemaining, requiredConsent {policy PLATFORM·CLUB, version, url} \| null, fields[] {key, value, required}}`; `PUT {consentAccepted, consentVersion, fields?, imageConsent?}`; `POST /me/onboarding/postpone`; paràmetres `signup.onboardingFields` (json) i `legal.maxPostpones = 3` | S01 §6 v0.3, catàleg | la spec no tenia la forma; l'executora n'havia inventat una altra |
| E10 | Detalls S01 confirmats tal com els va implementar l'executora: `Profile` = enum de rols; `/me/sessions` = array acotat amb `id` opac; `PATCH /me` → `Me`; `POST /platform/accounts` → `AccountSummary`; cossos buits a la resta de mutacions; `REFRESH_EXPIRED` = 400 (catàleg) tot i l'exemple 401 de R-01-06; `/oauth2/authorize` inclòs; l'enllaç màgic comparteix la quota per IP de `/oauth2/token` | E1-T01 | forats de la spec resolts amb el criteri més simple |
| E11 | `WEBHOOK_SIGNATURE_INVALID` passa de 400 a **401** (S11 tenia raó; S12 alineada) | `CATALEG_ERRORS.md`, S12 R-12-21 | una signatura invàlida és una petició no autenticada |
| E12 | Nova acció d'auditoria `ACCOUNT_EMAIL_STATUS_CHANGED` (S11 → `Account.emailStatus` BOUNCED/COMPLAINED); `Notification.recipientEmail` es pseudonimitza a la supressió (R-14-15) | S14 R-14-09, R-14-15 | l'executora feia servir `MEMBER_UPDATED` sobre un `Account` |
| E13 | Correu: `SystemNotificationService.send` es crida **després** del commit de la transacció de qui l'invoca (grava QUEUED + outbox abans de la I/O); `LogEmailSender` a `local`, arrencada fallida a `staging/prod` sense `SENDGRID_API_KEY`; l'enviament real es prova a staging | E1-T03 | sense compte SendGrid no s'atura el desenvolupament |
| E14 | Web: els tipus es generen de l'**snapshot OpenAPI real** copiat de l'api (`packages/api-client/openapi/openapi.json`, mai editat a mà) fusionat amb `pending.json` (contractes encara no publicats per l'api, mocks-first); test de fixtures contra esquemes | E1-W06 | acaba amb l'stub i la cerca del repo germà (no existeix a CI) |
| E15 | Tema genèric AgilityHub provisional (blau neutre/clar) fins que donis la paleta de marca | Part C | — |
| E16 | `MEMBER_ERASED` (409) afegit al catàleg d'errors (S14 el definia però el catàleg no el tenia); `HANDOFF_INVALID` i `TENANT_MISMATCH` afegits a la llista d'esdeveniments de seguretat S14 R-14-17 | catàlegs, S14 | consolidació incompleta del 03-09 |
| E17 | `POST /members/{id}/impersonation-token` → `{token, expiresAt, launchUrl}` (S01 alineada amb S03 §6); `launchUrl` porta un codi de handoff d'un sol ús, mai el JWT | S01 §6, E1-T04 | «Entra com l'abonat» obre l'app del club en una pestanya nova sense exposar el token |
| E18 | Pont OIDC per a l'SPA `apps/id`: `POST /oauth2/session {flow} → {redirectUrl}` (nou endpoint a S01 §6); claus de signatura en un anell xifrat AES-256-GCM a Mongo amb `OIDC_MASTER_KEY` (R-01-16 actualitzada; abans PEM a `.env`) | S01 §6, R-01-16, E1-T05 | cal un pont entre el login de l'SPA i la sessió de navegador OIDC; A1 (emmagatzematge xifrat) |
| E19 | Regla de col·locació del logo: espais compactes (capçalera/lateral < 48 px) = marca + nom del club; espais grans (pantalles d'accés, targetes) = logo complet (`logoDarkUrl` en mode fosc) | web `AGENTS.md` §2, `packages/ui` | el logo complet vertical era il·legible a 40 px |
| E20 | Pantalla 28 sense consentiments ni idioma (mockup/S03 §2 manen sobre el text de la tasca); tasques de 13 embegudes a `GET /me/dogs` segons R-03-18 (contracte api E2-T06) | E2-W03, E2-T06 | — |
| E21 | Desplegament (nou a la llista de Jordi): `OIDC_MASTER_KEY` per entorn i les URL de callback dels clients OIDC (`core.oidc.clients[]`), `SENDGRID_API_KEY`, domini verificat | `.env.example` de l'api | — |

---

## Part D — Ja resolt (per a constància)

| Data | Decisió |
|---|---|
| 03-09 | AgilityHub ID al core; course-core a R1; i18n total + perfil de país; tots els mòduls activables; regles generalitzades; pagaments SEPA/Stripe/manual; `agilityhubLevel`; repos `agilityhub-core-*`; noms Learn/Clubs/ID; etapes sense dates; specs per vertical + pantalles |
| 03-09 | Numeració única de notificacions (N-39…N-53); `Dog.status PENDING·ACTIVE·INACTIVE`; N-08b des de `ClassSessionUpdated`; bloquejos d'activitat síncrons; `IMPERSONATION_DENIED`; 422 per a precondicions de l'abonat |
| 05-09 (Jordi) | Onboarding «Completa el teu perfil» per als migrats; **SendGrid** com a proveïdor d'email (ADR-005); normes del club com a pàgina editable (`ClubPage`); esborranys legals generats |
| 05-09 (codi) | Learn: JWT + bcrypt 12, sense login social, alta per `POST /users`, API a `app.agilitydoghub.com`, 6 idiomes; escala `EASY·MEDIUM·HARD`; web-planner: `PlannerStore`, Supabase, Unity + AprilTag, format Smarter verificat (fixtures) |
| 06-09 (Jordi) | **Part A completa** (criteri: qualitat per davant del temps): A1 **proxy same-site + cookies httpOnly** (supersedeix el WebCrypto; tasca de correcció) · A3 admin inicial `jboix@agilitydoghub.com` + alta/baixa d'admins fàcil via API · A5 **nivells 100 % locals i configurables del club, sense `agilityhubLevel` ni vincle amb Learn** (supersedeix el 03-09; corregir S05 i model) · A6 recuperar el planner sencer · A9 tolerància 30/60 cm i dispositius OK, **app AR condicionada a validació S20** (segur: veure la pista al ring per planificar entrenament/competició) · A13 **backup diari** (supersedeix setmanal) · A16a retenció **6 anys** · resta d'A: OK segons recomanació · **Part E: les 21 confirmades** |
| 08-09 (Josep) | **Part B tancada** (21 respostes): mandats SEPA nous per a tothom i tot `RCUR` · 24 mesos de rebuts, sense impagats · tall en diumenge · **cobrament l'1 del mes facturat** (⚠️ A28) · **efectiu només per semestres naturals** (⚠️ A29) · Teràpia = quota de manteniment automàtica per modalitat · sessions de pack no fetes es perden en caducar · packs sense inactivitat + **40 % de descompte d'entrada** en passar de pack de 10 a abonat · segon gos = entrada + quota del mes en curs (regla del dia 25) · **avís immediat de classe amb un sol alumne** · canvi de nivell sense avís · anul·lació d'activitat fins a l'inici · FAQ/normes/modalitats amb text provisional · festius mantinguts des del sistema · llista d'instructors i administradors rebuda |
| 09-09 (Jordi) | **Exports reals de Playoff** (socis, tipologies, nivells del 07-09): mapatge de les 51 columnes tancat (`MAPATGE_CAMPS_PLAYOFF.md`; se n'aprofiten 44) · `MappingConfig` v4 de S18 amb tipologies i nivells vius · **camp nou `Dog.handlerName`** («Nom del guia») + `licenses[].category/division` · R-18-11b amb les incidències reals · fixtures anonimitzades i `staff.canic.yaml` lliurats fora del Dropbox · noves preguntes B29–B33 |
