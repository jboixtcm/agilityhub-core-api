#!/usr/bin/env python3
"""Import exact licensed results via the existing tenant-scoped competition API."""
import argparse
from datetime import date, datetime, timedelta, timezone
import fcntl
import hashlib
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler
from zoneinfo import ZoneInfo
import uuid
from bs4 import BeautifulSoup
import websocket
from discovery import DiscoveryError, UUID, RUN, PAGE, events, merge, render, run_links

ORIGIN='https://www.flowagility.com'
HEADERS=['CLA DO','Guía Perro','Club Licencia','T','F','R','PR','ET','TP','VEL','CAL','PTS']
SESSION=re.compile(r'[A-Za-z0-9_.~+%=-]{20,8192}')

class WorkerError(Exception): pass
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args): return None

def private_json(path):
    p=Path(path)
    if p.is_symlink() or not p.is_file() or p.stat().st_mode & 0o077:raise WorkerError('PRIVATE_FILE_REQUIRED')
    return json.loads(p.read_text())

def atomic(path, data):
    p=Path(path);fd,name=tempfile.mkstemp(dir=p.parent,prefix='.flow-')
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,'w') as f:
            json.dump(data,f,sort_keys=True);f.flush();os.fsync(f.fileno())
        os.replace(name,p)
    finally:
        if os.path.exists(name):os.unlink(name)

def now():return datetime.now(timezone.utc).isoformat()
def emit(event,**fields):print(json.dumps({'at':now(),'event':event,**fields}),flush=True)

def window(today):
    # Last completed Sunday, including on a delayed/restarted weekday invocation.
    sunday=today-timedelta(days=today.weekday()+1)
    return sunday-timedelta(days=15),sunday

def selected_event(event,start,end,weekly):
    a=date.fromisoformat(event['start']);b=date.fromisoformat(event['end'])
    return event['federation']=='FCI / RSCE' and a<=end and b>=start

def selected_date(day,start,end,weekly):return start<=day<=end and (not weekly or day.weekday() in (5,6))

