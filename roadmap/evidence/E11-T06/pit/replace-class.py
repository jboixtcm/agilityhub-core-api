#!/usr/bin/env python3
"""E11-T06: a batch report whose rows of one class come from a later targeted run of that class only.

Usage: replace-class.py BATCH.xml TARGETED.xml CLASS_PREFIX OUTPUT.xml
Every mutation of BATCH.xml whose mutated class starts with CLASS_PREFIX is dropped and the targeted run's mutations take
their place; the targeted run must contain exactly that class (otherwise the script refuses), and the two sets must have
the same mutation identities (same count, same mutants), so nothing is added or lost.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def identity(mutation):
    return tuple(mutation.findtext(name, '') for name in ('mutatedClass', 'mutatedMethod', 'methodDescription', 'lineNumber',
                                                          'mutator', 'description')) + tuple(
        tuple(node.text for node in mutation.iter(tag)) for tag in ('index', 'block'))


def main():
    batch, targeted, prefix, output = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], Path(sys.argv[4])
    root = ET.parse(batch).getroot()
    replaced = [m for m in root if m.findtext('mutatedClass').startswith(prefix)]
    incoming = list(ET.parse(targeted).getroot())
    assert all(m.findtext('mutatedClass').startswith(prefix) for m in incoming), 'targeted run has other classes'
    assert sorted(map(identity, replaced)) == sorted(map(identity, incoming)), 'different mutants'
    for mutation in replaced:
        root.remove(mutation)
    root.extend(incoming)
    ET.ElementTree(root).write(output, encoding='UTF-8', xml_declaration=True)
    print(f'{output.name}: {len(root)} mutations, {len(replaced)} rows of {prefix} replaced by the targeted run')


if __name__ == '__main__':
    main()
