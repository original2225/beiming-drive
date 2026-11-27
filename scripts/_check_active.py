
import json, urllib.request, time
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=90); _id=[0]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==_id[0]: return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:200]}"
    return r.get("result",{}).get("value")
cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
for path in ["/", "/shares", "/storage", "/profile"]:
    cmd("Page.navigate", url=B+path); time.sleep(2.6)
    act = js("Array.from(document.querySelectorAll('.nav-item')).map(e=>e.classList.contains('on'))")
    user_on = js("document.querySelector('.user-btn')?.classList.contains('on')")
    print(f"{path:12} 导航高亮={act}  用户区高亮={user_on}")
