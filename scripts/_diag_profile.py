
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
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:300]}"
    return r.get("result",{}).get("value")
cmd("Page.enable"); cmd("Runtime.enable")
cmd("Page.navigate", url=B+"/profile"); time.sleep(3.5)
print("path:", js("location.pathname"))
print("\nDOM:")
print(js("""(() => { function walk(el,d){ if(d>5||!el)return ''; const r=el.getBoundingClientRect();
 let s='  '.repeat(d)+el.tagName.toLowerCase()+(el.className?'.'+String(el.className).split(' ').filter(Boolean).join('.'):'')
   +` [${Math.round(r.width)}x${Math.round(r.height)}]`+(el.tagName==='INPUT'?' val='+(el.value||''):'')+'\\n';
 for(const c of el.children) s+=walk(c,d+1); return s;} return walk(document.querySelector('.page'),0); })()"""))
