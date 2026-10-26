#!/usr/bin/env python3
"""Regression checks for the release gate's JUnit-to-spec trace report."""
import contextlib
import csv
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("spec_trace", ROOT / "roadmap/evidence/E11-T12/trace.py")
TRACE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TRACE)


class SpecTraceTest(unittest.TestCase):
    def report(self, outcomes):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = {name: root / name for name in ("surefire", "failsafe")}
            specs = root / "specs"
            specs.mkdir()
            (specs / "S01-test.md").write_text("| T-01-01 | API | All parameter cases pass |\n")
            for name, directory in reports.items():
                directory.mkdir()
                cases = outcomes if name == "surefire" else [""]
                method = "T_01_01_parameterized" if name == "surefire" else "otherMethod"
                body = "".join(
                    f'<testcase classname="example.TraceTest" name="{method}(String)[{index}]">'
                    f'{outcome}</testcase>' for index, outcome in enumerate(cases, 1)
                )
                (directory / "TEST-trace.xml").write_text(
                    f'<testsuite name="example.{name}.TraceTest" tests="{len(cases)}" '
                    f'failures="{sum("failure" in case for case in cases)}" '
                    f'errors="{sum("error" in case for case in cases)}" '
                    f'skipped="{sum("skipped" in case for case in cases)}">{body}</testsuite>'
                )
            with patch.multiple(TRACE, REPORTS=reports, SPECS=specs, OUT=root,
                                JACOCO=root / "missing-jacoco.csv"), contextlib.redirect_stdout(io.StringIO()):
                TRACE.main()
            with (root / "trace-tests.csv").open() as handle:
                rows = list(csv.DictReader(handle))
            return rows, (root / "trace-summary.md").read_text()

    def test_a_skipped_parameter_never_proves_the_whole_spec_passed(self):
        rows, summary = self.report(["", '<skipped message="environment unavailable"/>'])
        self.assertEqual([row["result"] for row in rows], ["SKIPPED"])
        self.assertIn("| S01 | 1 | 1 | NO: T-01-01 |", summary)
        self.assertIn("1 with a non-passing method", summary)

    def test_a_failure_is_not_hidden_by_a_skipped_parameter(self):
        rows, summary = self.report(["", "<skipped/>", '<failure message="wrong result"/>'])
        self.assertEqual([row["result"] for row in rows], ["FAIL"])
        self.assertIn("wrong result", summary)

    def test_all_parameters_must_pass(self):
        rows, summary = self.report(["", ""])
        self.assertEqual([row["result"] for row in rows], ["PASS"])
        self.assertIn("| S01 | 1 | 1 | yes |", summary)


if __name__ == "__main__":
    unittest.main()
