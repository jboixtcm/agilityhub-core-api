#!/usr/bin/env python3
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[3]
files = [ROOT / 'src/main/java/com/agilityhub/core/configuration' / name for name in ['PrivacyLogFormatter.java','SentryPrivacyConfiguration.java']]
originals = {p:p.read_text() for p in files}
try:
    for p,s in originals.items(): p.write_text(s.replace('LogPrivacy.identifier(', 'LogPrivacy.scrub('))
    command = ['./mvnw','-q','-Dtest=LogPrivacyTest#T_14_30_trustedUuidIdsStayByteIdenticalWhileNonIdsAreScrubbed','test']
    print('COUNTERFACTUAL COMMAND ' + ' '.join(command), flush=True)
    result = subprocess.run(command,cwd=ROOT)
    print('COUNTERFACTUAL MAVEN EXIT ' + str(result.returncode),flush=True)
    if result.returncode == 0: raise SystemExit('Counterfactual unexpectedly passed')
finally:
    for p,s in originals.items(): p.write_text(s)
    print('RESTORED UUID identifier handling',flush=True)
