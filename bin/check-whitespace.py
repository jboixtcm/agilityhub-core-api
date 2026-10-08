#!/usr/bin/env python3
"""Check the full task diff, including committed changes and untracked files, without writing git state."""
import argparse
import subprocess


def check(base):
    result = subprocess.run(['git', 'diff', '--check', base, '--'])
    code = result.returncode
    paths = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z']).split(b'\0')
    for path in paths:
        if path:
            checked = subprocess.run(['git', 'diff', '--no-index', '--check', '--', '/dev/null', path.decode()])
            # --no-index implies --exit-code: 1 is an ordinary added file; 2/3 flag whitespace errors.
            code = max(code, 0 if checked.returncode == 1 else checked.returncode)
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='Commit immediately before the task started')
    args = parser.parse_args()
    raise SystemExit(check(args.base))


if __name__ == '__main__':
    main()
