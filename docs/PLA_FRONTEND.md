# Pla de frontend — `agilityhub-core-web`

**v2.0 · 03-09-2026** (substitueix la v1.0) · Referència d'estil: Avanta Webapp (monorepo) · Contracte d'UI: `03-disseny/mockups/pantalles/` (V8 mòbil, V7 escriptori, una pantalla per fitxer) · Specs: `specs/S01–S20` · Convencions: `specs/00-transversal/CONVENCIONS_I18N.md`, `CATALEG_MODULS.md` · Marc: `PLA_DESENVOLUPAMENT.md` v2

## 1. Base tècnica (patró Avanta)

- **Monorepo pnpm workspaces + Turborepo**, pnpm catalog; ESLint + Prettier + husky + commitlint; Vitest + Testing Library; Playwright (E2E).
- **React 19 + TypeScript + Vite + Tailwind CSS 4** · Radix UI · TanStack Query · zustand · react-hook-form + zod · react-router · **react-i18next + i18next-icu** (ca/es/en) · axios · Sentry · sonner · lucide-react + icones SVG dels mockups · three.js (només `course-ui`).

```
apps/
├── clubs/        # alumnes + instructors — PWA mobile-first (01–30 + visor/registre/sessió de recorreguts) — clubs.agilitydoghub.com + àlies (app.agilitycanic.cat)
├── clubs-admin/  # backoffice escriptori (D1–D17 + D18 recorreguts + D19 consola) — clubsadmin.agilitydoghub.com + àlies (admin.agilitycanic.cat)
└── id/           # AgilityHub ID: login OIDC, enllaç màgic, contrasenya, compte, productes — id.agilitydoghub.com
packages/
├── ui/           # design system: tokens per club → CSS variables, components base, mapa mòdul→UI, icones
├── i18n/         # locales/{ca,es,en}/{namespace}.json, ICU, format.ts (dates/hores/diners amb fus i moneda del club)
├── api-client/   # tipus generats de l'OpenAPI (openapi-typescript) + hooks TanStack Query per recurs + errors→i18n
├── auth/         # client de tokens, storage xifrat, refresh, guards de rol/perfil, bàner d'impersonació/suport, handoff
├── course-core/  # model canònic de recorregut, parser Smarter, geometria, col·locació, JSON Schema (TS pur)
└── course-ui/    # visors 2D/3D, editor de col·locació, editor de geometria, full de muntatge, marcadors QR
```
Estructura interna per app: `src/features/<vertical>` (pantalles, hooks, tests), `components`, `routes`, `stores`, `services`, `tests`.

## 2. Design system i marca blanca (`packages/ui`)

- **Tokens per club** de `GET /branding` (S02) → CSS variables consumides per Tailwind: colors, tipografia, radis, paleta de pistes, mode fosc/clar/auto; el tema s'aplica **abans del primer render** (sense flash) i alimenta el manifest PWA dinàmic. Lint: cap color literal fora dels tokens (T-02-05). El Cànic és el tema 1; el «club mínim» usa el preset AgilityHub.
- **Mapa mòdul → UI** (`modules.ts`): rutes, pestanyes del tabbar, targetes, camps i accions condicionats pels `modules[]` de `/branding` (CATALEG_MODULS). Cap `if club === canic`.
- Components base extrets dels mockups (una sola font per a les tres apps): Button (primari/sec/ghost/mini), Chip (+ estats on/nivell/pista amb dot), Badge (ok/avís/err/neutre), Card, Input/Select/Checkbox/Radio (RHF + Radix), Modal/Drawer, Toast, **Taula universal** (filtre per columna + indicador actiu + `filter-values` + columnes reordenables + vistes desades + files per pàgina + selecció múltiple + export), Acordió (FAQ), Tabbar mòbil, Appbar, **Graella dia** (pistes × hores, cel·les mitja alçada), **Quadre setmanal** (plantilles/agenda), Stepper d'alta, Uploader (URLs signades S3), Compte enrere (SeatHold), Editor `LocalizedText` per idioma, Editor de paràmetre per tipus (D11), Selector de perfil (03b), Bàner d'impersonació.
- Icones: el set SVG dels mockups (`i-paw`, `i-cone`, `i-info`, `i-filter`…) com a components.

