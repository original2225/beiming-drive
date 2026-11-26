# -*- coding: utf-8 -*-
"""最终验收：清理测试残留 → 三种右键上下文 → 弹窗 → 截图。"""
import json, urllib.request, time, base64, sys
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots"

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


def right_click(x, y):
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=x, y=y, button="right",
            buttons=2 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(1.0)


def shot(name):
    r = cmd("Page.captureScreenshot", format="png")
    p = rf"{OUT}\{name}"
    open(p, "wb").write(base64.b64decode(r["data"]))
    return p


def row_pos(kw):
    return js("""(() => {
      const row = [...document.querySelectorAll('.row')].find(x => x.innerText.includes(%s));
      if (!row) return null;
      const b = row.getBoundingClientRect();
      return {x: Math.round(b.x+b.width/2), y: Math.round(b.y+b.height/2)};
    })()""" % json.dumps(kw))


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Network.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)

# 登录（新 profile 可能没有 token）
cmd("Page.navigate", url=B + "/")
time.sleep(5)
if not js("localStorage.getItem('drive_token') || localStorage.getItem('token')"):
    cmd("Page.navigate", url=B + "/login")
    time.sleep(4)
    js("""(() => {
      const ins = document.querySelectorAll('input');
      const setv = (el, v) => {
        const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        s.call(el, v); el.dispatchEvent(new Event('input', {bubbles:true}));
      };
      setv(ins[0], 'admin@beiming.local'); setv(ins[1], 'Container2026!');
      const form = ins[0].closest('form');
      [...(form || document).querySelectorAll('button')].find(b => /登录/.test(b.innerText)).click();
    })()""")
    time.sleep(6)
    cmd("Page.navigate", url=B + "/")
    time.sleep(5)

print("根目录:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))

# ---------- 清理回收站里的 ctx-test* 残留 ----------
js("""(() => {
  const it = [...document.querySelectorAll('.nav-item')]
      .find(x => (x.querySelector('span')?.innerText || '').trim() === '回收站');
  if (it) it.click();
})()""")
time.sleep(3)
trash = js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))")
print("回收站:", trash)

cleaned = 0
for _ in range(10):
    p = row_pos("ctx-test")
    if not p:
        break
    right_click(p["x"], p["y"])
    js("""(() => { const it=[...document.querySelectorAll('.ctx-item')].find(x=>x.innerText.trim()==='彻底删除'); if(it) it.click(); })()""")
    time.sleep(1.0)
    js("""(() => { const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/彻底删除/.test(x.innerText)); if(b) b.click(); })()""")
    time.sleep(2.0)
    cleaned += 1
print(f"清掉 {cleaned} 个测试残留 →", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))

# ---------- 回我的文件，正式截图 ----------
js("""(() => {
  const it = [...document.querySelectorAll('.nav-item')]
      .find(x => (x.querySelector('span')?.innerText || '').trim() === '我的文件');
  if (it) it.click();
})()""")
time.sleep(3.5)
print("回到根目录:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))

# 1) 文件行右键
p = row_pos("北冥整合包")
right_click(p["x"], p["y"])
print("\n▶ 文件行:", js("JSON.stringify([...document.querySelectorAll('.ctx-item')].map(x=>x.innerText.trim()))"))
print("  菜单不出界:", js("(() => { const m=document.querySelector('.ctx-menu'); const r=m.getBoundingClientRect(); return r.right<=innerWidth && r.bottom<=innerHeight; })()"))
shot("08-context-menu.png")
js("document.body.click()")
time.sleep(0.6)

# 2) 文件夹行右键
p = row_pos("文档库")
right_click(p["x"], p["y"])
print("▶ 文件夹行:", js("JSON.stringify([...document.querySelectorAll('.ctx-item')].map(x=>x.innerText.trim()))"))
js("document.body.click()")
time.sleep(0.6)

# 3) 空白右键 → 截图
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
print("▶ 空白处:", js("JSON.stringify([...document.querySelectorAll('.ctx-item')].map(x=>x.innerText.trim()))"))
shot("13-context-blank.png")
js("document.body.click()")
time.sleep(0.6)

# 4) 重命名弹窗截图
p = row_pos("北冥整合包")
right_click(p["x"], p["y"])
js("""(() => { const it=[...document.querySelectorAll('.ctx-item')].find(x=>x.innerText.trim()==='重命名'); it.click(); })()""")
time.sleep(1.5)
print("▶ 重命名弹窗:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
shot("14-rename-dialog.png")
js("""(() => { const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/取消/.test(x.innerText)); if(b) b.click(); })()""")
time.sleep(0.6)

# 5) 移入回收站确认框截图（用确认框的取消，不改数据）
p = row_pos("北冥整合包")
right_click(p["x"], p["y"])
js("""(() => { const it=[...document.querySelectorAll('.ctx-item')].find(x=>x.innerText.trim()==='移入回收站'); it.click(); })()""")
time.sleep(1.5)
print("▶ 确认框:", js("(() => { const b=document.querySelector('.modal.modal-sm'); return b ? b.innerText.replace(/\\n+/g,' | ') : null; })()"))
shot("11-confirm-dialog.png")
js("""(() => { const b=[...document.querySelectorAll('.modal.modal-sm button')].find(x=>/取消/.test(x.innerText)); if(b) b.click(); })()""")
time.sleep(0.6)
print("\n最终根目录（未改动）:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))