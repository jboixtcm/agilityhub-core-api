#!/usr/bin/env python3
"""E7-T04 round 3: the "before the fix" mutations. Each one reverts one fix of the round, marked E7T04-R3-MUTATION.

    python3 roadmap/evidence/E7-T04/r3-mutations.py apply A B C D E   # revert those fixes (originals saved in target/)
    python3 roadmap/evidence/E7-T04/r3-mutations.py restore           # put every saved original back

A  OutboxDispatcher runs a consumer's transaction once, without the conflict retry (point 1).
B  D9's save keeps the gender select keys as typed (point 2, the save).
B2 D9's preview of an unsaved draft keeps them as typed (point 2, the preview).
C  The upgrade's actor as round 2 wrote it: the event's actorAccountId and the entry's actorName are the process, no details (point 3).
D  A batch without its frozen copy is skipped silently, without the WARN (nit #1).
E  TemplateProvider accepts no announcements repository (nit #2).
"""
import pathlib
import shutil
import sys

M = "/* E7T04-R3-MUTATION */ "
BACKUP = pathlib.Path("target/r3-mutation-backup")
MAIN = "src/main/java/com/agilityhub/core/"
MUTATIONS = {
    "A": [(MAIN + "shared/application/OutboxDispatcher.java",
           "retries.inTransaction(RETRY_CONTEXT, CONFLICT_ATTEMPTS, transactions, status -> {",
           M + "transactions.executeWithoutResult(status -> {")],
    "B": [(MAIN + "clubs/messaging/application/MessageTemplateService.java",
           "kept.put(locale, TemplateUpgrade.lowerGenderKeys(text.strip()));",
           M + "kept.put(locale, text.strip());")],
    "B2": [(MAIN + "clubs/messaging/application/TemplatePreviewService.java",
            "String title = draft == null ? text(template.title(), locale, config) : TemplateUpgrade.lowerGenderKeys(draft.title());",
            M + "String title = draft == null ? text(template.title(), locale, config) : draft.title();")],
    "C": [(MAIN + "clubs/messaging/application/TemplateUpgrade.java",
           "clock.instant(), payload, null, null,\n                DomainEvent.Origin.SYSTEM));",
           "clock.instant(), payload, " + M + "ACTOR, null,\n                DomainEvent.Origin.SYSTEM));"),
          (MAIN + "platform/application/audit/AuditWriter.java",
           'new AuditActor(null, null, "SYSTEM", null, null, null, null, UUID.randomUUID().toString()), Map.of("job", process));',
           'new AuditActor(null, process, "SYSTEM", null, null, null, null, UUID.randomUUID().toString()), ' + M + "null);")],
    "D": [(MAIN + "clubs/messaging/application/engine/NotificationEngine.java",
           'LOG.warn("Announcement batch without its frozen template; nothing sent batchId={} templateId={}", trigger.text("batchId"), facts.get().templateId());',
           M)],
    "E": [(MAIN + "clubs/messaging/application/engine/TemplateProvider.java",
           'this.announcements = java.util.Objects.requireNonNull(announcements, "the announcements repository is required");',
           M + "this.announcements = announcements;")],
}


def apply(keys):
    BACKUP.mkdir(parents=True, exist_ok=True)
    for key in keys:
        for path, original, mutated in MUTATIONS[key]:
            file = pathlib.Path(path)
            saved = BACKUP / file.name
            if not saved.exists():
                shutil.copy(file, saved)
            text = file.read_text()
            if text.count(original) != 1:
                raise SystemExit(f"{key}: expected the fixed line once in {path}")
            file.write_text(text.replace(original, mutated))
            print(f"applied {key}: {path}")


def restore():
    for saved in sorted(BACKUP.glob("*.java")):
        for key, edits in MUTATIONS.items():
            for path, _, _ in edits:
                if pathlib.Path(path).name == saved.name:
                    shutil.copy(saved, path)
                    print(f"restored {path}")
                    break
            else:
                continue
            break
        saved.unlink()


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "apply":
        apply(sys.argv[2:])
    elif len(sys.argv) == 2 and sys.argv[1] == "restore":
        restore()
    else:
        raise SystemExit(__doc__)
