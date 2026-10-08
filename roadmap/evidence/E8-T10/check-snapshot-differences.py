#!/usr/bin/env python3
"""Prove that the dry-run snapshot failures differ only in maintenance metadata, before adjusting the helper."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path
p=Path('target/failsafe-reports/TEST-com.agilityhub.core.payments.api.E8ScheduledProcessesIT.xml')
found=0
for case in ET.parse(p).getroot().findall('testcase'):
    failure=case.find('failure')
    if failure is None:
        continue
    message=failure.get('message')
    expected, actual=message.split(' but was:',1) if ' but was:' in message else message.split('but was:',1)
    expected=expected.split('expected:',1)[1]
    remove=lambda value: re.sub(r',? "tenant_write_counters"=\[[^\]]*\],?', '', value).strip()
    assert remove(expected)==remove(actual), case.get('name') + ': another difference remains'
    print(case.get('name') + ': all business rows identical; only the maintenance counter differs')
    found+=1
assert found==2
