"""The evidence merge must not inflate a score or silently omit generated classes."""
from copy import deepcopy
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent


def mutation(clazz, status='KILLED', tests=1):
    row = ET.Element('mutation', status=status, numberOfTestsRun=str(tests))
    for name, value in {'mutatedClass': clazz, 'mutatedMethod': 'read', 'methodDescription': '()I',
                        'lineNumber': '1', 'mutator': 'ExampleMutator', 'description': 'replace return'}.items():
        ET.SubElement(row, name).text = value
    return row


class CurrentMergeTest(unittest.TestCase):
    def run_merge(self, package_rows, change):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            root = ET.Element('mutations')
            for rows in package_rows.values():
                root.extend(deepcopy(rows))
            ET.ElementTree(root).write(folder / 'unit.xml')
            change(package_rows)
            command = [sys.executable, str(HERE / 'merge-current.py'), '--unit', str(folder / 'unit.xml')]
            for package, rows in package_rows.items():
                document = ET.Element('mutations')
                document.extend(rows)
                path = folder / (package + '.xml')
                ET.ElementTree(document).write(path)
                command += ['--supplement', str(path)]
            output = folder / 'merged.xml'
            command += ['--output', str(output)]
            result = subprocess.run(command, capture_output=True, text=True)
            return result, ET.parse(output).getroot() if output.exists() else None

    def rows(self):
        return {'bookings': [mutation('com.agilityhub.core.clubs.bookings.Example')],
                'identity': [mutation('com.agilityhub.core.identity.Example')],
                'payments': [mutation('com.agilityhub.core.payments.Example', 'SURVIVED'),
                             mutation('com.agilityhub.core.payments.sepa.generated.Example', 'NO_COVERAGE', 0)]}

    def test_E11_T06_zero_test_cached_result_cannot_create_a_new_kill(self):
        def cached(rows):
            rows['payments'][0].set('status', 'KILLED')
            rows['payments'][0].set('numberOfTestsRun', '0')
        result, root = self.run_merge(self.rows(), cached)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['KILLED', 'KILLED', 'SURVIVED', 'NO_COVERAGE'], [row.get('status') for row in root])

    def test_E11_T06_new_executed_kill_preserves_the_full_denominator(self):
        result, root = self.run_merge(self.rows(), lambda rows: rows['payments'][0].set('status', 'KILLED'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['KILLED', 'KILLED', 'KILLED', 'NO_COVERAGE'], [row.get('status') for row in root])

    def test_E11_T06_integration_coverage_updates_an_uncovered_survivors_triage(self):
        def covered(rows):
            rows['payments'][1].set('status', 'SURVIVED')
            rows['payments'][1].set('numberOfTestsRun', '2')
        result, root = self.run_merge(self.rows(), covered)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('SURVIVED', root[-1].get('status'))
        self.assertEqual('2', root[-1].get('numberOfTestsRun'))

    def test_E11_T06_missing_generated_mutant_or_changed_code_refuses_the_merge(self):
        for change in (lambda rows: rows['payments'].pop(),
                       lambda rows: setattr(rows['payments'][0].find('lineNumber'), 'text', '2')):
            with self.subTest(change=change):
                result, root = self.run_merge(self.rows(), change)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('incomplete or changed mutation set', result.stderr)
                self.assertIsNone(root)


if __name__ == '__main__':
    unittest.main(verbosity=2)
