# Roadmap protocol — organizer ↔ executor

This folder is the **shared context** between two AI agents that never talk directly:

- **Executor** (OpenAI model running in this repository): implements one task per session, reports evidence.
- **Organizer** (Claude, working from the project documentation): prepares tasks, verifies evidence, answers questions, opens the next tasks.
- **Jordi** (product owner): creates repos/accounts, answers decisions, runs the sessions.

Everything both agents need to know is written in files here. Nothing is assumed from chat history.

## Files

| File | Who writes | Purpose |
|---|---|---|
| `ROADMAP.md` | organizer | Stages E0–E12, task order, gates. Read-only for the executor. |
| `tasks/<ID>.md` | both (separate sections) | One file per task. Front matter = state. Executor fills **Executor report**; organizer fills **Organizer verification**. Everything lives on `main` (trunk-based; the publish script commits the executor's working tree after each session). |
| `STATUS.md` | generated | Board rendered from the task front matter by `tools/check.py --render`. Never edit by hand. |
| `MESSAGES.md` | both | Append-only log for questions, answers, decisions, blockers. |
| `tools/check.py` | organizer | Validates the roadmap and renders `STATUS.md`. Run it before every commit that touches `roadmap/`. |

## Task states (front matter `status:`)

| State | Meaning | Who sets it |
|---|---|---|
| `not_open` | not yet ready to start (stage not opened, or details pending) | organizer |
| `ready` | fully specified, dependencies satisfied, can be started | organizer |
| `in_progress` | an executor session is working on it (branch exists) | executor |
| `awaiting_verification` | executor finished, report filled, session published to `main` | executor |
| `changes_requested` | organizer found issues; numbered list in the task file | organizer |
| `blocked` | cannot proceed; reason + who unblocks in `MESSAGES.md` | either |
| `verified` | organizer checked evidence and code; goes into the next promotion of `main` → `release` | organizer only |

Only the organizer sets `verified`. The executor never edits the **Organizer verification** section. The organizer never edits the **Executor report** section.

## Executor session — step by step

1. Read `AGENTS.md` (repo root), then `roadmap/STATUS.md`, then `roadmap/MESSAGES.md` (answers addressed to `@executor`).
2. If a task is `changes_requested`, take it first. Otherwise take the **first** task in `ROADMAP.md` order whose status is `ready` and whose `depends_on` tasks are all `verified` (or `awaiting_verification` — allowed, but write a note in your report; if that dependency later gets `changes_requested`, you must rebase).
3. Run `python3 roadmap/tools/check.py --set <ID> in_progress`. **Never run git commands that write** (your sandbox keeps `.git` read-only): no branches, no commits. The publish script commits your working tree on `main` after the session, with the task ids and statuses in the commit message.
4. Do the work following the task file exactly. Open **only** the files listed under **Context to load** plus the code you touch. Do not start other tasks.
5. Run every command under **Verification**. Paste the **full output** (not a summary) into **Executor report → Evidence**.
6. Fill the rest of the report: files changed, rules/tests implemented (`R-xx-nn`, `T-xx-nn`), assumptions, questions, proposals for catalogs (parameters, events, notifications, error codes) — proposals are never applied silently.
7. Run `python3 roadmap/tools/check.py --set <ID> awaiting_verification` (it validates the report and renders `STATUS.md`). The publish script (`mac/publish.sh`, run by Jordi's wrapper after the session) commits everything on `main` and pushes — CI runs on `main`. Trunk-based: code and roadmap state always live on `main`; `release` is promoted by Jordi after verification.
8. If you are blocked: `check.py --set <ID> blocked`, append an entry to `MESSAGES.md` starting with `@organizer` (or `@jordi` for accounts/accesses), and stop.
9. Never touch `main` directly. Never delete or rewrite other tasks' files. Never mark anything `verified`.

## Organizer session — step by step

1. Read `STATUS.md`, `MESSAGES.md` (entries addressed to `@organizer`), and every task in `awaiting_verification` or `blocked`.
2. For each task awaiting verification: (a) read the Executor report and the diff of the session commit(s) on `main` (commits are named `executor: <ID> <status>, ...`; `git show <sha>`), plus the CI run of that push (`gh run list --branch main`); (b) re-run what can be re-run (static checks, `check.py`, `pnpm` tests, scripts); accept CI output as evidence for what needs Docker/Java; (c) check every `T-xx-nn` of the task exists as a test name and every `R-xx-nn` is implemented; (d) check i18n (3 locales), forbidden vocabulary, catalog usage, OpenAPI snapshot; (e) compare screenshots with `docs/pantalles/*.png` for front tasks.
3. Write the **Organizer verification** section: `Result: verified` or `Result: changes_requested` with a numbered list of concrete corrections; date.
4. Answer questions in `MESSAGES.md` with entries starting `@executor`. Record decisions that change specs in `docs/` (synced from the project documentation) and in the task file.
5. Open the next tasks (`not_open` → `ready`), adding any detail that depends on the results just verified. Update `ROADMAP.md` if the order changes.
6. `python3 roadmap/tools/check.py --render`, commit `chore(roadmap): verification <IDs>` on `main` (Jordi pushes it with `mac/status.sh`; when everything reviewed is verified, Jordi promotes `main` → `release` with `mac/promote.sh`).
7. Stage gate: when all tasks of a stage are `verified`, run the gate checklist in `ROADMAP.md` with Jordi and record the result in `MESSAGES.md` (`GATE E0: passed/failed + notes`).

## MESSAGES.md entry format

```
## 2026-09-08 · executor → organizer · E0-T05
@organizer **Question** — The parameter catalog lists `x` as `duration` but the spec example uses an int. Which one?
Blocking: no (assumed `duration`, see report).
```

```
## 2026-09-09 · organizer → executor · E0-T05
@executor **Answer** — `duration` (minutes). Catalog updated in docs/specs/00-transversal/CATALEG_PARAMETRES.md (line 37).
```

Keep entries short. One topic per entry. Newest at the bottom.

## Definition of Done (every task)

- All Verification commands pass with their expected results, and the outputs are in the report.
- Tests named after the spec ids (`T-xx-nn`) exist and pass; coverage thresholds hold.
- No new parameter / event / notification / error code outside the catalogs (proposals go in the report and in `MESSAGES.md`).
- UI strings in `ca`, `es`, `en` (front tasks); no forbidden vocabulary.
- `CHANGELOG.md` updated (Unreleased section), OpenAPI snapshot updated when the API changes.
- `status: awaiting_verification` set in the task file (the publish script commits the session on `main` and pushes).
