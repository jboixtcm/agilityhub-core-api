#!/usr/bin/env python3
"""E11-T06: split one PIT report that covers several batches into one report per batch (by `batches.py`'s partition).

Usage: split-batches.py REPORT.xml SUFFIX — writes `batchN-SUFFIX.xml` next to this script for every batch that owns at
least one mutation of REPORT (e.g. SUFFIX `mutations` → `batch13-mutations.xml`); refuses a mutation no batch owns.
"""
from importlib.machinery import SourceFileLoader
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent


def main():
    report, suffix = Path(sys.argv[1]), sys.argv[2]
    partition = SourceFileLoader('batches', str(HERE / 'batches.py')).load_module()
    selected = partition.batches()
    source = ET.parse(report).getroot()
    groups = {}
    for mutation in source:
        owners = partition.owners(mutation.findtext('mutatedClass').split('$')[0], selected)
        assert len(owners) == 1, f"{mutation.findtext('mutatedClass')}: owners {owners}"
        groups.setdefault(owners[0], []).append(mutation)
    for batch, mutations in sorted(groups.items()):
        root = ET.Element(source.tag, source.attrib)
        root.extend(mutations)
        output = HERE / f'{batch}-{suffix}.xml'
        assert not output.exists(), f'{output.name} exists'
        ET.ElementTree(root).write(output, encoding='UTF-8', xml_declaration=True)
        print(f'{output.name}: {len(mutations)} mutations')


if __name__ == '__main__':
    main()
