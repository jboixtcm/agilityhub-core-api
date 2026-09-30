"""E7-T03 step 3 helper: the first draft of seed/message-templates.{ca,es,en}.json from the product copy that E4-E7 kept in
messages_*.properties (member copy only; the staff copy stays there). `{var}` of a template variable becomes `[[var]]`; ICU
selectors stay ICU. The S11 §8 texts were then written by hand over this draft (see the E7-T03 report).

Run from the repository root: python3 roadmap/evidence/E7-T03/seed_from_messages.py (writes target/e7t03/draft-{locale}.json)."""
import json
import os
import re
import sys

SOURCE = 'src/main/java/com/agilityhub/core/clubs/messaging/domain/NotificationCatalog.java'
src = open(SOURCE, encoding='utf-8').read()
custom = re.findall(r'"([^"]+)"', re.search(r'CUSTOM_VARIABLES = List.of\(([^)]*)\)', src).group(1))
rows = {}
for chunk in src.split('rows.add(code(')[1:]:
    code = re.match(r'"([^"]+)"', chunk).group(1)
    category = re.match(r'"[^"]+", (\w+)', chunk).group(1)
    stmt = chunk.split('.build());')[0]
    found = re.search(r'\.vars\(([^)]*)\)', stmt)
    names = [] if not found else (custom if 'CUSTOM_VARIABLES' in found.group(1) else re.findall(r'"([^"]+)"', found.group(1)))
    rows[code] = dict(category=category, vars=names, audiences=re.findall(r'\.to\((\w+)', stmt), later='.later()' in stmt)


def d9(code):
    row = rows[code]
    out = list(row['vars'])
    if 'MEMBER' in row['audiences']:
        for key in ['member_first_name', 'member_last_names', 'member_name', 'gender', 'dog_name']:
            if key not in out:
                out.append(key)
    if 'member_name' in out and 'member_last_names' not in out:
        out.append('member_last_names')
    if 'dog_name' in out and 'dog_name_article' not in out:
        out.append('dog_name_article')
    if 'club_name' not in out:
        out.append('club_name')
    return out


def convert(text, known, leftovers):
    def repl(match):
        name = match.group(1)
        if name in known:
            return '[[' + name + ']]'
        leftovers.add(name)
        return match.group(0)
    return re.sub(r'\{([a-z_A-Z]+)\}', repl, text)


eligible = [c for c, r in rows.items() if r['category'] != 'SYSTEM' and not r['later']]
print(len(eligible), 'eligible codes', file=sys.stderr)
os.makedirs('target/e7t03', exist_ok=True)
for loc in ['ca', 'es', 'en']:
    keys = {}
    for line in open(f'src/main/resources/messages/messages_{loc}.properties', encoding='utf-8'):
        m = re.match(r'^notif\.(N-[0-9a-z]+)\.(title|body|sms)=(.*)$', line.rstrip('\n'))
        if m:
            keys[(m.group(1), m.group(2))] = m.group(3)
    out = []
    for code in eligible:
        known = set(d9(code))
        left = set()
        entry = {'code': code, 'title': convert(keys[(code, 'title')], known, left), 'body': convert(keys[(code, 'body')], known, left)}
        if (code, 'sms') in keys:
            entry['smsBody'] = convert(keys[(code, 'sms')], known, left)
        out.append(entry)
        if left and loc == 'ca':
            print(code, 'ICU args kept:', sorted(left), file=sys.stderr)
    with open(f'target/e7t03/draft-{loc}.json', 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
