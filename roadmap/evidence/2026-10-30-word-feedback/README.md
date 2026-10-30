# Word feedback 29 October 2026

Authorized on 30 October 2026 after discussion with Jordi. Source: SOFTWARE_CANIC/Proves/20261029-2_Josep.docx.

## Accepted behavior

- Attendance correction by administrators and the class instructor, audited; restore a notified booking only when its seat and accounting can be restored atomically.
- Attendance legend right aligned, state tooltips and explicit saved/change feedback.
- Free training permission assigned manually per dog.
- Improve dashboard total bars and dark timetable separators; fixed-size column checkboxes.
- Add an active dog directly from the existing member record, without creating another member.
- Explicit handlerIsMember flag; preserve the handler's customary name.
- Repair member editing and show validation errors on the relevant fields.
- Translate LEFT/INACTIVE display values; show leave date prominently and original joining date; link reactivation from edit.
- Class default capacity 5; only level A defaults to 6, using club catalog configuration.
- Preserve DRAFT filter in daily calendar, both ring and instructor views.
- Show monthly plan price; plan changes affect the next receipt and close their drawer after saving.
- Training configuration: simultaneous reservations per ring/slot, global default 1 and ring-specific overrides.
- Admin member merge with preview and optimistic versions: retain source records and immutable history, link them from the surviving record, move operational ownership, retire duplicates. No automatic live merge of 2193.

## Validation

In progress. Existing working-tree changes are preserved; baseline diffs saved privately before edits. No deployment claimed.
