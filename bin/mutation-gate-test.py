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
        # A timeout is not an assertion kill: 3 of 4, so 75 % fails an 80 % gate (it would pass if it counted as killed).
        summary, _ = gate.summarize(rows, dict.fromkeys(gate.PACKAGES, 80))
        for outcome in summary.values():
            self.assertEqual(75, outcome['assertionKillPercent'])
            self.assertEqual(1, outcome['notProof'])
            self.assertFalse(outcome['passed'])

    def test_E11_T06_batches_merge_and_a_mutation_reported_twice_is_refused(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, status in enumerate(['KILLED', 'SURVIVED']):
                rows = ET.Element('mutations')
                row = ET.SubElement(rows, 'mutation', status=status)
                ET.SubElement(row, 'mutatedClass').text = gate.PACKAGES['payments'] + 'Fixture'
                ET.SubElement(row, 'lineNumber').text = str(index)
                paths.append(Path(directory) / f'{index}.xml')
                ET.ElementTree(rows).write(paths[-1])
            self.assertEqual(['KILLED', 'SURVIVED'], [row.get('status') for row in gate.merge(paths)])
            with self.assertRaises(ValueError):
                gate.merge([paths[0], paths[0]])

    def test_E11_T06_two_mutants_of_one_line_differ_by_their_index(self):
        import tempfile
        rows = ET.Element('mutations', partial='true')
        for index in ('8', '16'):
            row = ET.SubElement(rows, 'mutation', status='KILLED')
            ET.SubElement(row, 'mutatedClass').text = gate.PACKAGES['payments'] + 'SepaText'
            ET.SubElement(row, 'lineNumber').text = '37'
            ET.SubElement(row, 'mutator').text = 'ConditionalsBoundaryMutator'
            ET.SubElement(ET.SubElement(row, 'indexes'), 'index').text = index
            ET.SubElement(ET.SubElement(row, 'blocks'), 'block').text = '1'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'batch.xml'
            ET.ElementTree(rows).write(path)
            self.assertEqual(2, len(gate.merge([path])))

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
