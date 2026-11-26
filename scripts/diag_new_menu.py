# -*- coding: utf-8 -*-
"""诊断：点新建菜单项后到底有没有触发 newAction。用真实鼠标坐标点击。"""
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
        return f"<ERR> {str(r['exceptionDetails'])[:200]}"
    return r.get("result", {}).get("value")


def real_click(x, y, wait=0.6):
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=x, y=y, button="left",
            buttons=1 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(wait)


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=1, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)
print("token:", bool(js("localStorage.getItem('drive_token')")))

# 装探针：记录 newAction 有没有被调用
js("""(() => {
  window.__probe = [];
  document.addEventListener('click', (e) => {
    const it = e.target.closest && e.target.closest('.new-item');
    window.__probe.push({
      tag: e.target.tagName + '.' + (e.target.className || ''),
      inNewItem: !!it,
      itemText: it ? it.innerText.trim() : null,
      inNewMenu: !!e.target.closest('.new-menu'),
      menuExists: !!document.querySelector('.new-menu'),
    });
  }, true);
  return 'ok';
})()""")

# 打开新建菜单（真实点击）
fab = js("(() => { const b=document.querySelector('.new-fab'); if(!b) return null; const r=b.getBoundingClientRect(); return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()")
print("fab 坐标:", fab)
real_click(fab["x"], fab["y"], 0.5)
print("菜单打开:", js("!!document.querySelector('.new-menu')"))

items = js("""(() => [...document.querySelectorAll('.new-item')].map(x => {
  const r = x.getBoundingClientRect();
  return {text: x.innerText.trim(), x: Math.round(r.x+r.width/2), y: Math.round(r.y+r.height/2),
          w: Math.round(r.width), h: Math.round(r.height)};
}))()""")
print("菜单项坐标:", json.dumps(items, ensure_ascii=False))

# 真实点击「新建 README」
target = next((it for it in items if "README" in it["text"]), None)
if target:
    real_click(target["x"], target["y"], 0.9)
    print("\n点击后探针:", json.dumps(js("window.__probe"), ensure_ascii=False, indent=1))
    print("对话框出现:", js("document.querySelector('.modal.modal-sm') !== null"))
    print("对话框内容:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
    print("菜单还开着:", js("!!document.querySelector('.new-menu')"))