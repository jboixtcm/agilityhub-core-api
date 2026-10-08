#!/usr/bin/env python3
"""The full-package gate must not count uncovered or timed-out mutants as assertion kills."""
from importlib.machinery import SourceFileLoader
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
gate = SourceFileLoader('gate', str(ROOT / 'bin/mutation-gate')).load_module()


class MutationGateTest(unittest.TestCase):
    def test_E11_T06_uncovered_mutants_stay_in_the_denominator(self):
        rows = ET.Element('mutations')
        for prefix in gate.PACKAGES.values():
            for status in ['KILLED', 'KILLED', 'NO_COVERAGE', 'SURVIVED']:
                row = ET.SubElement(rows, 'mutation', status=status)
                ET.SubElement(row, 'mutatedClass').text = prefix + 'Fixture'
        summary, survivors = gate.summarize(rows, dict.fromkeys(gate.PACKAGES, 60))
        for outcome in summary.values():
            self.assertEqual(50, outcome['assertionKillPercent'])
            self.assertFalse(outcome['passed'])
        self.assertEqual(6, len(survivors))
        for row in rows:
            row.set('status', 'TIMED_OUT' if row.get('status') == 'SURVIVED' else 'KILLED')
        summary, _ = gate.summarize(rows, dict.fromkeys(gate.PACKAGES, 70))
        for outcome in summary.values():
            self.assertEqual(75, outcome['assertionKillPercent'])
            self.assertFalse(outcome['passed'])

    def test_E11_T06_missing_package_never_passes(self):
        summary, _ = gate.summarize([], dict.fromkeys(gate.PACKAGES, 0))
        self.assertTrue(all(not row['passed'] for row in summary.values()))

    def test_E11_T06_pit_selects_every_critical_package_without_class_exclusions(self):
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        pom = ET.parse(ROOT / 'pom.xml')
        config = next(profile for profile in pom.findall('m:profiles/m:profile', ns)
                      if profile.findtext('m:id', namespaces=ns) == 'mutation').find('m:build/m:plugins/m:plugin/m:configuration', ns)
        targets = {row.text for row in config.findall('m:targetClasses/m:param', ns)}
        self.assertEqual({prefix + '*' for prefix in gate.PACKAGES.values()}, targets)
        self.assertIsNone(config.find('m:excludedClasses', ns))


if __name__ == '__main__':
    unittest.main(verbosity=2)
