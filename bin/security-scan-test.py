#!/usr/bin/env python3
"""The CI policy: lower severity / no fix report only; fixed CRITICAL and every secret block."""
import runpy
import unittest
from pathlib import Path

scanner = runpy.run_path(str(Path(__file__).with_name('security-scan')))
findings = scanner['findings']
java_inventory = scanner['java_inventory']


class ScanPolicyTest(unittest.TestCase):
    def test_fixed_critical_blocks_but_unfixed_and_lower_severity_only_report(self):
        report = {'Results': [{'Vulnerabilities': [
            {'VulnerabilityID': 'CVE-fixture-1', 'Severity': 'CRITICAL', 'FixedVersion': '2', 'PkgName': 'fixture'},
            {'VulnerabilityID': 'CVE-fixture-2', 'Severity': 'CRITICAL', 'FixedVersion': ''},
            {'VulnerabilityID': 'CVE-fixture-3', 'Severity': 'HIGH', 'FixedVersion': '2'}]}]}
        self.assertEqual(findings(report), {'vulnerabilities': 3, 'fixableCritical': [('CVE-fixture-1', 'fixture', '2')], 'secretRules': [], 'otherFindings': [('CVE-fixture-2', None, 'CRITICAL', None, ''), ('CVE-fixture-3', None, 'HIGH', None, '2')]})

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
        self.assertEqual(findings({}), {'vulnerabilities': 0, 'fixableCritical': [], 'secretRules': [], 'otherFindings': []})


if __name__ == '__main__':
    unittest.main()
