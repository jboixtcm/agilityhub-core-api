#!/usr/bin/env python3
"""Run the write-tracking unit regressions without touching an ongoing build's target directory."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

with tempfile.TemporaryDirectory(prefix='e8-t10-unit-') as name:
    root = Path(name)
    for source in ('pom.xml', 'mvnw', '.mvn', 'src'):
        path = Path(source)
        if path.is_dir():
            shutil.copytree(path, root / source)
        else:
            shutil.copy2(path, root / source)
    command = ['./mvnw', '-q', '-Dtest=TenantWriteTrackingTest', 'test']
    print('Isolated source copy; command: ' + ' '.join(command), flush=True)
    result = subprocess.run(command, cwd=root, timeout=600)
    report = root / 'target/surefire-reports/TEST-com.agilityhub.core.shared.persistence.TenantWriteTrackingTest.xml'
    if report.exists():
        suite = ET.parse(report).getroot()
        print('TenantWriteTrackingTest: ' + ' '.join(k + '=' + suite.get(k) for k in ('tests', 'failures', 'errors', 'skipped')))
    print('Isolated Maven exit: ' + str(result.returncode), flush=True)
    raise SystemExit(result.returncode)
