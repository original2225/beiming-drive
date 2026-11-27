# -*- coding: utf-8 -*-
import json, urllib.request, time
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"
tabs = json.loads(urllib.request.urlopen(CDP + "/json/list", timeout=10).read())
page = next(t for t in tabs if t["type"] == "page")
ws = create_connection(page["webSocketDebuggerUrl"], timeout=90)
_id=[0]
def cmd(m, **kw):
    _id[0]+=1
    ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
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
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B+"/")
time.sleep(5)

print("当前 path:", js("location.pathname+location.search"))
print("导航项详情:")
print(js("""(() => [...document.querySelectorAll('.nav-item')].map((n,i)=>{
  const r=n.getBoundingClientRect();
  return {i, text:n.innerText.replace(/\\n/g,''), x:Math.round(r.x), y:Math.round(r.y),
          w:Math.round(r.width), h:Math.round(r.height),
          top: document.elementFromPoint(Math.round(r.x+r.width/2), Math.round(r.y+r.height/2))?.className};
}))()"""))

# 直接看第二个导航项中心点上是什么元素
pos = js("""(() => { const n=document.querySelectorAll('.nav-item')[1];
  const r=n.getBoundingClientRect();
  return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""")
print("\n第二项中心:", pos)
print("该点上的元素:", js("""(() => {
  const el = document.elementFromPoint(%d, %d);
  if (!el) return 'null';
  return el.tagName + '.' + el.className + ' | 文本:' + el.innerText?.slice(0,20);
})()""" % (pos["x"], pos["y"])))

# 直接用 JS 点（绕过坐标问题），看 go() 是否工作
print("\n用 JS click 测试:")
js("document.querySelectorAll('.nav-item')[1].click()")
time.sleep(1.2)
print("  点后 path:", js("location.pathname+location.search"))
print("  点后高亮:", js("""[...document.querySelectorAll('.nav-item')].map(n=>n.classList.contains('on'))"""))
