python3 -c 'from datetime import datetime, timezone
from pathlib import Path
print('"'"'UTC:'"'"', datetime.now(timezone.utc).isoformat())
for name in ('"'"'/tmp/agilityhub-heavy.lock/pid'"'"', '"'"'/tmp/agilityhub-heavy.lock/what'"'"', '"'"'target/pit-reports/mutations.xml'"'"'):
    path = Path(name)
    if not path.exists():
        print(name + '"'"': absent'"'"')
        continue
    info = path.stat()
    print(f'"'"'{name}: bytes={info.st_size}, modifiedUTC={datetime.fromtimestamp(info.st_mtime, timezone.utc).isoformat()}'"'"')
    if path.name != '"'"'mutations.xml'"'"':
        print(path.read_text().strip())
'
