# -*- coding: utf-8 -*-
"""实测各页关键元素的计算字号，不靠读 CSS 猜。"""
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
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:200]}"
    return r.get("result",{}).get("value")

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)

PROBE = """(() => {
  const pick = (sel, label) => {
    const el = document.querySelector(sel);
    if (!el) return label + ": 无";
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return `${label}: ${cs.fontSize} / 高${Math.round(r.height)}px / 行高${cs.lineHeight}`;
  };
  return [
    pick('.side-brand', '品牌名'),
    pick('.nav-item', '导航项'),
    pick('.user-name', '用户名'),
    pick('.user-sub', '用户副标题'),
    pick('.quota-top', '存储标题'),
    pick('.quota-num', '存储数值'),
    pick('.new-btn', '上传按钮'),
  ];
})()"""

pages = [("/", "我的文件"), ("/downloader", "专属下载器"), ("/storage", "存储空间"), ("/profile", "个人信息")]
for path, name in pages:
    cmd("Page.navigate", url=B+path); time.sleep(2.8)
    print(f"\n=== {name} ({path}) ===")
    for line in js(PROBE): print("  ", line)
    body = js("getComputedStyle(document.body).fontSize")
    h2 = js("(() => { const h=document.querySelector('.page h2, .head h2'); return h ? getComputedStyle(h).fontSize+' / 高'+Math.round(h.getBoundingClientRect().height) : '无'; })()")
    print(f"   body 基准: {body}")
    print(f"   页面标题: {h2}")
