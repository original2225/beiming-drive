# -*- coding: utf-8 -*-
"""新建菜单功能验证 v2：按文本精确点菜单项，并用 API 核对建出来的东西到底是什么。"""
import json, urllib.request, time, os
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


def click_text(sel, text, wait=0.6):
    ok = js("""(() => {
      const it = [...document.querySelectorAll(%s)].find(x => x.innerText.trim() === %s);
      if (!it) return false;
      it.click();
      return true;
    })()""" % (json.dumps(sel), json.dumps(text)))
    time.sleep(wait)
    return ok


def open_new_menu():
    js("""(() => { const b=document.querySelector('.new-fab'); if(b) b.click(); })()""")
    time.sleep(0.35)


def fill_and_create(name, wait=4):
    js("""(() => {
      const el = document.querySelector('.modal.modal-sm input');
      if (!el) return false;
      const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
      s.call(el, %s); el.dispatchEvent(new Event('input',{bubbles:true}));
      const b = [...document.querySelectorAll('.modal.modal-sm button')].find(x=>/创建/.test(x.innerText));
      if (b) b.click();
      return true;
    })()""" % json.dumps(name))
    time.sleep(wait)


def api_list():
    return js("""(async () => {
      const t = localStorage.getItem('drive_token');
      if (!t) return {err: 'no-token'};
      const r = await fetch('/api/v1/files?orderBy=name&direction=asc', {headers:{Authorization:'Bearer '+t}});
      const j = await r.json();
      if (!j || !j.data) return {err: 'bad-response', raw: JSON.stringify(j).slice(0,120)};
      return (j.data.items || []).map(x => ({name: x.name, kind: x.kind, size: x.size}));
    })()""")


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=1, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)

# 全新 profile 没有 token，先登录
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
print("token:", bool(js("localStorage.getItem('drive_token')")))

print("起始:", json.dumps(api_list(), ensure_ascii=False))
STAMP = "v2%d" % int(time.time() % 100000)
README = "README-%s.md" % STAMP
FOLDER = "DIR-%s" % STAMP

# ---------- 1) 新建 README ----------
open_new_menu()
print("\n菜单项:", js("JSON.stringify([...document.querySelectorAll('.new-item span')].map(x=>x.innerText.trim()))"))
click_text('.new-item', '新建 README')
print("1) 对话框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
fill_and_create(README, 4)
print("   建完:", json.dumps(api_list(), ensure_ascii=False))
check = js("""(async () => {
  const t = localStorage.getItem('drive_token');
  const l = await (await fetch('/api/v1/files?orderBy=name&direction=asc', {headers:{Authorization:'Bearer '+t}})).json();
  const hit = (l.data.items||[]).find(x => x.name === %s);
  if (!hit) return {found: false};
  const body = await (await fetch('/api/v1/files/' + hit.id + '/preview?access_token=' + t)).text();
  return {found: true, kind: hit.kind, size: hit.size, body: body.slice(0, 60)};
})()""" % json.dumps(README))
print("   核对:", json.dumps(check, ensure_ascii=False))

# ---------- 2) 新建文件夹 ----------
open_new_menu()
click_text('.new-item', '新建文件夹')
print("\n2) 对话框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
fill_and_create(FOLDER, 3)
lst = api_list()
print("   建完:", json.dumps(lst, ensure_ascii=False))
ok_dir = isinstance(lst, list) and any(x.get("name") == FOLDER and x.get("kind") == "dir" for x in lst)
print("   含目录:", ok_dir)


def trash(kw):
    return js("""(async () => {
      const t = localStorage.getItem('drive_token');
      const l = await (await fetch('/api/v1/files?orderBy=name&direction=asc', {headers:{Authorization:'Bearer '+t}})).json();
      const hit = (l.data.items||[]).find(x => x.name === %s);
      if (!hit) return 'not-found';
      const r = await fetch('/api/v1/files/' + hit.id, {method:'DELETE', headers:{Authorization:'Bearer '+t}});
      return r.ok ? 'trashed' : 'http-' + r.status;
    })()""" % json.dumps(kw))


print("\n3) 清理:")
for kw in [README, FOLDER]:
    print("   ", kw, "->", trash(kw))
time.sleep(1.5)
js("location.reload()")
time.sleep(4)
print("\n最终:", json.dumps(api_list(), ensure_ascii=False))