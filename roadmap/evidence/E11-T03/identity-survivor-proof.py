#!/usr/bin/env python3
"""Reproduce five inherited PIT survivors; restore production sources on every exit."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
PASSWORD = ROOT / 'src/main/java/com/agilityhub/core/identity/application/PasswordService.java'
MAGIC = ROOT / 'src/main/java/com/agilityhub/core/identity/application/MagicLinkService.java'
WAITLIST = ROOT / 'src/main/java/com/agilityhub/core/clubs/bookings/domain/WaitlistRules.java'
originals = {path: path.read_bytes() for path in (PASSWORD, MAGIC, WAITLIST)}
methods = ['T_01_09_E11_passwordMinimumCountsCodePointsAndAcceptsTheExactBoundary',
           'T_01_09_E11_currentPasswordAlsoConsumesTheResetSessionsPermission',
           'T_01_09_passwordChangePreservesCurrentFamilyRevokesOtherDevicesAndSendsN26',
           'T_01_08_E11_inactiveMembershipNeverCreatesOrSendsAMagicLink']
try:
    text = PASSWORD.read_text()
    for before, after in [('< settings.integer("auth.passwordMinLength")', '<= settings.integer("auth.passwordMinLength")'),
                          ('sessions.clearPasswordReset(accountId, familyId);', ''),
                          ('sessions.revokeOthers(accountId, familyId, clock.instant());', '')]:
        assert text.count(before) == 1, before
        text = text.replace(before, after)
    PASSWORD.write_text(text)
    text = MAGIC.read_text()
    before = '.filter(m -> m.status() == Membership.Status.ACTIVE)'
    assert text.count(before) == 1
    MAGIC.write_text(text.replace(before, '.filter(m -> true)'))
    text = WAITLIST.read_text()
    before = 'b -> b.reason() == BookingLimits.Reason.DONE'
    assert text.count(before) == 1
    WAITLIST.write_text(text.replace(before, 'b -> true'))
    waitlist_method = 'T_08_08_waitingListLimitsPerClassPerDogWeekAttendedAndAcceptance'
    command = ['./mvnw', '-q', '-Dtest=IdentityCoreIT#' + '+'.join(methods) + ',WaitlistRulesTest#' + waitlist_method, 'test']
    result = subprocess.run(command, cwd=ROOT)
    print('Nested Maven exit:', result.returncode, flush=True)
    assert result.returncode == 1
    suite = ET.parse(ROOT / 'target/surefire-reports/TEST-com.agilityhub.core.identity.api.IdentityCoreIT.xml').getroot()
    assert int(suite.get('errors')) == 0
    failed = {case.get('name') for case in suite.findall('testcase') if case.find('failure') is not None}
    assert failed == set(methods), failed
    for method in methods:
        print('PASS expected assertion failure:', method, flush=True)
    suite = ET.parse(ROOT / 'target/surefire-reports/TEST-com.agilityhub.core.clubs.bookings.domain.WaitlistRulesTest.xml').getroot()
    assert int(suite.get('errors')) == 0 and int(suite.get('failures')) == 1
    assert suite.find('testcase/failure') is not None
    print('PASS expected assertion failure:', waitlist_method, flush=True)
finally:
    for path, data in originals.items():
        path.write_bytes(data)
    assert all(path.read_bytes() == data for path, data in originals.items())
    print('RESTORED all production sources byte-for-byte', flush=True)
