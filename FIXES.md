# Fix log — pre-production audit (Nov 2026)

Branch `fix/pre-production-audit`, based on the production commit. One entry per audit finding. Statuses: FIXED, PARTIAL, NEEDS-DECISION (no code touched; question and options stated), NOT-CODE (operational, data or process), INVALID, REVERTED-BY-REVIEW. `OTHER-REPO:` marks a change still needed in the other repository. Finding IDs refer to `PRE_PRODUCTION_AUDIT_2026-11-11.md`.

Integration tests (`*IT`) need MongoDB and were not run in the environment where these fixes were written; run the full CI before merging.
