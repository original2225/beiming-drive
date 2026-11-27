# -*- coding: utf-8 -*-
"""验证：在非文件页用竖栏搜索，能跳到文件页并真的出结果。"""
import json, urllib.request, time
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=120); _id=[0]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==_id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:250]}"
    return r.get("result",{}).get("value")

def click(sel, wait=1.2):
    pos = js("""(() => { const el=document.querySelector(%s); if(!el) return null;
      const r=el.getBoundingClientRect();
      return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""" % json.dumps(sel))
    if not pos: raise RuntimeError("没命中 "+sel)
    for t in ("mousePressed","mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
            buttons=1 if t=="mousePressed" else 0, clickCount=1)
    time.sleep(wait)

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)

# 从「我的分享」页开始（非文件页）
cmd("Page.navigate", url=B+"/shares"); time.sleep(2.8)
print("起点:", js("location.pathname"))

# 展开竖栏
click(".mini-avatar", 1.0)
print("竖栏展开:", js("document.querySelector('.rail')?.classList.contains('open')"))

# 在竖栏搜索框输入
js("""(() => { const i=document.querySelector('.rail-search input'); i.focus(); i.value=''; })()""")
for ch in "整合":
    cmd("Input.insertText", text=ch)
js("""(() => { const i=document.querySelector('.rail-search input');
  i.dispatchEvent(new Event('input',{bubbles:true})); })()""")
time.sleep(0.4)
print("输入框值:", js("document.querySelector('.rail-search input')?.value"))

# 回车
js("""(() => { const i=document.querySelector('.rail-search input');
  i.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})); i.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',bubbles:true})); })()""")
time.sleep(2.5)

print("\n跳转后路径:", js("location.pathname + location.search"))
print("结果条数:", js("document.querySelectorAll('.row').length"))
print("首条名称:", js("document.querySelector('.row-name')?.textContent?.trim()"))
print("搜索态标记:", js("!!document.querySelector('.search-kw, .searching, [class*=search]')"))
