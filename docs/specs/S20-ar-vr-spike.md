# S20 — Muntatge de pistes amb AR (o VR): spike de viabilitat i requisits

**Etapa:** R3 (després de R2); el **spike** es pot fer en qualsevol moment posterior a E9 perquè només necessita l'API de S16 · **Mòduls:** `COURSES` · **Pantalles:** cap a Clubs (l'app AR/VR és un client nou) · **Model:** cap entitat nova (ADR-013: l'AR consumeix RING, PLACEMENT i BUILD_SESSION) · VISIO §2/§6 · **Estat:** esborrany (03-09-2026) · **Versió:** 0.2 (05-09: ajust per la revisió del web-planner)

## 1. Propòsit i abast

Decidir **amb un prototip** si podem muntar una pista al camp amb el mòbil en **AR** (veure els obstacles projectats al ring real i col·locar-los al lloc exacte) i, si no arriba la precisió o el cost és desproporcionat, fer-ho en **VR/3D** (previsualitzar el muntatge en un ring virtual i guiar-se amb el full de muntatge i les distàncies als marcadors). L'objectiu de negoci de Jordi: «tenir tota la vertical». Aquesta spec fixa les **hipòtesis a validar**, els **criteris de decisió**, el **contracte d'API** que l'app ha de consumir (ja construït a S16) i el pla del spike. No especifica l'app final: ho farà una spec S21 quan el spike hagi decidit.

## 2. Pantalles i rutes

Prototip (spike), no producte: (1) pantalla de login OIDC (S01 client `ar-app`); (2) tria de club/ring/col·locació (llistes de l'API); (3) **calibratge**: escanejar ≥ 3 marcadors QR impresos (S16 `marker-sheet`) col·locats a les posicions de la geometria; (4) **vista AR**: obstacles com a siluetes 3D a escala amb número, distància i fletxa fins a la posició, estat de la sessió de muntatge (S16 `BuildSession`) sincronitzat; (5) mode **VR/3D**: el mateix ring en 3D navegable (sense càmera) amb full de muntatge; (6) marcar obstacle com a col·locat (→ `PATCH /build-sessions/{id}/obstacles/{id}`).

## 3. Entitats i camps

Cap de nova. Consumeix: `Ring.geometry` (mides, orientació, `calibrationMarkers[] {x, y, qrPayload}`), `Placement.resolvedObstacles[]` (metres, frame del ring), `BuildSession` (estats, SSE). Possible camp futur si el spike ho demana: `Ring.geometry.calibrationMarkers[].heightM` i `markerSizeCm` (proposta §13).

## 4. Regles de negoci / hipòtesis a validar

| Hipòtesi | Com es valida | Criteri d'èxit |
|---|---|---|
| **H-20-01 Precisió del calibratge amb marcadors** | Ring de 20 × 40 m amb 4 marcadors QR de 30 cm a les cantonades; es col·loquen 10 obstacles amb l'AR i es mesura amb cinta la desviació respecte al pla | error mitjà ≤ 30 cm i màxim ≤ 60 cm a 40 m del marcador més proper (tolerància típica de muntatge d'agility; a validar amb Jordi com a jutge) |
| **H-20-02 Deriva del tracking** | Caminar 5 min pel ring (sol, gespa, mal contrast) sense reescanejar | deriva ≤ 50 cm o **re-ancoratge automàtic** en veure un marcador |
| **H-20-03 Llum i superfície** | Proves a ple sol, a l'ombra i al capvespre; gespa i sorra | l'experiència és usable a 2 de 3 condicions de llum sense trípode |
| **H-20-04 Dispositius** | iPhone (ARKit) i Android mitjà (ARCore); mòbil vs tauleta | funciona a l'iPhone i a ≥ 1 Android de gamma mitjana |
| **H-20-05 Temps de muntatge** | Mateix recorregut muntat per dues persones: amb full de muntatge + cinta vs amb AR | AR ≥ 30 % més ràpid o ≥ igual de precís amb una sola persona |
| **H-20-06 Plataforma tècnica** | WebXR (PWA, sense botiga) vs natiu (Unity/ARKit+ARCore, o Capacitor + plugins) | WebXR és acceptable si compleix H-01/02 a iOS (avui WebXR a iOS Safari és limitat: hipòtesi pessimista → natiu) |
| **H-20-07 Sessió live** | Dos mòbils muntant el mateix ring | estat sincronitzat en < 2 s via SSE (S16 T-16-10) |

Regles de decisió: si H-01 **i** H-02 **i** H-04 es compleixen → **AR** (plataforma segons H-06); si falla H-01 o H-02 però H-05 dona valor amb el 3D → **VR/3D guiat** (previsualització + distàncies als marcadors + sessió live); si res no aporta valor → només full de muntatge (S16) i s'atura la línia.

## 5. Estats i transicions

Spike: `PLANNED → BUILT → FIELD_TESTED → DECIDED{AR | VR | STOP}`; documentat a `04-arquitectura/decisions/ADR-014-muntatge-ar-vr.md` (a escriure amb el resultat).

## 6. API

Tot existent (S16 §6 + S01): `GET /me/products`/membresies, `GET /rings/{id}/geometry`, `GET /placements?ringId=`, `GET /placements/{id}`, `POST /build-sessions`, `PATCH /build-sessions/{id}/obstacles/{obstacleId}`, `GET /build-sessions/{id}/events` (SSE; per a natiu, alternativa WebSocket — proposta §13 si SSE dona problemes en segon pla). Token OIDC natiu amb refresh (S01 client `ar-app`, PKCE). **Cap endpoint nou**; si el spike en necessita, primer s'afegeix a S16.

## 7. Esdeveniments

Cap de nou; el client emet `BuildSessionProgress` via l'API.

## 8. Notificacions

Cap.

## 9. Paràmetres i mòduls

Cap paràmetre; mòdul `COURSES` obligatori al club.

## 10. i18n i localització

Prototip en `ca` i `en`; unitats en metres; l'app final seguirà `Account.locale` (S01) i els `enums:` de S16.

## 11. Criteris d'acceptació del spike

- T-20-01 Informe de camp amb les 7 hipòtesis mesurades (taula amb valors reals, fotos, vídeo) i decisió argumentada.
- T-20-02 Prototip que autentica per OIDC contra staging, carrega un ring i una col·locació reals (fictícies) i sincronitza una sessió amb un segon dispositiu.
- T-20-03 Cost estimat de l'app final (plataforma triada, setmanes, dependències, botigues) i llista de canvis a S16 (si cal, p. ex. alçada dels marcadors).
- T-20-04 ADR-014 escrit i acceptat.

## 12. Paquets de feina

| Paquet | On | Depèn de | Lliurable |
|---|---|---|---|
| WP-20-A Preparació | staging + camp del Cànic | S16 en staging, `marker-sheet` imprès, geometria d'un ring real (mesurada) | ring calibrat + 2 col·locacions de prova |
| WP-20-B Prototip WebXR | repo `agilityhub-ar-spike` (nou, descartable) | WP-20-A | PWA amb calibratge per QR + siluetes AR (three.js/WebXR) + sessió live |
| WP-20-C Prototip natiu (només si H-06 descarta WebXR a iOS) | Unity AR Foundation o Swift ARKit | WP-20-A | mateix abast que B en iOS |
| WP-20-D Proves de camp i decisió | camp | B (o C) | informe T-20-01, ADR-014, spec S21 si es continua |

## 13. Dubtes oberts

| # | Dubte | Qui | Assumpció |
|---|---|---|---|
| 1 | Tolerància acceptable de col·locació per a entrenament (no competició) | Jordi (jutge) | 30 cm mitjana |
| 2 | Marcadors: cantonades del ring (4) o més (6–8) per a rings grans | spike | 4 + opció de 2 centrals |
| 3 | Dispositiu objectiu del club (mòbil de l'instructor) | Josep | iPhone recent + 1 Android |
| 4 | SSE en apps natives en segon pla → WebSocket? | spike | SSE; WebSocket si cal |
| 5 | VR «real» (ulleres) té sentit per a un club? | Jordi | no: «VR» = 3D navegable al mòbil/tauleta |

**Propostes**: camps `Ring.geometry.calibrationMarkers[].heightM`, `markerSizeCm` (S16) si el spike ho demana; ADR-014 (resultat del spike); repo `agilityhub-ar-spike` fora del monorepo (descartable).

## 14. Ajust després de la revisió del web-planner (05-09-2026) — v0.2

El monorepo `agilityhub-course-builder` **ja ha pres la direcció tècnica de l'AR**: app **Unity** (AR Foundation) que consumeix `BuildSessionExportV1` (`shared-types`, zod; «Phase 8 — Unity prep (D-037)»), calibratge amb **marcadors AprilTag** (`ring_markers.april_tag_id`, `marker_uid`, posició 3D i mida física; etiquetes A–F en ordre de lectura; `gradeCalibration` amb llindars en cm i `calibration_logs`), estat per obstacle escrit per l'AR (`build_obstacle_status`, `last_updated_client_session_id`), sessions amb codi d'unió i estratègia de muntatge (`selected_strategy = equipment_type`), perfils d'actius (`generic-fci`, `MANUFACTURER_PROFILES`) i un **placeholder de Quest (VR)** (`NEXT_PUBLIC_ENABLE_QUEST_PLACEHOLDER`).

Conseqüències sobre aquesta spec:
- **H-20-06 queda resolta**: plataforma = **Unity natiu** (iOS/Android); WebXR només com a pla B si l'app Unity no és viable. WP-20-B (WebXR) passa a opcional; WP-20-C (natiu) és el camí principal i **parteix de l'app Unity existent**, no de zero.
- El contracte d'API ja existeix: `GET /build-sessions/{id}/export` (`BuildSessionExportV1`) + `PATCH /build-sessions/{id}/obstacles/{obstacleId}` + `POST /rings/{id}/calibrations` + `POST /build-sessions/join {code}` (S16 §14.5). L'app Unity ha de canviar la seva capa de dades (Supabase → core + OIDC natiu `ar-app`, S01).
- El spike (WP-20-A/D) es converteix en: (1) inventari de l'estat real de les apps Unity/Quest (cal muntar l'arrel del monorepo — pendent), (2) prova de camp H-20-01…05 al Cànic amb un ring calibrat amb els marcadors AprilTag impresos des del core, (3) ADR-014 amb la decisió AR / VR (Quest) / només full de muntatge.
- Toleràncies (H-20-01) i llindars de calibratge: reutilitzar els de `gradeCalibration` (cm) en lloc d'inventar-ne.

## Canvis

- 03-09-2026 · v0.1 · esborrany inicial (spike) a partir de VISIO §2/§6, ADR-013 i S16.
- 05-09-2026 · v0.2 · §14: l'AR ja és Unity + AprilTag (D-037) amb Quest com a VR; el spike parteix de l'app existent i del contracte `BuildSessionExportV1`.
