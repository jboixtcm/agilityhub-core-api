# ADR-012 — Mòduls activables i regles configurables per club (marca blanca funcional)

**Estat:** Acceptada
**Data:** 2026-09-03
**Decisors:** Jordi (amb assistència IA)

## Context

Els mockups V8/V7 descriuen el que el **Cànic** necessita. Un altre club «pot tenir un altre look and feel o voler més/menys funcionalitats» (Jordi 03-09). ADR-002 ja fa que catàlegs, tema i paràmetres siguin dades; falten dues coses: **activar o desactivar blocs sencers** per club i **variants de regla** que no són un simple número (un instructor per classe, llista d'espera «tothom alhora», límits per gos, nivells obligatoris). Decisió de Jordi: tots els mòduls activables des de l'MVP; les quatre regles llistades es generalitzen ara amb el valor del Cànic per defecte.

## Opcions considerades

1. **Mòduls (flags) + regles com a paràmetres tipats, a CLUB/PARAMETRE** — un sol mecanisme, avaluat al back (guard) i al front (navegació/rutes), amb valors per defecte de producte.
2. Codi específic per club (branques/condicionals `if canic`) — descartat: és exactament l'adhoc que la marca blanca vol evitar.
3. Feature flags amb servei extern (LaunchDarkly, Unleash) — sobredimensionat; les nostres flags són configuració de negoci, no *rollout*.

## Decisió

**Opció 1.**

### Mòduls (`CLUB.modules`: set de claus actives; defaults de producte)

| Clau | Què activa | Cànic |
|---|---|---|
| `FREE_TRAINING` | Entrenaments lliures: pantalla 08, slots, dret per nivell/gos, pantalla 24, «Pot entrenar sol» | on |
| `BILLING` | Rebuts, remesa/cobraments, D6, proper rebut, quotes; sense ell el cens no té tarifa ni IBAN a l'alta | on |
| `PACKS` | Modalitats de tipus pack, CONSUM_PACK, comptadors | on |
| `SINGLE_CLASS` | Modalitat «classe individual» (càrrec per assistència o pagament a l'acte) | off |
| `ACTIVITIES` | D7 + inscripcions a activitats | on |
| `FAMILY_GROUP` | Pas 18 de l'alta, gossos del grup al selector, pagador únic | on |
| `WAITLIST` | Llista d'espera (mode segons regla) | on |
| `TASKS` | Tasques, notes als instructors, observacions, D14 | on |
| `FAQ` | Pantalla 30 + manteniment a D11 | on |
| `SMS` | Canal SMS a la matriu de plantilles (Twilio) | on |
| `PUSH` | Web push | on |
| `INACTIVITY` | Pantalla 14 i quota d'inactivitat | on |
| `COURSES` | Recorreguts: biblioteca, Smarter, rings, col·locació, recorregut muntat, muntatge | on (R1) |
| `LEARN_LINK` | Entrada «Aprèn amb AgilityHub» a l'app (enllaç a Learn; a R2, contingut per nivell) | on |
| `STATS` / `SOCIAL_LEAGUE` | Pantalles 27/27b (fase 2) | off |

Regles d'aplicació:
- **Back**: anotació `@RequiresModule("X")` als controllers/handlers → `404 MODULE_DISABLED` (no 403: el recurs «no existeix» per a aquest club). Els processos programats comproven el mòdul abans d'actuar. Els tests d'integració inclouen «mòdul desactivat».
- **Front**: `GET /branding` retorna `modules[]`; rutes, pestanyes del tabbar, targetes del tauler i camps de formulari es munten des d'un mapa mòdul→elements (un sol lloc per app). Les pantalles no fan `if club === canic`.
- **Dependències**: `PACKS` i `SINGLE_CLASS` requereixen `BILLING`; `INACTIVITY` requereix `BILLING` per a la quota (sense `BILLING`, la inactivitat només bloqueja reserves). La consola valida les dependències en activar.
- **Desactivar un mòdul amb dades** no esborra res: les dades queden i les pantalles desapareixen.

### Regles configurables (PARAMETRE tipat, valor per defecte = Cànic)

| Clau | Tipus | Valors | Cànic |
|---|---|---|---|
| `classes.maxInstructorsPerClass` | int | 1..n | **1** — CLASSE i classe de plantilla porten `instructorIds[]`; amb 1, la UI mostra un únic selector; incoherència = mateix instructor a dues classes solapades |
| `waitlist.mode` | enum | `ALL_AT_ONCE` (s'avisa tothom, plaça per a qui confirma primer) · `FIFO` (s'avisa el primer amb `waitlist.fifoConfirmMinutes` per confirmar; després el següent) | **ALL_AT_ONCE** |
| `bookings.limitUnit` | enum | `DOG` (el comptador setmanal és per gos) · `MEMBER` (per persona, sumant tots els gossos) | **DOG** |
| `levels.enabled` | bool | `false` = les classes no restringeixen per nivell, la cobertura per nivell i el gràfic D1 s'amaguen, `GOS.nivell` és opcional, «dret a entrenament lliure» només manual | **true** |

Cada regla té: valor per defecte de producte, override per club, històric de canvis (ja previst a PARAMETRE), test de les dues (o n) branques al DoD.

### Què NO es generalitza ara
Els fluxos i les pantalles (stepper d'alta, quadres, calendari) són els dels mockups per a tots els clubs; la variació és de tema (tokens + mode clar/fosc), textos, catàlegs, mòduls i regles. Peticions d'un club futur que no encaixin es converteixen en mòdul o regla nous, mai en codi d'un club.

## Conseqüències

- Cada spec (`05-desenvolupament/specs/Sxx`) declara els mòduls i regles que la toquen; el catàleg viu a `specs/00-transversal/CATALEG_MODULS.md` i `CATALEG_PARAMETRES.md`.
- El seed del Cànic activa tots els mòduls llevat de `SINGLE_CLASS`, `STATS`, `SOCIAL_LEAGUE`.
- Una mica més de codi (guards, mapes mòdul→UI, branques de regla testejades) a canvi que el segon club sigui configuració.
