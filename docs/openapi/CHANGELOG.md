# API contract changelog

Add one dated line per endpoint change whenever the API changes; regenerate and review `openapi.json` with `bin/openapi-snapshot` (Java 21 and Docker required).

## 2026-10-07 · E8-T08 · receipt incidents and the simulation collection date

- `BillingIncident` adds optional `invoiceId` and `displayNumber`, absent on member-level incidents. `BillingIncidentCode` adds the approved `MEMBER_NOT_ACTIVE` and `PAYMENT_METHOD_CHANGED`; a changed mandate is no longer an incident. `skipped[]` excludes members billed by this run, including through their family holder.
- `SimulationKpis.collectionDate` gives the configured default date in the billed month (club-local); legacy stored simulations without it return null. Current simulations always fill it, including those without a SEPA remittance.
- Invalid invoice-series parameters keep catalog `400 PARAMETER_INVALID`. Invalid stored SEPA identifiers return `422 SEPA_NOT_CONFIGURED` with only `{reason: IDENTIFIER, field}`.

## 2026-10-07 · E8-T05 round 2 · inactivity termination

- `POST /inactivity-periods/{id}/termination` documents that its end cannot be later than the current end; an extension returns the existing `422 INACTIVITY_INVALID_RANGE`. Use PATCH to extend with overlap validation and booking cancellation.

## 2026-10-07 · E8-T05 · packs, inactivity and leave

- The reserved pack, manual upfront-payment and S13 lifecycle routes now serve their documented operations.
- `POST /members/{id}/plan-change` (ADMIN, BILLING, Idempotency-Key) changes the current plan and price and creates a discounted DUE entry fee for an eligible pack-to-monthly change. `effectiveMonth`, when supplied, must equal the current club-local month.
- Member lists expose `leaveSource`, `inactivityUntil` and `hasPendingRequest`; member overview adds `inactivity` and `plannedLeave`. Inactivity detail adds nullable `bookingsInside` when automatic cancellation is disabled.
- Booking, waitlist and activity operations publish the catalog `MEMBER_LEAVING` response after the inclusive leave day.
- Free training enforces the dog owner's inclusive leave day (`MEMBER_LEAVING` afterwards) and inactivity period; its published errors include `MEMBER_LEAVING` and `DOG_NOT_ACTIVE`.
- Inactivity PATCH schemas reject unknown fields and require a nonnegative version. Erased-member writes remain 409; lifecycle state/duplicate conflicts follow the current error catalog (409).
- Declared errors completed after review: both inactivity PATCH routes list `403 READ_ONLY` (a different `fromMonth`/`comments` on an `ACTIVE` period) and `409 INACTIVITY_OVERLAP`; the member's inactivity PATCH and the `/me` inactivity and leave cancellations list `409 MEMBER_ERASED`.

## 2026-10-05 · E8-T04 round 2 · card invalidation and card-charge states

- `PaymentMethodView.invalid` on member responses is a boolean present only for `type = CARD`; it is absent for other methods.
- `GET /me` adds optional `paymentMethod {type, invalid?}` (`MePaymentMethod`), scoped to the current member with BILLING enabled. Provider identifiers and card credentials are never exposed. `invalid` is present only for CARD.
- `POST /billing/runs/{id}/card-charges` documents GENERATED or CHARGING and skips invoices already submitted; the obsolete E8-T04 stub description is removed.

## 2026-10-04 · E8-T04 · Stripe card payments and checkout

Provider routes now serve card charging, retries, refunds, card setup, checkout status and the existing checkout extensions. Webhooks return 200 after durable receipt, including deferred processing. `SignupWarning` adds `CARD_INVALID` for pending dashboard rows. Existing signup request and response fields remain compatible; errors use the catalog statuses (notably signature 401 and refund-overpayment 422).

## 2026-10-01 · E8-T07 · rolled back comes from the run; the waiting manual receipts are in the simulation

**Two schemas changed (additive), seven descriptions changed; no operation, status or error list changed.**
- `Invoice` (`GET /invoices/{id}`, the invoice writes, `BulkPaymentResult.invoices[]`): new required `rolledBack` (boolean). It is
  true when the receipt's run is `ROLLED_BACK` (R-12-14, §5), whatever its `cancelReason`: every receipt of a rolled-back run,
  one the admin had cancelled before included (its own reason and date kept). `cancelReason`'s description says so.
- `InvoicePreview` (`POST /billing/simulations`): new optional `invoiceId` (uuid) and `displayNumber`. A row with them is a waiting
  manual `SEPA_DD` receipt with `includeInNextRun` that the run will put into its remittance (R-12-19); on the run's own rows both
  keys are absent (never `null`), so S12 §6's JSON is unchanged. Such rows count in `kpis.count`, `kpis.total` and
  `kpis.byProvider.SEPA_XML`.
- `InvoiceListItem.rolledBack`: description only (it now comes from the run).
- Descriptions: `POST /invoices` (`includeInNextRun` with a total of zero or less → `400 VALIDATION_ERROR {field:
  includeInNextRun}`); `POST /invoices/{id}/cancellation` (the reason is free text: `ROLLBACK` is no longer refused);
  `GET /invoices` and `GET /invoices/{id}` (rolled back = its run); `POST /billing/simulations` (the waiting receipts and their
  incidents); `POST /billing/runs` (staleness also covers the unbilled charges and the waiting receipts; a waiting receipt
  with an incident is skipped); `POST /billing/runs/{id}/rollback` (the whole block, an admin-cancelled receipt included).
- Values without a contract change: a waiting receipt whose member has left, no longer pays by `SEPA_DD`, has no account or
  signed another mandate is a `NO_BANK_ACCOUNT` incident in the simulation and in `skipped[]`; with `SEPA_XML` off, a
  `PROVIDER_DISABLED` one. After a rollback the next run reissues the run's whole block of numbers.

## 2026-10-01 · E8-T03 · the `/remittances*` routes are served

**No schema, operation, status or required set changed; four descriptions changed and one error list grew.**
- `GET /remittances`, `GET /remittances/{id}`, `GET /remittances/{id}/file` and `POST /remittances/{id}/submission` no longer
  answer `501`. Their descriptions now state what they serve: the list's row and its empty page for a club without SEPA
  (R-12-28); the detail's `sequenceBreakdown`, `xsdValidatedAt` and masked creditor IBAN; the five-minute signed link, which
  authorises itself (no bearer), with `Content-Type: application/xml`, `Content-Disposition: attachment;
  filename="remesa-{period}.xml"` and the audited file access (`DATA_EXPORTED`); and the submission's rules. `submittedAt` is a
  club-local day, not after today and not before the remittance's day (`400 VALIDATION_ERROR {field: submittedAt}`), stored as
  the start of that day in the club's zone. The audit action is `REMITTANCE_SUBMITTED`. Afterwards a rollback is
  `409 RUN_NOT_ROLLBACKABLE {reasons: [REMITTANCE_SUBMITTED]}`.
- `POST /remittances/{id}/submission`: its `409` list gains `STALE_VERSION` (the billing transaction's exhausted write-conflict
  retries, as on the other billing writes).
- Values without a contract change: `POST /billing/runs` returns a remittance with `fileAvailable: true`, `xsdValidatedAt` and
  `xsdValidationSkipped: null`. It now answers `422 SEPA_NOT_CONFIGURED` when the club's `SEPA_XML` has no creditor identifier
  or IBAN (and the simulation lists those debtors as `PROVIDER_DISABLED`), with `details.memberIds` for a debit without a
  mandate, a signature date or an account, and with `details.reason = SCHEMA` for a file the schema refuses. It answers
  `422 CURRENCY_MISMATCH` for a club not in euros. The local signed download (`/api/v1/remittances/files/{clubId}/{id}`) is
  not published: the client only follows the `downloadUrl` it was given.

## 2026-10-01 · E7-T07 · the `ANNOUNCEMENT_SENT` audit entry carries `details`

**No schema, operation, status or required set changed; one description changed.**
- `POST /message-templates/{id}/send`: the description says that the `ANNOUNCEMENT_SENT` entry is on the template, with no
  changes and with `details {batchId, recipientCount, filters, selection}`.
- Values without a contract change: for this action, `GET /audit-entries` and `GET /audit-entries/{id}` now return `details`
  (`selection` is `FILTERS` or `MEMBERS`; `filters` is `[]` for a selection) and `changes: []`. Before, the four values were
  `changes[]` paths and `details` was absent.

## 2026-10-01 · E8-T02 round 2 · a receipt cancelled by a rollback is listed only under `CANCELLED` (`rolledBack`)

