# ADR-003 — Hosting, entorns i desplegament

**Estat:** Acceptada · **subdominis tancats el 2026-09-03 (2a sessió)**: `id.agilitydoghub.com` (AgilityHub ID, `apps/id` + endpoints OAuth2/OIDC) · `clubs.agilitydoghub.com` (PWA) · `clubsadmin.agilitydoghub.com` (backoffice) · `core.agilitydoghub.com` (API; verificat 05-09: l'API de Learn és `app.agilitydoghub.com` i el SPA `learn.agilitydoghub.com`) · `learn.agilitydoghub.com` (existent). Cada club afegeix els seus àlies CNAME. Novetats operatives: webhooks de Stripe per club a `core.*/webhooks/stripe/{clubId}` (ADR-009); **recomanació** (no decidida): quan la facturació estigui en producció, passar el cron de backup de setmanal a **diari** — és el mateix script, només canvia el cron, i redueix la finestra de pèrdua de 7 dies a 1.
**Data:** 2026-09-02 · actualitzada 2026-09-03 (decisions Jordi: BBDD al mateix droplet, backups setmanals, subdominis)
**Decisors:** Jordi (amb assistència IA)

## Context

Decisió de Jordi: «el deploy es farà com fem ara a AgilityHub», combinant els proveïdors d'Avanta i d'AgilityHub Learn. Referències reals:

- **AgilityHub Learn**: droplet DigitalOcean únic, codi a `/var/www/html`, deploy manual per SSH amb **runbook** (`prod-sync/DEPLOY.md`: backup previ, release, rollback, smoke test), front compilat en local i pujat per rsync, PWA en producció.
- **Avanta**: MongoDB self-hosted en droplet DO (legacy Psonrie) i **AWS S3** per a media (eu-west-3).
- RNF-06: pic de concurrència el diumenge a les 20:00 (obertura d'inscripcions) — centenars d'usuaris, no milers: un droplet ben dimensionat va sobrat.

## Opcions considerades

1. **Droplet DO + Docker Compose + S3, deploy per runbook SSH (estil AgilityHub)** — coherent amb com opera Jordi avui; cost baix i control total.
2. PaaS gestionat (Railway/Render + Atlas) — menys operació però trenca els hàbits existents i afegeix cost mensual.
3. Kubernetes/managed containers — sobredimensionat per a l'escala d'aquest producte.

## Decisió

**Opció 1**, amb aquesta topologia:

### Producció (droplet DigitalOcean dedicat del producte club)
```
Caddy (TLS automàtic, virtual hosts)
├── id.agilitydoghub.com          → estàtics apps/id + proxy /oauth2, /login, /.well-known → core-api
├── clubs.agilitydoghub.com       → estàtics apps/clubs (PWA alumnes + instructors)
├── clubsadmin.agilitydoghub.com  → estàtics apps/clubs-admin (backoffice + consola de clubs)
└── core.agilitydoghub.com        → agilityhub-core-api (Spring Boot, contenidor; /api/v1, /webhooks)
MongoDB 7+ (contenidor AL MATEIX droplet, replica set d'un node per a transaccions, volum persistent)
S3 (AWS, com Avanta) → media: cartilles, fotos de gossos, adjunts de tasques, plànols de recorreguts
```
Naming tancat el 03-09 (2a sessió): el front d'usuaris **no és `app.*`** (és l'API de Learn, verificat 05-09) i la nostra API és `core.*` per no confondre-la. Cada club apunta els **seus dominis** com a àlies (CNAME) del mateix desplegament — Cànic: `app.agilitycanic.cat` / `admin.agilitycanic.cat` (els que mostren els mockups) — i el host resol el tenant (ADR-002). El host del ID no té àlies per club: la marca del login es tria pel `client_id`/`redirect_uri` (marca del club) o és AgilityHub.

- **Base de dades**: MongoDB conviu **a la mateixa màquina** que l'API mentre el volum ho permeti (decisió Jordi 03-09); quan creixi, es mou a una **màquina específica** — només canvia la URI de connexió, cap canvi de codi. Els contenidors ja separen els dos serveis des del primer dia.
- **Backups**: **setmanals via script** (decisió Jordi 03-09): cron que fa `mongodump --oplog` + empaqueta volums i `.env` + puja la còpia a S3, amb retenció de N setmanes i **restauració provada** a staging (el runbook documenta com restaurar). Els media ja viuen a S3 (versionat activat). Si amb la facturació en marxa es vol una finestra de pèrdua més curta, apujar la cadència és canviar el cron.
- **Entorns**: `dev` local (Docker Compose: mongo + api; fronts amb Vite dev server) · `staging` = segon compose al mateix droplet amb subdominis `*.staging` i BBDD pròpia (dades fictícies, MAI clon de producció amb dades personals — restricció RGPD del projecte) · `prod`.
- **Deploy**: runbook estil AgilityHub adaptat (document `DEPLOY.md` al repo API): backup → `git pull` → build/restart contenidor → smoke test → rollback documentat. Fronts: build al Mac o al runner i rsync dels `dist/`. **Evolució prevista** (no bloquejant): GitHub Actions per build + rsync, com ja apuntava l'anàlisi tècnica d'AgilityHub.
- **Serveis externs**: Twilio (SMS, pendent §3), proveïdor d'email transaccional (ADR-005 obert — SendGrid és el que ja coneixeu d'AgilityHub Learn), Sentry (front i back), web push VAPID propi.

## Conseqüències

- Operativa idèntica a la que Jordi ja practica (SSH + runbook), sense corba nova; un sol servidor a vigilar.
- Docker aïlla les versions (Java, Mongo) i fa el droplet reproduïble; el compose és la documentació de la topologia.
- El punt únic de fallada (un droplet, amb la BBDD a dins) s'assumeix per l'escala del producte; mitigat amb els backups setmanals per script (còpia a S3 + restauració provada). El camí de creixement està marcat: primer una màquina específica per a MongoDB, després el que el volum demani.
- Els secrets viuen NOMÉS al `.env`/variables d'entorn del servidor — mai al repo (lliçó d'Avanta: hi ha credencials dins `application.properties` versionat; al projecte nou, `application.properties` només porta placeholders).
