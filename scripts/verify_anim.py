# -*- coding: utf-8 -*-
"""抓登录→主页过渡的动画帧：证明中间态真的存在、时长合理、没白屏。"""
import json, urllib.request, time, base64, os
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots\anim"
os.makedirs(OUT, exist_ok=True)

tabs = json.loads(urllib.request.urlopen(CDP + "/json/list", timeout=10).read())
page = next(t for t in tabs if t["type"] == "page")
ws = create_connection(page["webSocketDebuggerUrl"], timeout=60)
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


def shot(name):
    r = cmd("Page.captureScreenshot", format="png")
    p = os.path.join(OUT, name)
    open(p, "wb").write(base64.b64decode(r["data"]))
    return p


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=1, mobile=False)

# 干净起手：清 token 和「已播过入场」标记
cmd("Page.navigate", url=B + "/login")
time.sleep(3)
js("""(() => { localStorage.clear(); sessionStorage.clear(); })()""")
cmd("Page.navigate", url=B + "/login")
time.sleep(3.5)

print("登录页 input:", js("JSON.stringify([...document.querySelectorAll('input')].map(x=>x.type))"))
print("卡片动画前:", js("""(() => {
  const c = document.querySelector('.login-card');
  const s = getComputedStyle(c);
  return {cls: c.className, transform: s.transform, opacity: s.opacity, filter: s.filter};
})()"""))

# 填表
js("""(() => {
  const ins = document.querySelectorAll('input');
  const setv = (el, v) => {
    const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    s.call(el, v); el.dispatchEvent(new Event('input', {bubbles:true}));
  };
  setv(ins[0], 'admin@beiming.local');
  setv(ins[1], 'Container2026!');
})()""")
time.sleep(0.3)

# 点登录，然后密集采样
js("""(() => {
  const b = document.querySelector('button.submit');
  if (b) b.click();
})()""")

# 每 55ms 采一次，持续 1.4s：记录路径 + 卡片/主页面板状态
samples = []
t0 = time.time()
while time.time() - t0 < 1.45:
    s = js("""(() => {
      const card = document.querySelector('.login-card');
      const shell = document.querySelector('.shell');
      const o = {t: performance.now() | 0, path: location.pathname};
      if (card) {
        const st = getComputedStyle(card);
        o.card = {cls: card.className.replace(/\\s+/g,' ').trim(),
                  op: +(parseFloat(st.opacity).toFixed(2)),
                  tf: st.transform === 'none' ? 'none' : st.transform.slice(0, 40),
                  blur: st.filter.slice(0, 24)};
      }
      if (shell) {
        const st = getComputedStyle(shell);
        o.shell = {cls: shell.className.replace(/\\s+/g,' ').trim(),
                   op: +(parseFloat(st.opacity).toFixed(2)),
                   blur: st.filter.slice(0, 24),
                   rows: document.querySelectorAll('.row').length};
      }
      return o;
    })()""")
    samples.append(s)
    time.sleep(0.055)

# 打印时间线（只要状态有变化就打）
prev = None
print("\n时间线（仅在状态变化时打印）：")
for s in samples:
    if not isinstance(s, dict):
        continue
    key = (s.get("path"), json.dumps(s.get("card"), sort_keys=True), json.dumps(s.get("shell"), sort_keys=True))
    if key != prev:
        print(" ", json.dumps(s, ensure_ascii=False))
        prev = key

# 再从头放一遍，在关键时间点各截一帧
print("\n重放并抓中间帧：")
js("""(() => { localStorage.clear(); sessionStorage.clear(); location.href = '/login'; })()""")
time.sleep(3.5)
js("""(() => {
  const ins = document.querySelectorAll('input');
  const setv = (el, v) => {
    const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    s.call(el, v); el.dispatchEvent(new Event('input', {bubbles:true}));
  };
  setv(ins[0], 'admin@beiming.local');
  setv(ins[1], 'Container2026!');
})()""")
time.sleep(0.3)
shot("00-before.png")
js("""(() => { const b = document.querySelector('button.submit'); if (b) b.click(); })()""")

# 在离场动画中段和入场动画中段各抓一帧
# 用绝对时间点：shot() 本身有耗时，累加 sleep 会跑偏
t0 = time.time()
for at, name in [(0.16, "01-card-collapsing.png"),
                 (0.40, "02-card-gone.png"),
                 (0.60, "03-shell-emerging.png"),
                 (0.95, "04-settled.png")]:
    remain = at - (time.time() - t0)
    if remain > 0:
        time.sleep(remain)
    actual = time.time() - t0
    p = shot(name)
    st = js("""(() => {
      const c=document.querySelector('.login-card'), s=document.querySelector('.shell');
      const o={path:location.pathname};
      if(c){const g=getComputedStyle(c);o.card={cls:c.className.replace(/\\s+/g,' ').trim(),op:+(+g.opacity).toFixed(2),blur:g.filter.slice(0,22),tf:g.transform.slice(0,34)};}
      if(s){const g=getComputedStyle(s);o.shell={cls:s.className.replace(/\\s+/g,' ').trim(),op:+(+g.opacity).toFixed(2),blur:g.filter.slice(0,22),rows:document.querySelectorAll('.row').length};}
      if(!c && !s) o.blank = true;
      return o;
    })()""")
    print(f"  {name}  @{actual:.2f}s  {json.dumps(st, ensure_ascii=False)}")