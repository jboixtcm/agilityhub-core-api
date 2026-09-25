# Gate E3 review — consolidated report

**Date:** 2026-09-24.
**Requested by:** Jordi, who asked for an exhaustive review before closing stage E3.
**Scope:** gate E3 in depth: S04 complete (public signup, add-dog, D2 validation) and the dashboard part of S14 (D1), in both repos. The E0–E2 regression is covered by the automated suites of the audit runs.
**Frozen commits:** api `20552c6`, web `5d371d3`.

| Source | File | Verdict |
|---|---|---|
| Codex (gpt-6-astra, xhigh), independent | `codex-20260924-1554.md` | fail: 19 major, 11 minor |
| Organizer (Claude) + six read-only sub-audits, independent | `claude-20260924.md` | fail: 1 blocker, 18 major, many minors |
| Execution runs on the Mac, before the fixes (baseline) | api **E3-T07** (done), web **E3-W05** (running) | api: `clean verify` green, **`bin/e3-smoke` red** (a stale assertion, see below) |
| Execution runs on the Mac, after the fixes | api **E3-T11**, web **E3-W09** | pending |

**Independence.** Neither reviewer saw the other's report. The organizer wrote and saved its report before opening Codex's. Every finding below was confirmed by the organizer in the code. Some were also confirmed in the gate's own screenshots.

## Verdict: the E3 gate does not pass

The happy path works against the real core: a DNI applicant signs up, the admin validates and the welcome link works. But four whole areas fail:
- NIE and passport applicants cannot submit at all;
- the payment step shows raw placeholders, wrong labels on half of the month's days and no total;
- the family plan cannot be proposed or used, and add-dog fails for family-plan members;
- several D2 actions fail or lose data: dog edits, error display, contact edits, plan changes.

The gate tick of 24-09 is withdrawn; the organizer had checked D2 but not the 18/19 screenshots, which already showed the defects. A new gate line now waits for the fixes below.

## Agreement between the two audits

**Found by both** (highest confidence): 12 findings.
- The identity document at submission (B1).
- Retries and idempotency on 19 (M1).
- Error mapping on 16/19 and D2 (M2, M13).
- The missing total and the plan-blind upfront card (M3, M5).
- The first-month labels (M4).
- The MANUAL instructions (M7) and the text placeholders (M6); Codex rated the placeholders minor, but every applicant sees them.
- D2: dog version (M12), contact arrays (M14), plan selector and `dryRun`, family claim (M15).
- Upfront amounts rewritten on a plan change (M10, the `replace()` part).
- The S3 upload headers (M16).
- Minors: `planId: ""`, the config flags, `FAMILY_HOLDER_NOT_FOUND`, `signup.rateLimit`, `waitingTotal`, the `Member.signup` snapshot, D1 «(nou gos)» and chart legend, D2 phone and OTHER pronoun, module gating.

**Found only by the organizer's audit:**
- M8, plans hidden from signup: the family plan and add-dog. Three sub-audits found it independently.
- M9, the N-01 admin copy in the applicant's words.
- M10, payment rows not scoped to the submission (readmissions).
- M11, D1 stale after a validation.
- M17, rate limits behind the proxy and matching on raw URIs.
- M18, anonymous readmission overwrite.

**Found only by Codex:**
- M19, consent recorded against a legal version the applicant never saw.
- M20, screen 13 does not show a dog pending validation.
- The checkout ordering (a provider session created before the commit). It goes to **E8-T04**, because Stripe arrives in E8.

**Regression noted by Codex:** the E3-W01 screenshot of 19 had a total that the current code no longer renders.

**Found by the api baseline run (E3-T07, on `de0e17f`):**
- `./mvnw clean verify` is green: 513 unit and 826 integration tests.
- The seeds are idempotent, and the snapshot has no drift.
- **`bin/e3-smoke` fails both runs** at «Future verticals must stay empty». The assertion is older than the E4-T05 planning seed, which puts classes in the current week. The product follows S14 R-14-03; only the smoke is stale. With that single assertion downgraded, the whole E3 flow passes. → **E3-T08** step 1.
- The gate line «class/training blocks `null`/0» therefore no longer holds on `main`.

## Findings (final classification)

Paths: `api/` = `agilityhub-core-api/src/main/java/com/agilityhub/core/`, `web/` = `agilityhub-core-web/`. Line numbers are at the frozen commits.