## 3. i18n, formats i perfil de país

`CONVENCIONS_I18N.md` és la norma: namespaces per mòdul, claus `namespace:pantalla.element`, ICU per plurals/gènere, **mai** concatenació, `format.ts` amb el `timeZone` i la `currency` del club, detecció d'idioma, selector a 12/`apps/id`, `i18next-parser` a CI (clau absent en algun idioma = vermell), linter de vocabulari prohibit sobre `ca`/`es`. Els camps de l'alta i de la fitxa es renderitzen segons `branding.countryProfile` (tipus de document, CP amb lookup, prefix telefònic, mandat SEPA).

## 4. PWA (`apps/clubs`)

- Manifest per host (nom/icones/colors del club) + service worker (Workbox): precache de la shell, network-first per a l'API, pantalla offline digna, actualització amb avís.
- **Sessió**: refresh token xifrat (WebCrypto) al storage, access token en memòria, interceptor de refresh, tancament net; token d'impersonació només en memòria de sessió + bàner persistent (S01).
- **Web push**: subscripció VAPID des de 12 en context; iOS ≥ 16.4 instal·lada; el push és millora — correu/SMS són els canals garantits.
- Objectius: Lighthouse PWA ≥ 90, TTI < 3 s en 4G, bundle inicial < 250 KB gz (code-splitting per ruta; `course-ui` 3D en chunk diferit).

## 5. Contracte amb l'API

Tipus generats de l'OpenAPI (`packages/api-client`) + hooks per recurs (`useMembers`, `useWeek`, `useMeHome`…), claus de cache per mòdul, invalidació després de mutacions, `Idempotency-Key` als POST que ho demanen, `version` als PATCH. Errors: `code` → i18n (`errors` namespace) amb el `message` del back com a fallback; `fieldErrors` als formularis; `details` per a les pantalles de límit (06/29). Mock server (Prism) a partir de l'OpenAPI perquè el front pugui avançar amb el contracte fixat.

## 6. Mapa de pantalles → rutes → specs

### `apps/clubs` (alumne per defecte; instructor rere la tria de perfil 03b)
| Mockup | Ruta | Spec | Notes |
|---|---|---|---|
| 01 / 02 | `/entrar`, `/activacio` | S01 | enllaç màgic, contrasenya opcional, «Ja hi ets» |
| 03b | `/perfil-acces` | S01 | tria recordada; admin → handoff a `clubs-admin` |
| 03 | `/inici` | S08 | `GET /me/home`; xip «Tots» com a filtre |
| 04 / 06 / 29 / 07 | `/reservar`, `/reservar/confirmar`, `/reserves/:id` | S08 | darrer gos, SeatHold amb compte enrere, swap, estats |
| 08 | `/entrenaments` | S09 (+S16 muntat) | slots, «Qualsevol», n/3 |
| 10 | `/avui` | S06 | `GET /day-grid?view=member` |
| 11 | `/notificacions` | S11 | accions natives |
| 12 / 13 / 28 | `/perfil`, `/gossos`, `/dades` | S01/S11/S03 | preferències, idioma, contrasenya; pack (S12) |
| 14 / 15 | `/inactivitat`, `/baixa` | S13 | |
| 16–19 | `/apuntat-hi/*` | S04 | pública, stepper, perfil de país, Checkout |
| 20 / 21 / 22 / 24 / 26 | `/instructor/*` | S10, S09, S16 | grups, passar llista, fitxa, pista, tasques |
| 23 | `/instructor/avui` | S06 | `view=instructor` |
| 25 | `/historic` | S10 | |
| 30 | `/info` | S11 | FAQ |
| Recorreguts (nou) | `/recorreguts/muntat/:ringId`, `/instructor/pistes/:ringId/muntat`, `/instructor/muntatge/:sessionId` | S16 | visor, registre, sessió live |
| «Aprèn amb AgilityHub» | enllaç extern | S19 | mòdul `LEARN_LINK` |
| 27 / 27b | `/estadistiques` | R2 | ruta reservada, oculta |

