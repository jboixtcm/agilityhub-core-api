# Flow historical and weekly import follow-up

User-requested isolated branch `feature/flow-weekly-imports`. The operational plan and production evidence live in SOFTWARE_CANIC `implementation/flow-weekly/` and `ops/evidence/2026-11-16-flow-weekly/`.

Observed provider point labels: `1P`, `1PA`, `1PV`, `1PA / 1PV`. The readable wire enum `PA / PV` preserves simultaneous awards. Existing member and administration screens display this source classification string without needing a new translation. No inferred point, new business parameter, notification or error code is introduced.

A bounded Python worker discovers the authenticated archive and LiveView run navigation, renews the provider cookie in protected storage, filters exact licensed rows before preview, preserves existing sources, and resumes failed imports. A separate unprivileged process avoids blocking booking jobs. Scheduled activation and full backfill are not established by source publication alone.

Validation in progress: 17 Python regression tests passed locally and on the production host before preview. Focused Java parser/statistics/OpenAPI tests passed; regenerated contract adds only the combined point enum. A clean Maven verification is running. Final CI, deployment and historical counts will be appended after observation. All committed fixtures use fictional identities.

User follow-up: import all available history (not only 2026), separate Agility and Jumping P/PV/PA counters, show grade, and implement standard RSCE progress from the supplied official regulation. Added per-licence lifetime promotion and annual championship projections, grade provenance/conflict, alternative G1 route, and explicit missing judge/special-route verification. Current main including PR #18 was merged to preserve unrelated payment changes.

Focused validation: RsceProgressTest, CompetitionStatisticsTest, FlowPrintResultsParserTest and regenerated OpenApiSnapshotTest passed. CompetitionImportsIT passed, including previous-year PA and tenant isolation. The preceding pre-regulation clean verification passed; the final expanded clean verification is being rerun.

A real 2026 Friday competition was found during the backfill. Weekly windows therefore include Friday–Sunday, with DST/new-year boundary coverage. Another imported official PA omits numerical total penalties; G1 promotion now trusts an explicit PA even when that optional statistic is absent, while raw clear-run statistics remain uninferred.

Production coverage audit follow-up (2026-11-16): a sample of cached unsupported pages found valid numbered AG1/AG2/JP1/JP2 runs. Both Core and the worker now accept the numeric suffix while keeping the source UUID as the run identity. Unsupported checkpoints carry a result-format version so the corrected importer reconsiders older exclusions without replaying imported sources or unrelated no-match pages. T_STATS_49 covers discipline, separate run identity and rejected invalid labels; worker regressions cover source preservation and checkpoint migration. Full corrected archive replay remains an operational requirement, not established by code publication.

Numbered-run targeted validation passed (exit 0):

- `/private/tmp/canic-flow-worker-venv/bin/python -m unittest discover -s ops/flow_imports -v`: 24 tests.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -q -Dmaven.repo.local=/tmp/competition-m2 -Dtest=FlowPrintResultsParserTest,CompetitionParserBoundariesTest,CompetitionStatisticsTest test`: 11 tests.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -q -Dmaven.repo.local=/tmp/competition-m2 -Dtest=CompetitionImportsIT -Dsurefire.failIfNoSpecifiedTests=false test`: all cases passed, including T_STATS_50 persisted numbered-run readback.

Complete logs and exact class counts: `docs/flow-imports/evidence/numbered-runs/`. Final clean verification, image security gates, publication and production replay must pass before this correction is considered deployed.
