#!/usr/bin/env python3
"""E7-T04: totals of target/surefire-reports and target/failsafe-reports after `./mvnw -q clean verify`, plus one line per
test class the task adds or changes (tests, failures, errors, skipped)."""
from pathlib import Path
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[3]
CLASSES = [
    "com.agilityhub.core.clubs.bookings.api.RemindersJobIT",
    "com.agilityhub.core.clubs.bookings.api.NotificationActionsIT",
    "com.agilityhub.core.clubs.messaging.api.AnnouncementsIT",
    "com.agilityhub.core.clubs.messaging.application.engine.NotificationMatrixTest",
    "com.agilityhub.core.clubs.census.application.DemoScenarioSeedIT",
    "com.agilityhub.core.clubs.messaging.application.engine.TemplateVariableParityIT",
    "com.agilityhub.core.clubs.messaging.application.engine.NotificationEngineIT",
    "com.agilityhub.core.clubs.messaging.application.MessagingApplicationTest",
    "com.agilityhub.core.clubs.messaging.application.ports.MessagingPortsTest",
    "com.agilityhub.core.clubs.messaging.domain.ChannelResolverTest",
    "com.agilityhub.core.configuration.E7ContractIT",
    "com.agilityhub.core.configuration.E7PersistenceIT",
    "com.agilityhub.core.clubs.bookings.api.JobsApiIT",
    "com.agilityhub.core.platform.application.jobs.JobCatalogContractTest",
    "com.agilityhub.core.platform.application.jobs.JobFrameworkIT",
    "com.agilityhub.core.arch.ArchitectureTest",
    "com.agilityhub.core.platform.application.audit.AuditContractTest",
    "com.agilityhub.core.shared.domain.ErrorCatalogContractTest",
    "com.agilityhub.core.shared.domain.EventCatalogContractTest",
    "com.agilityhub.core.clubs.messaging.domain.NotificationCatalogContractTest",
    "com.agilityhub.core.shared.application.MessageParityTest",
]


def suites(folder):
    for path in sorted((ROOT / "target" / folder).glob("TEST-*.xml")):
        root = ElementTree.parse(path).getroot()
        yield root.get("name"), {k: int(root.get(k, "0")) for k in ("tests", "failures", "errors", "skipped")}


def main():
    seen = {}
    for folder in ("surefire-reports", "failsafe-reports"):
        total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        classes = 0
        for name, counts in suites(folder):
            classes += 1
            for key in total:
                total[key] += counts[key]
            seen[name] = (folder, counts)
        print(f"{folder}: {classes} classes, tests={total['tests']} failures={total['failures']} errors={total['errors']} skipped={total['skipped']}")
    for name in CLASSES:
        folder, counts = seen.get(name, ("missing", None))
        print(f"  {name.rsplit('.', 1)[1]} ({folder}): " + ("NOT RUN" if counts is None else
              f"tests={counts['tests']} failures={counts['failures']} errors={counts['errors']} skipped={counts['skipped']}"))


if __name__ == "__main__":
    main()