**One optional field added; two descriptions changed; no operation, status or required set changed** (ruling E87).
- `InvoiceListItem` gains the optional `rolledBack` (boolean), also a `fields` key of `GET /invoices` (`x-fields`): true for a
  receipt cancelled by a rollback (R-12-14), whose number the next run reissued. `GET /invoices` lists, counts and searches such a
  receipt only when the `status` filter selects `CANCELLED` (`eq` or `in`); `GET /billing/periods/{period}`'s `counts`, `GET
  /me/invoices` (and its `{id}` and `document`, now `404` for it) and D10's recent receipts leave it out.
- `POST /invoices/{id}/cancellation`: the description states that the reason `ROLLBACK` (the rollback's own) is `400
  VALIDATION_ERROR {field: reason}` (already in its error list).
- Values without a contract change: the club's next `POST /billing/runs` puts the `PENDING` manual `SEPA_DD` receipts with
  `includeInNextRun` into its remittance (`COLLECTING`, counted in `byProvider.SEPA_XML` and the remittance), and its rollback
  returns them to `PENDING`; a change of the club's payment providers or modules after the simulation is `409 SIMULATION_STALE`; a `COMPLETED`
  run's rollback is `409 RUN_NOT_ROLLBACKABLE {reasons: [COLLECTION_SUBMITTED]}`; the receipt PDF names the tax after the club's
  country profile (`ES` → «IVA»).

## 2026-10-01 · E8-T01 round 2 · guards before the stubs, the Stripe signature first, `MeInactivityPeriod.version`, nullable `toMonth`

**No operation or schema added or removed; 5 schemas and 11 operations changed** (`roadmap/evidence/E8-T01/15-r2-contract-diff.log`).
- `MeInactivityPeriod` gains the required `version` (int64, ≥ 0): the member's `PATCH /me/inactivity-periods/{id}` sends it back
  (R-13-04, T-13-26). The S13 §6 example of `GET /me/inactivity-periods` carries it.
- `InactivityRequest`, `AdminInactivityRequest`, `InactivityPatchRequest`, `AdminInactivityPatchRequest`: `toMonth` and `comments`
  are nullable (`toMonth: null` = an open period, S13 §3). The two PATCH bodies keep field presence: an omitted field stays,
  `toMonth: null` opens the period, `comments: null` clears them; `fromMonth: null` or an unknown field → `400 VALIDATION_ERROR`.
  Required sets unchanged.
- `POST /webhooks/stripe/{clubId}`: the description states the order — the `Stripe-Signature` (HMAC-SHA256 of `{t}.{raw body}`
  under the club's webhook secret, 5-minute tolerance) is verified before anything else; an unknown club or a club without a
  secret is `401 WEBHOOK_SIGNATURE_INVALID` too; then `BILLING` (404 `MODULE_DISABLED`) and `STRIPE` (404).
- `POST /checkout-sessions`: the description states that `bookingId`/`upfrontPaymentIds` pass the signup checkout's role,
  member and club checks and must be that member's in the club (another member's, another club's or unknown → 404) before
  `NOT_IMPLEMENTED`.
- `PATCH /me/inactivity-periods/{id}`, `PATCH /inactivity-periods/{id}`: descriptions state the field presence.
- Error lists (the `/me/*` stubs now resolve the caller's member first): `GET /me/inactivity-periods`,
  `GET /me/inactivity-periods/preview`, `GET /me/pack-balances` gain `404 NOT_FOUND`; `POST /me/card-setup` and
  `POST /me/inactivity-periods` gain `404 NOT_FOUND` and `409 MEMBER_ERASED`; `GET /me/leave-requests` and
  `POST /me/leave-requests` name their 404 `NOT_FOUND` (was the generic «Not Found»), and `POST /me/leave-requests` gains
  `409 MEMBER_ERASED`.

## 2026-10-01 · E8-T02 · the S12 monthly cycle and the invoice actions are served (no schema change)

**18 operations served, descriptions and three error lists changed; no path, schema or success status changed.** The operations
of E8-T01 that E8-T02 implements no longer answer `501`: `GET /billing/periods/{period}`, `POST /billing/simulations`,
`POST /billing/runs`, `GET /billing/runs/{id}`, `POST /billing/runs/{id}/rollback`, `GET /invoices`, `GET /invoices/{id}`,
`POST /invoices`, `POST /invoices/{id}/payment`, `POST /invoices/payments`, `POST /invoices/{id}/failure`,
`POST /invoices/{id}/cancellation`, `GET /invoices/{id}/document`, `GET /me/invoices`, `GET /me/invoices/{id}`,
`GET /me/invoices/{id}/document`, `GET /members/{id}/pending-charges`; `POST /billing/runs/{id}/card-charges` answers
`422 PAYMENT_PROVIDER_NOT_ENABLED` after its guards until E8-T04 brings a card provider. Their descriptions now state what they
do (the members' numbering order, the collection-day default and the two business days, the rollback's word `RETROCEDIR`, the
chips of D6, the family holder's receipts, the masked method of the PDF).
- Error lists: `POST /billing/runs` and `POST /billing/runs/{id}/rollback` gain `409 STALE_VERSION` (a write conflict that
  outlasts the retries); `POST /invoices` gains `409 BILLING_BUSY` (a run holds the receipt counter) and `409 STALE_VERSION`;
  `POST /invoices/payments` gains `409 STALE_VERSION`.
- Values the web sees without a contract change: a SEPA collection's `providerRef` is «{mandateRef}/{endToEndId}» and a manual
  one's the channel («TRANSFER · TR-1» with a reference); `InvoiceListItem.concept` is the first line's frozen description,
  «(+n)» when there are more; a manual invoice's `period` is its issue month; `GET /me/invoices` includes the family holder's
  receipts with `familyGroup = true` (R-12-27).

## 2026-10-01 · E7-T04 round 3 · D9 stores `gender` select keys in lower case (ruling E83)

**Descriptions only; no schema, status or path changed.** `POST /message-templates`, `PUT /message-templates/{id}`: the keys of a
`{gender, select, …}` are stored in lower case (`FEMALE {…}` → `female {…}`), the gender the engine renders (S11 §10,
R-11-12), so D9 shows the saved text with lower-case keys after a save. `POST /message-templates/{id}/preview`: a draft is
rendered as a save would store it. Also visible without a contract change: the start-up template upgrade's audit entries now
have `actorName = null` (role `SYSTEM`, `details.job = template-upgrade`), so `lastChange.actorName` is `null` for them (the
schema already allows it), and its `MessageTemplateChanged` has no `actorAccountId`.

## 2026-10-01 · E8-T01 · the S12 and S13 contract: billing, payments, packs, inactivity and leave (web E8-W* build mocks-first)

**57 operations added (36 S12, 21 S13) on 50 new paths; 2 operations changed; 135 schemas added, 2 changed; 1 security scheme
added.** Every new operation answers `501 NOT_IMPLEMENTED` after its tenant, role, module and resource guards (E8-T02…T06
serve them). Statuses are CATALEG_ERRORS' (§1 and rule 0), whatever S12/S13 §6 write. Roles: ADMIN on every route except the
`/me/*` ones (MEMBER, also the impersonation token) and the webhook; INSTRUCTOR → 403 everywhere; the impersonation token on
an admin route → `403 IMPERSONATION_DENIED`; another club's resource → 404.
- **S12, module `BILLING` (off → `404 MODULE_DISABLED`)**:
  - D6's month and cycle: `GET /billing/periods/{period}` (`BillingPeriod`) · `POST /billing/simulations` (201
    `BillingSimulation`, the S12 §6 JSON + `id`) · `POST /billing/runs` (201 `BillingRunResult`, `Idempotency-Key`) ·
    `GET /billing/runs/{id}` · `POST /billing/runs/{id}/card-charges` (202, key) · `POST /billing/runs/{id}/rollback` (key;
    `409 RUN_NOT_ROLLBACKABLE {reasons[]}`) · `GET /billing/exports?period=&format=csv|xlsx` (file or 202 `ExportAccepted`).
  - Remittances: `GET /remittances` (universal list, `x-filterable: period, status`, no `q`) · `GET /remittances/{id}` ·
    `GET /remittances/{id}/file` (`RemittanceFile`, a signed URL) · `POST /remittances/{id}/submission` (key). The creditor's IBAN
    is only masked (`Creditor.maskedIban`).
  - Invoices: `GET /invoices` (universal list, `x-filterable: period, status, memberId, paymentMethodType, runId, remittanceId,
    issueDate, total, kind`; `x-sortable: number, issueDate, total, memberLastName`; `q` = number and member) · `GET
    /invoices/{id}` (with `collections[]`) · `POST /invoices` (201, manual adjustment, key) · `POST /invoices/{id}/payment` ·
    `POST /invoices/payments` · `POST /invoices/{id}/failure` · `POST /invoices/{id}/retry` (202) · `POST /invoices/{id}/refund`
    (202) · `POST /invoices/{id}/cancellation` (all keyed, with `version` where S12 §6 has it) · `GET /invoices/{id}/document`
    (`application/pdf`). No `PATCH /invoices/{id}` (405, T-12-12).
  - The member's view (MEMBER and the impersonation token): `GET /me/invoices` (`MeInvoicePage`) · `GET /me/invoices/{id}` ·
    `GET /me/invoices/{id}/document` · `POST /me/card-setup` (201, key) · `GET /me/pack-balances` (`PACKS`).
  - Payments on the spot and packs: `GET /upfront-payments?memberId=&status=` · `POST /upfront-payments` (201, key) · `POST
    /upfront-payments/{id}/refund` (202, key) · `GET /pack-balances?memberId=&dogId=` · `POST /pack-balances` (201, key) · `POST
    /pack-balances/{id}/adjustments` (key) — `PACKS` (off → 404). The S12 pack is `PackBalanceDetail`: `PackBalance` stays S08's
    booking summary.
  - D10: `POST /members/{id}/card-setup-link` (201, key) · `GET /members/{id}/pending-charges` (`SINGLE_CLASS`).
  - `GET /checkout-sessions/{id}` (`CheckoutSessionView {checkoutSessionId, status: PENDING · PAID · EXPIRED}`): the creator
    only — ANON by host with the `X-Signup-Token` capability, the session's MEMBER, an ADMIN of the club; security `[{}, bearer]`.
  - `POST /webhooks/stripe/{clubId}` (outside `/api/v1`, like SendGrid's): no bearer and no host tenant; new security scheme
    `stripeSignature` (`Stripe-Signature` header). Unknown club, `BILLING` off or `STRIPE` not enabled → 404; a missing
    signature → `401 WEBHOOK_SIGNATURE_INVALID`.
- **S13**:
  - Inactivity, module `INACTIVITY` (off → 404): `GET /me/inactivity-periods` (`MeInactivityContext`, the S13 §6 JSON) · `GET
    /me/inactivity-periods/preview?fromMonth=&toMonth=` · `POST /me/inactivity-periods` (201, key) · `PATCH
    /me/inactivity-periods/{id}` (`version`) · `POST /me/inactivity-periods/{id}/cancellation` · `GET /inactivity-periods`
    (universal list, `x-filterable: memberId, state, fromMonth, toMonth, origin, requestedAt`; `x-sortable: fromMonth,
    requestedAt, memberLastName`) · `GET /inactivity-periods/{id}` · `POST /inactivity-periods` (201) · `POST
    /inactivity-periods/{id}/decision` · `PATCH /inactivity-periods/{id}` · `POST /inactivity-periods/{id}/termination` · `POST
    /inactivity-periods/{id}/cancellation`.
  - Leave, no module: `GET /me/leave-requests` (`MeLeaveContext`) · `POST /me/leave-requests` (201, key) · `POST
    /me/leave-requests/{id}/cancellation` · `GET /leave-requests` (universal list, `x-filterable: memberId, state, source,
    requestedDate, effectiveDate, reasonKey, nps`; `x-sortable: requestedAt, requestedDate, effectiveDate`) · `GET
    /leave-requests/{id}` · `POST /leave-requests/{id}/decision` · `POST /members/{id}/leave` (201) · `DELETE
    /members/{id}/planned-leave` (204, key) · `POST /members/{id}/reactivation` (200 `Member`).
  - A member reaches only their own period or request: another member's, also of their family group, is 404.
- **Changed operations:**
  - `POST /checkout-sessions` (E3-T03): `CheckoutSessionRequest` gains the optional `bookingId` and `upfrontPaymentIds`; a request
    with either answers `NOT_IMPLEMENTED` until E8-T04. The signup checkout itself does not change.
  - `GET /members`: `x-filterable` gains `leaveSource`, `inactivityUntil` (with `INACTIVITY`) and `hasPendingRequest`, ADMIN only
    (S13 R-13-17); until E8-T05 computes them, a filter or a facet on one of them answers `501 NOT_IMPLEMENTED`.
- **Changed schemas:** `AuditAction` gains `PACK_ADJUSTED`, `INVOICE_CREATED_MANUAL`, `REMITTANCE_SUBMITTED`,
  `CARD_CHARGES_STARTED` (S12 §13; S14 R-14-09); `CheckoutSessionRequest` (above).
- **Added schemas (135):** the forms of the operations above (`Invoice`, `InvoiceLine`, `InvoicePaymentMethod`, `Collection`,
  `InvoiceListItem`, `InvoicePage`, `MeInvoice`, `Remittance`, `RemittanceListItem`, `BillingRun`, `BillingRunResult`,
  `BillingSimulation`, `BillingPeriod`, `UpfrontPayment`, `PackBalanceDetail`, `PackMovement`, `PendingCharge`,
  `InactivityPeriod`, `InactivityPeriodListItem`, `MeInactivityContext`, `InactivityPreview`, `LeaveRequest`,
  `LeaveRequestListItem`, `MeLeaveContext`, `PlannedLeave`, …), their request bodies, the enums published once
  (`InvoiceStatus`, `InvoiceKind`, `InvoiceLineOrigin`, `PaymentMethodType`, `CollectionProvider`, `CollectionStatus`,
  `RemittanceStatus`, `BillingRunStatus`, `BillingIncidentCode`, `RollbackBlocker`, `UpfrontConcept`, `UpfrontStatus`,
  `UpfrontProvider`, `ManualChannel`, `PackBalanceState`, `PackMovementType`, `CheckoutStatus`, `InactivityState`,
  `LeaveRequestState`, `LeaveSource`, `LifecycleOrigin`, `LifecycleDecision`, `LifecycleCanceller`, `InactivityFinishReason`,
  `InactivityCancelReason`, `LeaveCancelReason`, `CancelledBookingType`, `ChangeSource`, `OverlapHint`, `MemberLeftReason`) and
  the error details `RunNotRollbackableDetails`, `CollectionDateTooSoonDetails`, `MaxAttemptsDetails`,
  `InactivityDeadlineDetails`, `InactivityOverlapDetails`, `MemberLeavingDetails`.

## 2026-10-01 · E7-T04 round 2 · an announcement goes out as it was sent (ruling E82)

**1 operation changed (description; no shape change).** S11 R-11-13.
- **`POST /message-templates/{id}/send`**: the batch stores the template as it is at the send (its version and texts), and
  each member's N-24 is rendered with that copy: archiving, disabling or editing the template after the `202` neither stops
  the batch nor changes its text (`Notification.templateVersion` is the version sent). Every announcement's PUSH respects the
  member's `pushClubNews` (`SKIPPED_BY_PREFERENCE`), whatever the category of the `CUSTOM` template it was sent with.

## 2026-10-01 · E7-T04 · «Enviar comunicat» is served (web E7-W03 adopts)

**1 operation changed (description; no shape change).** S11 R-11-13, T-11-18.
- **`POST /message-templates/{id}/send`** no longer answers `501 NOT_IMPLEMENTED`: N-24 or an active `CUSTOM` template of
  the club (another code or a disabled template → `422 TEMPLATE_NOT_SENDABLE`; an archived or unknown one → `404
  NOT_FOUND`). `recipients` is `{memberIds[]}` or `{filters[], q}` — never both nor neither (`400 VALIDATION_ERROR`
  `{field: recipients}`) — and `filters`/`q` are those of `GET /members` (an undeclared filter → `400 INVALID_FILTER`). A
  member who left is never a recipient (R-11-02); nobody → `422 NO_RECIPIENTS`. `dryRun: true` → `200 {batchId: null,
  recipientCount}`, nothing written; otherwise `202 {batchId, recipientCount}`, the batch is stored, `AnnouncementSent`
  goes to the outbox and `ANNOUNCEMENT_SENT` is audited on the template. The same `Idempotency-Key` replays the same
  `batchId`. Each member then gets one N-24 (`dedupKey {batchId}:{memberId}`) rendered with the template it was sent with: a
  `CUSTOM` template keeps its own category for the preferences and its own matrix.

## 2026-09-30 · E6-T06 · D14's filter values and search, D12's training slot length, 25's activity links (web E6-W04 steps 0b and 0c adopt)

**1 operation added; 2 operations changed (descriptions); 2 schemas changed (1 property added, 1 description).** Ruling E75.
- **`GET /followup/filter-values`** (new, INSTRUCTOR and ADMIN; MEMBER → 403; impersonation → `IMPERSONATION_DENIED`;
  `TASKS` off → `404 MODULE_DISABLED`): D14's universal filter values (CONVENCIONS_API §4), with `field` (one of
  `kind`, `memberId`, `dogId`, `authorAccountId`, `unread`; any other → `400 INVALID_FILTER`), `q` and `filter`, no
  `fields`; answers `FilterValues`. Each value's count is over the whole set the filters and `q` select, never one page; the
  filters on `field` itself are left out. `unread` is the caller's own: two instructors get different counts. Labels: the
  member's full name, the dog's name, the author's name (as their newest row stores it); `kind` and `unread` their values.
  The «Creador» filter reads `authorAccountId` here.
- **`GET /followup` searches** (description): `q` matches the member's full name, the dog's name, the author's name and the
  text (the task's or the note's whole text, not only `textExcerpt`), in any case and literally. It was declared already, but
  a non-blank one answered `400 INVALID_FILTER`.
- **`InstructorWeek.trainingSlotMinutes`** (`integer | null`, not required, always sent): the club's `training.slotMinutes`
  (S09), how long a half-height TRAINING cell is, for D12's legend; `null` with `FREE_TRAINING` off. `GET /instructor/week`'s
  description says so.
- **`HistoryItem.activityId`** (description; no shape change): only on the ACTIVITY rows whose activity is `PUBLISHED` or
  `FINISHED`, the states whose `GET /me/activities/{activityId}` answers (S07 §6); `null` on the others (a cancelled,
  unpublished or draft activity, also for a row the member had cancelled in time before) and on CLASS and TRAINING rows. Link
  the row when it is not `null`. **Behaviour change:** since E6-T05, a CANCELLED or CANCELLED_BY_CLUB row of a cancelled
  activity carried the id and linked to a 404.

## 2026-09-30 · E7-T03 round 2 · D10 reads the preferences, the messaging error details, push ownership (web E7-W01 adopts)

**1 operation added; 4 operations changed (descriptions); 3 schemas added, 1 schema changed (description).** Ruling E76.
- **`GET /members/{id}/notification-preferences`** (new, ADMIN; impersonation, MEMBER, INSTRUCTOR → 403; another club's or an
  unknown member → 404): D10's «Avisos» block, answering `NotificationPreferences` exactly as `GET /me/notification-preferences`
  answers it to that member (the defaults while the member has no block). No module, as the `PUT`.
- **Error details, published for the generated client**:
  - `ChannelNotAllowedDetails {audience, channel, cells[]}` (new, with `ChannelCell`): `422 CHANNEL_NOT_ALLOWED` names the
    first refused cell in `audience`/`channel` and every one in `cells`;
  - `TemplateFieldDetails {field, variables?, max?}` (new): `TEMPLATE_SYNTAX_ERROR`, `TEMPLATE_UNKNOWN_VARIABLE`,
    `SMS_BODY_REQUIRED` and `SMS_BODY_TOO_LONG` name the text in `details.field` (`body.ca`, `smsBody.es`…; `SMS_BODY_REQUIRED`
    names the default language's SMS text); `variables` only on the unknown variable, `max` (160) only on the long SMS;
  - `MissingVariablesDetails.missingVariables`: description only. N-02's `link` belongs to its welcome e-mail since E76, so
    the template's required variables are N-08a's `admin_text`.
- **`PUT /message-templates/{id}`, `POST /message-templates`, `GET /message-templates/{id}`**: descriptions name the details above. A catalog template
  accepts only its code's variables (`variables` of the list and the detail, the same list the preview and the delivery use);
  N-02's list no longer has `link`, and no catalog code has a member variable its row lacks. **`GET /message-templates/{id}`**
  answers the texts in the club's languages only, also for a template stored earlier with other languages too.
- **`POST /push-subscriptions`**: a subscription never changes owner. When another account's subscription of the same
  endpoint is active, it ends (`EXPIRED`, `PushUnsubscribed` for that account) and the caller gets a subscription of its own
  (a new `id`). A key that is not a point on P-256 (`(0,0)`, off the curve) is `422 PUSH_SUBSCRIPTION_INVALID`. No shape change.
- **`GET /notifications/export`**: the «Destinatari» cell is the recipient's name (an applicant's address when there is no
  name), never an id; the «Canals» cell is each channel with its delivery status, in the reader's language
  («App (Lliurat); SMS (Error)»). No shape change.

## 2026-09-30 · E5-T29 round 2 · which lists search, the register's export columns, a keyed trigger's retry (web E5-W05 adopts)

**0 operations added or removed; 14 operations changed (parameters, `x-columns` or descriptions); 0 schemas changed.**
- **`q` is declared only where the list searches** (CONVENCIONS_API §4 as amended 30-09, ruling E75). These operations no
  longer declare `q`; a non-blank `q` keeps answering `400 INVALID_FILTER` there, as since E5-T29, and a blank one is no
  search:
  - `GET /bookings` and `GET /bookings/filter-values`;
  - `GET /class-sessions`, `GET /weeks`, `GET /attendances`, `GET /jobs/{name}/runs`;
  - `GET /notifications`, `GET /notifications/filter-values`, `GET /notifications/export` (the log has no search either).
  - `GET /followup` still declares `q`; its search is api E6-T06.
- **The register searches** (`GET /training-bookings`, its `filter-values` and its `export`, S09 §2): `q` matches, in any
  case, the member's full name (first name and both last names), the dog's name or the ring's name.
- **The blocks search** (`GET /ring-blocks` and its `filter-values`): `q` matches the ring's name (a deactivated ring's too)
  or the block's note. A MEMBER never reads a note, so a member's `q` never matches one.
- **`GET /training-bookings` and `GET /training-bookings/export`, `x-columns`**: `endsAtLocal`, `memberNumber` and `endsAt`
  are export columns (not default-visible). `fields=endsAt,endsAtLocal,memberNumber` is a valid export selection; the
  default columns are unchanged. The export's headers are now the columns' names in the reader's language («Data», «Inici»,
  «Fi», «Pista», «Abonat», «Número», …) instead of the keys.
- **`GET /dogs/export`**: the «Titular» cell is the owner's full name only (S03 R-03-24); `GET /dogs` still sends the owner
  object with `firstName`.
- **`POST /jobs/{name}/trigger` with an `Idempotency-Key`** (CONVENCIONS_API §7, S15 R-15-09): the run keeps the request's
  reference. When the first answer is lost after the run committed (the key is released), the retry with the same key and
  body answers that run (its `runId`, state and counters) and runs nothing, audits nothing. No shape changes.

## 2026-09-30 · E7-T03 · S11 served: templates (D9), the log, feed 11, preferences (12/D10), push devices (web WP-11-D/E adopt)

**0 operations added; 19 operations stop answering 501 (descriptions changed); 3 schemas changed.** Only
`POST /message-templates/{id}/send` keeps its 501 until E7-T04. The forms are E7-T01's; what changes:
- **`NotificationListItem.audience`, `NotificationDetail.audience`** (`GET /notifications`, `GET /notifications/{id}`):
  `NotificationAudience | null` (the `anyOf` union), no longer required in the detail. A row written before E7-T02 that does
  not tell its audience (E7-T01 question 4) reads `null`; every engine row has one.
- **`PushKeys`** (`POST /push-subscriptions`): `p256dh` and `auth` at most 200 characters. The description now says what
  `422 PUSH_SUBSCRIPTION_INVALID` checks: an `https` endpoint with a host, base64url keys of a 65-byte P-256 point and a
  16-byte secret (what WebPush needs to encrypt).
- **`TemplatePreviewRequest.sendTest`** (published by E7-T01, served now): «envia prova» delivers the rendered preview once
  to the acting admin's account (APP and its e-mail, never SMS), stored in the log with `dedupKey test:{templateId}:…`.
- Behaviour the descriptions now state: `GET /message-templates` creates the seed of every eligible code on first read,
  lists in D9's order and leaves SMS out of `caps` with `SMS` off (its cells kept); archived templates answer 404 on
  `/{id}`; `MeNotification.channels` lists APP, then SMS/PUSH only when SENT or DELIVERED, never EMAIL (the §6 extract's
  `EMAIL` is not sent, part C S11); `GET /me/notifications` refuses `size` > 100 (`400 VALIDATION_ERROR`);
  `GET /notifications/export` answers the log's rows (the xlsx/pdf of `/exports`).

## 2026-09-30 · E6-T05 · `HistoryItem.activityId` (web E6-W04 step 0b adopts)

**0 operations added or changed; 1 schema changed.** Additive:
- **`HistoryItem.activityId`** (`GET /me/history`, screen 25; S10 §6 amended 30-09, ruling E74): `string | null`, not
  required, always sent. On an `ACTIVITY` row it is the activity's id (the row's `id` stays the registration's), so the row
  links to `/activitats/{activityId}`; on `CLASS` and `TRAINING` rows it is `null`. `GET /me/activities/{activityId}` answers
  `200` while the activity is `PUBLISHED` or `FINISHED` and `404 NOT_FOUND` otherwise: always for a `CANCELLED_BY_CLUB`
  row, and for a `CANCELLED` row whose activity the club cancelled after the member had left it.

## 2026-09-30 · E5-T29 · the E5 back office's contract gaps, `nextBookableAt`, P9 from the api (web E5-W05 adopts)

**3 operations added, 5 changed; 9 schemas changed, 0 added or removed.** Every new field is additive; the web can adopt:
- **`GET /bookings/filter-values`, `GET /training-bookings/filter-values`, `GET /ring-blocks/filter-values`** (new, step 6,
  CONVENCIONS_API §4): `field` (one of the list's `x-filterable`; any other is `400 INVALID_FILTER`), `filter` (the list's
  filters; those on `field` itself are left out) and `q`, like `/members/filter-values`; no `fields`. Answer `FilterValues`:
  the top 50 values, each with its count over the **whole** filtered set, never one page. Labels: a dog's, member's or
  ring's name, a class's `YYYY-MM-DDTHH:mm · {description}`; any other field its value. ADMIN and INSTRUCTOR; MEMBER →
  403, impersonation → `IMPERSONATION_DENIED`; the training one requires FREE_TRAINING. The three lists have no free-text
  search, so a non-blank `q` is `400 INVALID_FILTER` (before, a 500), on the list and on its filter values alike.
- **`ClassBookingItem`** (`GET /class-sessions/{id}/bookings`, step 1): `displayState` (required; S08 §6, as
  `GET /bookings/{id}`: a past ACTIVE booking is DONE, one marked NO_SHOW is NO_SHOW) and `levelCode` (`string | null`:
  the dog's level code; null with `levels.enabled = false` or without a level).
- **`WaitlistEntry`** (step 2, S10 R-10-00): `memberFirstName` and `handlerName` (`string | null`, optional in the schema;
  `handlerName` is null when the member handles the dog). Sent on every route that answers a `WaitlistEntry`, the staff
  `GET /class-sessions/{id}/waitlist-entries` included, so D12 writes «En espera: {handlerName ?? memberFirstName} + {dog}».
- **`TrainingBookingListItem`** (`GET /training-bookings`, step 3): `endsAt`, `endsAtLocal` (`HH:mm`, club time) and
  `memberNumber` (`integer | null`). All three are in `x-fields` of the list and of `GET /training-bookings/export`.
- **`BookingListItem`** (`GET /bookings`, step 4, D10 «Classes»): `classDescription` (the class's display description in
  the reader's language), `ringId`, `ringName`, `ringColor` (`string | null`). In `x-fields`.
- **`RingBlockListItem`** (`GET /ring-blocks`, step 5): `ringName` and `ringColor` (`string | null`), a deactivated ring's
  included. In `x-fields`.
- **`BookingLimitReachedDetails.nextBookableAt`** (step 7, S08 §2 row 29 amended 26-09): now the end of the **current**
  booking week, for a NEXT class as for a CURRENT one (Sunday 20:00 at the Cànic). It was the end of the class's own week,
  so for a NEXT class it came a week late. Description updated; same type.
- **`POST /jobs/{name}/trigger`** (step 8, no schema change): with an `Idempotency-Key` it no longer ends `FAILED`
  «ClientSessionException» (P9); the run happens outside the request's transaction, and the key replays the stored `200`.
- **`BookingClassSession.ringColor`** (step 9, 07's dot): `string | null`, on every `Booking` (and a `WaitlistEntry`'s class card).
- **`OwnerSummary.firstName`** (step 13, S10 R-10-00): required, a compound first name whole («Joan Antoni»), on
  `GET /dogs` rows (the instructor's projection included), its export and `GET /dogs/{id}`.
- **`Impersonation.memberName`** (`GET /me`, step 14, ruling E73): required during an impersonation; the display name of the
  member the admin opened (first name and last names), for the banner «Estàs veient l'app com {nom}».
- **`POST /auth/magic-link`**, description (step 10, S01 R-01-04 amended 28-09, ruling E70): the N-25 link of a RESET
  link ends with `&purpose=reset`, on `/activacio` (club clients) and `/magic-link` (the ID); a LOGIN link has no `purpose`.

## 2026-09-30 · E5-T28 round 2 · the signup checkout and a rejection, a lost answer

**0 operations added or removed, 1 changed (description only); 0 schemas changed.**
- **`POST /checkout-sessions`**, description (review #1–#3):
  - A rejection that expires the session gives the rows of the other submissions it charged back to `DUE`.
  - A rejection that commits while the provider opens the session gets `409 INVALID_STATE` and no `checkoutUrl`; the
    provider's session is expired.
  - A retry with the same `Idempotency-Key` after a lost answer returns the same checkout session.
- Not in the document:
  - `maskedAccount` and `paymentMethod.maskedAccount` of a migrated SEPA member now read
    `···· ···· ···· ···· 1332` (R-03-27), as every other account; they were `···· 1332`. This covers `GET /members/{id}`,
    the overview, the payment-method PATCH answer and the `GET /members` rows. Same type.
  - A re-validated week writes its `WEEK_VALIDATED` audit entry.

## 2026-09-30 · E5-T28 · audit corrections (census, signup, scheduling)

**0 operations added or removed, 4 changed (descriptions, and one `409` on the checkout); 1 schema changed (`SepaInput.iban`).**
- **`PATCH /members/{id}/payment-method`** (step 1, S03 R-03-07, ruling E42, INC-17): SEPA_DD → SEPA_DD is partial. The
  account the request does not send is kept (`iban`, or a migrated member's `ibanEncrypted` + `ibanLast4`), and so are
  `holderName`, `holderTaxId` and the mandate; `sepa.iban: null` clears the account («Compte no informat»); another
  `type` replaces the method. Behaviour change: `{type: SEPA_DD, sepa: {holderName}}` used to erase the IBAN.
- **`SepaInput.iban`**: `["string", "null"]` with the E42 description (absent keeps, `null` clears). It is also the
  `paymentMethod.sepa` of the D2 `PATCH /members/{id}`, which already had these semantics (R-04-19).
- **`POST /weeks/{id}/validation`** (step 5, T-06-23, A4-01): a VALIDATED week that still holds DRAFT classes validates
  them again (`200 {validatedClassIds}`, `WeekValidated` with those ids) and keeps its `validatedAt`; it was
  `409 INVALID_STATE`. No `@ContractErrors` change: the route no longer throws `INVALID_STATE`, and a `409
  STALE_VERSION` after the retries is transversal (CONVENCIONS_API §7, ruling E73 of 30-09, which supersedes A4-05).
- **`POST /class-sessions`** (step 5): a class created in a week that is not VALIDATED writes its week (`Week.version`
  + 1), so it conflicts with a concurrent validation and one of them runs again; never a DRAFT class in a VALIDATED week.
- **`POST /checkout-sessions`** (step 4, A3-06): the session and its `CHECKOUT_PENDING` rows commit in the signup's
  retried transaction (a concurrent census write never gives a `500`); the provider session opens after that commit,
  and a provider failure expires it again and releases the key. It now declares `409 STALE_VERSION` (the retries ran
  out), as `POST /signup` and `POST /me/dogs/signup` do for the same transaction (T-04-25 checks every S04 route).
- Not in the document (step 4, A3-01, INC-32): `POST /members/{id}/rejection` expires the signup's open checkout
  (session `EXPIRED`, rows `CANCELLED`) and asks the provider to expire it after the commit. A provider completion of an
  `EXPIRED` signup session, or of one whose rows are no longer `CHECKOUT_PENDING`, is a late completion (E34):
  `lateCompletionAt` + `providerPaymentId` on the session and a WARN, no `PAID` row, no card on the member.

## 2026-09-30 · E5-T27 round 3 · the health never reads a bearer

**0 operations added or removed, 1 changed (description only); 0 schemas changed.**
- **`GET /health`**, description (step 5, A7-07): an `Authorization` header is never read, so a bearer never sends the
  probe to the database (decoding one reads the persisted signing key ring). Behaviour change: a malformed, expired or
  impersonation bearer gets the usual `200 UP` / `503 DOWN` instead of `401` or `403`. The route is matched on its
  decoded path, so `/api/v1/%68ealth` is the same route.

## 2026-09-30 · E6-T03 round 5 · follow-up writes under concurrency, dual-role owners (E41)

**0 operations added or removed, 3 changed (descriptions only); 0 schemas changed.** Same requests and answers.
- **`POST /tasks/{id}/completion`**: an account with MEMBER and a staff role completes its own dog's task as the member
  (`doneBy.role = MEMBER`, the member's first name and gender) and another member's as staff (S01 R-01-07, rulings
  E41/E71). The second of two simultaneous completions is `422 TASK_ALREADY_DONE` with or without an `Idempotency-Key`.
- **`POST /attachments`** and **`DELETE /attachments/{id}`**, `INSTRUCTOR_NOTE`: such an account that owns the dog
  registers and removes its own note's files (it was 403); staff otherwise still get 403. Two simultaneous
  registrations on one entity are serialized (at the limit, the second is `ATTACHMENT_LIMIT_REACHED`); the second of
  two simultaneous removals is `404`.
- Not in the document (INC-47): every keyed follow-up write (`POST /tasks`, `DELETE /tasks/{id}`, the completion and
  the reopening, `POST /attachments/upload-url`, `POST /attachments`, `DELETE /attachments/{id}`, `POST
  /followup/{id}/read`, `POST /followup/read-all`, `PUT /dogs/{id}/observations`) retries a Mongo write conflict in its
  own transaction and answers the spec's code, never `500`. A stored `204` is replayed without `Content-Type`.

## 2026-09-30 · E5-T27 round 2 · the cancellation's origin, public file sandbox, health CORS

**0 operations added or removed, 1 changed (description only); 0 schemas changed.**
- **`POST /bookings/{id}/cancellation`**, description (review #3, R-08-19): a dual-role account's own cancellation has
  `origin APP` and `cancelReason MEMBER` (MEMBER was never an origin; the round-1 text said «origin MEMBER»); another
  member's booking is cancelled as an instructor (`origin INSTRUCTOR`). Same request and answers.
- Not in the document:
  - `GET /public/{clubSlug}/activities/{slug}/files/{fileId}` (review #1, ruling E71, CONVENCIONS_API §5): every file
    carries `Content-Security-Policy: …; sandbox` (images and videos `inline`, an SVG as an `attachment`), except a PDF
    shown `inline`, which keeps the api's policy without `sandbox`.
  - `GET /health` (review #2, ruling E71): CORS allows the configured platform hosts only (`CORS_PLATFORM_HOSTS`) and
    never looks a club's domain up in the database; another origin gets the same `UP`/`DOWN` envelope without CORS
    headers. Every other route keeps the club's CORS.

## 2026-09-28 · E5-T27 · audit corrections (identity, security, shared): launchUrl, health DOWN, keyed PUT/DELETE, dual roles

**0 operations added or removed, 6 changed; 0 schemas added or removed, 2 changed.** The web must regenerate its client.
- **`POST /members/{id}/impersonation-token` → `ImpersonationTokenResponse.launchUrl`** (step 2, rulings E17/E47; INC-15):
  now filled, `https://{club app host}/entrar?handoff=<code>`. The code is a one-shot handoff code valid 60 s, bound to
  the grant; `apps/clubs` redeems it once with `POST /oauth2/token grant_type=urn:agilityhub:grant:handoff,
  client_id=clubs-app` and receives the grant's impersonation JWT (`imp`, `memberId`, `impersonatedMemberId`; no refresh
  token, no cookie). A second redemption, an expired code, a code of an ended grant or another client → `400
  HANDOFF_INVALID`. The JWT never travels in the URL; `token` and `expiresAt` stay for API clients. The schema becomes
  `nullable` (null only when the club has no verified club app domain). E4-W16 adopts it.
- **`GET /health`** (step 5, INC-01 semantics, audit A7-07): `200 {status: UP}` only when a Mongo `ping` answers within
  1 s; otherwise **`503`** with the same `HealthResponse` envelope and `status: DOWN` (this 503 is not an `ApiError`).
  `HealthResponse.status` publishes `enum: [UP, DOWN]`. Still public, global, no tenant, no locale, no data; the Docker
  HEALTHCHECK and both compose files are unchanged.
- **Keyed `PUT`/`DELETE`** (step 4, ruling E46; INC-23): no document change. The four routes that declare
  `Idempotency-Key` (`PUT /class-sessions/{id}/attendance`, `PUT /dogs/{id}/observations`, `DELETE /tasks/{id}`,
  `DELETE /attachments/{id}`) are filtered because their handlers declare the header, not through a hand-kept list; a
  GET is never filtered. `IdempotentReplayContractIT` checks every operation of this snapshot with a required key.
- **`PUT /me/password`** (step 3, ruling E49; INC-24), description: after a RESET magic link, `current` may be left out
  once within 15 minutes; a LOGIN link never allows it. Same request and answers.
- **`POST /bookings/{id}/cancellation`** and **`POST /waitlist-entries/{id}/cancellation`** (step 1, ruling E41;
  INC-16), descriptions: an account with MEMBER and a staff role acts on its own (or its family group's) booking or entry
  as a member. Every member route (S07, S08, S09) now authorises `hasRole('MEMBER')` alone: MEMBER + ADMIN or MEMBER +
  INSTRUCTOR is served, a token without MEMBER is `403 FORBIDDEN` (no document change for those routes).
- Not in the document: signed downloads (step 8, ruling E61) answer an SVG as `attachment` and carry
  `Content-Security-Policy: …; sandbox`; CORS exposes `Content-Disposition`.

## 2026-09-28 · E7-T01 round 2 · the anonymous unsubscribe publishes `security: []`

**0 operations added or removed, 1 changed; 0 schemas changed.**
- **`POST /email-unsubscribes`** publishes `security: []`: no bearer, as the server already enforced (`permitAll`; the signed
  token authorises, the club comes from the host). It used to inherit the document's global bearer requirement, so a
  generated client would have sent (or demanded) a token (E7-T01 review #1). `E7ContractIT` now asserts every S11
  operation's effective requirement: `[]` for the anonymous one, the bearer for the other 20.

## 2026-09-27 · E7-T02 · S11 notification engine: VAPID key, the SMS guard status, bounce kinds, the unsubscribe served

**0 operations added or removed, 1 changed; 0 schemas added or removed, 3 changed.** The web must regenerate its client.
- **`GET /branding` → `BrandingResponse.pushPublicKey`** (new, required, nullable string): the product's VAPID public key
  (base64url) the browser passes to `PushManager.subscribe` (S11 R-11-07, S02); `null` when the club has no `PUSH`
  module or the product has no key (local/test without `VAPID_PUBLIC_KEY`).
- **`DeliveryStatus`** gains `SKIPPED_NOT_ALLOWED`: outside `prod`, an SMS to a number missing from `SMS_ALLOWED_NUMBERS`
  (organizer 24-09, E4-T05 review #1). Catalog proposal in the E7-T02 report, like the three `SKIPPED_*` of E7-T01.
- **`SendGridEvent.type`** (optional string): SendGrid's bounce kind — `bounce` (hard: the contact address is marked
  `bounced`, `EmailBounced`, N-51) or `blocked` (soft: the delivery fails, nothing is marked). R-11-08.
- **`POST /email-unsubscribes`** is served (no longer 501): the description loses «Contract only» and says that a second use
  of the link changes nothing. Its answers are unchanged (200 `EmailUnsubscribeResult`, 422 `UNSUBSCRIBE_TOKEN_INVALID`).

## 2026-09-27 · E6-T03 round 2 · D14 pages and authors (ruling E64)

**0 operations added or removed, 1 changed; 0 schemas added or removed, 1 description changed.**
- `GET /followup`: the `size` parameter publishes `enum: [20, 50]` (was `[20, 50, 200, 1000]`): D14 pages hold at most 50
  rows (S10 §3); `size=200` or `1000` is now `400 INVALID_FILTER`, as any size the list engine does not allow. The
  description says that `authorAccountId`, `authorName` and `authorGender` are whoever wrote the task or the note, as
  they were then, never the dog's current owner.
- `FollowupItem.authorGender`: description only (the writer's gender, stored with the row); same type, same enum.
- Not in the document: `TaskCreated` (outbox) now carries `textExcerpt`, the excerpt N-20 sends (see the task report).

## 2026-09-27 · E7-T01 · S11 communications contract (templates, log, feed, preferences, push, unsubscribe)

**20 operations added, 1 changed, 50 schemas added, 0 removed.** Every new operation answers `501 NOT_IMPLEMENTED` after
its role, tenant, impersonation, module and resource guards (E7-T02/E7-T03/E7-T04 serve them). Error statuses are
CATALEG_ERRORS' (rule 0): `CHANNEL_NOT_ALLOWED`, `INVALID_REMINDER_OPTION`, `PUSH_SUBSCRIPTION_INVALID`,
`UNSUBSCRIBE_TOKEN_INVALID`, `TEMPLATE_MANDATORY`, `TEMPLATE_NOT_CATALOG`, `TEMPLATE_NOT_CUSTOM`,
`TEMPLATE_NOT_SENDABLE` and `NO_RECIPIENTS` are **422** although S11 §6 writes 400/409 for most of them.
- D9 templates, ADMIN only (impersonation → 403): `GET /message-templates` (`category?`, `kind?`, `includeArchived?`) →
  `MessageTemplateList {items[MessageTemplateListItem], countsByCategory}` · `POST /message-templates` (201, CUSTOM) ·
  `GET /message-templates/{id}` → `MessageTemplateDetail` · `PUT /message-templates/{id}` · `POST …/{id}/preview` →
  `TemplatePreview` · `POST …/{id}/reset` · `DELETE …/{id}` (204, CUSTOM → ARCHIVED) · `POST …/{id}/send`
  (`Idempotency-Key` required; 202 `AnnouncementResult`, 200 on `dryRun`). A missing required variable is
  `400 VALIDATION_ERROR` with `details.missingVariables[]` (`MissingVariablesDetails`) until the catalog has S11's
  `TEMPLATE_MISSING_VARIABLE` (proposal).
- The log, ADMIN only: `GET /notifications` — universal list, `x-filterable: code, category, channel, status, memberId,
  createdAt`, `x-sortable: createdAt`, `x-columns: createdAt*, code*, recipient*, channels*, readAt`, `x-fields: id,
  createdAt, code, category, audience, recipient, channels, readAt`, `x-exportable: true`, default sort `createdAt desc`;
  `NotificationPage` of `NotificationListItem` (only `id` required) · `GET /notifications/filter-values` ·
  `GET /notifications/{id}` → `NotificationDetail` (frozen texts, `subject`, `deliveries[]` with `providerRef`/`lastError`).
- **Changed:** `GET /notifications/export` (E2-T01) now publishes the log's list contract (the same `x-filterable`,
  `x-sortable`, `x-columns`, `x-fields` and the `fields` parameter) and checks the query before its 501.
- Feed 11 (MEMBER, INSTRUCTOR, ADMIN; the impersonation token reads the member's feed): `GET /me/notifications`
  (`page`, `size`, `audience?`) → `MeNotifications {items[MeNotification], page, size, totalItems, unreadCount}` ·
  `POST /me/notifications/{id}/read` and `POST /me/notifications/read-all` → `ReadResult {unreadCount}`.
- «Avisos» of 12/D10: `GET /me/notification-preferences` → `NotificationPreferences` and `PUT` (MEMBER, also impersonated),
  `PUT /members/{id}/notification-preferences` (ADMIN). The request is partial: an absent key keeps its value and
  `reminderMinutesBefore: null` is «Mai».
- Push (PUSH module, impersonation → 403): `POST /push-subscriptions` (201 `PushSubscriptionCreated`),
  `DELETE /push-subscriptions/{id}` (204, own subscription only).
- `POST /email-unsubscribes` (anonymous, signed token; S11 §13 proposal) → `EmailUnsubscribeResult {category: CLUB_NEWS}`.
- Enums published once as components: `NotificationCategory`, `NotificationAudience`, `NotificationChannel`,
  `DeliveryStatus` (the catalog's six + `SKIPPED_NO_CONTACT`, `SKIPPED_CAP`, `SKIPPED_STALE`, S11 §13 proposals),
  `NotificationActionType`, `TemplateIcon`, `TemplateColor`, `TemplateKind`, `TemplateStatus`.
- The two S11 §6 JSON extracts (`GET /me/notifications`, `GET /me/notification-preferences`) are the contract fixtures
  `e7-me-notifications.json` and `e7-notification-preferences.json`; the web can build its MSW mocks from them.

## 2026-09-26 · E6-T03 · S10 tasks, attachments, observations and D14 follow-up are served

**0 operations added or removed; 1 optional property added; 15 descriptions changed; 1 parameter default changed; 4 error
lists extended.** The 14 follow-up routes of E6-T01 (`/tasks*`, `/followup*`, `PUT /dogs/{id}/observations`, `GET
/attachments`, `DELETE /attachments/{id}`) and the TASK / DOG_OBSERVATIONS registration of `POST /attachments` no longer
answer `501 NOT_IMPLEMENTED`: their descriptions lose the «Contract only» sentence and say what the api decides. The web
must regenerate its client.
- **`FollowupItem.authorGender`** (optional, nullable, `MALE|FEMALE|OTHER`; S10 §6 «Creador = Laura (alumna), per
  `gender`»): the member's gender on MEMBER_NOTE rows, for D14's «{nom} (alumne/alumna)»; `null` on TASK rows. It is in
  `x-fields` of `GET /followup`.
- **`GET /tasks` `includeDone` defaults to `true`** (S10 §6; E6-T01 round-2 review #2): the history shows PENDING and
  DONE unless `includeDone=false`; `state` picks one state and wins over it. `404 NOT_FOUND` is now listed (a staff
  caller's unknown dog).
- Error lists: `POST /tasks` + `FILE_TOO_LARGE`, `FILE_TYPE_NOT_ALLOWED` (the stored object of an `attachmentIds` key);
  `POST /attachments` + `VALIDATION_ERROR` (`name`); `PUT /dogs/{id}/observations` + `INVALID_STATE` (409
  `READMISSION_PENDING`, S04 R-04-06).
- Nullable fields of `Task`, `Actor` and `FollowupItem` are sent as `null` (not left out), like E6-T02's rows. A
  member reading a task gets `createdBy.accountId` and `doneBy.accountId` as `null`.
- `Observations.version` / the card's `observations.version` count the observation changes (`remarksMeta.version`),
  so a member's note or another write of the dog never makes an instructor's observations stale.
- `GET /followup` with `fields=` answers the page cut to `id` and the requested keys (CONVENCIONS_API §4), as every
  universal list; without it, the whole `FollowupPage`.

## 2026-09-26 · E6-T02 · S10 attendance, instructor day and week (with its PDF), student card, member history and `/attendances` are served

**0 operations added or removed; 2 optional properties added; 8 descriptions changed.** The 8 routes of E6-T01 that
this task serves no longer answer `501 NOT_IMPLEMENTED`: `GET /instructor/day`, `GET /instructor/week`, `GET
/instructor/week/export`, `GET` and `PUT /class-sessions/{id}/attendance`, `GET /attendances`, `GET
/dogs/{id}/instructor-card` and `GET /me/history`. Their descriptions lose the «Contract only; returns 501» sentence and
say what the api now decides. The web must regenerate its client for the two properties.
- **`AttendanceRow.memberFullName`** (optional, string; S10 R-10-00): only when `handlerName` differs from
  `memberFirstName`, the owner's full name, for the backoffice «(abonat: {nom i cognom})». Absent otherwise.
- **`SheetWaitlistEntry.handlerName`** (optional, nullable): `Dog.handlerName`, so 21's «Llista d'espera» shows
  «{guia} + {gos}» like the rows (R-10-00).
- Module-dependent fields are now **absent**, never `null`, as the schemas already said (not required, not nullable):
  `waiting` (WAITLIST), `waitlist` (WAITLIST), `pendingTasksCount` (TASKS, and until E6-T03 serves the follow-up port),
  `applied` (the `PUT` only), `InstructorCard.level` (`levels.enabled`), `instructorNote`, `tasks`, `observations`
  (TASKS, from E6-T03), `trainingsCount`, `trainingsPerWeek` (FREE_TRAINING). `WeekCell` leaves out every null field.
- `GET /class-sessions/{id}/attendance` answers `404` for a DRAFT class (it is on no instructor screen). The `PUT`
  answers `409 INVALID_STATE` for a DRAFT or CANCELLED class and `400 VALIDATION_ERROR` for a repeated `bookingId`.

## 2026-09-26 · E5-T25 · The S08 member flow's gaps (web E5-W01): the booked and the waiting dog, the in-time deadline, `BookedBy.self`, 03's ring colour and activity id, one `Idempotency-Key` per body

**0 operations added or removed; 7 properties added (5 required, 2 optional and nullable); 2 descriptions changed.** The
web must regenerate its client and can prune its three `pending.json` overlays (`x-schema-overlays`):
`Booking.dog`, `Booking.lateCancelThresholdMinutes` and `WaitlistEntry.dog`.

- **`Booking`** (`POST /bookings`, `POST /waitlist-entries/{id}/claim`, `GET /bookings/{id}`, `GET /me/bookings`, `POST
  /bookings/{id}/cancellation`; S08 §2 row 07, R-08-10):
  - `dog` (required, `HoldDog`: `id`, `name`, `sex`), as `SeatHoldResponse.dog`: the sex gives 07's Catalan article.
  - `lateCancelThresholdMinutes` (required, integer): the club's `bookings.lateCancelThresholdMinutes`, 240 at the Cànic.
    A MEMBER cannot read `/parameters`; 07's warning and late note name it.
  - `cancellableInTimeUntil` (required, date-time): the class start minus the threshold in elapsed time, computed by the
    api. A cancellation is in time while `now <= cancellableInTimeUntil` (R-08-10, the threshold itself is in time). Do no
    date arithmetic: across a DST change the wall-clock distance is not the threshold.
- **`BookedBy.self`** (required, boolean; 07's «Reservada per {name}»): `true` when the reader's own account made the
  booking. Stop comparing first names. While an admin impersonates a member, the reader is that member: a booking the
  member made reads `self: true`, one made while impersonating reads `self: false` and `viaClub: true`. `displayName` and
  `viaClub` are unchanged.
- **`WaitlistEntry.dog`** (required, `HoldDog`; the waiting-list detail): `dogName` stays.
- **`ReservationRow`** (`GET /me/home`, 03), two optional, nullable properties that the api always sends:
  - `ringColor`: the ring's colour on CLASS, CLASS_WAITLIST and TRAINING rows (the mockup's dot); `null` on ACTIVITY
    rows and for a ring without a colour.
  - `activityId`: on ACTIVITY rows, the activity of the registration `id` (S07 §2); `null` on the other rows.
- **`POST /bookings`** (R-08-08, amended 26-09): the description no longer says «Idempotency-Key = seatHoldId», and the
  header has a description. The key is one client UUID per request body: a retry of the same body reuses it and gets the
  same answer, a stored `409`/`422` included; a different body, for example another `swapBookingId` chosen after a failed
  attempt, takes a new key; the same key with another body answers `409 IDEMPOTENCY_KEY_REUSED`. Behaviour unchanged.
- **`BOOKING_NOT_CANCELLABLE`** is `422` (`CATALEG_ERRORS.md`, R-08-10, amended 26-09): unchanged. `POST
  /bookings/{id}/cancellation` publishes it under `422` only.

## 2026-09-26 · E5-T24 · D2's new file from the ADMIN's upload; self-authorising signed URLs; `x-fields` on exports; `id` filterable on five lists

**0 operations added or removed; 1 property made nullable; `x-fields` added on 9 operations (6 exports, 3 of them GET and
POST); the `fields` parameter removed from 3 operations; 6 `x-filterable` gain `id`; the `fields` parameter's description
changes wherever it is published, and 5 other descriptions change.** The web must regenerate its client:

- **`PATCH /dogs/{id}`** (S04 R-04-19, amended 26-09; web E4-W13 question 1). On a `PENDING` dog, a readmission's reused
  dog included, a new file in `documents` is the ADMIN's own `POST /attachments/upload-url` with `purpose = DOG_DOCUMENT`:
  upload it through its signed URL, then send its `fileKey` with the type's kept keys and the file's `name`. A key of
  another club, purpose or account, one claimed for another dog or type, one never uploaded, or one removed through S03
  answers `400 FILE_NOT_FOUND`. At most 10 new files per request. D2's view (`GET /members/{id}/signup`) then lists the
  file with that name and a `downloadUrl`. `DogPatch.documents` and the operation describe it; the operation also says
  that the card requirement applies only when the card's keys change (E5-T23 question 1).
- **Signed local file URLs** (CONVENCIONS_API §5, amended 26-09; web E4-W13 question 2). Not in the contract (hidden
  routes), behaviour only: `PUT /api/v1/attachments/uploads/{id}`, `GET /api/v1/attachments/files/{id}` and `GET
  /api/v1/signup/files` are authorised by their `expires` and `signature` alone. Send the upload with `Upload.headers`
  only, never a bearer; an `<img>` can read a download URL. A wrong signature or an expired URL answers `403 FORBIDDEN`.
  Another club's or an invalid bearer is not read.
- **Exports** (CONVENCIONS_API §4, amended 26-09). `GET|POST /members/export`, `/dogs/export`, `/audit-entries/export`
  and `GET /activities/export`, `/activity-registrations/export`, `/training-bookings/export` publish their list's
  `x-fields`. Without `columns`, `fields` picks the columns: its keys that are columns, in their order (the row id has no
  column); `fields` that names no column, or a key outside `x-fields`, answers `400 INVALID_FILTER`. With `columns`,
  `columns` decides, as before.
- **`fields` removed** from `GET /activities/filter-values`, `GET /invoices/export` and `GET /notifications/export`
  (contract-only exports). The `fields` parameter's description names this operation's `x-fields`.
- **`x-filterable` gains `id`** on `GET /weeks`, `/class-sessions`, `/ring-blocks`, `/training-bookings`,
  `/training-bookings/export` and `/bookings` (E5-T22 question 1): they already accepted it.
- **`TemplateClass.placementId`** is `["string", "null"]` (E5-T22 question 2): `GET /week-templates/{id}` and its writes
  send `null` for a class without a placement, and always without COURSES.
- **`GET /signup` in add-dog mode** (S04 R-04-09, amended 26-09; behaviour, and the descriptions of `SignupPlan.current` and
  `AddDogSignupRequest.planIdRequested`). A member whose own plan is
  no longer assignable (inactive, or its module off) counts as a member without a plan: no plan `current`, no
  `upfront.additionalDogOptions`, and `POST /me/dogs/signup` without `planIdRequested` answers `400 VALIDATION_ERROR`
  (`REQUIRED`). Before, that member's `GET /signup` answered `422 PLAN_NOT_AVAILABLE`.

## 2026-09-26 · E5-T23 · D2's card requirement only when the card changes

**0 operations, schemas or descriptions changed.** Behaviour only (R-04-19, R-04-08, R-04-06):

- `PATCH /dogs/{id}` with `signup.requireDogDocumentAtSignup`: a `VACCINATION_CARD` without files answers
  `422 DOG_DOCUMENT_REQUIRED` only when D2's view shows files for it, that is, when the edit changes the card. Sending
  back a `PENDING` card, alone or with another field, is no documents change (review E5-T21 #1). The description's
  «Sending back the view's keys is no change» already said so.
- The reused dog of a pending readmission: a documents edit merges into the request only the types whose keys differ
  from D2's view, as for a public signup's dog. A type sent back as shown no longer enters the request (review E5-T21 #4).
- Data, no contract change: the Cànic's seed now sets `paymentProviders.MANUAL.instructions` in `ca` and `es`, so its
  `GET /signup` sends `paymentMethods[MANUAL].instructions`, and its `GET /club` reads `MANUAL.configured: true`.

## 2026-09-26 · E5-T22 · `priceLabel` and `current` on `GET /signup` plans; `fields` honoured on every universal list; `id` filterable on D7's lists

**0 operations added or removed; 4 item schemas added and 2 page schemas renamed; 9 list item schemas narrowed to their row
id; 2 properties added; 1 property made nullable; 2 `x-fields` and 5 `x-filterable` changed; the `fields` parameter removed
from 3 contract-only operations.** The web must regenerate its client:

- **`GET /signup`** (S04 §6 and R-04-09, amended 26-09; S05 R-05-19). `SignupPlan` gains two optional properties:
  - `priceLabel`: the plan's `texts.priceLabel` in the reader's locale, when the plan has one. The Cànic's Teràpia reads
    «condicions i cost segons cada cas». This is screen 17's price line when the plan has no current price.
  - `current`: only in add-dog mode (a MEMBER); absent in the public signup.
    - `true` on the member's own plan. That plan is now listed even when the public offer hides it (M8), in catalog order,
      for example the family fare `ABONAT_FAMILIAR`. `upfront.planQuotes` quotes the same plans, one each.
    - `false` on every other plan.
    - A member without a plan (B34) gets the offer, with `current: false` on every plan. Their `POST /me/dogs/signup` then
      requires `planIdRequested`: without it, `400 VALIDATION_ERROR` with `fieldErrors: [{field: "planIdRequested",
      code: "REQUIRED"}]`, and nothing is stored. An empty offer keeps `planIdRequested` optional. The description of
      `AddDogSignupRequest.planIdRequested` says so.
- **Narrowed (breaking for generated types; CONVENCIONS_API §4).** These list item schemas now require only their row id,
  because `fields` may leave out every other key:
  - `id`: `WeekListItem`, `BookingListItem`, `TrainingBookingListItem`, `DogListItem`, `MemberListItem`,
    `AttendanceListItem`, `FollowupItem` and `AuditEntryListItem`;
  - `runId`: `JobRunListItem`.
  Without `fields`, every property is still sent.
- **New list item schemas:**
  - `GET /class-sessions` answers `ListPageClassSessionListItem` (was `ListPageClassSession`). Its items are
    `ClassSessionListItem`: `ClassSession`'s list keys, requiring only `id`.
  - `GET /ring-blocks` answers `ListPageRingBlockListItem` (was `ListPageRingBlock`, an `anyOf` of `RingBlock` and
    `RingBlockMemberView`). Its items are `RingBlockListItem`, requiring only `id`. A MEMBER's rows leave out `note` and
    `createdByName`, and asking for them in `fields` is `400 INVALID_FILTER`, as before.
  - `GET /members`: an INSTRUCTOR's items are `MemberInstructorListItem` (the keys of `MemberInstructorView`), requiring only
    `id`. `GET /members/{id}` keeps `MemberInstructorView`.
  - `GET /class-sessions/{id}/bookings` answers `ClassBookingItem`: the former `BookingListItem`, unchanged and whole.
- **Behaviour, `fields` now honoured.** An unrequested key is left out, a requested one keeps its value or its `null`, and
  the row id always comes:
  - `GET /bookings` and `GET /jobs/{name}/runs` used to return whole items whatever `fields` said.
  - `/weeks`, `/class-sessions`, `/ring-blocks` and `/training-bookings` already left keys out, but their schemas required
    them. `/training-bookings` sent `null` in place of an unrequested key.
- **`x-fields`:**
  - `GET /bookings` adds `dogName` and `memberName` (keys of the item it always sent).
  - `GET /jobs/{name}/runs` is the keys of `JobRunListItem`, from `runId` to `errorCount`, instead of `["id"]`. `fields=id`
    is now `400 INVALID_FILTER`.
- **`x-filterable`:** `id` is added on `GET /activities` (and its `filter-values` and `export`), on
  `GET /activities/{id}/registrations` and on `GET /activity-registrations/export`. These lists already accepted it.
- **Contract-only lists:** `GET /platform/audit-entries`, `/platform/erasure-requests` and `/platform/security-events` no
  longer publish the `fields` parameter (they publish no `x-fields`) until they are implemented.
- **`ClassSession.placementId`** (and `ClassSessionListItem`'s) is `["string", "null"]`. With COURSES, a class without a
  placement already sent `null`.
- **Behaviour, no schema change** (found by the new list contract test on the Cànic demo):
  - `GET /audit-entries`, `GET /members/{id}/audit-entries` and `GET /audit-entries/{id}`: every `changes[]` item sends
    `before` and `after`, `null` when there was no value. Before, a created value had no `before` key and a cleared one no
    `after`.
  - The same three: an instructor's action reads `origin: BACKOFFICE` (S14 §3), also for the `origin` filter. Before, it
    read `INSTRUCTOR`, which is not in the enum.
  - `GET /dogs` (ADMIN, BILLING and PACKS on): `pack` is sent only for a dog with a pack, with `PackSummary`'s keys. Before,
    every row carried an empty `pack: {}`.
  - `GET /me/activities`: the waiting ranks are read once for all the listed activities.
  - D7's list sets `waitlistRank` only on a row whose `state` is `WAITLISTED`.

## 2026-09-26 · E5-T21 · a nullable `ClubUpdate.taxId`; D2's documents edit, described

**0 operations or schemas added or removed; 1 property widened; 2 descriptions.** The web should regenerate its client:

- `PUT /club`: `ClubUpdate.taxId` is `["string", "null"]`, and its description ends «Blank (empty or spaces only) or null
  clears it.» (S02 §3, amended 26-09), like `displayCity`. Behaviour unchanged: `null` already cleared it. A club
  definition (`seeds/club-definition.schema.json`) still refuses `taxId: null`; it clears the tax id with `""`.
- `PATCH /dogs/{id}`: `DogPatch.documents` says that a kept file keeps its stored name, that the key of a file removed
  through `DELETE /dogs/{id}/documents/{docId}/files/{fileId}` answers `400 FILE_NOT_FOUND`, that the limit of 10 counts
  only the new uploads, and that sending back the view's keys is no change. `SignupDocumentFile.fileKey` (D2's view,
  `GET /members/{id}/signup`) says the same of the stored name.
- Behaviour, no schema change (R-04-19, R-04-08): a D2 documents edit, and the validation of a readmission's reused dog,
  keep the stored rows of files removed through S03, so a removed file stays removed and P9 still counts it as
  referenced; sending a removed key back is `400 FILE_NOT_FOUND`. An edit that changes no file key of any type writes
  nothing: no new `version`, no `SignupEdited`, no audit entry. A type that already has 10 files takes one more upload.
- Behaviour, at submission too (E5-T23, review E5-T21 #2): `POST /signup` of a readmission answers `400 FILE_NOT_FOUND`
  to the key of a file removed through S03 from the reused dog's own documents, and stores nothing. `POST /me/dogs/signup`
  answers the same to such a key: its dog is always a new one.
- Text, no schema change: `GET /signup` `paymentMethods[SEPA_DD].mandateText` in `ca` and `es` is word for word the
  approved mockup 19's («Autoritzo a … l'emissió de rebuts …», «Autorizo a … la emisión de recibos …»); `en` is unchanged.

## 2026-09-26 · E5-T20 · the E4 real-core follow-ups: sparse `fields` and `x-fields`, `waitlistRank`, the ring-conflicts errors, the day-grid `view` error

**0 operations or schemas added or removed; 2 list item schemas narrowed; 3 schemas gain `waitlistRank`; 14 list operations
gain `x-fields`; 3 operations change their documented errors.** The web must regenerate its client:

- **Narrowed (breaking for generated types):** `ActivityListItem` now requires only `id`, and `ActivityRegistrationListItem`
  only `registrationId` (CONVENCIONS_API §4, amended 26-09). With `fields=`, `GET /activities` and
  `GET /activities/{id}/registrations` leave out every key that was not requested: before, it came as `null` (or `false`).
  A requested key keeps its `null`. Without `fields=`, every property is still sent, `null` where it does not apply.
- `GET /activities/{id}/registrations`: `fields=id` is now `400 INVALID_FILTER` (the item has no `id`; its row id is
  `registrationId`), and so is `filter=activityId:…` (the activity comes from the path; it was never `x-filterable`).
  `appliedFilters` lists only the query's filters: the path's `activityId` is no longer there. `INVALID_FILTER` is now
  documented on this operation and on `GET /activities`.
- `waitlistRank` (integer ≥ 1 or `null`, R-07-08): the rank of a `WAITLISTED` registration among the activity's waiting
  entries, in `position` order, computed when read; after a promotion the next entry reads 1. `position` keeps the stored
  order and its gaps. Optional (and in `x-fields`) on `ActivityRegistrationListItem`; required (nullable) on
  `ActivityRegistration` (`POST /activity-registrations`, `GET /activity-registrations/{id}`, the cancellation,
  `GET /me/activities/{id}` `myRegistration`) and `ActivityRegistrationSummary` (`GET /me/activities` `mine[]`).
- `x-fields` (next to `x-filterable` and `x-sortable`) on every universal list: `/activities`,
  `/activities/{id}/registrations`, `/members`, `/dogs`, `/bookings`, `/weeks`, `/class-sessions`, `/ring-blocks`,
  `/training-bookings`, `/attendances`, `/followup`, `/jobs/{name}/runs`, `/audit-entries` and
  `/members/{id}/audit-entries`. It is every key the list accepts for any role; any other key is `400 INVALID_FILTER`.
  The `fields` parameter's description says so on every paged operation.
- `GET /activities/{id}/ring-conflicts` documents `400 INVALID_TIME_RANGE` and `422 OUTSIDE_OPENING_HOURS` (S07 §6). The
  422 is the publication's error for the same window; the 400 means there is no window to block (rings without a date or
  hours), a case the publication refuses earlier with `422 ACTIVITY_INCOMPLETE` (wording corrected by E5-T22). Behaviour:
  an activity with rings but no date now answers `400 INVALID_TIME_RANGE`, not `500`.
- Behaviour, no schema change: `GET /day-grid?view=foo` answers `400 VALIDATION_ERROR` with `details.field = "view"` and
  `details.fieldErrors = [{field: "view", code: "INVALID_VALUE"}]` (before, `details` was empty).
- Behaviour, no schema change (`GET /parameters`, CATALEG_PARAMETRES 26-09): `learn.baseUrl` and
  `learn.recommendationsTtlMinutes` are in the `system` block with `editableBy: PLATFORM` (a club `PUT` answers `403
  PLATFORM_ONLY`); `signup.onboardingFields` and `legal.maxPostpones` are in the `signup` block («Alta i consentiments»)
  with `editableBy: CLUB`.

## 2026-09-26 · E5-T19 · D2 adds or removes one file of a pending dog; a plain id for signup files

**0 operations or schemas added or removed; 1 response property added; 2 descriptions changed.** Additive:

- `SignupDocumentFile.fileKey` (required, string): each file row of `GET /members/{id}/signup` `dogs[].documents[].files[]`
  and `dogs[].readmission.current.documents[].files[]` carries its key (R-04-19).
- `PATCH /dogs/{id}` and `DogPatch.documents` (descriptions): a type's files are the ones sent. A `fileKey` that D2's view
  shows for that type keeps its file as it is; any other key is a new signup upload. With
  `signup.requireDogDocumentAtSignup`, a `VACCINATION_CARD` without files answers `422 DOG_DOCUMENT_REQUIRED` unless the
  reused dog of a pending readmission has its own card with a file (R-04-06).
- Behaviour, no schema change: a file claimed at signup gets a plain `id` (a UUID derived from its key), not its storage
  key, so `DELETE /dogs/{id}/documents/{docId}/files/{fileId}` addresses it (`DocumentFile.id`, `format: uuid`, now holds
  for these files too). The same `fileKey` sent twice in a type answers `400 VALIDATION_ERROR`
  (`documents.files.fileKey`, `DUPLICATE`): in a D2 edit (`PATCH /dogs/{id}`) and at submission (`POST /signup`,
  `POST /me/dogs/signup`; E5-T21, review E5-T19 #2). D2's view no longer lists a file removed through S03.

## 2026-09-26 · E5-T18 · the ES padding of `taxId`; the SEPA mandate text of mockup 19

**0 operations or schemas added or removed; 3 property descriptions changed.** Description only:

- `ClubSettings.taxId` (`GET /club`, `PUT /club`), `ClubUpdate.taxId` (`PUT /club`) and `ClubSummary.taxId`
  (`GET /branding`) now say what the `ES` profile already did since E5-T17 (S02 §3): a 7-digit DNI is stored padded to
  8 digits (`1234567L` → `01234567L`).
- Behaviour, no schema change: `GET /signup` `paymentMethods[SEPA_DD].mandateText` under the `ES` profile is the text
  of the approved mockup 19 (S04 §2 row 19, R-04-10), for example in `ca` «Autoritzo {legal name} a emetre rebuts sobre
  aquest compte amb caràcter indefinit mentre es mantingui la meva relació amb aquesta entitat (Llei 16/2009, de 13 de
  novembre, de serveis de pagament).». `GENERIC` is unchanged.

## 2026-09-26 · E3-T17 round 2 · the reused dog's record is frozen while its readmission is pending (E38)

**0 operations or schemas added; 12 operations changed: 11 gain `INVALID_STATE` in their documented 409s, and
`PATCH /dogs/{id}` gets its description only.** Additive: one more documented 409.

- `PATCH /dogs/{id}` (R-04-19): `documents` change only the types sent (never the card when it is not sent). On the
  reused dog of a pending readmission they merge into the request by type, and a type sent without files withdraws
  the submitted one. `handlerName` and `licenses` of that dog cannot change (`409 INVALID_STATE`,
  `details.reason = READMISSION_PENDING`). The response is the dog record: D2 re-reads `GET /members/{id}/signup`.
- The reused dog of a pending readmission is frozen (S04 R-04-06, amended 26-09): `409 INVALID_STATE` with
  `details.reason = READMISSION_PENDING` joins the documented 409 codes of `POST /dogs/{id}/documents`,
  `DELETE /dogs/{id}/documents/{docId}/files/{fileId}`, `POST /dogs/{id}/documents/reminder`, `PUT /dogs/{id}/photo`,
  `PATCH /dogs/{id}/level`, `PATCH /dogs/{id}/free-training`, `POST /dogs/{id}/transfer`, `PUT /me/dogs/{id}/photo`,
  `POST /me/dogs/{id}/documents`, `PUT /me/dogs/{id}/instructor-note` and `POST /attachments` (`INSTRUCTOR_NOTE`).

## 2026-09-26 · E5-T17 · a tax id of separators only; the E37 statuses of an early class

**0 operations or schemas changed** (the snapshot is byte-identical). Behaviour only:

- `PUT /club` (S02 §3 amended 26-09, R-02-06): a non-blank `taxId` that normalizes to nothing (`"-"`, `" / "`, `"."`)
  answers `400 VALIDATION_ERROR` with `details.field = taxId` and `fieldErrors [{field: taxId, code: INVALID_VALUE}]`,
  and the stored tax id stays. `""` or `null` clears it. Under the `ES` profile a 7-digit DNI is stored and returned
  padded (`1234567L` → `01234567L`).
- `GET /risk-review` and `GET /dashboard` (`riskReview`, S15 §6 E37 amended 25-09): a class that starts at or before its
  day's review time is `AT_RISK`, never `WILL_CANCEL`/`WILL_REVIEW` (D1: never `WILL_CANCEL`/`PENDING_DECISION`).

## 2026-09-26 · E3-T17 · the reused dog of a pending readmission on D2 (E38)

**0 operations added; 1 changed (description and error list); 2 schemas added** (`SignupDogReadmission`,
`SignupDogValues`), 1 changed (`SignupDogView`). Additive: an optional block and one more documented 409.

- `GET /members/{id}/signup` (`SignupDogView.readmission`, optional; S04 R-04-06, E38): only for the reused dog of a
  pending readmission. The dog's fields then show the submitted values and the documents the validation will write;
  `readmission.current` gives the dog record's own values and documents, `changedFields` the fields that differ, and
  `previousDeactivatedAt`/`previousDeactivationReason` what a rejection restores. Absent for any other dog.
- `PATCH /dogs/{id}` (R-04-19): on the reused dog of a pending readmission, the step-17 fields and the documents edit the
  submitted values, not the dog record; changing its chip answers `409 INVALID_STATE` with
  `details.reason = READMISSION_PENDING` (as the member's identity document, R-04-06 b). `INVALID_STATE` joins the
  documented 409 codes.

## 2026-09-26 · E5-T16 · the nulls of GET /club, the dashboard and the training override; a normalized tax id

**0 operations added or changed; 0 schemas added**, 12 changed (`ClubSettings`, `ClubAddress`, `ClubDomain`, `ClubLegal`,
`ClubPwa`, `Theme`, `LastChange`, `Dashboard`, `DashboardKpis`, `TrainingBookingRequest`; descriptions only in
`ClubSummary` and `ClubUpdate`). Additive: types widen to accept the `null` the api already sends, and three maps that
are always sent become required. No value on the wire changes, apart from the normalized `taxId`.

- `GET /club`, `PUT /club` (`ClubSettings`, R-02-12; review E3-T16 #1, INC-08): `legalName`, `taxId`, `contactEmail`,
  `contactPhone` and `websiteUrl` are `string | null`; `address`, `pwa`, `legal` and `lastChange` are the union
  `anyOf [$ref, null]`. `paymentProviders` is required (always sent, `{}` without providers).
  - The records they reach: every `ClubAddress` field, `ClubDomain.verifiedAt` (pending domain), `ClubLegal.imageConsentText`,
    `ClubPwa.name`/`shortName` and `Theme.logoUrl`/`logoDarkUrl`/`markUrl` are `… | null`. `ClubLegal.imageConsentTextI18n`
    and `ClubPwa.iconUrls` are required (always sent, `{}` when empty). `Theme` is also `/branding.theme`.
  - `LastChange.actorName` is `string | null` (an audit entry without an actor), wherever `LastChange` appears.
- `GET /dashboard` (`Dashboard.riskReview`, `pendingSignups`, `dogsByLevel`; the four `DashboardKpis` blocks; S14 §6):
  still required, and now the union `anyOf [$ref, null]` instead of the `$ref` beside `type: [object, null]`, which a
  JSON Schema 2020-12 validator refused for `null`.
- `POST /training-bookings` (`TrainingBookingRequest.override`, R-09-16): the union `anyOf [TrainingOverride, null]`
  instead of the `$ref` beside `type: "null"`, which accepted neither value.
- `taxId` (S02 §3, R-02-06; review E3-T16 #3): stored normalized, upper case, without spaces or separators;
  `ClubSettings.taxId`, `/branding.club.taxId` and the `ClubUpdate.taxId` description say so. `PUT /club` with
  `"g-6318 9617"` stores and publishes `G63189617`; the same id with other spacing is no change. `PUT /club {taxId: ""}`
  now stores and returns `null`, where it used to return `""` (added in E5-T17, review E5-T16 #7).
- The snapshot has no `$ref` with a sibling `type` any more (`E3GateFixesContractTest`).

## 2026-09-25 · E5-T15 · staff class detail, app row hours, descriptions

**0 operations added, 1 changed (`GET /class-sessions/{id}` description); 0 schemas added**, 6 changed (`ClassSession`,
`RegisteredActivity`, `ActivityRow`, `ActivityRegistrationListItem`, `Activity`, `MemberActivityDetail`). Additive.

- `GET /class-sessions/{id}` for ADMIN/INSTRUCTOR (web E4-W03 question 3): `ClassSession` gains two optional properties,
  sent only by this detail: `instructorNames` (`string[]`, in `instructorIds` order) and `ring` (`ClassRing | null`,
  `null` for a class without a ring; always sent in the detail). The create/patch answers and the calendar rows do not
  carry them. The member projection is unchanged (it already had `ring`).
- `RegisteredActivity` (the `activity` of a registration) and `ActivityRow` (`GET /me/activities` `bookable[]`) gain
  `startTime` and `endTime`: required, club-local `HH:mm` or `null` (R-07-13). A date-only activity has both `null`, so
  its row never reads «0:00»; `startsAtLocal` keeps the day's 00:00.
- Descriptions only: `ActivityRegistrationListItem.position` (kept after a waitlisted registration is cancelled; `null`
  once promoted, or if it was never waitlisted), and `Activity.endsAt` / `MemberActivityDetail.endsAt` (a derived
  instant, the next local 00:00 for an activity without `endTime`; clients display `startTime`/`endTime`).
- `GET /activities?fields=…`: a property that is not selected is `null`, never a primitive default (`allRings` was
  `false`). The schema is unchanged.

## 2026-09-25 · E4-T06 · activity read models for D7 and the app

**0 operations added, 7 changed; 0 schemas added**, 4 changed (`ActivityListItem`, `ActivityRegistrationListItem`,
`RegisteredActivity`, `ActivityRow`). Additive: new properties, and properties that are now always sent. Two
`endsAtLocal` fields widen to `string | null`.

- `GET /activities` (S07 §2 D7, R-07-01, R-07-11, R-07-13): `ActivityListItem` gains the D7 columns, all required:
  - `typeDisplay`: the label of `type` in the reader's locale, or the club's free label;
  - `startTime` and `endTime`: club-local `HH:mm` or `null`. `endTime` is `null` when the activity has no end;
  - `allRings`: the rings are every active ring of the catalog;
  - `location`: `string | null`, the free text of an activity away from the club;
  - `maxPlaces`: `integer | null`, `null` without a maximum.

  The values are those of `GET /activities/{id}`. `fields=` can select them.
- `GET /activities/{id}/registrations`: `ActivityRegistrationListItem.cancelReason`, `cancelledAt` and `position` are
  now required, and `null` until they apply. `cancelReason` keeps `null` in its `enum`. The serializer always sends
  them, and never omits them.
- `POST /activity-registrations`, `GET /activity-registrations/{id}`, `POST /activity-registrations/{id}/cancellation`,
  `GET /me/activities` (`mine[].activity`) and `GET /me/activities/{activityId}` (`myRegistration.activity`):
  `RegisteredActivity.endsAtLocal` is `string | null` (was `string`). It is `null` for an activity without an end
  time (S07 «Canvis» 24-09, point 3).
- `GET /me/activities` (`bookable[]`): `ActivityRow.endsAtLocal` is `string | null` (was `string`), with the same rule.
- `GET /me/home`: the schema is unchanged (`ReservationRow.endsAtLocal` was already nullable). An `ACTIVITY` row of an
  activity without an end now sends `null` instead of the next day at 00:00.

## 2026-09-25 · E3-T16 round 2 · the nulls the api sends, the tax id check

**0 operations added, 21 changed; 0 schemas added**, 14 changed (`Member`, `PaymentMethodView`, `Address`, `Phone`,
`BookingBlock`, `ImageRights`, `DisplayStatus`, `PlanReference`, `LevelSummary`, `FamilyMember`, `FamilyDog`,
`ClubSummary`, `ClubSettings`, `ClubUpdate`). Only widenings to `null` and descriptions: nothing becomes required or
optional.

- `GET /members/{id}/signup` (the D2 view), and every operation that returns the same schemas (`GET /members`,
  `/members/{id}`, `/members/{id}/overview`, `PATCH /members/{id}`, `/members/{id}/payment-method`,
  `POST /members/{id}/booking-block`, `GET /dogs`, `/dogs/{id}`, `PATCH /dogs/{id}/level`, `GET /me/dogs`,
  `GET|PATCH /me/profile`, `GET /me/family-group`, `GET|POST|PUT /family-groups…`, `GET /signup`): these records send
  every property, so an optional one without a value arrives as `null`. It is now declared: `type: [x, "null"]`, or
  `anyOf: [{$ref}, {type: "null"}]` for an object (`Member.idDocument`, `paymentMethod`, `consents`, `plan`).
  `PlanReference.billingMode` lists `null` in its `enum`. The web's E3-W08 examples: `member.plan`, `planId`,
  `maskedAccount`, and `readmission.submitted.paymentMethod.maskedAccount` / `channel`. `MemberPatch` and
  `MeProfilePatch` reuse `Address` and `Phone`, and already accepted `null` for `province`, `country` and `label`.
- `GET /members/{id}/signup`: a found family claim's `familyGroupClaim.holder` is the whole `FamilyMember`, with its
  `memberNumber` (`null` while pending) and its ACTIVE `dogs`. The required `dogs` used to be `null`.
- `GET /branding`: `ClubSummary.legalAddress` stays required, as `anyOf: [{$ref: LegalAddress}, {type: "null"}]`.
  The round-1 form (`null` among the types beside the `$ref`) still had to match the `$ref`, so a JSON Schema
  2020-12 validator refused the `null` the api sends.
- `GET /club`, `PUT /club`: `displayCity` is `string | null`. `ClubUpdate.taxId`: checked by the club's country
  profile only when it changes (`GENERIC` does not validate it; S02 R-02-06); a refused one answers
  `400 VALIDATION_ERROR` with `details.field: "taxId"` and `details.fieldErrors`.

## 2026-09-25 · E3-T16 · the member's document types, the public footer, the list page sizes

**0 operations added, 30 changed; 2 schemas added** (`DogDocumentType`, `LegalAddress`), 4 changed (`MeDogs`,
`ClubSummary`, `ClubSettings`, `ClubUpdate`). Additive, except that the documented `size` values are now closed:

- `GET /me/dogs` (R-03-15, R-03-32; S03 §6 amended 25-09): `MeDogs.documentTypes` (required) `[{key, label,
  required}]`, the club's `census.dogDocumentTypes` in catalog order, `label` in the reader's locale (fallback
  `club.defaultLocale`). Also for a member without dogs and under impersonation. Screen 13's «＋ DOC.» reads it: a
  MEMBER still gets `403` on `/parameters/*`.
- `GET /branding` (R-02-02, amended 25-09): `ClubSummary.city` is now required (`string | null`) and is
  `displayCity ?? address.city`. New required `ClubSummary.legalAddress: LegalAddress {street, postalCode, city} |
  null`, the registered office for the public footer (LSSI art. 10), `null` without a street or a postal code.
- `GET /club` and `PUT /club` (S02 §3): `ClubSettings.displayCity` and `ClubUpdate.displayCity` (optional; `null`
  clears it). `ClubSettings.taxId` loses its wrong `format: uuid`. `PUT /club` answers `400 VALIDATION_ERROR` for a
  `taxId` the club's country profile refuses (`ES`: a CIF, NIF or NIE with its check character).
- The 29 operations with the shared list `size` parameter (CONVENCIONS_API §4, amended 25-09): `size` is `enum [20,
  50, 200, 1000]`, default 50 (was `minimum: 1`). Any other value is `400 INVALID_FILTER`; a value above 1000 is now
  refused too (it was cut to 1000). `GET /tasks` keeps its own `size` (an S10 stub).

## 2026-09-25 · E3-T14 round 2 · the D2 methods of an add-dog are not assignable

**0 operations added, 1 changed; 0 schemas added** (descriptions only):

- `GET /members/{id}/signup`: for an add-dog (the member is not `PENDING`), `MemberSignupView.paymentMethods` holds
  only the member's current method, with `assignable = false`. The D2 `PATCH /members/{id}` answers `400
  VALIDATION_ERROR` (`paymentMethod` `READ_ONLY`) then; the method changes through D10 (R-04-19). The descriptions of
  `paymentMethods` and `SignupPaymentMethodOption` say so, and that `current` is the submitted method during a pending
  readmission. For a `PENDING` member nothing changes.

## 2026-09-25 · E3-T14 · the payment methods of a signup: enabled providers only; the D2 view lists them

**0 operations added, 1 changed; 1 schema added** (additive):

- `GET /members/{id}/signup`: `MemberSignupView.paymentMethods` (required, new schema `SignupPaymentMethodOption
  {type, label, current, assignable}`), the D2 method selector (web E3-W07 round 2). It lists the methods `GET /signup`
  offers, in the same order, all `assignable`, and marks the applicant's method `current`. When the applicant's method
  belongs to a provider that has been disabled since, it is listed last with `assignable = false`. The list is empty
  without BILLING.
- Behaviour behind unchanged schemas (R-04-10): `GET /signup.paymentMethods` offers only the providers whose
  `CLUB.paymentProviders.{provider}.enabled` is `true`, the same flag `GET /club` shows. A provider listed without
  `enabled: true` is off; `configured` does not gate the offer. `POST /signup` and the D2 `PATCH /members/{id}`
  answer `422 PAYMENT_METHOD_NOT_AVAILABLE` for any other method. The Stripe checks that answer `422
  PAYMENT_PROVIDER_NOT_ENABLED` read the same flag.

## 2026-09-25 · E3-T12 round 2 · `SignupFirstMonth.portion` may be `null`; `firstMonth` follows the answer's lines

**0 operations added, 2 changed; 1 schema changed** (`SignupFirstMonth`):

- `GET /members/{id}/signup` and `POST /members/{id}/validation?dryRun=true`: `SignupFirstMonth.portion` is still
  always present, and now `FULL` · `HALF` · `null` (`type: [string, null]`). `null` only for a first month frozen before
  the portion was stored (E3-T12) whose frozen amount is neither the plan's monthly price on the submission day nor its
  half. Such a snapshot is never read with today's `signup.firstMonthSplitDay` (R-04-15). A generated client must accept
  `null` there and then show no «(mitja quota)».
- Behaviour behind unchanged schemas: `SignupUpfrontReview.firstMonth` is present only when the answer's own `lines`
  have a `FIRST_MONTH` row. An unchanged-plan dry run of a submission made while BILLING was off has none, so it has no
  `firstMonth` either.

## 2026-09-25 · E3-T12 · the first month in the D2 view; the checkout of a pending readmission

**0 operations added, 2 changed; 1 schema added** (additive):

- `GET /members/{id}/signup` (`MemberSignupView.upfront`) and `POST /members/{id}/validation?dryRun=true`
  (`ValidationDryRun.upfront`): `SignupUpfrontReview.firstMonth` (optional, new schema `SignupFirstMonth
  {option, portion, startDate, amountDue}`, `portion` ∈ `FULL` · `HALF`), present only when the submission has a
  `FIRST_MONTH` line (R-04-15, web E3-W07). The view gives the values frozen at submission; the dry run the ones the
  validation will charge (the frozen ones, or the recalculated ones after a plan change). D2 names the month and
  «(mitja quota)» without the rule.
- Behaviour behind unchanged schemas: `POST /checkout-sessions` of a pending readmission sends the applicant's submitted
  primary address to the provider as the customer email, not the LEFT record's (R-04-06 c, R-04-26).

## 2026-09-25 · E3-T10 round 2 · nullable enums list `null`; the D2 PATCH error lists

**0 operations added, 2 changed; 14 schemas corrected** (paths and response shapes unchanged):

- Nullable enums (review of E3-T10 #3, generalised): every schema whose `type` lists `null` and that has an `enum` now
  lists `null` in the `enum` as well, so a JSON Schema 2020-12 validator accepts the `null` the type allows. It is done
  once, where the document is written (`OpenApiConfiguration.nullableEnumsModule`), and checked by
  `OpenApiNullableEnumContractTest`. The 14 of 25-09: `ActivityRegistrationListItem.cancelReason`, `Actor.gender`,
  `BookableClass.notBookableReason`, `CardMember.gender`, `HomeMember.gender`, `JobRun.skipReason`,
  `JobRunListItem.skipReason`, `JobScheduleView.dayOfWeek`, `JobSummary.module`, `RiskNotified.gender`, `SlotCell.reason`,
  `TrainingBooking.cancelReason`, `TrainingBooking.cancelledBy` and `WaitlistEntry.cancelReason`. A generated client
  that rejected `null` there now accepts it; the values themselves do not change.
- `PATCH /members/{id}` (D2 edit of a pending signup, S04 §6): + `INVALID_ID_DOCUMENT`, `INVALID_PHONE`, `INVALID_IBAN`
  (400), `PAYMENT_METHOD_NOT_AVAILABLE`, `PLAN_NOT_AVAILABLE` (422). Now in `S04ErrorContractTest`.
- `PATCH /dogs/{id}` (D2 edit of a pending dog's documents): + `VALIDATION_ERROR`, `FILE_NOT_FOUND`, `FILE_TOO_LARGE`,
  `FILE_TYPE_NOT_ALLOWED` (400), `DOCUMENT_TYPE_UNKNOWN`, `DOG_DOCUMENT_REQUIRED` (422). Now in `S04ErrorContractTest`.
- Behaviour behind unchanged schemas: `POST /checkout-sessions` charges the rows the member owes (`UpfrontPayment.memberId`),
  also those of a dog transferred to another member after an unpaid validation (S03 R-03-14).

## 2026-09-25 · E3-T09 round 2 · the pending readmission (R-04-06 a–d): DNI locked in D2, account kept, matching

**0 operations added, 3 changed** (descriptions and one error list; no schema change):

- `PATCH /members/{id}`: + `409 INVALID_STATE` with `details.reason = READMISSION_PENDING` when a pending readmission's
  identity document would change (a wrong one is resolved by rejecting the readmission); the description states that the
  other person fields of a pending readmission edit the submitted values.
- `POST /signup/identity-checks`: a pending readmission is also matched on the primary address it submitted
  (`SIGNUP_ALREADY_PENDING`); the N-39 cap per recipient is `signup.rateLimit.notificationsPerRecipientPerHour` (3).
- `POST /members/{id}/validation`: a member who has an account keeps it (no second `Account`, the login email never
  changes, R-04-22).

## 2026-09-25 · E3-T10 · gate E3 fixes (api 3/3): legal identity on /branding, D1 number types, S04 error lists

**0 operations added, 11 changed** (paths unchanged). Not additive where marked:

- `GET /branding` (`ClubSummary`): `legalName` and `taxId` (string or `null`, **required**): the club's public legal
  identity for the public footer (R-02-02 amended 24-09, LSSI art. 10).
- `GET /dashboard` (`ClassOccupancyKpi`), R-14-03 and S14 §9:
  - `percent` is an **integer** (`int32`) or `null`, was a number (`87.0` → `87`); **type change**;
  - `waitingTotal` is an integer or **`null`** (always present): `null` when `WAITLIST` is disabled, was `0`; **nullable now**.
- `GET /dashboard` (`RiskNotified.gender`): string or `null` (a member without a gender on file); **nullable now**.
- `GET /dashboard/counters`: `followUpUnread` is `0` when `TASKS` is disabled (value change, R-14-08).
- S04 error responses (E3-T10 step 9; the per-status descriptions list the codes each handler can throw, checked by
  `S04ErrorContractTest`):
  - `POST /signup`: + `VALIDATION_ERROR`, `INVALID_IBAN`, `MEMBER_ERASED`, `ID_DOCUMENT_ALREADY_EXISTS` and
    `CHIP_ALREADY_EXISTS` (a concurrent twin submission), `DOCUMENT_TYPE_UNKNOWN`, `FILE_TYPE_NOT_ALLOWED`,
    `FILE_TOO_LARGE`, `IDEMPOTENCY_KEY_REUSED`, `STALE_VERSION` (retries exhausted);
  - `POST /me/dogs/signup`: + `VALIDATION_ERROR`, `CHIP_ALREADY_EXISTS`, `DOCUMENT_TYPE_UNKNOWN`,
    `FILE_TYPE_NOT_ALLOWED`, `FILE_TOO_LARGE`, `IDEMPOTENCY_KEY_REUSED`, `STALE_VERSION`; − `PAYMENT_METHOD_NOT_AVAILABLE`
    (never thrown: the add-dog keeps the member's method);
  - `POST /signup/identity-checks`: − `ID_DOCUMENT_AMBIGUOUS` (the contract has one `idDocument`, so it cannot happen);
  - `GET /members/{id}/signup`: + `MEMBER_ERASED`, `PLAN_NOT_AVAILABLE` (a requested plan no longer assignable);
    description: for an ACTIVE member (add-dog) `signup` is the oldest pending dog's own submission;
  - `POST /members/{id}/validation`: + `VALIDATION_ERROR`, `LEVEL_NOT_ACTIVE`, `FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP`;
    description: the `nextInvoiceDate` check uses the first-month start frozen at submission while the plan is the
    requested one, and the validation assigns the levels itself (no `DogLevelChanged`);
  - `POST /members/{id}/rejection`: + `VALIDATION_ERROR`;
  - `POST /checkout-sessions`: + `VALIDATION_ERROR`, `MEMBER_ERASED`, `IDEMPOTENCY_KEY_REUSED`, `MODULE_DISABLED` (BILLING).

## 2026-09-24 · E3-T09 · gate E3 fixes (api 2/3): signed upload headers, readmission blocks (E38), anonymous-route statuses

**0 operations added, 6 changed** (paths unchanged). Additive except where marked:

- `POST /signup/upload-urls` (`UploadUrl`): `headers` (map, **required**): the headers the storage signed
  (`Content-Type`, `If-None-Match: *`). The client must send them unchanged with the PUT; on S3 a PUT without them
  answers 403, and a second PUT on the same key 412 (M16, R-04-08). The add-dog flow uses the same route.
- `GET /members/{id}/signup` (`MemberSignupView`): optional `readmission` (`SignupReadmission{current, submitted:
  ReadmissionValues, changedFields[], consents[ReadmissionConsent], previousLeftAt?, previousLeftReason?}`), only for a
  pending readmission (R-04-06, E38): the LEFT record keeps its values until validation, which applies the submitted
  ones. `ReadmissionValues{firstName, lastName1, lastName2?, gender?, birthDate?, contactEmails[], phones[], address?,
  paymentMethod? (masked PaymentMethodView)}`.
- Anonymous-route statuses (R-04-27, R-04-20):
  - `POST /signup/identity-checks`, `POST /signup/family-group-lookups` and `POST /signup/upload-urls` (anonymous)
    answer `422 SIGNUP_CLOSED` when `signup.enabled = false` or the club is not `ACTIVE`, like `POST /signup` (the
    local `PUT /signup/uploads` too; hidden route);
  - `400 VALIDATION_ERROR` on the new bounds: `idDocument.value` ≤ 30, each email ≤ 254, `holderName` ≤ 120 and
    `dogName` ≤ 40 characters (also in `SignupRequest.person` and `familyGroupClaim`); the `maxLength`s are in the schemas;
  - `maskedEmail` has the R-04-05 shape («m•••a@e•••.cat») and masks the account's access address, where N-39 goes
    (value change).
- `POST /members/{id}/rejection`: a rejected readmission returns to `LEFT` with its original `leftAt`, `leftReason` and
  data (description; E38).
- Audit (`GET /audit-entries`, `/audit-entries/{id}`, `/audit-entries/filter-values`): `AuditAction` gains `SIGNUP_SUBMITTED` (a readmission
  submission) and `AuditOrigin` gains `PUBLIC` (its origin). **Enum additions**: a client with an exhaustive switch
  must add both.

## 2026-09-24 · E3-T08 round 2 · `PAID_EXCEEDS_QUOTE` (E39b) and the billed price of a plan option

**0 operations added, 2 changed** (paths unchanged), additive:

- `POST /members/{id}/validation`:
  - `SignupWarning` gains `PAID_EXCEEDS_QUOTE` (S04 §5, E39b): a plan change leaves more paid than the new quote.
  - `dryRun=true` (`ValidationDryRun.upfront`, schema `SignupUpfrontReview`): optional `paidExceedsQuote` (Money).
  - `ValidationResult`: `warnings[]` (`SignupWarning`, required, empty unless `PAID_EXCEEDS_QUOTE`) and optional
    `paidExceedsQuote` (Money). The refund is S12's.
  - `ValidationDryRun.price` is the price the plan bills: the `MAINTENANCE_FEE` price for a `MAINTENANCE` plan (value
    change, not schema).
- `GET /members/{id}/signup` (`SignupPlanOption.prices`): only the price the plan bills, i.e. the `priceId` that the
  validation accepts and stores (description change). `proposals.priceId` follows the same rule.

## 2026-09-24 · E3-T08 · gate E3 fixes (api 1/3): per-plan quotes, `planOptions`, `warnDays`, dog `version`, `/me/dogs` status, signup flags, new 409 reasons

**0 operations added, 3 changed** (paths unchanged). Additive except where marked:

- `GET /signup` (`SignupConfig`):
  - `upfront.planQuotes[]` (new, `SignupPlanQuote{planId, lines[{concept, amount}], totalDue, options[]}` with
    `SignupQuoteOption{option: TODAY|ALTERNATIVE, portion: FULL|HALF, startDate, amountDue, totalDue}`): one quote per
    offered plan, computed with the submission's code (R-04-14/15). Add-dog mode: also the member's own plan (maybe
    hidden), and the options are the additional-dog ones. `firstMonthOptions` stays, documented as superseded.
  - `upfront.additionalDogOptions` now always holds `TODAY`; `ALTERNATIVE` only up to `billing.upfrontCutoffDay`.
  - `allowFamilyGroupPending` (present with `FAMILY_GROUP`) and `requireDogDocumentAtSignup` (new booleans).
  - `texts.*` placeholders are resolved by the server; `texts.familyGroupIntro` is now **nullable** (always present,
    `null` without a family fare), so it left the `required` list.
  - `paymentMethods[MANUAL].instructions` = `CLUB.paymentProviders.MANUAL.instructions` (value change, not schema).
- `GET /members/{id}/signup` (`MemberSignupView`): `planOptions[]` (`SignupPlanOption{planId, name, type,
  prices[{priceId, amount, periodicity, concept}]}`, the assignable plans), `warnDays`, and `dogs[].version` (the dog's
  own version for `PATCH /dogs/{id}`). New `409 INVALID_STATE` response with `details.reason = NOT_PENDING`.
- `POST /members/{id}/validation`: `409 INVALID_STATE` gains `details.reason` = `NOT_PENDING` or `CHECKOUT_PENDING`
  (a plan change during a checkout, S04 §5 E39); `SignupWarning` gains `CHECKOUT_PENDING` (the `dryRun` warning);
  `nextInvoiceDate` before the first-month start → `400 VALIDATION_ERROR` on `nextInvoiceDate` (description only).
- `GET /me/dogs` (`MeDog`): `status` (`ACTIVE`|`PENDING`, required). **Not additive:** `documents` and `licenses` are no
  longer required, because a `PENDING` dog carries only `id, name, breed, sex, ageYears, status`.

## 2026-09-24 · E6-T01 round 2 · `Idempotency-Key` on `POST /attachments`

**0 operations added, 1 changed** (276 → 276): `POST /attachments` (E3-T03) now declares the optional
`Idempotency-Key` header (`uuid`) that S10 §6 marks «I = sí» (CONVENCIONS_API §7: accepted; the same key replays the
same `201` body, the same key with another body → `409 IDEMPOTENCY_KEY_REUSED`, added to its 409 response). No schema
changed. The S10 routes that publish the header are now 8: `PUT /class-sessions/{id}/attendance`,
`PUT /dogs/{id}/observations`, `POST /tasks`, `DELETE /tasks/{id}`, `POST /attachments` (optional),
`DELETE /attachments/{id}`, `POST /followup/{id}/read`, `POST /followup/read-all`.

## 2026-09-24 · E6-T01 · S10 contract (attendance, instructor aggregates, tasks, attachments, follow-up, history)

**22 operations added** (254 → 276). They answer `501 NOT_IMPLEMENTED` after the real tenant, role, impersonation,
module and resource guards until E6-T02 (attendance, instructor aggregates, history) and E6-T03 (tasks, attachments,
observations, D14):

- `GET /instructor/day` (20), `GET /instructor/week` (D12), `GET /instructor/week/export?format=pdf` (`application/pdf`),
  `GET /class-sessions/{id}/attendance` (21/D12), `PUT /class-sessions/{id}/attendance` (`Idempotency-Key`),
  `GET /attendances` (universal list: `x-filterable` `dogId, memberId, classSessionId, classDate, state`; `x-sortable`
  `classStartsAt, classDate`), `GET /dogs/{id}/instructor-card` (22/D13): INSTRUCTOR, ADMIN; impersonation →
  `403 IMPERSONATION_DENIED`, MEMBER → `403 FORBIDDEN`.
- `GET /me/history` (25): MEMBER, also the impersonation token; a dog that is not accessible → `404 DOG_NOT_ACCESSIBLE`.
- Under `TASKS` (`404 MODULE_DISABLED` when off): `PUT /dogs/{id}/observations` (`Idempotency-Key`), `GET /tasks`
  (MEMBER own dogs, also impersonated; `includeDeleted` ADMIN only), `POST /tasks` (201, `Idempotency-Key`),
  `GET|PATCH|DELETE /tasks/{id}` (`DELETE` 204 with `Idempotency-Key`), `POST /tasks/{id}/completion` (MEMBER owner also
  impersonated, INSTRUCTOR, ADMIN), `POST /tasks/{id}/reopening`, `GET /attachments?entityType&entityId`,
  `DELETE /attachments/{id}` (204, `Idempotency-Key`), `GET /followup` (universal list: `x-filterable`
  `kind, memberId, dogId, authorAccountId, unread`; `x-sortable` `activityAt`), `GET /followup/unread-count`,
  `POST /followup/{id}/read` and `POST /followup/read-all` (204, `Idempotency-Key`).

Schemas added (56): the five S10 §6 forms `InstructorDay`, `AttendanceSheet` (+ `applied[]` on the PUT),
`InstructorWeek`, `InstructorCard`, `MemberHistory`, with their parts, plus `AttendanceSaveRequest`,
`AttendanceListItem`, `Task`, `Actor`, `TaskList`, `TaskCreateRequest`, `TaskPatchRequest`, `AttachmentList`,
`FollowupPage`, `FollowupItem`, `FollowupUnreadCount`, `Observations`, `ObservationsRequest` and the error details
`StaleAttendanceDetails{current}`, `AttendanceBookingNotActiveDetails{bookingId}`, `AttendanceWindowClosedDetails{editableUntil}`,
`FileTooLargeDetails{maxSizeMb}`, `AttachmentLimitReachedDetails{max}`. Module-off fields are optional
(`pendingTasksCount`, `waiting`/`waitlist`, `instructorNote`/`tasks`/`observations`, `trainingsCount`/`trainingsPerWeek`).

Existing operations and schemas changed:

- `POST /attachments/upload-url`: `purpose` gains `TASK` and `DOG_OBSERVATIONS` (INSTRUCTOR, ADMIN; `TASKS`; same type
  and size rules); INSTRUCTOR is now an accepted role for those two purposes only; `IMPERSONATION_DENIED` documented.
- `POST /attachments`: `entityType` gains `TASK` and `DOG_OBSERVATIONS` (INSTRUCTOR, ADMIN; 501 until E6-T03);
  `NOT_FOUND`, `MODULE_DISABLED`, `IMPERSONATION_DENIED` documented; `ATTACHMENT_LIMIT_REACHED` now carries `details.max`.
- Schema `AttachmentResponse` is renamed **`Attachment`** (same six fields), the S10 name.
- `ClassSession` (staff view, only in `GET /weeks/{id}/calendar`) and `DayGridCell` (INSTRUCTOR view, CLASS cells) gain
  the optional `attendanceStatus` (`NONE · PENDING · DONE · CLOSED`), derived from the S10-owned `attendanceSummary`.
- `TASK_ALREADY_DONE` is 422 (was 409; `CATALEG_ERRORS.md` §3 rule 0).

Web adopters: regenerate the types (`AttachmentResponse` → `Attachment`); screens 20, 21, 22, 25, 26, D12, D13 and D14
can build against the examples of S10 §6 (fixtures `e6-*-responses.json`).

## 2026-09-24 · E5-T11 · `Level.progression` (E29); one key-first rule for the keyed public routes

- `Level` and `LevelReaderView` (`GET/POST /levels`, `GET/PATCH /levels/{id}`, `PUT /levels/order`): new required
  boolean `progression` (S05 §3, ruling E29). `LevelCreate.progression` and `LevelPatch.progression` are optional;
  on create it defaults to `true`, and a level stored before the field existed reads `true`. The `/levels` list
  contract gets the column `progression*`. Only progression levels count for the automatic «{first} i sup.»
  (S06 R-06-03): `displayDescription` / `description` of classes and templates change accordingly (e.g. {D,E,F,G}
  now reads «D i sup.» when Teràpia is last and outside the progression).
- Keyed public routes `GET /public/{clubSlug}/plans`, `GET /public/{clubSlug}/pages/{key}`,
  `GET /public/{clubSlug}/activities` and `GET /public/{clubSlug}/activities/{slug}`: the key is checked before the
  club, so an unknown slug answers **403 `INVALID_API_KEY`** (it was 404 `CLUB_NOT_FOUND` on plans and pages).
  `CLUB_NOT_FOUND` is removed from their documented errors; `CLUB_SUSPENDED` (403, own key only) is now documented on
  plans and activities. The keyless file redirect `…/activities/{slug}/files/{fileId}` keeps `CLUB_NOT_FOUND`.

Web adopters: regenerate the types (`Level.progression`, `LevelCreate/LevelPatch.progression`); the D11 «Nivells»
card gets the «Progressió» column and toggle (E4-W06). A public website that told «club not found» from «bad key»
must treat both as 403 `INVALID_API_KEY`.


## 2026-09-24 · E5-T06 round 2 · `activities` absent without ACTIVITIES; `dogName` only with «Tots»

- `GET /me/bookable-classes` → `BookableClasses.activities` is now optional and nullable: with the `ACTIVITIES`
  module off it is `null` (S08 §9 «04 sense bloc Activitats»), like `pack` and `singleClass`; it was `[]`.
- `GET /me/home` → `ReservationRow.dogName` (already nullable) is documented and served only with «Tots» (no `dogId`);
  with a dog selected every row has `dogName: null` (T-08-12 «amb {gos} només amb Tots»). `dogId` is still sent.

Web adopters: regenerate the types (`activities` nullable); screen 04 hides the «Activitats» block when it is null;
screen 03 no longer has to hide the dog name when a chip is selected.

## 2026-09-24 · E5-T09 · explicit error statuses and S05 rings follow S09 R-09-13

- Error statuses (organizer ruling, `CATALEG_ERRORS.md` §1): `JOB_UNKNOWN` 422 → **404** on the five `/jobs/{name}*`
  and `/platform/clubs/{clubId}/jobs/{name}/trigger` operations; `SLOT_NOT_ON_GRID` 422 → **400** and
  `OVERRIDE_NOT_ALLOWED` 422 → **403** on `POST /training-bookings` (the `override` description says 403).
- `PATCH /rings/{id}`: new optional body field `cancelBookings` (boolean) and a documented `422 RING_HAS_BOOKINGS
  {bookings[]}` when `allowsFreeTraining` goes off, or the ring is deactivated, with live training bookings
  (R-05-08 amended; S09 R-09-13). With `cancelBookings: true` they are cancelled as `CANCELLED_BY_CLUB /
  RING_NOT_RESERVABLE` in the same transaction. `409 RING_IN_USE` stays for future live classes (R-05-07).

Web adopters: regenerate the types (`RingPatch.cancelBookings`); map the three codes by code as before (their HTTP
status changed); D16 «Reservable per entrenaments» / «Desactiva» must handle `RING_HAS_BOOKINGS` with a confirmation
that resends `cancelBookings: true`, like D4 does for ring blocks.

## 2026-09-24 · E5-T06 · S08 WP-08-D aggregates served (the last 2 E5 operations no longer 501)

No path, parameter, request body or response shape change. Two things change:
- The `description` of `GET /me/home` and `GET /me/bookable-classes` drops «Contract only; returns 501…» and
  states the served rules: the four row sources and their modules on 03, the proposed dog (`lastDogForClass`, then
  the first own dog; no accessible dog → `404 DOG_NOT_ACCESSIBLE`), the exclusions and the 30 s base cache of 04.
- `HomeMember.gender` becomes optional and nullable (`MALE`/`FEMALE`/`OTHER` or null): members migrated or seeded
  without a declared gender have none, and the aggregate does not invent one.

Web adopters: regenerate the types for the nullable `gender`; nothing else.

## 2026-09-24 · E5-T05 · S15 routes served (8 operations no longer 501)

No path, parameter, request body, response or schema change: only the `description` of the eight S15
operations drops «Contract only; returns 501…»: `GET /jobs` · `GET /jobs/{name}/runs` · `GET /jobs/{name}/runs/{runId}` ·
`POST /jobs/{name}/trigger` · `PUT /jobs/{name}/switch` · `GET /risk-review` · `GET /platform/jobs/overview` (now also
states that only implemented processes with their module on are listed and that `status` keeps the cells of that
health) · `POST /platform/clubs/{clubId}/jobs/{name}/trigger`. `GET /jobs` lists `week-opening`, `risk-review`,
`waitlist-fifo` (FIFO clubs only), `payment-timeouts` (SINGLE_CLASS) and `cleanup`; the other catalog rows appear
with E6–E8. Web adopters: nothing to regenerate.

## 2026-09-24 · E5-T03 · S08 WP-08-C waiting list served (5 operations no longer 501)

No path, parameter, request body or response change. Two things change:
- The `description` of the five waiting-list operations drops «Contract only; returns 501…» and states the
  served rules: `POST /waitlist-entries` · `GET /waitlist-entries/{id}` (member own **or family group**) ·
  `POST /waitlist-entries/{id}/cancellation` · `POST /waitlist-entries/{id}/claim` ·
  `GET /class-sessions/{id}/waitlist-entries` («every entry of the class, any state, in position order»).
- One enum value is added: `WaitlistEntry.cancelReason` gains **`MEMBER_LEFT`**. This is the S15 §13 catalog
  proposal, set by `WaitlistService.cancelByMember` when a member leaves.

Web adopters: regenerate the types for the new enum value; nothing else.

## 2026-09-24 · E5-T02 · S08 WP-08-B served (9 operations no longer 501)

No path, parameter, request body, response or schema changes: only the `description` of nine
operations drops «Contract only; returns 501 NOT_IMPLEMENTED…», because they now serve:
`POST /seat-holds` · `DELETE /seat-holds/{id}` · `POST /bookings` · `GET /me/bookings` ·
`GET /bookings/{id}` · `GET /bookings/{id}/calendar.ics` (any token mismatch → 404) ·
`POST /bookings/{id}/cancellation` · `GET /bookings` · `GET /class-sessions/{id}/bookings`
(«every booking of the class, any state»). `/me/home`, `/me/bookable-classes` (E5-T06) and the
waiting-list routes (E5-T03) still answer 501. Web adopters need no regeneration of types.

## 2026-09-24 · E5-T01 · Contract S08 + S09 + S15 (32 operations, 501 behind the guards)

32 new operations (16 S08, 8 S09, 8 S15) on 30 new paths, 96 new schemas; none removed.
Every one runs the tenant, role, ownership and module guards and then answers
`501 NOT_IMPLEMENTED` until E5-T02…T05. Member routes accept the impersonation token
(origin BACKOFFICE); every other E5 route answers `403 IMPERSONATION_DENIED`.

- S08 (`clubs.bookings`): `GET /me/home` (MeHome) · `GET /me/bookable-classes` (BookableClasses) ·
  `POST /seat-holds` 201 (SeatHoldResponse; `waitlistEntryId` needs WAITLIST) · `DELETE /seat-holds/{id}` 204 ·
  `POST /bookings` 201 (`Idempotency-Key`; Booking) · `GET /me/bookings` · `GET /bookings/{id}` ·
  `GET /bookings/{id}/calendar.ics?token=` (signed token, no JWT, `security: []`) ·
  `POST /bookings/{id}/cancellation` (MEMBER, INSTRUCTOR) · `GET /bookings` (ADMIN, INSTRUCTOR; x-filterable
  state, dogId, memberId, classSessionId, bookingWeekKey, origin, classStartsAt; x-sortable classStartsAt, bookedAt) ·
  `POST /waitlist-entries` 201 · `GET /waitlist-entries/{id}` · `POST /waitlist-entries/{id}/cancellation` ·
  `POST /waitlist-entries/{id}/claim` 201 (`Idempotency-Key`) — all four WAITLIST ·
  `GET /class-sessions/{id}/bookings` · `GET /class-sessions/{id}/waitlist-entries` (WAITLIST).
- S09 (`clubs.training`, FREE_TRAINING): `GET /training-slots` · `GET /me/training-summary` ·
  `GET /me/training-bookings` · `POST /training-bookings` 201 (`Idempotency-Key`) · `GET /training-bookings/{id}` ·
  `POST /training-bookings/{id}/cancellation` (optional `Idempotency-Key`) · `GET /training-bookings`
  (x-filterable date, ringId, memberId, dogId, state, origin; x-sortable startsAt; x-exportable, listKey
  `training-bookings`) · `GET /training-bookings/export?format=xlsx|pdf`. `/ring-blocks*` is unchanged
  (E4-T01/T03; x-filterable ringId, kind, reason, state, from, to already published).
- S15: `GET /jobs` (JobSummaries) · `GET /jobs/{name}/runs` (x-filterable status, scheduledFor, trigger, dryRun;
  x-sortable scheduledFor, startedAt) · `GET /jobs/{name}/runs/{runId}` (JobRun) · `POST /jobs/{name}/trigger` ·
  `PUT /jobs/{name}/switch` · `GET /risk-review` (form A, published as `RiskReviewForm` because the S14
  dashboard already owns `RiskReview`) · `GET /platform/jobs/overview` · `POST /platform/clubs/{clubId}/jobs/{name}/trigger`.
  `{name}` is the R-15-01 route id; unknown → `422 JOB_UNKNOWN` (catalog rule 0); module of the process off → `404 MODULE_DISABLED`.
- Error `details` schemas published: BookingLimitReachedDetails, ClassFullDetails, NotYetOpenDetails,
  WaitlistLimitDetails, InactivityPeriodDetails, TrainingLimitReachedDetails, SlotTakenDetails,
  SlotOutOfWindowDetails, TrainingCancelTooLateDetails, RingHasBookingsDetails.
- Existing schema changed: `AuditAction` gains `JOB_TRIGGERED` (S14 R-14-09 / S15 R-15-09).
- Status changes (catalog rule 0 wins over S08/S09 §6): `CLASS_NOT_FULL`, `WAITLIST_FULL` and
  `DOG_ALREADY_BOOKED` 409 → 422.


## 2026-09-19 · E4-T04 · Activities implemented

All S07 routes now execute activity lifecycle, registrations, lists/exports and
public/member projections. Public activities expose optional typeLabelI18n,
shortDescriptionI18n and longDescriptionI18n maps. Local public file redirects
accept their signed expires/signature capability without an API key; the initial
request still returns 302. Impersonated cancellation always requires a reason.
PATCH preserves omission versus explicit null. Catalog status codes are unchanged.


## 2026-09-19 · E4-T03 · Calendar operations implemented

The remaining 15 S06 operations now execute week validation, class lifecycle,
ring blocking, calendar and day-grid queries. Class and ring-block lists use
the universal list contract. Patch bodies retain their fields and distinguish
omission from explicit nullable resets. Optional privacy/module fields are
omitted from member and disabled-module projections. No endpoints or catalog
items were added. The snapshot removes the former 501 operation descriptions.


## 2026-09-16 · E4-T02 · Planning operations implemented

The 16 template, coverage, week, candidate and generation operations now execute
S06 P2 instead of returning 501. Their schemas and paths are unchanged. Nullable
patch fields distinguish omitted values from explicit resets. `POST /weeks`
returns 200 for an existing week and 201 for a new one. Week list filtering,
sorting and paging use the shared list contract. Generation candidates retain
ADMIN access as published in S06 §6 and E4-T01.


## 2026-09-16 · E4-T01 · Scheduling and activities contracts

Adds **55 operations: 31 S06 + 24 S07**, all reserved with `501 NOT_IMPLEMENTED`
after tenant, role, impersonation and module checks. Business execution and
successful projections remain E4-T02/T03/T04. All paths below use `/api/v1`.

S06 (31 operations):

- `GET /week-templates`
- `POST /week-templates`
- `GET /week-templates/{id}`
- `PATCH /week-templates/{id}`
- `POST /week-templates/{id}/bands`
- `PATCH /week-templates/{id}/bands/{bandId}`
- `DELETE /week-templates/{id}/bands/{bandId}`
- `POST /week-templates/{id}/classes`
- `PATCH /week-templates/{id}/classes/{classId}`
- `DELETE /week-templates/{id}/classes/{classId}`
- `GET /coverage`
- `GET /weeks`
- `GET /weeks/generation-candidates`
- `POST /weeks`
- `GET /weeks/{id}`
- `POST /weeks/{id}/generation`
- `POST /weeks/{id}/validation`
- `GET /weeks/{id}/calendar`
- `GET /class-sessions`
- `POST /class-sessions`
- `GET /class-sessions/{id}`
- `PATCH /class-sessions/{id}`
- `GET /class-sessions/{id}/cancellation-preview`
- `POST /class-sessions/{id}/cancellation`
- `POST /class-sessions/{id}/risk-exemption`
- `GET /ring-blocks`
- `GET /ring-blocks/{id}`
- `POST /ring-blocks`
- `PATCH /ring-blocks/{id}`
- `POST /ring-blocks/{id}/cancellation`
- `GET /day-grid`

S07 (24 operations):

- `GET /activities`
- `GET /activities/filter-values`
- `GET /activities/export`
- `POST /activities`
- `GET /activities/{id}`
- `PATCH /activities/{id}`
- `PUT /activities/{id}/image`
- `DELETE /activities/{id}/image`
- `POST /activities/{id}/documents`
- `DELETE /activities/{id}/documents/{docId}`
- `GET /activities/{id}/ring-conflicts`
- `POST /activities/{id}/publication`
- `DELETE /activities/{id}/publication`
- `GET /activities/{id}/cancellation-preview`
- `POST /activities/{id}/cancellation`
- `GET /activities/{id}/registrations`
- `POST /activity-registrations`
- `GET /activity-registrations/{id}`
- `POST /activity-registrations/{id}/cancellation`
- `GET /me/activities`
- `GET /me/activities/{activityId}`
- `GET /public/{clubSlug}/activities`
- `GET /public/{clubSlug}/activities/{slug}`
- `GET /public/{clubSlug}/activities/{slug}/files/{fileId}`

The existing `GET /activity-registrations/export` remains the only registrations
export route (not counted among the 55 additions). It gains filters `activityId,
state, origin, registeredAt, memberId`, sort keys `registeredAt, position,
memberLastName`, and columns `member*, state*, position*, origin*, registeredAt*,
cancelledAt, cancelReason`. Its ADMIN/ACTIVITIES guards remain.

Universal list metadata:

| Route | Filters | Sort | Columns / defaults |
|---|---|---|---|
| `/weeks` | startDate, state | startDate | WeekListItem |
| `/class-sessions` | date, state, ringId, instructorId, levelId, weekId | startsAt, date | Staff only |
| `/ring-blocks` | ringId, kind, reason, state, from, to | from | MEMBER projection omits note/createdByName |
| `/activities` | state, type, date, ringId, levelId, deleted, registrationOpen | date, title, state, createdAt | title*, date*, rings*, registrations*, state*, type, slug, registrationTo; date desc; deleted:eq:false |
| `/activities/{id}/registrations` | state, origin, registeredAt, memberId | registeredAt, position, memberLastName | ActivityRegistrationListItem |

S06 forms A–D and S07 forms A–C have typed schemas, role-specific class/ring-block
projections, public activity allowlists, UTC instants and local business dates.
The scheduling Java `ValidationResult` publishes as `WeekValidationResult` because
S04 already owns the `ValidationResult` component; the signup component is preserved.
Public list/detail use `clubApiKey`, declare Cache-Control `public, max-age=300`
and ETag; files declare a keyless 302 with Location. API-key failures retain 403.
Idempotency-Key is required for generation, class cancellation, ring-block creation,
activity publication/cancellation and registration creation. All editable PATCH
contracts require version; class-session PATCH rejects date.

`POST /attachments/upload-url` adds `ACTIVITY_IMAGE` and `ACTIVITY_DOCUMENT` purposes,
both gated by ACTIVITIES. Images require image/* and configured allowed MIME types;
both use files.maxSizeMb. No ErrorCode status changes were needed: the existing enum
already matches the 16 September catalog amendment; dedicated assertions cover all
S06/S07 statuses and ca/es/en messages.

The existing `AuditAction` wire enum adds the four actions already approved in
S14 R-14-09 on 16 September for E4: `TEMPLATE_BAND_DELETED`, `ACTIVITY_UPDATED`,
`ACTIVITY_REGISTERED_BY_CLUB`, and `ACTIVITY_REGISTRATION_CANCELLED_BY_CLUB`.
This publishes names only; audit-producing mutations remain E4-T02/T03/T04.

## 2026-09-10 · E3-T04 · Dashboard implementation

GET `/dashboard` and `/dashboard/counters` now return their S14 aggregates.
`ClassOccupancyKpi.percent` is required and nullable: no available class capacity
returns `null`, with booked/capacity/waitingTotal zero from the scheduling null
object. `PendingSignup.warnings` is optional and omitted with paymentMethodType
when BILLING is disabled (S14 T-14-23). Other disabled blocks remain explicit null.
No endpoint or enum was added. Impersonation returns 403 IMPERSONATION_DENIED.

Dashboard snapshots share generatedAt across club locale variants for 60 seconds;
counters expire after 30 seconds and unread counts are scoped to the current
administrator. Required outbox events invalidate both caches. Until the later
verticals replace the ports, classes, training, recent bookings and request/unread
counters are empty or zero. Census, signup and level counts are real.

## 2026-09-10 · E3-T03 · Signup implementation and add-dog billing choices

The eleven S04 routes now execute their documented behavior. Public submission
returns 201 and a 24-hour capability, or 202 for the honeypot. D2 review, dry run,
validation/rejection, signed uploads, recognition and checkout enforce the
published tenant/role/module/error contracts. Anonymous replay is scoped by host,
plus the capability for checkout, and its stored response is encrypted.

- `AddDogSignupRequest.additionalDogOption` is optional, TODAY by default;
  ALTERNATIVE selects the first day of the next month within the configured cutoff.
- `SignupConfig.upfront.additionalDogOptions` is optional in member mode. Configuration
  choices use `{option, startDate, amount}` (including firstMonthOptions, corrected
  from the stub's amountDue field to the organizer's step-11 shape).
- `SignupUpfront.additionalDog` optionally exposes `{option, startDate, amountDue}`.
- `AddDogSignupResult.checkout` contains required `{required, memberId}`. MEMBER
  checkout accepts its own memberId without an anonymous signupToken.
- Closed configuration contains only enabled=false and closedText. Empty catalogs
  permit omitted planId; corresponding submission/proposal plan references are optional.
- `MemberPatch.paymentMethod` and `.signup.planIdRequested`, and `DogPatch.birthMonth`,
  `.notesToInstructors` and `.documents`, describe the PENDING-only D2 edits. Consent
  editing remains forbidden while PENDING; the existing active-member consent path
  appends to the ledger.
- Rejection optionally returns paidPaymentRequiresRefund, preserving PAID lines.
  Required reason length is 3–500. ES signup phone prefixes may use the country default.

Local signed file transport routes are hidden from the public API snapshot; the
signed URLs are returned by upload/review. Stripe network integration remains E8;
local/test use FakeCheckoutGateway and the common completion/expiry handler.

## 2026-09-09 · E3-T01 · Signup and dashboard contract

All new operations are standard `501 NOT_IMPLEMENTED` stubs under `/api/v1`:

- GET `/signup`: anonymous or MEMBER configuration; optional bearer authentication,
  country profile, resolved texts/legal content, first-month options, masked member mode.
- POST `/signup/identity-checks`: anonymous recognition result and masked email.
- GET `/signup/towns?postalCode=`: anonymous country-profile town/region array.
- POST `/signup/upload-urls`: anonymous or MEMBER signed upload contract.
- POST `/signup/family-group-lookups`: anonymous holder lookup; requires FAMILY_GROUP.
- POST `/signup`: anonymous submission; required UUID Idempotency-Key, fictional
  request example, 201 result and the later 202 honeypot response.
- POST `/checkout-sessions`: anonymous capability or MEMBER/ADMIN bearer; BILLING
  and Idempotency-Key required; 201 checkout URL/session contract.
- POST `/me/dogs/signup`: MEMBER, including valid impersonation; Idempotency-Key required.
- GET `/members/{id}/signup`: ADMIN D2 aggregate with documents, warnings and proposals.
- POST `/members/{id}/validation?dryRun=`: ADMIN; version required; 200 oneOf
  ValidationDryRun/ValidationResult (dryRun defaults to false).
- POST `/members/{id}/rejection`: ADMIN; version/reason required; typed member/dog outcome.

`MemberListItem` gains optional `signupPending`, `pendingDogs`, `warnings` and
`signup{submittedAt}`. GET `/members` reserves the three virtual filter fields
(`x-filterable`) and `signup.submittedAt` (`x-sortable`) for E3-T03; runtime evaluation
and projection remain deferred. Existing census list behavior is unchanged.

`Dashboard` and `DashboardKpis` module/parameter-controlled blocks are required
nullable properties, serialized as null when disabled. Risk status is the closed
CANCELLED/AT_RISK/WILL_CANCEL/PENDING_DECISION enum. D1 and D2 share `SignupWarning`;
payment method and notified gender enums are explicit. All S14 §6 fields are checked.

The catalog's §3 rule 0 overrides the illustrative S04/task statuses: SIGNUP_CLOSED,
SIGNUP_ALREADY_PENDING and ID_DOCUMENT_AMBIGUOUS are 422; MEMBER_ALREADY_EXISTS and
MEMBERSHIP_EXISTS are 409. Correct existing SIGNUP_ALREADY_PENDING (409→422) and
DOG_CHIP_ALREADY_REGISTERED (409→422). All 19 errors already have ca/es/en messages.

Contract-only boundaries: R-04-20 per-club/IP limits, 64 KB/10-file limits, anonymous
idempotency, signupToken/ownership/redirect checks, calculations, persistence and
notifications are E3-T02/T03. The JWT idempotency filter lets only anonymous POST
/signup and /checkout-sessions reach these effect-free stubs; E3-T03 must replace
that exception before implementing their mutations. Tests verify no signup writes.
Six S04 event payload fixtures and N-01/02/03/37/39 notification fixtures are published
for the implementation tests; no sending or new catalog entries are introduced.

## 2026-09-09 · E2-T12 · Club pages

- Add GET/POST `/club-pages`, GET/PATCH `/club-pages/{key}` and key-authenticated
  GET `/public/{clubSlug}/pages/{key}`. Authenticated responses retain translation
  maps; public responses resolve them using Accept-Language and the club default.
- `ClubPage` includes key, title, body, version, publishedAt, active and lastChange.
  Admins also receive the last ten published snapshots. Draft publication timestamps
  and non-admin lastChange.by are explicitly nullable. Non-admins see active pages only.
- Publication starts at version 1; activation or an active body edit advances it.
  PATCH requires version; draft and title-only edits preserve the publication version.
  Translation maps replace the supplied field. Markdown permits headings, paragraphs,
  emphasis, lists and safe links, with at most 20000 body characters per locale.

## 2026-09-09 · E2-T08 · Export lifecycle

- Implement caller-owned `GET /exports` and `GET /exports/{id}`. List at most 100
  recent jobs; detail adds a signed URL when READY. Files and links expire after
  seven days. Local downloads require the bearer token and signed query parameters
  at `GET /exports/{id}/download`; S3 returns a direct signed object URL.
- Add POST aliases for `/members/export` and `/dogs/export` with the same query
  parameters and binary/202 responses as GET. Inline exports also retain READY
  jobs. Freeze locale, timezone, query and filenames at submission.
- Preserve catalog HTTP 422 for EXPORT_LIMIT and EXPORT_EXPIRED; rate limiting
  returns 429 RATE_LIMITED and oversized requests return 422 EXPORT_TOO_LARGE.

## 2026-09-09 · E2-T05 · Plans and dated prices

- Implement `/plans*`, `/prices*` and public plans. Add `billingMode` to monthly plan
  requests and all plan projections; default omitted monthly input to `MONTHLY_FEE`.
- Public plans include resolved text plus translation maps, pack/single-class terms,
  current prices with tax percentages and localized `priceLine`. Omit price/entry-fee
  fields when BILLING is disabled; reader projections omit audit and usage data.
- Replace plan `warnings` with usage counts and expose `upfrontCollections` in usage.
  Price `periodicity` is derived; PATCH `validTo: null` reopens an interval subject to
  overlap/lock checks. Document update-time currency and overlap errors.
- Missing or invalid public keys return catalog `INVALID_API_KEY` (403); resolve the
  club by slug without a tenant header, vary public caching by key and language.

## 2026-09-09 · E2-T03 · Base catalogs

- `/levels`, `/rings` and `/faq-entries` now execute the S05 CRUD/order rules; FAQ category suggestions return localized values and counts. ADMIN-only inactive lists and reduced MEMBER/INSTRUCTOR views are enforced.
- Remove `agilityhubLevel` from level requests, responses and reader schemas (A5). Level `warnings` is the S05 usage object, replacing the contract stub's string array.
- Level codes and ring short names accept lowercase input and store uppercase, with case-insensitive uniqueness. `RingPatch.trainingCapacity: null` resets to the configured fallback; omission preserves the override. Ring geometry and active setup remain owned by S16.
- `lastChange` uses the approved `CATALOG_CHANGED` audit action. `ORDER_INCOMPLETE` retains the catalog's HTTP 422 despite S05's illustrative 400.

## 2026-09-09 · E1-T10 · identity locale contract and integration rehearsal

`MeAccount`, `AccountSummary`, `AccountPatchRequest`, `OnboardingFields` and
`PlatformAccountRequest` now declare `ca es en fr de no pt` for `locale`.
The account validator, PATCH `/me` and onboarding persist all seven languages;
unsupported values retain `LOCALE_NOT_SUPPORTED`. UI translations remain
`ca/es/en`, with English fallback in the apps. No routes or catalog items were added.
The snapshot is regenerated and the enum set has a T-01-23 contract regression.
`bin/e1-smoke` exercises the current cookie grant/revocation contract end to end.

## 2026-09-09 · E2-T02 · Club settings and parameters implementation

- Settings routes now execute their S02 use cases, including scoped parameter history/reset, self-service modules and the global parameter catalog.
- Added `PUT /api/v1/club` with a required version and optional identity/contact/theme fields; console fields return `PLATFORM_ONLY`. Added `GET /api/v1/club/opening-hours`, `GET /api/v1/club/holidays` and public tenant-bound `GET /api/v1/country-profile`.
- `Parameter` adds `default`, `block` and `history` to support the task's detail response; the existing history route remains available. Version zero denotes an absent override; reset keeps a versioned history record with `isOverride=false`.
- `HolidaysUpdate.value` now contains `{date, label}` objects, as required by B23 and S02 R-02-09. History entries retain the previous effective value, consistent with the existing club-as-code writer.
- The closed catalog's HTTP `422 TIMEZONE_CHANGE_BLOCKED` is preserved. Club timezone edits remain platform-only; the current endpoint additionally refuses a changed timezone when tenant classes exist.

## 2026-09-09 · E1-T13 · Cookie refresh and platform roles

- `POST /oauth2/token`: COOKIE clients omit `refresh_token` from JSON and issue/rotate `ah_refresh`; refresh accepts the cookie with explicit client_id and a same-host Origin/Referer. BODY delivery remains unchanged. The contract documents Set-Cookie and conditional form requirements.
- `POST /oauth2/revoke`: optional JSON `token`; COOKIE clients may send `{}` to revoke the bearer session, because the refresh cookie path excludes this route. Successful revoke and `/connect/logout` expire the refresh cookie; deleting the current device session does too.
- `GET/PUT /api/v1/platform/accounts/{id}/platform-roles`: `{platformRoles[]}`, live AGILITYHUB_ADMIN authorization, global account scope, `409 LAST_PLATFORM_ADMIN`, atomic `PLATFORM_ROLES_CHANGED` audit and no domain event.

## 2026-09-06 · E1-T06 · Onboarding implementation

- `GET /api/v1/me/onboarding`: active platform-first consent selection, club-scoped policy renewal, and configured prefilled profile fields; existing response shape retained.
- `PUT /api/v1/me/onboarding`: accepts the current required consent and optional profile/image fields; rejects impersonated tokens and preserves the existing validation errors.
- `POST /api/v1/me/onboarding/postpone`: decrements the renewal allowance per policy, club and version; initial acceptance and exhausted allowances return the state unchanged. Rejects impersonated tokens.

## 2026-09-06 · E1-T05 · OIDC implementation

- `GET /.well-known/openid-configuration`: active discovery metadata and public 60-second cache policy.
- `GET /.well-known/jwks.json`, `GET /oauth2/jwks`: two public RSA keys with `kid`, `use`, `alg`; same cache policy.
- `GET /oauth2/authorize`: active cookie-bound login/code flow; optional `nonce` and `max_age`, conditional S256 PKCE for public clients, prompt and UI hints.
- `POST /oauth2/session`: new apps/id continuation contract `{flow}` → `{redirectUrl}`, requiring the flow cookie, same-origin Origin, and a fresh global id-web bearer login. Rotates the host-only Secure/HttpOnly/Lax session cookie.
- `POST /oauth2/token`: authorization-code and confidential-client grants are active; ID tokens follow `openid`, exposed refresh tokens follow `offline_access`; refresh may preserve or narrow scopes.
- `GET /oauth2/userinfo`: active openid-scoped account claims, with profile/email/memberships scope filtering.
- `GET /connect/logout`: active signed ID-token hint validation, exact registered post-logout URI, optional `state`, and browser-cookie revocation.

The existing catalog envelope is retained; protocol validation reasons use
`VALIDATION_ERROR.details.oauth2Error`. See README for apps/id integration and
configuration, including the migration from environment PEM to encrypted Mongo keys.

## 2026-09-06 · E1-T11 · Required properties by default

All Java model properties are required unless explicitly optional (`Optional`, Spring/JSpecify
`@Nullable`, or `@Schema` with `NOT_REQUIRED`/`nullable=true`). Branding, theme colors,
manifest, health, money, discovery and user-info membership schemas now declare their
required fields. Existing conditional/PATCH fields retain their optional status through
explicit annotations, including module-dependent E2 projections. `ApiError.details`,
`OnboardingState.requiredConsent` and `OnboardingField.value` are optional; onboarding
null types remain unchanged. `ApiError.traceId` stays required; validation field errors
remain nested in `details`. SYSTEM webhook callbacks may omit `clubId`. Schemas with
only optional properties publish `required: []`. Routes and runtime serialization are unchanged.


## 2026-09-06 · E2-T01 · S02/S03/S05/S14 contracts

All new operations return the localized `501 NOT_IMPLEMENTED` envelope after authentication,
role, tenant, module and request-shape checks. Implementations belong to the later E2 tasks.
Existing branding, manifest and S01 routes remain active. `/members/{id}/impersonation-token`
retains its S01 controller; its response gains optional `launchUrl` for S03.

Desktop lists use `ListPage<T>` (`items`, `page`, `size`, `totalItems`, `totalPages`,
`appliedFilters`). Catalogs keep S05's unpaginated `{items, totalItems}` shape.
`x-filterable` and `x-sortable` are ordered field-key arrays; `x-filter-operators` records
explicit `contains`/`between` restrictions. `x-columns` contains ordered
`{key, defaultVisible, module?, parameter?}` objects, preserving S03 defaults and gates.
Bounded lists publish empty capability arrays where the spec provides no universal filters.
Only the three E2 desktop lists advertise `x-exportable=true`; the three future-vertical
export routes have empty field allowlists pending their contracts. Role-specific list items
use `anyOf` because the reduced projection structurally overlaps the admin projection.

Audit responses publish all 59 action names from S14 R-14-09 without expanding the
implemented audit-writer action set.

Canonical `ErrorCode` statuses win over narrative examples: `MEMBER_NOT_ACTIVE`,
`EXPORT_EXPIRED`, `EXPORT_LIMIT`, `ORDER_INCOMPLETE` and `DATA_EXPORT_TOO_SOON` are 422;
`INVALID_API_KEY` is 403. No error enum or catalog additions were necessary.
`FAMILY_GROUP_MEMBER_TAKEN` maps to `FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP`;
uncatalogued `MEMBER_ERASED` maps to `INVALID_STATE` (see roadmap/MESSAGES.md).
The S05 `/rings/{id}/geometry` row is explicitly a link to S16 and is outside this contract.

`GET /public/{clubSlug}/plans` uses an `X-Api-Key` security scheme and slug-based context,
including requests from an external host. Postal lookup supports anonymous club-host context.
Global account-erasure and platform routes do not require a club claim. All admin-only
contracts deny impersonated tokens; future ownership checks remain necessary in services.

- `GET /api/v1/members`: E2 contract; protected.
- `GET /api/v1/members/filter-values`: E2 contract; protected.
- `GET /api/v1/members/{id}`: E2 contract; protected.
- `GET /api/v1/members/{id}/overview`: E2 contract; protected.
- `PATCH /api/v1/members/{id}`: E2 contract; protected.
- `PATCH /api/v1/members/{id}/payment-method`: E2 contract; protected.
- `POST /api/v1/members/{id}/booking-block`: E2 contract; protected.
- `DELETE /api/v1/members/{id}/booking-block`: E2 contract; protected.
- `POST /api/v1/members/{id}/access-resend`: E2 contract; protected.
- `PUT /api/v1/members/{id}/roles`: E2 contract; protected.
- `GET /api/v1/dogs`: E2 contract; protected.
- `GET /api/v1/dogs/filter-values`: E2 contract; protected.
- `GET /api/v1/dogs/{id}`: E2 contract; protected.
- `PATCH /api/v1/dogs/{id}`: E2 contract; protected.
- `PATCH /api/v1/dogs/{id}/level`: E2 contract; protected.
- `PATCH /api/v1/dogs/{id}/free-training`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/transfer`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/deactivation`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/reactivation`: E2 contract; protected.
- `PUT /api/v1/dogs/{id}/photo`: E2 contract; protected.
- `GET /api/v1/dogs/{id}/documents`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/documents`: E2 contract; protected.
- `DELETE /api/v1/dogs/{id}/documents/{docId}/files/{fileId}`: E2 contract; protected.
- `POST /api/v1/dogs/{id}/documents/reminder`: E2 contract; protected.
- `POST /api/v1/family-groups`: E2 contract; protected.
- `GET /api/v1/family-groups/{id}`: E2 contract; protected.
- `PUT /api/v1/family-groups/{id}`: E2 contract; protected.
- `DELETE /api/v1/family-groups/{id}`: E2 contract; protected.
- `GET /api/v1/me/family-group`: E2 contract; protected.
- `GET /api/v1/me/dogs`: E2 contract; protected.
- `PUT /api/v1/me/dogs/{id}/photo`: E2 contract; protected.
- `POST /api/v1/me/dogs/{id}/documents`: E2 contract; protected.
- `PUT /api/v1/me/dogs/{id}/instructor-note`: E2 contract; protected.
- `GET /api/v1/me/profile`: E2 contract; protected.
- `PATCH /api/v1/me/profile`: E2 contract; protected.
- `GET /api/v1/levels`: E2 contract; protected.
- `POST /api/v1/levels`: E2 contract; protected.
- `GET /api/v1/levels/{id}`: E2 contract; protected.
- `PATCH /api/v1/levels/{id}`: E2 contract; protected.
- `DELETE /api/v1/levels/{id}`: E2 contract; protected.
- `PUT /api/v1/levels/order`: E2 contract; protected.
- `GET /api/v1/rings`: E2 contract; protected.
- `POST /api/v1/rings`: E2 contract; protected.
- `GET /api/v1/rings/{id}`: E2 contract; protected.
- `PATCH /api/v1/rings/{id}`: E2 contract; protected.
- `DELETE /api/v1/rings/{id}`: E2 contract; protected.
- `PUT /api/v1/rings/order`: E2 contract; protected.
- `GET /api/v1/plans`: E2 contract; protected.
- `POST /api/v1/plans`: E2 contract; protected.
- `GET /api/v1/plans/{id}`: E2 contract; protected.
- `PATCH /api/v1/plans/{id}`: E2 contract; protected.
- `DELETE /api/v1/plans/{id}`: E2 contract; protected.
- `PUT /api/v1/plans/order`: E2 contract; protected.
- `GET /api/v1/faq-entries`: E2 contract; protected.
- `POST /api/v1/faq-entries`: E2 contract; protected.
- `PATCH /api/v1/faq-entries/{id}`: E2 contract; protected.
- `DELETE /api/v1/faq-entries/{id}`: E2 contract; protected.
- `PUT /api/v1/faq-entries/order`: E2 contract; protected.
- `GET /api/v1/faq-entries/filter-values`: E2 contract; protected.
- `GET /api/v1/instructors`: E2 contract; protected.
- `POST /api/v1/instructors`: E2 contract; protected.
- `PATCH /api/v1/instructors/{id}`: E2 contract; protected.
- `DELETE /api/v1/instructors/{id}`: E2 contract; protected.
- `GET /api/v1/administrators`: E2 contract; protected.
- `POST /api/v1/administrators`: E2 contract; protected.
- `PATCH /api/v1/administrators/{membershipId}`: E2 contract; protected.
- `DELETE /api/v1/administrators/{membershipId}`: E2 contract; protected.
- `GET /api/v1/prices`: E2 contract; protected.
- `POST /api/v1/prices`: E2 contract; protected.
- `PATCH /api/v1/prices/{id}`: E2 contract; protected.
- `DELETE /api/v1/prices/{id}`: E2 contract; protected.
- `GET /api/v1/public/{clubSlug}/plans`: E2 contract; anonymous.
- `GET /api/v1/club`: E2 contract; protected.
- `GET /api/v1/parameters`: E2 contract; protected.
- `GET /api/v1/parameters/{key}`: E2 contract; protected.
- `GET /api/v1/parameters/{key}/history`: E2 contract; protected.
- `PUT /api/v1/parameters/{key}`: E2 contract; protected.
- `DELETE /api/v1/parameters/{key}`: E2 contract; protected.
- `PUT /api/v1/club/modules/{module}`: E2 contract; protected.
- `PUT /api/v1/club/opening-hours`: E2 contract; protected.
- `PUT /api/v1/club/holidays`: E2 contract; protected.
- `GET /api/v1/country-profile/postal-codes/{code}`: E2 contract; anonymous.
- `GET /api/v1/platform/parameter-catalog`: E2 contract; protected.
- `GET /api/v1/audit-entries`: E2 contract; protected.
- `GET /api/v1/audit-entries/filter-values`: E2 contract; protected.
- `GET /api/v1/audit-entries/{id}`: E2 contract; protected.
- `GET /api/v1/members/{id}/audit-entries`: E2 contract; protected.
- `GET /api/v1/members/{id}/consents`: E2 contract; protected.
- `POST /api/v1/members/{id}/erasure`: E2 contract; protected.
- `GET /api/v1/members/{id}/erasure`: E2 contract; protected.
- `DELETE /api/v1/members/{id}/erasure`: E2 contract; protected.
- `POST /api/v1/accounts/{id}/erasure`: E2 contract; protected.
- `GET /api/v1/platform/audit-entries`: E2 contract; protected.
- `GET /api/v1/platform/erasure-requests`: E2 contract; protected.
- `GET /api/v1/platform/security-events`: E2 contract; protected.
- `GET /api/v1/saved-views`: E2 contract; protected.
- `GET /api/v1/saved-views/{id}`: E2 contract; protected.
- `POST /api/v1/saved-views`: E2 contract; protected.
- `PUT /api/v1/saved-views/{id}`: E2 contract; protected.
- `DELETE /api/v1/saved-views/{id}`: E2 contract; protected.
- `GET /api/v1/exports`: E2 contract; protected.
- `GET /api/v1/exports/{id}`: E2 contract; protected.
- `POST /api/v1/members/{id}/data-export`: E2 contract; protected.
- `POST /api/v1/me/data-export`: E2 contract; protected.
- `GET /api/v1/members/export`: E2 contract; protected.
- `GET /api/v1/dogs/export`: E2 contract; protected.
- `GET /api/v1/audit-entries/export`: E2 contract; protected.
- `GET /api/v1/invoices/export`: E2 contract; protected.
- `GET /api/v1/activity-registrations/export`: E2 contract; protected.
- `GET /api/v1/notifications/export`: E2 contract; protected.
- `GET /api/v1/dashboard`: E2 contract; protected.
- `GET /api/v1/dashboard/counters`: E2 contract; protected.

## 2026-09-06 · E1-T03 Round 2 · webhook signature error

- `POST /webhooks/email/sendgrid`: missing or invalid signatures now return HTTP 401 with `code = WEBHOOK_SIGNATURE_INVALID` and empty `details`, superseding the initial `UNAUTHENTICATED` workaround below. Signature failures are recorded using the existing `WEBHOOK_SIGNATURE_INVALID` security event.

## 2026-09-06 · E1-T01 Round 2 · S01 v0.3 corrections

- `GET /api/v1/me/onboarding`: replace the previous state with required `pending`, `postponeRemaining`, nullable `requiredConsent {policy: PLATFORM|CLUB, version, url}`, and `fields[] {key, value: string|null, required}`.
- `PUT /api/v1/me/onboarding`: require `consentAccepted` and `consentVersion`; optional `fields {name?, locale?, phone?}` and `imageConsent`; return the corrected state and document `VALIDATION_ERROR`, `LOCALE_NOT_SUPPORTED` (400) and `CONSENT_VERSION_OUTDATED` (422).
- `POST /api/v1/me/onboarding/postpone`: new authenticated account route with no body and the corrected state as its 200 contract. Like GET/PUT onboarding, returns standard 501 until E1-T06.
- `POST /api/v1/auth/magic-link`: replaces the root `/auth/magic-link` route, remains public with optional club host, and shares the configured authentication IP quota with `/oauth2/token`; 429 includes `Retry-After`.
- `GET /api/v1/me`, `PATCH /api/v1/me`: account uses `MeAccount` with required `hasPassword` and `onboardingPending`, plus optional `emailVerifiedAt` (date-time); membership gains optional `gender` (`MALE|FEMALE|OTHER`). The provisioning `AccountSummary` is unchanged. Current bootstrap derives password presence and returns `onboardingPending=false`; verification time and Member gender are contract fields pending service/model support.

The two approved onboarding parameters are already registered with their documented defaults by E1-T03. No new error codes, events, notifications or parameter keys are introduced in this round. These corrections supersede the initial E1-T01 route/onboarding assumptions below.

## 2026-09-06 · E1-T03 email webhook

- `POST /webhooks/email/sendgrid`: public ECDSA-authenticated JSON event array with timestamp/signature headers; no bearer or request tenant required. Returns empty 200 for processed/replayed/irrelevant events, 400 for malformed signed JSON, and 401 `UNAUTHENTICATED` with `details.reason = WEBHOOK_SIGNATURE_INVALID` for missing/invalid signatures. The existing catalog assigns `WEBHOOK_SIGNATURE_INVALID` to 400; alignment is proposed in `roadmap/MESSAGES.md` without changing the catalog.

## 2026-09-06 · E1-T01 identity contract

- `POST /oauth2/token`: typed `TokenRequest` / `TokenResponse`, five grants and their form fields in `x-grants`; optional refresh/ID tokens and required scope. E0 password/refresh remain active for the public club clients; pending grants return 501.
- `GET /.well-known/openid-configuration`: public global OIDC discovery schema; 501 until E1-T05.
- `GET /.well-known/jwks.json`, `GET /oauth2/jwks`: typed public JWK schemas and MVC contract signatures; existing security filters still serve both aliases.
- `GET /oauth2/authorize`: public authorization-code/PKCE parameters and 302 redirect contract; 501 until E1-T05.
- `POST /oauth2/revoke`: authenticated, idempotent JSON `{token}` revocation with empty 200 response; pending implementation returns 501.
- `GET /oauth2/userinfo`: openid-scoped account claims and optional scoped memberships; 501 until E1-T05.
- `GET /connect/logout`: ID-token hint and post-logout redirect parameters, 302 contract; 501 until E1-T05.
- `POST /auth/magic-link`: public `{email, purpose, client_id, redirect_uri?}`, neutral empty 202 contract; 501 until E1-T02.
- `POST /api/v1/auth/handoff`: authenticated club context, `{targetClientId}` and 201 `HandoffResponse`; 501 until E1-T04.
- `GET /api/v1/me`: R-01-15 `Me` schema replaces `modules` with `features`, adds platformRoles, clubId, profiles, activeProfile, instructorId, rememberProfile and optional impersonation. Existing club bootstrap is mapped to this schema; global/impersonated bootstrap returns 501 until E1 implementation.
- `PATCH /api/v1/me`: optional locale/name, updated `Me` response; pending implementation returns 501.
- `PUT /api/v1/me/password`: `{current?, new, repeat}`, empty 200, denies impersonation; pending implementation returns 501.
- `PUT /api/v1/me/profile`: `{activeProfile, remember}`, fresh access token, club context required; pending implementation returns 501.
- `GET /api/v1/me/sessions`: bounded array of public device sessions without credential material; pending implementation returns 501.
- `DELETE /api/v1/me/sessions/{id}`: account-owned session revocation, empty 200; pending implementation returns 501.
- `GET /api/v1/me/onboarding`: `OnboardingState` with requested fields and current privacy policy; 501 until E1-T06.
- `PUT /api/v1/me/onboarding`: optional name/locale/phone, required privacy acceptance/version and optional image consent; updated `OnboardingState`, 501 until E1-T06. Field names are explicit task assumptions pending organizer confirmation.
- `POST /api/v1/members/{id}/impersonation-token`: same-club ADMIN, `{reason?}`, 201 token/expiry; denies nested impersonation, returns 501 until E1-T04.
- `POST /api/v1/platform/accounts`: `PlatformAccountRequest`, 201 public account/ID; global platform admin or learn audience with accounts:write scope, pending implementation returns 501.
- `PUT /api/v1/accounts/{id}/password`: write-only passwordHash and empty 200; same global authorization, pending implementation returns 501.

All pending methods use `501 NOT_IMPLEMENTED` in the shared localized `ApiError` envelope. No ErrorCode additions: all 17 S01 codes already exist. Canonical catalog statuses win over conflicting narrative examples (`REFRESH_EXPIRED` is 400). OAuth/magic-link routes use the identity root; application routes use `/api/v1` per CONVENCIONS_API §1.

## 2026-09-06 · E0-T12 baseline

- `GET /api/v1/health`: public global health, application version and build time.
- `GET /api/v1/branding`: public host-scoped club branding, modules, locales and signup settings; supports ETag / 304.
- `GET /api/v1/manifest.webmanifest`: public host-scoped PWA manifest (`application/manifest+json`); supports ETag / 304.
- `GET /api/v1/me`: bearer-protected account, active membership and enabled modules; MEMBER / INSTRUCTOR / ADMIN / AGILITYHUB_ADMIN.
- `POST /oauth2/token`: form-encoded password or rotating refresh grant for a host-selected club and public client; shared ApiError failures.
- `GET /oauth2/jwks`: public global RSA verification key set.
- `GET /.well-known/jwks.json`: public global alias for the RSA verification key set.
- `GET /api/v1/openapi.json`: OpenAPI 3.1 contract replaces `/v3/api-docs` in local/test; generation remains disabled in staging/prod.

All operations declare their bounded context and shared `ApiError` responses; protected operations inherit the `bearer` security requirement. Fixed server URLs and recursively sorted object keys make the snapshot independent of test ports, locale and build time. No list endpoints exist yet; future lists must declare `x-filterable` / `x-sortable` per `CONVENCIONS_API.md` §4.
