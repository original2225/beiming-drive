# -*- coding: utf-8 -*-
"""量各页面内容距主区左右边缘的实际距离，并检查内容宽度上限。"""
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
  const main = document.querySelector('.main');
  if (!main) return '无 .main';
  const mr = main.getBoundingClientRect();
  // 找这一页里第一个有文字的块级内容，量它离主区左右边各多远
  const cand = ['.topbar', '.page h2', '.head h2', '.list-head', '.panel'];
  for (const sel of cand) {
    const el = document.querySelector(sel);
    if (!el) continue;
    const r = el.getBoundingClientRect();
    const left = Math.round(r.x - mr.x), right = Math.round(mr.right - r.right);
    return `${sel}: 左距${left}px 右距${right}px 内容宽${Math.round(r.width)}px`;
  }
  return '未找到内容块';
})()"""

for path, name in [("/", "我的文件"), ("/shares", "我的分享"), ("/downloader", "专属下载器"),
                   ("/storage", "存储空间"), ("/profile", "个人信息")]:
    cmd("Page.navigate", url=B+path); time.sleep(2.6)
    print(f"{name:8} {js(PROBE)}")