### `apps/clubs-admin`
`/tauler` D1 (S14) · `/preinscripcions/:id` D2 (S04) · `/plantilles` D3/D3b · `/calendari` D4/D4b/D4c (S06) · `/abonats` D5 + `/abonats/:id` D10 (S03, S12, S13) · `/gossos` D15 (S03) · `/facturacio` D6 + `/facturacio/remeses` (S12) · `/activitats` D7 (S07) · `/modalitats` D8 (S05) · `/comunicats` D9 (S11) · `/parametres` D11 (S02, S05 FAQ i nivells, S15 processos) · `/agenda` D12 · `/alumnes/:id` D13 · `/seguiment` D14 (S10) · `/pistes` D16 (+ geometria S16) · `/equip` D17 (S05) · `/recorreguts` D18 (S16) · `/inactivitats` (S13, pàgina nova) · `/consola/*` D19 (S17, només `AGILITYHUB_ADMIN`). Guard per rol (instructor: `/agenda`, `/alumnes`, `/recorreguts` lectura); «Entra com l'abonat» obre `clubs` amb token d'impersonació.

### `apps/id`
`/login` (flux OIDC amb marca del client) · `/magic-link` · `/set-password` · `/account` (dades, idioma, sessions, membresies) · `/products` (R2 selector; R1 enllaços) (S01, S19).

## 7. Ordre d'implementació (segueix les etapes; paquets = §12 de cada spec)

**E0** — monorepo + `ui` (tokens, components nucli, mapa de mòduls) + `i18n` + `api-client` (generació) + `auth` (esquelet) + shell de les tres apps amb routing i layout (tabbar/sidebar dels mockups) + CI (lint, tests, i18n-parser, vocabulari) · **fil C**: extracció de `course-core` (S16 WP-0/A) en paral·lel.
**E1** — `auth` complet + 01/02/03b + files de 12 + bàner d'impersonació + `apps/id` (S01 WP-F/D).
**E2** — Taula universal + D5/D15 + D10 + D8/D16/D17 + D11 generat del catàleg + 13/28 (S03, S05, S02).
**E3** — 16–19 (stepper amb perfil de país, uploader, consentiments, Checkout) + D2 + D1 (S04, S14).
**E4** — D3/D3b (quadre de plantilles, cobertura), D4/D4b/D4c, 10, 23, D7 (S06, S07).
**E5** — 03/04/06/29/07, 08, 24, inscripcions a activitats (S08, S09, S07).
**E6** — 20/21/22/25/26, D12/D13/D14 (S10).
**E7** — 11, 12 preferències, 30, push, D9 (S11).
**E8** — D6 + remeses + calaix de rebut, 12/rebuts, bloc D10, pack a 13, 14/15, pàgina d'inactivitats (S12, S13).
**E9** — `course-ui` + D18 + geometria a D16 + visor/registre/sessió mòbil + integracions (S16).
**E10** — D19 consola + assistent (S17).
**E11** — polit transversal: estats buits/carregant/error a tot arreu, accessibilitat (focus, contrast AA, labels), Lighthouse, es/en revisats, E2E dels 4 fluxos crítics + sessió de muntatge, QA amb el Josep contra staging.

## 8. Qualitat i verificació

- Tests: unitaris de components amb lògica (Taula universal, stepper, límits/SeatHold, quadres, editor de paràmetre, `course-ui`) + E2E Playwright (alta, reserva amb espera, passar llista, remesa simulada, muntatge de recorregut) contra staging seed.
- **Fidelitat als mockups** (`pantalles/*.png` al costat): cada pantalla es revisa contra el mockup abans de tancar el PR; divergències anotades a la spec (§13) i validades amb Jordi; pantalles sense mockup → captura a staging per al Josep.
- Linter de vocabulari sobre els JSON d'i18n; i18n-parser; lint de colors; bundle-size a CI.
- Sentry + source maps a prod; analítica mínima (esdeveniments de reserva) només si el club ho demana.
