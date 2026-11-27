# -*- coding: utf-8 -*-
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
        if r.get("id")==_id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR> {str(r['exceptionDetails'])[:300]}"
    return r.get("result",{}).get("value")

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Page.navigate", url=B+"/"); time.sleep(4)

print("DOM 层级：")
print(js("""(() => {
  const shell = document.querySelector('.shell');
  function walk(el, d) {
    if (d > 3 || !el) return '';
    const r = el.getBoundingClientRect();
    let s = '  '.repeat(d) + el.tagName.toLowerCase()
      + (el.className ? '.' + String(el.className).split(' ').filter(Boolean).join('.') : '')
      + `  [${Math.round(r.x)},${Math.round(r.y)} ${Math.round(r.width)}x${Math.round(r.height)}]`
      + (el.__vnode || el.__vueParentComponent ? ' <vue>' : '') + '\\n';
    for (const c of el.children) s += walk(c, d+1);
    return s;
  }
  return walk(document.body, 0);
})()"""))

print("\n.shell 和 .main 是否带过渡类（切换瞬间）:")
js("window.__s=[]; window.__t=performance.now(); (() => { const tick=()=>{ "
   "const sh=document.querySelector('.shell'), m=document.querySelector('.main'); "
   "window.__s.push({t:Math.round(performance.now()-window.__t), "
   "shOp: sh?+(getComputedStyle(sh).opacity):-1, shCls: sh?sh.className:'', "
   "mOp: m?+(getComputedStyle(m).opacity):-1, mCls: m?m.className:''}); "
   "if(performance.now()-window.__t<800) requestAnimationFrame(tick); }; tick(); })()")
pos=js("""(() => { const n=document.querySelectorAll('.nav-item')[1]; const r=n.getBoundingClientRect();
  return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
        buttons=1 if t=="mousePressed" else 0, clickCount=1)
time.sleep(1.5)
s=js("window.__s")
print(f"采样 {len(s)} 帧")
for x in s[::5]:
    print(f"  t={x['t']:4d} shell.op={x['shOp']:.2f} shCls='{x['shCls']}' main.op={x['mOp']:.2f} mCls='{x['mCls']}'")
