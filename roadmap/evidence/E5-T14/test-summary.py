"""E5-T14 test summary: totals of surefire and failsafe plus one line per test class the task adds or changes (AGENTS.md rule 4),
and the new test methods with their result."""
import glob
import xml.etree.ElementTree as ET

CLASSES = (
    "com.agilityhub.core.shared.application.DemoSeedActorTest",
    "com.agilityhub.core.clubs.bookings.application.DemoBookingSeederTest",
    "com.agilityhub.core.arch.SeedAndWriterRulesTest",
    "com.agilityhub.core.arch.ArchitectureTest",
    "com.agilityhub.core.shared.domain.EventCatalogContractTest",
    "com.agilityhub.core.clubs.census.application.DemoPlanningSeedIT",
    "com.agilityhub.core.clubs.bookings.api.WaitlistIT",
    "com.agilityhub.core.clubs.messaging.application.SystemNotificationServiceIT",
    "com.agilityhub.core.configuration.E2ContractIT",
)
METHODS = (
    "E5_T14_", "T_06_28_aReanchoredRunBooksOnlyIntoTheWeeksItGenerated", "T_06_28_reanchorOneWeekLater",
    "R_08_13_anOfferWithNoN15RecipientIsNotMarkedAndGetsNoN46", "R_08_13_R_08_14_anExpiredFifoOfferNeverGetsN46",
    "T_05_18_T_07_17_everyKeyedPublicRouteChecksTheKeyBeforeTheClub", "E5_T09_classCountersAreWrittenOnlyByTheBookingContext",
)
out = ["Command: ./mvnw -q clean verify -> exit=0 (03-clean-verify.log)"]
for kind in ("surefire", "failsafe"):
    totals = [0, 0, 0, 0]
    per = {}
    methods = []
    for path in glob.glob(f"target/{kind}-reports/TEST-*.xml"):
        root = ET.parse(path).getroot()
        values = [int(root.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        per[root.get("name")] = values
        for case in root.iter("testcase"):
            if any(case.get("name", "").startswith(m) for m in METHODS):
                failed = any(child.tag in ("failure", "error", "skipped") for child in case)
                methods.append(f"    {case.get('classname').rsplit('.', 1)[-1]}.{case.get('name')} {'FAILED' if failed else 'passed'}")
    out.append(f"{kind} TOTAL tests={totals[0]} failures={totals[1]} errors={totals[2]} skipped={totals[3]}")
    for name in CLASSES:
        if name in per:
            t, f, e, s = per[name]
            out.append(f"  {name.rsplit('.', 1)[-1]} tests={t} failures={f} errors={e} skipped={s}")
    out.append("  new/changed methods:")
    out.extend(sorted(methods))
out.append("(read from target/surefire-reports and target/failsafe-reports after ./mvnw -q clean verify)")
print("\n".join(out))
