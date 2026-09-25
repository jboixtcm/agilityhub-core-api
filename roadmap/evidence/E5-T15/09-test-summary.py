import pathlib
import xml.etree.ElementTree as ET

CHANGED = {
    "surefire": ["SchedulingTransactionsTest", "SchedulingPersistenceTest", "SeedAndWriterRulesTest", "ArchitectureTest", "ArchitectureRulesTest",
                 "RiskReviewQueryTest", "JobViewsTest", "CatalogServiceRetryTest", "E4ResponseContractTest"],
    "failsafe": ["TrainingLanesOffIT", "TrainingIT", "CalendarIT", "RiskReviewJobIT", "JobsApiIT", "CleanupJobIT", "ActivityIT",
                 "CacheInvalidationRaceIT", "WaitlistIT", "E4ContractIT", "OpenApiSnapshotTest"],
}
lines = []
for kind in ("surefire", "failsafe"):
    totals = [0, 0, 0, 0]
    per = {}
    for report in sorted(pathlib.Path("target/" + kind + "-reports").glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        per[suite.get("name").rsplit(".", 1)[-1]] = (suite.get("name"), values)
    lines.append("%s totals: tests=%d failures=%d errors=%d skipped=%d (%d classes)" % (kind, *totals, len(per)))
    for name in CHANGED[kind]:
        full, values = per[name]
        lines.append("  %s: tests=%d failures=%d errors=%d skipped=%d" % (full, *values))
print("\n".join(lines))
