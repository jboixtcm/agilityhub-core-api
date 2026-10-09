#!/usr/bin/env python3
"""E11-T06: a batch's final report = its full-suite report, with the mutants that a targeted run kills marked KILLED.

Usage: union.py BASE.xml TARGETED.xml OUTPUT.xml
BASE is a batch run with the whole test selection. TARGETED is a run over the package with only the new survivor tests as
targetTests (production code unchanged in between). A mutant is killed if any test kills it, so each
non-killed BASE mutant that TARGETED kills (same identity: class, method, descriptor, line, mutator, description,
instruction indexes and blocks) takes TARGETED's row; every other BASE row stays as it is. TARGETED rows that do not exist
in BASE make the script refuse (the code changed between the runs).
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def identity(mutation):
    return tuple(mutation.findtext(name, '') for name in ('mutatedClass', 'mutatedMethod', 'methodDescription', 'lineNumber',
                                                          'mutator', 'description')) + tuple(
        tuple(node.text for node in mutation.iter(tag)) for tag in ('index', 'block'))


def main():
    base_path, targeted_path, output = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    root = ET.parse(base_path).getroot()
    base = {identity(m): m for m in root}
    targeted = {identity(m): m for m in ET.parse(targeted_path).getroot()}
    # A package-wide targeted run also holds other batches' classes: only the base report's classes are compared, and for
    # those every targeted mutant must exist in the base report (otherwise the code changed between the runs).
    classes = {key[0] for key in base}
    targeted = {key: m for key, m in targeted.items() if key[0] in classes}
    unknown = targeted.keys() - base.keys()
    assert not unknown, f'{len(unknown)} targeted mutants of the base classes are not in the base report'
    changed = 0
    for position, mutation in enumerate(list(root)):
        other = targeted.get(identity(mutation))
        if mutation.get('status') != 'KILLED' and other is not None and other.get('status') == 'KILLED':
            root.remove(mutation)
            root.insert(position, other)
            changed += 1
    ET.ElementTree(root).write(output, encoding='UTF-8', xml_declaration=True)
    print(f'{output.name}: {len(root)} mutations, {changed} not-killed rows of {base_path.name} killed by {targeted_path.name}')


if __name__ == '__main__':
    main()
