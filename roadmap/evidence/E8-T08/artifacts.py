from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

base = Path('roadmap/evidence/E8-T08')
golden = Path('src/test/resources/sepa/remesa-2026-09.golden.xml')
actual = Path('target/sepa/remesa-2026-09.actual.xml')
assert golden.read_bytes() == actual.read_bytes()
digest = hashlib.sha256(golden.read_bytes()).hexdigest()
(base / 'golden.sha256').write_text(digest + '  ' + str(golden) + '\n')
print('Golden and actual XML: identical; SHA-256 ' + digest[:8] + '...[truncated] (full digest in golden.sha256).')

ns = {'p': 'urn:iso:std:iso:20022:tech:xsd:pain.008.001.02'}
debits = ET.fromstring(golden.read_bytes()).findall('.//p:DrctDbtTxInf', ns)
expected = {207: 'Mia Mas Example', 208: 'Eva Puig Example', 211: 'Laura Serra Example', 212: 'Teresa Torres Example'}
accounts = set()
for debit in debits:
    member = int(debit.findtext('p:DrctDbtTx/p:MndtRltdInf/p:MndtId', namespaces=ns).split('-')[-2])
    iban = debit.findtext('p:DbtrAcct/p:Id/p:IBAN', namespaces=ns)
    name = debit.findtext('p:Dbtr/p:Nm', namespaces=ns)
    assert iban[4:] == f'{member:020d}' and name == expected.pop(member)
    assert int(iban[4:] + '1428' + iban[2:4]) % 97 == 1
    accounts.add(iban)
assert not expected and len(debits) == len(accounts) == 4
print('Four distinct fictional debtors: correct member/account/holder mappings and mod-97 check digits.')

kpis = json.loads(Path('target/e8-t08/simulation-kpis.json').read_text())
assert kpis['collectionDate'] == '2026-09-01' and kpis['count'] == 8
(base / 'simulation-kpis.json').write_text(json.dumps(kpis, indent=2) + '\n')
print('Simulation KPIs saved: count=8, collectionDate=2026-09-01.')
