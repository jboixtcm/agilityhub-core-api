"""E7-T03 step 3/10: the member copy of the templated codes now lives in seed/message-templates.{ca,es,en}.json, so its
`notif.N-xx.title|body|sms` keys leave messages_{ca,es,en}.properties. Kept: the staff copy (`notif.N-xx.staff.*`, product
copy of R-11-12), N-02's title/body (the welcome e-mail with the S01 link, rendered by SystemEmailRenderer), the SYSTEM codes
and `notif.N-36.actor`. The engine lower-cases `gender`, so the staff copy selects on `female` (S11 §10).

Run from the repository root: python3 roadmap/evidence/E7-T03/messages_cleanup.py"""
import json
import re

codes = [t['code'] for t in json.load(open('src/main/resources/seed/message-templates.ca.json', encoding='utf-8'))['templates']]
removed = 0
for loc in ['ca', 'es', 'en']:
    path = f'src/main/resources/messages/messages_{loc}.properties'
    lines = open(path, encoding='utf-8').read().split('\n')
    kept = []
    for line in lines:
        m = re.match(r'^notif\.(N-[0-9a-z]+)\.(title|body|sms)=', line)
        if m and m.group(1) in codes and m.group(1) != 'N-02':
            removed += 1
            continue
        if line.startswith('notif.') and '.staff.' in line:
            line = line.replace('{gender, select, FEMALE ', '{gender, select, female ')
        kept.append(line)
    open(path, 'w', encoding='utf-8').write('\n'.join(kept))
print('removed', removed, 'keys')
