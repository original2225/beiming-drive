# -*- coding: utf-8 -*-
"""新建菜单功能验证 v3：全部用 CDP 真实鼠标点击（JS 合成 .click() 对 Vue 绑定不可靠）。"""
import json, urllib.request, time
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"

tabs = json.loads(urllib.request.urlopen(CDP + "/json/list", timeout=10).read())
page = next(t for t in tabs if t["type"] == "page")
ws = create_connection(page["webSocketDebuggerUrl"], timeout=60)
i = [0]


def cmd(m, **kw):
    i[0] += 1
    ws.send(json.dumps({"id": i[0], "method": m, "params": kw}))
    while True:
        r = json.loads(ws.recv())
        if r.get("id") == i[0]:
            if "error" in r:
                raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result", {})


def js(e):
    r = cmd("Runtime.evaluate", expression=e, returnByValue=True, awaitPromise=True)
    if "exceptionDetails" in r:
        return f"<ERR> {str(r['exceptionDetails'])[:250]}"
    return r.get("result", {}).get("value")


def real_click_sel(sel, wait=0.6):
    p = js("""(() => {
      const el = document.querySelector(%s);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return {x: Math.round(r.x+r.width/2), y: Math.round(r.y+r.height/2)};
    })()""" % json.dumps(sel))
    if not p:
        return False
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=p["x"], y=p["y"], button="left",
            buttons=1 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(wait)
    return True


def click_new_item(text, wait=0.9):
    """按文字定位菜单项，再用真实坐标点它"""
    p = js("""(() => {
      const it = [...document.querySelectorAll('.new-item')].find(x => x.innerText.includes(%s));
      if (!it) return null;
      const r = it.getBoundingClientRect();
      return {x: Math.round(r.x+r.width/2), y: Math.round(r.y+r.height/2)};
    })()""" % json.dumps(text))
    if not p:
        return False
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=p["x"], y=p["y"], button="left",
            buttons=1 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(wait)
    return True


def fill_and_create(name, wait=4):
    js("""(() => {
      const el = document.querySelector('.modal.modal-sm input');
      if (!el) return false;
      const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
      s.call(el, %s); el.dispatchEvent(new Event('input',{bubbles:true}));
      return true;
    })()""" % json.dumps(name))
    time.sleep(0.3)
    real_click_sel('.modal.modal-sm button.btn-primary, .modal.modal-sm .modal-foot button:last-child', 0.4)
    time.sleep(wait)


def api_list():
    return js("""(async () => {
      const t = localStorage.getItem('drive_token');
      if (!t) return {err:'no-token'};
      const j = await (await fetch('/api/v1/files?orderBy=name&direction=asc',
                      {headers:{Authorization:'Bearer '+t}})).json();
      if (!j || !j.data) return {err:'bad', raw: JSON.stringify(j).slice(0,100)};
      return (j.data.items || []).map(x => ({name:x.name, kind:x.kind, size:x.size}));
    })()""")


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=1, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)

if not js("localStorage.getItem('drive_token')"):
    cmd("Page.navigate", url=B + "/login")
    time.sleep(4)
    js("""(() => {
      const ins = document.querySelectorAll('input');
      const setv=(el,v)=>{const s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));};
      setv(ins[0],'admin@beiming.local'); setv(ins[1],'Container2026!');
      document.querySelector('button.submit')?.click();
    })()""")
    time.sleep(7)
    cmd("Page.navigate", url=B + "/")
    time.sleep(5)

print("起始:", json.dumps(api_list(), ensure_ascii=False))
STAMP = "v3%d" % int(time.time() % 100000)
README = "README-%s.md" % STAMP
FOLDER = "DIR-%s" % STAMP

# ---------- 1) 新建 README ----------
real_click_sel('.new-fab', 0.45)
ok = click_new_item('新建 README', 0.9)
print(f"\n1) 点「新建 README」:", ok)
print("   对话框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
fill_and_create(README, 4)

lst = api_list()
print("   建完列表:", json.dumps(lst, ensure_ascii=False))
if isinstance(lst, list):
    hit = next((x for x in lst if x["name"] == README), None)
    print("   文件已创建:", bool(hit), "| kind:", hit["kind"] if hit else None)
    if hit:
        body = js("""(async () => {
          const t = localStorage.getItem('drive_token');
          const l = await (await fetch('/api/v1/files?orderBy=name&direction=asc',{headers:{Authorization:'Bearer '+t}})).json();
          const h = (l.data.items||[]).find(x=>x.name===%s);
          if(!h) return null;
          return await (await fetch('/api/v1/files/'+h.id+'/preview?access_token='+t)).text();
        })()""" % json.dumps(README))
        print("   文件内容:", repr(body)[:80])

# ---------- 2) 新建文件夹 ----------
real_click_sel('.new-fab', 0.45)
ok2 = click_new_item('新建文件夹', 0.9)
print(f"\n2) 点「新建文件夹」:", ok2)
print("   对话框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
fill_and_create(FOLDER, 3)

lst2 = api_list()
print("   建完列表:", json.dumps(lst2, ensure_ascii=False))
if isinstance(lst2, list):
    print("   目录已创建:",
          any(x.get("name") == FOLDER and x.get("kind") == "dir" for x in lst2))

# ---------- 3) 清理 ----------
def trash(kw):
    return js("""(async () => {
      const t = localStorage.getItem('drive_token');
      const l = await (await fetch('/api/v1/files?orderBy=name&direction=asc',{headers:{Authorization:'Bearer '+t}})).json();
      const h = (l.data.items||[]).find(x=>x.name===%s);
      if(!h) return 'not-found';
      const r = await fetch('/api/v1/files/'+h.id,{method:'DELETE',headers:{Authorization:'Bearer '+t}});
      return r.ok?'trashed':'http-'+r.status;
    })()""" % json.dumps(kw))

print("\n3) 清理:")
for kw in [README, FOLDER]:
    print("   ", kw, "->", trash(kw))
time.sleep(1.5)
js("location.reload()")
time.sleep(4)
print("\n最终:", json.dumps(api_list(), ensure_ascii=False))