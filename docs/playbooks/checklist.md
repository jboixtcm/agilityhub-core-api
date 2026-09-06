# Task report checklist

Copy the applicable items into the **Executor report** of the one selected task.
Keep the report's required headings; record why any item is not applicable.
The current [AGENTS.md](../../AGENTS.md) and [roadmap protocol](../../roadmap/README.md)
govern the session. Excerpts in the companion playbooks link to their real source
files; copy imports and enclosing context from those files when adapting them.

## Scope and implementation

- [ ] Read `AGENTS.md`, `roadmap/README.md`, `STATUS.md`, `MESSAGES.md`, then run
  `python3 roadmap/tools/check.py --next` and read the selected task completely.
- [ ] For `changes_requested`, address exactly the organizer's numbered items
  and append **Round 2** to the report. For `in_progress`, continue its report.
- [ ] Set the selected task `in_progress`; work on exactly that task, its allowed
  context and touched code. Note any dependency still `awaiting_verification`.
- [ ] Follow the task's Steps/Fixed conventions, glossary names and model precedence;
  cite conflicts and assumptions. Keep identifiers, comments and reports in English.
- [ ] Use [new-entity](new-entity.md), [new-endpoint](new-endpoint.md),
  [new-scheduler](new-scheduler.md), or [new-event-consumer](new-event-consumer.md)
  for the relevant implementation pattern.
- [ ] Every club document carries `clubId`; use `TenantRepository` and test isolation.
  Restrict `GlobalRepository` to model-defined global or privileged infrastructure.
- [ ] Use catalog-only parameters, events, notifications and errors. Put missing-item
  proposals in the report and `roadmap/MESSAGES.md`; never apply them silently.
- [ ] No club literals in code: rules use approved parameters/catalogs/seeds. Use
  `Money{amountMinor, currency}`, injected `Clock`, UTC instants and `ClubClock`.
- [ ] Spec-audited mutations use `@Audited`; aggregate, audit and `EventPublisher`
  outbox writes commit together. Mask sensitive audit fields and test rollback.
- [ ] Errors use `ApiException(ErrorCode.X)`; preserve ca/es/en message parity.
  Frontend tasks also check UI translations, forbidden vocabulary and mockups.
- [ ] Secrets come only from environment variables, with `${VAR}` configuration
  and `.env.example` names. No credentials, `.env`, dumps or personal data in changes;
  fixtures use fictional `@example.test` people. Truncate every token/hash in reports.

## Verification and evidence

- [ ] Implement the task's `R-xx-nn` rules and `T-xx-nn` tests in the same task;
  use Java names such as `T_02_03_catalogMatchesDocument` and list the actual mapping.
- [ ] Every endpoint has happy-path, tenant, role and applicable module-off tests;
  include negative/rollback/idempotency cases required by the spec.
- [ ] Run every task **Verification** command and paste its **full output**, command
  and exit status in **Evidence**. Label failures/retries; do not summarize away output.
- [ ] For implementation changes, run `./mvnw -q verify`: domain/application ≥85%
  lines and ≥80% branches, API ≥70% lines, architecture and catalog contracts pass.
  For documentation-only tasks, follow their specified checks and verify excerpts.
- [ ] After any API change, run `bin/openapi-snapshot` and review/include
  `docs/openapi/openapi.json`. Explain non-applicability when no API changed.
- [ ] For playbook changes, check the five filenames, English text, ≤150 lines each,
  `wc -w docs/playbooks/*.md`, source links, and excerpts against compilable source.

## Report and handoff

- [ ] Fill **Branch / commits** (working tree only; publish script handles git),
  **Evidence**, **Files changed**, **Rules and tests implemented**, **Assumptions**,
  and **Questions / catalog proposals**. Use explicit “None” where appropriate.
- [ ] Update `CHANGELOG.md` under **Unreleased** with the task ID and resulting change.
- [ ] Use read-only `git status` / `git diff` to review scope. Never run branch,
  checkout, add, commit, stash or push; `.git` is read-only in executor sessions.
- [ ] Run `python3 roadmap/tools/check.py --set <ID> awaiting_verification`;
  fix report validation errors. Let it render `STATUS.md`; do not edit it by hand.
- [ ] Never edit another task, `roadmap/ROADMAP.md`, or **Organizer verification**;
  never mark a task `verified`. Finish by printing the task ID and final status.
- [ ] If blocked by access or an unresolvable contradiction, set `<ID> blocked`,
  append the question and proposed assumption to `roadmap/MESSAGES.md` addressed to
  `@organizer` (`@jordi` for access/accounts), and stop. Git write access is not a blocker.
