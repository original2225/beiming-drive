# -*- coding: utf-8 -*-
import json, urllib.request, time
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=90); _id=[0]

logs=[]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("method")=="Log.entryAdded":
            logs.append(r["params"]["entry"])
            continue
        if r.get("method")=="Runtime.consoleAPICalled":
            logs.append({"level":r["params"].get("type"),"text":" ".join(str(a.get("value","")) for a in r["params"].get("args",[]))})
            continue
        if r.get("method")=="Runtime.exceptionThrown":
            d=r["params"]["exceptionDetails"]
            logs.append({"level":"exception","text":(d.get("text","")+" "+(d.get("exception",{}) or {}).get("description",""))[:400]})
            continue
        if r.get("id")==_id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR> {str(r['exceptionDetails'])[:400]}"
    return r.get("result",{}).get("value")

cmd("Page.enable"); cmd("Runtime.enable"); cmd("Log.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Page.navigate", url=B+"/")
time.sleep(5)

print("=== 控制台/异常 ===")
for l in logs[-15:]:
    print(" ", l.get("level"), "|", (l.get("text") or "")[:260])

print("\n=== DOM ===")
print(js("""(() => { function walk(el,d){ if(d>4||!el)return ''; const r=el.getBoundingClientRect();
 let s='  '.repeat(d)+el.tagName.toLowerCase()+(el.className?'.'+String(el.className).split(' ').filter(Boolean).join('.'):'')+` [${Math.round(r.width)}x${Math.round(r.height)}]`+'\\n';
 for(const c of el.children) s+=walk(c,d+1); return s;} return walk(document.body,0); })()"""))
