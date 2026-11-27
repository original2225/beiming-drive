
import json, urllib.request
from websocket import create_connection
CDP="http://127.0.0.1:9444"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=60); i=[0]
def cmd(m,**kw):
    i[0]+=1; ws.send(json.dumps({"id":i[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==i[0]: return r.get("result",{})
cmd("Runtime.enable")
r=cmd("Runtime.evaluate",expression="localStorage.getItem('drive_token')||''",returnByValue=True)
print(r["result"]["value"])