class Flow:
    def __init__(self, session_file, delay=0.3):
        self.path=Path(session_file);self.session=private_json(self.path)['session'];self.delay=delay;self.last=0
        if not SESSION.fullmatch(self.session):raise WorkerError('INVALID_FLOW_SESSION')
        self.http=build_opener(NoRedirect())
    def get(self,path):
        if not (path in ('/zone/events/past','/zone/events/past_all') or re.fullmatch('/zone/event/'+UUID+'/runs',path) or PAGE.fullmatch(path) or RUN.fullmatch(path)):
            raise WorkerError('UNEXPECTED_FLOW_PATH')
        time.sleep(max(0,self.delay-(time.monotonic()-self.last)))
        try:
            with self.http.open(Request(ORIGIN+path,headers={'Cookie':'_facom_key='+self.session,'Accept-Language':'es','Accept':'text/html'}),timeout=30) as r:
                body=r.read(8_000_001)
                if len(body)>8_000_000:raise WorkerError('FLOW_PAGE_TOO_LARGE')
                html=body.decode('utf-8')
                if '/user/login' in html and 'name="user[password]"' in html:raise WorkerError('FLOW_AUTH_REQUIRED')
                for header in r.headers.get_all('Set-Cookie',[]):
                    c=SimpleCookie();c.load(header)
                    if '_facom_key' in c:
                        value=c['_facom_key'].value
                        if not SESSION.fullmatch(value):raise WorkerError('INVALID_FLOW_RENEWAL')
                        self.session=value;atomic(self.path,{'session':value,'renewedAt':now(),'maxAge':c['_facom_key']['max-age']})
                return html
        except HTTPError as e:
            raise WorkerError('FLOW_AUTH_REQUIRED' if e.code in (301,302,303,401,403) else 'FLOW_HTTP_'+str(e.code)) from None
        except (URLError,TimeoutError):raise WorkerError('FLOW_NETWORK') from None
        finally:self.last=time.monotonic()
    def live(self,path):
        html=self.get(path);doc=BeautifulSoup(html,'html.parser')
        if doc.select_one('#runs_list'):return html
        root=doc.select_one('[data-phx-main]');csrf=doc.select_one('meta[name="csrf-token"]')
        if not root or not csrf:raise WorkerError('FLOW_LIVE_AUTH_REQUIRED')
        token=csrf['content'];ws=None
        try:
            ws=websocket.create_connection('wss://www.flowagility.com/live/websocket?'+urlencode({'_csrf_token':token,'_mounts':0,'vsn':'2.0.0'}),origin=ORIGIN,cookie='_facom_key='+self.session,timeout=20,redirect_limit=0)
            ws.send(json.dumps(['1','1','lv:'+root['id'],'phx_join',{'url':ORIGIN+path,'params':{'_csrf_token':token,'_mounts':0,'_track_static':[]},'session':root['data-phx-session'],'static':root.get('data-phx-static')}]))
            state={};deadline=time.monotonic()+45
            for _ in range(12):
                if time.monotonic()>deadline:break
                raw=ws.recv()
                if len(raw)>8_000_000:raise WorkerError('FLOW_LIVE_TOO_LARGE')
                msg=json.loads(raw)
                if msg[3]=='phx_reply':
                    if msg[4].get('status')!='ok':raise WorkerError('FLOW_LIVE_REJECTED')
                    diff=msg[4].get('response',{}).get('rendered',{})
                elif msg[3]=='diff':diff=msg[4]
                else:continue
                state=merge(state,diff);html=render(state)
                doc=BeautifulSoup(html,'html.parser')
                if doc.select_one('#runs_list'):return html
                # An explicitly empty day still has navigation and no loading indicator.
                if 'Loading...' not in doc.get_text() and run_links(html)[1]:return html
            raise WorkerError('FLOW_LIVE_INCOMPLETE')
        except (websocket.WebSocketException,TimeoutError,OSError,ValueError,KeyError):raise WorkerError('FLOW_LIVE_FAILED') from None
        finally:
            if ws:ws.close()
    def discover_runs(self,path,start=None,end=None):
        pending=[path];seen=set();runs=set()
        while pending:
            page=pending.pop(0)
            if page in seen:continue
            if len(seen)>=100:raise WorkerError('FLOW_TOO_MANY_PAGES')
            seen.add(page);html=self.live(page);found,links=run_links(html);runs.update(found)
            pending.extend(x for x in links if x not in seen and (start is None or start<=date.fromisoformat(x.rsplit('/',1)[1])<=end))
        if not runs:raise WorkerError('FLOW_NO_RUNS')
        return sorted(runs)

