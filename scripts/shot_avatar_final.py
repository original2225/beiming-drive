# -*- coding: utf-8 -*-
"""抓两张图：展开态（头像可点 + 相机遮罩）与悬停态。"""
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

def shot(name):
    r = cmd("Page.captureScreenshot", format="png")
    f = os.path.join(OUT, name)
    with open(f, "wb") as fh: fh.write(base64.b64decode(r["data"]))
    return f

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)

if not js("document.querySelector('.rail').classList.contains('open')"):
    js("document.querySelector('.mini-avatar')?.click()")
    time.sleep(0.9)

print("有头像图片:", js("!!document.querySelector('.rail-avatar img')"))
print("图片是否真的加载出来:", js("""(() => {
  const img = document.querySelector('.rail-avatar img');
  return img ? (img.complete && img.naturalWidth > 0) : false;
})()"""))
print("自然尺寸:", js("""(() => {
  const img = document.querySelector('.rail-avatar img');
  return img ? img.naturalWidth + 'x' + img.naturalHeight : null;
})()"""))

# 悬停
pos = js("""(() => { const el=document.querySelector('.rail-avatar');
  const r=el.getBoundingClientRect();
  return {x: Math.round(r.x+r.width/2), y: Math.round(r.y+r.height/2)}; })()""")
cmd("Input.dispatchMouseEvent", type="mouseMoved", x=pos["x"], y=pos["y"])
time.sleep(0.45)
print("悬停遮罩不透明度:", js("getComputedStyle(document.querySelector('.avatar-cam')).opacity"))
print(shot("07-avatar-final-hover.png"))

# 裁一张只含右栏的近景，方便看清
r = cmd("Page.captureScreenshot", format="png",
        clip={"x": 1140, "y": 0, "width": 300, "height": 300, "scale": 2})
f2 = os.path.join(OUT, "08-avatar-closeup.png")
with open(f2, "wb") as fh: fh.write(base64.b64decode(r["data"]))
print(f2)
