# -*- coding: utf-8 -*-
"""端到端：通过右键菜单「重命名」真的改掉文件名，再从 API 核对。"""
import json, urllib.request, time, sys
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"

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
        return f"<ERR> {str(r['exceptionDetails'])[:250]}"
    return r.get("result", {}).get("value")


def right_click(x, y):
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=x, y=y, button="right",
            buttons=2 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(1.0)


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=1, mobile=False)

token = js("localStorage.getItem('drive_token') || localStorage.getItem('token')")
print("token 存在:", bool(token))

# 全新 profile 没有 token：先走登录页
if not token:
    cmd("Page.navigate", url=B + "/login")
    time.sleep(4)
    js("""(() => {
      const ins = document.querySelectorAll('input');
      if (ins.length < 2) return 'no-inputs';
      const setv = (el, v) => {
        const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        s.call(el, v); el.dispatchEvent(new Event('input', {bubbles:true}));
      };
      setv(ins[0], 'admin@beiming.local');
      setv(ins[1], 'Container2026!');
      const form = ins[0].closest('form');
      const btn = [...(form ? form.querySelectorAll('button') : document.querySelectorAll('button'))]
                  .find(b => /登录|登入|Login/i.test(b.innerText));
      if (btn) btn.click();
      return 'submitted';
    })()""")
    time.sleep(6)
    print("登录后:", js("location.pathname"), "| token:", bool(js("localStorage.getItem('drive_token') || localStorage.getItem('token')")))

cmd("Page.navigate", url=B + "/")
time.sleep(5)

# 在文档库里对 index.md 右键 -> 重命名
pos = js("""(() => {
  const row = [...document.querySelectorAll('.row')].find(x => x.innerText.includes('文档库'));
  if (!row) return null;
  const b = row.getBoundingClientRect();
  return {x: Math.round(b.x+b.width/2), y: Math.round(b.y+b.height/2)};
})()""")
if js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))").find('index.md') < 0:
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"],
            button="left", buttons=1 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(3)

names = js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))")
print("当前目录:", names)

p = js("""(() => {
  const row = [...document.querySelectorAll('.row')].find(x => /index\\.md$/i.test(x.querySelector('.row-name')?.innerText||''));
  if (!row) return null;
  const b = row.getBoundingClientRect();
  return {x: Math.round(b.x+b.width/2), y: Math.round(b.y+b.height/2)};
})()""")
if not p:
    sys.exit("找不到 index.md")

right_click(p["x"], p["y"])
print("菜单:", js("JSON.stringify([...document.querySelectorAll('.ctx-item')].map(x=>x.innerText.trim()))"))

# 点「重命名」
js("""(() => {
  const it = [...document.querySelectorAll('.ctx-item')].find(x => x.innerText.trim() === '重命名');
  if (it) it.click();
})()""")
time.sleep(1.5)

dlg = js("""(() => {
  const inputs = [...document.querySelectorAll('input')].filter(x => x.offsetParent !== null && x.type !== 'file');
  const dlg = document.querySelector('.mask, .modal, .dialog');
  return {
    inputCount: inputs.length,
    values: inputs.map(x => x.value),
    dlgText: dlg ? dlg.innerText.slice(0, 100) : null
  };
})()""")
print("重命名对话框:", json.dumps(dlg, ensure_ascii=False))

NEW = "index-renamed-by-ctx.md"

# 找重命名框：定位在 .modal.modal-sm 里的 input（不靠 type 选择器，稳）
ok = js("""(() => {
  const box = document.querySelector('.modal.modal-sm');
  if (!box) return {found: false, why: 'no modal'};
  const el = box.querySelector('input');
  if (!el) return {found: false, why: 'no input'};
  const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  s.call(el, %s);
  el.dispatchEvent(new Event('input', {bubbles: true}));
  return {found: true, before: el.value, placeholder: el.placeholder};
})()""" % json.dumps(NEW))
print("填名字:", ok)

# 点「保存」
clicked = js("""(() => {
  const box = document.querySelector('.modal.modal-sm');
  if (!box) return 'no-modal';
  const b = [...box.querySelectorAll('button')].find(x => /保存|确定|创建|OK/i.test(x.innerText));
  if (!b) return 'no-button';
  b.click();
  return 'clicked:' + b.innerText.trim();
})()""")
print("点保存:", clicked)
time.sleep(2.5)

after = js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))")
print("改名后列表:", after)

# API 侧核对
path_id = js("""(() => {
  const row = [...document.querySelectorAll('.row')].find(x => x.innerText.includes('index-renamed'));
  return row ? row.innerText.slice(0, 40) : null;
})()""")
print("页面上有改名后的行:", bool(path_id))

# 改回来
if path_id:
    right_click(p["x"], p["y"])
    js("""(() => {
      const it = [...document.querySelectorAll('.ctx-item')].find(x => x.innerText.trim() === '重命名');
      if (it) it.click();
    })()""")
    time.sleep(1.5)
    js("""(() => {
      const cands = [...document.querySelectorAll('input[type=text]')]
          .filter(x => x.offsetParent !== null && /index-renamed/i.test(x.value));
      if (!cands.length) return false;
      const el = cands[cands.length-1];
      const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      s.call(el, 'index.md');
      el.dispatchEvent(new Event('input', {bubbles: true}));
      let box = el.parentElement;
      for (let k = 0; k < 5 && box; k++) {
        const b = [...box.querySelectorAll('button')].find(x => /确定|保存|重命名|OK/i.test(x.innerText));
        if (b) { b.click(); return true; }
        box = box.parentElement;
      }
      return false;
    })()""")
    time.sleep(2.5)
    print("还原后:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))