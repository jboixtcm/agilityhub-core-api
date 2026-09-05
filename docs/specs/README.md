# Especificacions per vertical — índex

**v1.0 · 03-09-2026** · Una spec per vertical, escrita amb `00-transversal/PLANTILLA_SPEC.md`. Són **el document que es carrega a cada sessió de vibe-coding** juntament amb les pantalles implicades (`03-disseny/mockups/pantalles/`) i els catàlegs transversals. On una spec i el model discrepin, mana el model (`MODEL_DADES_CANIC.md` v1.6 + `MODEL_DADES_PLATAFORMA.md`); on la spec i els mockups discrepin en comportament d'UI, manen els mockups i s'anota a la spec.

## Transversals (`00-transversal/`)
| Fitxer | Contingut |
|---|---|
| `CONVENCIONS_API.md` | base, auth/tenant, rutes, llistats universals, formats, errors, idempotència, esdeveniments, seguretat, tests mínims |
| `MATRIU_PERMISOS.md` | rols × recursos |
| `CATALEG_MODULS.md` | mòduls per club i què activa cadascun (ADR-012) |
| `CATALEG_PARAMETRES.md` | totes les claus de paràmetre amb tipus i valor del Cànic |
| `CATALEG_NOTIFICACIONS.md` | N-01…N-38: esdeveniment, categoria, públics, canals, variables, acció |
| `CATALEG_ESDEVENIMENTS.md` | esdeveniments de domini (outbox) i consumidors |
| `CONVENCIONS_I18N.md` | claus, ICU, formats, fus horari, contingut localitzat, perfil de país (ADR-011) |
| `CATALEG_ERRORS.md` | tots els `ErrorCode` amb l'estat HTTP canònic, per spec |
| `PLANTILLA_SPEC.md` | esquelet i regles d'escriptura |

## Verticals
| Spec | Vertical | Etapa | Mòduls | Pantalles |
|---|---|---|---|---|
| `S01-agilityhub-id-i-acces.md` | AgilityHub ID: comptes, membresies, enllaç màgic, OIDC, impersonació, federació de Learn | E1 | — | 01, 02, 03b, 12 (accés), `apps/id` |
| `S02-club-configuracio-i-parametres.md` | CLUB, tema, dominis, mòduls, perfil de país, paràmetres amb històric, `/branding` | E0/E2 | tots | D11 (paràmetres) |
| `S03-cens-abonats-gossos.md` | Abonats, gossos, grup familiar, documents, fitxa D10, llistats universals, bloqueig, rols | E2 | `FAMILY_GROUP`, `TASKS` | D5, D10, D15, 13, 28 |
| `S04-alta-publica-i-validacio.md` | Alta 16–19, validació D2, consentiments, pagament inicial | E3 | `BILLING`, `PACKS`, `FAMILY_GROUP` | 16, 17, 18, 19, D2 |
| `S05-catalegs.md` | Nivells, pistes, instructors/admins, modalitats i tarifes, FAQ | E2 | `FREE_TRAINING`, `PACKS`, `SINGLE_CLASS`, `FAQ` | D8, D16, D17, D11 (FAQ) |
| `S06-planificacio-setmanes-i-calendari.md` | Plantilles, generació, validació, calendari, anul·lació amb inscrits, bloquejos, quadres del dia | E4 | `WAITLIST` (recompte), `COURSES` (cel·la) | D3, D3b, D4, D4b, D4c, 10, 23 |
| `S07-activitats.md` | Activitats (D7), inscripcions, bloqueig de pistes, web pública | E4/E5 | `ACTIVITIES` | D7, 04 (bloc), 03/25 |
| `S08-reserves-de-classes-i-llista-espera.md` | Reservar, confirmar amb bloqueig temporal, límits, swap, anul·lació, llista d'espera (2 modes) | E5 | `WAITLIST`, `PACKS`, `SINGLE_CLASS` | 03, 04, 06, 29, 07 |
| `S09-entrenaments-lliures.md` | Slots, dret per nivell/gos, reserva, «Qualsevol», comptador, reserva/bloqueig d'instructor | E5 | `FREE_TRAINING` | 08, 24 |
| `S10-assistencia-tasques-i-seguiment.md` | Grups del dia, passar llista, fitxa d'alumne, tasques/adjunts, seguiment D14, agenda D12, històric | E6 | `TASKS` | 20, 21, 22, 25, 26, D12, D13, D14 |
| `S11-comunicacions.md` | Plantilles amb matriu, notificacions i log, preferències, email/SMS/push, comunicats massius, FAQ (pantalla) | E7 | `SMS`, `PUSH`, `FAQ` | 11, 12 (preferències), 30, D9 |
| `S12-facturacio-i-pagaments.md` | Rebuts, simulació/remesa SEPA/retrocés, Stripe, manual, packs, classe individual, cobraments anticipats, exports | E8 | `BILLING`, `PACKS`, `SINGLE_CLASS` | D6, D8 (preus), 13 (pack) |
| `S13-inactivitat-i-baixa.md` | Períodes d'inactivitat, sol·licitud de baixa, efectes sobre reserves i quota | E8 | `INACTIVITY`, `BILLING` | 14, 15, D10 |
| `S14-tauler-auditoria-exportacions-rgpd.md` | D1, auditoria, exports, drets RGPD (accés, supressió per pseudonimització) | E3 + transversal | — | D1 |
| `S15-processos-programats.md` | Obertura de setmana, revisió 7:30, no presentats 8:00, recordatoris, venciments, FIFO, caducitats, neteja | E5–E8 | segons procés | — |
| `S16-recorreguts-rings-i-muntatge.md` | course-core, biblioteca, Smarter, geometria de rings, col·locació, recorregut muntat, sessions de muntatge, inventari | E9 | `COURSES` | D18 (nova), 08/10/23 (integració), visor mòbil |
| `S17-consola-de-clubs.md` | Super-admin: crear club, clonar catàlegs, dominis, mòduls, proveïdors, checklist d'onboarding, ús | E10 | — | D19 (nova) |
| `S18-migracio-playoff.md` | Exports, mapatge, deduplicació, càrrega validada, tall | E12 | — | — |
| `S19-integracio-learn.md` | SSO fase 2, selector de productes, contingut per nivell, challenges (contracte) | R2 | `LEARN_LINK` | — |
| `S20-ar-vr-spike.md` | Spike de viabilitat AR/VR i requisits d'API | R3 | `COURSES` | — |

## Com s'usa en una sessió

1. Obrir la spec del vertical + `CONVENCIONS_API.md` + les pantalles implicades (HTML per al microcopy, PNG per a la referència visual).
2. Si la sessió és de back: també `MODEL_DADES_PLATAFORMA.md` §0 (glossari) i les seccions del model v1.6 citades a la spec.
3. Si és de front: `PLA_FRONTEND.md` §2 (design system) i `CONVENCIONS_I18N.md`.
4. Tancar la sessió amb el paquet de feina (§12 de la spec) verificable i els tests `T-xx-nn` en verd.
