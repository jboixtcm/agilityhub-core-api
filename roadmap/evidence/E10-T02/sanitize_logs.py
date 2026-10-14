"""Redact identifiers and fictional contact data without dropping evidence lines."""
from pathlib import Path
import re

root = Path(__file__).resolve().parent
for path in sorted(root.glob('*.log')):
    if not path.with_suffix('.exit').exists():
        continue
    text = path.read_text(errors='replace')
    updated = re.sub(r'(?i)(traceId[=\s"\x27:]+)([0-9a-f-]{32,})', r'\1[truncated]', text)
    updated = re.sub(r'(?i)((?:recipientHash|recipientIdHash)[=\s"\x27:]+)[a-f0-9]{32,}', r'\1[truncated]', updated)
    updated = re.sub(r'(?<![\w.-])[\w.+-]+@(?:[\w-]+\.)+[A-Za-z]{2,}', '[email redacted]', updated)
    if updated != text:
        assert len(updated.splitlines()) == len(text.splitlines())
        path.write_text(updated)
        print(f'{path.name}: identifiers/contact data redacted; every output line retained.')
