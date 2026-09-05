# ADR-010 — AgilityHub ID: compte únic, membresies i federació de Learn

**Estat:** Acceptada (amplia ADR-004, que segueix vigent per als mecanismes d'accés del club)
**Data:** 2026-09-03
**Decisors:** Jordi (amb assistència IA)

## Context

La visió (`VISIO_PLATAFORMA_AGILITYHUB.md`) demana que **un usuari tingui unes soles credencials i canviï de producte «estil Google»** entre Learn (en producció, Laravel), Clubs (aquest projecte) i la futura app AR. Restriccions explícites de Jordi: **els usuaris de Learn no han de canviar de contrasenya** i la feina a Learn ha de ser mínima. El model v1.6 té USUARI dins del club (`clubId`), cosa que impedeix la mateixa persona en dos productes o dos clubs.

## Opcions considerades

1. **Mòdul `identity` dins del core (Spring Authorization Server, OIDC)** — pros: reaprofita l'auth d'Avanta (password grant, magic link, refresh), cap component nou a operar, extraïble a servei propi més endavant, login page pròpia amb marca (AgilityHub o del club per domini); contres: som nosaltres qui mantenim l'IdP.
2. **Keycloak self-hosted** — pros: OIDC, social login, MFA i consola de fàbrica, importa hashes bcrypt; contres: un servei més a operar i temar, enllaç màgic com a extensió, corba nova.
3. **Learn (Laravel Passport) com a IdP** — pros: zero migració; contres: tota la vertical depèn de l'app Laravel i del seu model d'usuari.

## Decisió

**Opció 1.** El core incorpora el context delimitat `identity` amb aquestes peces:

### Model
- **ACCOUNT** (global, sense `clubId`): `email` (únic, clau universal), `passwordHash` (opcional; bcrypt — accepta `$2y$` de Laravel), `name`, `locale`, `avatar`, `status`, `platformRoles[]` (`AGILITYHUB_ADMIN`), `externalIds` (`learnUserId`), `createdAt`, `lastLoginAt`.
- **MEMBERSHIP**: `accountId` × `clubId` → `roles[]` (`MEMBER`, `INSTRUCTOR`, `ADMIN`), `memberId` (ABONAT), `instructorId`, `defaultProfile` (tria 03b recordada), `status`. Un compte pot tenir membresies a diversos clubs.
- **Tokens**: enllaç màgic d'un sol ús (caducitat curta), refresh tokens 30 dies lliscants per dispositiu, tokens d'impersonació.
- **OIDC clients** registrats: `clubs-app`, `clubs-admin` (first-party, públics amb PKCE), `learn` (confidencial), `ar-app` (natiu, PKCE).

### Fluxos
- **Apps first-party de Clubs (R1)**: usen directament el **token endpoint** amb els grants `password` i `urn:agilityhub:grant:magic-link` (grant type personalitzat) + `refresh_token`. Així les pantalles 01/02 viuen dins la PWA, com als mockups. El context de club surt del **host** (domini → club); el JWT porta `sub` (accountId), `clubId`, `roles[]`, `memberId`, `instructorId` i, en impersonació, `actorAccountId` + `impersonatedMemberId` (ADR-004).
- **Altres productes (Learn fase 2, app AR)**: **authorization code + PKCE** amb la pantalla de login a `id.agilitydoghub.com` (`apps/id`: login, enllaç màgic, contrasenya, compte, selector de productes). La sessió al domini de l'ID és el que dona el «canvi de producte estil Google». Quan Clubs passi al flux de redirecció (R2), l'SSO és complet.
- **Alta al club**: si l'email existeix a ACCOUNT, s'hi afegeix la MEMBERSHIP; si no, es crea el compte (sense contrasenya; entra per enllaç màgic).

### Federació de Learn — sense canviar contrasenyes
1. **Importació**: script que llegeix `users` de Learn (`id, email, password, name, locale, created_at`) i crea ACCOUNTs amb el mateix hash (`externalIds.learnUserId`). Es prova primer contra un export a staging. Conflictes (mateix email amb compte ja creat pel club): es fusionen conservant el hash de Learn.
2. **Learn fase 1 (invisible per a l'usuari, ~2 dies)**: `LoginController` → `POST id.agilitydoghub.com/oauth2/token` (grant password) en lloc d'`Auth::attempt()` local; si OK → `Auth::login($userLocal)`. Registre i canvi/recuperació de contrasenya → primer a l'ID, després al local. Nova columna `users.agilityhub_account_id`. Les sessions obertes no es tallen. Rollback: tornar a `Auth::attempt()` (els hashes segueixen a Learn).
3. **Learn fase 2 (R2)**: botó «Continua amb AgilityHub» (OIDC), sessió compartida i selector de productes.

### Seguretat
- Contrasenya opcional (bcrypt/argon2id per a les noves); enllaç màgic token aleatori de 32 bytes, un sol ús, 15 min; rate limit a `/oauth2/token`, `/auth/magic-link` i alta pública; bloqueig progressiu per intents.
- Els tokens d'impersonació caduquen en 1 h i no es poden refrescar.
- El compte només guarda identitat i membresies; les dades personals del club queden al tenant (RGPD: la política d'AgilityHub cobreix el compte; la del club, les seves dades).

## Conseqüències

- El model v1.6 §USUARI queda **substituït** per ACCOUNT + MEMBERSHIP (`MODEL_DADES_PLATAFORMA.md`); els rols a D10/D17 escriuen a MEMBERSHIP.
- `apps/id` és una app petita més al monorepo; `id.agilitydoghub.com` és un host més al Caddy (ADR-003).
- Learn necessita un PR petit (fase 1) coordinat amb l'etapa E1; cap canvi de dades a Learn excepte la columna nova.
- L'app AR entra per OIDC natiu sense feina extra.
- Reversible: si un dia es vol Keycloak, ACCOUNT/MEMBERSHIP s'exporten i els clients OIDC es reapunten.
