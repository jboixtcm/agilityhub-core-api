"""E7-T01 round 2: the jacoco rules (application/domain ≥ 85 % lines and 80 % branches, api ≥ 70 % lines) over every
ruled package — how many pass, the ones below first, the messaging ones always — and the coverage of the classes round 2
changes; read from target/site/jacoco/jacoco.csv of the last clean verify."""
import csv

CLASSES = ["EmailUnsubscribesController", "LegacyNotificationRows", "LegacyNotificationReader", "Notification", "MigrateNotificationsCommand",
           "NotificationCatalog", "NotificationEngine"]
totals, classes = {}, {}
with open("target/site/jacoco/jacoco.csv", encoding="utf-8") as source:
    for row in csv.DictReader(source):
        package = row["PACKAGE"].removeprefix("com.agilityhub.core.")
        counts = [int(row[key]) for key in ("LINE_COVERED", "LINE_MISSED", "BRANCH_COVERED", "BRANCH_MISSED")]
        values = totals.setdefault(package, [0, 0, 0, 0])
        for index in range(4):
            values[index] += counts[index]
        name = row["CLASS"].split(".")[0]
        if name in CLASSES:
            aggregate = classes.setdefault(name + " (" + package + ")", [0, 0, 0, 0])
            for index in range(4):
                aggregate[index] += counts[index]


def pct(covered, missed):
    return 100.0 * covered / (covered + missed) if covered + missed else 100.0


rows = []
for package, (lc, lm, bc, bm) in sorted(totals.items()):
    parts = package.split(".")
    ruled, api = "application" in parts or "domain" in parts, "api" in parts
    if not (ruled or api):
        continue
    ok = (pct(lc, lm) >= 85 and pct(bc, bm) >= 80) if ruled else pct(lc, lm) >= 70
    rows.append((ok, package, pct(lc, lm), pct(bc, bm), lm, bm))
print("Packages under a jacoco rule: %d of %d pass" % (sum(1 for r in rows if r[0]), len(rows)))
for ok, package, lines, branches, lm, bm in sorted(rows, key=lambda r: (r[0], r[1])):
    if not ok or package.startswith("clubs.messaging"):
        print("  %-4s %-44s lines %5.1f%% (%d missed)  branches %5.1f%% (%d missed)" % ("OK" if ok else "LOW", package, lines, lm, branches, bm))
print("Classes round 2 changes (clubs.messaging.persistence has no jacoco rule):")
for name in sorted(classes):
    lc, lm, bc, bm = classes[name]
    print("  %-58s lines %5.1f%% (%d missed)  branches %5.1f%% (%d missed)" % (name, pct(lc, lm), lm, pct(bc, bm), bm))
