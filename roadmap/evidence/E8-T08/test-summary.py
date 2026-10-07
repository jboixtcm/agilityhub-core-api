from pathlib import Path
import xml.etree.ElementTree as ET
assert Path('roadmap/evidence/E8-T08/12-clean-verify.exit').read_text().strip() == '0'
classes={'ArchitectureTest','E8ContractIT','BillingFollowupsIT','BillingCycleIT','BillingItSupport','RemittancesIT','InvoiceActionsIT','InvoiceNumberingIT','SepaRemittanceWriterTest','SepaDirectDebitsTest','InvoicingRulesTest','CensusRulesTest','ParameterValidatorTest','E8ResponseContractTest'}
print('Clean verify exit: 0 (see corresponding clean-verify log and executor report).')
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
print('BillingItSupport is an abstract fixture; covered by the concrete billing IT classes.')
