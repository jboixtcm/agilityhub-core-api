import unittest
from datetime import date
from unittest.mock import patch,MagicMock
from pathlib import Path
import tempfile
import json
import os
import io,time
from urllib.error import HTTPError
from discovery import date_range,events,merge,render,run_links,DiscoveryError
from worker import execute,window,selected_date,result_subset,assignments,WorkerError,atomic,private_json,Flow,Api

RUNID='11111111-1111-4111-8111-111111111111'
def fixture(license='ABC123',caption='G2 / M / AG',day='2026.11.15'):
    heads=['CLA DO','Guía Perro','Club Licencia','T','F','R','PR','ET','TP','VEL','CAL','PTS']
    row='<div class="flex-row"><div><div>1</div><div>1</div></div><div><div>Fictional Handler</div><div>Fictional Dog</div></div><div><div>Example Club</div><div>'+license+'</div></div>'+''.join('<div>'+s+'</div>' for s in ['40.0','0','0','0','0','0','4.0','EXC','P'])+'</div>'
    return '<html><div class="trial-caption">Example</div><div class="organ-caption">Example - '+day+'</div><div class="results-caption">Resultados - '+caption+'</div><div class="flex-row">'+''.join('<div>'+s+'</div>' for s in heads)+'</div>'+row+'</html>'

