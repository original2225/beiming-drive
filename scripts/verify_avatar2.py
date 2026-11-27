# -*- coding: utf-8 -*-
"""头像第二轮：右键删除确认（选择器用 .modal/.tip-line）、toast 文案。"""
import json, urllib.request, time, base64, os
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots\avatar"
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
        return f"<ERR> {str(r['exceptionDetails'])[:250]}"
    return r.get("result", {}).get("value")


def shot(name):
    r = cmd("Page.captureScreenshot", format="png")
    p = os.path.join(OUT, name)
    with open(p, "wb") as f:
        f.write(base64.b64decode(r["data"]))
    return p


def center(sel):
    return js("""(() => {
      const el = document.querySelector(%s);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return {x: Math.round(r.x + r.width/2), y: Math.round(r.y + r.height/2)};
    })()""" % json.dumps(sel))


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B + "/")
time.sleep(5)

# 确保展开且已有头像
if not js("document.querySelector('.rail').classList.contains('open')"):
    js("document.querySelector('.mini-avatar')?.click()")
    time.sleep(0.8)
print("展开:", js("document.querySelector('.rail').classList.contains('open')"))
print("有头像:", js("!!document.querySelector('.rail-avatar img')"))

# 右键头像
pos = center(".rail-avatar")
print("头像坐标:", pos)
for t in ("mousePressed", "mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="right",
        buttons=2 if t == "mousePressed" else 0, clickCount=1)
time.sleep(1.2)

print("\n确认框存在:", js("!!document.querySelector('.mask .modal')"))
print("标题:", (js("document.querySelector('.mask .modal h3')?.innerText") or "").strip())
print("正文:", (js("document.querySelector('.mask .modal .tip-line')?.innerText") or "").strip())
print("按钮:", js("""[...document.querySelectorAll('.mask .modal-foot button')].map(b=>b.innerText.trim())"""))
print("危险按钮是红的:", js("""!!document.querySelector('.mask .modal-foot .btn-danger')"""))
shot("05-avatar-remove-confirm.png")

# 点确认删除
btn = js("""(() => {
  const b = document.querySelector('.mask .modal-foot .btn-danger');
  if (!b) return null;
  const r = b.getBoundingClientRect();
  return {x: Math.round(r.x+r.width/2), y: Math.round(r.y+r.height/2)};
})()""")
print("\n确认按钮坐标:", btn)
if btn:
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=btn["x"], y=btn["y"], button="left",
            buttons=1 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(2)
print("删除后还有图片吗（应 False）:", js("!!document.querySelector('.rail-avatar img')"))
print("现在显示首字母:", js("(document.querySelector('.rail-avatar span:not(.avatar-cam):not(.avatar-spin)')?.innerText || '').trim()"))
shot("06-avatar-after-remove.png")

# me 是否同步清掉
print("me.avatarKey:", js("(async () => { const t=localStorage.getItem('drive_token'); const j=await (await fetch('/api/v1/auth/me',{headers:{Authorization:'Bearer '+t}})).json(); return j.data?.avatarKey; })()"))
