# Registre de decisions pendents — per a Jordi (i preguntes per al Josep)

**v1.2 · 06-09-2026** (v1.1 + Part E: decisions de contracte preses per l'organitzador durant el primer dia de desenvolupament, per revisar) · v1.1 · 05-09-2026 (v1.0 + respostes inline de Jordi a `PENDENTS_DESENVOLUPAMENT.md` §3–4: 4 h, efectiu mensual, sense IVA, cobrament l'últim dia del mes, gamificació R2 OK, DNS al deploy) · Consolida els §13 de les 20 specs, `PENDENTS_DESENVOLUPAMENT.md` i les revisions de codi (Learn, web-planner). Cada punt diu **on hi ha el conflicte o el dubte**, **què recomano** (i per què) i **què canvia si tries una altra cosa**. El desenvolupament **ja pot avançar** amb la recomanació aplicada: totes les specs la tenen incorporada com a assumpció.

**Com respondre**: edita aquest fitxer i escriu a la línia `Decisió Jordi:` un `OK` o el canvi que vulguis (una frase). Els punts de la part B són per al Josep: si ja saps la resposta, escriu-la; si no, deixa `→ Josep` i els agruparé en un correu. En la propera sessió llegeixo aquest fitxer i actualitzo specs i catàlegs.

---

## Part A — Decisions de producte i de plataforma (Jordi)

### A1 · Sessió a la PWA: on guardem el refresh token
- **Conflicte**: `clubs.*`/`app.agilitycanic.cat` i `core.*` són dominis diferents → una cookie httpOnly no pot viatjar sense `SameSite=None` i tercers. Alternativa: proxy `same-site` (`app.agilitycanic.cat/api` → core) a cada àlies de club.
- **Recomanació**: refresh token **xifrat amb WebCrypto** al storage del navegador (clau no exportable), access token només en memòria; el proxy same-site queda com a millora d'E11 si es vol treure el token del storage. Cost mínim, funciona a iOS PWA.
- **Si canvies**: proxy per host a Caddy per a cada club (configuració per domini) i cookies; +2 dies a E1 i a E10.
- **Decisió Jordi:** 

### A2 · Comprovar contrasenyes compromeses (HIBP k-anonymity) a R1
- **Recomanació**: **sí** (`auth.checkCompromisedPasswords = true`): és una crida externa barata i evita contrasenyes trencades; només s'aplica a contrasenyes noves (les de Learn no es toquen).
- **Decisió Jordi:** 

