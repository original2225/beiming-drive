
import json, urllib.request, time
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=60); i=[0]
def cmd(m,**kw):
    i[0]+=1; ws.send(json.dumps({"id":i[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==i[0]: return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    return r.get("result",{}).get("value")
cmd("Page.enable"); cmd("Runtime.enable")
cmd("Page.navigate", url=B+"/"); time.sleep(3)
print(js("""(() => {
  const main=document.querySelector('.main').getBoundingClientRect();
  const out=[];
  for (const sel of ['.topbar','.crumbs','.bar-info','.list','.row-name']) {
    const el=document.querySelector(sel);
    if(!el) { out.push(sel+': 无'); continue; }
    const r=el.getBoundingClientRect();
    out.push(`${sel}: 左距${Math.round(r.x-main.x)} 内容宽${Math.round(r.width)} padding=${getComputedStyle(el).padding}`);
  }
  return out.join('\\n');
})()"""))
