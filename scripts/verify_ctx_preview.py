# -*- coding: utf-8 -*-
"""进入文档库，对 md 文件右键，确认「在线预览」出现；再点它验证预览真的打开。"""
import json, urllib.request, time, base64, sys
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


def right_click(x, y):
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=x, y=y, button="right",
            buttons=2 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(1.1)


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)

cmd("Page.navigate", url=B + "/")
time.sleep(5)

# 进入「文档库」：双击比较稳（单击只在 dir 上有效，但右键菜单的"打开"也可以）
pos = js("""(() => {
  const row = [...document.querySelectorAll('.row')].find(x => x.innerText.includes('文档库'));
  const b = row.getBoundingClientRect();
  return {x: Math.round(b.x + b.width/2), y: Math.round(b.y + b.height/2)};
})()""")
for t in ("mousePressed", "mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"],
        button="left", buttons=1 if t == "mousePressed" else 0, clickCount=1)
time.sleep(3)
print("进入后:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))

# 找 md 文件
md = js("""(() => {
  const rows = [...document.querySelectorAll('.row')];
  const row = rows.find(x => /\\.md$/i.test(x.querySelector('.row-name')?.innerText || ''));
  if (!row) return {found: false, all: rows.map(r => r.querySelector('.row-name')?.innerText)};
  const b = row.getBoundingClientRect();
  return {found: true, name: row.querySelector('.row-name').innerText,
          x: Math.round(b.x + b.width/2), y: Math.round(b.y + b.height/2)};
})()""")
print("md 文件:", json.dumps(md, ensure_ascii=False))

if md.get("found"):
    right_click(md["x"], md["y"])
    items = js("JSON.stringify([...document.querySelectorAll('.ctx-item')].map(x=>x.innerText.trim()))")
    print("\nmd 文件右键菜单:", items)
    has_preview = "在线预览" in (items or "")
    print("含「在线预览」:", has_preview)

    r = cmd("Page.captureScreenshot", format="png")
    open(r"C:\Users\15780\dev\beiming-drive\docs\screenshots\10-context-preview.png", "wb").write(base64.b64decode(r["data"]))
    print("截图: 10-context-preview.png")

    if has_preview:
        # 点「在线预览」
        js("""(() => {
          const it = [...document.querySelectorAll('.ctx-item')].find(x => x.innerText.trim() === '在线预览');
          if (it) it.click();
        })()""")
        time.sleep(2.5)
        box = js("""(() => {
          const b = document.querySelector('.preview-box, .mask');
          if (!b) return {open: false};
          return {open: true, text: (b.innerText || '').slice(0, 120)};
        })()""")
        print("\n点「在线预览」后:", json.dumps(box, ensure_ascii=False))
        r2 = cmd("Page.captureScreenshot", format="png")
        open(r"C:\Users\15780\dev\beiming-drive\docs\screenshots\10-context-preview-open.png", "wb").write(base64.b64decode(r2["data"]))