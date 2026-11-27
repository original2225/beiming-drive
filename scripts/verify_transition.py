# -*- coding: utf-8 -*-
"""页面切换过渡验收：
1) 侧栏在 文件→分享 切换时坐标是否不变（不能跟着重建）
2) 导航高亮是否平滑切换
3) 内容区是否走了过渡（逐帧采样 opacity）
4) 骨架屏是否真的出现过
"""
import json, urllib.request, time, base64, os
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots\transition"
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
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result", {})

def js(e):
    r = cmd("Runtime.evaluate", expression=e, returnByValue=True, awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR> {str(r['exceptionDetails'])[:300]}"
    return r.get("result", {}).get("value")

def shot(name):
    r = cmd("Page.captureScreenshot", format="png")
    f = os.path.join(OUT, name)
    with open(f, "wb") as fh: fh.write(base64.b64decode(r["data"]))
    return f

def click(sel, expect_path=None):
    """点导航项，并（可选）断言路径真的变了 ——
    之前吃过"选择器写错 → 点了没反应 → 脚本全绿"的亏，所以这里必须校验。"""
    pos = js("""(() => { const el=document.querySelector(%s); if(!el) return null;
      const r=el.getBoundingClientRect();
      return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""" % json.dumps(sel))
    if not pos:
        raise RuntimeError(f"选择器没命中元素: {sel}")
    before = js("location.pathname + location.search")
    for t in ("mousePressed","mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
            buttons=1 if t=="mousePressed" else 0, clickCount=1)
    time.sleep(0.35)
    after = js("location.pathname + location.search")
    if expect_path is not None and after != expect_path:
        raise RuntimeError(f"点了 {sel} 但路径没到 {expect_path}，实际 {after}（点击没生效）")
    return before, after

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)

if not js("localStorage.getItem('drive_token')"):
    cmd("Page.navigate", url=B + "/login"); time.sleep(4)
    js("""(() => {
      const ins=document.querySelectorAll('input');
      const setv=(el,v)=>{const s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));};
      setv(ins[0],'admin@beiming.local'); setv(ins[1],'Container2026!');
      document.querySelector('button.submit')?.click(); })()""")
    time.sleep(7)
    cmd("Page.navigate", url=B + "/"); time.sleep(5)

def railSnapshot():
    return js("""(() => {
      const side = document.querySelector('.side');
      const brand = document.querySelector('.side-brand');
      const navs = [...document.querySelectorAll('.nav-item')].map(n => ({
        text: n.innerText.trim(),
        on: n.classList.contains('on'),
        y: Math.round(n.getBoundingClientRect().y)
      }));
      return {
        sideRect: side ? [Math.round(side.getBoundingClientRect().x), Math.round(side.getBoundingClientRect().width)] : null,
        brandY: brand ? Math.round(brand.getBoundingClientRect().y) : null,
        navs
      };
    })()""")

print("=" * 60)
print("【起始】我的文件")
a = railSnapshot()
print("  侧栏 x/宽:", a["sideRect"], "| brand y:", a["brandY"])
print("  导航:", [(n["text"], n["on"]) for n in a["navs"]])

# --- 逐帧采样：点「我的分享」---
print("\n【切换】我的文件 → 我的分享，逐帧采样内容区透明度")
js("window.__samples = []; window.__t0 = performance.now(); "
   "(() => { const tick = () => { "
   "const m = document.querySelector('.main > .page') || document.querySelector('.main > *'); "
   "window.__samples.push({t: Math.round(performance.now()-window.__t0), "
   "op: m ? +(getComputedStyle(m).opacity) : -1, "
   "path: location.pathname, rows: document.querySelectorAll('.row, .job').length, "
   "sk: !!document.querySelector('.sk-line') }); "
   "if (performance.now()-window.__t0 < 900) requestAnimationFrame(tick); }; tick(); })()")

click('.nav-item:nth-child(2)', '/shares')
time.sleep(1.6)

samples = js("window.__samples")
print(f"  采样 {len(samples)} 帧")
for s in samples[::6]:
    print(f"    t={s['t']:4d}ms op={s['op']:.2f} path={s['path']:12s} rows={s['rows']} 骨架={s['sk']}")

after = railSnapshot()
print("\n【结束】我的分享")
print("  侧栏 x/宽:", after["sideRect"], "| brand y:", after["brandY"])
print("  导航:", [(n["text"], n["on"]) for n in after["navs"]])

print("\n--- 判据 ---")
print("  侧栏位置未变:", a["sideRect"] == after["sideRect"])
print("  侧栏品牌 y 未变:", a["brandY"] == after["brandY"])
print("  导航项数量一致(4项):", len(a["navs"]) == len(after["navs"]) == 4)
print("  各导航 y 未变:", [n["y"] for n in a["navs"]] == [n["y"] for n in after["navs"]])
print("  高亮切到「我的分享」:", any(n["text"]=="我的分享" and n["on"] for n in after["navs"]))
print("  走了过渡(出现中间透明度):",
      any(0.05 < s["op"] < 0.98 for s in samples))
print("  出现过骨架屏:", any(s["sk"] for s in samples))

shot("01-shares-after-transition.png")

# 再切到下载器
print("\n【切换】我的分享 → 专属下载器")
js("window.__s2 = []; window.__t2 = performance.now(); "
   "(() => { const tick = () => { const m = document.querySelector('.main > .page') || document.querySelector('.main > *'); "
   "window.__s2.push({t: Math.round(performance.now()-window.__t2), op: m?+(getComputedStyle(m).opacity):-1}); "
   "if (performance.now()-window.__t2 < 800) requestAnimationFrame(tick); }; tick(); })()")
click('.nav-item:nth-child(3)', '/downloader')
time.sleep(1.5)
s2 = js("window.__s2")
print(f"  采样 {len(s2)} 帧；中间透明度出现:",
      any(0.05 < s['op'] < 0.98 for s in s2))
b = railSnapshot()
print("  侧栏 x/宽:", b["sideRect"], "| 与开始时一致:", a["sideRect"] == b["sideRect"])
print("  导航:", [(n["text"], n["on"]) for n in b["navs"]])
shot("02-downloader-after-transition.png")

# 回收站（以前只有文件页侧栏有这一项）
print("\n【切换】专属下载器 → 回收站")
click('.nav-item:nth-child(4)', '/?view=trash')
time.sleep(1.4)
c = railSnapshot()
print("  path:", js("location.pathname + location.search"))
print("  高亮:", [(n["text"], n["on"]) for n in c["navs"]])
print("  侧栏仍在原位:", c["sideRect"] == a["sideRect"])
shot("03-trash-after-transition.png")
