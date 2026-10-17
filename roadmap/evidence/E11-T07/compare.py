"""Baseline vs final test summaries, per test class (E11-T07 evidence): python3 compare.py BASELINE FINAL."""
import re
import sys


def load(path):
    rows = {}
    for line in open(path):
        match = re.match(r'(surefire|failsafe) (\S+) tests=(\d+) failures=(\d+) errors=(\d+) skipped=(\d+)', line)
        if match:
            rows[(match[1], match[2])] = tuple(map(int, match.groups()[2:]))
    return rows


base, final = load(sys.argv[1]), load(sys.argv[2])
print('# Baseline (main 2316349c, Boot 3.5.16) vs final (rebased on origin/main 195d82d2, Boot 4.1.1), per test class.')
for kind in ('surefire', 'failsafe'):
    b = [v for k, v in base.items() if k[0] == kind]
    f = [v for k, v in final.items() if k[0] == kind]
    print(f'{kind}: baseline classes={len(b)} tests={sum(x[0] for x in b)} | final classes={len(f)} tests={sum(x[0] for x in f)} '
          f'failures={sum(x[1] for x in f)} errors={sum(x[2] for x in f)} skipped={sum(x[3] for x in f)}')
missing = [k for k in base if k not in final]
fewer = [(k, base[k][0], final[k][0]) for k in base if k in final and final[k][0] < base[k][0]]
print('baseline classes missing in final:', missing or 'none')
print('classes with fewer tests than baseline:', fewer or 'none')
print('classes new in final (this task or the rebased lanes):')
for key in sorted(set(final) - set(base)):
    print('  ', key[0], key[1], 'tests=%d' % final[key][0])
print('classes with more tests than baseline:')
for key in sorted(base):
    if key in final and final[key][0] > base[key][0]:
        print('  ', key[0], key[1], base[key][0], '->', final[key][0])
