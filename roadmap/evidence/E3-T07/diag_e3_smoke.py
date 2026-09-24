#!/usr/bin/env python3
"""E3-T07 diagnostic for the bin/e3-smoke failure «Future verticals must stay empty».

Loads bin/e3-smoke unchanged, builds the same disposable stack, prints the dashboard
values the failing assertion reads (no token, no password), then runs the rest of the
scenario with ONLY that assertion downgraded to a printed warning. Every other
assertion still fails the run. Prints the final exit code. Evidence only; the smoke
itself is not modified. Run from the repository root.
"""
import importlib.machinery
import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
loader = importlib.machinery.SourceFileLoader("e3smoke", str(ROOT / "bin/e3-smoke"))
spec = importlib.util.spec_from_loader("e3smoke", loader)
smoke_module = importlib.util.module_from_spec(spec)
loader.exec_module(smoke_module)

DOWNGRADED = "Future verticals must stay empty"
original_require = smoke_module.require
original_dashboard = smoke_module.Smoke.dashboard


def tolerant_require(condition, message):
    if not condition and message == DOWNGRADED:
        print("WARN (downgraded by diagnostic): " + message, flush=True)
        return
    original_require(condition, message)


def reporting_dashboard(self, admin, pending, active):
    result = original_dashboard(self, admin, pending, active)
    if not getattr(self, "_reported", False):
        self._reported = True
        kpis = result["kpis"]
        print("DIAG kpis.classOccupancy = " + json.dumps(kpis.get("classOccupancy")), flush=True)
        print("DIAG kpis.trainingBookings = " + json.dumps(kpis.get("trainingBookings")), flush=True)
        print("DIAG dogsByLevel.totalActiveDogs = " + json.dumps(result["dogsByLevel"]["totalActiveDogs"]), flush=True)
        print("DIAG generatedAt/week keys = " + json.dumps(sorted(result.keys())), flush=True)
        counts = self.mongo("(()=>{const c=" + json.dumps(self.club_id) + ";return {classSessions:db.class_sessions.countDocuments({clubId:c}),"
                            "bookings:db.bookings.countDocuments({clubId:c}),trainingBookings:db.training_bookings.countDocuments({clubId:c})};})()")
        print("DIAG seeded documents = " + json.dumps(counts), flush=True)
    return result


smoke_module.require = tolerant_require
smoke_module.Smoke.dashboard = reporting_dashboard


def main():
    os.umask(0o077)
    code = 0
    with tempfile.TemporaryDirectory(prefix="agilityhub-e3diag-") as directory:
        smoke = smoke_module.Smoke(Path(directory), None)
        try:
            smoke.start()
            smoke.scenario()
        except AssertionError as error:
            print("FAIL: " + str(error), flush=True)
            code = 1
        except Exception as error:  # controlled: class name only, like the smoke
            print("FAIL: " + type(error).__name__, flush=True)
            code = 1
        finally:
            smoke.cleanup()
    print(f"DIAG exit code {code}", flush=True)
    sys.exit(code)


if __name__ == "__main__":
    main()
