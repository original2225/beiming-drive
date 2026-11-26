# -*- coding: utf-8 -*-
"""搜索能力验收：在真实页面上逐个验证 中文子串 / 拼音全拼 / 首字母 / 容错 / 隔离。

不只看接口返回，走竖栏搜索框真实输入 + 回车，读列表。
"""
import json, urllib.request, time, base64, os
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots\search"
os.makedirs(OUT, exist_ok=True)

tabs = json.loads(urllib.request.urlopen(CDP + "/json/list", timeout=10).read())
page = next(t for t in tabs if t["type"] == "page")
ws = create_connection(page["webSocketDebuggerUrl"], timeout=90)
_id = [0]


def cmd(m, **kw):
    _id[0] += 1
    ws.send(json.dumps({"id": _id[0], "method": m, "params": kw}))
    while True:
        r = json.loads(ws.recv())
        if r.get("id") == _id[0]:
            if "error" in r:
                raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result", {})


def js(e):
    r = cmd("Runtime.evaluate", expression=e, returnByValue=True, awaitPromise=True)
    if "exceptionDetails" in r:
        return f"<ERR> {str(r['exceptionDetails'])[:300]}"
    return r.get("result", {}).get("value")


def shot(name):
    r = cmd("Page.captureScreenshot", format="png")
    p = os.path.join(OUT, name)
    with open(p, "wb") as f:
        f.write(base64.b64decode(r["data"]))
    return p


def search(kw):
    js("""(() => {
      const el = document.querySelector('.rail-search input');
      if (!el) return false;
      const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
      s.call(el, %s); el.dispatchEvent(new Event('input',{bubbles:true}));
      return true;
    })()""" % json.dumps(kw))
    time.sleep(0.3)
    for t in ("keyDown", "keyUp"):
        cmd("Input.dispatchKeyEvent", type=t, key="Enter", code="Enter",
            windowsVirtualKeyCode=13, nativeVirtualKeyCode=13)
    time.sleep(2.2)
    return js("""(() => [...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))()""")


def api(kw):
    """直接打接口，避开前端防抖/聚焦问题，用于判断是后端还是前端的问题"""
    return js("""(async () => {
      const t = localStorage.getItem('drive_token');
      const r = await fetch('/api/v1/files/search?q=' + encodeURIComponent(%s),
                            {headers:{Authorization:'Bearer '+t}});
      const j = await r.json();
      return {code: j.code, total: j.data?.total, names: (j.data?.items||[]).map(x=>x.name)};
    })()""" % json.dumps(kw))


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)

if not js("localStorage.getItem('drive_token')"):
    cmd("Page.navigate", url=B + "/login")
    time.sleep(4)
    js("""(() => {
      const ins = document.querySelectorAll('input');
      const setv=(el,v)=>{const s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));};
      setv(ins[0],'admin@beiming.local'); setv(ins[1],'Container2026!');
      document.querySelector('button.submit')?.click();
    })()""")
    time.sleep(7)
    cmd("Page.navigate", url=B + "/")
    time.sleep(5)

# 展开竖栏
if not js("document.querySelector('.rail').classList.contains('open')"):
    js("document.querySelector('.mini-search')?.click()")
    time.sleep(0.6)

print("=" * 66)
print("根目录:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))
print("索引文档数:", js("""(async () => {
  const t = localStorage.getItem('drive_token');
  const j = await (await fetch('/api/v1/files/search?q=x', {headers:{Authorization:'Bearer '+t}})).json();
  return j.code;
})()"""))

CASES = [
    ("中文子串：整合", "整合"),
    ("中文子串：文档", "文档"),
    ("拼音全拼：beiming", "beiming"),
    ("首字母：bmzhb", "bmzhb"),
    ("首字母：gybm（关于北冥）", "gybm"),
    ("拼音半截：zheng", "zheng"),
    ("英文数字：v2.4", "v2.4"),
    ("错一字：文挡（应命中文档库）", "文挡"),
]

results = {}
print("\n--- 后端接口逐个验 ---")
for label, kw in CASES:
    r = api(kw)
    results[label] = r
    print(f"  {label:26s} -> {json.dumps(r, ensure_ascii=False)}")

print("\n--- 前端竖栏搜索框实际输入 ---")
for label, kw in CASES[:4] + [("错一字：文挡", "文挡")]:
    names = search(kw)
    print(f"  {label:26s} -> {json.dumps(names, ensure_ascii=False)}")

# 截一张拼音搜索的图
search("beiming")
print("\n拼音搜索截图:", shot("05-search-pinyin.png"))

# 恢复
search("")
for t in ("keyDown", "keyUp"):
    cmd("Input.dispatchKeyEvent", type=t, key="Enter", code="Enter",
        windowsVirtualKeyCode=13, nativeVirtualKeyCode=13)
time.sleep(2)
print("恢复后:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))