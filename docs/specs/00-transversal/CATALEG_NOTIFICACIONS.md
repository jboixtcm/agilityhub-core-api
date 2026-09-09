# Catàleg de notificacions

**v1.0 · 03-09-2026** · Cada notificació neix d'un **esdeveniment** (`CATALEG_ESDEVENIMENTS.md`), es renderitza amb una **plantilla** (`MessageTemplate`, editable a D9 llevat de les `SYSTEM`) en l'**idioma del destinatari** (ADR-011) i s'envia pels canals que resulten de: **matriu de la plantilla** (canals × públics) ∩ **preferències de l'abonat** (només per al públic alumne, dins del que la plantilla permet) ∩ **mòduls del club** (`SMS`, `PUSH`). Sempre es desa a `notifications` (log, RF-NOT-03), també si cap canal extern s'ha enviat (l'app la mostra a 11).

Categories (D9): `OPERATIONAL` (Operativa: correu OFF per defecte per a l'alumne) · `PERSONAL` (Comunicats individuals: correu ON) · `CLUB_CHANGES` (Canvis en reserves fets pel club: app + correu + **SMS**) · `CLUB_NEWS` (Comunicats del club: app + correu + push segons toggle) · `SYSTEM` (correus de compte: no configurables, sempre correu).

Públics: `MEMBER` (alumne), `INSTRUCTORS` (tots els instructors del club, o els de la classe si s'indica), `ADMINS`, `APPLICANT` (sol·licitant d'alta sense compte: només correu).

Canals: `APP` (feed 11 + badge) · `EMAIL` · `SMS` (a tots els telèfons de l'abonat) · `PUSH` (web push, si subscrit). Regla del Josep (18-08): **tot missatge sobre una acció de l'alumne que no és a iniciativa seva porta SMS** → categoria `CLUB_CHANGES`.

Accions natives (`action`): `CHANGE_CLASS` (obre 04 amb el gos preseleccionat) · `CLAIM_SEAT` (POST claim de la llista d'espera) · `OPEN_BOOKING` · `OPEN_DOG` · `OPEN_TASKS` · `OPEN_INVOICES` · `OPEN_ACTIVITY` · `OPEN_SETUP`. Les plantilles admeten variables però **no** accions (R28-08): l'acció la fixa el codi per `code`.

| Codi | Nom (CA) | Esdeveniment | Categoria | Públic → canals per defecte | Variables | Acció | Spec |
|---|---|---|---|---|---|---|---|
| N-01 | Sol·licitud d'alta rebuda | `SignupSubmitted` | OPERATIONAL | APPLICANT → EMAIL · ADMINS → APP+EMAIL | member_name, dogs, plan_name, club_name | admin: OPEN_SIGNUP | S04 |
| N-02 | Benvinguda i accés | `MemberValidated` | SYSTEM+PERSONAL | MEMBER → EMAIL (enllaç màgic) + APP (un cop dins) | member_first_name, gender, club_name, link | — | S04 |
| N-03 | Sol·licitud rebutjada | `SignupRejected` | PERSONAL | APPLICANT → EMAIL | member_name, reason, club_name | — | S04 |
| N-04 | Reserva confirmada | `BookingCreated` | OPERATIONAL | MEMBER → APP (EMAIL off) | dog_name, class_date, class_time, class_description, ring_name, calendar_links | OPEN_BOOKING | S08 |
| N-05 | Reserva anul·lada (per tu) | `BookingCancelled{byMember}` | OPERATIONAL | MEMBER → APP | dog_name, class_date, class_time, late (bool) | — | S08 |
| N-06 | Entrenament reservat | `TrainingBooked` | OPERATIONAL | MEMBER → APP | dog_name, date, time, ring_name | — | S09 |
| N-07 | Entrenament anul·lat | `TrainingCancelled` | OPERATIONAL | MEMBER → APP | idem | — | S09 |
| N-08a | **Classe anul·lada pel club** | `ClassCancelledByClub` | CLUB_CHANGES | MEMBER (inscrits) → APP+EMAIL+**SMS** · INSTRUCTORS → APP+EMAIL · ADMINS → APP | dog_name, class_date, class_time, class_description, **admin_text** | CHANGE_CLASS | S06 |
| N-08b | Classe modificada pel club (hora/pista/instructor) | `ClassSessionUpdated{diff ∋ startTime·ringId·instructorIds, booked > 0}` | CLUB_CHANGES | MEMBER → APP+EMAIL+SMS · INSTRUCTORS → APP | dog_name, class_date, changes | OPEN_BOOKING | S06 |
| N-09 | Canvi de nivell | `DogLevelChanged` | PERSONAL | MEMBER → APP+EMAIL | dog_name, level_name | OPEN_DOG | S03 |
| N-10 | Rebut impagat / cobrament fallit | `InvoiceFailed` | OPERATIONAL | ADMINS → APP+EMAIL | member_name, invoice_number, amount, reason | OPEN_INVOICES | S12 |
| N-11a | Pack a punt d'esgotar-se | `PackLowBalance` | PERSONAL | MEMBER → APP+EMAIL | dog_name, pack_remaining | OPEN_DOG | S12 |
| N-11b | Pack a punt de caducar / caducat | `PackExpiring` · `PackExpired` | PERSONAL | MEMBER → APP+EMAIL | dog_name, pack_expiry | OPEN_DOG | S12 |
| N-13 | Recordatori de classe/entrenament | `ReminderDue` (scheduler) | OPERATIONAL | MEMBER → APP+PUSH (EMAIL segons preferència) | dog_name, date, time, ring_name | OPEN_BOOKING | S11 |
| N-14 | Sol·licitud de baixa rebuda | `LeaveRequested` | OPERATIONAL | ADMINS → APP+EMAIL | member_name, requested_date, reason | OPEN_MEMBER | S13 |
| N-15 | **S'ha alliberat una plaça!** | `SeatReleased` (si > llindar) | OPERATIONAL (SMS activat a la plantilla) | MEMBER en espera (tots o el primer FIFO) → APP+**SMS**+PUSH | dog_name, class_date, class_time, confirm_by (FIFO) | CLAIM_SEAT | S08 |
| N-16 | Possible anul·lació de classe | `ClassAtRisk` | CLUB_CHANGES | MEMBER inscrit → APP+EMAIL · ADMINS → APP | dog_name, class_date, class_time, review_time, review_day | CHANGE_CLASS | S15 |
| N-17 | Classe anul·lada per manca d'alumnes | `ClassAutoCancelled` | CLUB_CHANGES | MEMBER inscrit → **N-08a** amb text automàtic · ADMINS+INSTRUCTORS → APP+EMAIL | class_date, class_time, dogs_count | CHANGE_CLASS | S15 |
| N-18a | Sol·licitud d'inactivitat rebuda | `InactivityRequested` | OPERATIONAL | ADMINS → APP+EMAIL | member_name, from_month, to_month | OPEN_MEMBER | S13 |
| N-18b | Inactivitat aprovada/denegada | `InactivityResolved` | PERSONAL | MEMBER → APP+EMAIL | from_month, to_month, decision, fee | — | S13 |
| N-18c | Represa d'activitat | `InactivityEnded` | PERSONAL | MEMBER → APP | — | — | S13 |
| N-19 | **T'hem trobat a faltar** (no presentat) | `NoShowNoticeDue` (lot 8:00) | PERSONAL | MEMBER → APP+EMAIL (segons preferències) | dog_name, class_date | — | S10 |
| N-20 | Tasca nova | `TaskCreated` | PERSONAL | MEMBER → APP+EMAIL | dog_name, instructor_name, task_excerpt | OPEN_TASKS | S10 |
| N-21 | Tasca completada | `TaskCompleted` | OPERATIONAL | INSTRUCTORS → APP | member_name, dog_name, task_excerpt | OPEN_DOG | S10 |
| N-22 | Nota de l'alumne nova/canviada | `MemberNoteChanged` | OPERATIONAL | INSTRUCTORS → APP | member_name, dog_name | OPEN_DOG | S10 |
| N-23 | Cartilla pendent (recordatori) | manual (D10/D15) o `DocumentReminderDue` | PERSONAL | MEMBER → APP+EMAIL | dog_name, document_type | OPEN_DOG | S03 |
| N-24 | Comunicat del club | `AnnouncementSent` (D9 «Enviar comunicat») | CLUB_NEWS | selecció → APP+EMAIL (+PUSH si toggle) | free text amb variables | — | S11 |
| N-25 | Enllaç per entrar | `MagicLinkRequested` | SYSTEM | compte → EMAIL | link, expires_minutes | — | S01 |
| N-26 | Contrasenya establerta/canviada | `PasswordChanged` | SYSTEM | compte → EMAIL | — | — | S01 |
| N-27 | Accés reenviat | `AccessResent` (D10) | SYSTEM | MEMBER → EMAIL (enllaç màgic) | link | — | S03 |
| N-28 | Baixa confirmada (data d'efecte) | `LeaveResolved` | PERSONAL | MEMBER → APP+EMAIL | effective_date | — | S13 |
| N-29 | Reserves bloquejades / desbloquejades | `BookingBlockChanged` | PERSONAL | MEMBER → APP+EMAIL | reason | — | S03 |
| N-30 | Cobrament realitzat (Stripe) / rebut emès | `InvoicePaid{STRIPE}` · `UpfrontPaymentSucceeded` | PERSONAL | MEMBER → EMAIL (si `billing.stripeReceiptEmail` és fals, Stripe no l'envia i l'enviem nosaltres) | amount, concept, invoice_number | OPEN_INVOICES | S12 |
| N-31 | Recorregut nou a la pista | `RingSetupChanged` (si `messaging.notifyNewRingSetup`) | OPERATIONAL | MEMBER amb dret d'entrenament → APP | ring_name, setup_kind, level | OPEN_SETUP | S16 |
| N-32a | Activitat publicada | `ActivityPublished` | CLUB_NEWS | MEMBER (nivell admès) → APP (+EMAIL si el club ho marca) | activity_title, date | OPEN_ACTIVITY | S06 |
| N-32b | Inscripció a activitat confirmada / anul·lada | `ActivityRegistrationChanged` | OPERATIONAL | MEMBER → APP | activity_title, date, state | OPEN_ACTIVITY | S08 |
| N-32c | Activitat cancel·lada pel club | `ActivityCancelled` | CLUB_CHANGES | inscrits → APP+EMAIL+SMS | activity_title, admin_text | — | S06 |
| N-33 | Ja pots reservar la setmana vinent | `WeekOpened` (si `messaging.notifyWeekOpening`) | OPERATIONAL | MEMBER → APP+PUSH | week_start | OPEN_BOOKING | S15 |
| N-34 | Sol·licituds d'alta pendents des de fa dies | `SignupPendingAging` (diari) | OPERATIONAL | ADMINS → APP | count, oldest_days | OPEN_SIGNUP | S15 |
| N-35 | Cobrament fallit (targeta) | `InvoiceFailed{STRIPE}` | PERSONAL | MEMBER → APP+EMAIL | amount, reason, retry_link | OPEN_INVOICES | S12 |
| N-36 | Reserva feta/anul·lada pel club en nom teu | `BookingCreated/Cancelled{origin=BACKOFFICE}` | CLUB_CHANGES | MEMBER → APP+EMAIL+SMS | dog_name, class_date, class_time, actor: «el club» | OPEN_BOOKING | S08 |
| N-37 | Nou gos afegit / gos donat de baixa | `DogRegistered` · `DogDeactivated` | PERSONAL | MEMBER → APP | dog_name | OPEN_DOG | S03 |
| N-38 | Canvi de mètode de pagament / IBAN | `MemberPaymentMethodChanged` | PERSONAL | MEMBER → EMAIL | masked_account | — | S12 |

## Variables disponibles (claus de codi; etiqueta en l'idioma de l'admin a D9)

`member_name`, `member_first_name`, `gender`, `dog_name`, `level_name`, `class_date`, `class_time`, `class_description`, `ring_name`, `instructor_name`, `club_name`, `admin_text`, `reason`, `amount`, `invoice_number`, `pack_remaining`, `pack_expiry`, `date`, `time`, `link`, `confirm_by`, `activity_title`, `effective_date`, `from_month`, `to_month`. Sintaxi a la plantilla: `[[dog_name]]`. Les dates es formaten segons el `locale` del destinatari i el fus del club.

## Regles

1. **Idioma**: el del destinatari (`Account.locale`); si la plantilla no té aquest idioma, fallback al `defaultLocale` del club.
2. **Preferències** (12/D10): només apliquen al públic MEMBER i només poden **treure** canals que la plantilla permet (mai afegir-ne). L'`APP` és sempre actiu. El recordatori (N-13) només existeix si l'abonat ha triat antelació.
3. **SMS**: a tots els telèfons de l'abonat; comptador per club (`usage.smsSentMonth`) i `messaging.sms.monthlyCap`; el text SMS és una versió curta de la plantilla (camp `smsBody`, màx. 160 caràcters GSM-7, sense enllaços llargs).
4. **Push**: només si hi ha subscripció activa; error 410 → es dona de baixa la subscripció.
5. **Correu**: proveïdor per ADR-005; bounce/complaint → marca a `Member.contactEmails[].bounced` i avís a l'admin.
6. **Idempotència**: una notificació per (`eventId`, `recipient`, `channel`); els reintents no dupliquen.
7. **Log** (`notifications`): estat per canal (`QUEUED`, `SENT`, `DELIVERED`, `FAILED`, `SKIPPED_BY_PREFERENCE`, `SKIPPED_MODULE_OFF`), `readAt` per a l'app.
8. Tests: per a cada codi, la matriu canals×públics amb preferències ON/OFF i mòduls ON/OFF (PLA_BACKEND §9.8).

## Annex A — codis afegits el 03-09 a partir de les specs (numeració tancada)

| Codi | Nom (CA) | Esdeveniment | Categoria | Públic → canals | Variables | Acció | Spec |
|---|---|---|---|---|---|---|---|
| N-18d | Canvi en un període d'inactivitat | `InactivityChanged` | OPERATIONAL | ADMINS → APP | member_name, from_month, to_month | OPEN_MEMBER | S13 |
| N-32d | Activitat modificada pel club | `ActivityUpdated{diff amb data/hora/lloc, registrantCount > 0}` | CLUB_CHANGES | inscrits → APP+EMAIL+SMS | activity_title, changes | OPEN_ACTIVITY | S07 |
| N-39 | Verifica que ets tu (afegir un gos com a soci existent) | `SignupRecognitionRequested` | SYSTEM | compte → EMAIL | link, expires_minutes, club_name | — | S04 |
| N-40 | Reserva no completada (pagament caducat) | `BookingCancelled{reason: PAYMENT_TIMEOUT}` | OPERATIONAL | MEMBER → APP+EMAIL | dog_name, class_date, class_time | OPEN_BOOKING | S08 |
| N-41 | Remesa del mes pendent | `RemittanceReminderDue` | OPERATIONAL | ADMINS → APP+EMAIL | period, pending_count | OPEN_INVOICES | S15/S12 |
| N-42 | Procés automàtic amb errors | `JobFailed` (màx. 1 per procés i dia) | OPERATIONAL | ADMINS → APP (+EMAIL) | job_name, error_count | OPEN_JOBS | S15 |
| N-43 | Domini del club trencat | `ClubDomainBroken` | SYSTEM | admins de plataforma → EMAIL | club_name, host | — | S17 |
| N-44 | Nou challenge disponible (R2) | `ChallengePublished` | CLUB_NEWS | MEMBER amb nivell mapejat → APP | challenge_title, level | OPEN_CHALLENGE | S19 |
| N-45 | Intent de challenge validat (R2) | `ChallengeAttemptValidated` | PERSONAL | MEMBER → APP+EMAIL | challenge_title, score | OPEN_CHALLENGE | S19 |
| N-46 | La plaça ja s'ha ocupat | `WaitlistNotified` revertit (`NOTIFIED → ACTIVE`, `ALL_AT_ONCE`) | OPERATIONAL | MEMBER → APP | dog_name, class_date, class_time | — | S08 |
| N-47 | Entrenament reservat / anul·lat pel club en nom teu | `TrainingBooked/Cancelled{origin: BACKOFFICE ∨ by ≠ MEMBER}` | CLUB_CHANGES | MEMBER → APP+EMAIL+SMS | dog_name, date, time, ring_name | OPEN_BOOKING | S09 |
| N-48 | Ara ets instructor / administrador del club (opcional, fora de R1) | `MembershipChanged{roles +}` | PERSONAL | compte → APP+EMAIL | club_name, role | — | S05 |
| N-49 | Límit mensual d'SMS assolit | `SmsCapReached` (un cop per mes) | OPERATIONAL | ADMINS → APP+EMAIL | month, cap | — | S11 |
| N-50 | Les teves dades estan a punt | `ExportJob{MEMBER_DATA} → READY` | PERSONAL | MEMBER → APP+EMAIL | link, expires_days | OPEN_EXPORT | S14 |
| N-51 | Correu rebotat | `EmailBounced` | OPERATIONAL | ADMINS → APP | member_name, email | OPEN_MEMBER | S11 |
| N-52 | Hem rebut la teva sol·licitud de supressió | `AccountErasureRequested{source ≠ RETENTION}` | SYSTEM | compte → EMAIL | execute_date, club_name | — | S14 |
| N-54 | **Classe amb pocs alumnes** (per sota del mínim) | `ClassBelowMinimum` (anul·lació dins termini) | OPERATIONAL | INSTRUCTORS de la classe + ADMINS → APP+EMAIL | class_date, class_time, class_description, ring_name, dogs_count | CHANGE_CLASS | S15 |
| N-53 | Invitació com a administrador del club | `POST /platform/clubs/{id}/admins` | SYSTEM | compte convidat → EMAIL | club_name, link, inviter_name | — | S17 |

Variants: N-02 `MIGRATED` (S18, benvinguda dels abonats migrats) · N-32b amb `origin=BACKOFFICE` → CLUB_CHANGES (S07) · N-36 cobreix també les reserves de classe fetes/anul·lades pel club. Accions natives noves: `OPEN_JOBS`, `OPEN_EXPORT`, `OPEN_CHALLENGE`, `OPEN_MEMBER`, `OPEN_SIGNUP`. Variables noves: `upfront_total`, `payment_instructions`, `pay_link` (N-01), `member_last_names`, `dog_name_article`, `kind` (N-13), `class_description` (N-19), `admin_text`/`cancelled_count` (N-18b/N-28), `auto_cancel` (N-16/N-17).
