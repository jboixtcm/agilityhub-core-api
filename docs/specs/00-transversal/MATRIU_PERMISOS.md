# Matriu de permisos — rols × recursos

**v1.0 · 03-09-2026** · Rols: `ANON` · `MEMBER` (alumne) · `INSTRUCTOR` · `ADMIN` (administrador del club) · `AGILITYHUB_ADMIN` (plataforma) · `IMPERSONATED` = `ADMIN` actuant «com l'abonat» (mateixos permisos que `MEMBER` sobre aquell abonat + auditoria). Un compte pot tenir més d'un rol al club; el **perfil actiu** (03b) només canvia la navegació de l'app — l'API autoritza per la unió de rols del JWT.

Llegenda: **R** llegir · **W** crear/modificar · **X** accions especials · «propi» = el seu abonat, els seus gossos i els del grup familiar · «—» sense accés (`403`; `404` si el recurs és d'un altre club).

| Recurs | ANON | MEMBER | INSTRUCTOR | ADMIN | AGILITYHUB_ADMIN |
|---|---|---|---|---|---|
| `/branding`, `/public/*` | R | R | R | R | R |
| `/signup` (alta pública) | W | W (afegir gos: `/me/dogs/signup`) | — | — | — |
| `/oauth2/*`, `/auth/magic-link` | W | W | W | W | W |
| `/me`, `/me/profile`, canvi de perfil actiu, contrasenya, idioma | — | RW | RW | RW | RW |
| Abonats `/members` | — | R propi (via `/me`) · W dades de contacte (28) | R **tots** (fitxa d'alumne 22/D13: dades de contacte visibles, IBAN no) | RW + X (validar, rebutjar, bloquejar reserves, baixa, inactivitat, canvi de mètode de pagament, rols, reenviar accés, impersonar) | R (consola: recomptes, sense dades personals per defecte) |
| Gossos `/dogs` | — | R propi · W «notes als instructors», documents, foto | R tots · W observacions, nivell (proposta → l'ADMIN el fixa) | RW (nivell, «pot entrenar sol», transferir, baixa) | — |
| Grup familiar | — | R propi | R | RW | — |
| Catàlegs (`/levels`, `/rings`, `/instructors`, `/plans`, `/prices`) | R (`/public/*/plans`) | R | R | RW | RW (clonar catàlegs base) |
| Paràmetres `/parameters`, FAQ, plantilles de comunicat | — | R FAQ | R FAQ | RW | RW |
| Plantilles setmanals, setmanes, generació, validació | — | — | R | RW + X | — |
| Classes `/class-sessions` | — | R (actives del seu nivell/tots els quadres) | R totes · W assistència | RW + X (anul·lar, eliminar→anul·lada, exempció de risc) | — |
| Bloquejos de pista `/ring-blocks` | — | R (com «Ocupada», sense qui) | RW (propis i de tothom: 24) | RW | — |
| Reserves de classe `/bookings` | — | RW propi (límits) | R totes · X «ha avisat» en nom de l'alumne (21) | R totes · via impersonació per reservar/anul·lar | — |
| Llista d'espera | — | RW propi (claim) | R | R + X cancel·lar | — |
| Entrenaments (slots i reserves) | — | R slots · RW propi | R totes · W reserva/bloqueig sense alumne (24) | RW | — |
| Activitats i inscripcions | — | R publicades · RW inscripció pròpia | R | RW + X (publicar, cancel·lar) | — |
| Assistència `/attendances` | — | R pròpia (25) | RW de les seves classes i de qualsevol (visió global) | RW | — |
| Tasques, observacions, seguiment D14 | — | R tasques pròpies · X marcar feta · W adjunts propis | RW tasques i observacions de qualsevol alumne · R D14 | RW · R D14 | — |
| Notificacions `/notifications` | — | R pròpies · X accions natives | R pròpies | R pròpies + R log complet del club | — |
| Preferències d'avís | — | RW pròpies | RW pròpies | RW de qualsevol abonat (D10) | — |
| Comunicats massius (`/message-templates/{id}/send`) | — | — | — | X | — |
| Facturació (rebuts, remeses, cobraments, exports) | — | R rebuts propis | — | RW + X (simular, generar, retrocedir, marcar cobrat/impagat, reintentar Stripe, reemborsar) | — |
| Pagaments a l'acte (`/checkout-sessions`) | W (alta amb Stripe) | W propi | — | W en nom de l'abonat (manual) | — |
| Inactivitat / baixa | — | W sol·licitud pròpia | — | RW + X (aprovar, denegar, data d'efecte) | — |
| Tauler D1, KPIs | — | — | — | R | R agregat per club (consola) |
| Auditoria | — | — | — | R | R plataforma |
| RGPD: paquet de dades, supressió (S14) | — | X paquet propi (`/me/data-export`) | — | X paquet i supressió d'un abonat | X supressió de compte |
| Processos programats (S15) | — | — | — | R execucions · X interruptors, executar ara, dry run | R/X tots els clubs |
| Accés de suport de plataforma (S17) | — | — | — | — | X (token `support`, auditat, sense IBAN ni exports) |
| Recorreguts `/courses` | — | R visibles (CLUB/PUBLIC) | RW del club · X importar Smarter | RW · X publicar al club | RW `AGILITYHUB` (públics) |
| Rings (geometria), col·locacions, recorregut muntat, sessions de muntatge, inventari | — | R recorregut muntat (08/10) | RW (registrar muntat, sessió de muntatge) | RW | — |
| Consola de clubs `/platform/*` | — | — | — | — | RW + X (crear club, mòduls, dominis, proveïdors, suspendre) |
| Vistes desades `/saved-views` | — | — | RW pròpies | RW pròpies + compartides | — |

## Regles transversals

1. **Instructor = visió global** (Q-08 de la spec): veu totes les classes, alumnes i gossos del club, mai dades bancàries.
2. **Abonat**: només el seu abonat i els gossos propis i del grup familiar; les reserves d'un gos del grup les pot fer qualsevol membre del grup (v1.6).
3. **Impersonació**: el token porta `impersonatedMemberId`; l'API el tracta com a `MEMBER` d'aquell abonat; les respostes inclouen `impersonation: {actorName}` perquè la PWA mostri el bàner; **prohibit** usar-lo en endpoints `ADMIN`.
4. **Plataforma**: `AGILITYHUB_ADMIN` no entra a dades personals de cap club llevat que tingui també `ADMIN` en aquell club (membresia) — la consola treballa amb configuració i agregats.
5. **Tests**: per a cada endpoint, la matriu es prova explícitament (rol permès i **cada** rol denegat), més el cas de tenant creuat (`404`).
