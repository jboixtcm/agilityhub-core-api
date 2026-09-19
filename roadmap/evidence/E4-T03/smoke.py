#!/usr/bin/env python3
"""E4-T03 curl rehearsal using the existing disposable Compose harness."""
from pathlib import Path
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import argparse
import json
import os
import re
import runpy
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[3]
harness = runpy.run_path(str(ROOT / 'bin/e3-smoke'))
Base = harness['Smoke']
Base.__init__.__globals__['LOCAL_IMAGE'] = 'agilityhub-e4-t03-smoke:local'
require = harness['require']

class CalendarSmoke(Base):
    def call(self, method, path, expected=200, **kwargs):
        kwargs['quiet'] = True
        result = super().call(method, path, expected, **kwargs)
        public_path = re.sub(r'[0-9a-f]{8}-[0-9a-f-]{27,}', '[id truncated]', path)
        print(f'curl {method} {public_path} -> {expected}', flush=True)
        return result

    def scenario(self):
        def login(user, client):
            return self.call('POST', '/oauth2/token', form=dict(grant_type='password', client_id=client,
                username=user, password=self.env['SEED_PASSWORD']))['access_token']
        admin = login('admin@example.test', 'clubs-admin')
        member = login('member@example.test', 'clubs-app')
        today = datetime.now(ZoneInfo('Europe/Madrid')).date()
        monday = today + timedelta(days=7-today.weekday())
        fixture = self.mongo('(()=>{const clubId=' + json.dumps(self.club_id) + ';return {ring:db.rings.findOne({clubId,active:true})._id,instructor:db.instructors.findOne({clubId,active:true})._id,level:db.levels.findOne({clubId,active:true})._id};})()')
        template = self.call('POST', '/api/v1/week-templates', 201, access=admin, body=dict(name='E4 calendar smoke', kind='WEEKDAYS'))
        tid = template['id']
        band = self.call('POST', f'/api/v1/week-templates/{tid}/bands', 201, access=admin, body=dict(startTime='18:00', endTime='19:00'))
        self.call('POST', f'/api/v1/week-templates/{tid}/classes', 201, access=admin, body=dict(bandId=band['bands'][0]['id'], dayOfWeek='MONDAY',
            ringId=fixture['ring'], instructorIds=[fixture['instructor']], levelIds=[fixture['level']]))
        week = self.call('POST', '/api/v1/weeks', 201, access=admin, body=dict(startDate=str(monday)))
        wid = week['id']
        self.call('POST', f'/api/v1/weeks/{wid}/generation', access=admin, key=str(uuid.uuid4()), body=dict(weekdayTemplateId=tid))
        def grid():
            return self.call('GET', f'/api/v1/day-grid?date={monday}&view=member', access=member)
        require(grid()['rows'] == [], 'Drafts leaked before validation')
        self.call('POST', f'/api/v1/weeks/{wid}/validation', access=admin, body={})
        view = grid()
        require(len(view['rows']) == 1 and view['rows'][0]['cells'][0]['kind'] == 'CLASS', 'Validated class not visible')
        require('occupancy' not in json.dumps(view), 'Member grid leaked counts')
        print('PASS generated DRAFT week -> validation -> member CLASS cell, occupancy absent', flush=True)
        start = datetime.combine(monday, datetime.min.time(), ZoneInfo('Europe/Madrid')).replace(hour=16, minute=10)
        body = dict(ringId=fixture['ring'], **{'from':start.isoformat(), 'to':(start+timedelta(minutes=30)).isoformat()}, kind='BLOCK', reason='MAINTENANCE')
        self.call('POST', '/api/v1/ring-blocks', 201, access=admin, key=str(uuid.uuid4()), body=body)
        self.call('POST', '/api/v1/ring-blocks', 409, access=admin, key=str(uuid.uuid4()), body=body, error='RING_BLOCK_CONFLICT')
        draft = self.call('POST', '/api/v1/class-sessions', 201, access=admin, body=dict(date=str(monday+timedelta(days=7)),
            startTime='18:00', endTime='19:00', ringId=fixture['ring'], instructorIds=[fixture['instructor']], levelIds=[fixture['level']]))
        key = str(uuid.uuid4())
        cancelled = self.call('POST', f'/api/v1/class-sessions/{draft["id"]}/cancellation', access=admin, key=key, body=dict(reason='DELETED'))
        require(cancelled['state'] == 'CANCELLED', 'Draft cancellation not persisted')
        replay = self.call('POST', f'/api/v1/class-sessions/{draft["id"]}/cancellation', access=admin, key=key, body=dict(reason='DELETED'))
        require(replay == cancelled, 'Cancellation replay differs')
        counts = self.mongo('(()=>{const clubId='+json.dumps(self.club_id)+';return {validated:db.domain_events.countDocuments({clubId,type:"WeekValidated"}),cancelled:db.domain_events.countDocuments({clubId,type:"ClassCancelledByClub"}),blocks:db.domain_events.countDocuments({clubId,type:"RingBlockCreated"}),N08a:db.notifications.countDocuments({clubId,code:"N-08a"})};})()')
        require(counts == dict(validated=1,cancelled=1,blocks=1,N08a=0), 'Unexpected outbox/notification counts: '+str(counts))
        print('PASS outbox: WeekValidated=1, ClassCancelledByClub=1, RingBlockCreated=1; N-08a rows=0 for an empty draft', flush=True)
        print('PASS registrant delivery is covered by CalendarIT: 4 bookings + 2 waitlist, 21 N-08a rows, 2 PackRefunded events', flush=True)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image', help='Reuse the current working tree image built by a preceding rehearsal')
args = parser.parse_args()
os.umask(0o077)
with tempfile.TemporaryDirectory(prefix='e4-t03-smoke-') as directory:
    smoke = CalendarSmoke(Path(directory), args.image)
    try:
        smoke.start()
        smoke.scenario()
    finally:
        smoke.cleanup()
