# Catàleg d'esdeveniments de domini

**v1.0 · 03-09-2026** · Escrits a l'outbox `domain_events` **dins la mateixa transacció** que el canvi (MODEL_DADES_PLATAFORMA §6); consumits pel dispatcher (notificacions, projeccions, comptadors, webhooks interns) amb idempotència per `eventId`. Nom = `Aggregate` + participi, en anglès. Tot esdeveniment porta: `eventId`, `clubId` (o `null`), `occurredAt`, `actorAccountId` (+ `impersonatedMemberId`), `origin` (`APP` · `BACKOFFICE` · `SYSTEM` · `WEBHOOK`).

| Esdeveniment | Payload principal | Emès a | Consumidors |
|---|---|---|---|
| **Identitat i plataforma** | | | |
| `AccountCreated` | accountId, email, source (SIGNUP · IMPORT_LEARN · CONSOLE) | S01 | auditoria |
| `MagicLinkRequested` | accountId, clientId | S01 | N-25 |
| `PasswordChanged` | accountId | S01 | N-26 |
| `MembershipChanged` | accountId, clubId, roles (abans/després) | S01/S03/S05 | auditoria, cache de permisos |
| `ImpersonationStarted` / `ImpersonationEnded` | actorAccountId, memberId | S01 | auditoria |
| `ClubCreated` / `ClubUpdated` / `ClubModulesChanged` / `ClubStatusChanged` | clubId, diff | S02/S17 | cache de branding, auditoria |
| `ParameterChanged` | key, before, after | S02 | cache de configuració, auditoria |
| **Cens i alta** | | | |
| `SignupSubmitted` | memberId (PENDING), dogIds, planId, paymentMethodType | S04 | N-01, D1 pendents |
| `SignupEdited` | memberId, diff | S04 | auditoria |
| `MemberValidated` | memberId, memberNumber, dogs[{dogId, levelId}], nextInvoiceDate, upfrontPaymentId | S04 | N-02, MEMBERSHIP, KPIs |
| `SignupRejected` | memberId, reason | S04 | N-03, GOS → baixa |
| `MemberUpdated` | memberId, diff (contacte, adreça) | S03 | auditoria |
| `MemberPaymentMethodChanged` | memberId, type, masked | S03/S12 | N-38, auditoria |
| `MemberStatusChanged` | memberId, before, after, effectiveDate | S03/S13 | cancel·lació de reserves futures, MEMBERSHIP suspensió, KPIs |
| `BookingBlockChanged` | memberId, active, reason | S03 | N-29, auditoria |
| `DogRegistered` / `DogUpdated` / `DogDeactivated` / `DogTransferred` | dogId, memberId, diff | S03 | N-37, auditoria |
| `DogLevelChanged` | dogId, before, after | S03 | N-09, recalcul de visibilitat de classes, cobertura |
| `DogFreeTrainingChanged` | dogId, allowed, source (LEVEL · MANUAL) | S03 | pestanya 08 |
| `DogDocumentUploaded` / `DogDocumentPending` | dogId, type, state | S03/S04 | D15 pendents, N-23 |
| `MemberNoteChanged` | dogId, memberId | S10 | N-22, D14 |
| **Catàlegs** | | | |
| `LevelChanged` / `RingChanged` / `InstructorChanged` / `PlanChanged` / `PriceChanged` / `FaqChanged` / `MessageTemplateChanged` | id, diff | S05/S11 | auditoria, cache |
| **Planificació** | | | |
| `WeekTemplateChanged` | templateId, diff, inconsistencies[] | S06 | cobertura |
| `WeekGenerated` | weekId, classCount, templateId | S06 | D4 |
| `WeekValidated` | weekId, classIds[] | S06 | visibilitat alumnes, N-33 (si activat a l'obertura) |
| `ClassSessionCreated` / `ClassSessionUpdated` | classId, diff | S06 | N-08b si hi ha inscrits i canvia hora/pista/instructor |
| `ClassCancelledByClub` | classId, reason, adminText, affected[{bookingId, memberId, dogId}], waitlistIds[] | S06/S15 | N-08a, bookings → CANCELLED_BY_CLUB, waitlist → CANCELLED, packs retornats, comptadors |
| `ClassAutoCancelled` | classId, dogsCount | S15 | N-17 (+ N-08a als inscrits) |
| `ClassAtRisk` | classId, dogsCount, reviewAt | S15 | N-16, D1 targeta |
| `ClassBelowMinimum` | classId, countedDogs, minDogs | S15 (R-15-12b) | N-54 (instructors + admins; cap efecte sobre la classe) |
| `ClassRiskExemptionChanged` | classId, exempt | S06 | scheduler |
| `RingBlockCreated` / `RingBlockCancelled` | blockId, ringId, from, to, reason, activityId? | S06/S09 | slots d'entrenament → bloquejats, quadres |
| `ActivityPublished` / `ActivityUpdated` / `ActivityCancelled` / `ActivityFinished` | activityId | S06 | N-32, bloquejos de pista, web pública |
| **Reserves** | | | |
| `SeatHeld` / `SeatHoldReleased` | classId, memberId, dogId, expiresAt | S08 | UI compte enrere (no notifica) |
| `BookingCreated` | bookingId, classId, memberId, dogId, origin, swapFromBookingId?, packMovementId? | S08 | N-04 (o N-36 si BACKOFFICE), comptadors, pack |
| `BookingCancelled` | bookingId, by (MEMBER · INSTRUCTOR · ADMIN · SYSTEM), late (bool), minutesBefore, origin | S08/S10 | N-05 (o N-36), `SeatReleased`, pack retornat si no late |
| `SeatReleased` | classId, freeSeats, minutesBefore, notifyWaitlist (bool: > `waitlist.notifyThresholdMinutes`) | S08 | N-15 (mode ALL_AT_ONCE: tots; FIFO: primer) |
| `WaitlistJoined` / `WaitlistLeft` | entryId, classId, memberId, dogId | S08 | comptadors «⏳n» |
| `WaitlistNotified` | entryIds[], classId, confirmBy? | S08 | N-15, timer FIFO |
| `WaitlistConsolidated` | entryId, bookingId | S08 | N-04, resta d'entrades → informades (plaça ocupada) |
| `WaitlistExpired` (FIFO) | entryId | S15 | següent de la cua |
| `TrainingBooked` / `TrainingCancelled` | trainingBookingId, slotId, ringId, memberId, dogId, origin | S09 | N-06/N-07, quadres, comptador n/3 |
| `TrainingCounterReset` | weekStart | S15 | comptadors |
| `ActivityRegistrationChanged` | registrationId, activityId, memberId, state | S08 | N-32b |
| **Assistència i seguiment** | | | |
| `AttendanceMarked` | bookingId, state (PRESENT · NOTIFIED · NO_SHOW · PENDING), by | S10 | «ha avisat» → `BookingCancelled`+`SeatReleased`; NO_SHOW → cua N-19 (lot 8:00); % assistència |
| `NoShowNoticeDue` | bookingIds[] | S15 | N-19 |
| `TaskCreated` / `TaskUpdated` / `TaskDeleted` / `TaskCompleted` | taskId, dogId, by | S10 | N-20/N-21, D14 |
| `AttachmentAdded` | attachmentId, entity | S10 | — |
| **Facturació i pagaments** | | | |
| `InvoiceIssued` | invoiceId, memberId, period, total, paymentMethodType | S12 | comptadors D6 |
| `InvoiceCollecting` / `InvoicePaid` / `InvoiceFailed` / `InvoiceCancelled` | invoiceId, provider, collectionId, reason? | S12 | N-10/N-30/N-35, D6 |
| `RemittanceSimulated` | remittanceDraftId, incidents[], totals | S12 | D6 |
| `RemittanceGenerated` | remittanceId, invoiceIds[], xmlFileKey | S12 | avança proper rebut, auditoria |
| `RemittanceRolledBack` | remittanceId, invoiceIds[] | S12 | retrocés de numeració i dates, auditoria |
| `UpfrontPaymentRecorded` / `UpfrontPaymentSucceeded` / `UpfrontPaymentFailed` | paymentId, concept, provider | S04/S12 | N-30, pack obert |
| `PackOpened` / `PackConsumed` / `PackRefunded` / `PackLowBalance` / `PackExpiring` / `PackExpired` | packBalanceId, dogId, remaining | S08/S12/S15 | N-11, baixa prevista automàtica |
| `StripeWebhookReceived` | eventId, type | S12 | processament idempotent |
| **Inactivitat i baixa** | | | |
| `InactivityRequested` / `InactivityResolved` / `InactivityStarted` / `InactivityEnded` | periodId, memberId, from, to, decision | S13/S15 | N-18, reserves dins l'interval → cancel·lades, quota |
| `LeaveRequested` / `LeaveResolved` | requestId, memberId, requestedDate, effectiveDate | S13 | N-14/N-28, `MemberStatusChanged` amb data futura |
| **Comunicacions** | | | |
| `NotificationQueued` / `NotificationSent` / `NotificationFailed` | notificationId, channel | S11 | log |
| `AnnouncementSent` | templateId, recipientCount, filters | S11 | N-24 |
| `PushSubscribed` / `PushUnsubscribed` | accountId, endpoint | S11 | — |
| `ReminderDue` | bookingId/trainingBookingId, memberId | S15 | N-13 |
| **Recorreguts** | | | |
| `CourseCreated` / `CourseUpdated` / `CourseImported` | courseId, source | S16 | biblioteca |
| `RingGeometryChanged` | ringId | S16 | recàlcul d'avisos de col·locacions actives |
| `PlacementCreated` / `PlacementUpdated` | placementId, warnings[] | S16 | — |
| `RingSetupChanged` | ringId, setupId, status | S16 | N-31 (opcional), quadres 08/10/23, caducitat |
| `BuildSessionStarted` / `BuildSessionProgress` / `BuildSessionFinished` | sessionId, obstacleId?, status | S16 | SSE live, `RingSetupChanged` en acabar |
| **Sistema** | | | |
| `WeekOpened` | weekStart | S15 | N-33, `TrainingCounterReset` |
| `SchedulerRun` | job, clubId, startedAt, finishedAt, effects | S15 | traça (principi 0.3.7) |
| `DataExported` | listKey, format, rows, by | S14 | auditoria |
| `AccountErasureRequested` / `AccountErased` | accountId | S14 | pseudonimització |

## Regles

1. Els services **no** envien notificacions ni criden proveïdors externs: emeten esdeveniments; el dispatcher fa la resta (testable amb dobles).
2. Consumidors idempotents (`processedAt` per consumidor); reintents amb backoff; cua d'errors visible a la consola (S17).
3. Cada esdeveniment té un test que verifica que s'emet amb el payload esperat (assert a l'outbox) — PLA_BACKEND §9.
4. Afegir un esdeveniment = afegir-lo aquí + classe `record` al paquet `domain.events` del context corresponent.

