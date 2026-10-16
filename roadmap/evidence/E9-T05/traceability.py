"""E9-T05 step 3: every R-16 rule → the S16 §11 tests that cover it → the api's test methods named after them.

Run from the repository root: python3 roadmap/evidence/E9-T05/traceability.py > roadmap/evidence/E9-T05/NN-traceability.log
The rule of each test comes from S16 §11 (`- T-16-NN (R-16-AA/BB, R-16-CC) …`). Two name none: T-16-13 covers R-16-07's optional
N-31, and T-16-14 (the Smarter `.txt` upload, its type and the 512 KB limit) covers R-16-02's api half (the original file kept as
`sourceFileKey`, «Mida màxima 512 KB»; the parser itself is course-core's T-16-01) and R-16-16's file validation. A test method covers every `T_16_NN` its name carries. T-16-01, -02, -04, -15 and -16 are the web's (course-core, course-ui,
the fronts): the api has no test for them by design.
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

SPEC = Path("docs/specs/S16-recorreguts-rings-i-muntatge.md")
WEB = {"T-16-01": "course-core parseSmarter", "T-16-02": "course-core placeInRing", "T-16-04": "course-ui snapshots",
       "T-16-15": "clubs-admin D18 e2e", "T-16-16": "clubs mobile e2e"}
EXTRA = {"T-16-13": ["R-16-07"], "T-16-14": ["R-16-02", "R-16-16"]}

text = SPEC.read_text()
rules = sorted(set(re.findall(r"\*\*(R-16-\d\d)\b", text)))
tests_of = defaultdict(list)
for test, refs in re.findall(r"^- (T-16-\d\d) \(([^)]*)\)", text, re.M):
    numbers = []
    for group in re.findall(r"R-16-([\d/]+)", refs):
        numbers += group.split("/")
    for number in numbers:
        tests_of["R-16-" + number.zfill(2)].append(test)
for test, extra in EXTRA.items():
    for rule in extra:
        tests_of[rule].append(test)

methods = defaultdict(set)
for path in Path("src/test/java").rglob("*.java"):
    for name in re.findall(r"void (T_\w+)\s*\(", path.read_text()):
        for number in re.findall(r"T_16_(\d\d)", name):
            methods["T-16-" + number].add(f"{path.stem}.{name}")

missing = []
print("| Rule | S16 §11 tests | api test methods | classes |")
print("|---|---|---|---|")
for rule in rules:
    tests = sorted(set(tests_of.get(rule, [])))
    api = sorted({m for t in tests for m in methods.get(t, ())})
    web = [t for t in tests if t in WEB]
    classes = sorted({m.split(".")[0] for m in api})
    if not api:
        missing.append(rule)
    shown = ", ".join(t + (" (web)" if t in WEB else "") for t in tests)
    print(f"| {rule} | {shown} | {len(api)} | {', '.join(classes)} |")
print()
for test in sorted(methods):
    print(f"{test}: " + "; ".join(sorted(methods[test])))
print()
print("Rules without an api test method: " + (", ".join(missing) if missing else "none"))
sys.exit(1 if missing else 0)
