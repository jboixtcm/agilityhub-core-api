# ADR-013 — Plataforma de recorreguts: recuperar `course-core` al core, rings sobre les pistes, muntatge i base de l'AR

**Estat:** Acceptada · **verificada el 05-09-2026** sobre `apps/web-planner` (resultat a S16 §14; pendent l'arrel del monorepo per a `course-core`/`shared-types`/Unity)
**Data:** 2026-09-03
**Decisors:** Jordi (amb assistència IA)

## Context

La vertical demana que el club **munti pistes** (pròpies, Smarter o d'AgilityHub/challenges) i que l'alumne vegi què hi ha muntat; més endavant, muntatge amb AR/VR. El projecte `@agilityhub/web-planner` (Next.js + Supabase + three.js) ja té: parser i geometria de recorreguts Smarter (`@agilityhub/course-core`), venues amb **rings** de geometria real (mides, portes amb posició/amplada/flux, zones no-go, marcadors de calibratge imprimibles amb QR), **col·locació** d'un recorregut dins el ring (2D/3D) amb avisos (marges, portes, inventari), inventari d'obstacles per ring, events amb jutges, **sessions de muntatge** (estat per obstacle, mode live) i calendari. Decisió de Jordi (03-09): recuperar-ho **a l'MVP** i que el **core sigui la font de veritat** de recorreguts, rings i challenges; el web-planner es retira.

## Opcions considerades

1. **Recuperar `course-core` (TS pur) i el component de col·locació com a packages del monorepo, persistència al core (Mongo), mòdul `courses` a l'API** — una plataforma, un format de recorregut per a Clubs, Learn i AR.
2. Mantenir el web-planner com a servei (Next + Supabase) i integrar per API — dos stacks i dues BBDD; els rings del planner i les pistes del club serien entitats duplicades.
3. Modelar-ho a Learn (Laravel) — carrega l'app de vídeo amb geometria i muntatge.

## Decisió

**Opció 1.**

### Paquets i mòduls
- `agilityhub-core-web/packages/course-core`: model canònic de recorregut (obstacles, posicions, orientacions, numeració, seqüència), **parser Smarter `.txt`**, geometria (bounding box, distàncies, línies de cursa), regles de col·locació (marges, portes, no-go, inventari) i avisos. **Zero dependències de React/Supabase**. Versió d'esquema al JSON (`schemaVersion`).
- `agilityhub-core-web/packages/course-ui`: visor 2D (SVG/canvas) i 3D (three.js) del recorregut i del ring, editor de col·locació (arrossegar/rotar el recorregut dins el ring), full de muntatge imprimible, marcadors QR. Usat per `apps/clubs-admin` (biblioteca, col·locació) i `apps/clubs` (visor per a alumnes/instructors, sessió de muntatge al mòbil).
- `agilityhub-core-api/…/courses`: col·leccions **COURSE** (recorregut), **RING** (geometria, lligada 1—1 a PISTA del club), **PLACEMENT** (recorregut col·locat en un ring), **RING_SETUP** (què hi ha muntat ara en una pista: col·locació o imatge, des de/fins a, qui), **BUILD_SESSION** (muntatge: estat per obstacle, live), **OBSTACLE_INVENTORY** (per ring/club), i a R2 **CHALLENGE** + **CHALLENGE_ATTEMPT**. Propietari d'un recorregut: `ownerType = CLUB | AGILITYHUB | ACCOUNT` (dissenys personals d'un jutge) amb `visibility = PRIVATE | CLUB | PUBLIC`.

### Encaix amb el club
- **PISTA = RING**: la pista del club (D16) guanya un bloc opcional «geometria» (mides, orientació, portes, zones no-go, marcadors). Sense geometria, la pista segueix funcionant com fins ara (classes, entrenaments) i només admet «recorregut muntat» per imatge.
- **Recorregut muntat** (IDEA-02): l'instructor/admin registra què hi ha a cada pista (col·locació o foto/plànol, tipus, nivell orientatiu, fins quan). Surt a la pantalla 08 (abans de reservar entrenament), al detall de la reserva, a 10/23 i a D12; caducitat automàtica (paràmetre). Notificació opcional «recorregut nou a la pista X».
- **Biblioteca del club** (nova pantalla d'escriptori D18 i visor mòbil): pujar Smarter, importar d'AgilityHub, etiquetar (disciplina, nivell AgilityHub, autor), duplicar, col·locar en un ring, generar full de muntatge, obrir sessió de muntatge.
- **Activitats** (D7): una activitat pot vincular col·locacions per ring (competicions, seminaris) — el que al planner eren *events*. Els «jutges convidats» del planner es modelen com a comptes amb accés a la col·locació (R2).
- **Learn/challenges (R2)**: AgilityHub publica recorreguts `PUBLIC` amb un CHALLENGE (criteris, vigència, nivell); el club el munta; l'alumne registra l'intent (temps, faltes, vídeo) des de Clubs o Learn.
- **AR/VR (R3)**: consumeix RING (marcadors de calibratge), PLACEMENT (posicions absolutes) i BUILD_SESSION (estat live per WebSocket/SSE) — **cap dada nova**, l'AR és un client.

### Verificacions abans de tancar l'etapa E9 (el codi no s'ha pogut revisar el 03-09)
1. `course-core` no importa res de Supabase/Next; té tests; llicència i autoria clares.
2. Format del model (unitats, origen de coordenades, rotacions, tipus d'obstacle) i cobertura del parser Smarter (versions del fitxer `.txt`, obstacles no suportats).
3. Quines regles de col·locació existeixen i quins avisos generen; dependència de three.js (versió) i pes del bundle.
4. Com es representen marcadors QR i calibratge (per a l'AR).
5. Què cal reescriure (persistència Supabase → API del core; auth del planner → AgilityHub ID).

## Conseqüències

- E9 és una etapa amb un fil propi (TS pur, independent del back del club) que pot començar a E0.
- El model del club creix amb les taules del mòdul `courses` (`MODEL_DADES_PLATAFORMA.md` §5) i la pantalla D18 (sense mockup: es dissenya en codi seguint el design system; Josep la valida en staging).
- El web-planner queda congelat i la seva BBDD Supabase es migra (si hi ha dades reals) amb un script d'importació de rings/recorreguts.
- La spec S16 recull els detalls funcionals i les proves.

## Verificació del 05-09-2026 (WP-16-0) — resum

- **Confirmat**: `course-core` (parser Smarter `parseSmarterTxt`, geometria, `runWarnings` amb `ruleId` i severitats, portes, AprilTag, calibratge, unitats) i `shared-types` (tipus de fila + **`BuildSessionExportV1`**, el contracte de l'app **Unity** — D-037) són packages TS purs consumits pel planner; el planner és un Next 15 madur (fase 12d, 21 fitxers de test, decisions D-0xx) amb esquema Supabase complet (venues, rings amb portes/no-go/marcadors AprilTag/inventari, courses, col·locacions amb calendari/esdeveniments/graus/mides, sessions de muntatge amb codi d'unió i estat per obstacle en temps real, calibratge, esdeveniments amb jutges, auditoria) i rols `agilityhub_admin · venue_admin · judge`; feature flags d'AR (Unity) i **Quest** (VR).
- **Decisió refinada**: recuperar **tot** el planner, no només `course-core`: (1) copiar `course-core` + `shared-types`; (2) implementar `CoreApiStore` (la UI només coneix la interfície `PlannerStore`) contra el core, que reprodueix l'esquema Supabase (S16 §14.2) i serveix `BuildSessionExportV1`; (3) moure les pàgines i components React a `apps/clubs-admin` (D18) i `packages/course-ui`; (4) el **club és un `Venue`** (venues sense club → R2); (5) l'AR segueix la direcció ja presa (Unity + AprilTag; S20 s'ajusta).
- **Pendent**: muntar l'arrel de `agilityhub-course-builder` per verificar dependències/llicència de `course-core`, l'estat de les apps Unity/Quest i si Supabase té dades reals a migrar.
