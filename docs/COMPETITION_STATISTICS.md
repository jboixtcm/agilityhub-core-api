# Competition statistics — branch implementation

Status: implemented for review on `feature/competition-statistics` in the API and web repositories. This does not activate STATS or deploy anything to production. See `competition-statistics-evidence/REPORT.md` for checks and remaining verification.

## Behavior and scope

The member profile links to `/estadistiques` when STATS is enabled. Members select an active dog, FCAG season and RSCE calendar year. The screen shows official net FCAG points and position, dated source links, annual distinct trials with an explicit official point, Agility/Jumping summaries, and per-run details. A zero-penalty completed round is counted separately from official P/PA/PV. Average speed uses completed ranked runs with a reported speed; absent values stay absent.

Administrators open **Resultats de competició**, choose FCAG or FlowAgility, and preview the URL or an optional saved file. Only unique exact licence matches are suggested, preserving leading zeroes. Ambiguous and unmatched rows remain unselected until an administrator assigns a licensed dog of the club. The review checkbox is required before confirming selected rows. Unselected rows are omitted.

A confirmation atomically replaces the snapshot for the same source URL and season. Omitted previous rows are removed, as the review text explains. An import error leaves the previous snapshot intact. Revision checks reject stale previews; replaying the same confirmed preview and assignments returns its original receipt. Previews expire after 24 hours. Requests require `Idempotency-Key`; retrying an unanswered request reuses the same key. Limit: 2 MB and 2,000 source rows.

STATS remains off by default. Social league, scheduled synchronization, bulk event traversal, federation account management and production activation are outside this branch. Administrators import each individual Flow run separately.

## Source evidence and limitations (2026-11-15)

