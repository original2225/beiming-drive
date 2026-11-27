# -*- coding: utf-8 -*-
"""注入一段放慢 10 倍的过渡，再截中间帧。
CDP 的 Animation.setPlaybackRate 只管 Web Animations，对 CSS transition 无效
（实测高度仍瞬间变 -1），所以直接覆盖 CSS。"""
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
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:150]}"
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

# 覆盖成 2.4s（原 .24s 的 10 倍），透明度也同步拉长并保留延迟比例
js("""(() => {
  const st = document.createElement('style');
  st.id = '__slowmo';
  st.textContent = `
    .upload-slot-leave-active {
      transition: max-height 2.4s cubic-bezier(.4,0,.2,1),
                  opacity 1.0s ease 1.4s,
                  margin-bottom 2.4s cubic-bezier(.4,0,.2,1),
                  padding-top 2.4s cubic-bezier(.4,0,.2,1),
                  padding-bottom 2.4s cubic-bezier(.4,0,.2,1),
                  transform 2.4s cubic-bezier(.4,0,.2,1) !important;
    }`;
  document.head.appendChild(st);
  return true;
})()""")

pos=js("""(() => { const n=document.querySelectorAll('.nav-item')[1]; const r=n.getBoundingClientRect();
  return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
        buttons=1 if t=="mousePressed" else 0, clickCount=1)

for label, wait in [("040", 0.5), ("070", 0.5), ("088", 0.6)]:
    time.sleep(wait)
    h = js("(() => { const b=document.querySelector('.new-btn'); return b ? +b.getBoundingClientRect().height.toFixed(1) : -1; })()")
    op = js("(() => { const b=document.querySelector('.new-btn'); return b ? +(+getComputedStyle(b).opacity).toFixed(2) : -1; })()")
    f = shot(f"3{label}-slowmo.png", side)
    print(f"{label}  高={h:5}  透明={op}  → {os.path.basename(f)}")
