# -*- coding: utf-8 -*-
"""截图：三个内页 + 一次切换中间态。"""
import json, urllib.request, time, base64, os
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
OUT=r"C:\Users\15780\dev\beiming-drive\docs\screenshots\transition"
os.makedirs(OUT, exist_ok=True)
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=90); _id=[0]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==_id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return None
    return r.get("result",{}).get("value")
def shot(name):
    r=cmd("Page.captureScreenshot",format="png")
    f=os.path.join(OUT,name)
    with open(f,"wb") as fh: fh.write(base64.b64decode(r["data"]))
    return f

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)

for path, name in [("/", "10-files.png"), ("/shares", "11-shares.png"), ("/downloader", "12-downloader.png")]:
    cmd("Page.navigate", url=B+path); time.sleep(2.2)
    print(name, shot(name))

# 切换中间态：点分享后 130ms 抓一张，应能看到旧内容淡出/新内容浮入
cmd("Page.navigate", url=B+"/"); time.sleep(2.5)
pos=js("""(() => { const n=document.querySelectorAll('.nav-item')[1]; const r=n.getBoundingClientRect();
  return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
        buttons=1 if t=="mousePressed" else 0, clickCount=1)
time.sleep(0.13)
print("mid", shot("13-transition-mid.png"))
time.sleep(1.2)
print("end", shot("14-after.png"))