class Api:
    def __init__(self,config):
        self.origin=config['apiOrigin'];self.token=None;self.http=build_opener(NoRedirect());self.started=0
        if not re.fullmatch(r'https://[a-z0-9.-]+',self.origin):raise WorkerError('INVALID_API_ORIGIN')
        credential_dir=os.environ.get('CREDENTIALS_DIRECTORY')
        credential_file=str(Path(credential_dir)/'api-access') if credential_dir else config['apiAccountsFile']
        if credential_dir:
            credential=Path(credential_file);metadata=credential.stat()
            if credential.is_symlink() or not credential.is_file() or metadata.st_mode & 0o037 or metadata.st_gid not in (0,os.getgid()) or metadata.st_uid not in (0,os.getuid()):raise WorkerError('PRIVATE_SYSTEMD_CREDENTIAL_REQUIRED')
            accounts=json.loads(credential.read_text())['accounts']
        else:accounts=private_json(credential_file)['accounts']
        selected=[a for a in accounts if a['role']=='ADMIN']
        if len(selected)!=1:raise WorkerError('ADMIN_ACCOUNT_NOT_UNIQUE')
        self.account=selected[0];self.club=config['clubId'];self.login()
    def login(self):
        if self.token:self.close()
        result=self.request('POST','/oauth2/token',form={'grant_type':'password','client_id':'clubs-admin','username':self.account['username'],'password':self.account['password']})
        self.token=result['access_token'];self.started=time.monotonic();self.expires=self.started+max(5,int(result.get('expires_in',300))-60)
        club=self.request('GET','/api/v1/club',retry_auth=False)
        if club.get('id')!=self.club or 'STATS' not in club['modules']:raise WorkerError('TENANT_OR_MODULE_MISMATCH')
    def request(self,method,path,data=None,form=None,key=None,retry_auth=True):
        if not (path in ('/oauth2/token','/oauth2/revoke','/api/v1/club','/api/v1/competition-imports','/api/v1/competition-imports/preview') or re.fullmatch('/api/v1/competition-imports/'+UUID+'/confirm',path) or re.fullmatch('/api/v1/dogs/'+UUID+r'/competition-statistics(?:\?year=20\d{2})?',path)):
            raise WorkerError('UNEXPECTED_API_PATH')
        if self.token and time.monotonic()>=self.expires and path.startswith('/api/'):self.login()
        headers={'Origin':self.origin};body=None
        if self.token:headers['Authorization']='Bearer '+self.token
        if data is not None:body=json.dumps(data).encode();headers['Content-Type']='application/json'
        if form is not None:body=urlencode(form).encode();headers['Content-Type']='application/x-www-form-urlencoded'
        if method=='POST' and path.startswith('/api/'):
            key=key or str(uuid.uuid4());headers['Idempotency-Key']=key
        try:
            with self.http.open(Request(self.origin+path,method=method,data=body,headers=headers),timeout=45) as r:
                raw=r.read(8_000_001)
                if len(raw)>8_000_000:raise WorkerError('API_RESPONSE_TOO_LARGE')
                return json.loads(raw) if raw else None
        except HTTPError as e:
            if e.code==401 and retry_auth and path.startswith('/api/'):
                self.token=None;self.login()
                return self.request(method,path,data=data,form=form,key=key,retry_auth=False)
            try:
                p=json.loads(e.read(10000));reason=p.get('details',{}).get('reason',p.get('code','ERROR'))
                if not re.fullmatch('[A-Z_0-9]{1,100}',str(reason)):reason='ERROR'
            except (ValueError,AttributeError):reason='ERROR'
            raise WorkerError('API_'+str(e.code)+'_'+reason) from None
        except (URLError,TimeoutError):raise WorkerError('API_NETWORK') from None
    def close(self):
        if self.token:
            try:self.request('POST','/oauth2/revoke',data={})
            except WorkerError as e:
                if not str(e).startswith('API_401_'):raise
            finally:self.token=None

def result_subset(html,licenses,start,end,weekly):
    doc=BeautifulSoup(html,'html.parser')
    for e in doc.select('script,style,meta,input,[data-phx-session]'):
        # Print documents do not require interactive page wrappers.
        if e.has_attr('data-phx-session'):
            e.attrs={k:v for k,v in e.attrs.items() if not k.startswith('data-phx')}
        else:e.decompose()
    title=doc.select('.results-caption');organ=doc.select('.organ-caption')
    if not title or not organ:raise WorkerError('FLOW_PRINT_STRUCTURE')
    caption=title[0].get_text(' ',strip=True)
    if not re.fullmatch(r'(?:Resultados|Results|Resultats) - G[123] / [A-Za-z]+ / (?:AG|JP)',caption):return None,'unsupported',0
    m=re.search(r' - (20\d{2})\.(\d{2})\.(\d{2})$',organ[0].get_text(' ',strip=True))
    if not m:raise WorkerError('FLOW_RESULT_DATE')
    day=date(*map(int,m.groups()))
    if not selected_date(day,start,end,weekly):return None,'outside_window',0
    header=False;matched=0;total=0
    for row in list(doc.select('div.flex-row')):
        cells=row.find_all(recursive=False)
        values=[c.get_text(' ',strip=True) for c in cells]
        if values==HEADERS:header=True;continue
        if not header or not cells:continue
        if len(cells)!=12:continue
        nested=[c.find_all(recursive=False) for c in cells[:3]]
        if any(len(n)!=2 for n in nested):raise WorkerError('FLOW_ROW_STRUCTURE')
        total+=1;license=nested[2][1].get_text(' ',strip=True)
        if license in licenses:
            matched+=1
            point=cells[11].get_text(' ',strip=True)
            # Flow prints a count plus the official class. The Core model stores one class per run.
            # Accept exactly one explicit point; never infer it from faults, time, or qualification.
            if re.fullmatch(r'1(?:P|PA|PV)',point):cells[11].string=point[1:]
        else:row.decompose()
    if not header:raise WorkerError('FLOW_HEADERS_CHANGED')
    if not total:return None,'unpublished',0
    if not matched:return None,'no_match',total
    return str(doc),'matched',total

