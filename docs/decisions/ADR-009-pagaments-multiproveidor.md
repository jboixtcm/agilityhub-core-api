# ADR-009 — Pagaments multi-proveïdor: remesa SEPA, Stripe i manual

**Estat:** Acceptada (substitueix el tema «TPV online — backlog» de la llista inicial; ADR-006 sobre la llibreria pain.008 queda oberta i es decideix a l'etapa E8)
**Data:** 2026-09-03
**Decisors:** Jordi (amb assistència IA)

## Context

El Cànic cobra les quotes amb **remesa SEPA** (pain.008 al banc) i els cobraments anticipats per transferència/Bizum. Molts altres clubs «aniran amb TPV (Stripe)», tenen **classe individual** (pagament per classe) en lloc de quota, i «ha de ser possible facturar de les dues maneres i que sigui configurable» (Jordi 03-09). El model v1.6 lliga REBUT i REMESA a SEPA.

## Opcions considerades

1. **REBUT independent del proveïdor + cobrament per proveïdor (`SEPA_XML`, `STRIPE`, `MANUAL`)**, configurable per club i per abonat.
2. Tot per Stripe (targeta i SEPA Direct Debit per Stripe), sense remesa bancària — més simple, però el Cànic ja té creditor i circuit bancari i les comissions de Stripe SEPA són superiors a la remesa pròpia.
3. Només remesa SEPA i TPV al backlog (pla v1) — no compleix el requisit dels altres clubs.

## Decisió

**Opció 1**, amb aquest disseny:

### Configuració
- `CLUB.paymentProviders`: subconjunt de `{SEPA_XML, STRIPE, MANUAL}` activats, amb la seva configuració: SEPA (creditor, IBAN d'abonament, sufix, sèrie de rebuts) · Stripe (**compte Stripe propi del club**: `secretKey` i `webhookSecret` xifrats a la BBDD amb clau del servidor, `publishableKey`; mode test/live) · Manual (instruccions: compte per a transferències, Bizum, efectiu — textos localitzats).
- `ABONAT.paymentMethod`: `SEPA_DD` (IBAN + titular + mandat amb data i referència) · `CARD` (Stripe `customerId` + `paymentMethodId` desats amb SetupIntent) · `MANUAL` (efectiu/transferència/Bizum). El formulari d'alta (19) ofereix només els mètodes que el club té activats.

### Model de cobrament
- **REBUT** (invoice) és neutre: número de sèrie, abonat, mes, línies, import, moneda, `paymentMethod` congelat, estat `PENDING → COLLECTING → PAID | FAILED | CANCELLED`.
- **COBRAMENT** (collection attempt) per proveïdor: `SEPA_XML` → el rebut entra en una **REMESA** (simulació → XML → retrocés, com al model) · `STRIPE` → `PaymentIntent` off-session sobre el mètode desat; el resultat arriba per **webhook** (idempotent per `event.id`) · `MANUAL` → «marcar cobrat» per l'admin.
- **Pagaments a l'acte** (entrada, primer mes, packs, classe individual, activitats): `STRIPE` → Stripe Checkout (redirecció) amb `PaymentIntent` i webhook; `MANUAL` → COBRAMENT_ANTICIPAT amb import efectivament cobrat (com ara).
- **Classe individual** (`MODALITAT.tipus = SINGLE_CLASS`): preu per classe; mode `CHARGE_ON_ATTENDANCE` (cada assistència genera una LINIA_REBUT i el rebut mensual les agrupa) o `PAY_TO_BOOK` (reservar = pagar amb Stripe; l'anul·lació dins termini reemborsa o deixa crèdit — paràmetre).
- Devolucions Stripe: `refund` des de D6 amb traça; per SEPA no hi ha reemborsament automàtic (marca manual, com avui).
- **Impagats**: SEPA → marca manual (BR-08); Stripe → `payment_intent.payment_failed` marca `FAILED` i avisa l'admin (N-10); reintent manual des de D6.

### Immutabilitat i auditoria
Rebuts i cobraments són **append-only**; retrocedir una remesa crea moviments de retrocés, mai esborra. Tots els canvis de mètode de pagament de l'abonat s'auditen (ja al model).

### Fora d'aquesta decisió
- Stripe Connect / comissions de plataforma (R4, quan hi hagi model comercial).
- Redsys/Bizum API: no; Bizum queda com a pagament manual.

## Conseqüències

- El model incorpora `paymentProviders`, `paymentMethod`, COBRAMENT i el tipus `SINGLE_CLASS` (`MODEL_DADES_PLATAFORMA.md`).
- E8 construeix els tres proveïdors darrere d'una interfície `PaymentProvider` amb dobles als tests; Stripe es prova amb el mode test i webhooks signats de mostra; la remesa amb *golden files* (PLA_BACKEND §9).
- El Cànic segueix exactament igual (SEPA + manual); Stripe queda desactivat al seu seed.
- ADR-006 (llibreria pain.008 vs XML propi validat amb XSD) es decideix a E8; ADR-005 (email transaccional) a E1.
