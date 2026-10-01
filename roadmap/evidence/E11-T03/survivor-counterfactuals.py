#!/usr/bin/env python3
"""Reproduce important observed mutants; always restore domain sources."""
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'src/main/java/com/agilityhub/core'
originals = {}
try:
    changes = {
        'clubs/bookings/application/WaitlistService.java': [('size() + others >= capacity', 'size() - others >= capacity')],
        'identity/application/TokenService.java': [
            ('revoked(session.account().id(), old.familyId(), old.clubId(), "LIMIT");', '// Counterfactual: omitted LIMIT event.'),
            ('revoked(accountId, token.familyId(), token.clubId(), "LOGOUT");', '/* Counterfactual: omitted LOGOUT event. */'),
        ],
    }
    for relative, replacements in changes.items():
        path = BASE / relative
        originals[path] = path.read_text()
        amended = originals[path]
        for old, new in replacements:
            assert old in amended, relative + ': absent target'
            amended = amended.replace(old, new)
        path.write_text(amended)
    command = ['./mvnw', '-q', '-Dit.test=WaitlistIT#T_08_22_R_08_15_theClaimOfADemotedEntryAnswersLikeItsHold,IdentityCoreIT#T_01_10_revokeIsIdempotentAndCannotRevokeAnotherAccount+T_01_06_maximumSessionsRevokesOldestAndDeviceMetadataIsBounded', 'test-compile', 'failsafe:integration-test', 'failsafe:verify']
    print('COUNTERFACTUAL COMMAND ' + ' '.join(command), flush=True)
    result = subprocess.run(command, cwd=ROOT)
    print('COUNTERFACTUAL MAVEN EXIT ' + str(result.returncode), flush=True)
    if result.returncode == 0:
        raise SystemExit('Counterfactual unexpectedly passed')
finally:
    for path, original in originals.items():
        path.write_text(original)
    print('RESTORED all temporarily changed domain sources', flush=True)
