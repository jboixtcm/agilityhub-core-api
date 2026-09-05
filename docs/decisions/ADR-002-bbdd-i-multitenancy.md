# ADR-002 — Base de dades i model multi-tenant (marca blanca)

**Estat:** Acceptada · **ampliada el 2026-09-03 (2a sessió)** per ADR-010 (COMPTE global + MEMBRESIA substitueixen USUARI dins del club), ADR-011 (CLUB amb `locales`, `defaultLocale`, `timeZone`, `currency`, `countryProfile`; textos de catàleg com a `LocalizedText`), ADR-012 (`CLUB.modules` + regles configurables) i ADR-013 (col·leccions del mòdul `courses`, amb propietari club/AgilityHub/compte). Les col·leccions **globals** (ACCOUNT, CLUB, OIDC_CLIENT, COURSE públics d'AgilityHub, AGILITYHUB_LEVEL) es marquen explícitament com a tals a la façana de persistència; tota la resta porta `clubId`.
**Data:** 2026-09-02
**Decisors:** Jordi (amb assistència IA)

## Context

El producte es construeix pel Cànic però amb vocació de **marca blanca** sota AgilityHub: les necessitats dels clubs d'agility són similars i afegir un club ha de ser barat. El model de dades v1.6 ja té l'entitat CLUB «multi-tenant preparat» (RNF-07) i una taula PARAMETRE per a tota la configuració operativa. Motors sobre la taula: MySQL o MongoDB.

## Opcions considerades

1. **MongoDB + multi-tenant real (una BBDD, discriminador `clubId`)** — pros: mateix motor i mateixos patrons que Avanta (Spring Data Mongo, repos two-tier, Testcontainers); afegir un club = un registre CLUB, cap desplegament; agregacions per a quadres i cobertura; transaccions multi-document per a remeses. Contres: la integritat referencial és responsabilitat del codi; disciplina obligatòria del filtre `clubId`.
2. **MySQL + multi-tenant** — pros: FK reals, SQL per a informes; contres: patrons nous respecte d'Avanta (JPA, Flyway), més fricció d'arrencada, i l'experiència AgilityHub Learn (MySQL) inclou taules fora de migracions — no és un avantatge net.
3. **Single-tenant parametritzat (una instància per club)** — pros: aïllament total; contres: cost operatiu per club (deploy, backups, versions), just el que la marca blanca vol evitar.
4. **Adhoc Cànic i generalitzar després** — refactor car garantit; descartada.

## Decisió

**MongoDB amb multi-tenant real** (opció 1):

- **`clubId` a totes les col·leccions** de dades de negoci, sempre dins d'**índexs compostos** (`clubId + …`) i aplicat de manera transversal (claim `clubId` al JWT + injecció a la capa de persistència perquè cap query pugui oblidar-lo).
- **CLUB** concentra la identitat i configuració del tenant: nom, NIF, adreça, logo i **tokens de tema** (colors, tipografia), dades SEPA del creditor, sèrie de rebuts, idiomes actius, dominis. **PARAMETRE** (per club) porta tota la resta de regles operatives — el Cànic n'és el primer joc de valors, cap valor de negoci hardcoded.
- **Catàlegs per club**: NIVELL, PISTA, MODALITAT/TARIFA, PLANTILLA_COMUNICAT, FAQ… tot mantenible des del backoffice; les 5 pistes o els nivells Cadells–G del Cànic són *dades*, no codi.
- **Els nivells són lliures per club** (èmfasi Jordi 03-09): cada club crea els nivells que vulgui per organitzar els seus grups d'alumnes, amb **nom lliure**, ordre, color, aforament i estat actiu. Cap regla de negoci no pot citar un nivell pel seu literal («D», «Cadells»): tota regla que avui el Cànic expressa amb un nivell concret es modela com a **atribut del nivell** (p. ex. «dona dret a entrenament lliure», que al Cànic s'activa a partir de D) o com a **paràmetre del club que referencia un nivell per id**. El mateix criteri val per a la resta de catàlegs.
- **Resolució del tenant per domini**: cada club apunta els seus dominis (p. ex. `app.agilitycanic.cat`, `admin.agilitycanic.cat`) al mateix desplegament; el host resol el CLUB (branding, idiomes) abans del login, i el JWT el fixa després.
- **Transaccions**: MongoDB replica set (requerit) amb transaccions multi-document als fluxos crítics (remesa SEPA, validació de setmana, consolidació de plaça des de la llista d'espera). Moviments de facturació **append-only** (immutables per disseny, BR-12/BR-14).
- **Aïllament**: cap endpoint no accepta `clubId` del client; sempre surt del token o del domini. Els tests d'integració inclouen casos de fuga entre tenants.

## Conseqüències

- Afegir el club N = alta de CLUB + catàlegs + dominis. Cap canvi de codi si el club encaixa amb els paràmetres existents; les peticions noves es converteixen en paràmetres nous. El producte incorpora la **gestió de clubs** (consola interna al MVP; pantalla «Nou club» + super-admin AgilityHub post-MVP): un club té logo, colors corporatius, textos i catàlegs propis — el SAAS creix club a club (o venue a venue) sense desplegaments.
- El codi és una mica més cerimoniós (filtre de tenant a tot arreu), a canvi d'un sol desplegament i una sola versió per a tots els clubs.
- Backups i migracions de dades són globals; una restauració per a un sol club requereix eines pròpies (assumit, documentat al pla).
- La v1 es desplega amb el Cànic com a únic tenant actiu: el multi-tenant no s'ha de «notar» fins al segon club.