def assignments(preview):
    selected={}
    for row in preview['rows']:
        if row['match']=='AMBIGUOUS_LICENSE':raise WorkerError('AMBIGUOUS_LICENSE')
        if row['match']=='EXACT_LICENSE' and row.get('suggestedDogId'):
            selected[row['entry']['key']]=row['suggestedDogId']
    return selected

def execute(config,start,end,weekly,commit,limit=0,event_path=None):
    state_dir=Path(config['stateDirectory'])
    if not state_dir.is_dir() or state_dir.is_symlink() or state_dir.stat().st_mode&0o077:raise WorkerError('PRIVATE_STATE_DIRECTORY_REQUIRED')
    with open(state_dir/'worker.lock','a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise WorkerError('IMPORT_ALREADY_RUNNING') from None
        state_path=state_dir/'state.json';state=private_json(state_path) if state_path.exists() else {'schema':1,'runs':{},'events':{}}
        if weekly and state.get('completedThrough'):
            start=min(start,date.fromisoformat(state['completedThrough'])+timedelta(days=1))
        flow=Flow(state_dir/'session.json',config.get('requestDelaySeconds',0.3));api=Api(config)
        report={'startedAt':now(),'from':str(start),'through':str(end),'weekly':weekly,'committed':commit,'events':0,'runs':0,'newSnapshots':0,'newResults':0,'existing':0,'errors':[],'statuses':{}}
        try:
            history={r['sourceUrl']:r for r in api.request('GET','/api/v1/competition-imports') if r['source']=='FLOW_AGILITY'}
            # Existing verified source is used only to obtain the tenant's current licence directory.
            directory=api.request('POST','/api/v1/competition-imports/preview',data={'source':'FLOW_AGILITY','sourceUrl':config['directoryProbeUrl'],'content':flow.get(config['directoryProbeUrl'].removeprefix(ORIGIN))})
            licenses={d['license'].strip() for d in directory['dogs']};report['licensedDogs']=len(licenses)
            if not licenses:raise WorkerError('NO_RSCE_LICENSES')
            fingerprint=hashlib.sha256('\n'.join(sorted(licenses)).encode()).hexdigest()
            catalog=events(flow.get('/zone/events/past_all'),datetime.now(ZoneInfo(config['timeZone'])).date())
            selected=[e for e in catalog if selected_event(e,start,end,weekly)]
            if event_path:selected=[e for e in selected if e['path']==event_path]
            if not selected and not weekly:raise WorkerError('NO_EVENTS_IN_REQUESTED_RANGE')
            if limit:selected=selected[:limit]
            report['events']=len(selected);emit('discovered',events=len(selected),licensedDogs=len(licenses),fromDate=str(start),throughDate=str(end))
            for event in selected:
                ep=event['path']
                try:
                    # Historical completed event navigation is immutable enough to cache; weekly discovery is always fresh.
                    cached=state['events'].get(ep)
                    paths=cached['runs'] if cached and not weekly else flow.discover_runs(ep,date.fromisoformat(event['start']),date.fromisoformat(event['end']))
                    if commit:state['events'][ep]={'runs':paths,'discoveredAt':now()};atomic(state_path,state)
                    for path in paths:
                        source=ORIGIN+path;report['runs']+=1
                        if source in history:report['existing']+=1;continue
                        prior=state['runs'].get(path)
                        if commit and not weekly and prior and prior.get('licensesHash')==fingerprint and prior['status'] in ('no_match','unsupported'):
                            status=prior['status'];report['statuses'][status]=report['statuses'].get(status,0)+1;continue
                        try:
                            content,status,total=result_subset(flow.get(path),licenses,start,end,weekly)
                            if content:
                                preview=api.request('POST','/api/v1/competition-imports/preview',data={'source':'FLOW_AGILITY','sourceUrl':source,'content':content})
                                if preview['sourceUrl']!=source or preview['season']!=str(date.fromisoformat(preview['rows'][0]['entry']['date']).year):raise WorkerError('PREVIEW_SOURCE_MISMATCH')
                                mapping=assignments(preview)
                                if not mapping:raise WorkerError('MATCH_DISAPPEARED')
                                if commit:
                                    # A second read after preview protects existing/manual snapshots.
                                    # Later concurrent inserts are rejected by Core's optimistic revision.
                                    latest=api.request('GET','/api/v1/competition-imports')
                                    existing=next((r for r in latest if r['sourceUrl']==source),None)
                                    if existing:
                                        history[source]=existing;report['existing']+=1;continue
                                    receipt=api.request('POST','/api/v1/competition-imports/'+preview['id']+'/confirm',data={'assignments':mapping})
                                    if receipt['sourceUrl']!=source or receipt['importedRows']!=len(mapping) or receipt['revision']!=1:raise WorkerError('RECEIPT_MISMATCH')
                                    history[source]=receipt;report['newSnapshots']+=1;report['newResults']+=len(mapping);status='imported'
                                    emit('imported',runId=path.split('/')[-2],results=len(mapping),totalResults=report['newResults'])
                                else:status='would_import';report['newResults']+=len(mapping);report['newSnapshots']+=1
                            report['statuses'][status]=report['statuses'].get(status,0)+1
                            if commit:state['runs'][path]={'status':status,'checkedAt':now(),'licensesHash':fingerprint};atomic(state_path,state)
                        except (WorkerError,DiscoveryError) as e:
                            reason=str(e);report['errors'].append({'path':path,'reason':reason});emit('run_failed',runId=path.split('/')[-2],reason=reason)
                            if 'AUTH' in reason or 'TENANT' in reason:raise
                    emit('event_finished',eventId=ep.split('/')[-2],runs=len(paths),newResults=report['newResults'],errors=len(report['errors']))
                    atomic(state_dir/('last-report.json' if commit else 'last-preview.json'),report)
                except (WorkerError,DiscoveryError) as e:
                    report['errors'].append({'path':ep,'reason':str(e)});emit('event_failed',eventId=ep.split('/')[-2],reason=str(e))
                    if 'AUTH' in str(e) or 'TENANT' in str(e):raise
            after=api.request('GET','/api/v1/competition-imports')
            report['totalFlowSnapshots']=sum(r['source']=='FLOW_AGILITY' for r in after)
            report['totalFlowResults']=sum(r['importedRows'] for r in after if r['source']=='FLOW_AGILITY')
            if commit and not report['errors'] and not event_path and not limit:
                state['completedThrough']=str(max(end,date.fromisoformat(state.get('completedThrough',str(end)))))
                atomic(state_path,state)
            report['finishedAt']=now();atomic(state_dir/('last-report.json' if commit else 'last-preview.json'),report)
            emit('finished',**{k:v for k,v in report.items() if k!='errors'},errorCount=len(report['errors']))
            return 1 if report['errors'] else 0
        finally:api.close()

def main():
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--from-date');p.add_argument('--through-date');p.add_argument('--weekly',action='store_true');p.add_argument('--commit',action='store_true');p.add_argument('--limit-events',type=int,default=0);p.add_argument('--event-path');a=p.parse_args()
    config=private_json(a.config)
    if a.weekly:
        if a.from_date or a.through_date:p.error('Weekly dates are computed in club time')
        start,end=window(datetime.now(ZoneInfo(config['timeZone'])).date())
    else:
        if not a.from_date or not a.through_date:p.error('An explicit date range is required')
        start,end=date.fromisoformat(a.from_date),date.fromisoformat(a.through_date)
    today=datetime.now(ZoneInfo(config['timeZone'])).date()
    if start>end or end>=today:raise WorkerError('ONLY_COMPLETED_DATES_ALLOWED')
    return execute(config,start,end,a.weekly,a.commit,a.limit_events,a.event_path)
if __name__=='__main__':
    try:raise SystemExit(main())
    except (WorkerError,DiscoveryError) as e:emit('failed',reason=str(e));raise SystemExit(1)
    except Exception as e:emit('failed',reason=type(e).__name__);raise SystemExit(1)