### Blocker
- **B1** `web/apps/clubs/src/SignupPage.tsx:1359`: `POST /signup` sends the draft's `idDocument` (type always `DNI`; the passport is never sent) instead of `identityDocument()`. NIE applicants get `400 INVALID_ID_DOCUMENT`, and passport-only applicants a 400. Neither can sign up. R-04-01.

### Major, public signup (web)
- **M1** `SignupPage.tsx:1331-1382`: a fresh `Idempotency-Key` per click, and the draft and token are lost before checkout. A retry gets `SIGNUP_ALREADY_PENDING` (or `DOG_CHIP_ALREADY_REGISTERED` in add-dog). CONVENCIONS_API §7, R-04-26/27.
- **M2** `SignupPage.tsx:451-463, 1389-1404`: top-level api codes (`INVALID_ID_DOCUMENT`, `INVALID_IBAN`, `INVALID_PHONE`, `PLAN_NOT_AVAILABLE`, …) end in a generic message, with no field and no way back to the step. §2, CATALEG_ERRORS.
- **M3** «Total a pagar al club» is missing on 19 (§2, §10 `signup:step4.total`, T-04-32). It is a regression from E3-W01.
- **M4** `signup.json:111-112`: the option labels are fixed for d ≥ 16, so on days 1–15 they contradict the amounts. R-04-15, §10 (four keys).
- **M5** `SignupPage.tsx:1517-1560`: the upfront card ignores the plan type: monthly options for packs and Teràpia, no PACK line, «Entrada 0,00 €». R-04-14. The cause is shared with the api: `GET /signup` builds one set of options from the first monthly plan (`SignupService.java:227`). The api returns a quote per plan (E3-T08), and the web renders the selected one (E3-W08).
- **M19** `SignupPage.tsx:1311`: the consent version sent is the current config's, not the one the applicant accepted, and the draft keeps `privacyAccepted` for 24 h. R-04-17, GDPR art. 7.
- **M20** screen 13 (`api/…/clubs/census/application/CensusQuery.java:123` returns ACTIVE dogs only; the web has no pending branch): after an add-dog, the member sees no trace of the pending dog. S04 §13-8 («xip "pendent de validació" a 13»), R-04-25.

### Major, signup configuration, plans, payments and notifications (api)
- **M6** `api/…/census/application/SignupService.java:221-237`: raw `{deadlineDay}` and `{twoDogsMonthlyFee}` in `texts` (screenshots 18/19). §6/§10.
- **M7** `SignupService.java:226` with `SignupNotifications.java:21-31`: the upfront instructions are the semester cash conditions, and N-01 carries no `upfront_total`/`payment_instructions`. A MANUAL applicant is never told what to pay or how. §2 row 19, CATALEG_NOTIFICACIONS N-01.
- **M8** `api/…/clubs/signup/domain/SignupPlanCatalog.java:18`, `SignupPolicy.java:43-47`, `SignupService.java:198,229,263-276`: only `showOnSignup` plans are assignable. Consequences:
  - the Cànic's `ABONAT_FAMILIAR` is never proposed and cannot be chosen (`422 PLAN_NOT_AVAILABLE`);
  - add-dog fails for family-plan members;
  - D2 fails if a requested plan is hidden.
  
  R-04-13, R-04-25.
- **M9** `SignupNotifications.java:34`: the admins' N-01 says «Hem rebut la teva sol·licitud…», without the applicant's name or plan. §8, N-01.
- **M10** `api/…/payments/application/UpfrontPayments.java:22-26,63-79`: payment rows are not scoped to their submission (a readmission inherits the old rows), and `replace()` rewrites a PARTIAL `amountDue` and cancels `CHECKOUT_PENDING`. §5 («els imports no es modifiquen mai»).
- **M11** `api/…/clubs/dashboard/application/DashboardEvents.java` (async invalidation only) + `web/…/SignupReviewPage.tsx:304-310,369`: after a validation, D1 still lists the signup and the menu count is old. [VALIDA] then leads to a generic error. The e2e hides it with a 1 s wait. R-14-01.
- **M21** `SignupService.java:141,165,333,344`: §3 constraints are not enforced on the server:
  - `nextInvoiceDate` ≥ the first-month start (it feeds billing);
  - birth date ≥ 1900;
  - chip format per country profile, and normalisation (the uniqueness and readmission matching depend on it).

