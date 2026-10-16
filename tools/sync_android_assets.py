from pathlib import Path
import json,re,shutil

root=Path(__file__).resolve().parents[1]
web=root/'web'
android=root/'android'
assets=android/'assets/web'
(assets/'assets').mkdir(parents=True,exist_ok=True)
catalog=json.loads((web/'data/catalog.json').read_text(encoding='utf-8'))
for relative in ['index.html','app.js','styles.css','assets/campus-map.json','assets/icon.svg']:
    shutil.copyfile(web/relative,assets/relative)
for name,dest in [('catalog.json','catalog.json'),('repository-snapshot.json','snapshot.json'),('device-locations.json','device-locations.json')]:
    shutil.copyfile(web/'data'/name,assets/dest)

html=(assets/'index.html').read_text(encoding='utf-8')
html=html.replace('<script src="app.js" defer></script>','<script src="android.js"></script>\n  <script src="app.js" defer></script>')
html=html.replace('  <link rel="manifest" href="manifest.webmanifest">\n','')
html=html.replace('校园充电 Web 验证版','安卓手机版 · 2.0.4')
html=html.replace('实时状态直接由本地服务查询充电平台。','实时状态由手机直接查询充电平台。').replace('适合先验证的操作','使用方式')
html=html.replace('Web 本地服务需保持电脑开机并联网；页面转入后台后自动更新可能暂停。','手机通过 Wi-Fi 或移动数据独立查询，无需电脑。前台每分钟自动更新；后台提醒由系统安排检查，省电限制可能延迟通知。')
html=html.replace('    <footer>', '''    <section class="native-options" id="native-options" aria-label="更新与提醒设置">
      <label><span><b>前台自动更新</b><small>打开应用时，每分钟更新设备状态</small></span><input id="native-auto" type="checkbox" role="switch"></label>
      <label><span><b>后台空位提醒</b><small>从没有空位变为有空位时通知</small></span><input id="native-watch" type="checkbox" role="switch"></label>
      <p id="native-watch-note">系统约每 15 分钟安排检查，省电限制可能延迟提醒。</p>
    </section>
    <footer>''')
(assets/'index.html').write_text(html,encoding='utf-8')
code=(assets/'app.js').read_text(encoding='utf-8')
code=code.replace("let mapReady=false,toastTimer,pollTimer,lastWidth=0,autoTimer;", "let mapReady=false,toastTimer,pollTimer,lastWidth=0,autoTimer;\n  let pendingNativeDevice=null;")
code=code.replace("    if(mapReady&&locationsChanged)fitMap('campus');", "    if(mapReady&&locationsChanged)fitMap('campus');\n    if(pendingNativeDevice&&all().some(s=>s.id===pendingNativeDevice)){const id=pendingNativeDevice;pendingNativeDevice=null;setPage('devices');openDevice(id);}")
code=code.replace("  setPage(location.hash.slice(1)||'map');bindMap();loadMap();", "  window.addEventListener('charge-open-device',e=>{if(typeof e.detail!=='string')return;pendingNativeDevice=e.detail;if(all().some(s=>s.id===pendingNativeDevice)){const id=pendingNativeDevice;pendingNativeDevice=null;setPage('devices');openDevice(id);}});\n  setPage(location.hash.slice(1)||'map');bindMap();loadMap();")
code=code.replace("  if('serviceWorker' in navigator&&window.isSecureContext)navigator.serviceWorker.register('/sw.js').catch(()=>{});\n",'')
code=code.replace('无法连接本地查询服务，请确认电脑服务仍在运行。','状态更新暂时失败，请检查手机网络后重试。')
code=code.replace('本地服务暂时不可用。请启动文件夹中的 Web 服务。','暂时无法读取状态，请关闭后重新打开应用。')
(assets/'app.js').write_text(code,encoding='utf-8')
with (assets/'styles.css').open('a',encoding='utf-8') as output:
    output.write('''\n.native-options{margin:23px 0 0;padding:6px 19px 15px;background:#fff;border:1px solid var(--line);border-radius:18px}.native-options label{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:15px 0}.native-options label+label{border-top:1px solid var(--line)}.native-options b{display:block;font-size:13px;font-weight:550}.native-options small{display:block;font-size:10px;color:#879582;margin-top:3px}.native-options input{appearance:none;flex-shrink:0;width:35px;height:21px;background:#d5dfce;border-radius:12px;position:relative;cursor:pointer}.native-options input:checked{background:#487c57}.native-options input:before{content:"";position:absolute;top:3px;left:3px;width:15px;height:15px;border-radius:50%;background:#fff}.native-options input:checked:before{transform:translateX(14px)}.native-options p{font-size:10px;color:#96a08e;line-height:1.7}\n''')

station_path=android/'src/com/stu/chargewatch/Station.java'
station=station_path.read_text(encoding='utf-8')
ids=', '.join(json.dumps(d['id']) for d in catalog['devices'])
labels=', '.join(json.dumps(d['display_name'],ensure_ascii=False) for d in catalog['devices'])
station=re.sub(r'    static final String\[\] IDS = \{[^\n]+\};', '    static final String[] IDS = {'+ids+'};',station)
if 'static final String[] NAMES' not in station:
    station=station.replace('    static final String[] LABELS', '    static final String[] NAMES = {'+labels+'};\n    static String name(String id) { for(int i=0;i<IDS.length;i++)if(IDS[i].equals(id))return NAMES[i];return id; }\n    static final String[] LABELS')
else:station=re.sub(r'    static final String\[\] NAMES = \{[^\n]+\};','    static final String[] NAMES = {'+labels+'};',station)
station_path.write_text(station,encoding='utf-8')
print(json.dumps({'bundled_devices':len(catalog['devices']),'locations':len(catalog['locations']),'ports':sum(d['expected_ports'] for d in catalog['devices'])},ensure_ascii=False))
