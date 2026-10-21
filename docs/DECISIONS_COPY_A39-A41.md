# Decisions pendents de copy — A39 · A40 · A41 (per a Jordi)

**v1.1 · 21-10-2026** (respostes del Jordi al xat, E153 — tancat) · v1.0 · 21-10-2026 · Consolida, en una sola passada, tots els literals i regles de copy que les tasques web tenen «pendents de Jordi» (E144): els literals proposats per E10-W01, E9-W03, E9-W04, E11-W01 i E11-W02 (**A39**), les regles de copy que E11-W05 espera (**A40**) i els noms del club plantilla (S17 §13 #5) i els sis textos d'`auth.refreshReuseGraceSeconds` (**A41**). Fonts: `roadmap/tasks/*.md` (secció «Questions / catalog proposals» i «Organizer verification»), `roadmap/MESSAGES.md` dels dos repos, els locals `packages/i18n/src/locales/{ca,es,en}/*.json` del web `main` `08e4702d` i els seeds de l'api `main` `8d73ad2a`. Les cadenes són **literals** (copiades dels fitxers); on només hi havia proposta en ca, l'es/en és meu i porta «(proposta nova)». La regla dels botons en castellà (219 textos, infinitiu) ja està decidida (E137/E144) i no hi és.

**Com respondre**: a cada grup hi ha una línia `Decisió Jordi:`. Un `OK` aprova tot el grup tal com és a la columna «ca · es · en» (les files on «recomanació» diu «OK» no cal tocar-les); per canviar una fila, escriu-hi l'id i el text nou (`A39.24: «Club pilot»`). A la columna «recomanació» només hi ha text quan proposo un canvi o un matís. Quan ho responguis, l'organitzador omple l'«Approved copy» d'E11-W05 (passos 3, 4 i 6), sincronitza `CATALEG_ERRORS.md` (A40.4) i crea la tasca de seed del club plantilla (A41.1).

---

## A39 · Literals proposats per les tasques web (agrupats per pantalla)

Cap d'aquests textos té mockup: l'executor els va inventar i els va marcar «for Jordi». **Recomanació global: OK**, amb les excepcions marcades a la columna «recomanació» (A39.1, A39.24, A39.44, A39.53, A39.73, A39.94, A39.133, A39.158, A39.160, A39.210–211).

### A39.A · Consola de clubs (D19; E10-W01 #6, E10-W03) — `console.json`, `shell:nav.platform`, `id:products.console*`, `admin-audit:actions.*`

Cap mockup: S17 només dona els noms de pantalla, pestanyes i passos. Tot el fitxer és copy inventat per l'executor; aquí hi ha els textos amb càrrega de producte (estats, mòduls, checklist, conseqüències, errors); la resta s'aprova en bloc a l'última fila.

| id | pantalla/clau | ca | es | en | recomanació |
|---|---|---|---|---|---|
| A39.1 | `console:status.ONBOARDING` | En posada en marxa | En puesta en marcha | Onboarding | OK (alternativa: «En configuració» / «En configuración» / «Being set up») |
| A39.2 | `console:status.ACTIVE` | Actiu | Activo | Active | OK |
| A39.3 | `console:status.SUSPENDED` | Suspès | Suspendido | Suspended | OK |
| A39.4 | `console:statusTab.meaning.ONBOARDING` | El club ja funciona i els administradors veuen un bàner de posada en marxa. | El club ya funciona y los administradores ven un banner de puesta en marcha. | The club already works and its administrators see an onboarding banner. | OK |
| A39.5 | `console:statusTab.meaning.ACTIVE` | El club opera amb normalitat. | El club opera con normalidad. | The club operates normally. | OK |
| A39.6 | `console:statusTab.meaning.SUSPENDED` | Els accessos al club es rebutgen i els processos no s'executen. | Los accesos al club se rechazan y los procesos no se ejecutan. | Access to the club is refused and processes do not run. | OK |
| A39.7 | `console:statusTab.suspendEffects` | En suspendre: es rebutgen els accessos al club, els processos automàtics se salten i els cobraments en línia queden en cua fins que es reactivi. | Al suspender: se rechazan los accesos al club, los procesos automáticos se saltan y los cobros en línea quedan en cola hasta que se reactive. | On suspension: access to the club is refused, automatic processes are skipped and online payments queue until it is reactivated. | OK |
| A39.8 | `console:modules.ACTIVITIES` label — help | Activitats — Activitats amb inscripció (cursets, sortides) i el bloqueig de pistes que fan servir. | Actividades — Actividades con inscripción (cursillos, salidas) y el bloqueo de pistas que usan. | Activities — Activities with registration (courses, outings) and the rings they block. | OK |
| A39.9 | `console:modules.BILLING` label — help | Facturació — Rebuts, remeses, cobraments i el mètode de pagament a l'alta. | Facturación — Recibos, remesas, cobros y el método de pago en el alta. | Billing — Receipts, remittances, collections and the payment method at signup. | OK |
| A39.10 | `console:modules.COURSES` label — help | Recorreguts — Biblioteca de recorreguts, geometria de les pistes i sessions de muntatge. | Recorridos — Biblioteca de recorridos, geometría de las pistas y sesiones de montaje. | Courses — Course library, ring geometry and build sessions. | OK |
| A39.11 | `console:modules.FAMILY_GROUP` label — help | Grup familiar — Gossos de diversos abonats agrupats amb un pagador únic. | Grupo familiar — Perros de varios abonados agrupados con un pagador único. | Family group — Dogs of several members grouped under one payer. | OK |
| A39.12 | `console:modules.FAQ` label — help | Preguntes freqüents — La pàgina de preguntes freqüents de l'app. | Preguntas frecuentes — La página de preguntas frecuentes de la app. | Frequently asked questions — The app's frequently asked questions page. | OK |
| A39.13 | `console:modules.FREE_TRAINING` label — help | Entrenaments lliures — Reserva de pista per entrenar sense classe, segons el nivell del gos. | Entrenamientos libres — Reserva de pista para entrenar sin clase, según el nivel del perro. | Free training — Ring booking to train without a class, by the dog's level. | OK |
| A39.14 | `console:modules.INACTIVITY` label — help | Inactivitat — Períodes d'inactivitat que demanen els abonats i la seva quota. | Inactividad — Periodos de inactividad que piden los abonados y su cuota. | Inactivity — Inactivity periods members ask for, and their fee. | OK |
| A39.15 | `console:modules.LEARN_LINK` label — help | Enllaç a Learn — L'accés a Learn des del menú de l'app. | Enlace a Learn — El acceso a Learn desde el menú de la app. | Learn link — The access to Learn from the app's menu. | OK |
| A39.16 | `console:modules.PACKS` label — help | Packs — Modalitats de packs de sessions amb el seu saldo. | Packs — Modalidades de packs de sesiones con su saldo. | Packs — Session pack plans with their balance. | OK |
| A39.17 | `console:modules.PUSH` label — help | Notificacions push — Notificacions web al dispositiu de l'abonat. | Notificaciones push — Notificaciones web en el dispositivo del abonado. | Push notifications — Web notifications on the member's device. | OK |
| A39.18 | `console:modules.SINGLE_CLASS` label — help | Classes soltes — Classes soltes que es paguen per reserva o per assistència. | Clases sueltas — Clases sueltas que se pagan por reserva o por asistencia. | Single classes — Single classes paid per booking or per attendance. | OK |
| A39.19 | `console:modules.SMS` label — help | SMS — El canal SMS dels avisos i comunicats. | SMS — El canal SMS de los avisos y comunicados. | SMS — The SMS channel of notices and announcements. | OK |
| A39.20 | `console:modules.SOCIAL_LEAGUE` label — help | Lliga social — Resultats de la lliga social del club. | Liga social — Resultados de la liga social del club. | Social league — Results of the club's social league. | OK |
| A39.21 | `console:modules.STATS` label — help | Estadístiques — Estadístiques de l'abonat a l'app. | Estadísticas — Estadísticas del abonado en la app. | Statistics — The member's statistics in the app. | OK |
| A39.22 | `console:modules.TASKS` label — help | Tasques i seguiment — Tasques, notes dels instructors, observacions i el seguiment d'alumnes. | Tareas y seguimiento — Tareas, notas de los instructores, observaciones y el seguimiento de alumnos. | Tasks and follow-up — Tasks, instructors' notes, observations and student follow-up. | OK |
| A39.23 | `console:modules.WAITLIST` label — help | Llista d'espera — Llista d'espera de les classes completes. | Lista de espera — Lista de espera de las clases completas. | Waiting list — Waiting list of full classes. | OK |
| A39.24 | `console:presets.CANIC` | Com el club pilot | Como el club piloto | Like the pilot club | OK (S17 diu «Com el Cànic»; la regla 2 d'AGENTS prohibeix el literal de club) |
| A39.25 | `console:presets.COMPLET` | Complet | Completo | Full | OK |
| A39.26 | `console:presets.MINIM` | Mínim | Mínimo | Minimal | OK |
| A39.27 | `console:wizard.presetHelp.CANIC` | Els mòduls del club pilot: tots llevat de classes soltes, estadístiques i lliga social. | Los módulos del club piloto: todos salvo clases sueltas, estadísticas y liga social. | The pilot club's modules: all but single classes, statistics and social league. | OK |
| A39.28 | `console:wizard.presetHelp.COMPLET` | Tots els mòduls llevat d'estadístiques i lliga social. | Todos los módulos salvo estadísticas y liga social. | Every module but statistics and social league. | OK |
| A39.29 | `console:wizard.presetHelp.MINIM` | Només llista d'espera, preguntes freqüents i notificacions push. | Solo lista de espera, preguntas frecuentes y notificaciones push. | Only waiting list, frequently asked questions and push notifications. | OK |
| A39.30 | `console:checklist.items.branding` | Marca: logo i colors propis | Marca: logo y colores propios | Brand: own logo and colours | OK |
| A39.31 | `console:checklist.items.dns` | Dominis verificats | Dominios verificados | Verified domains | OK |
| A39.32 | `console:checklist.items.faq` | Preguntes freqüents | Preguntas frecuentes | Frequently asked questions | OK |
| A39.33 | `console:checklist.items.firstAdmin` | Primer administrador amb accés | Primer administrador con acceso | First administrator with access | OK |
| A39.34 | `console:checklist.items.legal` | Política de privacitat | Política de privacidad | Privacy policy | OK |
| A39.35 | `console:checklist.items.sepa` | Proveïdor de pagament configurat | Proveedor de pago configurado | Payment provider configured | OK |
| A39.36 | `console:checklist.items.templates` | Plantilles de comunicat | Plantillas de comunicado | Message templates | OK |
| A39.37 | `console:checklist.missing` | falten: {items} | faltan: {items} | missing: {items} | OK |
| A39.38 | `console:localization.consequences.countryProfile` | El perfil de país canvia les validacions de documents, codis postals i fiscalitat dels formularis del club. | El perfil de país cambia las validaciones de documentos, códigos postales y fiscalidad de los formularios del club. | The country profile changes how the club's forms validate documents, postal codes and taxes. | OK |
| A39.39 | `console:localization.consequences.currency` | La moneda passa de {from} a {to}: els preus vigents no es converteixen i caldrà revisar les modalitats. | La moneda pasa de {from} a {to}: los precios vigentes no se convierten y habrá que revisar las modalidades. | The currency changes from {from} to {to}: current prices are not converted and the plans will need a review. | OK |
| A39.40 | `console:localization.consequences.defaultLocale` | Els textos que no existeixin en un idioma es mostraran en el nou idioma predeterminat. | Los textos que no existan en un idioma se mostrarán en el nuevo idioma predeterminado. | Texts missing in a language will be shown in the new default language. | OK |
| A39.41 | `console:localization.consequences.localesAdded` | Els textos del club (pàgines, plantilles, preguntes) s'hauran de completar en els idiomes nous. | Los textos del club (páginas, plantillas, preguntas) se tendrán que completar en los idiomas nuevos. | The club's texts (pages, templates, questions) will need completing in the new languages. | OK |
| A39.42 | `console:localization.consequences.localesRemoved` | Els abonats ja no podran triar {locales}; els textos en aquest idioma es conserven però no es mostren. | Los abonados ya no podrán elegir {locales}; los textos en ese idioma se conservan pero no se muestran. | Members will no longer be able to choose {locales}; texts in that language are kept but not shown. | OK |
| A39.43 | `console:localization.consequences.timeZone` | El fus horari passa de {from} a {to}: tots els horaris del club es llegiran en el fus nou. | El huso horario pasa de {from} a {to}: todos los horarios del club se leerán en el huso nuevo. | The time zone changes from {from} to {to}: every schedule of the club will be read in the new zone. | OK |
| A39.44 | `console:errors.catalogNotEmpty` | {part}: el club ja en té. Només es copien les parts buides. | {part}: el club ya tiene. Solo se copian las partes vacías. | {part}: the club already has some. Only empty parts are copied. | es: «el club ya tiene.» queda coix → «{part}: el club ya tiene contenido. Solo se copian las partes vacías.» |
| A39.45 | `console:errors.domainState` | No es pot fer amb un domini en estat «{status}». | No se puede hacer con un dominio en estado «{status}». | This cannot be done with a domain that is «{status}». | OK |
| A39.46 | `console:errors.primaryDomain` | És el domini principal de la seva app: abans fes principal un altre domini verificat. | Es el dominio principal de su app: antes haz principal otro dominio verificado. | It is the primary domain of its app: first make another verified domain primary. | OK |
| A39.47 | `console:errors.fileTooLarge` | El fitxer és massa gran: com a màxim {maxSizeMb} MB. | El archivo es demasiado grande: como máximo {maxSizeMb} MB. | The file is too large: {maxSizeMb} MB at most. | OK |
| A39.48 | `console:errors.generic` | No s'ha pogut completar l'acció. Torna-ho a provar. | No se ha podido completar la acción. Vuelve a intentarlo. | The action could not be completed. Try again. | OK |
| A39.49 | `console:errors.moduleDependency` | Per activar {module} cal activar {requires}. | Para activar {module} hay que activar {requires}. | To turn on {module}, {requires} must be on. | OK |
| A39.50 | `console:errors.providerInUse` | {count, plural, one {Hi ha # abonat que paga amb aquest proveïdor.} other {Hi ha # abonats que paguen amb aquest proveïdor.}} | {count, plural, one {Hay # abonado que paga con este proveedor.} other {Hay # abonados que pagan con este proveedor.}} | {count, plural, one {# member pays with this provider.} other {# members pay with this provider.}} | OK |
| A39.51 | `console:errors.stale` | Algú ha canviat el club mentrestant. Hem carregat la versió actual amb els teus canvis a sobre: revisa-la i torna a desar. | Alguien ha cambiado el club mientras tanto. Hemos cargado la versión actual con tus cambios encima: revísala y vuelve a guardar. | Someone changed the club meanwhile. We loaded the current version with your changes on top: review it and save again. | OK |
| A39.52 | `console:wizard.description` | Vuit passos per crear un club. Es crea en posada en marxa i després es completa la checklist d'onboarding. | Ocho pasos para crear un club. Se crea en puesta en marcha y después se completa la checklist de onboarding. | Eight steps to create a club. It is created in onboarding, then the onboarding checklist is completed. | OK |
| A39.53 | `console:domains.adminHostsNote` | Un domini del backoffice verificat ja té certificat, però el backoffice no s'hi serveix fins que operacions l'afegeix a la llista de hosts del backoffice i recarrega el servidor. | Un dominio del backoffice verificado ya tiene certificado, pero el backoffice no se sirve en él hasta que operaciones lo añade a la lista de hosts del backoffice y recarga el servidor. | A verified back-office domain gets its certificate at once, but the back office is served on it only once operations add it to the back-office host list and reload the server. | Canviar: INC-64 (E11-T09) ha tret el pas manual de hosts → esborrar la frase (o «Un domini del backoffice verificat se serveix de seguida.») |
| A39.54 | `shell:nav.platform` | Plataforma | Plataforma | Platform | OK |
| A39.55 | `id:products.consoleTitle` | Consola de clubs | Consola de clubes | Club console | OK |
| A39.56 | `id:products.consoleDescription` | Crea i governa els clubs de la plataforma. Només per a l'equip AgilityHub. | Crea y gestiona los clubes de la plataforma. Solo para el equipo AgilityHub. | Create and govern the platform's clubs. For the AgilityHub team only. | OK |
| A39.57 | `id:products.openConsole` | OBRE LA CONSOLA | ABRIR LA CONSOLA | OPEN THE CONSOLE | OK |
| A39.58 | `admin-audit:actions.CLUB_CREATED` | Club creat | Club creado | Club created | OK |
| A39.59 | `admin-audit:actions.CLUB_UPDATED` | Dades del club modificades | Datos del club modificados | Club details changed | OK |
| A39.60 | `admin-audit:actions.CLUB_STATUS_CHANGED` | Estat del club modificat | Estado del club modificado | Club status changed | OK |
| A39.61 | `admin-audit:actions.CLUB_MODULES_CHANGED` | Mòduls del club modificats | Módulos del club modificados | Club modules changed | OK |
| A39.62 | `admin-audit:actions.CLUB_DOMAIN_CHANGED` | Domini del club modificat | Dominio del club modificado | Club domain changed | OK |
| A39.63 | `admin-audit:actions.CLUB_PAYMENT_PROVIDER_CHANGED` | Proveïdor de pagament del club modificat | Proveedor de pago del club modificado | Club payment provider changed | OK |
| A39.64 | `admin-audit:actions.CLUB_ADMIN_INVITED` | Administrador del club convidat | Administrador del club invitado | Club administrator invited | OK |
| A39.65 | `admin-audit:actions.PLATFORM_ROLES_CHANGED` | Rols de plataforma modificats | Roles de plataforma modificados | Platform roles changed | OK |
| A39.66 | `admin-audit:actions.PUBLIC_API_KEY_ROTATED` | Clau d'API pública renovada | Clave de API pública renovada | Public API key rotated | OK |
| A39.67 | `admin-audit:actions.SUPPORT_ACCESS_STARTED` | Accés de suport iniciat | Acceso de soporte iniciado | Support access started | OK |
| A39.68 | `admin-audit:actions.SUPPORT_ACCESS_ENDED` | Accés de suport acabat | Acceso de soporte terminado | Support access ended | OK |
| A39.69 | la resta de `console.json` (≈240 claus: títols i descripcions de pestanya, camps, columnes, botons, ajudes de formulari, previsualització del tema, dominis, clau pública, accés de suport, equip) | — | — | — | OK en bloc (són etiquetes funcionals; fitxer: `packages/i18n/src/locales/ca/console.json`) |

**Decisió Jordi:** OK en bloc amb les 11 correccions recomanades (21-10, E153).


### A39.B · Recorreguts al backoffice (D16 geometria, D18; E9-W03 #4) — `admin-courses.json`, `enums:agilityhubLevel`, `enums:obstacleType`, `enums:setupStatus`

Sense mockup per a D18 i la geometria de D16: tot el que no cita S16 §2/§10 és de l'executor. Per revisar també amb el Josep (vocabulari d'agility).

| id | pantalla/clau | ca | es | en | recomanació |
|---|---|---|---|---|---|
| A39.70 | `admin-courses:detail.metadata` | Dades del recorregut | Datos del recorrido | Course details | OK |
| A39.71 | `admin-courses:geometry.borderClearance` | Marge a la tanca (m) | Margen a la valla (m) | Fence margin (m) | OK |
| A39.72 | `admin-courses:geometry.zone.margin` | Marge d'avís de {label} (m) | Margen de aviso de {label} (m) | Warning margin of {label} (m) | OK |
| A39.73 | `admin-courses:fields.agilityhubLevel` | Nivell orientatiu | Nivel orientativo | Guide level | OK; en: unificar «Guide level» (aquí) i «Orientative level» (`courses:register.level`) → «Guide level» |
| A39.74 | `enums:agilityhubLevel.EASY` | Fàcil | Fácil | Easy | OK |
| A39.75 | `enums:agilityhubLevel.MEDIUM` | Mitjà | Medio | Medium | OK |
| A39.76 | `enums:agilityhubLevel.HARD` | Difícil | Difícil | Hard | OK |
| A39.77 | `enums:obstacleType.Jump` | Salt | Salto | Jump | OK |
| A39.78 | `enums:obstacleType.DoubleJump` | Salt doble | Salto doble | Double jump | OK |
| A39.79 | `enums:obstacleType.Tunnel3m` | Túnel de 3 m | Túnel de 3 m | 3 m tunnel | OK |
| A39.80 | `enums:obstacleType.Tunnel4m` | Túnel de 4 m | Túnel de 4 m | 4 m tunnel | OK |
| A39.81 | `enums:obstacleType.Tunnel5m` | Túnel de 5 m | Túnel de 5 m | 5 m tunnel | OK |
| A39.82 | `enums:obstacleType.Tunnel6m` | Túnel de 6 m | Túnel de 6 m | 6 m tunnel | OK |
| A39.83 | `enums:obstacleType.DogWalk` | Passarel·la | Pasarela | Dog walk | OK |
| A39.84 | `enums:obstacleType.AFrame` | Empalissada | Empalizada | A-frame | OK |
| A39.85 | `enums:obstacleType.Seesaw` | Balancí | Balancín | Seesaw | OK |
| A39.86 | `enums:obstacleType.Weave` | Eslàlom | Eslalon | Weave poles | OK |
| A39.87 | `enums:obstacleType.LongJump` | Salt de llargada | Salto de longitud | Long jump | OK |
| A39.88 | `enums:obstacleType.Wall` | Mur | Muro | Wall | OK |
| A39.89 | `enums:obstacleType.Tire` | Roda | Rueda | Tyre | OK |
| A39.90 | `enums:obstacleType.Unknown` | Desconegut | Desconocido | Unknown | OK |
| A39.91 | `admin-courses:warnings.obstacle` | L'obstacle {numbers} ({type}) | El obstáculo {numbers} ({type}) | Obstacle {numbers} ({type}) | OK |
| A39.92 | `admin-courses:warnings.outsideRing` | {obstacle} queda fora de la pista. | {obstacle} queda fuera de la pista. | {obstacle} is outside the ring. | OK |
| A39.93 | `admin-courses:warnings.borderClearance` | {obstacle} queda massa a prop de la tanca. | {obstacle} queda demasiado cerca de la valla. | {obstacle} is too close to the fence. | OK |
| A39.94 | `admin-courses:warnings.courseNoGo` | {obstacle} toca una zona no-go del recorregut. | {obstacle} toca una zona prohibida del recorrido. | {obstacle} touches a no-go zone of the course. | es «zona prohibida» ≠ «no-go» d'S16 i d'`enums:courseWarningRule` es → «{obstacle} toca una zona no-go del recorrido.» |
| A39.95 | `admin-courses:warnings.ringNoGo` | {obstacle} toca la zona «{zone}». | {obstacle} toca la zona «{zone}». | {obstacle} touches the “{zone}” zone. | OK |
| A39.96 | `admin-courses:warnings.doorBlocked` | {obstacle} tapa la porta «{door}». | {obstacle} tapa la puerta «{door}». | {obstacle} blocks the “{door}” door. | OK |
| A39.97 | `admin-courses:warnings.doorClearance` | {obstacle} queda massa a prop de la porta «{door}». | {obstacle} queda demasiado cerca de la puerta «{door}». | {obstacle} is too close to the “{door}” door. | OK |
| A39.98 | `admin-courses:warnings.courseLarger` | El recorregut no hi cap, ni girat. | El recorrido no cabe, ni girado. | The course does not fit, not even turned. | OK |
| A39.99 | `admin-courses:warnings.inventoryNotConfigured` | La pista no té inventari: no es comprova el material. | La pista no tiene inventario: no se comprueba el material. | The ring has no inventory: the equipment is not checked. | OK |
| A39.100 | `admin-courses:warnings.inventoryUnknown` | {count, plural, one {# obstacle és} other {# obstacles són}} d'un tipus desconegut. | {count, plural, one {# obstáculo es} other {# obstáculos son}} de un tipo desconocido. | {count, plural, one {# obstacle is} other {# obstacles are}} of an unknown type. | OK |
| A39.101 | `admin-courses:warnings.inventoryShort` | Falta material: {count} × {type}. | Falta material: {count} × {type}. | Missing equipment: {count} × {type}. | OK |
| A39.102 | `admin-courses:editor.noWarnings` | Cap avís: el recorregut hi cap bé. | Ningún aviso: el recorrido cabe bien. | No warnings: the course fits. | OK |
| A39.103 | `admin-courses:editor.criticalAdmin` | {count, plural, one {Hi ha # avís crític.} other {Hi ha # avisos crítics.}} Pots desar-la igualment, si cal. | {count, plural, one {Hay # aviso crítico.} other {Hay # avisos críticos.}} Puedes guardarla igualmente, si hace falta. | {count, plural, one {There is # critical warning.} other {There are # critical warnings.}} You can still save it if needed. | OK |
| A39.104 | `admin-courses:editor.criticalInstructor` | {count, plural, one {Hi ha # avís crític.} other {Hi ha # avisos crítics.}} Corregeix-los abans de desar-la. | {count, plural, one {Hay # aviso crítico.} other {Hay # avisos críticos.}} Corrígelos antes de guardarla. | {count, plural, one {There is # critical warning.} other {There are # critical warnings.}} Fix them before saving it. | OK |
| A39.105 | `admin-courses:editor.forceQuestion` | Vols desar-la igualment? Quedarà registrat que l'has desada amb avisos crítics. | ¿Quieres guardarla igualmente? Quedará registrado que la has guardado con avisos críticos. | Save it anyway? It will be recorded that you saved it with critical warnings. | OK |
| A39.106 | `admin-courses:editor.forceAdminOnly` | Només un administrador pot desar una col·locació amb avisos crítics. | Solo un administrador puede guardar una colocación con avisos críticos. | Only an administrator can save a placement with critical warnings. | OK |
| A39.107 | `admin-courses:editor.blockedBy` | Avisos crítics | Avisos críticos | Critical warnings | OK |
| A39.108 | `admin-courses:editor.stale` | Algú ha desat aquesta col·locació mentrestant. Hem mantingut la teva posició: desa de nou per substituir-la. | Alguien ha guardado esta colocación mientras tanto. Hemos mantenido tu posición: guarda de nuevo para sustituirla. | Someone saved this placement in the meantime. Your position was kept: save again to replace theirs. | OK |
| A39.109 | `admin-courses:detail.stale` | Algú ha canviat el recorregut mentrestant. L'hem tornat a carregar amb els teus canvis a sobre: revisa'ls i desa de nou. | Alguien ha cambiado el recorrido mientras tanto. Lo hemos vuelto a cargar con tus cambios encima: revísalos y guarda de nuevo. | Someone changed the course in the meantime. It was loaded again with your changes on top: check them and save again. | OK |
| A39.110 | `admin-courses:detail.storedSheet` | Full desat | Hoja guardada | Stored sheet | OK |
| A39.111 | `admin-courses:detail.sheetStale` | La geometria de la pista ha canviat: revisa la col·locació abans de treure'n el full de muntatge. | La geometría de la pista ha cambiado: revisa la colocación antes de sacar la hoja de montaje. | The ring's geometry changed: review the placement before printing its build sheet. | OK |
| A39.112 | `admin-courses:stale` | Geometria canviada: revisa la col·locació | Geometría cambiada: revisa la colocación | Geometry changed: review the placement | OK |
| A39.113 | `admin-courses:setups.replaced` | Substituït | Sustituido | Replaced | OK |
| A39.114 | `admin-courses:setup.instructorRefused` | En aquest club només l'administració pot registrar què hi ha muntat. | En este club solo la administración puede registrar qué hay montado. | In this club only the administration can register what is set up. | OK |
| A39.115 | `enums:setupStatus.ACTIVE` | Muntat | Montado | Set up | OK |
| A39.116 | `enums:setupStatus.EXPIRED` | Caducat | Caducado | Expired | OK |
| A39.117 | `enums:setupStatus.DISMANTLED` | Desmuntat | Desmontado | Taken down | OK |
| A39.118 | la resta d'`admin-courses.json` (≈270 claus: llista D18, fitxa, editor de col·locació, geometria de D16, portes, marcadors, fulls PDF) | — | — | — | OK en bloc |

**Decisió Jordi:** OK en bloc amb les 11 correccions recomanades (21-10, E153).


### A39.C · Recorreguts a l'app dels abonats (08, 24, visor, registre i sessió de muntatge; E9-W04 #5) — `courses.json`, `enums:buildObstacleStatus`, `enums:buildSessionStatus`, `enums:setupKind`, `enums:courseSource`

| id | pantalla/clau | ca | es | en | recomanació |
|---|---|---|---|---|---|
| A39.119 | `courses:viewer.none` | Sense recorregut registrat | Sin recorrido registrado | No course registered | OK |
| A39.120 | `courses:viewer.noneHelp` | Quan es registri què hi ha muntat a aquesta pista, ho veuràs aquí. | Cuando se registre qué hay montado en esta pista, lo verás aquí. | When what is set up on this ring is registered, you will see it here. | OK |
| A39.121 | `courses:viewer.empty` | La pista és buida: no hi ha res muntat. | La pista está vacía: no hay nada montado. | The ring is empty: nothing is set up. | OK |
| A39.122 | `courses:viewer.register` | Registra què hi ha muntat | Registra qué hay montado | Register what is set up | OK |
| A39.123 | `courses:viewer.dismantleConfirm` | El muntatge de {ring} deixarà de sortir als quadres i a la reserva d'entrenaments. | El montaje de {ring} dejará de salir en los cuadros y en la reserva de entrenamientos. | The setup on {ring} will no longer show on the day boards and in training bookings. | OK |
| A39.124 | `courses:viewer.join.label` | Codi d'una sessió de muntatge | Código de una sesión de montaje | Code of a build session | OK |
| A39.125 | `courses:viewer.join.submit` | Uneix-t'hi | Únete | Join | OK |
| A39.126 | `courses:viewer.join.notFound` | No hi ha cap sessió oberta amb aquest codi. | No hay ninguna sesión abierta con este código. | No open session has this code. | OK |
| A39.127 | `courses:register.kind` | Què hi ha muntat? | ¿Qué hay montado? | What is set up? | OK |
| A39.128 | `courses:register.emptyHelp` | La pista quedarà marcada com a buida. | La pista quedará marcada como vacía. | The ring will be marked as empty. | OK |
| A39.129 | `courses:register.source` | D'on surt? | ¿De dónde sale? | Where does it come from? | OK |
| A39.130 | `courses:register.untilHelp` | Si ho deixes en blanc, es retira sol al cap dels dies que té configurats el club. | Si lo dejas en blanco, se retira solo al cabo de los días que tiene configurados el club. | Left blank, it is removed automatically after the days the club has configured. | OK |
| A39.131 | `courses:register.replaces` | Substituirà el muntatge actual ({kind}, de {day}). | Sustituirá el montaje actual ({kind}, del {day}). | It will replace the current setup ({kind}, from {day}). | OK |
| A39.132 | `courses:register.instructorRefused` | En aquest club només l'administració pot registrar què hi ha muntat. | En este club solo la administración puede registrar qué hay montado. | In this club only the administration can register what is set up. | OK |
| A39.133 | `courses:register.level` | Nivell orientatiu | Nivel orientativo | Orientative level | en → «Guide level» (vegeu A39 admin-courses) |
| A39.134 | `courses:session.code` | Codi per unir-s'hi: | Código para unirse: | Code to join: | OK |
| A39.135 | `courses:session.finishedNoSetup` | Sessió acabada. En aquest club, l'administració registra què hi ha muntat. | Sesión terminada. En este club, la administración registra qué hay montado. | Session finished. In this club, the administration registers what is set up. | OK |
| A39.136 | `courses:session.tap` | {number}, {type}, {status}. Toca per passar a {next}. | {number}, {type}, {status}. Toca para pasar a {next}. | {number}, {type}, {status}. Tap to set {next}. | OK |
| A39.137 | `courses:builtAgo` | muntat {days, plural, =0 {avui} =1 {ahir} other {fa # dies}} | montado {days, plural, =0 {hoy} =1 {ayer} other {hace # días}} | set up {days, plural, =0 {today} =1 {yesterday} other {# days ago}} | OK |
| A39.138 | `enums:buildObstacleStatus.NOT_PLACED` | pendent | pendiente | pending | OK |
| A39.139 | `enums:buildObstacleStatus.CURRENT` | ara | ahora | now | OK |
| A39.140 | `enums:buildObstacleStatus.PLACED` | col·locat | colocado | placed | OK |
| A39.141 | `enums:buildObstacleStatus.SKIPPED` | saltat | saltado | skipped | OK |
| A39.142 | `enums:buildObstacleStatus.NEEDS_CHECK` | per revisar | por revisar | to check | OK |
| A39.143 | `enums:buildSessionStatus.NOT_STARTED` | sense començar | sin empezar | not started | OK |
| A39.144 | `enums:buildSessionStatus.IN_PROGRESS` | en curs | en curso | in progress | OK |
| A39.145 | `enums:buildSessionStatus.COMPLETED` | acabada | terminada | finished | OK |
| A39.146 | `enums:buildSessionStatus.ABANDONED` | abandonada | abandonada | abandoned | OK |
| A39.147 | `enums:setupKind.AGILITY` | Agility | Agility | Agility | OK |
| A39.148 | `enums:setupKind.JUMPING` | Jumping | Jumping | Jumping | OK |
| A39.149 | `enums:setupKind.FUN` | Fun | Fun | Fun | OK |
| A39.150 | `enums:setupKind.OBSTACLE_DRILL` | Treball d'obstacle | Trabajo de obstáculo | Obstacle drill | OK |
| A39.151 | `enums:setupKind.EMPTY` | Buida | Vacía | Empty | OK |
| A39.152 | `enums:courseSource.SMARTER` | Smarter | Smarter | Smarter | OK |
| A39.153 | `enums:courseSource.EDITOR` | Editor | Editor | Editor | OK |
| A39.154 | `enums:courseSource.IMAGE` | Plànol o foto | Plano o foto | Plan or photo | OK |
| A39.155 | `enums:courseSource.AGILITYHUB_COPY` | Còpia d'AgilityHub | Copia de AgilityHub | AgilityHub copy | OK |
| A39.156 | la resta de `courses.json` (≈75 claus: visor 2D/3D, zoom, cerca de recorregut, sessió de muntatge, blocs a 08/24/agenda) | — | — | — | OK en bloc |

**Decisió Jordi:** OK en bloc amb les 11 correccions recomanades (21-10, E153).


### A39.D · RGPD: 12 «Les meves dades» i D10 «Privacitat i dades» (E11-W01, S14 §13 #9) — `auth:profile.dataExport.*`, `admin-census:member.privacy.*`

Textos del `main` `08e4702d` (ronda 1). La ronda 2 (E136, fil D) canvia «7 dies» → data real, pot reutilitzar N-50 i afegeix línies d'estat noves (FAILED, BLOCKED, ERASING) que l'executor llistarà a «Questions»; les aprovaries amb la verificació de la ronda. Per revisar també amb el Josep.

| id | pantalla/clau | ca | es | en | recomanació |
|---|---|---|---|---|---|
| A39.157 | `auth:profile.dataExport.title` | Les meves dades | Mis datos | My data | OK |
| A39.158 | `auth:profile.dataExport.description` | Prepara un paquet amb les dades del teu compte. Estarà disponible durant 7 dies. | Prepara un paquete con los datos de tu cuenta. Estará disponible durante 7 días. | Prepare a package containing your account data. It will be available for 7 days. | OK; la ronda 2 (E136) hi posa la data real: «Estarà disponible fins al {date}.» / «Estará disponible hasta el {date}.» / «It will be available until {date}.» |
| A39.159 | `auth:profile.dataExport.action` | Descarrega les meves dades | Descargar mis datos | Download my data | OK |
| A39.160 | `auth:profile.dataExport.ready` | El paquet està a punt. | El paquete está listo. | The package is ready. | OK (o reutilitzar N-50 «Les teves dades estan a punt», E136 #5) |
| A39.161 | `auth:profile.dataExport.failed` | No s'ha pogut preparar el paquet. | No se ha podido preparar el paquete. | The package could not be prepared. | OK |
| A39.162 | `auth:profile.dataExport.expired` | El paquet ha caducat. | El paquete ha caducado. | The package has expired. | OK |
| A39.163 | `admin-census:member.privacy.title` | Privacitat i dades | Privacidad y datos | Privacy and data | OK |
| A39.164 | `admin-census:member.privacy.erased` | suprimit | suprimido | erased | OK |
| A39.165 | `admin-census:member.privacy.export` | Descarrega les dades (RGPD) | Descargar los datos (RGPD) | Download data (GDPR) | OK |
| A39.166 | `admin-census:member.privacy.exportQueued` | El paquet s'està preparant. El trobaràs a Exportacions. | El paquete se está preparando. Lo encontrarás en Exportaciones. | The package is being prepared. You will find it under Exports. | OK |
| A39.167 | `admin-census:member.privacy.consents.title` | Historial de consentiments | Historial de consentimientos | Consent history | OK |
| A39.168 | `admin-census:member.privacy.consents.granted` | acceptat | aceptado | accepted | OK |
| A39.169 | `admin-census:member.privacy.consents.withdrawn` | revocat | revocado | withdrawn | OK |
| A39.170 | `admin-census:member.privacy.consents.types.privacyPolicy` | Política de privacitat | Política de privacidad | Privacy policy | OK |
| A39.171 | `admin-census:member.privacy.consents.types.imageRights` | Drets d'imatge | Derechos de imagen | Image rights | OK |
| A39.172 | `admin-census:member.privacy.erasure.title` | Supressió de dades | Supresión de datos | Data erasure | OK |
| A39.173 | `admin-census:member.privacy.erasure.action` | Suprimeix les dades (RGPD) | Suprimir los datos (RGPD) | Erase data (GDPR) | OK |
| A39.174 | `admin-census:member.privacy.erasure.description` | La supressió pseudonimitza les dades personals i conserva els moviments que s'han de retenir legalment. | La supresión seudonimiza los datos personales y conserva los movimientos que deben retenerse legalmente. | Erasure pseudonymises personal data and keeps transactions that must be retained by law. | OK |
| A39.175 | `admin-census:member.privacy.erasure.confirm` | PROGRAMA LA SUPRESSIÓ | PROGRAMAR LA SUPRESIÓN | SCHEDULE ERASURE | OK |
| A39.176 | `admin-census:member.privacy.erasure.scheduled` | Supressió programada per al {date} | Supresión programada para el {date} | Erasure scheduled for {date} | OK |
| A39.177 | `admin-census:member.privacy.erasure.cancel` | Anul·la la supressió | Cancelar la supresión | Cancel erasure | OK |
| A39.178 | `admin-census:member.privacy.erasure.cancelDescription` | La sol·licitud es cancel·larà i les dades no se suprimiran en la data programada. | La solicitud se cancelará y los datos no se suprimirán en la fecha programada. | The request will be cancelled and the data will not be erased on the scheduled date. | OK |
| A39.179 | `admin-census:member.privacy.erasure.checks.left` | L'abonat ha d'estar de baixa. | El abonado debe estar de baja. | The member must have left. | OK |
| A39.180 | `admin-census:member.privacy.erasure.checks.invoices` | No hi pot haver cap rebut obert. | No puede haber ningún recibo abierto. | There must be no open invoices. | OK |
| A39.181 | `admin-census:member.privacy.erasure.checks.upfront` | No hi pot haver cap pagament inicial obert. | No puede haber ningún pago inicial abierto. | There must be no open upfront payments. | OK |
| A39.182 | `admin-census:member.privacy.erasure.checks.bookings` | No hi pot haver cap reserva futura activa. | No puede haber ninguna reserva futura activa. | There must be no active future bookings. | OK |
| A39.183 | `admin-census:member.privacy.erasure.blockReasons.memberNotLeft` | L'abonat encara no està de baixa. | El abonado todavía no está de baja. | The member has not left yet. | OK |
| A39.184 | `admin-census:member.privacy.erasure.blockReasons.invoicesOpen` | Hi ha rebuts pendents, en cobrament o impagats. | Hay recibos pendientes, en cobro o impagados. | There are pending, collecting or failed invoices. | OK |
| A39.185 | `admin-census:member.privacy.erasure.blockReasons.upfrontOpen` | Hi ha pagaments inicials oberts. | Hay pagos iniciales abiertos. | There are open upfront payments. | OK |
| A39.186 | `admin-census:member.privacy.erasure.blockReasons.futureBookings` | Hi ha reserves futures actives. | Hay reservas futuras activas. | There are active future bookings. | OK |
| A39.187 | `admin-census:member.privacy.erasure.blockReasons.otherMembershipsActive` | El compte té una altra afiliació activa. | La cuenta tiene otra afiliación activa. | The account has another active membership. | OK |
| A39.188 | la resta (estats de progrés «Preparant el paquet…», «Programant la supressió…», errors de lectura, `blockReasons.other`) | — | — | — | OK en bloc |

**Decisió Jordi:** OK en bloc amb les 11 correccions recomanades (21-10, E153).


### A39.E · E11-W02 (rondes 1–3): paràmetre INC-59, regles INC-60, arrencada sense servidor, comptador del 429, galeria, filtres d'auditoria, `UNKNOWN_HOST`

| id | pantalla/clau | ca | es | en | recomanació |
|---|---|---|---|---|---|
| A39.189 | `admin-settings:param.courses.defaultWarningThresholdM.label` | Llindar d'avís de col·locació | Umbral de aviso de colocación | Placement warning threshold | OK |
| A39.190 | `admin-settings:param.courses.defaultWarningThresholdM.help` | Distància mínima, en metres, per sota de la qual col·locar un recorregut a la pista genera un avís. | Distancia mínima, en metros, por debajo de la cual colocar un recorrido en la pista genera un aviso. | Minimum distance, in metres, below which placing a course in the ring raises a warning. | OK |
| A39.191 | `enums:courseWarningRule.outside-ring` | Fora de la pista | Fuera de la pista | Outside the ring | OK |
| A39.192 | `enums:courseWarningRule.border-clearance` | Marge al perímetre | Margen al perímetro | Perimeter clearance | OK |
| A39.193 | `enums:courseWarningRule.course-no-go` | Zona no-go del recorregut | Zona no-go del recorrido | Course no-go zone | OK |
| A39.194 | `enums:courseWarningRule.ring-no-go` | Zona no-go de la pista | Zona no-go de la pista | Ring no-go zone | OK |
| A39.195 | `enums:courseWarningRule.door-clearance` | Distància a la porta | Distancia a la puerta | Door clearance | OK |
| A39.196 | `enums:courseWarningRule.course-larger-than-ring` | Recorregut més gran que la pista | Recorrido más grande que la pista | Course larger than the ring | OK |
| A39.197 | `enums:courseWarningRule.obstacle-inventory` | Inventari d'obstacles | Inventario de obstáculos | Obstacle inventory | OK |
| A39.198 | `id:login.countdown` | Massa intents. Torna-ho a provar d'aquí a {seconds} s | Demasiados intentos. Vuelve a intentarlo dentro de {seconds} s | Too many attempts. Try again in {seconds} s | OK |
| A39.199 | `common:boot.offline` | No hi ha connexió amb el servidor. | No hay conexión con el servidor. | There is no connection to the server. | OK |
| A39.200 | `common:boot.retry` | Torna-ho a provar | Volver a intentarlo | Try again | OK |
| A39.201 | `common:unavailable.title` | No disponible | No disponible | Unavailable | OK |
| A39.202 | `inactivity:retry` | Torna-ho a provar | Volver a intentarlo | Try again | OK |
| A39.203 | `leave:retry` | Torna-ho a provar | Volver a intentarlo | Try again | OK |
| A39.204 | `shell:loading` | Carregant | Cargando | Loading | OK |
| A39.205 | `common:gallery.theme.club` | Tema del club | Tema del club | Club theme | OK |
| A39.206 | `common:gallery.theme.agilityhub` | Tema AgilityHub | Tema AgilityHub | AgilityHub theme | OK |
| A39.207 | `admin-audit:filters.from` | Des de | Desde | From | OK |
| A39.208 | `admin-audit:filters.to` | Fins a | Hasta | To | OK |
| A39.209 | `errors:UNKNOWN_HOST` | No hi ha cap club configurat per a aquesta adreça. | No hay ningún club configurado para esta dirección. | No club is configured for this address. | OK |

**Decisió Jordi:** OK en bloc amb les 11 correccions recomanades (21-10, E153).

### A39.F · E11-W05 pas 5 (d): dues frases noves per al límit d'error de pantalla (encara no són als locals; proposta de l'organitzador, 21-10)

| id | pantalla/clau | ca | es | en | recomanació |
|---|---|---|---|---|---|
| A39.210 | `common:screen.updated` (un chunk que ja no existeix perquè l'app s'ha actualitzat; avui diu `common:boot.offline`, que és fals) | L'aplicació s'ha actualitzat. Torna a carregar-la. | La aplicación se ha actualizado. Vuelve a cargarla. | The app was updated. Reload it. | OK; el botó que l'acompanya: «Torna a carregar» / «Volver a cargar» / «Reload» (proposta nova) |
| A39.211 | `common:screen.failed` (una pantalla que peta en pintar-se; avui mostra `errors:INTERNAL_ERROR`, amb codi de referència i «vosaltres») | No s'ha pogut mostrar aquesta pantalla. | No se ha podido mostrar esta pantalla. | This screen could not be shown. | OK, amb [Torna-ho a provar] |

**Decisió Jordi:** OK en bloc amb les 11 correccions recomanades (21-10, E153).

---

## A40 · Regles de copy (E11-W05 passos 3 i 4; E142)

### A40.1 · D16: confirmació de `RING_HAS_BOOKINGS` (desactivar una pista o treure-li «Reservable per entrenaments» amb reserves vives; R-05-07/R-05-08)
- **Avui**: el 422 surt com a *toast* i el canvi es perd. D4 ja té la mateixa confirmació aprovada (`admin-scheduling:calendar.ringBookings.*`, R-06-09/R-06-11).
- **Proposta de l'organitzador (21-10)**, claus noves `admin-catalogs:rings.ringBookings.*` (D4 reutilitzat paraula per paraula, amb les dues accions de D16):

| clau | ca | es | en | recomanació |
|---|---|---|---|---|
| `title` | Aquesta pista té reserves d'entrenament | Esta pista tiene reservas de entrenamiento | This ring has training bookings | OK (= D4) |
| `text` | Si continues, aquestes reserves quedaran anul·lades pel club i se'n farà l'avís: | Si continúas, estas reservas quedarán anuladas por el club y se enviará el aviso: | If you continue, the club cancels these bookings and sends the notice: | ca = D4. **es/en no són «paraula per paraula»**: D4 diu «…y se avisará:» i «If you continue, these bookings will be cancelled by the club and notified:» → copiar D4 exactament |
| `deactivate` | Anul·la les reserves i desactiva la pista | Anular las reservas y desactivar la pista | Cancel the bookings and deactivate the ring | OK |
| `unreservable` | Anul·la les reserves i desa | Anular las reservas y guardar | Cancel the bookings and save | OK (= D4 `confirm`) |
| `back` | Torna enrere | Volver atrás | Back | en: D4 diu «Go back» → «Go back» |

- La llista de reserves sota el text: «{memberName} + {dogName} · {day} {time}», com a D4 (`ringBookings`).
- **Recomanació**: OK amb els dos alineaments a D4 (es `text`, en `text` i `back`): dues pantalles, una frase.

**Decisió Jordi:** A40.1 el text de D4 lletra per lletra · A40.2 sí, «Document d'identitat» · A40.3 formes amb barra · A40.4 tot a tu/tú (26 ca + 26 es) · A40.5 es «revertir/reversión», ca «retrocedir» (21-10, E153).

### A40.2 · «DNI» → «Document d'identitat» (pantalla 28 i formularis; INC-41 #17)
- **El problema**: a 28 l'etiqueta és «DNI» encara que el document sigui un NIE o un passaport (`MeProfile` no porta el tipus). El mockup 28 diu «DNI» (literal del Cànic), però el producte és genèric (`countryProfile.idDocumentTypes`, CONVENCIONS_I18N §4).
- **Proposta (E11-W05 pas 4)**: 6 claus canvien; 2 es queden perquè anomenen els tipus que ofereix el perfil `ES`.

| clau | ca ara → proposta | es ara → proposta | en ara → proposta |
|---|---|---|---|
| `census:myData.idDocument` (28) | DNI → **Document d'identitat** | DNI → **Documento de identidad** | ID document (igual) |
| `census:members.columns.idDocument` (D5) | DNI → **Document d'identitat** | DNI → **Documento de identidad** | ID document (igual) |
| `census:members.search` (D5) | Cerca per nom, DNI, gos… → **Cerca per nom, document, gos…** | Buscar por nombre, DNI, perro… → **Buscar por nombre, documento, perro…** | Search by name, ID, dog… (igual) |
| `admin-census:signupReview.fields.idDocument` (D2) | DNI/NIE → **Document d'identitat** | DNI/NIE → **Documento de identidad** | DNI/NIE → **ID document** |
| `admin-census:signupReview.readmission.documentLocked` (D2) | El DNI/NIE no es pot canviar durant una readmissió: per corregir-lo, rebutja la readmissió. → **El document d'identitat no es pot canviar…** (resta igual) | El DNI/NIE no se puede cambiar durante una readmisión… → **El documento de identidad no se puede cambiar…** | The ID document cannot change… (igual) |
| `signup:person.existingDogNote` (16) | …et reconeixerem pel DNI o l'email… → **…et reconeixerem pel document d'identitat o l'email…** | …te reconoceremos por el DNI o el email… → **…te reconoceremos por el documento de identidad o el email…** | …from your identity document or email… (igual) |
| `signup:person.idDniNie` (16, opció de tipus) | DNI / NIE (es queda) | DNI / NIE | DNI / NIE |
| `signup:person.passport` (16, opció de tipus) | Passaport — si no tens DNI/NIE (es queda) | Pasaporte — si no tienes DNI/NIE | Passport — if you do not have a DNI/NIE |

- **Recomanació**: OK. Matís: `signup:person.passport` «si no tens DNI/NIE» només té sentit amb el perfil `ES`; si l'alta amb perfil `GENERIC` mostra la mateixa opció, millor «Passaport» a seques (la tasca ho pot comprovar).

**Decisió Jordi:** A40.1 el text de D4 lletra per lletra · A40.2 sí, «Document d'identitat» · A40.3 formes amb barra · A40.4 tot a tu/tú (26 ca + 26 es) · A40.5 es «revertir/reversión», ca «retrocedir» (21-10, E153).

### A40.3 · Forma neutra per a `gender = OTHER` (11 claus amb `select` de gènere; INC-41 #19)
- **El problema**: avui `OTHER` cau a la branca `other`, el masculí. L'api envia `MALE | FEMALE | OTHER`; la proposta (E11-W05 pas 4) és que cada cadena tingui `FEMALE {…} OTHER {…} other {…}` (`other` = masculí, també per a gènere absent) i que `OTHER` prengui la forma neutra (la que ja fan servir els mockups: «Hola, {name}!») o, si no n'hi ha, la forma amb barra. L'en no té gènere (no canvia).

| clau | ca: branca `OTHER` proposada (FEMALE i other com ara) | es: branca `OTHER` proposada | recomanació |
|---|---|---|---|
| `auth:activation.welcome` («Benvinguda/Benvingut, {name}!») | **Hola, {name}!** | **¡Hola, {name}!** | OK (= `auth:profileChoice.greeting` i `home:greeting`) |
| `auth:profileChoice.member` («Com a alumna/alumne») | **Com a alumne/a** | **Como alumno/a** | OK |
| `auth:profileChoice.instructor` | **Com a instructor/a** | **Como instructor/a** | OK |
| `auth:profileChoice.admin` | **Com a administrador/a** | **Como administrador/a** | OK |
| `auth:profile.memberProfile` («alumna/alumne») | **alumne/a** | **alumno/a** | OK |
| `auth:profile.instructorProfile` | **instructor/a** | **instructor/a** | OK |
| `auth:profile.adminProfile` | **administrador/a** | **administrador/a** | OK |
| `admin-census:member.imageNotice` («…no publiqueu fotos on surti ella/ell.») | **No autoritza l'ús de la seva imatge: no publiquis fotos on surti aquesta persona.** | **No autoriza el uso de su imagen: no publiques fotos en las que aparezca esta persona.** | OK. Nota: la proposta canvia també el registre de les tres branques: es «no publique» → «no publiques» (i ca «no publiqueu» → **«no publiquis»**, que el pas 4 no diu però és la mateixa regla d'A40.4) |
| `admin-census:signupReview.warning.NO_IMAGE_CONSENT` («…on surti ella/ell.») | **…no publiquis fotos on surti {FEMALE ella · OTHER aquesta persona · other ell}.** | **…no publiques fotos en las que aparezca {FEMALE ella · OTHER esta persona · other él}.** | OK; avui es diu «no publiquéis … donde salga» i `imageNotice` «no publique … en las que aparezca»: les dues queden iguals |
| `admin-census:followup.author.member` («{name} (alumna/alumne)») | **{name} (alumne/a)** | **{name} (alumno/a)** | OK |
| `instructor:card.memberStatus` («Abonada/Abonat») | **Abonat/ada** | **Abonado/a** | la tasca proposa «Abonat/da»; la forma normativa catalana és **«Abonat/ada»** |

- **Recomanació**: OK amb «Abonat/ada» i amb «no publiquis» també en ca. Alternativa, si no t'agraden les barres: treure el gènere d'aquests rètols de perfil («Perfil d'alumne», «Perfil d'instructor», «Perfil d'administrador») i deixar el `select` només a les salutacions i als avisos d'imatge.

**Decisió Jordi:** A40.1 el text de D4 lletra per lletra · A40.2 sí, «Document d'identitat» · A40.3 formes amb barra · A40.4 tot a tu/tú (26 ca + 26 es) · A40.5 es «revertir/reversión», ca «retrocedir» (21-10, E153).

### A40.4 · Registre d'`errors.json`: «vosaltres/usted» → «tu/tú» (INC-41 #21)
- **El problema**: `errors.json` (251 codis, els missatges de `CATALEG_ERRORS.md`) tracta el lector de «vosaltres» (ca) i d'«usted» (es), mentre que tots els altres espais de noms diuen «tu/tú» («Torna-ho a provar», «Posa't en contacte amb el club»), i els errors surten inline al costat d'aquestes frases.
- **Recomanació: tu/tú a tot `errors.json`**, com la resta de l'app (una sola veu; l'app parla a la persona, no a un col·lectiu, i l'«usted» no lliga amb «¡Hola, {name}!»). El canvi s'aplica a E11-W05 pas 4 i l'organitzador sincronitza `CATALEG_ERRORS.md` (E109).
- **Abast real: 26 cadenes ca i 26 es**, no 14/13: la llista de marcadors del pas 4 (ca `vostre`, `podeu`, `Torneu`, `Seleccioneu`, `Trieu`, `indiqueu`; es `Vuelva`, `Seleccione`, `Elija`, `indique`, `su cuenta`) no atrapa `Introduïu`, `teniu`, `Inicieu`, `Completeu`, `Accepteu`, `Activeu`, `Confirmeu`, `Reviseu`, `Actualitzeu` ni es `Introduzca`, `Complete`, `Acepte`, `Active`, `Confirme`, `Revise`, `Inicie`, `Actualícelo`, `No puede`, `No tiene`, `su propio`. Cal afegir-los a la prova Vitest del pas 4. Les 26, amb la proposta (en no canvia):

| # | clau `errors:` | ca ara → proposta | es ara → proposta |
|---|---|---|---|
| 1 | `ACCOUNT_BLOCKED` | El vostre compte està bloquejat. → **El teu compte està bloquejat.** | Su cuenta está bloqueada. → **Tu cuenta está bloqueada.** |
| 2 | `CANNOT_CHANGE_OWN_ADMIN_ROLE` | No podeu canviar el vostre rol d'administrador. → **No pots canviar el teu rol d'administrador.** | No puede cambiar su propio rol de administrador. → **No puedes cambiar tu propio rol de administrador.** |
| 3 | `DESCRIPTION_REQUIRED` | Introduïu una descripció. → **Escriu una descripció.** | Introduzca una descripción. → **Escribe una descripción.** |
| 4 | `DOG_NOT_ACCESSIBLE` | No podeu accedir a aquest gos. → **No pots accedir a aquest gos.** | No puede acceder a este perro. → **No puedes acceder a este perro.** |
| 5 | `FORBIDDEN` | No teniu permís per fer aquesta acció. → **No tens permís per fer aquesta acció.** | No tiene permiso para realizar esta acción. → **No tienes permiso para realizar esta acción.** |
| 6 | `INTERNAL_ERROR` | S'ha produït un error inesperat. Torneu-ho a provar; si persisteix, indiqueu el codi de referència al club. → **S'ha produït un error inesperat. Torna-ho a provar; si persisteix, indica el codi de referència al club.** | Se ha producido un error inesperado. Vuelva a intentarlo; si persiste, indique el código de referencia al club. → **Se ha producido un error inesperado. Vuelve a intentarlo; si persiste, indica el código de referencia al club.** |
| 7 | `LEVEL_REQUIRED` | Seleccioneu un nivell. → **Tria un nivell.** | Seleccione un nivel. → **Elige un nivel.** |
| 8 | `LEVEL_UNCHANGED` | Seleccioneu un nivell diferent. → **Tria un nivell diferent.** | Seleccione un nivel diferente. → **Elige un nivel diferente.** |
| 9 | `NEXT_INVOICE_DATE_REQUIRED` | Introduïu la data de la factura següent. → **Escriu la data de la factura següent.** | Introduzca la fecha de la siguiente factura. → **Escribe la fecha de la siguiente factura.** |
| 10 | `NO_MEMBERSHIP` | No teniu cap abonament per a aquest club. → **No tens cap abonament per a aquest club.** | No tiene ningún abono para este club. → **No tienes ningún abono para este club.** |
| 11 | `PASSWORD_COMPROMISED` | Trieu una contrasenya que no hagi aparegut en una filtració de dades. → **Tria una contrasenya que no hagi aparegut en una filtració de dades.** | Elija una contraseña que no haya aparecido en una filtración de datos. → **Elige una contraseña que no haya aparecido en una filtración de datos.** |
| 12 | `RATE_LIMITED` | Hi ha massa sol·licituds. Torneu-ho a provar més tard. → **Hi ha massa sol·licituds. Torna-ho a provar més tard.** | Hay demasiadas solicitudes. Vuelva a intentarlo más tarde. → **Hay demasiadas solicitudes. Vuelve a intentarlo más tarde.** |
| 13 | `REFRESH_EXPIRED` | La sessió ha caducat. Torneu a iniciar la sessió. → **La sessió ha caducat. Torna a iniciar la sessió.** | La sesión ha caducado. Vuelva a iniciar sesión. → **La sesión ha caducado. Vuelve a iniciar sesión.** |
| 14 | `REFRESH_REUSED` | La sessió ja no és vàlida. Torneu a iniciar la sessió. → **La sessió ja no és vàlida. Torna a iniciar la sessió.** | La sesión ya no es válida. Vuelva a iniciar sesión. → **La sesión ya no es válida. Vuelve a iniciar sesión.** |
| 15 | `SAME_MEMBER` | Seleccioneu un abonat diferent. → **Tria un abonat diferent.** | Seleccione un abonado diferente. → **Elige un abonado diferente.** |
| 16 | `SETUP_SOURCE_REQUIRED` | Seleccioneu l'origen del muntatge. → **Tria l'origen del muntatge.** | Seleccione el origen del montaje. → **Elige el origen del montaje.** |
| 17 | `SIMULATION_STALE` | La simulació no està actualitzada. Torneu-la a executar. → **La simulació no està actualitzada. Torna-la a executar.** | La simulación no está actualizada. Vuelva a ejecutarla. → **La simulación no está actualizada. Vuelve a ejecutarla.** |
| 18 | `SMS_BODY_REQUIRED` | Introduïu el contingut del missatge de text. → **Escriu el contingut del missatge de text.** | Introduzca el contenido del mensaje de texto. → **Escribe el contenido del mensaje de texto.** |
| 19 | `STALE_VERSION` | Aquest element s'ha modificat des d'un altre lloc. Actualitzeu-lo i torneu-ho a provar. → **Aquest element s'ha modificat des d'un altre lloc. Actualitza'l i torna-ho a provar.** | Este elemento se ha modificado desde otro lugar. Actualícelo y vuelva a intentarlo. → **Este elemento se ha modificado desde otro lugar. Actualízalo y vuelve a intentarlo.** |
| 20 | `VALIDATION_ERROR` | Reviseu els camps destacats. → **Revisa els camps destacats.** | Revise los campos destacados. → **Revisa los campos destacados.** |
| 21 | `UNAUTHENTICATED` | Inicieu la sessió per continuar. → **Inicia la sessió per continuar.** | Inicie sesión para continuar. → **Inicia sesión para continuar.** |
| 22 | `ACTIVITY_INCOMPLETE` | Completeu l'activitat abans de continuar. → **Completa l'activitat abans de continuar.** | Complete la actividad antes de continuar. → **Completa la actividad antes de continuar.** |
| 23 | `CONSENT_VERSION_OUTDATED` | Accepteu la versió actual del consentiment. → **Accepta la versió actual del consentiment.** | Acepte la versión actual del consentimiento. → **Acepta la versión actual del consentimiento.** |
| 24 | `MODULE_DEPENDENCY` | Activeu primer el mòdul necessari. → **Activa primer el mòdul necessari.** | Active primero el módulo necesario. → **Activa primero el módulo necesario.** |
| 25 | `PRODUCTION_REQUIRES_CONFIRMATION` | Confirmeu l'operació abans d'executar-la a producció. → **Confirma l'operació abans d'executar-la a producció.** | Confirme la operación antes de ejecutarla en producción. → **Confirma la operación antes de ejecutarla en producción.** |
| 26 | `TASK_NOT_DONE` | Completeu primer aquesta tasca. → **Completa primer aquesta tasca.** | Complete primero esta tarea. → **Completa primero esta tarea.** |

- Nota: les es són frases, no etiquetes d'acció: «Vuelve a intentarlo» no entra en la regla de l'infinitiu dels botons (E137); cal que `check-es-actions.mjs` (E11-W04) no les agafi.

**Decisió Jordi:** A40.1 el text de D4 lletra per lletra · A40.2 sí, «Document d'identitat» · A40.3 formes amb barra · A40.4 tot a tu/tú (26 ca + 26 es) · A40.5 es «revertir/reversión», ca «retrocedir» (21-10, E153).

### A40.5 · es «retroceder la remesa» o «revertir la remesa» (D6, R-12-14)
- **El problema**: en ca el mockup D6 i S12 diuen «retrocedir la remesa» (terme del producte: també retrocedeix la numeració). En es, «retroceder» és intransitiu («retroceder la remesa» és un calc); E11-W02 ho va deixar com a terme i ho pregunta. A més, `errors:RUN_NOT_ROLLBACKABLE` ja diu «revertir» en ca i en es, descasat de la resta.
- **Recomanació**: ca es queda amb **«retrocedir»** (mockup) i es passa a **«revertir»** (ca «retrocés» → es «reversión»). La paraula que l'usuari escriu per confirmar, «RETROCEDIR», és contracte de l'api (`confirmation: "RETROCEDIR"`, S12 §6) i no es tradueix. Les cadenes:

| clau | es ara → proposta | ca (sense canvi, llevat de l'última) |
|---|---|---|
| `admin-billing:actions.rollback` | Retroceder la remesa → **Revertir la remesa** | Retrocedeix la remesa |
| `admin-billing:confirm.rollbackTitle` | Retroceder la remesa · {month} → **Revertir la remesa · {month}** | Retrocedeix la remesa · {month} |
| `admin-billing:confirm.rollback` | Retroceder → **Revertir** | Retrocedeix |
| `admin-billing:confirm.rollingBack` | Retrocediendo la remesa… → **Revirtiendo la remesa…** | Retrocedint la remesa… |
| `admin-billing:confirm.rollbackRemittance` | la remesa quedará retrocedida (el fichero XML se conserva). → **la remesa quedará revertida (el fichero XML se conserva).** | la remesa quedarà retrocedida (el fitxer XML es conserva). |
| `admin-billing:confirm.submissionBody` | …A partir de ahí ya no se podrá retroceder. → **…ya no se podrá revertir.** | …ja no es podrà retrocedir. |
| `admin-billing:status.rolledBack` | anulado · retroceso → **anulado · reversión** | anul·lat · retrocés |
| `admin-billing:drawer.cancelReasonRollback` | retroceso de la remesa → **reversión de la remesa** | retrocés de la remesa |
| `admin-billing:errors.notRollbackable` | No se puede retroceder la remesa: → **No se puede revertir la remesa:** | No es pot retrocedir la remesa: |
| `admin-audit:actions.REMITTANCE_ROLLED_BACK` | Remesa retrocedida → **Remesa revertida** | Remesa retrocedida |
| `enums:remittanceStatus.ROLLED_BACK` | retrocedida → **revertida** | retrocedida |
| `enums:collectionFailure.ROLLBACK` | retroceso de la remesa → **reversión de la remesa** | retrocés de la remesa |
| `errors:RUN_NOT_ROLLBACKABLE` | Esta remesa no se puede revertir. (ja bé) | Aquesta remesa no es pot revertir. → **Aquesta remesa no es pot retrocedir.** (sincronitzar `CATALEG_ERRORS.md`) |

**Decisió Jordi:** A40.1 el text de D4 lletra per lletra · A40.2 sí, «Document d'identitat» · A40.3 formes amb barra · A40.4 tot a tu/tú (26 ca + 26 es) · A40.5 es «revertir/reversión», ca «retrocedir» (21-10, E153).

---

## A41 · Club plantilla (S17 §13 #5) i `auth.refreshReuseGraceSeconds`

### A41.1 · Els catàlegs del club plantilla en tres idiomes (S17 §13 #5; E10-T03 Q4; INC-53 #13)
- **Fet verificat**: `seeds/club-template-default.yaml` (api `8d73ad2a`) té **els catàlegs buits** (`levels: []`, `rings: []`, `plans: []`, `faq: []`, `messageTemplates: []`), amb `locales: [ca, es, en]` i `defaultLocale: en`. Per tant, no hi ha cap nom ca sense es/en *en aquest fitxer*: la consola «Catàlegs base» avui copia 0 files (E10-T03). L'única font de noms és el seed del Cànic (`club-canic.yaml`: ca i es, cap en; les pistes són noms propis sense traducció). S17 §13 #5 pregunta qui manté els textos AgilityHub en tres idiomes; l'assumpció és «`club-template-default` al seed».
- **Recomanació**: una tasca petita d'api (seed) que ompli el club plantilla amb un catàleg genèric en ca/es/en, derivat del Cànic però sense cap literal de club. Proposta de contingut (es = el del seed del Cànic on n'hi ha; en = proposta nova; «plantilla» = el que proposo que entri al club plantilla):

**Nivells** (`levels`, `name` localitzat)

| codi | ca (Cànic) | es | en (proposta nova) | plantilla |
|---|---|---|---|---|
| CAD | Cadells | Cachorros | Puppies | sí |
| A … G | A … G | A … G | A … G | **no**: l'escala per lletres és del Cànic (A5: nivells 100 % locals). Proposta genèrica: **Iniciació / Iniciación / Beginners**, **Intermedi / Intermedio / Intermediate**, **Avançat / Avanzado / Advanced** |
| TER | Teràpia | Terapia | Therapy | sí |
| PENDENT | Pendent | Pendiente | Pending | sí (el nivell d'entrada abans de la primera classe) |

**Pistes** (`rings`, `name` no localitzat): el Cànic té «Muntanya», «Central», «Carretera», «Cadells», «Petita» (noms propis). Plantilla: **«Pista 1»** i **«Pista 2»** (`shortName` P1/P2); un club els rebateja a D16.

**Modalitats** (`plans`, `name` localitzat)

| codi | ca (Cànic) | es | en (proposta nova) | plantilla |
|---|---|---|---|---|
| ABONAT | Abonat | Abonado | Member | sí |
| ABONAT_FAMILIAR | Abonat · 2 gossos (familiar) | Abonado · 2 perros (familiar) | Member · 2 dogs (family) | sí |
| PACK6 | Pack 6 | Pack 6 | Pack 6 | sí |
| PACK10 | Pack 10 | Pack 10 | Pack 10 | sí |
| TERAPIA | Teràpia | Terapia | Therapy | sí |
| COMPETICIO_1 | Competició 1 gos | Competición 1 perro | Competition · 1 dog | sí |

(Els preus no es copien si la moneda difereix, `console:catalogs.currencyWarning`; a la plantilla, preus a 0 o cap preu.)

**Preguntes freqüents** (`faq`, `category` i `question` localitzats)

| categoria ca (Cànic) | es | en (proposta nova) |
|---|---|---|
| Convivència al club | Convivencia en el club | Life at the club |
| Reserves de classe | Reservas de clase | Class bookings |
| Competicions | Competiciones | Competitions |

| pregunta ca (Cànic) | es | en (proposta nova) |
|---|---|---|
| Puc venir amb més gent al club? | ¿Puedo venir con más gente al club? | Can I bring other people to the club? |
| El gos pot anar deslligat pel club? | ¿Puede ir el perro suelto por el club? | Can my dog be off the lead at the club? |
| Què faig si la meva gossa té el zel? | ¿Qué hago si mi perra está en celo? | What do I do if my female dog is in season? |
| Quantes classes puc reservar i en quin moment? | ¿Cuántas clases puedo reservar y cuándo? | How many classes can I book, and when? |
| Què he de fer si no puc venir a una classe? | ¿Qué debo hacer si no puedo venir a una clase? | What should I do if I cannot come to a class? |
| Què passa si el dia de classe plou? | ¿Qué pasa si llueve el día de clase? | What happens if it rains on class day? |
| Què cal per poder participar a competicions d'agility? | ¿Qué necesito para participar en competiciones de agility? | What do I need to take part in agility competitions? |

- Les **respostes** són del Cànic (normes pròpies): a la plantilla, o bé només les tres categories sense preguntes, o bé aquestes set preguntes amb respostes genèriques que escriuries tu. Recomano **només les categories** (el club escriu les seves respostes a D11, FAQ i nivells).
- **Plantilles de comunicat** (`messageTemplates`): el seed de producte ja és en tres idiomes (`src/main/resources/seed/message-templates.{ca,es,en}.json`, 52 codis); a la plantilla, tots els codis com al Cànic. Els **noms** es/en els va escriure l'executor (INC-53 #13, E11-T02) i l'organitzador et demana que els revisis aquí:

| codi | ca | es | en | recomanació |
|---|---|---|---|---|
| N-01 | Sol·licitud d'alta rebuda | Solicitud de alta recibida | Signup request received | OK |
| N-02 | Benvinguda i accés | Bienvenida y acceso | Welcome and access | OK |
| N-03 | Sol·licitud rebutjada | Solicitud rechazada | Signup request declined | OK |
| N-04 | Reserva confirmada | Reserva confirmada | Booking confirmed | OK |
| N-05 | Reserva anul·lada (per tu) | Reserva cancelada (por ti) | Booking cancelled (by you) | OK |
| N-06 | Entrenament reservat | Entrenamiento reservado | Training booked | OK |
| N-07 | Entrenament anul·lat | Entrenamiento anulado | Training cancelled | OK |
| N-08a | Classe anul·lada pel club | Clase cancelada por el club | Class cancelled by the club | OK |
| N-08b | Classe modificada pel club (hora/pista/instructor) | Clase modificada por el club (hora/pista/instructor) | Class changed by the club (time/ring/instructor) | OK |
| N-09 | Canvi de nivell | Cambio de nivel | Level change | OK |
| N-10 | Rebut impagat / cobrament fallit | Recibo impagado / cobro fallido | Unpaid receipt / failed collection | OK (es «Recibo impagado»: a l'app el rebut és «recibo», coherent) |
| N-11a | Pack a punt d'esgotar-se | Bono a punto de agotarse | Pack running out | es «Bono» vs «Pack» (ca/en i `enums:upfrontConcept.PACK` i `invoiceLineOrigin.PACK` es «Pack»; «bono» surt també a `admin-settings`, `errors`, `census` es) → unificar a «Pack» |
| N-11b | Pack a punt de caducar / caducat | Bono a punto de caducar / caducado | Pack about to expire / expired | ídem N-11a |
| N-13 | Recordatori de classe/entrenament | Recordatorio de clase/entrenamiento | Class/training reminder | OK |
| N-14 | Sol·licitud de baixa rebuda | Solicitud de baja recibida | Leave request received | OK |
| N-15 | S'ha alliberat una plaça! | ¡Se ha liberado una plaza! | A place has been freed up! | OK |
| N-16 | Possible anul·lació de classe | Posible cancelación de clase | Class may be cancelled | OK |
| N-17 | Classe anul·lada per manca d'alumnes | Clase cancelada por falta de alumnos | Class cancelled for lack of students | OK |
| N-18a | Sol·licitud d'inactivitat rebuda | Solicitud de inactividad recibida | Inactivity request received | OK |
| N-18b | Inactivitat aprovada/denegada | Inactividad aprobada/denegada | Inactivity approved/declined | OK |
| N-18c | Represa d'activitat | Reanudación de la actividad | Back to activity | OK |
| N-19 | T'hem trobat a faltar (no presentat) | Te hemos echado de menos (no presentado) | We missed you (no-show) | OK |
| N-20 | Tasca nova | Tarea nueva | New task | OK |
| N-21 | Tasca completada | Tarea completada | Task completed | OK |
| N-22 | Nota de l'alumne nova/canviada | Nota del alumno nueva/cambiada | Student note new/changed | OK |
| N-23 | Cartilla pendent (recordatori) | Cartilla pendiente (recordatorio) | Vaccination card pending (reminder) | OK |
| N-24 | Comunicat del club | Comunicado del club | Message from the club | OK |
| N-28 | Baixa confirmada (data d'efecte) | Baja confirmada (fecha de efecto) | Leave confirmed (effective date) | OK |
| N-29 | Reserves bloquejades / desbloquejades | Reservas bloqueadas / desbloqueadas | Bookings blocked / unblocked | OK |
| N-30 | Cobrament realitzat (Stripe) / rebut emès | Cobro realizado (Stripe) / recibo emitido | Payment collected (Stripe) / receipt issued | OK |
| N-31 | Recorregut nou a la pista | Recorrido nuevo en la pista | New course on the ring | OK |
| N-32a | Activitat publicada | Actividad publicada | Activity published | OK |
| N-32b | Inscripció a activitat confirmada / anul·lada | Inscripción a actividad confirmada / anulada | Activity registration confirmed / cancelled | OK |
| N-32c | Activitat cancel·lada pel club | Actividad cancelada por el club | Activity cancelled by the club | OK |
| N-33 | Ja pots reservar la setmana vinent | Ya puedes reservar la semana que viene | Next week is open for booking | OK |
| N-34 | Sol·licituds d'alta pendents des de fa dies | Solicitudes de alta pendientes desde hace días | Signup requests pending for days | OK |
| N-35 | Cobrament fallit (targeta) | Cobro fallido (tarjeta) | Card payment failed | OK |
| N-36 | Reserva feta/anul·lada pel club en nom teu | Reserva hecha/anulada por el club en tu nombre | Booking made/cancelled by the club on your behalf | OK |
| N-37 | Nou gos afegit / gos donat de baixa | Perro nuevo añadido / perro dado de baja | New dog added / dog removed | OK |
| N-38 | Canvi de mètode de pagament / IBAN | Cambio de método de pago / IBAN | Payment method / IBAN changed | OK |
| N-18d | Canvi en un període d'inactivitat | Cambio en un periodo de inactividad | Change in an inactivity period | OK |
| N-32d | Activitat modificada pel club | Actividad modificada por el club | Activity changed by the club | OK |
| N-40 | Reserva no completada (pagament caducat) | Reserva no completada (pago caducado) | Booking not completed (payment expired) | OK |
| N-41 | Remesa del mes pendent | Remesa del mes pendiente | This month's direct debit pending | OK (en «direct debit» és més clar que «remittance») |
| N-42 | Procés automàtic amb errors | Proceso automático con errores | Automatic process with errors | OK |
| N-46 | La plaça ja s'ha ocupat | La plaza ya se ha ocupado | The place has been taken | OK |
| N-47 | Entrenament reservat / anul·lat pel club en nom teu | Entrenamiento reservado / anulado por el club en tu nombre | Training booked / cancelled by the club on your behalf | OK |
| N-49 | Límit mensual d'SMS assolit | Límite mensual de SMS alcanzado | Monthly SMS limit reached | OK |
| N-50 | Les teves dades estan a punt | Tus datos están listos | Your data is ready | OK |
| N-51 | Correu rebotat | Correo rebotado | Email bounced | OK |
| N-54 | Classe amb pocs alumnes (per sota del mínim) | Clase con pocos alumnos (por debajo del mínimo) | Class with few students (below the minimum) | OK |
| N-55 | Reemborsament a fer a mà | Reembolso a hacer a mano | Refund to make by hand | OK |

**Decisió Jordi:** A41 es «Bono» a tot el castellà · A41.1 joc inicial genèric al seed de la plantilla, clonable per parts, editable per club (E11-T18) · A41.2 OK (21-10, E153).

### A41.2 · Els sis textos d'`auth.refreshReuseGraceSeconds` (paràmetre de plataforma, INC-36; E9-W06 els va afegir al web, E134)
- **Context**: paràmetre `int`, 0–120 s, per defecte 30, bloc «sistema», `editableBy: PLATFORM` (només la consola el veu). És el marge durant el qual un token de renovació ja rotat es pot tornar a fer servir un cop (una resposta perduda, dues pestanyes alhora) sense tancar la família de sessions.

| clau | ca | es | en | recomanació |
|---|---|---|---|---|
| `admin-settings:param.auth.refreshReuseGraceSeconds.label` | Marge de reutilització del token de renovació | Margen de reutilización del token de renovación | Refresh-token reuse grace period | OK |
| `admin-settings:param.auth.refreshReuseGraceSeconds.help` | Segons durant els quals es pot recuperar una única resposta de renovació perduda. Un valor de 0 desactiva el marge. | Segundos durante los que se puede recuperar una única respuesta de renovación perdida. Un valor de 0 desactiva el margen. | Seconds during which one lost refresh response may be recovered. A value of 0 disables the grace period. | OK; si el vols més planer: «Segons durant els quals una renovació de sessió perduda (o dues pestanyes alhora) es pot repetir sense tancar la sessió. 0 = sense marge.» (proposta nova) |

**Decisió Jordi:** A41 es «Bono» a tot el castellà · A41.1 joc inicial genèric al seed de la plantilla, clonable per parts, editable per club (E11-T18) · A41.2 OK (21-10, E153).

---

## Resum
- **A39**: 211 ítems en 6 blocs (A · consola 69 · B · recorreguts backoffice 49 · C · recorreguts app 38 · D · RGPD 32 · E · E11-W02 21 · F · E11-W05 2). Recomanació «OK» a tots menys 11 matisos.
- **A40**: 5 regles (RING_HAS_BOOKINGS 5 cadenes · DNI 6 cadenes · OTHER 11 cadenes · registre d'`errors` 26 ca + 26 es · retroceder/revertir 12 es + 1 ca).
- **A41**: 2 ítems (club plantilla: 4 catàlegs + 52 noms de plantilla · 6 textos del paràmetre).
