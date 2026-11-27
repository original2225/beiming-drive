# -*- coding: utf-8 -*-
"""验收存储页 + 个人页的真实渲染与交互（走界面，不走接口）。"""
import json, urllib.request, time, base64, os
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
OUT=r"C:\Users\15780\dev\beiming-drive\docs\screenshots\pages"
os.makedirs(OUT, exist_ok=True)
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=120); _id=[0]
logs=[]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("method") in ("Runtime.consoleAPICalled","Runtime.exceptionThrown","Log.entryAdded"):
            logs.append(r); continue
        if r.get("id")==_id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:250]}"
    return r.get("result",{}).get("value")
def shot(name, clip=None):
    kw={"format":"png"}
    if clip: kw["clip"]={**clip,"scale":2}
    r=cmd("Page.captureScreenshot",**kw)
    f=os.path.join(OUT,name)
    open(f,"wb").write(base64.b64decode(r["data"]))
    return f

cmd("Page.enable"); cmd("Runtime.enable"); cmd("Log.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)

# ========== 1) 点左下角存储条 ==========
cmd("Page.navigate", url=B+"/"); time.sleep(3.5)
print("=== 1. 点存储条 ===")
print("  存储条是 button:", js("document.querySelector('.quota')?.tagName"))
pos = js("""(() => { const q=document.querySelector('.quota'); if(!q) return null;
  const r=q.getBoundingClientRect(); return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
print("  位置:", pos)
before = js("location.pathname")
for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
        buttons=1 if t=="mousePressed" else 0, clickCount=1)
time.sleep(1.8)
after = js("location.pathname")
print(f"  路径 {before} → {after}  ({'通过' if after=='/storage' else '失败'})")

print("\n=== 2. 存储页内容 ===")
print("  标题:", js("document.querySelector('.page h2')?.textContent"))
print("  已用字号:", js("getComputedStyle(document.querySelector('.head-num')).fontSize"))
print("  分段条段数:", js("document.querySelectorAll('.seg-part').length"))
print("  图例项:", js("Array.from(document.querySelectorAll('.lg')).map(e=>e.textContent.trim())"))
print("  明细行:", js("Array.from(document.querySelectorAll('.item .item-name')).map(e=>e.textContent.trim())"))
print("  回收站按钮:", js("document.querySelector('.trash-item .btn')?.textContent?.trim()"))
print("  骨架屏已消失:", js("!document.querySelector('.sk-wrap')"))
print("  截图:", shot("40-storage.png"))

# ========== 3) 点回收站「去清理」==========
print("\n=== 3. 去清理 ===")
pos2 = js("""(() => { const b=document.querySelector('.trash-item .btn'); if(!b) return null;
  const r=b.getBoundingClientRect(); return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
if pos2:
    for t in ("mousePressed","mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=pos2["x"], y=pos2["y"], button="left",
            buttons=1 if t=="mousePressed" else 0, clickCount=1)
    time.sleep(1.6)
    print("  跳转到:", js("location.pathname + location.search"))

# ========== 4) 侧栏用户区 → 个人页 ==========
print("\n=== 4. 侧栏用户区 ===")
u = js("""(() => { const b=document.querySelector('.user-btn'); if(!b) return null;
  const r=b.getBoundingClientRect(); return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2),
    name: b.querySelector('.user-name')?.textContent, sub: b.querySelector('.user-sub')?.textContent}; })()""")
print("  用户区:", u)
for t in ("mousePressed","mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=u["x"], y=u["y"], button="left",
        buttons=1 if t=="mousePressed" else 0, clickCount=1)
time.sleep(1.8)
print("  路径:", js("location.pathname"))

print("\n=== 5. 个人页内容 ===")
print("  标题:", js("document.querySelector('.page h2')?.textContent"))
print("  昵称:", js("document.querySelector('.hero-name')?.textContent?.trim()"))
print("  徽章:", js("document.querySelector('.badge')?.textContent"))
print("  邮箱:", js("document.querySelector('.hero-mail')?.textContent"))
print("  加入时间行:", js("document.querySelector('.hero-meta')?.textContent?.trim()"))
print("  头像已加载:", js("(() => { const i=document.querySelector('.ava img'); return i ? (i.naturalWidth>0 ? i.naturalWidth+'x'+i.naturalHeight : 'width=0') : '无img(首字母)'; })()"))
print("  区块:", js("Array.from(document.querySelectorAll('.row-title')).map(e=>e.textContent.trim())"))
print("  截图:", shot("41-profile.png"))

print("\n=== 异常 ===")
n=0
for l in logs[-12:]:
    if l["method"]=="Runtime.exceptionThrown":
        print("  EXC:", str(l["params"]["exceptionDetails"])[:220]); n+=1
    elif l["method"]=="Runtime.consoleAPICalled" and l["params"]["type"]=="error":
        print("  ERR:", " ".join(str(a.get("value","")) for a in l["params"].get("args",[]))[:220]); n+=1
if n==0: print("  无")
