#!/usr/bin/env python3
"""List every non-killed current mutant; refuse a final table with unreviewed rows.

Decisions name exact mutation identities. Generated JAXB code stays in the full
denominator, including its unexercised optional factories and accessors.
"""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
FIELDS = ('mutatedClass', 'mutatedMethod', 'methodDescription', 'lineNumber', 'mutator', 'description')


def identity(row):
    return '|'.join([row.findtext(key, '') for key in FIELDS] +
                    [','.join(node.text or '' for node in row.iter(tag)) for tag in ('index', 'block')])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--xml', type=Path, action='append', required=True)
    parser.add_argument('--decisions', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    decisions = json.loads(args.decisions.read_text())
    rows, seen, missing = [], set(), []
    for path in args.xml:
        for row in ET.parse(path).getroot():
            key = identity(row)
            assert key not in seen, 'Duplicate mutation identity'
            seen.add(key)
            if row.get('status') == 'KILLED':
                assert int(row.get('numberOfTestsRun', '0')) > 0, 'Unwitnessed kill'
                continue
            reason = decisions.get(key)
            if (reason is None and row.get('status') == 'NO_COVERAGE'
                    and row.findtext('mutatedClass', '').startswith('com.agilityhub.core.payments.sepa.generated.')):
                reason = ('Generated JAXB optional factory/accessor not exercised by the supported pain.008 export fixtures; '
                          'no hand-written business rule here. Retained as uncovered in the full denominator.')
            if not reason:
                missing.append(key)
                reason = 'UNREVIEWED'
            rows.append({'identity': key, 'status': row.get('status'), 'reason': reason})
    with args.output.open('x') as output:
        json.dump(rows, output, indent=2)
        output.write('\n')
    print(f'{len(seen)} distinct current mutation identities; {len(rows)} non-killed rows; {len(missing)} unreviewed.')
    for key in missing:
        print('UNREVIEWED ' + key)
    return 1 if missing else 0


if __name__ == '__main__':
    raise SystemExit(main())
