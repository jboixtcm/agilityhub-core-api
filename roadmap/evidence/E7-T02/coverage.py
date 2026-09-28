"""E7-T02: line and branch coverage of every package under the jacoco rules (application/domain >= 85 % lines and 80 %
branches, api >= 70 % lines), the ones below them first, the messaging packages, and the classes this task adds or
changes; read from target/site/jacoco/jacoco.csv of the last clean verify."""
import csv

CLASSES = ["NotificationEngine", "NotificationDispatcher", "RecipientResolver", "TemplateRenderer", "VariableFormatter", "TemplateProvider",
           "UnsubscribeTokens", "NotificationEmailRenderer", "NotificationEventHandler", "ChannelResolver", "SmsText", "RetryPolicy", "DogNameArticle",
           "TwilioSmsSender", "WebPushSender", "AllowListSmsSender", "FakeSmsSender", "FakePushSender", "LogSmsSender", "SendGridWebhookService",
           "EmailUnsubscribeService", "MessagingNotificationFacts", "NotificationPreferences", "SystemNotificationService", "NotificationFacts",
           "NotificationTrigger", "NotificationSubject", "MessagingPortDefaults", "MemberContact", "ClubSmsUsage", "ClubFormats",
           "MessagingCensusDirectory", "CensusNotificationFacts", "SchedulingNotificationFacts", "BookingNotificationFacts", "TrainingNotificationFacts",
           "ActivityNotificationFacts", "FollowupNotificationFacts", "CommonNotificationFacts",
           # round 2
           "NotificationLinks", "EmailSuppression", "NotificationRepository", "LegacyNotificationRows", "MigrateNotificationsCommand", "ClubEmailSettings"]
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
    ruled = "application" in package.split(".") or "domain" in package.split(".")
    api = "api" in package.split(".")
    if not (ruled or api):
        continue
    ok = (pct(lc, lm) >= 85 and pct(bc, bm) >= 80) if ruled else pct(lc, lm) >= 70
    rows.append((ok, package, pct(lc, lm), pct(bc, bm), lm, bm))
print("Packages under a jacoco rule (application/domain: 85 %% lines, 80 %% branches; api: 70 %% lines): %d, below the rule: %d"
      % (len(rows), sum(1 for r in rows if not r[0])))
for ok, package, lines, branches, lm, bm in sorted(rows, key=lambda r: (r[0], r[1])):
    if not ok or package.startswith(("clubs.messaging", "platform.application", "clubs.census.application", "clubs.bookings.application",
                                     "clubs.scheduling.application", "clubs.training.application")):
        print("  %-4s %-44s lines %5.1f%% (%d missed)  branches %5.1f%% (%d missed)" % ("OK" if ok else "LOW", package, lines, lm, branches, bm))
print("Classes this task adds or changes:")
for name in sorted(classes):
    lc, lm, bc, bm = classes[name]
    print("  %-62s lines %5.1f%% (%d missed)  branches %5.1f%% (%d missed)" % (name, pct(lc, lm), lm, pct(bc, bm), bm))
