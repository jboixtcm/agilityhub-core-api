#!/usr/bin/env python3
"""E3-T11 spec-test traceability for S04 and S14 (a copy of the E3-T07 script).

Reads the JUnit XML reports of the last build (target/surefire-reports and
target/failsafe-reports) and writes, next to this script:

- trace-tests.csv    one row per (specId, test method): specId, class, method, result
- trace-summary.md   per spec id in scope: methods, all passing or not, «missing»;
                     plus the section «Audit tests» (E3-T11 step 3): for each api item of
                     the gate audit's required-tests list, the test that proves it and its result
- 02-test-summary.log totals of each report directory plus one line per test class
                      whose name or methods contain T_04_ or T_14_

A parameterized method (several <testcase> invocations) is one method; its result
is PASS only when every invocation passes. A method named T_04_12_T_04_13_... gives
one row per id. Run from the repository root: python3 roadmap/evidence/E3-T11/trace.py
Changes from E3-T07: the titles, and the «Audit tests» section (AUDIT below).
"""
import csv
import re
import sys
import xml.etree.ElementTree as ET
from collections import OrderedDict, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
REPORTS = OrderedDict(surefire=ROOT / "target/surefire-reports", failsafe=ROOT / "target/failsafe-reports")
SPEC_ID = re.compile(r"(?<![0-9A-Za-z])T_(04|14)_(\d{2})(?!\d)")

# Scope of the task: S04 §11 in full and the listed S14 ids.
S04 = [f"T-04-{n:02d}" for n in range(1, 35)]
S14 = [f"T-14-{n:02d}" for n in (1, 2, 3, 4, 5, 6, 11, 22, 23, 25)]
# Ids the specs assign to the front layer (component / Playwright) or to the stack smoke.
FRONT = {"T-04-29", "T-04-30", "T-04-31", "T-04-32", "T-04-33", "T-14-25"}
SMOKE = {"T-04-34": "bin/e3-smoke (E2E against the local stack)", "T-14-11": "also bin/e3-smoke"}

# roadmap/reviews/gate-E3/consolidated.md, «Tests that must exist after the fixes»: the api items, as E3-T11 step 3
# lists them. Each maps to the test method(s) that prove it; the result is read from the JUnit XML, never assumed.
CENSUS = "com.agilityhub.core.clubs.census.api."
AUDIT = [
    ("a retry after a lost 201: the same Idempotency-Key gives the same 201",
     [(CENSUS + "SignupIT", "T_04_23_idempotencyReplaysEncryptedResponseAndClosesSignup")],
     "public POST /signup replayed with the same key: identical 201 body, one member. No add-dog (POST /me/dogs/signup) "
     "same-key replay test exists; that route relies on the shared IdempotencyFilter (IdempotencyIT)"),
    ("family-plan add-dog",
     [(CENSUS + "SignupGateFixesIT", "T_04_21_familyFareMemberAddsADogAndReadsTheSignupConfiguration")],
     "an ABONAT_FAMILIAR member reads GET /signup on days 1, 17 and 25 and adds a dog (201)"),
    ("a validation with the family fare",
     [(CENSUS + "SignupGateFixesIT", "T_04_16_foundFamilyClaimProposesAndValidatesTheHiddenFamilyFare")],
     "a FOUND claim proposes the hidden ABONAT_FAMILIAR (90 €/month) and the validation stores it with the group"),
    ("a dog edit after an add-dog",
     [(CENSUS + "SignupGateFixesIT", "R_04_19_reviewDogCarriesTheVersionThatTheDogPatchCompares")],
     "add-dog, then a member PATCH, then two dog PATCHes with the D2 dog's own version (200)"),
    ("D1 fresh right after a validation, with no sleep",
     [("com.agilityhub.core.clubs.dashboard.api.DashboardIT", "T_14_11_R_14_01_submissionValidationAndRejectionRefreshD1WithoutTheOutbox")],
     "submission, validation and rejection each read D1 and the counters immediately; no sleep, no outbox dispatch"),
    ("an S3-signed upload against MinIO",
     [(CENSUS + "SignupS3UploadIT", "R_04_08_T_04_13_signedSignupUploadNeedsTheReturnedHeadersAndWritesOnce")],
     "against LocalStack S3 with signature validation on, not MinIO (E3-T09 deviation, accepted by the organizer: "
     "the MinIO images can no longer be pulled)"),
    ("a readmission that is rejected, with the original data intact",
     [(CENSUS + "SignupSecurityFixesIT", "T_04_12_T_04_19_rejectedReadmissionLeavesTheRecordExactlyAsItWas")],
     "the LEFT record after the rejection equals the record before the readmission (version/updatedAt aside)"),
    ("a percent-encoded route that is still rate-limited",
     [(CENSUS + "SignupSecurityFixesIT", "R_04_20_T_04_28_percentEncodedAndDoubleSlashRoutesShareTheLimitOfThePlainRoute")],
     "/signup/identity-%63hecks gets 429 RATE_LIMITED after 10 plain calls; `//` is refused with 400 by the firewall"),
    ("the per-plan quote (pack, Teràpia, days 5 and 17)",
     [(CENSUS + "SignupGateFixesIT", "T_04_05_T_04_06_eachPlanGetsItsOwnQuoteLikeTheSubmission")],
     "Cànic seed: ABONAT on 17-08 (TODAY HALF 130 € / ALTERNATIVE FULL 160 €) and 05-08 (FULL 160 € / HALF 130 €), "
     "PACK6 135 € with no options, TERAPIA 50 € with no options"),
]


