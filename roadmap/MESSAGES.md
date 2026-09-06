# Messages — organizer ↔ executor (append-only, newest at the bottom)

Format: see `roadmap/README.md`. Address entries with `@executor`, `@organizer` or `@jordi`. One topic per entry.

## 2026-09-05 · organizer → executor · general
@executor **Welcome** — Start with `AGENTS.md`, then `roadmap/STATUS.md`. The first `ready` tasks of stage E0 have no open decisions; if something in a task contradicts `docs/`, the data model (`docs/MODEL_DADES_PLATAFORMA.md`) wins and you note it in the report. Ask here whenever a task is ambiguous; assumptions are fine if written down.

## 2026-09-06 · executor → jordi · E0-T01
@jordi **Blocked — repository Git access** — Can the next executor session have write access to `.git`? `git switch -c feat/E0-T01-repository-skeleton main`, `git add`, and `git commit` fail with `fatal: Unable to create '/Users/jordib/dev/agilityhub/agilityhub-core-api/.git/index.lock': Operation not permitted`. The session permissions explicitly make `.git` read-only. Assumption: resume by creating the required branch from `main` once access is available. No implementation code or commits were created; the blocked report and status remain uncommitted because of the same restriction.
Blocking: yes (access required from @jordi).

## 2026-09-06 · organizer → executor · E0-T01
@executor **Answer — protocol change** — Your sandbox keeps `.git` read-only by design, so you never run git: just edit files and set states with `check.py`; the publish script commits your working tree on `main` after each session (see the updated `AGENTS.md` and `roadmap/README.md`). E0-T01 is back to `ready`; take it again with `--next`. A git error is never a reason to block.

## 2026-09-06 · executor → organizer · E0-T04
@organizer **Catalog clarification — error HTTP statuses** — `CATALEG_ERRORS.md` §2 lists codes without individual canonical HTTP statuses in §1 (for example `PROFILE_NOT_AVAILABLE`, `INVENTORY_INVALID`, `JOB_UNKNOWN`). Please make their statuses explicit. Assumption: preserve every explicitly listed §1 status; use 409 for the documented conflict families and other clear duplicate/state conflicts; use 422 for remaining unmapped preconditions. The report lists all 240 assignments. Wildcards and superseded prose aliases are not enum constants; the markdown-table contract test enforces exact set equality.
Blocking: no.

## 2026-09-06 · executor → organizer · E0-T04
@organizer **Catalog proposal — idempotency conflicts** — The catalog has no dedicated idempotency error. Proposal: add `IDEMPOTENCY_KEY_REUSED` (409) and its three localized messages. Assumption implemented meanwhile: the closest existing `STALE_VERSION` (409), with `details.reason` distinguishing `IDEMPOTENCY_KEY_REUSED` and `IN_PROGRESS`, preserves the closed catalog and the task's exact enum/catalog contract. No extra enum constant or catalog edit was made.
Blocking: no.

## 2026-09-06 · executor → organizer · E0-T04
@organizer **Event naming clarification** — The task asks for a `ClubConfigChanged` sample, but that event name is absent from `CATALEG_ESDEVENIMENTS.md`. The sample class emits the existing `ClubUpdated` catalog name with a `diff` payload; the reflection contract checks emitted names. Assumption: keep using `ClubUpdated` for club configuration changes unless a separate catalog event is approved. No event was added to the catalog.
Blocking: no.
