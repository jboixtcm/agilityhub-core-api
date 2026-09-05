# Metodologia de vibe-coding — com es construeix cada paquet

**v1.0 · 03-09-2026** · Per a Jordi + Claude Code / Cowork amb 2–3 fils en paral·lel · Complementa `PLA_DESENVOLUPAMENT.md` §5–6

## 1. Unitat de treball: el paquet (WP-xx-y)

Cada spec (`specs/Sxx`) té a §12 els seus paquets de feina amb dependències i lliurable verificable. **Una sessió = un paquet** (o menys). Mai «fes la vertical sencera». L'ordre dins d'un vertical és sempre: **Contracte → Back → Front → Integració amb seed**.

## 2. Guió d'una sessió (el prompt d'arrencada)

Carrega, i només això:
1. `specs/00-transversal/CONVENCIONS_API.md` (+ `CONVENCIONS_I18N.md` si és front, `MATRIU_PERMISOS.md` si toca autorització).
2. La spec del vertical (`specs/Sxx-*.md`) — sencera.
3. Les pantalles implicades: `03-disseny/mockups/pantalles/{mobil|escriptori}/NN-*.html` (microcopy i anotacions) i el `.png` (visual).
4. Si és back: `04-arquitectura/MODEL_DADES_PLATAFORMA.md` §0 (glossari) + les seccions del model citades a la spec. Si és front: `PLA_FRONTEND.md` §2.
5. El `CLAUDE.md` del repo (context permanent) i la skill del repo (`core-api-dev` / `core-web-dev`).

Prompt tipus:
> «Paquet **WP-08-B (reserves, back)** de la spec S08. Implementa les regles R-08-01…R-08-11 i els endpoints de §6 que hi corresponen, amb els tests T-08-01…T-08-18 i els d'aïllament/rols/mòdul-off. Emet els esdeveniments de §7. No inventis paràmetres: si en falta un, para i proposa'l. Quan acabis: `mvn verify` en verd, OpenAPI regenerat, CHANGELOG, i un resum no tècnic de 5 línies.»

Regles d'or a cada sessió:
- **Paràmetres, esdeveniments, notificacions i errors només del catàleg**; una clau nova = proposta al final de la sessió + entrada al catàleg al mateix PR (mai inline).
- **Literals d'UI = els del mockup** (`ca`); `es`/`en` al mateix PR.
- **Cap literal del Cànic al codi**; si la spec ho diu com a exemple, és el valor del paràmetre.
- Tests **abans** de donar el paquet per acabat; els noms de test citen `T-xx-nn`.
- Si la spec i el model discrepen → mana el model, s'anota a la spec (§13 o «Canvis»).
- Si un detall no és a cap lloc → assumpció explícita al PR + §13 de la spec; no s'inventa en silenci.

## 3. Fils en paral·lel (2–3)

| Fil | Què | Com s'evita el xoc |
|---|---|---|
| A · Back del nucli | paquets back de S03→S13 en l'ordre de les etapes | és qui **fixa el contracte** (paquet «Contracte» primer) |
| B · Front del club | paquets front del vertical que A ja té contractat | treballa contra el mock (Prism) de l'OpenAPI i s'integra quan A tanca |
| C · Plataforma i recorreguts | S01, S16 (course-core → courses), S11 motor, S17, hardening | contextos separats (`identity`, `courses`, `platform`) → PR sense conflictes amb A |

Branques `feat/Sxx-WP-y-nom`; PR petits; `main` sempre desplegable a staging. Els paquets «Contracte» es fusionen **abans** que ningú hi construeixi a sobre. Cada fil obre com a màxim **un** paquet alhora.

## 4. Skills i context permanent (es creen a E0)

- `core-api-dev` (adaptació d'`avanta-spring-dev`): convencions Avanta + contextos + `TenantRepository` + outbox + `@RequiresModule` + `@Audited` + com escriure tests amb `AbsTestContainer`, builders del seed i asserts d'outbox/auditoria.
- `core-web-dev`: monorepo, tokens, mapa de mòduls, i18n/ICU, api-client, patrons de la Taula universal i dels quadres, fidelitat al mockup.
- `core-feature-workflow` (adaptació d'`avanta-feature-workflow`): branca per paquet, plantilla de PR (spec, regles, tests, assumpcions, i18n, CHANGELOG), checklist DoD.
- `CLAUDE.md` de cada repo: enllaça les specs (ruta al Dropbox o còpia al repo — decisió: **còpia sincronitzada de `specs/` i `pantalles/` dins del repo** a `docs/`, perquè el context viatgi amb el codi i CI pugui validar `T-xx-nn`; el Dropbox segueix sent la font per a Josep).

## 5. Definition of Done d'un paquet

Codi + tests en verd (CI) · tenant/rols/mòdul-off coberts · esdeveniments + auditoria del catàleg · i18n ca/es/en · pantalla revisada contra el mockup (captura al PR) · OpenAPI i tipus regenerats · catàlegs actualitzats si hi ha claus noves · CHANGELOG · resum no tècnic · Jordi revisa i fusiona.

## 6. Revisió setmanal (divendres, 30 min)

Etapes tancades (`✅` al pla) · paquets oberts per fil · assumpcions noves als §13 (les que cal preguntar al Josep s'agrupen en un sol correu) · riscos · retallades si cal (ordre de `PLA_DESENVOLUPAMENT.md` §8). Les specs s'actualitzen amb la secció «Canvis» (data + què) — mai es reescriuen en silenci.

## 7. Quan una sessió es talla (límit de context o d'ús)

El paquet és la unitat de recuperació: la branca + la spec + els tests fets diuen on s'era. En reprendre: llegir el PR obert (descripció + diff), executar els tests, continuar pel primer `T-xx-nn` vermell. No es reescriu res que ja passi tests.
