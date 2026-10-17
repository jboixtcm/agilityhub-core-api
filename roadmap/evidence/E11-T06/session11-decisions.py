#!/usr/bin/env python3
"""Session 11: the triage decisions of the delta's not-killed mutants, joined with session 10's (E11-T06 step 8).

Every not-killed row of `pit/session11/final-mutations.xml` that no session-10 decision names (generated SEPA code aside, which
`session6-triage.py` handles) must match exactly one rule below, and every rule exactly one row. Two rows are session 10's
mutants on lines shifted by E10-T04's edits: their reasons are carried over with the current line references.
Usage: session11-decisions.py DELTA.json JOINED.json
"""
from importlib.machinery import SourceFileLoader
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
TRIAGE = SourceFileLoader('triage', str(HERE / 'session6-triage.py')).load_module()
SESSION10 = ['decisions-payments-refunds.json', 'decisions-payments-other.json', 'decisions-bookings-b0-b3.json',
             'decisions-bookings-b4-b5.json', 'decisions-identity.json']
RULES = [
    ('identity.api.MeController', 'lambda$me$1', '62',
     'reason: equivalent (session 10\'s row at line 61, shifted by E10-T04) — the token subject is the grant\'s impersonated '
     'account (ImpersonationService.java:117), whose membership memberId equals the grant\'s impersonatedMemberId (:125), the '
     'id `current.impersonation().memberId()` carries (:129); MeController\'s `membership` is that account\'s, so the fallback '
     '`census.displayName(membership.memberId())` repeats the lookup that just returned empty; Optional.empty gives the same '
     '`account.name()`.'),
    ('identity.api.MeController', 'support', '88',
     'test: MeControllerSurvivors3Test#T_17_09_R_17_10_theSupportBootstrapSaysWhetherThePlatformAccountHasAPassword'),
    ('identity.application.SupportAccessService', 'lambda$validate$1', '90',
     'test: SupportAccessServiceSurvivors3Test#T_17_09_R_17_10_aSupportTokenWhoseGrantIsGoneIsUnauthenticated'),
    ('identity.application.SupportAccessService', 'lambda$validate$2', '96',
     'test: SupportAccessServiceSurvivors3Test#T_17_09_R_17_10_aSupportTokenWhosePlatformAccountIsGoneIsUnauthenticated'),
    ('identity.api.CurrentUserFilter', 'doFilterInternal', '48',
     'reason: equivalent (session 10\'s row at line 40, shifted by E10-T04) — for an `imp` token SecurityConfiguration\'s '
     'authorities converter (SecurityConfiguration.java:90-92) already yields exactly [ROLE_MEMBER], so the incoming '
     'JwtAuthenticationToken has the same Jwt, name and authorities as the one line 48 installs; removing the call changes '
     'nothing a downstream check reads.'),
    ('identity.application.DemoPlatformAccountsSeeder', 'apply', '45',
     'test: DemoPlatformAccountsSeederSurvivors3Test#T_17_10_R_17_12_anAccountThatIsAlreadyAPlatformAdministratorGetsNoWrite'),
    ('identity.domain.SupportAccessStarted', 'origin', '12',
     'test: SupportAccessEventsSurvivors3Test#T_17_09_R_17_10_aStartedSupportAccessIsAConsoleEventOfItsGrant'),
    ('identity.domain.SupportAccessEnded', 'origin', '15',
     'test: SupportAccessEventsSurvivors3Test#T_17_09_R_17_10_anEndedSupportAccessIsAConsoleEventOfItsGrant'),
    ('identity.application.DemoPlatformAccountsSeeder$AccountSpec', 'toString', '24',
     'test: DemoPlatformAccountsSeederSurvivors3Test#T_17_10_R_17_12_aSeedAccountNeverPrintsItsCredentials'),
]


def main(delta_path, joined_path):
    joined = {}
    for name in SESSION10:
        decided = json.loads((HERE / 'pit/session10' / name).read_text())
        assert not set(decided) & set(joined), f'{name} repeats an identity'
        joined.update(decided)
    delta, used = {}, [0] * len(RULES)
    for row in ET.parse(HERE / 'pit/session11/final-mutations.xml').getroot():
        clazz = row.findtext('mutatedClass')
        key = TRIAGE.identity(row)
        if (row.get('status') == 'KILLED' or key in joined
                or clazz.startswith('com.agilityhub.core.payments.sepa.generated.')):
            continue
        hits = [i for i, (short, method, number, _) in enumerate(RULES) if clazz == 'com.agilityhub.core.' + short
                and row.findtext('mutatedMethod') == method and row.findtext('lineNumber') == number]
        assert len(hits) == 1, f'{len(hits)} rules for {key}'
        used[hits[0]] += 1
        delta[key] = RULES[hits[0]][3]
    assert used == [1] * len(RULES), f'Rules not matching exactly one row: {used}'
    joined.update(delta)
    for path, document in ((delta_path, delta), (joined_path, joined)):
        with Path(path).open('x') as handle:
            json.dump(document, handle, indent=1, ensure_ascii=False)
            handle.write('\n')
    tests = sum(value.startswith('test:') for value in delta.values())
    print(f'{len(delta)} delta decisions: {tests} tests, {len(delta) - tests} reasons; {len(joined)} decisions joined')


if __name__ == '__main__':
    main(*sys.argv[1:3])
