# ADR-008 — Estratègia de migració de dades des de PlayOff Entidades

**Estat:** Acceptada (detall operatiu a `05-desenvolupament/specs/S18-migracio-playoff.md`)
**Data:** 2026-09-03
**Decisors:** Jordi (amb assistència IA)

## Context

El Cànic gestiona avui el cens, les quotes/remeses SEPA, les classes i les reserves d'entrenament a PlayOff Entidades (instància de **producció amb dades personals reals: només lectura**). Cal passar al nou sistema sense perdre números d'abonat, sense duplicar persones (a Playoff la fitxa és persona + un gos, i el multi-gos es fa duplicant fitxes) i sense que ningú hagi de tornar a donar-se d'alta. Restriccions: RGPD (cap dada real fora de producció), tall net entre l'última remesa de Playoff i la primera del nou sistema, i marxa enrere possible abans d'obrir.

## Opcions considerades

1. **Exports de llistats (lectura) → anonimització determinista → eina pròpia de mapatge/validació/càrrega idempotent → conciliació → tall un diumenge** — control total del desdoblament persona/gos, assajos segurs a staging, informe per al club.
2. Importadors genèrics CSV del backoffice nou — no resolen el desdoblament ni la conciliació; descartada.
3. Migració «viva» amb doble escriptura o integració amb Playoff — impossible (Playoff és només lectura) i innecessària.

## Decisió

**Opció 1.** Eina `migration:playoff` al core (`migration/`): mapatge YAML versionat (tipologies → modalitats, nivells → gos, estats), regles de desdoblament (persona per DNI; gos per fitxa; inferències marcades), números d'abonat conservats, mandats migrats (o nous amb `FRST`), rebuts històrics de 24 mesos com a sèrie `PLAYOFF` (`kind = MIGRATED`), packs en curs, comptes AgilityHub sense correu durant la càrrega i benvingudes per lots el dia del go-live; `sourceIds` a tots els destins per a la idempotència; anonimitzador (`migration:anonymize`) per als assajos; conciliació amb l'«Informe de previsión» (≤ 1 %); tall: D-7 assaig a staging amb el Josep, D-1 Playoff en lectura, D0 export final → càrrega a producció → conciliació → DNS/app → benvingudes; Playoff consultable 3 mesos.

## Conseqüències

- Cap dada personal real a staging ni als repos; els exports es custodien xifrats fora del Dropbox i s'esborren als 30 dies.
- Els números d'abonat i l'històric de rebuts es mantenen; la numeració nova comença a `{YYYY}-0001` (sèrie diferent, sense col·lisió).
- La primera remesa la genera el nou sistema el mes següent al tall, supervisada i amb retrocés.
- Dubtes que resolen el Josep i la comptabilitat (tipologies vives, mandats, IVA, semestres) són a S18 §13 i S12 §13.
