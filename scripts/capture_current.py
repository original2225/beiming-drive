# -*- coding: utf-8 -*-
"""连到用户正在看的那个 Edge，截当前画面 + 报页面状态（不改动任何数据）。"""
import json, urllib.request, time, base64, sys, os
from websocket import create_connection

B = "http://127.0.0.1:8140"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots"

# 找到用户那个 Edge 的调试端口：扫常见端口
PORTS = [9222, 9223, 9333, 9444, 9555]
cdp = None
for p in PORTS:
    try:
        v = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{p}/json/version", timeout=2).read())
        cdp = f"http://127.0.0.1:{p}"
        print(f"发现 CDP: {cdp}  {v.get('Browser')}")
        break
    except Exception:
        continue

if not cdp:
    sys.exit("没有可用的 CDP 端口（用户窗口没开调试开关）")

tabs = json.loads(urllib.request.urlopen(cdp + "/json/list", timeout=10).read())
pages = [t for t in tabs if t["type"] == "page"]
print("标签页:")
for t in pages:
    print("  ", t.get("url", "")[:70], "|", (t.get("title") or "")[:30])

target = next((t for t in pages if "8140" in t.get("url", "")), pages[0] if pages else None)
if not target:
    sys.exit("没有标签页")

ws = create_connection(target["webSocketDebuggerUrl"], timeout=45)
i = [0]


def cmd(m, **kw):
    i[0] += 1
    ws.send(json.dumps({"id": i[0], "method": m, "params": kw}))
    while True:
        r = json.loads(ws.recv())
        if r.get("id") == i[0]:
            if "error" in r:
                raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result", {})


def js(e):
    r = cmd("Runtime.evaluate", expression=e, returnByValue=True, awaitPromise=True)
    if "exceptionDetails" in r:
        return f"<ERR> {str(r['exceptionDetails'])[:200]}"
    return r.get("result", {}).get("value")


cmd("Page.enable")
cmd("Runtime.enable")

info = js("""(() => {
  const rows = [...document.querySelectorAll('.row')];
  return {
    url: location.href,
    title: document.title,
    vw: innerWidth, vh: innerHeight,
    dpr: devicePixelRatio,
    rows: rows.length,
    names: rows.map(r => r.querySelector('.row-name')?.innerText.trim()),
    user: document.querySelector('.user-name, .uname')?.innerText?.trim() || null,
    js: [...document.querySelectorAll('script[src]')].map(s => s.src.split('/').pop()),
    loggedIn: !/login/i.test(location.pathname)
  };
})()""")
print("\n页面状态:")
print(json.dumps(info, ensure_ascii=False, indent=1))

r = cmd("Page.captureScreenshot", format="png")
p = os.path.join(OUT, "review-now.png")
open(p, "wb").write(base64.b64decode(r["data"]))
print("\n截图:", p, os.path.getsize(p) // 1024, "KB")