# -*- coding: utf-8 -*-
import json, urllib.request, time, base64, os
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
OUT=r"C:\Users\15780\dev\beiming-drive\docs\screenshots\font"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=90); _id=[0]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==_id[0]: return r.get("result",{})
def shot(name):
    r=cmd("Page.captureScreenshot",format="png")
    f=os.path.join(OUT,name); open(f,"wb").write(base64.b64decode(r["data"])); return f
cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B+"/downloader"); time.sleep(3)
print(shot("60-downloader.png"))