### A3 · Comptes importats de Learn: rols i idioma inicials
- **Fet verificat**: Learn té `users.role` (`user·admin·anonymous·coach·designer`), cap columna d'idioma, 6 idiomes al front (`de en es fr no pt`, fallback `es`), ~1.000 usuaris.
- **Recomanació**: importar tots els comptes amb `locale = es` (canviable), `externalIds.learnRole` informatiu, **cap `AGILITYHUB_ADMIN` automàtic** — només els comptes que tu indiquis (el teu i el de qui gestioni la consola); el compte convidat (`anonymous@…`) **no** s'importa. Els `coach/designer` quedaran per a R2 (dissenys de recorreguts personals).
- **Decisió Jordi:** (llista d'emails que han de ser admins de plataforma)

### A4 · Onboarding dels comptes importats i migrats
- **Ja decidit per tu (05-09)**: pantalla «Completa el teu perfil» al primer accés, amb casella obligatòria de la política de privacitat i dades bàsiques opcionals (S01 §14, S18). Ho confirmo aquí: **OK per defecte**.
- **Decisió Jordi:** 

### A5 · Escala AgilityHub de nivells = la dificultat real de Learn
- **Conflicte**: jo havia proposat `FOUNDATIONS…COMPETITION`; Learn ja usa **`EASY · MEDIUM · HARD`** a challenges i course maps (+ categoria `Grand · Garden` per a la mida de pista).
- **Recomanació**: `Level.agilityhubLevel ∈ {EASY, MEDIUM, HARD}` i `Course.sizeCategory ∈ {GRAND, GARDEN}`; mapatge proposat del Cànic: Cadells/A/B → EASY · C/D/E → MEDIUM · F/G → HARD (es pot canviar a D11). Si Learn amplia l'escala, es migra l'enum (cap literal al codi).
- **Decisió Jordi:** 

### A6 · Recuperar el web-planner sencer (no només `course-core`)
- **Fet verificat**: el planner és molt més madur del que suposàvem (fase 12d, 21 fitxers de test, esquema Supabase complet, `PlannerStore` amb dues implementacions, `BuildSessionExportV1` per a l'app **Unity**, marcadors **AprilTag**, calibratge, sessions live, calendari, esdeveniments amb jutges).
- **Recomanació**: **recuperar-ho tot** implementant `CoreApiStore` (tercera implementació de `PlannerStore`) contra el core i movent pàgines i components React a `apps/clubs-admin` + `packages/course-ui`; el core reprodueix l'esquema (S16 §14.2) i serveix `BuildSessionExportV1` perquè l'app Unity segueixi funcionant. Reescriure només la capa Next (rutes, `supabase-server`, Resend). Guanyem mesos de feina feta; E9 passa de «construir» a «portar».
- **Si canvies** (només `course-core` + UI nova): E9 més llarga i l'app Unity trencada fins que es reescrigui el seu client.
- **Acció teva**: muntar l'**arrel** de `agilityhub-course-builder` (packages `course-core`, `shared-types`, `ui` i les apps Unity/Quest) i dir-me si Supabase té **dades reals** a migrar.
- **Decisió Jordi:** 

### A7 · Un club = un venue
- **Conflicte**: el planner treballa amb *venues* (sales, camps) que poden no ser clubs; el nostre model només tenia pistes del club.
- **Recomanació**: entitat `Venue` al core; **en crear un club es crea el seu venue** automàticament (les pistes de D16 són els rings del venue); venues sense club (sales de competició, jutges) a **R2**.
- **Decisió Jordi:** 

### A8 · Recorreguts: detalls tècnics amb impacte de producte
- (a) Edició in-place amb historial de 10 versions (recom.) vs versions immutables · (b) PDF del full de muntatge i dels marcadors generat al **client** i desat a S3 (recom.) vs al servidor · (c) persistir `resolvedObstacles` de cada col·locació (recom. sí: l'AR i les consultes els necessiten).
- **Decisió Jordi:** 

### A9 · AR/VR: la direcció ja presa
- **Fet verificat**: Unity + AprilTag (D-037) amb export `BuildSessionExportV1`, i un placeholder de **Quest** (VR). El spike S20 passa a ser una prova de camp amb l'app existent connectada al core; WebXR només com a pla B.
- **Recomanació**: confirmar-ho i fixar la **tolerància de col·locació** per a entrenament (proposo 30 cm de mitjana, 60 cm màxim — tu ets jutge) i el dispositiu objectiu (iPhone recent + 1 Android de gamma mitjana).
- **Decisió Jordi:** 

### A10 · Accés de suport de plataforma («Entra com a administrador del club») a R1
- **Recomanació**: **sí**, amb restriccions (token de 60 min, tot auditat com `PLATFORM_SUPPORT`, sense IBAN complets ni exports); és la manera de donar suport al segon club sense demanar credencials.
- **Decisió Jordi:** 

### A11 · Certificats per als dominis dels clubs
- **Recomanació**: Caddy **on-demand TLS** amb `ask` al core (només emet certificats per a hosts verificats) — zero operació per club. Alternativa: wildcard + CNAME (no cobreix dominis propis dels clubs).
- **Decisió Jordi:** 

### A12 · Canvi de versió dels textos legals
- **Recomanació** (coherent amb el que has dit per als migrats): en publicar una versió nova, **pop-up amb casella** al següent accés; es pot posposar fins a 3 vegades (paràmetre) i després bloqueja. Els consentiments guarden versió i data.
- **Decisió Jordi:** 

### A13 · Backups: setmanal → diari quan la facturació entri a producció
- **Recomanació**: **diari** (mateix script, canvia el cron). Vas decidir setmanal el 03-09; ho torno a posar sobre la taula pel risc amb remeses.
- **Decisió Jordi:** 

### A14 · Compra de packs i pagaments des de l'app amb Stripe a R1
- **Recomanació**: **cap pantalla a R1** (el Cànic no usa Stripe); l'endpoint `POST /checkout-sessions {upfrontPaymentIds}` queda preparat i la classe individual `PAY_TO_BOOK` es construeix a E8 per als altres clubs.
- **Decisió Jordi:** 

### A15 · Mandats SEPA a la migració
- **Recomanació**: si Playoff exporta referència i data de mandat, **es migren** i les remeses van com a `RCUR`; si no, mandats nous `{club}-{número}-1` amb data d'alta i **cal parlar amb el banc** (`FRST` o comunicació). Pregunta B6 al Josep/Playoff.
- **Decisió Jordi:** 

### A16 · Retenció i supressió (RGPD)
- (a) `rgpd.retentionYearsAfterLeave`: proposo **6 anys** per al perfil ES (Codi de Comerç, art. 30) en lloc dels 5 de la spec · (b) el paquet de dades de l'abonat inclou les observacions dels instructors (dret d'accés) i l'IBAN emmascarat (recom.) · (c) en suprimir, els noms dels gossos es pseudonimitzen (recom.) · (d) supressió d'un compte federat amb Learn: es tramita manualment a Learn (recom.) · (e) l'abonat demana la supressió al club, no des de l'app, a R1 (recom.).
- **Decisió Jordi:** 

### A17 · Exportacions i tauler
- (a) Els instructors **no** exporten llistats a R1 (403) · (b) avís de preinscripcions antigues: el mockup D1 diu «més de **2** dies» i el catàleg 7 → recom. **2** (mana el mockup; és un paràmetre) · (c) entrada «Auditoria» al menú Configuració (recom. sí).
- **Decisió Jordi:** 

### A18 · Processos automàtics
- (a) targeta «Processos automàtics» a D11 amb interruptor, [Simula] i [Executa ara] per a l'ADMIN (recom. sí; el Josep valida els literals) · (b) execució manual síncrona (recom.) · (c) `FINISHED` cada minut amb 15 min de gràcia (recom.) · (d) retencions: esdeveniments 90 dies, execucions 90, Stripe 400, exports 7 (recom.) · (e) també s'executen en clubs `ONBOARDING` (recom. sí, per a demos).
- **Decisió Jordi:** 

### A19 · Semàntica de la «setmana de reserva»
- **Conflicte**: si la setmana de reserva comença **diumenge 20:00**, la «setmana vinent» sempre té 1 plaça reservable i «Properament» és la d'aquí a dues setmanes; el literal de la pantalla 29 («Podràs reservar per a la setmana vinent a partir de diumenge a les 20 h») queda una mica imprecís.
- **Recomanació**: mantenir la semàntica (és la que descriu el Josep) i **ajustar el literal** de 29 a «Podràs reservar aquesta classe a partir de diumenge {dia} a les 20 h» (el back envia la data exacta). Pregunta de literal al Josep (B1).
- **Decisió Jordi:** 

### A20 · Detalls de reserves que afecten l'alumne
- (a) plaça de la llista d'espera: **hold + confirmació en 30 s** (qui toca primer i confirma) en lloc de «primer que confirma» estricte (recom.) · (b) N-04 també al membre del grup familiar que ha fet la reserva (recom. sí) · (c) baixa amb data futura: la data de baixa **inclosa** com a activa; les classes posteriors no són reservables (recom.) · (d) «Properament» només la setmana W2 (recom.).
- **Decisió Jordi:** 

### A21 · Activitats
- (a) tipus = enum de producte + etiqueta lliure (recom.) · (b) un esborrany **no** bloqueja pistes; es bloquegen en publicar, amb diàleg de conflictes que pot anul·lar classes (recom.) · (c) llista d'espera d'activitat amb promoció automàtica FIFO (recom.) · (d) **N-32d** «Activitat modificada pel club» amb SMS quan canvia data/hora/lloc amb inscrits (recom. acceptar: regla del Josep).
- **Decisió Jordi:** 

### A22 · Planificació
- (a) classes en diumenge per a altres clubs → R2 (recom.) · (b) esborrat físic de franges/classes de plantilla permès amb auditoria (recom.) · (c) l'instructor veu D3/D4 en lectura (recom.) · (d) descripció automàtica amb una sola forma «B+C» / «D i sup.» (els mockups en barregen tres) (recom.) · (e) l'alumne mai veu recomptes a la pantalla 10 (el mockup mostra «Teràpia 1 g») (recom.).
- **Decisió Jordi:** 

### A23 · Mòduls autoservei a D11
- **Recomanació**: el club pot activar/desactivar sol `FAQ`, `PUSH`, `LEARN_LINK`; la resta només des de la consola (canvien facturació o dades).
- **Decisió Jordi:** 

### A24 · Migració des de Playoff
- (a) baixes de fa més de 5 anys **no** es migren (RGPD, recom.) · (b) rebuts històrics en sèrie `PLAYOFF` separada (recom.) · (c) 24 mesos de rebuts (B8 amb comptabilitat) · (d) tall un diumenge abans de les 20:00, reserves futures no migrades (B9).
- **Decisió Jordi:** 

### A25 · Learn
- (a) retirar el login clàssic de Learn 3 mesos després de la fase 2 (recom.) · (b) classificacions públiques amb nom del gos + inicials (recom.) · (c) validació manual dels vídeos de challenges (recom.) · (d) cal **crear** a Laravel `GET /api/v1/recommendations` i l'endpoint de course maps (R2; fet verificat: no existeixen).
- **Decisió Jordi:** 

### A26 · Textos legals (esborranys a `05-desenvolupament/legal/`)
- Generats: política de l'AgilityHub ID, plantilla de política del club, autorització d'imatge (casella + aclariment + clàusula), normes del club (plantilla editable com a `ClubPage`). **Recomanació**: revisió per un advocat abans d'E12; tu omples els marcadors `[…]`.
- **Decisió Jordi:** 

### A27 · Accions que només pots fer tu (no són decisions, són bloquejos)
- Muntar l'arrel de `agilityhub-course-builder` (A6) · confirmar dades reals a Supabase · llista d'admins de plataforma (A3) · compte SendGrid i domini verificat (E1) · compte Twilio (E7) · compte Stripe de test (E8) · XSD pain.008 i banc (E8; SEPA **genèric**, sense dependre de CaixaBank — Jordi 05-09) · exports de Playoff + llista d'equip (E2/E12) · DNS del Cànic (E10; «el generarem al deploy» — Jordi 05-09) · reunió amb qui porta la comptabilitat (format d'export, E8).
- **Playoff — resposta a la teva pregunta («què necessites? vols accés de nou?»)**: no cal accés a Playoff ara. Per a E2 només necessito, de cada export (abonats, gossos, tipologies/quotes, rebuts, mandats), **els noms de columna i 3–5 files anonimitzades** (o l'export sencer fora del Dropbox: el `migration:anonymize` en treu les fixtures i l'original no es guarda). Per a E11/E12 caldrà l'export complet en lectura, fet pel Josep el dia del tall, i mai copiat al projecte. La llista d'instructors i administradors (noms + correu) sí que la necessito abans d'E2 per als seeds i les invitacions.
- **Estat:** 

---

## Part B — Preguntes per al Josep (amb l'assumpció aplicada mentrestant)

| # | Pregunta | Assumpció aplicada (recomanada) | Si respon diferent |
|---|---|---|---|
| B1 | ~~Llindar d'anul·lació: 4 h o 2 h?~~ **Resolt per Jordi (05-09): 4 h, editable a D11** (`bookings.lateCancelThresholdMinutes = 240`; catàleg, S08, S10, normes i CLAUDE.md actualitzats). Queda per al Josep només el literal de la pantalla 29 (A19) | 4 h | — |
| B2 | ~~Efectiu: mensual o semestres?~~ **Resolt per Jordi (05-09): mensual**. Per al Josep: el text de semestres de la pantalla 19 (`signup.text.cashConditions`) es canvia o es manté? | `MONTHLY`; text de 19 pendent | canviar el text |
| B3 | ~~Les quotes porten IVA?~~ **Resolt per Jordi (05-09): club sense ànim de lucre, sense IVA** (`taxPercent = 0` al Cànic; el mecanisme queda per a altres clubs) | 0 % | — |
| B4 | ~~Data de cobrament de la remesa?~~ **Resolt per Jordi (05-09): l'últim dia del mes** (`billing.sepa.collectionDayOfMonth = 0` = últim dia; editable en generar). Per al Josep: la remesa de la quota d'octubre es cobra el 31-10 o el 30-09? (assumpció: **el mes que es factura**, és a dir l'últim dia del mateix mes) | últim dia del mes facturat | canviar a mes anterior |
| B5 | El banc exigeix `FRST` per als mandats nous o accepta `RCUR`? | `RCUR` per a tot | `useFrst = true` |
| B6 | Playoff exporta la **referència i data del mandat** SEPA? | si no: mandats nous + avís al banc (A15) | migrar-los |
| B7 | Quines **tipologies** de Playoff segueixen vives (Competició, Abonat Plus, Familiar, Manteniment…) i a quina modalitat nova van? | mapatge provisional de S18 §3; les no mapejades queden «sense modalitat» per revisar | ajustar el YAML de mapatge |
| B8 | Rebuts històrics: n'hi ha prou amb **24 mesos**? Importem els **impagats** pendents? | 24 mesos; impagats com a `FAILED` | canviar `receiptsMonths` |
| B9 | **Tall**: un diumenge abans de les 20:00; les reserves de la setmana del tall no es migren (es tornen a fer al nou sistema) | així | migrar reserves futures (+2 dies) |
| B10 | **Teràpia**: quan passa un abonat a «quota de manteniment» i qui ho decideix? | commutador a D10 (admin), per defecte quota normal | regla automàtica |
| B11 | **Packs**: la caducitat de «12-06 + 5 mesos» és l'11-11 (recom.) o el 12-11? Si el pack caduca, es retorna igualment la sessió d'una anul·lació dins termini? | +5 mesos − 1 dia; sí, es retorna | — |
| B12 | **Inactivitat**: els canvis que fa l'abonat sobre un període ja aprovat s'apliquen sols (dins del dia 25) o cal reaprovar-los? Packs: es pausen? Quota d'inactivitat per a packs? Grup familiar amb un membre inactiu? Baixa prevista en caducar un pack: 30 dies de gràcia i avís? Reactivació: es torna a cobrar l'entrada? Denegar una baixa: s'avisa? | s'apliquen sols amb avís a l'admin; packs no es pausen i no paguen quota; tarifa familiar sense recàlcul automàtic; 30 dies i avís «si no renoves…»; sense entrada automàtica; sí, s'avisa | — |
| B13 | **Alta**: un abonat actiu que afegeix un gos paga només l'entrada? Una readmissió torna a pagar entrada? Es pot validar una alta amb «Compte no informat»? Edat mínima / menors? Xip obligatori? | només entrada; sí (l'admin pot posar 0); sí (avís, no bloqueig); cap edat mínima (tutor per a menors, pendent legal); xip obligatori | — |
| B14 | **«Notes als instructors» a l'alta**: el model ho demana però el mockup 17 no ho mostra | camp opcional a 17 | treure'l de l'alta |
| B15 | **Entrenaments**: el límit de 3/setmana compta per **data de sessió** (dissabte es pot reservar dilluns encara que aquesta setmana sigui 3/3)? Un slot ja començat no es pot reservar? La llicència no es valida (només s'informa)? | sí; estricte; no es valida | — |
| B16 | **Assistència**: el % inclou les anul·lades tard al denominador? Es pot editar la llista fins a l'endemà (admin sense límit)? «Ha avisat» després d'acabar la classe només és un registre? Clic a D14 obre la fitxa d'alumne (D13) | sí; sí; sí; D13 | — |
| B17 | **Revisió de risc**: només a les 7:30 (no immediata quan un alumne anul·la)? L'avís «possible anul·lació» s'envia un sol cop per reserva? Recordatori de remesa el dia 22? | sí; sí; sí | — |
| B18 | **Setmanes**: la validació queda **bloquejada** si hi ha incoherències? [Elimina] i [Anul·la] deixen la classe igual («anul·lada», motiu diferent)? | sí; sí | — |
| B19 | **Cens**: si un gos canvia de nivell, les reserves futures en classes que ja no l'admeten es mantenen (amb avís a l'admin)? Un gos amb reserves futures no es pot transferir ni donar de baixa fins que s'anul·len? En donar de baixa l'abonat, els gossos es donen de baixa sols i en reactivar-lo es reactiven un a un? El bloqueig de reserves també s'aplica quan un membre del grup reserva per al gos del bloquejat? Nom i cognoms a 28 només lectura? Canvis del grup familiar → recàlcul manual de la tarifa? | sí; sí; sí; sí; sí; manual | — |
| B20 | **Activitats**: l'abonat pot anul·lar la inscripció fins al tancament d'inscripcions o fins a l'inici? Publicar una activitat pot anul·lar classes amb inscrits (amb avís)? Activitats d'un sol dia a R1? | tancament; sí; sí | — |
| B21 | **Comunicats**: enviament immediat i només a abonats? Fila «Comunicats del club» a la matriu de 12 (per poder tornar-hi després de «deixar de rebre»)? Selector d'idioma actiu a 12? | sí; sí; sí | — |
| B22 | **FAQ**: respostes definitives de les 7 preguntes provisionals; **normes del club**: revisar la plantilla `legal/NORMES_CLUB_PLANTILLA.md`; textos de les modalitats (D8 curt vs 17 llarg) | provisionals marcades; plantilla; text de 17 | — |
| B23 | **Festius**: llista de dates amb etiqueta (no importació de calendari) | sí | — |
| B24 | **Pantalles sense mockup** (es fan amb el design system i es validen a staging): fitxa del gos al backoffice, calaix «Edita» de D10, «Nou abonat», confirmació d'alta enviada, «Inactivitats i baixes», remeses, 12/rebuts, «Tanca la sessió», detall d'activitat i diàlegs, cercador d'alumnes de l'instructor, «Entrenaments» d'escriptori, auditoria, exportacions, D18 recorreguts, D19 consola, `apps/id`, «Completa el teu perfil», pàgines del club a 30 | literals proposats a cada spec §2 | — |
| B25 | **Literals proposats** que no són als mockups (llista a S03/S07/S08/S09/S10/S11/S13/S14 §13): «Sense sessions», «Completa», «Desbloqueja les reserves», «Reserves bloquejades», «Cap classe aquest dia», «llista passada», «avís ja enviat», «Readmissió», «pendent de validació», «Sol·licitud enviada», «INSCRIU-M'HI», «BLOQUEJA LA PISTA», text del límit d'entrenaments… | s'implementen així i es revisen a staging | — |
| B26 | **Pendents antics**: consells «fixats pels instructors» (13), WhatsApp per als no presentats (descartat). Gamificació i FlowAgility (27, R2): **OK Jordi 05-09** | 13 sense consells; 21 app + correu | — |
| B27 | Dispositiu objectiu per a l'AR (A9) | iPhone recent + 1 Android | — |

---

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

---

## Part D — Ja resolt (per a constància)

| Data | Decisió |
|---|---|
| 03-09 | AgilityHub ID al core; course-core a R1; i18n total + perfil de país; tots els mòduls activables; regles generalitzades; pagaments SEPA/Stripe/manual; `agilityhubLevel`; repos `agilityhub-core-*`; noms Learn/Clubs/ID; etapes sense dates; specs per vertical + pantalles |
| 03-09 | Numeració única de notificacions (N-39…N-53); `Dog.status PENDING·ACTIVE·INACTIVE`; N-08b des de `ClassSessionUpdated`; bloquejos d'activitat síncrons; `IMPERSONATION_DENIED`; 422 per a precondicions de l'abonat |
| 05-09 (Jordi) | Onboarding «Completa el teu perfil» per als migrats; **SendGrid** com a proveïdor d'email (ADR-005); normes del club com a pàgina editable (`ClubPage`); esborranys legals generats |
| 05-09 (codi) | Learn: JWT + bcrypt 12, sense login social, alta per `POST /users`, API a `app.agilitydoghub.com`, 6 idiomes; escala `EASY·MEDIUM·HARD`; web-planner: `PlannerStore`, Supabase, Unity + AprilTag, format Smarter verificat (fixtures) |
