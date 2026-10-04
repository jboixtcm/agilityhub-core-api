#!/usr/bin/env python3
"""Summarize only a complete PIT XML report; separate test kills from timeout/error detections."""
from pathlib import Path
import argparse, collections, gzip, json, re, xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
source = ROOT / 'target/pit-reports/mutations.xml'
root = ET.parse(source).getroot()
results = collections.defaultdict(list)
classes = collections.defaultdict(collections.Counter)
survivors = []
for mutation in root:
    name = mutation.findtext('mutatedClass')
    package = 'bookings' if '.clubs.bookings.' in name else 'identity' if '.identity.' in name else 'outside-scope'
    status = mutation.get('status')
    results[package].append((status,mutation.get('detected') == 'true'))
    classes[name][status] += 1
    if status in ('SURVIVED','NO_COVERAGE','TIMED_OUT','RUN_ERROR','MEMORY_ERROR'):
        survivors.append(dict(package=package,status=status,**{key:mutation.findtext(key) for key in ['mutatedClass','mutatedMethod','lineNumber','mutator','description']}))
assert set(results) == {'bookings','identity'}
summary={}
for package,values in results.items():
    counts = dict(collections.Counter(status for status,_ in values))
    detected = sum(detected for _,detected in values)
    summary[package] = dict(total=len(values),statuses=counts,pitDetectedPercent=round(100*detected/len(values),2),
                            testKilledPercent=round(100*counts.get('KILLED',0)/len(values),2))
parser = argparse.ArgumentParser()
parser.add_argument('--log', required=True)
args = parser.parse_args()
log=(EVIDENCE/args.log).read_text()
assert re.search(r'^exit 0$', log, re.M), 'A complete successful PIT run is required'
summary['wallSecondsIncludingLockWait']=float(re.search(r'elapsed_seconds ([0-9.]+)',log).group(1))
regressions = [
    ('waitlist hold capacity', 'WaitlistService', 'Replaced long addition with subtraction', 'T_08_22_R_08_15_'),
    ('logout revocation event', 'TokenService', '::revoked', 'T_01_10_revokeIsIdempotent'),
    ('session-limit revocation event', 'TokenService', '::revoked', 'T_01_06_maximumSessions'),
]
for label, class_name, description, test in regressions:
    matches = [mutation for mutation in root
               if mutation.findtext('mutatedClass').endswith('.' + class_name)
               and description in mutation.findtext('description')
               and test in (mutation.findtext('killingTest') or '')]
    assert matches and all(mutation.get('status') == 'KILLED' for mutation in matches), label
    print('PASS assertion kill:', label, 'by', test)
(EVIDENCE/'pitest-class-statuses.json').write_text(json.dumps({name: dict(counts) for name, counts in sorted(classes.items())},indent=2)+'\n')
(EVIDENCE/'pitest-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
(EVIDENCE/'pitest-survivors.json').write_text(json.dumps(survivors,indent=2)+'\n')
(EVIDENCE/'pitest-mutations.xml.gz').write_bytes(gzip.compress(source.read_bytes(),mtime=0))
print(json.dumps(summary,indent=2))