### Major, D2 back office (web)
- **M12** `web/apps/clubs-admin/src/dashboard/SignupReviewPage.tsx:150-158`: the dog `PATCH` carries the member's `version` (`SignupDogView` has none). Dog edits fail after an add-dog or a member edit. R-04-19.
- **M13** `SignupReviewPage.tsx:50-59,329-332`: 422 codes (`LEVEL_REQUIRED`, `NEXT_INVOICE_DATE_REQUIRED`, `UPFRONT_AMOUNT_EXCEEDS_DUE`, …) show a generic toast instead of the field. §2 D2.
- **M14** `SignupReviewPage.tsx:185-212`: editing the first email or phone deletes the second. Gender, second contacts, notes and documents are missing, and the person block stays editable in add-dog mode. R-04-19, R-04-03.
- **M15** `SignupReviewPage.tsx:418-426,471,334-345`: D2 cannot change the plan (one option; `dryRun` never recalculates) and cannot resolve a family claim that is not FOUND (no holder search, no «sense grup»). §2 D2, R-04-13, T-04-33. Depends on M8.

### Major, security and privacy (api; for the release)
- **M16** `api/…/clubs/census/api/SignupController.java:146` + `web/…/SignupPage.tsx:879-883`: the S3-signed `If-None-Match` header is dropped, so every signup upload fails with 403 on S3 (staging/prod). The tests use local storage. R-04-08.
- **M17** `agilityhub-core-api/src/main/resources/application.yml:49-53`, `api/…/shared/api/RateLimitFilter.java:34`: two problems:
  - behind Caddy, with the default empty `TRUSTED_PROXY_PATTERN`, every applicant shares one limiter bucket and one consent `ipHash`, and DEPLOY.md contradicts the config;
  - routes are matched on the raw URI, so a percent-encoded path is expected to bypass the limit (to confirm with a test).
  
  R-04-20, R-04-17.
- **M18** `SignupService.java:135-151`: an anonymous readmission (DNI only) overwrites the LEFT member's record at submission, with no before-image and no audit. A rejection then loses the original data. R-04-06, R-14-09, GDPR art. 5.

### Minor (fix in the same tasks where cheap; otherwise before the release)
The full lists, with file:line, are in the two source reports. The ones routed to the fix tasks:

**Api:**
- `FAMILY_HOLDER_NOT_FOUND` at submit → `NOT_FOUND_PENDING`.
- `additionalDogOptions` after the cutoff day.
- `allowFamilyGroupPending`/`requireDogDocumentAtSignup` exposed in `GET /signup`.
- `ACCOUNT_NOT_PROVIDED` with `ibanLast4`.
- The D2 `paymentMethod` PATCH keeps the holder, `holderTaxId` and `mandateSignedAt`.
- `censusSignupRejected` uses `payload.dogIds`.
- `Member.signup` snapshot fields.
- Event payloads: `MembershipChanged` roles, the actor, `UpfrontPaymentRecorded.memberId`, `DogDocumentPending.trigger`; no `DogLevelChanged` at validation.
- N-01 member copy without the admin action; N-02/N-39 in `Account.locale`.
- OpenAPI error lists.
- Dashboard:
  - `waitingTotal` null without `WAITLIST`;
  - `followUpUnread` gated by `TASKS`;
  - `RiskNotified.gender` nullable;
  - `percent` as an integer.

**Api, security:**
- `signup.rateLimit` is read.
- Per-recipient caps on N-39 and the applicant's N-01.
- Consents under impersonation are recorded with the actor, never as the member's.
- The anonymous endpoints respect `signup.enabled`.
- Size limits and indexed lookups on the anonymous lookups.
- `SignupEdited` diffs masked in `domain_events`.
- `IdempotencyFilter` retries a transient transaction error (a test with concurrent submissions).
- DEPLOY.md: IAM `DeleteObject` and the proxy doc.

