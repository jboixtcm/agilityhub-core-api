# Catàleg de mòduls per club (ADR-012)

**v1.0 · 03-09-2026** · `CLUB.modules[]` · Guard al back `@RequiresModule` (→ `404 MODULE_DISABLED`) · Mapa mòdul→UI a `packages/ui/modules.ts` (rutes, pestanyes, targetes, camps) · `GET /branding` retorna `modules[]`.

| Clau | Depèn de | Back: què desactiva | Front `apps/clubs` | Front `apps/clubs-admin` | Cànic |
|---|---|---|---|---|---|
| `FREE_TRAINING` | — | `/training-slots`, `/training-bookings`, dret per nivell, scheduler de reinici del comptador, «Ocupada» per entrenament a 10/23 | pestanya Entrenaments (08), 24 (reserva de pista per instructor: només bloqueig si off), comptador n/3 a 03 | D11 bloc Entrenaments, D16 «reservable per entrenaments», D12 reserves a mitja alçada | on |
| `BILLING` | — | `/invoices`, `/remittances`, `/collections`, proper rebut, IBAN/mètode de pagament a l'alta, quota d'inactivitat, `PACKS`, `SINGLE_CLASS` | pas 19 sense pagament (només consentiments), 12 sense rebuts | D6, D8 (només noms de modalitat sense preus), D10 bloc pagament, D1 KPI d'efectius | on |
| `PACKS` | `BILLING` | modalitats `PACK`, `/pack-balances`, consum/retorn de sessions | comptador de pack a 04/13 | D8 packs, D10 pack per gos | on |
| `SINGLE_CLASS` | `BILLING` | modalitat `SINGLE_CLASS`, línies per consum, PAY_TO_BOOK | preu a la fila de classe (04), pagament a 06 | D8 tipus classe individual | off |
| `ACTIVITIES` | — | `/activities`, `/activity-registrations`, `/public/*/activities`, bloqueig de pistes per activitat | bloc ACTIVITATS a 04, activitats a 03/25 | D7 | on |
| `FAMILY_GROUP` | — | `/family-groups`, gossos del grup al selector, pagador únic, tarifa familiar | pas 18 de l'alta, xips de gossos del grup | D10 bloc grup familiar, D2 «Grup trobat» | on |
| `WAITLIST` | — | `/waitlist-entries`, estats «Completa · ⏳n», scheduler FIFO | files «Completa · ⏳n» passen a «Completa» inerta; 11 sense [Agafa la plaça] | D4 «+espera», D11 bloc Llista d'espera, D12 | on |
| `TASKS` | — | `/tasks`, `/attachments` de tasques, notes als instructors, observacions, D14, avisos N-20/21/22 | 13 sense Tasques/Notes, 22/26 | D13 blocs, D14, comptador del menú | on |
| `FAQ` | — | `/faq-entries` | 30 i la 6a icona del tabbar | D11 card FAQ | on |
| `SMS` | — | canal SMS a la matriu de plantilles (Twilio); si off, la matriu amaga la columna SMS i cap enviament | «i per SMS» a 11, SMS a 12 | D9 columna SMS, D10 preferències | on |
| `PUSH` | — | `/push-subscriptions`, enviament web push | toggle push a 12, permís en context | D9 columna Push (informativa) | on |
| `INACTIVITY` | — (`BILLING` per a la quota) | `/inactivity-periods`, scheduler d'inicis/finals, quota d'inactivitat | 14, oferta a 15 | D10 acció Inactivitat, D6 KPI, D11 quota | on |
| `COURSES` | — | mòdul `courses` sencer: `/courses`, `/rings/{id}/geometry`, `/placements`, `/ring-setups`, `/build-sessions`, `/obstacle-inventories`, scheduler de caducitat de muntatges | «Recorregut muntat» a 08/10/23/detall de reserva, visor mòbil, sessió de muntatge (instructor) | D18 biblioteca, D16 geometria, D12 recorregut a la cel·la, D7 col·locacions | on |
| `LEARN_LINK` | — | — (només front) | entrada «Aprèn amb AgilityHub» al menú/perfil (enllaç a Learn; R2: contingut pel nivell del gos) | — | on |
| `STATS` | — | agregats de 27 (fase 2) | 27 | — | off |
| `SOCIAL_LEAGUE` | `STATS` | `LLIGA_SOCIAL`, `RESULTAT_COMPETICIO` (fase 2) | 27/27b | D20 resultats (fase 2) | off |

## Regles

1. Activar/desactivar es fa des de la consola de clubs (S17) o, per als mòduls marcats com a «autoservei» (`FAQ`, `PUSH`, `LEARN_LINK`), des de D11 pel mateix club. Cada canvi s'audita i emet `ClubModulesChanged`.
2. Dependències validades al back (`422 MODULE_DEPENDENCY`).
3. Desactivar no esborra dades; reactivar les torna a mostrar.
4. Els **schedulers** comproven el mòdul del club abans d'executar-se.
5. **Seed del Cànic**: tots `on` llevat de `SINGLE_CLASS`, `STATS`, `SOCIAL_LEAGUE`. **Seed «club mínim»** (per a demos i tests): només `WAITLIST`, `FAQ`, `PUSH`.
6. Tests: per a cada mòdul, un test d'integració amb el mòdul off que verifica `404 MODULE_DISABLED` als seus endpoints i que la resta del sistema funciona (p. ex. alta sense `BILLING` no demana IBAN).
