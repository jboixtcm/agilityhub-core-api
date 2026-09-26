import pathlib
import xml.etree.ElementTree as ET

# E5-T25: the totals of the last `./mvnw -q clean verify` (target/surefire-reports and target/failsafe-reports), one line
# per test class this task adds or changes, and the result of each test method it adds or changes. Run from the repository root.
CLASSES = ["S08MemberFlowContractTest", "MemberFlowContractIT", "MemberAggregatesIT", "BookingRulesTest", "E5ContractIT",
           "E5ResponseContractTest", "OpenApiSnapshotTest", "BookingsIT", "WaitlistIT", "TrainingIT", "ErrorCatalogContractTest"]
METHODS = ["S08_07_T_08_15_aBookingCarriesTheBookedDog",
           "R_08_10_T_08_42_aBookingCarriesTheThresholdAndTheInTimeDeadline",
           "S08_07_T_08_19_aWaitlistEntryCarriesTheWaitingDog",
           "S08_03_T_08_12_T_07_16_homeRowsCarryTheRingColourAndTheActivity",
           "S08_07_T_08_27_bookedBySaysWhetherTheReaderMadeTheBooking",
           "R_08_08_T_08_16_theIdempotencyKeyOfABookingIsOneUuidPerBody",
           "R_08_10_T_08_18_bookingNotCancellableIsPublishedAs422Only",
           "S08_07_T_08_15_everyBookingCarriesItsDog",
           "R_08_10_T_08_06_everyBookingCarriesTheThresholdAndTheInTimeDeadline",
           "T_08_42_theInTimeDeadlineIsElapsedTimeAcrossTheDaylightSavingChange",
           "S08_07_T_08_19_everyWaitlistEntryCarriesItsDog",
           "S08_07_T_08_27_bookedBySelfComparesAccountsNotNames",
           "R_08_08_T_08_16_eachBodyTakesItsOwnIdempotencyKey",
           "T_08_12_T_07_16_homeRowsCarryTheRingColourAndTheActivityId",
           "T_08_06_T_08_42_theInTimeDeadlineIsTheLastInstantTheRuleCallsInTime",
           "T_08_47_T_09_24_T_15_29_snapshotPublishesEveryOperationWithTypedResponsesAndListMetadata",
           "T_08_47_T_09_24_T_15_29_formsKeepEveryDocumentedFieldDuringRoundTrip",
           "T_08_16_theSameIdempotencyKeyReplaysTheResponseAlsoAStored409",
           "T_08_18_cancellationInTimeLateAndRejectedWithSeatReleaseAndMinimumAlert"]
lines, per, cases = [], {}, {}
for kind in ("surefire", "failsafe"):
    totals, count = [0, 0, 0, 0], 0
    for report in sorted(pathlib.Path("target/" + kind + "-reports").glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        values = [int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
        totals = [a + b for a, b in zip(totals, values)]
        count += 1
        per[suite.get("name").rsplit(".", 1)[-1]] = (kind, suite.get("name"), values)
        for case in suite.iter("testcase"):
            failed = any(child.tag in ("failure", "error", "skipped") for child in case)
            cases[case.get("name").split("(")[0]] = (case.get("classname").rsplit(".", 1)[-1], "FAIL" if failed else "PASS")
    lines.append("%s totals: tests=%d failures=%d errors=%d skipped=%d (%d classes)" % (kind, *totals, count))
lines.append("Classes this task adds or changes, and the classes its tests pin:")
for name in CLASSES:
    kind, full, values = per.get(name, ("?", name, [0, 0, 0, 0]))
    lines.append("  [%s] %s: tests=%d failures=%d errors=%d skipped=%d" % (kind, full, *values))
lines.append("E5-T25 test methods (added or changed) and the existing ones they rely on:")
for name in METHODS:
    owner, result = cases.get(name, ("?", "MISSING"))
    lines.append("  %s %s#%s" % (result, owner, name))
print("\n".join(lines))
