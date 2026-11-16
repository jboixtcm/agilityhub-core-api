"""Bounded, read-only discovery of authenticated Flow event and run pages."""
from copy import deepcopy
from datetime import date
import re
from bs4 import BeautifulSoup

UUID = r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}'
RUN = re.compile(r'/zone/(?:prints/)?run/('+UUID+r')/results(?:#list_anchor_header)?$')
PAGE = re.compile(r'/zone/runs/ring/'+UUID+r'/date/\d{4}-\d{2}-\d{2}$')
MONTHS = {name:i+1 for i,name in enumerate(('Ene','Feb','Mar','Abr','May','Jun','Jul','Ago','Sep','Oct','Nov','Dic'))}
MONTHS.update({name:i+1 for i,name in enumerate(('Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'))})

class DiscoveryError(Exception): pass

def date_range(label, year):
    halves = label.split(' - ')
    explicit = re.findall(r', (20\d{2})',label)
    end_year = int(explicit[-1]) if explicit else year
    def part(s, inherited_month=None, inherited_year=end_year):
        m = re.fullmatch(r'(?:(\w+) )?(\d{1,2})(?:, (20\d{2}))?',s.strip())
        if not m or (not m[1] and inherited_month is None): raise DiscoveryError('Unknown event date format')
        return date(int(m[3] or inherited_year),MONTHS[m[1]] if m[1] else inherited_month,int(m[2]))
    start = part(halves[0]);end=part(halves[-1],start.month)
    if start>end and not re.search(r', 20\d{2}',halves[0]): start=start.replace(year=end.year-1)
    if start>end:raise DiscoveryError('Reversed event range')
    return start,end

def events(html, today):
    doc=BeautifulSoup(html,'html.parser');result=[]
    for card in doc.select('div.group.mb-6'):
        link=card.find('a',href=re.compile(r'^/zone/event/'+UUID+r'/runs$'))
        if not link:continue
        texts=list(card.stripped_strings)
        if len(texts)<3:raise DiscoveryError('Incomplete event card')
        start,end=date_range(texts[0],today.year)
        result.append({'path':link['href'],'start':start.isoformat(),'end':end.isoformat(),'federation':texts[1]})
    if not result:raise DiscoveryError('Empty archive or authentication required')
    if len({r['path'] for r in result})!=len(result):raise DiscoveryError('Duplicate event')
    return result

def merge(old, diff):
    if not isinstance(old,dict) or not isinstance(diff,dict) or 's' in diff:return deepcopy(diff)
    result=deepcopy(old)
    for k,v in diff.items(): result[k]=merge(result.get(k),v)
    return result

def render(tree):
    components=tree.get('c',{})
    def component(key,seen=()):
        if str(key) in seen:raise DiscoveryError('Cyclic live component')
        value=deepcopy(components[str(key)]);static=value.get('s')
        if isinstance(static,int):
            template=component(abs(static),seen+(str(key),));del value['s'];value=merge(template,value)
        return value
    def node(v,templates=None):
        if v is None:return ''
        if isinstance(v,str):return v
        if isinstance(v,int):return node(component(v))
        if not isinstance(v,dict):raise DiscoveryError('Unsupported live rendering')
        templates=v.get('p',templates)
        statics=v.get('s')
        if isinstance(statics,int) and templates:statics=templates[str(statics)]
        if not isinstance(statics,list):raise DiscoveryError('Missing live template')
        def interpolate(row):
            return ''.join(s+(node(row.get(str(i)),templates) if i<len(statics)-1 else '') for i,s in enumerate(statics))
        if 'd' in v:return ''.join(interpolate({str(i):x for i,x in enumerate(row)}) for row in v['d'])
        return interpolate(v)
    return node(tree)

def run_links(html):
    doc=BeautifulSoup(html,'html.parser');runs=set();pages=set()
    for a in doc.select('a[href]'):
        path=a['href'];match=RUN.fullmatch(path)
        if match:runs.add('/zone/prints/run/'+match[1]+'/results')
        if PAGE.fullmatch(path):pages.add(path)
    return sorted(runs),sorted(pages)
