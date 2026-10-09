#!/usr/bin/env python3
"""E11-T06: compare two PIT reports of one batch; print every mutant whose status changed, and every final non-kill.

Usage: compare.py FIRST.xml FINAL.xml
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def key(mutation):
    return tuple(mutation.findtext(name, '') for name in ('mutatedClass', 'mutatedMethod', 'methodDescription', 'lineNumber',
                                                          'mutator', 'description')) + tuple(
        tuple(node.text for node in mutation.iter(tag)) for tag in ('index', 'block'))


def load(path):
    return {key(mutation): mutation for mutation in ET.parse(path).getroot()}


def label(mutation):
    return (f"{mutation.findtext('mutatedClass').rsplit('.', 1)[1]}:{mutation.findtext('lineNumber')} "
            f"{mutation.findtext('mutator').rsplit('.', 1)[1]} idx={','.join(node.text for node in mutation.iter('index'))}")


def main():
    first, final = load(Path(sys.argv[1])), load(Path(sys.argv[2]))
    print(f'first {len(first)} mutations, final {len(final)}; only in first {len(first.keys() - final.keys())}, '
          f'only in final {len(final.keys() - first.keys())}')
    for identity, mutation in final.items():
        before = first.get(identity)
        status = mutation.get('status')
        was = before.get('status') if before is not None else 'NEW'
        if status != 'KILLED' or was != status:
            print(f'{was:>11} -> {status:<11} {label(mutation)}')
            if was == 'KILLED' and status != 'KILLED':
                print(f'{"":>27} first killed by {before.findtext("killingTest")}')


if __name__ == '__main__':
    main()