## Annex A — esdeveniments afegits el 03-09 a partir de les specs

| Esdeveniment | Payload | Spec |
|---|---|---|
| `AccountLocaleChanged` · `AccountEmailChanged` · `SessionRevoked{familyId, reason}` · `LearnAccountsImported{created, merged, errors}` · `ImpersonationEnded{reason}` | identitat | S01 |
| `ParameterInvalidOverride{key, value, error}` | configuració | S02 |
| `AccessResent{memberId, accountId}` · `FamilyGroupChanged{groupId, holderMemberId, memberIds before/after}` · `DocumentReminderDue{dogId, type}` · `DogDocumentPending{trigger}` | cens | S03 |
| `SignupRecognitionRequested{memberId, accountId, redirect}` | alta | S04 |
| `PriceChanged{action: CLOSED}` (supersessió) · `MembershipChanged` des de D17 | catàlegs | S05 |
| `RingBlockUpdated{blockId, diff}` · enum `InconsistencyType` (RING_DOUBLE_BOOKED · INSTRUCTOR_DOUBLE_BOOKED · RING_BLOCKED · RING_TRAINING_CONFLICT · LEVEL_INACTIVE · RING_INACTIVE · INSTRUCTOR_INACTIVE) | planificació | S06/S09 |
| `ActivityPublished{levelIds, notifyEmail}` · `ActivityUpdated{diff, registrantCount}` · `ActivityCancelled{adminText, affected[]}` · `ActivityRegistrationChanged{origin, cancelReason, promoted}` | activitats | S07 |
| `WaitlistNotified{mode}` · `BookingCancelled{reason: PAYMENT_TIMEOUT · INSTRUCTOR_NOTICE · INACTIVITY · LEAVE · CLASS_CANCELLED}` | reserves | S08/S13 |
| `TrainingCancelled{by, cancelReason, late}` | entrenaments | S09 |
| `AttendanceMarked{classSessionId, memberId, previousState, late, afterClassEnd}` · `TaskReopened` · `AttachmentRemoved` | assistència | S10 |
| `EmailBounced{memberId, email}` · `SmsCapReached{month}` · `NotificationPreferencesChanged` · `EmailUnsubscribed` | comunicacions | S11 |
| `BillingRunCreated` · `BillingRunCompleted` · `PackAdjusted{delta, reason}` · `MemberCardInvalidated` | pagaments | S12 |
| `InactivityChanged` · `InactivityCancelled` · `LeaveCancelled` · `LeaveResolved{source, decision, cancelledBookings[]}` · `InactivityResolved{fee, cancelledBookings[]}` | inactivitat/baixa | S13 |
| `AccountErasureRequested{accountId?, memberIds[], clubIds[], executeAt, source}` · `AccountErased{…}` · `DataExported{jobId, kind}` | RGPD | S14 |
| `RemittanceReminderDue{period, pendingMembers}` · `JobFailed{job, runId, status, errorCount}` · `SchedulerRun{trigger, counters}` · `SignupPendingAging{count, oldestDays, memberIds[]}` (N-34) · `WaitlistExpired` (FIFO) · `TrainingCounterReset` | processos | S15 |
| `CourseDeleted` · `CourseCopied{fromCourseId}` · `InventoryChanged{scope}` | recorreguts | S16 |
| `ClubDomainVerified` · `ClubDomainBroken` · `PlatformRoleChanged` · `ClubPaymentProviderChanged` · `ClubApplied{slug, diffSummary, dryRun}` · `SupportAccessStarted` | consola | S17 |
| `MigrationRunStarted` · `MigrationRunCompleted{counters}` · `MigrationRunFailed{step}` | migració | S18 |
| `ChallengePublished` · `ChallengeClosed` · `ChallengeAttemptSubmitted` · `ChallengeAttemptValidated` · `AccountSharingChanged` (R2) | Learn | S19 |
