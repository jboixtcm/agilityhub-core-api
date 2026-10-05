#!/usr/bin/env python3
"""Run the supported image smoke using the exact JAR from the successful clean verification."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile
import zipfile

root = Path.cwd()
artifact, = Path('target').glob('*.jar')
with zipfile.ZipFile(artifact) as archive:
    entries = {entry.filename: entry for entry in archive.infolist()}
    classes = [path for path in Path('target/classes').rglob('*') if path.is_file()]
    for path in classes:
        relative = path.relative_to('target/classes').as_posix()
        entry = relative if relative.startswith('META-INF/') and relative in entries else 'BOOT-INF/classes/' + relative
        if relative == 'META-INF/build-info.properties':
            # Fresh OpenAPI generation refreshes this Maven timestamp after the clean verify package.
            stable = lambda value: [line for line in value.decode().splitlines() if not line.startswith('build.time=')]
            assert stable(archive.read(entry)) == stable(path.read_bytes()), 'Packaged build identity differs'
            continue
        assert entry in entries and archive.read(entry) == path.read_bytes(), f'Packaged class/resource differs: {entry}'
print(f'PASS verified JAR matches all {len(classes)} compiled classes/resources (excluding refreshed build.time only)', flush=True)
original = artifact.read_bytes()
print('Verified JAR sha256=' + hashlib.sha256(original).hexdigest()[:8] + '...[truncated]', flush=True)

image = 'agilityhub-e8-t04-verified:local'
with tempfile.TemporaryDirectory(prefix='agilityhub-e8-t04-image-') as directory:
    context = Path(directory)
    shutil.copy2(artifact, context / 'app.jar')
    shutil.copytree('seeds', context / 'seeds')
    dockerfile = Path('Dockerfile').read_text()
    runtime = 'FROM eclipse-temurin' + dockerfile.split('\nFROM eclipse-temurin', 1)[1]
    source = 'COPY --from=build --chown=agilityhub:agilityhub /workspace/target/*.jar app.jar'
    assert source in runtime
    runtime = runtime.replace(source, 'COPY --chown=agilityhub:agilityhub app.jar app.jar')
    (context / 'Dockerfile').write_text(runtime)
    environment = dict(os.environ, BUILDX_CONFIG=str(context / 'buildx'))
    subprocess.run(['docker', 'build', '--tag', image, str(context)], check=True, env=environment)
    container = subprocess.check_output(['docker', 'create', image], text=True).strip()
    try:
        copied = context / 'image.jar'
        subprocess.run(['docker', 'cp', container + ':/app/app.jar', str(copied)], check=True)
        assert copied.read_bytes() == original, 'Runtime image changed the verified JAR'
        print('PASS runtime Dockerfile stage preserved; image JAR is byte-identical to clean verify', flush=True)
    finally:
        subprocess.run(['docker', 'rm', container], stdout=subprocess.DEVNULL, check=True)
subprocess.run([str(root / 'bin/e3-smoke'), '--image', image], check=True)