- FCAG public page: <https://app.fcagility.cat/federated/classification/division/128>. Observed HTTP 200 and four category tables, multirow headers and raw/net Total columns. The parser selects the **last Total**, the official net value after discards. The parser was run against the downloaded public HTML and the actual server adapter then fetched and parsed the live page successfully: 57 rows across Petita, Mitjana, Intermèdia and Gran in 2026-2027. It handles the secondary header mixing th/td cells and rejects changed/incomplete data rows.
- Flow's signed-in individual run and print pages were inspected in the user's Chrome session. The print representation has trial/organizer/date captions and 12 result cells (CLA/DO, Guía/Perro, Club/Licencia, T, F, R, PR, ET, TP, VEL, CAL, PTS). Tests recreate that structure with fictional identities. Observed real PTS values were `-`; positive P/PA/PV cases are synthetic and must still be checked against a corresponding live run.
- Anonymous requests redirect to login. `Accept: application/json` returned 406. The public app uses Phoenix LiveView; the [official streaming interface](https://github.com/flowagility/streaming) describes live ring updates. No historical REST API was established.
- Chrome confirms a session cookie named `_facom_key` with HttpOnly. The adapter sends that cookie in a server HTTP request to the canonical individual-run print endpoint. **A real authenticated server request is still unverified:** automatic tool approval denied Terminal access for transferring the browser session into a local probe. No session was committed or provisioned, and the clipboard was restored. Browser access and transport unit tests are not provider authentication verification.
- Supported print headers are the observed Spanish representation. Unexpected layouts or point labels fail closed, preserving existing results.
- The print view exposes a run UUID but no trial UUID. Trial identity is a hash of trial title plus organizer/date. A provider rename can change that identity; review/reimport all runs of the renamed trial consistently. CSV can supply a stable explicit trial ID.

## Flow session configuration

Provision the deployment secret environment variable `APP_COMPETITION_FLOW_SESSIONS` as a JSON object whose keys are exact internal club IDs and whose values are the raw `_facom_key` session values. Both environment examples leave it blank, and production Compose explicitly passes it to Core. Use the private deployment environment; never paste sessions into source files, frontend forms, release manifests, issue reports or committed logs. Recreate Core after rotation so it receives the new environment.

The selected tenant can use only its own entry. The adapter always targets `https://www.flowagility.com/zone/prints/run/{runUuid}/results`, refuses redirects, bounds the entire request at 20 seconds and the response at 2 MB. Credentials are never returned or logged by the adapter. Missing/invalid sessions, login pages and authorization redirects produce `VALIDATION_ERROR` with reason `SOURCE_AUTH_REQUIRED`, translated into a renewal/configuration message. Renewal is an operator action, not an automatic password login.

The browser's normal `/zone/run/{uuid}/results#list_anchor_header` URL is accepted and canonicalized to the same print source as `/zone/prints/run/{uuid}/results`; alternate hosts, credentials in URLs, ports, queries, encoded paths and arbitrary fragments are rejected. No provider mutation is performed.

Before deployment approval, provision a session securely, preview one known live run through the server, compare its result fields and explicit point labels with Flow, and confirm only reviewed club dogs in a test tenant. Renew/revoke the session afterwards as appropriate. A successful local mock test alone does not satisfy this check.

## Saved input

FCAG accepts saved HTML of its division classification page. Flow accepts saved HTML of an individual run's **print results** view, or a reviewed CSV interchange file. This CSV is an application template, not a claimed native Flow export. Its exact header is:

```csv
license,dogName,trialId,runId,date,trial,grade,category,discipline,position,faults,refusals,totalPenalty,timeSeconds,speedMps,qualification,point
0123,Núvol,trial-example,run-example,2026-11-15,Prova de tardor,G3,M,JUMPING,2,0,0,0,35.21,5.4,EXC_0,PA
```

Use stable IDs, ISO dates, decimal numbers and `AGILITY` or `JUMPING`. Official point must be `NONE`, `P`, `PA` or `PV`; never infer it from time, speed or penalties. Blank optional numeric fields preserve unknown/disqualified results. A file must contain one calendar year. CSV quoting and UTF-8 BOM are supported. The UI offers the template download.

## API, privacy and authorization

All routes require STATS and authenticated tenant context:

| Route | Role |
|---|---|
| `GET /api/v1/me/dogs/{id}/competition-statistics` | MEMBER, own/family dog under existing ownership policy |
| `GET /api/v1/dogs/{id}/competition-statistics` | ADMIN or INSTRUCTOR, same club |
| `GET /api/v1/competition-imports` | ADMIN |
| `POST /api/v1/competition-imports/preview` | ADMIN |
| `POST /api/v1/competition-imports/{id}/confirm` | ADMIN |

Only selected results persist in `competition_imports`. Parsed previews in `competition_previews` have a TTL index and explicit expiry enforcement. Personal data exports include `competitionResults` for the subject's dogs only. Erasure removes those entries and clears the tenant's previews (unassigned rows can also contain the subject), and their exact response-cache entries, leaving other tenants and unrelated write retries untouched. Confirmations reuse the existing `MIGRATION_APPLIED` audit action with entity type `CompetitionImport`; no new catalog codes or notification events were invented.

Tests use task-local `T-STATS-01`…`T-STATS-32` identifiers because no assigned roadmap task covers this user-requested feature. Existing organizer task/status files are unchanged. New interface copy is proposed feature text in Catalan, Spanish and English; there is no approved import-screen mockup. Both screenshots accompany the validation report for review.


## Importació històrica i setmanal — 16 novembre 2026

El procés operatiu de `ops/flow_imports/` descobreix les proves FCI/RSCE i les mànegues de cada pista/dia, i utilitza la mateixa previsualització i confirmació de l'API. Només assigna coincidències úniques de llicència del club. Les importacions ja existents no se sobreescriuen; les fallades es poden reprendre. La configuració i la sessió renovada es desen privadament al volum xifrat. El servei programat és independent dels processos de reserves. L'activació efectiva i els resultats es documenten a les evidències operatives.

Les etiquetes oficials observades són `1P`, `1PA`, `1PV` i `1PA / 1PV`. P sense PV existeix (G2/XS/JP, febrer de 2026). La combinació s'exposa com `PA / PV`, incrementa els dos comptadors i conserva una sola mànega i prova. P, PA i PV simples només incrementen el seu comptador. Cap punt no es dedueix de la velocitat, la qualificació o les penalitzacions. Les multiplicitats desconegudes, com `2PV`, es refusen.

## RSCE grade progression (2026 consolidated regulation)

The supplied official regulation, effective 2026-07-01, articles 60–63 and 71, is linked in every `rsceProgress` projection. This is point progress for the standard route, not an official eligibility certificate. It introduces no configurable club parameter or notification.

- Each RSCE licence is evaluated separately. The registered grade takes precedence; the latest result is a labelled fallback, and conflicting grades are exposed. Reaching a target never modifies census data or promotes a dog.
- G1 → G2: three clear Excellent Agility rounds, or two Agility plus two Jumping. Two different judges remain an explicit unverified condition because current imported rows do not identify judges. G1 has no ordinary championship route; J12 and Para-Agility exceptions are explained.
- G2 → G3: three explicitly awarded PA per discipline, over all imported years. PA / PV contributes one to each award counter, once to the round/competition total. Prior higher-grade results trigger a downgrade review; earlier promotion points are not reused automatically.
- G2 championship: six qualifying points including at least four PV per discipline. G3: four PV in G3 per discipline. P/PV/PA raw counters stay independent; a combined PA / PV counts once in the championship total. PA alone is not silently reclassified as PV.
- Championship years use the previous calendar year's results. G1 and other licences are excluded. A detected G2 → G3 transition offers the G2 alternative with an explicit prior club-notification condition. Older championship periods are not recomputed using the 2026 standard.
- Island residency, double points for mainland competitors at island events, debut exceptions, junior/Para-Agility conditions, direct podium qualifications, historical completeness and administrative notices require club verification. The interface labels the standard route and links the regulation; it never declares official qualification from counters alone.

`CompetitionImportRepository.forDog` now loads the tenant-scoped imported history so non-expiring promotion awards survive annual filters. Annual results and summaries retain the selected year. Coverage dates identify the available results, not a guarantee of complete sporting history.
