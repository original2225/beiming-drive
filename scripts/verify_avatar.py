# -*- coding: utf-8 -*-
"""头像验收：点开竖栏 → 悬停头像看相机遮罩 → 点开文件选择框 → 真实换图 → 首字母回退。"""
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


def hover(x, y):
    cmd("Input.dispatchMouseEvent", type="mouseMoved", x=x, y=y)
    time.sleep(0.35)


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
    js("document.querySelector('.mini-avatar')?.click()")
    time.sleep(0.8)

print("竖栏展开:", js("document.querySelector('.rail').classList.contains('open')"))
print("头像元素是 button:", js("document.querySelector('.rail-avatar')?.tagName"))
print("当前显示图片:", js("!!document.querySelector('.rail-avatar img')"))
print("图片地址:", js("document.querySelector('.rail-avatar img')?.getAttribute('src')"))

# 1) 遮罩默认不可见
print("\n1) 遮罩默认透明度:", js("getComputedStyle(document.querySelector('.avatar-cam')).opacity"))
print("   有相机 SVG:", js("!!document.querySelector('.avatar-cam svg')"))

# 2) 悬停头像 → 遮罩浮出
pos = center(".rail-avatar")
print("\n2) 头像坐标:", pos)
hover(pos["x"], pos["y"])
op = js("getComputedStyle(document.querySelector('.avatar-cam')).opacity")
print("   悬停后遮罩透明度:", op)
shot("01-avatar-hover.png")

# 3) 点头像 → 触发隐藏 file input 的 click
print("\n3) 隐藏 input 存在:", js("!!document.querySelector('.avatar-input')"))
print("   input accept:", js("document.querySelector('.avatar-input')?.getAttribute('accept')"))
# 监听是否被点开（file input 打开系统框在 headless 下不会真弹，检查 handler 是否被调用）
js("window.__avatarClicked = false; document.querySelector('.avatar-input')?.addEventListener('click', () => window.__avatarClicked = true)")
for t in ("mousePressed", "mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
        buttons=1 if t == "mousePressed" else 0, clickCount=1)
time.sleep(0.6)
print("   点头像触发了 input.click():", js("window.__avatarClicked"))

# 4) 真实走一遍上传：用 DOM 直接给 input 塞文件并派发 change
print("\n4) 模拟选图并上传 …")
import subprocess
png_bytes = bytes.fromhex(
 "89504e470d0a1a0a0000000d494844520000000200000002080600000072b60d"
 "240000001849444154789c6360f8cfc0f09f0100010100001f0a2db40000000049454e44ae426082")
b64 = base64.b64encode(png_bytes).decode()
js("""(async () => {
  const bin = atob(%s);
  const arr = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
  const file = new File([arr], 'av.png', { type: 'image/png' });
  const dt = new DataTransfer();
  dt.items.add(file);
  const el = document.querySelector('.avatar-input');
  el.files = dt.files;
  el.dispatchEvent(new Event('change', { bubbles: true }));
})()""" % json.dumps(b64))
time.sleep(3)
print("   上传后图片地址:", js("document.querySelector('.rail-avatar img')?.getAttribute('src')"))
print("   提示条文字:", js("(document.querySelector('.toast')?.innerText || '').trim()"))
shot("02-avatar-changed.png")

# 5) 折叠态也显示图片
print("\n5) 收起竖栏，看折叠态头像")
js("document.querySelector('.rail-close')?.click()")
time.sleep(0.7)
print("   折叠态有图片:", js("!!document.querySelector('.mini-avatar img')"))
shot("03-avatar-collapsed.png")

# 6) 恢复：删掉头像，回到首字母
print("\n6) 右键头像删掉 → 回到首字母")
js("document.querySelector('.mini-avatar')?.click()")
time.sleep(0.7)
pos2 = center(".rail-avatar")
for t in ("mousePressed", "mouseReleased"):
    cmd("Input.dispatchMouseEvent", type=t, x=pos2["x"], y=pos2["y"], button="right",
        buttons=2 if t == "mousePressed" else 0, clickCount=1)
time.sleep(1.2)
print("   确认框文案:", js("(document.querySelector('.confirm-box')?.innerText || '').replace(/\\n/g,' ')")[:120] if js("!!document.querySelector('.confirm-box')") else "无确认框")
shot("04-avatar-remove-confirm.png")
