# -*- coding: utf-8 -*-
"""验新建菜单的动作真的生效：新建 README 建出文件、新建文件夹建出目录、上传文件夹逐级建目录。"""
import json, urllib.request, time, base64, os
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


def click_sel(sel, wait=0.5):
    pos = js("""(() => {
      const el = document.querySelector(%s);
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return {x: Math.round(b.x + b.width/2), y: Math.round(b.y + b.height/2)};
    })()""" % json.dumps(sel))
    if not pos:
        return False
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"],
            button="left", buttons=1 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(wait)
    return True


def names():
    return js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))")


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=1, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)
print("起始:", names())

STAMP = "rail%d" % int(time.time() % 100000)

# ---------- 1) 新建 README ----------
click_sel(".new-fab", 0.35)
click_sel('.new-item:nth-child(4)', 0.8)   # 新建 README
dlg = js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()")
print("\n1) README 对话框:", dlg)
js("""(() => {
  const el = document.querySelector('.modal.modal-sm input');
  const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
  s.call(el, %s); el.dispatchEvent(new Event('input',{bubbles:true}));
  const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/创建/.test(x.innerText));
  if(b) b.click();
})()""" % json.dumps("README-%s.md" % STAMP))
time.sleep(3)
print("   建完列表:", names())
print("   对话框关闭:", js("document.querySelector('.modal.modal-sm') === null"))

# ---------- 2) 新建文件夹（走菜单） ----------
click_sel(".new-fab", 0.35)
click_sel('.new-item:nth-child(5)', 0.8)   # 新建文件夹（第5个子元素：3个 item + sep + 2）—— 用文本定位更稳
if js("document.querySelector('.modal.modal-sm') === null"):
    # 上面点空了，用文本精确点
    js("""(() => {
      const it = [...document.querySelectorAll('.new-item')].find(x => x.innerText.trim() === '新建文件夹');
      if (it) it.click();
    })()""")
    time.sleep(0.8)
print("\n2) 文件夹对话框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
js("""(() => {
  const el = document.querySelector('.modal.modal-sm input');
  const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
  s.call(el, %s); el.dispatchEvent(new Event('input',{bubbles:true}));
  const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/创建/.test(x.innerText));
  if(b) b.click();
})()""" % json.dumps("DIR-%s" % STAMP))
time.sleep(3)
after = names()
print("   建完列表:", after)
print("   含新目录:", ("DIR-%s" % STAMP) in (after or ""))

# ---------- 3) 清理：把这次建的东西删掉 ----------
def right_click_row(kw):
    p = js("""(() => {
      const row = [...document.querySelectorAll('.row')].find(x => x.innerText.includes(%s));
      if (!row) return null;
      const b = row.getBoundingClientRect();
      return {x: Math.round(b.x+b.width/2), y: Math.round(b.y+b.height/2)};
    })()""" % json.dumps(kw))
    if not p: return False
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=p["x"], y=p["y"], button="right",
            buttons=2 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(1.0)
    return True

cleaned = []
for kw in ["README-%s.md" % STAMP, "DIR-%s" % STAMP]:
    if right_click_row(kw):
        js("""(() => { const it=[...document.querySelectorAll('.ctx-item')].find(x=>x.innerText.trim()==='移入回收站'); if(it) it.click(); })()""")
        time.sleep(1.0)
        js("""(() => { const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/移入回收站/.test(x.innerText)); if(b) b.click(); })()""")
        time.sleep(2.0)
        cleaned.append(kw)
print("\n3) 已送回收站:", cleaned)
print("   最终列表:", names())