**Web, signup:**
- `planId` omitted when empty.
- The honeypot `aria-hidden`.
- Focus on error after render, with `aria-describedby`.
- Functional draft updates in the async upload and the town lookup.
- The config flags enforced.
- `offerLabel` gated by `FAMILY_GROUP`.
- The image-consent text from `texts.imageConsent`.
- The holder prefilled from the family group.
- `notesToInstructors` and `holderTaxId`.
- The add-dog success page.
- «Laia F..».
- Chip validation.
- The IBAN removed from the draft when the method changes.
- GENERIC profile: an editable prefix and the first document type (latent).
- MSW error shapes and statuses aligned with the api.

**Web, D2 and D1:**
- `STALE_VERSION` gets a reload action, and errors show inside the modal and drawer.
- The refund warning (`paidPaymentRequiresRefund`).
- The breakdown line from `firstMonth`.
- The price from `proposals.priceId`, without a hard-coded «/mes».
- The phone in full.
- en OTHER → «them».
- Module gating of the plan and group rows.
- Accessibility: distinct VALIDA names, `aria-invalid` on the date.
- D1:
  - «(nou gos)»;
  - the overdue date in red;
  - «+0 / −2» signs;
  - «1 avís» (ICU);
  - times as «7:30»;
  - risk rows → D4;
  - the legend with `activeDogWeeks`, and a tooltip with the level name;
  - counters only for ADMIN.
- D5 «Nou abonat» → `/preinscripcions/nova` is fixed.

### Routed elsewhere
- **E8-T04 (Stripe):**
  - the checkout provider call after the commit, with a stable idempotency id;
  - a late completion is still recorded;
  - a cancelled session → DUE;
  - `pay_link` in N-01.
- **E5-T15 (S15):** a risk row after the day's review time, or with the job switched off, is `AT_RISK`, never «s'anul·larà…» (ruling E37).
- **INC register, not a task:** rejected-signup retention (R-14-16b, E11).

## Organizer rulings (DECISIONS_PENDENTS Part E)
- **E35:** the D1 «Gossos per nivell» chart uses the same levels as D3 coverage (progression levels, R-06-06 after E29). Dogs of other levels count in «altres».
- **E36:** screen 13 shows the member's own PENDING dogs with the chip «pendent de validació» and no actions (S04 §13-8). `GET /me/dogs` returns them with `status`.
- **E37:** after the day's review time, or with P2 switched off, an at-risk row is `AT_RISK`; «s'anul·larà» only when P2 will really run. This is an S15 §6 correction.
- **E38:** a readmission no longer overwrites the LEFT record at submission. The submitted data waits in the signup and is applied at validation. D2 shows the old and new values. A rejection leaves the LEFT record as it was. This amends S04 R-04-06.
- **E39:** at D2, a plan change while an upfront row of the submission is `CHECKOUT_PENDING` is refused with `409 INVALID_STATE`, `details.reason = CHECKOUT_PENDING`, and `dryRun` warns about it. `PARTIAL` rows are kept like `PAID` ones, and their paid amount is deducted from the new amount due. This completes S04 §5.

**Spec hygiene applied with the rulings:**
- S04 statuses per the catalog: `SIGNUP_CLOSED`, `SIGNUP_ALREADY_PENDING`, `ID_DOCUMENT_AMBIGUOUS` and `MEMBER_NOT_ACTIVE` are 422.
- An unknown host answers `404 UNKNOWN_HOST` (S02).
- `dashboard.pendingSignupAgeWarnDays` is 2 (the catalog), and the warning is `pendingDays > warnDays` everywhere, as in the D1 text «de fa més de {n} dies».
- S14 R-14-02 subtracts leavers by `leftAt`.
- The «avisat/avisats» examples follow the ICU.

## Fix plan
The E3 fix tasks run before the E4–E8 tasks of each queue, because the executors take the lowest stage first.

