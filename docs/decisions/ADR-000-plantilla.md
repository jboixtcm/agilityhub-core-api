# ADR-000 — Plantilla de decisió d'arquitectura

> Copieu aquest fitxer com a `ADR-nnn-titol-curt.md`, ompliu-lo i no l'esborreu mai: si una decisió canvia, es crea un ADR nou que substitueix l'anterior (i s'hi enllaça).

**Estat:** Proposada | Acceptada | Substituïda per ADR-nnn
**Data:** AAAA-MM-DD
**Decisors:** …

## Context
Quin problema o elecció tenim davant, i per què ara. Restriccions rellevants (pressupost, equip, terminis, RNF de l'especificació).

## Opcions considerades
1. Opció A — pros / contres
2. Opció B — pros / contres
3. Opció C — pros / contres

## Decisió
Què triem i el raonament principal.

## Conseqüències
Què implica (bo i dolent): cost, manteniment, dependències, reversibilitat, impacte sobre requisits concrets (RF/RNF).

---

## Decisions pendents d'obrir (llista inicial)

| ADR | Tema | Notes |
|---|---|---|
| ADR-001 | Stack tecnològic (backend + frontend) | Considerar l'experiència de l'equip |
| ADR-002 | Base de dades i model multi-tenant | RNF-07: separació per club des del dia 1 |
| ADR-003 | Hosting i entorns (dev/staging/prod) | RNF-06: pic de concurrència diumenge 20:00 |
| ADR-004 | Autenticació i gestió d'identitat | Depèn de Q-06 |
| ADR-005 | Proveïdor d'email transaccional | RF-NOT: volum baix, tracking d'obertura opcional |
| ADR-006 | Generació del fitxer SEPA (pain.008) | Llibreria vs implementació pròpia |
| ADR-007 | Web responsive/PWA vs app nativa | Resolta dins ADR-001 (PWA; Capacitor si cal) |
| ADR-008 | Estratègia de migració de dades des de Playoff | **Acceptada 03-09** (detall a S18) |
| ADR-009 | Pagaments multi-proveïdor (SEPA · Stripe · manual) | **Acceptada 03-09** |
| ADR-010 | AgilityHub ID (compte únic, OIDC, federació de Learn) | **Acceptada 03-09** |
| ADR-011 | i18n i localització (idiomes, fus, moneda, perfil de país) | **Acceptada 03-09** |
| ADR-012 | Mòduls activables i regles configurables per club | **Acceptada 03-09** |
| ADR-013 | Plataforma de recorreguts (course-core, rings, muntatge) | **Acceptada 03-09** (pendent de verificar el web-planner) |
| ADR-014 | Muntatge de pistes amb AR o VR | S'obre amb el spike (S20, R3) |
