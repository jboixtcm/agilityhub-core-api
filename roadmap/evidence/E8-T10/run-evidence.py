#!/usr/bin/env python3
"""Run a verification with a one-hour limit; retain output, exit, and only safe evidence text."""
from pathlib import Path
import re
import shlex
import subprocess
import sys


def clean(text):
    text = re.sub(r'\x1b\[[0-9;]*m', '', text)
    text = re.sub(r'\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]){11,30}\b', '[IBAN truncated]', text)
    text = re.sub(r'\b[a-f0-9]{32,}\b', lambda m: m[0][:8] + '...[truncated]', text)
    text = re.sub(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', 'eyJ...[truncated]', text)
    text = re.sub(r'(?:sk_(?:test|live)_|whsec_)[A-Za-z0-9]{16,}', '[secret truncated]', text)
    # Failed database-snapshot assertions can print fixture credentials and random idempotency keys.
    def field(match):
        name, value = match[2], match[3]
        if 'truncated' in value or value.lower() in {'redacted', '[redacted]'}:
            return match[0]
        if name.lower() == 'key' and not re.fullmatch(r'[a-f0-9-]{32,}', value, re.I):
            return match[0]
        return match[1] + value[:4] + '...[truncated]' + match[4]
    text = re.sub(r'''(["']([^"']*(?:secret|token|password|hash)[^"']*|key)["']\s*[:=]\s*["'])([^"'\r\n]*)(["'])''', field, text, flags=re.I)
    return '\n'.join(line.rstrip() for line in text.splitlines()).rstrip() + '\n'


def main():
    log = Path(sys.argv[1])
    command = sys.argv[2:]
    if log.exists():
        raise SystemExit('Refusing to overwrite existing evidence')
    with log.with_name('commands.txt').open('a') as commands:
        commands.write(log.name + ': ' + shlex.join(command) + '\n')
    with log.open('w') as output:
        try:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=3600)
            code = result.returncode
        except subprocess.TimeoutExpired:
            code = 124
    log.write_text(clean(log.read_text()) + '\nexit ' + str(code) + '\n')
    print('Command: ' + shlex.join(command))
    print('Log: ' + str(log))
    print('Exit: ' + str(code))
    raise SystemExit(code)


if __name__ == '__main__':
    main()
