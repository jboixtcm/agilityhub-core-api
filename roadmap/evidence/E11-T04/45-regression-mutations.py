"""Prove the behavior tests reject the previous defects, using temporary copies."""
from pathlib import Path
import subprocess
import tempfile

root = Path.cwd()
source = (root / 'deploy/backup/backup.py').read_text()
tests = (root / 'deploy/backup/test_backup.py').read_text()
mutations = [
    ('prefix guard removed', "            if not item['Key'].startswith(prefix):\n                continue\n", '',
     'test_E11_T04_prefix_filters_outside_keys_among_valid_pages'),
    ('snapshot replaced with live reads', "if timestamp is None:", "if True:",
     'test_E11_T04_snapshot_counts_all_collections_at_one_timestamp'),
    ('free space guard removed', "if estimate < GIB or shutil.disk_usage(work).free < estimate:", "if False:",
     'test_E11_T04_low_space_refused'),
    ('oplog timestamp taken from another namespace', "if oplog:", "if not prelude:",
     'test_E11_T04_archive_stream_tracks_only_the_actual_oplog'),
]
for label, old, new, method in mutations:
    assert old in source
    with tempfile.TemporaryDirectory(prefix='e11-mutation-') as directory:
        path = Path(directory)
        (path / 'backup.py').write_text(source.replace(old, new))
        (path / 'test_backup.py').write_text(tests)
        result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--entrypoint', '/opt/backup/bin/python',
             '-v', str(path)+':/tests:ro', '-w', '/tests', 'agilityhub-backup:e11-t04', '-B', '-m', 'unittest',
             '-v', 'test_backup.BackupSafetyTest.'+method], capture_output=True, text=True)
        print(label + ' -> exit ' + str(result.returncode), flush=True)
        print(result.stdout + result.stderr, flush=True)
        assert result.returncode == 1, 'Regression test accepted the defect'
print('PASS all four reinstated defects rejected; working tree unchanged')