class Tests(unittest.TestCase):
    def test_cross_year_ranges(self):
        self.assertEqual(date_range('Dic 30, 2025 - Ene 2, 2026',2026),(date(2025,12,30),date(2026,1,2)))
        self.assertEqual(date_range('Nov 14 - 15',2026),(date(2026,11,14),date(2026,11,15)))
        self.assertEqual(date_range('Oct 31 - Nov 1',2026),(date(2026,10,31),date(2026,11,1)))
        with self.assertRaises(DiscoveryError):date_range('unexpected',2026)
    def test_weekly_window_dst_and_new_year(self):
        for day in (date(2026,3,30),date(2026,10,26),date(2027,1,4)):
            a,b=window(day);self.assertEqual(a.weekday(),5);self.assertEqual(b.weekday(),6)
            self.assertEqual((b-a).days,15);self.assertEqual((day-b).days,1)
    def test_weekly_excludes_weekdays(self):
        self.assertFalse(selected_date(date(2026,11,13),date(2026,11,1),date(2026,11,15),True))
        self.assertTrue(selected_date(date(2026,11,13),date(2026,11,1),date(2026,11,15),False))
    def test_archive_validation(self):
        html='<div class="group mb-6"><span>Nov 14 - 15</span><span>FCI / RSCE</span><span>Example</span><a href="/zone/event/'+RUNID+'/runs"></a></div>'
        self.assertEqual(events(html,date(2026,11,16))[0]['start'],'2026-11-14')
        with self.assertRaises(DiscoveryError):events(html+html,date(2026,11,16))
        with self.assertRaises(DiscoveryError):events('Login',date(2026,11,16))
    def test_live_components_and_updates(self):
        initial={'0':'Loading...','s':['<main>','</main>']}
        diff={'0':{'d':[[1],[2]],'s':['','']},'c':{'1':{'0':'one','s':['<b>','</b>']},'2':{'0':'two','s':1}}}
        state=merge(initial,diff);self.assertEqual(render(state),'<main><b>one</b><b>two</b></main>')
        self.assertEqual(initial['0'],'Loading...')
    def test_live_cycles_fail_closed(self):
        with self.assertRaises(DiscoveryError):render({'0':1,'s':['',''],'c':{'1':{'s':1}}})
    def test_navigation_allowlist(self):
        r,p=run_links('<a href="/zone/run/'+RUNID+'/results#list_anchor_header"></a><a href="https://evil.test/zone/run/'+RUNID+'/results"></a><a href="/zone/runs/ring/'+RUNID+'/date/2026-11-15"></a>')
        self.assertEqual(r,['/zone/prints/run/'+RUNID+'/results']);self.assertEqual(len(p),1)
    def test_exact_licensed_subset(self):
        start,end=date(2026,1,1),date(2026,11,15)
        content,status,total=result_subset(fixture(),{'ABC123'},start,end,False)
        self.assertEqual((status,total),('matched',1));self.assertIn('ABC123',content)
        self.assertEqual(result_subset(fixture(),{'ABC'},start,end,False)[1],'no_match')
        self.assertEqual(result_subset(fixture(day='2025.11.15'),{'ABC123'},start,end,False)[1],'outside_window')
        self.assertEqual(result_subset(fixture(caption='Open'),{'ABC123'},start,end,False)[1],'unsupported')
    def test_explicit_single_official_point_is_preserved(self):
        for point in ('P','PA','PV'):
            html=fixture().replace('<div>P</div>','<div>1'+point+'</div>')
            content,_,_=result_subset(html,{'ABC123'},date(2026,1,1),date(2026,11,15),False)
            self.assertIn('<div>'+point+'</div>',content)
        content,_,_=result_subset(fixture().replace('<div>P</div>','<div>2PV</div>'),{'ABC123'},date(2026,1,1),date(2026,11,15),False)
        self.assertIn('2PV',content) # Core rejects unsupported multiplicity rather than losing points.
    def test_tokens_removed_from_content(self):
        content,_,_=result_subset(fixture()+'<meta name="csrf-token" content="secret"><script>secret</script>',{'ABC123'},date(2026,1,1),date(2026,11,15),False)
        self.assertNotIn('secret',content)
    def test_ambiguous_assignment_rejected(self):
        with self.assertRaises(WorkerError):assignments({'rows':[{'match':'AMBIGUOUS_LICENSE'}]})
        self.assertEqual(assignments({'rows':[{'match':'UNMATCHED','entry':{'key':'x'},'suggestedDogId':'wrong'}]}),{})
        self.assertEqual(assignments({'rows':[{'match':'EXACT_LICENSE','entry':{'key':'x'},'suggestedDogId':'dog'}]}),{'x':'dog'})
    def test_private_files(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'state.json';atomic(p,{'x':1});self.assertEqual(private_json(p),{'x':1})
            p.chmod(0o644)
            with self.assertRaises(WorkerError):private_json(p)
    def test_request_path_guards(self):
        api=Api.__new__(Api)
        with self.assertRaises(WorkerError):api.request('GET','/api/v1/accounts')
        flow=Flow.__new__(Flow)
        with self.assertRaises(WorkerError):flow.get('/zone/../../user/login')
    def test_expired_session_does_not_block_fresh_login(self):
        api=Api.__new__(Api);api.token='expired'
        with patch.object(api,'request',side_effect=WorkerError('API_401_UNAUTHENTICATED')):
            api.close()
        self.assertIsNone(api.token)
    def test_unauthorized_write_retries_once_with_same_idempotency_key(self):
        api=Api.__new__(Api);api.token='expired';api.expires=time.monotonic()+100;api.origin='https://example.test';api.http=MagicMock()
        error=HTTPError('https://example.test',401,'Unauthorized',{},io.BytesIO(b'{"code":"UNAUTHENTICATED"}'))
        response=MagicMock();response.__enter__.return_value.read.return_value=b'{"ok":true}'
        api.http.open.side_effect=[error,response]
        with patch.object(api,'login') as login:
            self.assertEqual(api.request('POST','/api/v1/competition-imports/'+RUNID+'/confirm',data={'assignments':{'x':'y'}}),{'ok':True})
            login.assert_called_once()
        requests=[c.args[0] for c in api.http.open.call_args_list]
        self.assertEqual(requests[0].get_header('Idempotency-key'),requests[1].get_header('Idempotency-key'))
    def test_api_tenant_guard(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'account';atomic(p,{'accounts':[{'role':'ADMIN','username':'example@example.test','password':'fake'}]})
            with patch.object(Api,'request',side_effect=[{'access_token':'fake'},{'id':'different','modules':['STATS']}]):
                with self.assertRaisesRegex(WorkerError,'TENANT'):Api({'apiOrigin':'https://example.test','apiAccountsFile':str(p),'clubId':'expected'})

class ExecutionTests(unittest.TestCase):
    def scenario(self, concurrent=False, fail=False, weekly=False, completed=None):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);state=Path(temp.name);state.chmod(0o700)
        if completed:atomic(state/'state.json',{'schema':1,'events':{},'runs':{},'completedThrough':completed})
        path='/zone/prints/run/'+RUNID+'/results';source='https://www.flowagility.com'+path
        flow=MagicMock();flow.get.return_value=fixture();flow.discover_runs.return_value=[path]
        preview={'id':RUNID,'sourceUrl':source,'season':'2026','dogs':[{'license':'ABC123'}], 'rows':[{'match':'EXACT_LICENSE','suggestedDogId':'dog','entry':{'key':'result','date':'2026-11-15'}}]}
        receipt={'source':'FLOW_AGILITY','sourceUrl':source,'importedRows':1,'revision':1}
        api=MagicMock();history_reads=0;confirmed=[]
        def request(method,url,**kwargs):
            nonlocal history_reads
            if url=='/api/v1/competition-imports':
                history_reads+=1
                return [receipt] if confirmed or (concurrent and history_reads>1) else []
            if url.endswith('/preview'):return preview
            if url.endswith('/confirm'):
                if fail:raise WorkerError('API_409_STALE_VERSION')
                confirmed.append(True);return receipt
            raise AssertionError(url)
        api.request.side_effect=request
        event={'path':'/zone/event/'+RUNID+'/runs','federation':'FCI / RSCE','start':'2026-11-15','end':'2026-11-15'}
        config={'stateDirectory':str(state),'directoryProbeUrl':source,'timeZone':'Europe/Madrid'}
        with patch('worker.Flow',return_value=flow),patch('worker.Api',return_value=api),patch('worker.events',return_value=[event]),patch('worker.emit'):
            result=execute(config,date(2026,11,1) if weekly else date(2026,1,1),date(2026,11,15),weekly,True)
        return result,json.loads((state/'last-report.json').read_text()),confirmed,state
    def test_commit_receipt_and_checkpoint(self):
        result,report,confirmed,state=self.scenario()
        self.assertEqual(result,0);self.assertEqual(report['newResults'],1);self.assertEqual(len(confirmed),1)
        self.assertEqual(next(iter(private_json(state/'state.json')['runs'].values()))['status'],'imported')
    def test_concurrent_manual_snapshot_is_preserved(self):
        result,report,confirmed,_=self.scenario(concurrent=True)
        self.assertEqual(result,0);self.assertFalse(confirmed);self.assertEqual(report['existing'],1)
    def test_weekly_recovers_missed_occurrences(self):
        result,report,_,state=self.scenario(weekly=True,completed='2026-10-11')
        self.assertEqual(result,0);self.assertEqual(report['from'],'2026-10-12')
        self.assertEqual(private_json(state/'state.json')['completedThrough'],'2026-11-15')
    def test_failed_confirmation_remains_retryable(self):
        result,report,confirmed,state=self.scenario(fail=True)
        self.assertEqual(result,1);self.assertFalse(confirmed);self.assertEqual(len(report['errors']),1)
        self.assertFalse(private_json(state/'state.json')['runs'])

if __name__=='__main__':unittest.main()
