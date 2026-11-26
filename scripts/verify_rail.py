# -*- coding: utf-8 -*-
"""验证右侧竖栏 + 顶栏新建菜单：折叠态、展开态、悬停放大、新建动作。"""
import json, urllib.request, time, base64, os, sys
from websocket import create_connection

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots\rail"
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
        return f"<ERR> {str(r['exceptionDetails'])[:250]}"
    return r.get("result", {}).get("value")


def shot(name):
    r = cmd("Page.captureScreenshot", format="png")
    p = os.path.join(OUT, name)
    open(p, "wb").write(base64.b64decode(r["data"]))
    return p


def move(x, y):
    cmd("Input.dispatchMouseEvent", type="mouseMoved", x=x, y=y, buttons=0)
    time.sleep(0.45)


def click_sel(sel):
    pos = js("""(() => {
      const el = document.querySelector(%s);
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return {x: Math.round(b.x + b.width/2), y: Math.round(b.y + b.height/2)};
    })()""" % json.dumps(sel))
    if not pos:
        return False
    for t in ("mousePressed", "mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"],
            button="left", buttons=1 if t == "mousePressed" else 0, clickCount=1)
    time.sleep(0.5)
    return True


cmd("Page.enable")
cmd("Runtime.enable")
cmd("Network.enable")
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
    time.sleep(6)
    cmd("Page.navigate", url=B + "/")
    time.sleep(5)

print("页面 JS:", js("(() => [...document.querySelectorAll('script[src]')].map(s=>s.src.split('/').pop()).join(','))()"))
print("根目录:", js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))

# ---------- 1) 折叠态 ----------
r = js("""(() => {
  const rail = document.querySelector('.rail');
  const mini = document.querySelector('.rail-mini');
  const av = document.querySelector('.mini-avatar');
  const se = document.querySelector('.mini-search');
  if (!rail) return {rail: null};
  const rb = rail.getBoundingClientRect();
  const ab = av ? av.getBoundingClientRect() : null;
  const sb = se ? se.getBoundingClientRect() : null;
  return {
    width: Math.round(rb.width),
    right: Math.round(rb.right), vw: innerWidth,
    hasAvatar: !!av, avatarY: ab ? Math.round(ab.y) : null,
    hasSearch: !!se, searchY: sb ? Math.round(sb.y) : null,
    /* 头像在顶部、放大镜在底部 */
    avatarTop: ab ? ab.y < rb.height/2 : null,
    searchBottom: sb ? sb.y > rb.height/2 : null,
    gapTipOpacity: (() => {
      const g = document.querySelector('.gap-tip');
      return g ? getComputedStyle(g).opacity : null;
    })()
  };
})()""")
print("\n1) 折叠态:", json.dumps(r, ensure_ascii=False, indent=1))
shot("01-collapsed.png")

# ---------- 2) 悬停头像变大 ----------
ab = js("(() => { const b=document.querySelector('.mini-avatar').getBoundingClientRect(); return {x:Math.round(b.x+b.width/2), y:Math.round(b.y+b.height/2)}; })()")
before = js("(() => { const s=getComputedStyle(document.querySelector('.mini-avatar')); return {tf:s.transform, shadow:s.boxShadow.slice(0,30)}; })()")
move(ab["x"], ab["y"])
after = js("(() => { const s=getComputedStyle(document.querySelector('.mini-avatar')); return {tf:s.transform, shadow:s.boxShadow.slice(0,30)}; })()")
print("\n2) 头像悬停前:", before)
print("   头像悬停后:", after)

# ---------- 3) 中间空白区提示 ----------
gap = js("(() => { const b=document.querySelector('.rail-gap').getBoundingClientRect(); return {x:Math.round(b.x+b.width/2), y:Math.round(b.y+b.height/2)}; })()")
tip_before = js("getComputedStyle(document.querySelector('.gap-tip')).opacity")
move(gap["x"], gap["y"])
time.sleep(0.35)
tip_after = js("getComputedStyle(document.querySelector('.gap-tip')).opacity")
print("\n3) 空白区提示 opacity:", tip_before, "->", tip_after)
shot("02-gap-hover.png")

# ---------- 4) 展开 ----------
click_sel(".rail-gap")
time.sleep(0.6)
r2 = js("""(() => {
  const rail = document.querySelector('.rail');
  const full = document.querySelector('.rail-full');
  if (!full) return {open: false};
  const rb = rail.getBoundingClientRect();
  const head = document.querySelector('.rail-head');
  const nav = document.querySelector('.rail-nav');
  const search = document.querySelector('.rail-search');
  return {
    open: rail.classList.contains('open'),
    width: Math.round(rb.width),
    name: document.querySelector('.rail-name')?.innerText.trim(),
    mail: document.querySelector('.rail-mail')?.innerText.trim(),
    items: [...document.querySelectorAll('.rail-item span')].map(x=>x.innerText.trim()),
    searchCapsule: search ? {radius: getComputedStyle(search).borderRadius,
                             hasIcon: !!search.querySelector('svg'),
                             placeholder: search.querySelector('input')?.placeholder} : null,
    /* 顺序：头像姓名 → 菜单 → 搜索在最后 */
    order: {headY: Math.round(head.getBoundingClientRect().y),
            navY: Math.round(nav.getBoundingClientRect().y),
            searchY: Math.round(search.getBoundingClientRect().y)},
    railRight: Math.round(rb.right), vw: innerWidth
  };
})()""")
print("\n4) 展开态:", json.dumps(r2, ensure_ascii=False, indent=1))
shot("03-expanded.png")

# ---------- 5) 收起 ----------
click_sel(".rail-close")
time.sleep(0.5)
print("\n5) 收起后宽:", js("Math.round(document.querySelector('.rail').getBoundingClientRect().width)"),
      "| open:", js("document.querySelector('.rail').classList.contains('open')"))

# ---------- 6) 新建菜单 ----------
click_sel(".new-fab")
time.sleep(0.35)
n = js("""(() => {
  const m = document.querySelector('.new-menu');
  if (!m) return null;
  const fab = document.querySelector('.new-fab');
  return {
    open: fab.classList.contains('open'),
    crossRotated: getComputedStyle(fab.querySelector('svg')).transform,
    items: [...m.querySelectorAll('.new-item span')].map(x=>x.innerText.trim()),
    inView: (()=>{const r=m.getBoundingClientRect();return r.right<=innerWidth&&r.bottom<=innerHeight;})()
  };
})()""")
print("\n6) 新建菜单:", json.dumps(n, ensure_ascii=False, indent=1))
shot("04-new-menu.png")