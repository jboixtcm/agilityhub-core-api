# ADR-005 — Proveïdor d'email transaccional

**Estat:** Acceptada
**Data:** 2026-09-05
**Decisors:** Jordi (amb assistència IA)

## Context

Els enllaços màgics (S01), les benvingudes (S04), les notificacions per correu (S11) i els correus de l'AgilityHub ID depenen d'un proveïdor d'email transaccional des d'E1. AgilityHub Learn ja envia amb **SendGrid** (compte existent, `sendgrid/sendgrid` a Laravel; webhook d'esdeveniments configurat però no persistit). El web-planner usa **Resend** per a invitacions.

## Opcions considerades

1. **SendGrid (Twilio)** — ja contractat per Learn, domini `agilitydoghub.com` verificat, mateix proveïdor que l'SMS (Twilio) → un sol compte, una sola factura; API i webhooks madurs.
2. Resend — API senzilla (la usa el planner), però un proveïdor més.
3. Amazon SES — més barat a volum alt, més operació.

## Decisió

**SendGrid** per a tota la plataforma (decisió Jordi 05-09): `EmailSender` del core amb implementació `SendGridEmailSender` (API v3, plantilles pròpies renderitzades al core en l'idioma del destinatari, no *dynamic templates* de SendGrid), webhook d'esdeveniments (`/webhooks/email/sendgrid`) per a bounces/complaints/deliveries persistit a `notifications` (S11), remitent per club (`messaging.email.fromName/fromAddress`) sobre el domini de producte i, opcionalment, el domini del club verificat a SendGrid (S17). Twilio per a SMS reutilitza el mateix compte d'empresa.

## Conseqüències

- E1 desbloquejat: l'ID envia enllaços màgics des del primer dia.
- Cap dependència nova per a Learn; el planner deixa Resend en migrar al core.
- Riscos: límits de sortida del compte (revisar el pla de SendGrid amb el volum previst: alumnes × avisos), reputació del domini compartida amb Learn (separar amb subdomini de remitent `clubs.agilitydoghub.com` si cal).