| Task | Repo | Content | Depends on | Status 25-09 19:45 |
|---|---|---|---|---|
| **E3-T07** | api | Baseline audit run on `de0e17f` | — | verified |
| **E3-T08** | api | The smoke (step 1); M5 api side (a quote per plan in `GET /signup`); M6, M7, M8 (+ `planOptions`), M9, M10 (E39), M11 server side (+ `warnDays` in the signup view), M20 api side (E36), M21; the dog `version`; the signup flags; E35 | E3-T07 | verified (2 rounds) |
| **E3-T09** | api | M16 api side (+ a MinIO IT), M17, M18 (E38); the security minors | E3-T08 | verified (2 rounds) |
| **E3-T10** | api | The other api minors, the event payloads, the `Member.signup` snapshot, the OpenAPI error lists, the D1 details (`leftAt`, `waitingTotal`, `TASKS` gating, `percent`), and the weak tests | E3-T09 | verified (2 rounds) |
| **E3-T12** | api | Follow-ups of the E3-T09 round-2 review (the readmission checkout email, idempotent recipient caps, N-01/N-03 values from the event, a generation-aware configuration cache) and the D2 `firstMonth` field (added 25-09) | E3-T09, E3-T10 | verified (2 rounds) |
| **E3-T13** | api | Follow-ups of the E3-T10 round-2 review, the checkout: the cut-off only from a public signup, descriptions in each submission's locale (added 25-09) | E3-T10 | verified |
| **E3-T14** | api | The payment methods of a signup: only enabled providers (R-04-10), the Cànic seed, `paymentMethods` in the D2 view (added 25-09, from E3-W07 round 2 on the real core) | E3-T10 (queued after E3-T12/T13) | awaiting verification, round 2 |
| **E3-T15** | api | A failure-safe recipient-cap admission: written before the charge, living as long as its event (added 25-09, from the E3-T12 round-2 review) | E3-T10 | verified |
| **E3-T16** | api | The member's document types in `GET /me/dogs`: screen 13 cannot read `/parameters` (added 25-09, from the E4-W08 report); the Cànic's legal identity, `displayCity` and `legalAddress` for the public footer (Jordi, 25-09); the E3-T15 review's test gaps and the list `size` contract | E3-T10 | ready |
| **E3-T11** | api | Audit re-run after the fixes | E3-T07…T10, E3-T12…T16 | not_open (the organizer opens it) |
| **E3-W05** | web | Baseline audit run | — | verified |
| **E3-W06** | web | B1, M1, M2, M19, the payment texts under the right method; the signup minors (no api change needed) | E3-W04 | verified (2 rounds) |
| **E3-W07** | web | Snapshot adoption; D2: M12–M15, M11 web side, the refund warning; the D2 and D1 minors (the E38 readmission moved to E3-W08) | E3-W06, api E3-T08 | verified (2 rounds) |
| **E3-W08** | web | M3, M4, M5 from the per-plan quote; M16 web side; M20 web side (E36); the signup flags; 16/17 and the public shell; E38 readmission old/new; the three narrow cases of the E3-W06 round-2 review | E3-W07, api E3-T08/T09/T10/T12 | changes requested (round 1) |
| **E3-W10** | web | Test stability (added 24-09 20:45, re-scoped 21:05; not an audit finding): a build-time budget for the MSW mock worlds (E4-W02 round 4's uncached `clubInstant` made 5 of 5 e2e runs time out), the flaky D3b URL test (CI red at `d95b199`), and a lighter e2e container copy | — | verified (2 rounds) |
| **E3-W11** | web | D2 follow-ups of the E3-W07 round-2 review: the quote dropped at reload start, the payment methods from the D2 view; the E3 stage of the real-core e2e for every task id (added 25-09) | E3-W07, api E3-T14 | ready (opened 25-09 19:45) |
| **E3-W12** | web | Screen 13 without `/parameters`: the document types from `GET /me/dogs`, «Nivell» only when the dog carries `level`, mocks that refuse `/parameters` to members (added 25-09, from the E4-W08 report); the footer's registered-office line | api E3-T16 | not_open (opened when E3-T16 is published) |
| **E3-W09** | web | Audit re-run after the fixes | E3-W05…W08, E3-W10…W12, api E3-T16 | not_open |

**Tests that must exist after the fixes:**
- a NIE and a passport submission (Vitest + real-core e2e);
- a retry after a lost 201;
- a total on 19, and labels on days 5 and 17;
- a pack-plan quote;
- family-plan add-dog;
- a validation with the family fare;
- a dog edit after an add-dog;
- 422 codes on D2 fields;
- D1 fresh right after a validation (no wait in the e2e);
- an S3-signed upload against MinIO;
- a readmission that is rejected, with the original data intact;
- a percent-encoded route that is still rate-limited.

**Closing the gate:**
- The organizer verifies every fix task and the two audit re-runs.
- The organizer then re-checks the screenshots of every E3 screen against its mockup, as the gate line requires.
- Then Jordi closes stage E3.
