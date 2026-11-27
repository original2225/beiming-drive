# -*- coding: utf-8 -*-
import json, urllib.request, time, base64, os
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
OUT=r"C:\Users\15780\dev\beiming-drive\docs\screenshots\font"
os.makedirs(OUT, exist_ok=True)
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=90); _id=[0]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==_id[0]: return r.get("result",{})
def shot(name, clip=None):
    kw={"format":"png"}
    if clip: kw["clip"]={**clip,"scale":2}
    r=cmd("Page.captureScreenshot",**kw)
    f=os.path.join(OUT,name); open(f,"wb").write(base64.b64decode(r["data"])); return f

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)

for path, name in [("/", "50-files.png"), ("/downloader", "51-downloader.png"),
                   ("/?view=trash", "52-trash.png")]:
    cmd("Page.navigate", url=B+path); time.sleep(2.8)
    print(name, shot(name))

# 侧栏特写
cmd("Page.navigate", url=B+"/"); time.sleep(2.5)
side = """(() => { const s=document.querySelector('.side').getBoundingClientRect();
  return {x:Math.round(s.x), y:Math.round(s.y), width:Math.round(s.width), height:Math.round(s.height)}; })()"""
r = cmd("Runtime.evaluate", expression=side, returnByValue=True)["result"]["value"]
print("side", shot("53-side.png", r))
