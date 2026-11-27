# -*- coding: utf-8 -*-
import json, urllib.request, time
from websocket import create_connection

B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=90)
_id=[0]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==_id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR> {str(r['exceptionDetails'])[:300]}"
    return r.get("result",{}).get("value")

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Page.navigate", url=B+"/"); time.sleep(4.5)

print("A) nav-item:nth-child(2) 选中了什么？")
print(js("""(() => {
  const el = document.querySelector('nav-item:nth-child(2)');
  return el ? el.outerHTML.slice(0,150) : 'null（说明前缀选择器写错了）';
})()"""))

print("\nB) 正确的选择器 .nav-item:nth-child(2)：")
print(js("""(() => {
  const el = document.querySelector('.nav-item:nth-child(2)');
  return el ? el.innerText.replace(/\\n/g,'') : 'null';
})()"""))

print("\nC) 第三项中心点上到底是什么元素（详细）：")
print(js("""(() => {
  const n = document.querySelectorAll('.nav-item')[2];
  const r = n.getBoundingClientRect();
  const cx = Math.round(r.x+r.width/2), cy = Math.round(r.y+r.height/2);
  const el = document.elementFromPoint(cx, cy);
  function chain(e){ const out=[]; let x=e; for(let i=0;i<5&&x;i++){out.push(x.tagName+(x.className?'.'+String(x.className).split(' ').join('.'):'')); x=x.parentElement;} return out; }
  return {cx, cy, el: el ? el.tagName+'|'+(el.className||'') : 'null', chain: chain(el)};
})()"""))

print("\nD) 侧栏是否被别的元素盖住（z-index / 遮挡）:")
print(js("""(() => {
  const side = document.querySelector('.side');
  const sr = side.getBoundingClientRect();
  const el = document.elementFromPoint(Math.round(sr.x+sr.width/2), Math.round(sr.y+sr.height/2));
  return {sideZ: getComputedStyle(side).zIndex, covering: el ? el.tagName+'.'+el.className : 'null'};
})()"""))
