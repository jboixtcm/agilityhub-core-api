#!/usr/bin/env python3
"""E11-T12 spec-test traceability (every spec id), widened from E3-T07's trace.py.

Reads the JUnit XML reports of the last build (target/surefire-reports and
target/failsafe-reports), the spec ids declared in docs/specs/S01…S18 and the
JaCoCo CSV of `./mvnw -q clean verify`, and writes, next to this script:

- trace-tests.csv     one row per (specId, test method): specId, class, method, result
- trace-summary.md    per spec S01…S18: ids declared, ids with at least one test method,
                      whether every one of them passes, the missing ids (and whether the
                      spec assigns them to the web or a smoke); S14 §11 one line per id
- 02-test-summary.log totals of each report directory, the JaCoCo gate, the ArchitectureTest
                      rules, and one line per test class whose name or methods contain
                      T_14_ or INC_

A parameterized method (several <testcase> invocations) is one method; its result is PASS
only when every invocation passes. A method named T_04_12_T_04_13_... gives one row per id.
Run from the repository root: python3 roadmap/evidence/E11-T12/trace.py
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
JACOCO = ROOT / "target/site/jacoco/jacoco.csv"
SPECS = ROOT / "docs/specs"
SPEC_ID = re.compile(r"(?<![0-9A-Za-z])T_(\d{2})_(\d{2})(?!\d)")
RELATED = re.compile(r"T_14_|INC_")
IN_SCOPE = [f"{n:02d}" for n in range(1, 19)]          # S01 … S18
# The thresholds of pom.xml (jacoco check, PACKAGE rules) and PLA_BACKEND §9.
JACOCO_RULES = [("domain+application", re.compile(r"\.(domain|application)(\.|$)"), 0.85, 0.80),
                ("api", re.compile(r"\.api(\.|$)"), 0.70, None)]
WEB = re.compile(r"\b(front|Playwright|E2E|component|PWA|web)\b", re.IGNORECASE)
SMOKE = re.compile(r"smoke", re.IGNORECASE)


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


def declared_ids():
    """spec number -> OrderedDict(id -> defining line) from docs/specs/Snn-*.md."""
    specs = OrderedDict()
    for number in IN_SCOPE:
        files = sorted(SPECS.glob(f"S{number}-*.md"))
        ids = OrderedDict()
        for path in files:
            pattern = re.compile(rf"(?<![0-9A-Za-z])T-{number}-(\d{{2}})(?!\d)")
            lines = path.read_text().splitlines()
            heading = ""
            for line in lines:
                # The bullet-style §11 groups its ids under bold group lines («**Front (component / E2E)**»).
                if re.match(r"^\s*(#+ .*|\*\*[^*].*\*\*:?)\s*$", line):
                    heading = line.strip().strip("#* :")
                for match in pattern.finditer(line):
                    spec = f"T-{number}-{match[1]}"
                    defines = re.match(rf"\s*(\|\s*|-\s*|\*\s*)T-{number}-{match[1]}\b", line)
                    if spec not in ids or (defines and not ids[spec][2]):
                        ids[spec] = (line.strip(), heading, bool(defines))
        specs[number] = OrderedDict(sorted((k, v[:2]) for k, v in ids.items()))
    return specs


def assignment(defined):
    """Whether the spec gives the id to the web, a smoke or another tool (its table group or its bold group line)."""
    line, heading = defined if defined else ("", "")
    cells = [c.strip() for c in line.strip("|").split("|")] if line.startswith("|") else []
    inline = re.match(r"\*\*([^*]+)\*\*\s*[—:-]", line)          # «**Front** — T-12-25 …»
    group = cells[1] if len(cells) > 1 else (inline[1] if inline else heading)
    notes = []
    head = re.sub(r"^[-*|\s]*T-\d{2}-\d{2}\s*(\([^)]*\))?\s*", "", line)[:40]
    if WEB.search(group):
        notes.append(f"web (spec group «{group}»)")
    elif re.match(r"(front\b|`packages/ui`)", head, re.IGNORECASE):
        notes.append(f"web (spec line starts «{head[:20]}»)")
    if re.match(r"(inclosos a|cobert per)\b", head):
        notes.append(f"covered by other ids per the spec («{head[:30]}…»)")
    if re.search(r"Vitest|course-core", group):
        notes.append(f"course-core package (spec group «{group}»)")
    if re.search(r"\bk6\b", line):
        notes.append("k6 load test (spec line names k6)")
    if SMOKE.search(line):
        notes.append("smoke (named in the spec line)")
    return "; ".join(notes)


def jacoco_gate():
    if not JACOCO.exists():
        return ["JaCoCo: target/site/jacoco/jacoco.csv missing"]
    packages = defaultdict(lambda: [0, 0, 0, 0])   # line missed, line covered, branch missed, branch covered
    with JACOCO.open() as handle:
        for row in csv.DictReader(handle):
            p = packages[row["PACKAGE"]]
            p[0] += int(row["LINE_MISSED"]); p[1] += int(row["LINE_COVERED"])
            p[2] += int(row["BRANCH_MISSED"]); p[3] += int(row["BRANCH_COVERED"])
    out = []
    for name, rule, line_min, branch_min in JACOCO_RULES:
        checked, below, lowest_line, lowest_branch = 0, [], (2.0, ""), (2.0, "")
        for package, (lm, lc, bm, bc) in sorted(packages.items()):
            if not rule.search(package):
                continue
            checked += 1
            line = lc / (lm + lc) if lm + lc else 1.0
            branch = bc / (bm + bc) if bm + bc else 1.0
            lowest_line = min(lowest_line, (line, package))
            lowest_branch = min(lowest_branch, (branch, package))
            if line < line_min or (branch_min is not None and branch < branch_min):
                below.append(f"{package} line={line:.3f} branch={branch:.3f}")
        text = (f"JaCoCo {name}: {checked} packages, line >= {line_min:.2f}"
                + (f", branch >= {branch_min:.2f}" if branch_min is not None else "")
                + f"; lowest line {lowest_line[0]:.3f} ({lowest_line[1]})")
        if branch_min is not None:
            text += f", lowest branch {lowest_branch[0]:.3f} ({lowest_branch[1]})"
        text += f"; below threshold: {', '.join(below) or 'none'}"
        out.append(text)
    return out


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
                # Some reports carry a short `classname` (ArchitectureTest); the suite name is always qualified.
                klass, method = suite.get("name") or case.get("classname", ""), method_name(case.get("name", ""))
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
        result = next((r for r in ("ERROR", "FAIL", "SKIPPED") if r in results), "PASS")
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

    specs = declared_ids()
    lines = ["# E11-T12 · spec-test traceability (S01…S18, every spec id)", "",
             "Generated by `python3 roadmap/evidence/E11-T12/trace.py` from the JUnit XML of "
             "`./mvnw -q clean verify` (surefire = unit, failsafe = integration) and the ids declared in "
             "`docs/specs/S01…S18`.",
             "«with tests» = at least one test method carries the id; «all pass» = every such method passes "
             "(a parameterized method counts once). «missing» = no test method in this repository carries the id; "
             "the note says whether the spec's line gives it to the web (front/E2E/Playwright) or to a smoke.", "",
             "| Spec | Ids declared | With tests | All pass | Missing |", "|---|---|---|---|---|"]
    missing_detail = OrderedDict()
    grand = [0, 0, 0]
    for number, ids in specs.items():
        tested = [s for s in ids if by_id.get(s)]
        failing = [s for s in tested if any(r["result"] != "PASS" for r in by_id[s])]
        missing = [s for s in ids if not by_id.get(s)]
        missing_detail[number] = [(s, assignment(ids[s])) for s in missing]
        grand[0] += len(ids); grand[1] += len(tested); grand[2] += len(failing)
        lines.append(f"| S{number} | {len(ids)} | {len(tested)} | "
                     f"{'yes' if not failing else 'NO: ' + ', '.join(failing)} | "
                     f"{', '.join(missing) or '—'} |")
    lines += ["", f"Totals: {grand[0]} ids declared · {grand[1]} with tests · {grand[2]} with a non-passing method.", "",
              "## Missing ids and the spec's assignment", ""]
    for number, missing in missing_detail.items():
        for spec, note in missing:
            lines.append(f"- {spec}: {note or 'api (no web/smoke wording in the spec line)'}")
    lines += ["", "## S14 §11 (T-14-01 … T-14-30)", "",
              "| Spec id | Methods | Invocations | Result | Classes | Note |", "|---|---|---|---|---|---|"]
    for n in range(1, 31):
        spec = f"T-14-{n:02d}"
        found = by_id.get(spec, [])
        note = assignment(specs["14"].get(spec, ""))
        if not found:
            lines.append(f"| {spec} | 0 | 0 | missing | — | {note or 'api'} |")
            continue
        status = "pass" if all(r["result"] == "PASS" for r in found) else \
            "FAIL (" + ", ".join(sorted({r["result"] for r in found if r["result"] != "PASS"})) + ")"
        names = sorted({r["klass"].rsplit(".", 1)[-1] for r in found})
        lines.append(f"| {spec} | {len(found)} | {sum(r['invocations'] for r in found)} | {status} | "
                     f"{', '.join(names)} | {note} |")
    failing_rows = [r for r in rows if r["result"] != "PASS"]
    declared = {s for ids in specs.values() for s in ids}
    undeclared = sorted(set(by_id) - declared)
    lines += ["", f"Rows in trace-tests.csv: {len(rows)} · distinct ids in tests: {len(by_id)} · "
              f"non-passing rows: {len(failing_rows)}"]
    for row in failing_rows:
        lines.append(f"- {row['specId']} {row['klass']}#{row['method']}: {row['result']} — "
                     f"{row['messages'][0] if row['messages'] else '(no message)'}")
    lines += ["", "Ids in test names that S01…S18 do not declare (informative): "
              + (", ".join(f"{s} ({len(by_id[s])})" for s in undeclared) or "none")]
    (OUT / "trace-summary.md").write_text("\n".join(lines) + "\n")

    summary = []
    for report, total in totals.items():
        summary.append(f"{report}: suites={total['suites']} tests={total['tests']} failures={total['failures']} "
                       f"errors={total['errors']} skipped={total['skipped']}")
    summary.append("")
    summary += jacoco_gate()
    summary.append("")
    arch = [(k, m, e["results"]) for (k, m), e in methods.items() if ".arch." in k]
    summary.append(f"Architecture rules (com.agilityhub.core.arch.*): {len(arch)} methods in "
                   f"{len({k for k, _, _ in arch})} classes, {sum(all(r == 'PASS' for r in res) for _, _, res in arch)} pass")
    for klass, method, results in arch:
        summary.append(f"  {klass.rsplit('.', 1)[-1]}#{method}: "
                       f"{'PASS' if all(r == 'PASS' for r in results) else ','.join(sorted(set(results)))}")
    summary.append("")
    summary.append("Test classes whose name or methods contain T_14_ or INC_ (tests/failures/errors/skipped, time):")
    related = sorted({k for (k, m) in methods if RELATED.search(m)} | {k for k in classes if RELATED.search(k)})
    for klass in related:
        s = classes[klass]
        summary.append(f"  [{s['report']}] {klass}: tests={s['tests']} failures={s['failures']} "
                       f"errors={s['errors']} skipped={s['skipped']} time={s['time']:.1f}s")
    summary.append(f"Related classes: {len(related)}")
    (OUT / "02-test-summary.log").write_text("\n".join(summary) + "\n")
    print("\n".join(summary[:len(totals) + 1 + len(JACOCO_RULES) + 2]))
    print(f"Related classes (T_14_ or INC_): {len(related)}")
    print()
    print("\n".join(lines[5:7 + 2 + len(specs)]))
    print(f"Rows in trace-tests.csv: {len(rows)} · non-passing rows: {len(failing_rows)}")


if __name__ == "__main__":
    main()
