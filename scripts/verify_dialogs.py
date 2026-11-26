# -*- coding: utf-8 -*-
"""验证：确认框（移入回收站）+ 新建文件夹对话框 + 校验错误提示。"""
import json, urllib.request, time, base64
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


def right_click(x, y):
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=x, y=y, button="right",
            buttons=2 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(1.0)


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)
print("根目录:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))

# ---------- 1) 空白右键 → 新建文件夹 → 故意填非法名 → 看报错 ----------
blank = js("""(() => {
  const vw = innerWidth, vh = innerHeight;
  for (let y = vh - 40; y > 200; y -= 20) {
    for (let x = 300; x < vw - 100; x += 40) {
      const el = document.elementFromPoint(x, y);
      if (el && (el.classList.contains('list') || el.classList.contains('main'))) return {x, y};
    }
  }
  return null;
})()""")
right_click(blank["x"], blank["y"])
js("""(() => { const it=[...document.querySelectorAll('.ctx-item')].find(x=>x.innerText.trim()==='新建文件夹'); it.click(); })()""")
time.sleep(1.2)
print("\n1) 新建文件夹对话框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))

# 填非法字符
js("""(() => {
  const el = document.querySelector('.modal.modal-sm input');
  const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  s.call(el, 'a/b*c');
  el.dispatchEvent(new Event('input', {bubbles: true}));
})()""")
js("""(() => { const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/创建/.test(x.innerText)); b.click(); })()""")
time.sleep(0.8)
print("   非法名字报错:", js("(() => { const e=document.querySelector('.err-line'); return e ? e.innerText : '（没有报错）'; })()"))

# 填空名
js("""(() => {
  const el = document.querySelector('.modal.modal-sm input');
  const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  s.call(el, '   ');
  el.dispatchEvent(new Event('input', {bubbles: true}));
  const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/创建/.test(x.innerText));
  b.click();
})()""")
time.sleep(0.8)
print("   空名字报错:", js("(() => { const e=document.querySelector('.err-line'); return e ? e.innerText : '（没有报错）'; })()"))

# 填合法名，真的建（名字带时间戳，保证重跑不撞重名）
import time as _t
FOLDER = "ctx-test-%d" % int(_t.time())

js("""(() => {
  const el = document.querySelector('.modal.modal-sm input');
  const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  s.call(el, %s);
  el.dispatchEvent(new Event('input', {bubbles: true}));
  const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/创建/.test(x.innerText));
  b.click();
})()""" % json.dumps(FOLDER))
time.sleep(3)
print("   建完后列表:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))
print("   对话框已关:", js("document.querySelector('.modal.modal-sm') === null"))
err = js("(() => { const e=document.querySelector('.err-line'); return e ? e.innerText : null; })()")
if err:
    print("   !! 报错:", err)

# ---------- 2) 对新文件夹右键 → 移入回收站 → 确认框 ----------
p = js("""(() => {
  const row = [...document.querySelectorAll('.row')].find(x => x.innerText.includes(%s));
  if (!row) return null;
  const b = row.getBoundingClientRect();
  return {x: Math.round(b.x+b.width/2), y: Math.round(b.y+b.height/2)};
})()""" % json.dumps(FOLDER))
right_click(p["x"], p["y"])
js("""(() => { const it=[...document.querySelectorAll('.ctx-item')].find(x=>x.innerText.trim()==='移入回收站'); it.click(); })()""")
time.sleep(1.2)
print("\n2) 确认框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
print("   危险按钮是红的:", js("(() => { const b=document.querySelector('.modal.modal-sm button.btn-danger'); return !!b; })()"))

r = cmd("Page.captureScreenshot", format="png")
open(r"C:\Users\15780\dev\beiming-drive\docs\screenshots\11-confirm-dialog.png", "wb").write(base64.b64decode(r["data"]))

js("""(() => { const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/移入回收站/.test(x.innerText)); b.click(); })()""")
time.sleep(2.5)
print("   删完后列表:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))

# ---------- 3) 回收站里右键 → 恢复 / 彻底删除 ----------
js("""(() => {
  const it = [...document.querySelectorAll('.nav-item')]
      .find(x => (x.querySelector('span')?.innerText || '').trim() === '回收站');
  if (it) it.click();
})()""")
time.sleep(3)
print("\n3) 回收站列表:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))
p2 = js("""(() => {
  const row = [...document.querySelectorAll('.row')].find(x => x.innerText.includes(%s));
  if (!row) return null;
  const b = row.getBoundingClientRect();
  return {x: Math.round(b.x+b.width/2), y: Math.round(b.y+b.height/2)};
})()""" % json.dumps(FOLDER))
if p2:
    right_click(p2["x"], p2["y"])
    print("   回收站右键菜单:", js("JSON.stringify([...document.querySelectorAll('.ctx-item')].map(x=>x.innerText.trim()))"))
    r2 = cmd("Page.captureScreenshot", format="png")
    open(r"C:\Users\15780\dev\beiming-drive\docs\screenshots\12-context-trash.png", "wb").write(base64.b64decode(r2["data"]))
    # 彻底删除
    js("""(() => { const it=[...document.querySelectorAll('.ctx-item')].find(x=>x.innerText.trim()==='彻底删除'); if(it) it.click(); })()""")
    time.sleep(1.5)
    print("   彻底删除确认框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : '（无确认框，直接删了）'; })()"))
    # 如果有确认框就点掉
    js("""(() => { const b=document.querySelector('.modal.modal-sm button.btn-danger, .modal.modal-sm button.btn-primary'); if(b) b.click(); })()""")
    time.sleep(2.5)
    print("   清空后回收站:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))