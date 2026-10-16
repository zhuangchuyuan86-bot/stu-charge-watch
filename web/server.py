"""Local Web prototype: imported repository catalogue + normal platform reads."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse,parse_qs,unquote
import argparse,json,mimetypes,socket,threading,time
from platform_client import Provider,DEVICES,STATUS_LABELS

ROOT=Path(__file__).resolve().parent
CATALOG=json.loads((ROOT/'data/catalog.json').read_text(encoding='utf-8'))
SNAPSHOT=json.loads((ROOT/'data/repository-snapshot.json').read_text(encoding='utf-8'))
SNAPSHOTS={item['code']:item for item in SNAPSHOT['data']}
METADATA={item['id']:item for item in CATALOG['devices']}
CLIENT=Provider()
JOB_LOCK=threading.Lock()
JOB={'running':False,'completed':0,'total':0,'started_at':None,'finished_at':None,'ids':[]}
CACHE_FILE=ROOT/'data/last-live.json'

def now():return datetime.now(timezone.utc).isoformat()
def timestamp(value):
    try:return datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()
    except (ValueError,AttributeError,TypeError):return None

def restore():
    try:
        records=json.loads(CACHE_FILE.read_text(encoding='utf-8'))
        for record in records:
            code=record.get('id')
            if code not in METADATA or not isinstance(record.get('ports'),list):continue
            last=timestamp(record.get('last_attempt'))
            record['attempt']=time.monotonic()-max(0,time.time()-(last or 0))
            CLIENT.cache[code]=record
    except (OSError,ValueError,TypeError):pass

def record_for(code):
    meta=METADATA[code]
    cached=CLIENT.cache.get(code)
    live=cached if cached and cached.get('ports') else None
    fallback=SNAPSHOTS.get(code,{})
    if live:
        record={key:value for key,value in live.items() if key!='attempt'}
        record['source']='platform'
    else:
        record={'id':code,'ports':[{**p,'label':STATUS_LABELS.get(p['state'],'未知状态')} for p in fallback.get('ports',[])],'last_success':None,'last_attempt':cached.get('last_attempt') if cached else None,'error':cached.get('error') if cached else None,'source':'repository_snapshot' if fallback else 'unqueried','snapshot_at':CATALOG['repository']['snapshot_at'] if fallback else None}
    success=timestamp(record.get('last_success'))
    age=time.time()-success if success else None
    fresh=record['source']=='platform' and not record.get('error') and age is not None and 0<=age<=120
    ports=record['ports']
    counts={'total':len(ports),'free':sum(p['state']==1 for p in ports),'busy':sum(p['state']==2 for p in ports),'other':sum(p['state'] not in (1,2) for p in ports)}
    return {**meta,**record,'counts':counts,'expected_ports':len(ports) or meta['expected_ports'],'fresh':fresh,'age_seconds':age,'cached':bool(cached and time.monotonic()-cached['attempt']<60)}

def payload():
    records=[record_for(code) for code in DEVICES]
    valid=[r for r in records if r['fresh']]
    with JOB_LOCK:job=dict(JOB)
    return {'devices':records,'locations':CATALOG['locations'],'repository':CATALOG['repository'],'server_time':now(),'minimum_interval_seconds':60,'refresh':job,'stats':{'registered_devices':len(records),'registered_ports':sum(r['expected_ports'] for r in records),'fresh_devices':len(valid),'known_free':sum(r['counts']['free'] for r in valid),'known_busy':sum(r['counts']['busy'] for r in valid),'mapped_locations':len(CATALOG['locations'])}}

def refresh(ids):
    def query(code):
        try:CLIENT.get_station(code)
        finally:
            with JOB_LOCK:JOB['completed']+=1
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(query,ids))
        try:
            saved=[{k:v for k,v in r.items() if k!='attempt'} for r in list(CLIENT.cache.values())]
            temporary=CACHE_FILE.with_suffix('.tmp');temporary.write_text(json.dumps(saved,ensure_ascii=False),encoding='utf-8');temporary.replace(CACHE_FILE)
        except OSError:pass
    finally:
        with JOB_LOCK:JOB.update(running=False,finished_at=now())

def start_refresh(ids):
    with JOB_LOCK:
        if JOB['running']:return False
        JOB.update(running=True,completed=0,total=len(ids),started_at=now(),finished_at=None,ids=ids)
    threading.Thread(target=refresh,args=(ids,),daemon=True).start()
    return True

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed=urlparse(self.path);route=unquote(parsed.path)
        if route=='/api/stations':return self.json(200,payload())
        if route=='/api/catalog':return self.json(200,CATALOG)
        if route=='/health':return self.json(200,{'ok':True,'app':'stu-charge-web','devices':len(DEVICES)})
        if route=='/favicon.ico':route='/assets/icon.svg'
        if route=='/':route='/index.html'
        allowed={'/index.html','/app.js','/styles.css','/manifest.webmanifest','/sw.js','/assets/campus-map.json','/assets/icon.svg'}
        if route not in allowed:return self.json(404,{'error':'Not found'})
        path=ROOT/route.lstrip('/')
        if not path.is_file():return self.json(404,{'error':'Not found'})
        content_type={'.js':'text/javascript','.json':'application/json','.webmanifest':'application/manifest+json','.svg':'image/svg+xml'}.get(path.suffix,mimetypes.guess_type(str(path))[0] or 'application/octet-stream')
        self.respond(200,path.read_bytes(),content_type+';charset=utf-8')

    def do_POST(self):
        if urlparse(self.path).path!='/api/refresh':return self.json(404,{'error':'Not found'})
        origin=self.headers.get('Origin')
        if origin and urlparse(origin).netloc!=self.headers.get('Host'):return self.json(403,{'error':'Origin rejected'})
        try:
            length=int(self.headers.get('Content-Length','0'))
            if length>4096 or length<0:return self.json(413,{'error':'Request too large'})
            data=json.loads(self.rfile.read(length) or b'{}')
            ids=data.get('ids',list(DEVICES))
            if not isinstance(ids,list) or not ids or len(ids)>len(DEVICES) or any(not isinstance(code,str) or code not in METADATA for code in ids):return self.json(400,{'error':'Unknown device'})
            started=start_refresh(list(dict.fromkeys(ids)))
            self.json(202,{'started':started,'refresh':payload()['refresh']})
        except (ValueError,TypeError,AttributeError):self.json(400,{'error':'Invalid request'})

    def json(self,status,data):self.respond(status,json.dumps(data,ensure_ascii=False).encode('utf-8'),'application/json;charset=utf-8')
    def respond(self,status,body,content_type):
        self.send_response(status);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('Referrer-Policy','no-referrer')
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; worker-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers()
        try:self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    def log_message(self,format,*args):pass

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=8766);parser.add_argument('--host',default='0.0.0.0');args=parser.parse_args()
    restore()
    service=ThreadingHTTPServer((args.host,args.port),Handler)
    print('Preview: http://127.0.0.1:'+str(args.port)+'/',flush=True)
    try:
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:sock.connect(('1.1.1.1',80));print('Phone on same LAN: http://'+sock.getsockname()[0]+':'+str(args.port)+'/',flush=True)
    except OSError:pass
    try:service.serve_forever()
    except KeyboardInterrupt:pass
    finally:service.server_close()

if __name__=='__main__':main()
