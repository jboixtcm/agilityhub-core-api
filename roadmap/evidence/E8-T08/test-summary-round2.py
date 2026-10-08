from pathlib import Path
import xml.etree.ElementTree as ET
# Round 2 (E92): totals of the clean verify recorded in 29-clean-verify.exit, plus the classes this round adds or changes.
assert Path('roadmap/evidence/E8-T08/29-clean-verify.exit').read_text().strip() == '0'
classes={'ArchitectureTest','E8ContractIT','E8ResponseContractTest','CensusRulesTest','SignupCensusCorrectionsIT','BillingFollowupsIT','CensusIT','SignupPaymentMethodsIT'}
print('Clean verify exit: 0 (29-clean-verify.log).')
for lane in ('surefire','failsafe'):
    totals={name:0 for name in ('tests','failures','errors','skipped')}
    rows=[]
    for path in sorted(Path('target/'+lane+'-reports').glob('TEST-*.xml')):
        root=ET.parse(path).getroot()
        counts={name:int(root.get(name,0)) for name in totals}
        for name,count in counts.items(): totals[name]+=count
        if root.get('name','').split('.')[-1] in classes:
            rows.append(root.get('name')+' '+str(counts))
    print(lane+': '+str(totals))
    for row in rows: print(row)
    assert totals['tests']>0 and all(totals[name]==0 for name in ('failures','errors','skipped'))
for case in ET.parse('target/failsafe-reports/TEST-com.agilityhub.core.clubs.census.api.SignupCensusCorrectionsIT.xml').getroot().iter('testcase'):
    if case.get('name').startswith('R_03_07'):
        print('passed: SignupCensusCorrectionsIT.'+case.get('name'))
for case in ET.parse('target/surefire-reports/TEST-com.agilityhub.core.arch.ArchitectureTest.xml').getroot().iter('testcase'):
    if 'E8_T08' in case.get('name'):
        print('passed: ArchitectureTest.'+case.get('name'))
