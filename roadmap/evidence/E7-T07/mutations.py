#!/usr/bin/env python3
"""E7-T07: the "before the fix" mutations. Each one reverts one fix (steps 1-2) or plants one defect the old matrix let through
(step 3), marked E7T07-MUTATION. The originals are saved in target/e7t07-mutation-backup.

    python3 roadmap/evidence/E7-T07/mutations.py apply A [B …]   # apply those mutations
    python3 roadmap/evidence/E7-T07/mutations.py old-it          # put the pre-E7-T07 NotificationActionsIT (git HEAD) in place
    python3 roadmap/evidence/E7-T07/mutations.py restore         # put every saved original back

A  Step 1: bin/e7-smoke's feed check as before, SMS required in the card whatever the delivery.
B  Step 2: the send's @Audited has no `details` expression (the entry has neither details nor changes).
C  Step 2: ANNOUNCEMENT_SENT is not an «event» action (an entry without changes is discarded).
D  Step 2: the aspect ignores the `details` expression.
E  Step 3: every clock time is written «18.50» instead of «18:50» (the old matrix never read a time).
F  Step 3: N-32b also goes to every member the activity admits (the old matrix only looked at Pere's).
G  Step 3: N-36 always says the club booked the class, also when it cancelled it (the old matrix's two N-36 lines were equal).
"""
import pathlib
import shutil
import subprocess
import sys

M = "/* E7T07-MUTATION */ "
BACKUP = pathlib.Path("target/e7t07-mutation-backup")
MAIN = "src/main/java/com/agilityhub/core/"
IT = "src/test/java/com/agilityhub/core/clubs/bookings/api/NotificationActionsIT.java"
MUTATIONS = {
    "A": [("bin/e7-smoke",
           '        problem = feed_card_problem(card, deliveries, self.real_sms)\n        self.require(problem is None, f"The N-08a card: {problem}")\n',
           '        # E7T07-MUTATION\n        self.require("SMS" in card["channels"] and card["action"]["type"] == "CHANGE_CLASS", '
           'f"The card: channels {card[\'channels\']}, action {card[\'action\']}")\n')],
    "B": [(MAIN + "clubs/messaging/application/AnnouncementService.java",
           'entity = "#result.templateId()", details = "#result.details()")',
           'entity = "#result.templateId()") ' + M)],
    "C": [(MAIN + "platform/application/audit/AuditWriter.java",
           "AuditAction.DATA_EXPORTED,\n            AuditAction.ANNOUNCEMENT_SENT);",
           "AuditAction.DATA_EXPORTED " + M + ");")],
    "D": [(MAIN + "platform/application/audit/AuditedAspect.java",
           "details(evaluate(audited.details(), context)));",
           M + "null);")],
    "E": [(MAIN + "platform/application/ClubFormats.java",
           'public String formatClock(LocalTime time) { return time.format(DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)); }',
           'public String formatClock(LocalTime time) { return time.format(DateTimeFormatter.ofPattern(' + M + '"H.mm", Locale.ROOT)); }')],
    "F": [(MAIN + "clubs/activities/application/ActivityNotificationFacts.java",
           'members.add(trigger.text("memberId"));',
           'members.add(trigger.text("memberId")); ' + M + "members.addAll(audience.admittedMemberIds(a));")],
    "G": [(MAIN + "clubs/bookings/application/BookingNotificationFacts.java",
           '.value("change", "BookingCreated".equals(trigger.type()) ? "BOOKED" : "CANCELLED")',
           '.value("change", ' + M + '"BOOKED")')],
}


def save(path):
    BACKUP.mkdir(parents=True, exist_ok=True)
    saved = BACKUP / pathlib.Path(path).name
    if not saved.exists():
        shutil.copy(path, saved)


def apply(keys):
    for key in keys:
        for path, original, mutated in MUTATIONS[key]:
            file = pathlib.Path(path)
            save(path)
            text = file.read_text()
            if text.count(original) != 1:
                raise SystemExit(f"{key}: expected the fixed text once in {path}")
            file.write_text(text.replace(original, mutated))
            print(f"applied {key}: {path}")


def old_it():
    save(IT)
    old = subprocess.run(["git", "show", "HEAD:" + IT], check=True, capture_output=True, text=True).stdout
    pathlib.Path(IT).write_text(old)
    print(f"old NotificationActionsIT (git HEAD) in place: {IT}")


def restore():
    targets = [path for mutations in MUTATIONS.values() for path, _, _ in mutations] + [IT]
    for path in dict.fromkeys(targets):
        saved = BACKUP / pathlib.Path(path).name
        if saved.exists():
            shutil.copy(saved, path)
            saved.unlink()
            print(f"restored {path}")


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "apply":
        apply(sys.argv[2:])
    elif command == "old-it":
        old_it()
    elif command == "restore":
        restore()
    else:
        raise SystemExit(__doc__)
