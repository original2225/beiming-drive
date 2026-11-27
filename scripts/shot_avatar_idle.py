# -*- coding: utf-8 -*-
"""抓未悬停状态：图片应该清晰可见，遮罩不可见。"""
import json, urllib.request, time, base64, os
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots\avatar"
os.makedirs(OUT, exist_ok=True)

tabs = json.loads(urllib.request.urlopen(CDP + "/json/list", timeout=10).read())
page = next(t for t in tabs if t["type"] == "page")
ws = create_connection(page["webSocketDebuggerUrl"], timeout=90)
_id = [0]

def cmd(m, **kw):
    _id[0] += 1
    ws.send(json.dumps({"id": _id[0], "method": m, "params": kw}))
    while True:
        r = json.loads(ws.recv())
        if r.get("id") == _id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result", {})

def js(e):
    r = cmd("Runtime.evaluate", expression=e, returnByValue=True, awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR> {str(r['exceptionDetails'])[:200]}"
    return r.get("result", {}).get("value")

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)

if not js("document.querySelector('.rail').classList.contains('open')"):
    js("document.querySelector('.mini-avatar')?.click()")
    time.sleep(0.9)

# 把鼠标移到远处，确保不是悬停态
cmd("Input.dispatchMouseEvent", type="mouseMoved", x=700, y=600)
time.sleep(0.5)

print("图片已加载:", js("""(() => {
  const i = document.querySelector('.rail-avatar img');
  return i ? (i.complete && i.naturalWidth > 0) : false;
})()"""))
print("未悬停遮罩透明度（应 0）:", js("getComputedStyle(document.querySelector('.avatar-cam')).opacity"))
print("图片 URL:", js("document.querySelector('.rail-avatar img')?.getAttribute('src')"))

# 近景裁剪（未悬停）
r = cmd("Page.captureScreenshot", format="png",
        clip={"x": 1170, "y": 8, "width": 110, "height": 90, "scale": 4})
f = os.path.join(OUT, "09-avatar-idle-closeup.png")
with open(f, "wb") as fh: fh.write(base64.b64decode(r["data"]))
print(f)
