# ADR-001 — Stack tecnològic (backend + frontend)

**Estat:** Acceptada · **actualitzada 2026-09-03 (2a sessió)**: el backend és la plataforma AgilityHub (`agilityhub-core-api`, monòlit modular amb contextos `identity` · `clubs` · `courses` · `payments`) i el front és `agilityhub-core-web` (apps `clubs`, `clubs-admin`, `id` + packages `ui`, `i18n`, `api-client`, `course-core`, `course-ui`). Els noms `agilityhub-club-*` del 02-09 queden substituïts (els repos `agilityhub-api`/`agilityhub-web` ja existeixen per a Learn). Vegeu `VISIO_PLATAFORMA_AGILITYHUB.md` i ADR-010…013.
**Data:** 2026-09-02
**Decisors:** Jordi (amb assistència IA)

## Context

Comença la fase de desenvolupament del SAAS del club (model de dades v1.6, mockups V8 mòbil / V7 escriptori com a contracte d'UI). L'equip és en Jordi + IA, amb l'objectiu d'un MVP operatiu com més aviat millor però d'alta qualitat i estable. S'han d'afegir tests i fer codi fàcil de mantenir. La referència de treball és **Avanta** (front i back), on ja hi ha convencions madures, skills d'IA afinades i experiència operativa. A mig termini el producte ha de ser **marca blanca** sota AgilityHub (vegeu ADR-002).

## Opcions considerades

1. **Replicar l'stack d'Avanta** — pros: reutilització directa de patrons, seguretat (OAuth2 + magic links ja resolts), skills i workflow de branques/PR existents, un sol conjunt d'hàbits entre projectes; contres: dos llenguatges (Java + TS).
2. **Stack unificat en TypeScript (NestJS + React)** — pros: un sol llenguatge; contres: es perd tot l'actiu d'Avanta (convencions, auth, tests, experiència), més risc amb un sol desenvolupador.
3. **Stack nou "de moda" (Go/HTMX, RoR…)** — descartat: cap avantatge que compensi la corba d'aprenentatge amb aquest equip i calendari.

## Decisió

**Opció 1 — replicar Avanta**, amb aquests components:

### Backend (`agilityhub-core-api`, abans `agilityhub-club-api`)
- **Spring Boot 3.5.x · Java LTS (mateixa versió que Avanta) · Maven**
- Arquitectura per capes amb dependències només cap avall: `api/` (controllers, autorització, OpenAPI) → `application/` (services, DTOs record, integracions) → `domain/` (entitats amb validació al constructor + `toDTO()`, màquines d'estat, enums) → `persistence/` (repositoris two-tier: façana + interfície Spring Data MongoDB) · `configuration/` · `utilities/`
- **Contextos delimitats** (paquets de primer nivell, cadascun amb les seves capes): `identity` (AgilityHub ID, ADR-010) · `clubs` (tot el domini del club, subdividit per mòduls funcionals) · `courses` (recorreguts, rings, muntatge, ADR-013) · `payments` (proveïdors de cobrament, ADR-009) · `platform` (consola de clubs, perfils de país, i18n). Regla: `clubs` pot dependre d'`identity`, `courses` i `payments` per interfície; cap context depèn de `clubs`.
- **Auth**: OAuth2 Authorization Server amb JWT — password grant opcional + **magic links** + refresh tokens; sessió persistent 30 dies lliscants (model §USUARI). Rols alumne/instructor/administrador com a claims.
- **Tests**: **testing exhaustiu com a requisit de qualitat** (decisió Jordi 03-09): unitaris de domini + integració amb Testcontainers (patró `AbsTestContainer`) de cada endpoint + aïllament de tenant; cap PR de backend sense tests al mateix PR; llindar de cobertura a CI. Detall: `PLA_BACKEND.md` §9.
- Entitats: patró A (col·lecció pròpia, `@Document`, id UUID string, constructor des de DTO amb `InvalidParamException`, no-arg constructor) i patró B (subdocuments embeguts), com a la skill `avanta-spring-dev`.

### Frontend (`agilityhub-core-web`, abans `agilityhub-club-webapp`)
- **Monorepo pnpm workspaces + Turborepo**, versions compartides via pnpm catalog.
- **React 19 + TypeScript + Vite + Tailwind CSS 4** · Radix UI · TanStack Query (server state) + zustand (client state) · react-hook-form · react-router · react-i18next + i18next-icu (CA/ES/EN, ADR-011) · axios · Sentry · sonner · lucide-react · three.js (només `course-ui`).
- **Tres apps + packages**:
  - `apps/clubs` — alumnes + instructors, **mobile-first PWA** (pantalles 01–30; tria de perfil 03b dins la mateixa app, validada pel Josep). `clubs.agilitydoghub.com` + àlies per club (`app.agilitycanic.cat`).
  - `apps/clubs-admin` — backoffice d'escriptori (D1–D17 + D18 biblioteca de recorreguts + consola de clubs per al super-admin). `clubsadmin.agilitydoghub.com` + àlies per club.
  - `apps/id` — AgilityHub ID: login, enllaç màgic, contrasenya, compte, selector de productes (`id.agilitydoghub.com`, ADR-010).
  - `packages/ui` (design system + tokens per club, ADR-002) · `packages/i18n` · `packages/api-client` (tipus generats de l'OpenAPI + hooks) · `packages/auth` · `packages/course-core` i `packages/course-ui` (ADR-013).
- Qualitat: ESLint + Prettier + husky + commitlint (conventional commits), Jest + Testing Library.

### Distribució mòbil
**PWA instal·lable** (manifest + service worker + web push, iOS ≥16.4 inclòs). Si un club demana presència a botigues, **Capacitor** com a embolcall posterior — decisió reversible, no condiciona el codi (recollit aquí; substitueix l'obertura d'un ADR-007 separat).

## Conseqüències

- Tot el coneixement, les skills (`avanta-spring-dev`, `avanta-feature-workflow`, adaptades) i els hàbits de PR es traslladen amb cost gairebé zero.
- Dos llenguatges: assumit — la frontera REST/OpenAPI és el contracte entre plans (PLA_BACKEND / PLA_FRONTEND).
- La tria de BBDD i el model multi-tenant queden a l'**ADR-002**; hosting i entorns a l'**ADR-003**; identitat a l'**ADR-004 + ADR-010**; pagaments a l'**ADR-009**; i18n a l'**ADR-011**; mòduls i regles a l'**ADR-012**; recorreguts a l'**ADR-013**. Segueixen oberts: ADR-005 (email transaccional, es decideix a E1), ADR-006 (llibreria pain.008, E8) i ADR-008 (migració Playoff, E12) — calendaritzats al `05-desenvolupament/PLA_DESENVOLUPAMENT.md` v2.
