# -*- coding: utf-8 -*-
"""抓上传按钮的 3 个关键时刻：正常态 / 收缩中 / 消失后。"""
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
def shot(name, clip=None):
    kw={"format":"png"}
    if clip: kw["clip"]={**clip,"scale":2}
    r=cmd("Page.captureScreenshot",**kw)
    f=os.path.join(OUT,name)
    with open(f,"wb") as fh: fh.write(base64.b64decode(r["data"]))
    return f

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B+"/"); time.sleep(3.5)

side=js("""(() => { const s=document.querySelector('.side').getBoundingClientRect();
  return {x:Math.round(s.x), y:Math.round(s.y), width:Math.round(s.width), height:Math.round(s.height)}; })()""")
print("侧栏区域:", side)

print("正常态:", shot("20-btn-normal.png", side))

pos=js("""(() => { const n=document.querySelectorAll('.nav-item')[1]; const r=n.getBoundingClientRect();
  return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
        buttons=1 if t=="mousePressed" else 0, clickCount=1)
time.sleep(0.10)
print("收缩中(100ms):", shot("21-btn-collapsing.png", side))
time.sleep(1.2)
print("消失后:", shot("22-btn-gone.png", side))
print("最终按钮存在:", js("!!document.querySelector('.new-btn')"))
