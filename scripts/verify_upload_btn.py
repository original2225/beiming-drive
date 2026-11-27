# -*- coding: utf-8 -*-
"""验证上传按钮消失是否平滑：
1) 按钮高度逐帧变化（不能一帧到 0）
2) 导航栏第一项的 y 坐标是否平滑上移（不能瞬跳）
"""
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
    if "exceptionDetails" in r: return f"<ERR> {str(r['exceptionDetails'])[:200]}"
    return r.get("result",{}).get("value")

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B+"/"); time.sleep(3.5)

print("起始（我的文件）:")
print("  按钮存在:", js("!!document.querySelector('.new-btn')"))
print("  按钮高度:", js("document.querySelector('.new-btn')?.getBoundingClientRect().height"))
print("  导航第一项 y:", js("Math.round(document.querySelectorAll('.nav-item')[0].getBoundingClientRect().y)"))

# 开始采样，然后点「我的分享」
js("""window.__bt=[]; window.__bt0=performance.now();
(() => { const tick=()=>{
  const b=document.querySelector('.new-btn');
  const n=document.querySelectorAll('.nav-item')[0];
  window.__bt.push({
    t: Math.round(performance.now()-window.__bt0),
    h: b ? +b.getBoundingClientRect().height.toFixed(1) : -1,
    op: b ? +(getComputedStyle(b).opacity) : -1,
    navY: n ? Math.round(n.getBoundingClientRect().y) : -1
  });
  if (performance.now()-window.__bt0 < 700) requestAnimationFrame(tick);
}; tick(); })()""")

pos=js("""(() => { const n=document.querySelectorAll('.nav-item')[1]; const r=n.getBoundingClientRect();
  return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
        buttons=1 if t=="mousePressed" else 0, clickCount=1)
time.sleep(1.2)

s=js("window.__bt")
print(f"\n采样 {len(s)} 帧（点「我的分享」后）")
print("   t(ms)  按钮高  透明度  导航y")
for x in s[::4]:
    print(f"  {x['t']:5d}  {x['h']:7.1f}  {x['op']:6.2f}  {x['navY']:5d}")

hs=[x["h"] for x in s if x["h"] >= 0]
print("\n判据:")
print("  最终按钮消失:", hs[-1] == -1 if hs else "n/a")
uniq_h = sorted(set(round(h,1) for h in hs if h > 0))
print(f"  出现过中间高度（说明不是瞬跳）: {len(uniq_h) > 2}  样本={uniq_h[:8]}")
navs=sorted(set(x["navY"] for x in s if x["navY"]>0))
print(f"  导航 y 有平滑变化: {len(navs) > 2}  取值={navs[:10]}")
maxstep = max((abs(hs[i]-hs[i-1]) for i in range(1,len(hs)) if hs[i]>=0 and hs[i-1]>=0), default=0)
print(f"  最大单帧高度跳变: {maxstep:.1f}px（越小越平滑）")