def method_name(name):
    """Strip the JUnit parameter list and invocation index: m(String)[1] -> m."""
    return name.split("(", 1)[0].split("[", 1)[0]


def outcome(case):
    for tag, value in (("error", "ERROR"), ("failure", "FAIL"), ("skipped", "SKIPPED")):
        node = case.find(tag)
        if node is not None:
            message = (node.get("message") or node.get("type") or "").strip().splitlines()
            return value, (message[0][:300] if message else "")
    return "PASS", ""


def main():
    totals = OrderedDict()
    methods = OrderedDict()   # (class, method) -> {"results": [...], "messages": [...]}
    classes = defaultdict(lambda: dict(tests=0, failures=0, errors=0, skipped=0, time=0.0, report=""))
    for report, directory in REPORTS.items():
        files = sorted(directory.glob("TEST-*.xml"))
        if not files:
            sys.exit(f"no JUnit XML in {directory}; run ./mvnw -q clean verify first")
        total = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
        for path in files:
            suite = ET.parse(path).getroot()
            total["suites"] += 1
            for key in ("tests", "failures", "errors", "skipped"):
                total[key] += int(suite.get(key, "0"))
            for case in suite.iter("testcase"):
                klass, method = case.get("classname", ""), method_name(case.get("name", ""))
                result, message = outcome(case)
                entry = methods.setdefault((klass, method), dict(results=[], messages=[], report=report))
                entry["results"].append(result)
                if message:
                    entry["messages"].append(message)
                stats = classes[klass]
                stats["report"] = report
                stats["tests"] += 1
                stats["failures"] += result == "FAIL"
                stats["errors"] += result == "ERROR"
                stats["skipped"] += result == "SKIPPED"
                stats["time"] += float(case.get("time", "0") or 0)
        totals[report] = total

    rows = []
    by_id = defaultdict(list)
    for (klass, method), entry in methods.items():
        results = entry["results"]
        result = next((r for r in ("ERROR", "FAIL") if r in results), None) \
            or ("SKIPPED" if all(r == "SKIPPED" for r in results) else "PASS")
        seen = []
        for match in SPEC_ID.finditer(method):
            spec = f"T-{match[1]}-{match[2]}"
            if spec not in seen:
                seen.append(spec)
        for spec in seen:
            row = dict(specId=spec, klass=klass, method=method, result=result, invocations=len(results),
                       messages=entry["messages"], report=entry["report"])
            rows.append(row)
            by_id[spec].append(row)

    rows.sort(key=lambda r: (r["specId"], r["klass"], r["method"]))
    with (OUT / "trace-tests.csv").open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["specId", "class", "method", "result"])
        for row in rows:
            writer.writerow([row["specId"], row["klass"], row["method"], row["result"]])

    lines = ["# E3-T11 · spec-test traceability (S04 §11, S14 in scope)", "",
             "Generated by `python3 roadmap/evidence/E3-T11/trace.py` from the JUnit XML of "
             "`./mvnw -q clean verify` (surefire = unit, failsafe = integration).",
             "«methods» counts test methods (a parameterized method counts once; «invocations» counts its runs).",
             "«missing» = no test method in this repository carries the id.", "",
             "| Spec id | Methods | Invocations | All pass | Classes | Note |", "|---|---|---|---|---|---|"]
    missing = []
    for spec in S04 + S14:
        found = by_id.get(spec, [])
        note = []
        if spec in FRONT:
            note.append("front layer per spec (web repository)")
        if spec in SMOKE:
            note.append(SMOKE[spec])
        if not found:
            missing.append(spec)
            lines.append(f"| {spec} | 0 | 0 | missing | — | {'; '.join(note)} |")
            continue
        passing = all(r["result"] == "PASS" for r in found)
        status = "yes" if passing else "NO (" + ", ".join(sorted({r["result"] for r in found if r["result"] != "PASS"})) + ")"
        names = sorted({r["klass"].rsplit(".", 1)[-1] for r in found})
        lines.append(f"| {spec} | {len(found)} | {sum(r['invocations'] for r in found)} | {status} | "
                     f"{', '.join(names)} | {'; '.join(note)} |")
    failing = [r for r in rows if r["result"] not in ("PASS",)]
    lines += ["", f"Ids in scope: {len(S04 + S14)} · with tests: {len(S04 + S14) - len(missing)} · "
              f"missing: {len(missing)} ({', '.join(missing) or 'none'})",
              f"Rows in trace-tests.csv: {len(rows)} (all T_04_/T_14_ ids found, including S14 ids outside the scope)",
              f"Non-passing rows: {len(failing)}"]
    for row in failing:
        lines.append(f"- {row['specId']} {row['klass']}#{row['method']}: {row['result']} — "
                     f"{row['messages'][0] if row['messages'] else '(no message)'}")
    scope_block = lines[-(3 + len(failing)):]
    out_of_scope = sorted(set(by_id) - set(S04 + S14))
    lines += ["", "S14 ids found outside the task scope (informative): " + (", ".join(
        f"{s} ({len(by_id[s])})" for s in out_of_scope) or "none")]

    lines += ["", "## Audit tests", "",
              "`roadmap/reviews/gate-E3/consolidated.md`, «Tests that must exist after the fixes»: the api items listed in "
              "E3-T11 step 3. The result is read from the JUnit XML of the same build.", "",
              "| # | Audit item | Test class#method | Report | Invocations | Result | What it proves |",
              "|---|---|---|---|---|---|---|"]
    audit_missing, audit_failing = [], []
    for index, (item, tests, proof) in enumerate(AUDIT, 1):
        for klass, method in tests:
            entry = methods.get((klass, method))
            if entry is None:
                audit_missing.append(item)
                lines.append(f"| {index} | {item} | `{klass.rsplit('.', 1)[-1]}#{method}` | — | 0 | **missing** | {proof} |")
                continue
            results = entry["results"]
            result = next((r for r in ("ERROR", "FAIL") if r in results), None) \
                or ("SKIPPED" if all(r == "SKIPPED" for r in results) else "PASS")
            if result != "PASS":
                audit_failing.append(f"{item}: {klass}#{method} {result} — "
                                     f"{entry['messages'][0] if entry['messages'] else '(no message)'}")
            lines.append(f"| {index} | {item} | `{klass.rsplit('.', 1)[-1]}#{method}` | {entry['report']} | "
                         f"{len(results)} | {result} | {proof} |")
    lines += ["", f"Audit items: {len(AUDIT)} · passing: {len(AUDIT) - len(audit_missing) - len(audit_failing)} · "
              f"missing: {len(audit_missing)} · not passing: {len(audit_failing)}"]
    lines += [f"- NOT PASSING: {line}" for line in audit_failing] + [f"- MISSING: {item}" for item in audit_missing]
    audit_block = lines[-(1 + len(audit_failing) + len(audit_missing)):]
    (OUT / "trace-summary.md").write_text("\n".join(lines) + "\n")

    summary = []
    for report, total in totals.items():
        summary.append(f"{report}: suites={total['suites']} tests={total['tests']} failures={total['failures']} "
                       f"errors={total['errors']} skipped={total['skipped']}")
    summary.append("")
    summary.append("Test classes whose name or methods contain T_04_ or T_14_ (tests/failures/errors/skipped, time):")
    related = sorted({k for (k, m) in methods if SPEC_ID.search(m)} | {k for k in classes if SPEC_ID.search(k)})
    for klass in related:
        s = classes[klass]
        summary.append(f"  [{s['report']}] {klass}: tests={s['tests']} failures={s['failures']} "
                       f"errors={s['errors']} skipped={s['skipped']} time={s['time']:.1f}s")
    summary.append(f"Related classes: {len(related)}")
    (OUT / "02-test-summary.log").write_text("\n".join(summary) + "\n")
    print("\n".join(summary))
    print()
    print("\n".join(scope_block + audit_block))


if __name__ == "__main__":
    main()
