#!/usr/bin/env python3
"""E7-T07 step 3: the seed texts (title, body, staff copy) of the codes NotificationActionsIT drives, in ca/es/en, so that
every expected body of the IT is checked against its template and its variables, not copied from an actual run."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CODES = ["N-04", "N-05", "N-06", "N-07", "N-08a", "N-08b", "N-13", "N-15", "N-16", "N-17", "N-19", "N-20", "N-21", "N-22",
         "N-32a", "N-32b", "N-32c", "N-32d", "N-36", "N-47", "N-54"]

for code in CODES:
    for locale in ("ca", "es", "en"):
        data = json.loads((ROOT / "src/main/resources/seed" / f"message-templates.{locale}.json").read_text())
        for template in data["templates"]:
            if template["code"] == code:
                extra = {k: v for k, v in template.items() if k not in ("code", "title", "body", "icon", "color", "matrix", "smsBody")}
                print(f"{code} {locale} | {template['title']} | {template['body']}" + (f" | {extra}" if extra else ""))
