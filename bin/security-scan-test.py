#!/usr/bin/env python3
"""The CI policy: lower severity / no fix report only; fixed CRITICAL and every secret block, unless a valid suppression applies."""
import datetime
import json
import runpy
import unittest
from pathlib import Path

scanner = runpy.run_path(str(Path(__file__).with_name('security-scan')))
findings = scanner['findings']
java_inventory = scanner['java_inventory']
suppressions = scanner['suppressions']
TODAY = datetime.date(2026, 10, 8)


def entry(**overrides):
    value = {'id': 'CVE-fixture-1', 'package': 'fixture', 'installedVersion': '1', 'recorded': '2026-10-08',
             'expires': '2026-12-07', 'task': 'E0-T00', 'reason': 'Fixture reason'}
    value.update(overrides)
    return value


class ScanPolicyTest(unittest.TestCase):
    def test_fixed_critical_blocks_but_unfixed_and_lower_severity_only_report(self):
        report = {'Results': [{'Vulnerabilities': [
            {'VulnerabilityID': 'CVE-fixture-1', 'Severity': 'CRITICAL', 'FixedVersion': '2', 'PkgName': 'fixture'},
            {'VulnerabilityID': 'CVE-fixture-2', 'Severity': 'CRITICAL', 'FixedVersion': ''},
            {'VulnerabilityID': 'CVE-fixture-3', 'Severity': 'HIGH', 'FixedVersion': '2'}]}]}
        self.assertEqual(findings(report), {'vulnerabilities': 3, 'fixableCritical': [('CVE-fixture-1', 'fixture', '2')], 'suppressedCritical': [], 'secretRules': [], 'otherFindings': [('CVE-fixture-2', None, 'CRITICAL', None, ''), ('CVE-fixture-3', None, 'HIGH', None, '2')]})

    def test_a_suppression_applies_only_to_its_exact_finding_and_package_version(self):
        critical = {'VulnerabilityID': 'CVE-fixture-1', 'Severity': 'CRITICAL', 'FixedVersion': '2', 'PkgName': 'fixture',
                    'InstalledVersion': '1', 'Title': 'Fixture title'}
        active, expired = suppressions({'suppressions': [entry()]}, TODAY)
        self.assertEqual(expired, [])
        result = findings({'Results': [{'Vulnerabilities': [critical]}]}, active)
        self.assertEqual(result['fixableCritical'], [])
        self.assertEqual(result['suppressedCritical'], [('CVE-fixture-1', 'fixture', '1', '2', '2026-12-07', 'Fixture title')])
        for other in ({'InstalledVersion': '1.1'}, {'PkgName': 'other'}, {'VulnerabilityID': 'CVE-fixture-9'}):
            blocked = findings({'Results': [{'Vulnerabilities': [dict(critical, **other)]}]}, active)
            self.assertEqual(len(blocked['fixableCritical']), 1, other)
            self.assertEqual(blocked['suppressedCritical'], [])

    def test_an_expired_suppression_stops_applying_and_the_finding_blocks_again(self):
        active, expired = suppressions({'suppressions': [entry()]}, datetime.date(2026, 12, 8))
        self.assertEqual((active, expired), ([], ['CVE-fixture-1']))
        critical = {'VulnerabilityID': 'CVE-fixture-1', 'Severity': 'CRITICAL', 'FixedVersion': '2', 'PkgName': 'fixture', 'InstalledVersion': '1'}
        self.assertEqual(findings({'Results': [{'Vulnerabilities': [critical]}]}, active)['fixableCritical'], [('CVE-fixture-1', 'fixture', '2')])
        self.assertEqual(len(suppressions({'suppressions': [entry()]}, datetime.date(2026, 12, 7))[0]), 1)

    def test_a_suppression_without_a_reason_or_longer_than_ninety_days_fails_the_audit(self):
        for bad in (entry(reason=' '), entry(task=None), entry(expires='2027-01-07'), entry(expires='2026-10-07')):
            with self.assertRaises(ValueError):
                suppressions({'suppressions': [bad]}, TODAY)
        self.assertEqual(len(suppressions({'suppressions': [entry(expires='2027-01-06')]}, TODAY)[0]), 1)

    def test_the_committed_suppressions_are_valid_and_name_their_task(self):
        for committed in json.loads(scanner['SUPPRESSIONS'].read_text())['suppressions']:
            active, _ = suppressions({'suppressions': [committed]}, datetime.date.fromisoformat(committed['recorded']))
            self.assertEqual(len(active), 1)
            self.assertGreater(len(committed['reason']), 40)

    def test_every_secret_blocks_without_reporting_the_match(self):
        result = findings({'Results': [{'Secrets': [{'RuleID': 'fixture-secret', 'Match': 'sensitive-fixture'}]}]})
        self.assertEqual(result['secretRules'], ['fixture-secret'])
        self.assertNotIn('sensitive-fixture', str(result))

    def test_missing_java_dependencies_fail_even_when_no_vulnerabilities_were_found(self):
        for report in [{}, {'Results': [{'Type': 'jar', 'Packages': [{'Name': 'org.springframework:spring-web'}]}]}]:
            with self.assertRaisesRegex(ValueError, 'Java dependency inventory incomplete'):
                java_inventory(report)

    def test_packaged_java_inventory_includes_core_runtime_dependencies(self):
        packages = [{'Name': name} for name in scanner['REQUIRED_JAVA_PACKAGES']]
        report = {'Results': [{'Type': 'jar', 'Packages': packages}, {'Type': 'debian', 'Packages': [{'Name': 'curl'}]}]}
        self.assertEqual(java_inventory(report), 3)

    def test_no_vulnerability_findings_is_distinct_from_a_valid_inventory(self):
        self.assertEqual(findings({}), {'vulnerabilities': 0, 'fixableCritical': [], 'suppressedCritical': [], 'secretRules': [], 'otherFindings': []})


if __name__ == '__main__':
    unittest.main()
