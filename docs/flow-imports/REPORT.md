# Flow historical and weekly import follow-up

User-requested isolated branch `feature/flow-weekly-imports`. The operational plan and production evidence live in SOFTWARE_CANIC `implementation/flow-weekly/` and `ops/evidence/2026-11-16-flow-weekly/`.

Observed provider point labels: `1P`, `1PA`, `1PV`, `1PA / 1PV`. The readable wire enum `PA / PV` preserves simultaneous awards. Existing member and administration screens display this source classification string without needing a new translation. No inferred point, new business parameter, notification or error code is introduced.

A bounded Python worker discovers the authenticated archive and LiveView run navigation, renews the provider cookie in protected storage, filters exact licensed rows before preview, preserves existing sources, and resumes failed imports. A separate unprivileged process avoids blocking booking jobs. Scheduled activation and full backfill are not established by source publication alone.

Validation in progress: 17 Python regression tests passed locally and on the production host before preview. Focused Java parser/statistics/OpenAPI tests passed; regenerated contract adds only the combined point enum. A clean Maven verification is running. Final CI, deployment and historical counts will be appended after observation. All committed fixtures use fictional identities.
