# ADR-004 — Autenticació i gestió d'identitat

**Estat:** Acceptada · **ampliada per ADR-010 (2026-09-03)**: el compte és global (AgilityHub ID, `id.agilitydoghub.com`) amb membresies per club; el grant d'enllaç màgic és un grant OAuth2 personalitzat; Learn s'hi federa sense canviar contrasenyes; les apps first-party usen el token endpoint (R1) i el flux OIDC de redirecció arriba a R2. Els mecanismes descrits aquí (JWT, refresh lliscant, contrasenya opcional, impersonació auditada) es mantenen.
**Data:** 2026-09-02
**Decisors:** Jordi (amb assistència IA)

## Context

Q-06 i el model §USUARI fixen el comportament: **enllaç màgic per correu** com a via principal, contrasenya **opcional** establerta des de dins l'app, **sessió persistent 30 dies lliscants**, rols alumne/instructor/administrador sobre un mateix compte amb **tria de perfil recordada** (pantalla 03b), i accions «Entra com l'abonat» auditades. Avanta ja té en producció un OAuth2 Authorization Server amb JWT que cobreix password grant, magic links i refresh tokens.

## Opcions considerades

1. **Replicar el mòdul d'auth d'Avanta** (Spring Authorization Server + JWT + magic links) — provat en producció, mateix codi de referència, cap dependència externa.
2. Servei extern (Firebase Auth / Auth0 / Supabase) — delegació còmoda però una dependència i un cost nous, i el flux d'enllaç màgic + sessió lliscant + suplantació auditada s'acaba fent a mida igualment.
3. Sessions de servidor clàssiques — encaixa malament amb dues SPAs + PWA offline-friendly.

## Decisió

**Opció 1**, adaptada al multi-tenant:

- JWT amb claims: `userId`, `clubId`, `rols`, `abonatId`/`instructorId` segons rol; refresh token amb caducitat lliscant de 30 dies que s'estén amb l'ús.
- **Enllaç màgic**: token d'un sol ús per correu (N-02 a l'activació de l'alta i per a cada accés sense contrasenya); la mateixa pantalla serveix per restaurar contrasenya («Ja hi ets»).
- **Contrasenya opcional** (hash bcrypt/argon2): establir-la i canviar-la només amb sessió iniciada.
- **Perfil per defecte**: última tria de perfil desada per usuari (03b) i retornada al login.
- **«Entra com l'abonat»**: token d'impersonació emès a l'admin amb claim `actorUserId` + `abonatSuplantatId`; tota escriptura feta amb aquest token s'audita amb els dos identificadors (model §AUDITORIA) i marca origen «backoffice».
- Autorització per capes: anotacions de rol als controllers + comprovació de pertinença al `clubId` del recurs a la capa de servei.

## Conseqüències

- Cap dependència d'identitat externa; el cost és mantenir el mòdul, ja amortitzat a Avanta.
- L'SMS no intervé en l'auth (només notificacions), així que Twilio no és crític per al login.
- Els enllaços màgics obliguen a tenir el proveïdor d'email transaccional (ADR-005) resolt a la fase 1 — és la primera integració externa del calendari.
