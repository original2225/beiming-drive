
import json, urllib.request, time
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=60); i=[0]
def cmd(m,**kw):
    i[0]+=1; ws.send(json.dumps({"id":i[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==i[0]: return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return str(r["exceptionDetails"])[:200]
    return r.get("result",{}).get("value")
cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B+"/downloader"); time.sleep(3)
print(js("""(() => {
  const g = (sel, label) => {
    const el = document.querySelector(sel);
    if (!el) return label + ': 无';
    const r = el.getBoundingClientRect();
    return `${label}: 右边=${Math.round(r.right)} 左=${Math.round(r.left)} 宽=${Math.round(r.width)}`;
  };
  return [
    g('.panel', '下载卡片'),
    g('.panel .btn-primary', '开始下载'),
    g('.panel .hint', '说明文字'),
    g('.list-head', '列表头容器'),
    g('.list-head .btn', '刷新'),
    g('.job', '任务卡片'),
    g('.job-ops .btn:last-child', '最后一个按钮(删除)'),
    g('.job-ops', '按钮组'),
  ].join('\\n');
})()"""))
