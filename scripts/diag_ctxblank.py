# -*- coding: utf-8 -*-
"""诊断：空白处右键为什么没出菜单 —— 看 (700,640) 和 (1400,120) 到底命中什么元素。"""
import json, urllib.request, time, sys
from websocket import create_connection

CDP = "http://127.0.0.1:9333"
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


cmd("Runtime.enable")
print("当前页:", js("location.pathname"))

for (x, y) in [(700, 640), (1400, 120), (700, 300)]:
    info = js("""(() => {
      const el = document.elementFromPoint(%d, %d);
      if (!el) return {hit: null};
      const path = [];
      let n = el;
      for (let k = 0; k < 5 && n; k++) {
        path.push((n.tagName || '').toLowerCase() + (n.className ? '.' + String(n.className).split(' ').filter(Boolean).join('.') : ''));
        n = n.parentElement;
      }
      return {
        hit: path[0],
        path: path,
        inList: !!el.closest('.list'),
        inRow: !!el.closest('.row'),
        isListSelf: el.classList.contains('list')
      };
    })()""" % (x, y))
    print(f"\n({x},{y}) -> {json.dumps(info, ensure_ascii=False, indent=1)}")

print("\n.list 的矩形:", js("(() => { const l=document.querySelector('.list'); if(!l) return null; const r=l.getBoundingClientRect(); return {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}; })()"))
print("主容器矩形:", js("(() => { const m=document.querySelector('.main') || document.querySelector('main'); if(!m) return null; const r=m.getBoundingClientRect(); return {cls:m.className, x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}; })()"))