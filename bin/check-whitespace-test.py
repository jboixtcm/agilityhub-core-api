#!/usr/bin/env python3
"""E8-T10 point 5: committed, working-tree and untracked changes all belong to the handoff."""
from pathlib import Path
import runpy
import subprocess
import unittest
import tempfile
from unittest.mock import patch

check = runpy.run_path(str(Path(__file__).with_name('check-whitespace.py')))['check']


class WhitespaceTest(unittest.TestCase):
    def test_E8_T10_point5_committed_whitespace_fails_even_with_a_clean_worktree(self):
        def run(args, **kwargs):
            return subprocess.CompletedProcess(args, 2 if args == ['git', 'diff', '--check', 'task-base', '--'] else 0)
        with patch('subprocess.run', side_effect=run), patch('subprocess.check_output', return_value=b''):
            self.assertEqual(check('task-base'), 2)

    def test_E8_T10_point5_untracked_paths_are_checked_without_shell_interpolation(self):
        filename = 'evidence with spaces.log'
        with patch('subprocess.run', side_effect=[subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 3)]) as run, \
                patch('subprocess.check_output', return_value=(filename + '\0').encode()):
            self.assertEqual(check('task-base'), 3)
            self.assertEqual(run.call_args.args[0], ['git', 'diff', '--no-index', '--check', '--', '/dev/null', filename])

    def test_E8_T10_point5_clean_range_and_untracked_file_pass(self):
        with patch('subprocess.run', side_effect=[subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 1)]), \
                patch('subprocess.check_output', return_value=b'clean.log\0'):
            self.assertEqual(check('task-base'), 0)

    def test_E8_T10_point5_real_git_distinguishes_added_files_from_whitespace_errors(self):
        real_run = subprocess.run
        def run(args, **kwargs):
            if args == ['git', 'diff', '--check', 'task-base', '--']:
                return subprocess.CompletedProcess(args, 0)
            return real_run(args, **kwargs)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'new evidence.log'
            with patch('subprocess.run', side_effect=run), patch('subprocess.check_output', return_value=(str(path) + '\0').encode()):
                path.write_text('clean evidence\n')
                self.assertEqual(check('task-base'), 0)
                path.write_text('trailing whitespace ' + '\n')
                self.assertNotEqual(check('task-base'), 0)


if __name__ == '__main__':
    unittest.main()
