#!/usr/bin/env python3
"""Merge the current full-package unit pass and its package integration supplements.

Keep every generated mutant, including uncovered generated classes. Never promote a
zero-test cached result to a new kill. A supplement must contain exactly the unit
pass's mutation set for its whole package; changed code or incomplete reports fail.
"""
import argparse
from collections import Counter
from importlib.machinery import SourceFileLoader
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
gate = SourceFileLoader('gate', str(ROOT / 'bin/mutation-gate')).load_module()
identity = SourceFileLoader('union', str(HERE / 'union.py')).load_module().identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unit', type=Path, required=True)
    parser.add_argument('--supplement', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Output exists; use a new artifact name')
    root = ET.parse(args.unit).getroot()
    base = {identity(m): m for m in gate.merge([args.unit])}
    assert all(int(m.get('numberOfTestsRun', '0')) > 0 for m in base.values()
               if m.get('status') == 'KILLED'), 'The unit pass must witness every credited kill'
    assert any(key[0].startswith('com.agilityhub.core.payments.sepa.generated.') for key in base), 'Missing generated SEPA mutations'
    packages, promoted, unwitnessed, covered_survivors = set(), 0, 0, 0
    for report in args.supplement:
        other = {identity(m): m for m in gate.merge([report])}
        found = {name for name, prefix in gate.PACKAGES.items() if any(key[0].startswith(prefix) for key in other)}
        assert len(found) == 1, 'One full package per supplement'
        package = found.pop()
        assert package not in packages, 'Duplicate package supplement'
        packages.add(package)
        expected = {key for key in base if key[0].startswith(gate.PACKAGES[package])}
        assert set(other) == expected, f'{package}: incomplete or changed mutation set'
        for key, mutation in other.items():
            if base[key].get('status') != 'KILLED' and mutation.get('status') == 'KILLED':
                if int(mutation.get('numberOfTestsRun', '0')) == 0:
                    unwitnessed += 1
                    continue
                base[key] = mutation
                promoted += 1
            elif (base[key].get('status') == 'NO_COVERAGE' and mutation.get('status') == 'SURVIVED'
                  and int(mutation.get('numberOfTestsRun', '0')) > 0):
                # Integration coverage changes the triage, but earns no assertion kill.
                base[key] = mutation
                covered_survivors += 1
        print(f'{package}: {len(expected)} mutation identities match the clean unit pass')
    assert packages == set(gate.PACKAGES), 'All three package supplements are required'
    for index, mutation in enumerate(list(root)):
        replacement = base[identity(mutation)]
        if replacement is not mutation:
            root.remove(mutation)
            root.insert(index, replacement)
    ET.ElementTree(root).write(args.output, encoding='UTF-8', xml_declaration=True)
    print(f'{len(root)} generated mutants retained; {promoted} additional witnessed kills; {unwitnessed} unwitnessed promotions refused')
    print(f'{covered_survivors} previously uncovered mutants were exercised and survived')
    print(dict(Counter(m.get('status') for m in root)))


if __name__ == '__main__':
    main()
