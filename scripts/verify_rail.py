# -*- coding: utf-8 -*-
"""验证右侧竖栏在五个页面都常驻，且展开状态跨页保持。"""
import json, urllib.request, time, base64, os
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
OUT=r"C:\Users\15780\dev\beiming-drive\docs\screenshots\rail"
os.makedirs(OUT, exist_ok=True)
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=120); _id=[0]
logs=[]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("method") in ("Runtime.exceptionThrown","Runtime.consoleAPICalled"):
            logs.append(r); continue
        if r.get("id")==_id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:250]}"
    return r.get("result",{}).get("value")
def shot(name):
    r=cmd("Page.captureScreenshot",format="png")
    f=os.path.join(OUT,name); open(f,"wb").write(base64.b64decode(r["data"])); return f

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)

PROBE = """(() => {
  const rail = document.querySelector('.rail');
  if (!rail) return { exists: false };
  const r = rail.getBoundingClientRect();
  return {
    exists: true,
    width: Math.round(r.width),
    right: Math.round(r.right),
    open: rail.classList.contains('open'),
    hasAvatar: !!rail.querySelector('.mini-avatar, .rail-avatar'),
    hasSearch: !!rail.querySelector('.mini-search, .rail-search'),
  };
})()"""

print("=== 五页竖栏存在性 ===")
for path, name in [("/", "我的文件"), ("/shares", "我的分享"), ("/downloader", "专属下载器"),
                   ("/storage", "存储空间"), ("/profile", "个人信息")]:
    cmd("Page.navigate", url=B+path); time.sleep(2.6)
    print(f"  {name:8} {js(PROBE)}")

# 跨页保持状态：在下载器页展开，再切到存储页看是否仍展开
print("\n=== 展开状态跨页保持 ===")
cmd("Page.navigate", url=B+"/downloader"); time.sleep(2.5)
pos = js("""(() => { const b=document.querySelector('.mini-avatar') || document.querySelector('.rail-gap');
  if(!b) return null; const r=b.getBoundingClientRect();
  return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
        buttons=1 if t=="mousePressed" else 0, clickCount=1)
time.sleep(1.0)
print("  下载器页展开后:", js(PROBE))
print("  截图:", shot("70-rail-open-downloader.png"))

for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=16, y=1, button="left", buttons=0, clickCount=1)
# 直接点左侧导航切页（用 JS 更稳）
js("document.querySelectorAll('.nav-item')[1].click()")
time.sleep(1.6)
print("  切到分享页后:", js(PROBE))
print("  截图:", shot("71-rail-open-shares.png"))
