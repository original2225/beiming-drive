# -*- coding: utf-8 -*-
"""验证北冥云盘右键菜单：文件行 / 文件夹行 / 空白处 三种上下文。"""
import json, urllib.request, time, base64, sys

try:
    from websocket import create_connection
except ImportError:
    sys.exit("需要 websocket-client：pip install websocket-client")

B = "http://127.0.0.1:8140"
CDP = "http://127.0.0.1:9444"


def wait_up(timeout=60):
    for _ in range(timeout):
        try:
            if json.loads(urllib.request.urlopen(B + "/actuator/health", timeout=3).read())["status"] == "UP":
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


class Tab:
    def __init__(self):
        tabs = json.loads(urllib.request.urlopen(CDP + "/json/list", timeout=10).read())
        page = next(t for t in tabs if t["type"] == "page")
        self.ws = create_connection(page["webSocketDebuggerUrl"], timeout=60)
        self.i = 0
        self.cmd("Page.enable")
        self.cmd("Runtime.enable")
        self.cmd("Network.enable")
        # headless 默认视口很小，必须显式设，否则 (700,640) 这种坐标全在视口外
        self.cmd("Emulation.setDeviceMetricsOverride",
                 width=1440, height=900, deviceScaleFactor=1, mobile=False)

    def cmd(self, m, **kw):
        self.i += 1
        self.ws.send(json.dumps({"id": self.i, "method": m, "params": kw}))
        while True:
            r = json.loads(self.ws.recv())
            if r.get("id") == self.i:
                if "error" in r:
                    raise RuntimeError(f"{m}: {r['error']}")
                return r.get("result", {})

    def js(self, expr):
        r = self.cmd("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=True)
        if "exceptionDetails" in r:
            return f"<JS ERROR> {str(r['exceptionDetails'])[:200]}"
        return r.get("result", {}).get("value")

    def goto(self, url, wait=4):
        self.cmd("Page.navigate", url=url)
        time.sleep(wait)

    def right_click(self, x, y):
        for t in ("mousePressed", "mouseReleased"):
            self.cmd("Input.dispatchMouseEvent", type=t, x=x, y=y, button="right",
                     buttons=2 if t == "mousePressed" else 0, clickCount=1)
        time.sleep(1.1)

    def row_pos(self, keyword):
        return self.js("""(() => {
          const row = [...document.querySelectorAll('.row')].find(x => x.innerText.includes(%s));
          if (!row) return null;
          const b = row.getBoundingClientRect();
          return {x: Math.round(b.x + b.width/2), y: Math.round(b.y + b.height/2)};
        })()""" % json.dumps(keyword))

    def ctx_items(self):
        return self.js("JSON.stringify([...document.querySelectorAll('.ctx-item')].map(x=>x.innerText.trim()))")

    def shot(self, path):
        r = self.cmd("Page.captureScreenshot", format="png")
        open(path, "wb").write(base64.b64decode(r["data"]))
        return path


def main():
    if not wait_up():
        sys.exit("后端没起来")
    print("后端 UP")

    t = Tab()
    t.cmd("Network.setCacheDisabled", cacheDisabled=True)
    t.cmd("Network.clearBrowserCache")

    # 全新 profile 没有 token，先登录
    t.goto(B + "/login", 4)
    if t.js("document.querySelector('input[type=email]') !== null") not in (True, "true"):
        print("!! 当前不在登录页，input 类型:", t.js("JSON.stringify([...document.querySelectorAll('input')].map(x=>x.type))"))
    t.js("""(() => {
      const ins = document.querySelectorAll('input');
      const setv = (el, v) => {
        const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        s.call(el, v); el.dispatchEvent(new Event('input', {bubbles:true}));
      };
      setv(ins[0], 'admin@beiming.local');
      setv(ins[1], 'Container2026!');
      const form = ins[0].closest('form');
      const btn = [...(form ? form.querySelectorAll('button') : document.querySelectorAll('button'))]
                  .find(b => /登录|登入|Login/i.test(b.innerText));
      btn.click();
    })()""")
    time.sleep(6)
    if "/login" in (t.js("location.pathname") or ""):
        sys.exit("登录失败，停在登录页：%s" % t.js("document.body.innerText.slice(0,200)"))
    t.goto(B + "/", 5)

    print("页面 JS:", t.js("(() => [...document.querySelectorAll('script[src]')].map(x=>x.src.split('/').pop()).join(','))()"))
    print("根目录:", t.js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))

    # 1) 文件行
    p = t.row_pos("北冥整合包")
    t.right_click(p["x"], p["y"])
    items = t.ctx_items()
    print("\n1) 文件行右键:", items)
    print("   高亮目标行:", t.js("!!document.querySelector('.row.ctx-target')"))
    print("   菜单在视口内:", t.js("(() => { const m=document.querySelector('.ctx-menu'); if(!m) return null; const r=m.getBoundingClientRect(); return r.right<=innerWidth && r.bottom<=innerHeight; })()"))
    t.shot(r"C:\Users\15780\dev\beiming-drive\docs\screenshots\08-context-menu.png")
    t.js("document.body.click()")
    time.sleep(0.6)

    # 2) 文件夹行
    p2 = t.row_pos("文档库")
    t.right_click(p2["x"], p2["y"])
    print("\n2) 文件夹行右键:", t.ctx_items())
    t.shot(r"C:\Users\15780\dev\beiming-drive\docs\screenshots\09-context-dir.png")
    t.js("document.body.click()")
    time.sleep(0.6)

    # 3) 空白处 —— 必须有元素能被命中，否则右键事件根本不发生
    blank = t.js("""(() => {
      const vw = innerWidth, vh = innerHeight;
      const cands = [];
      for (let y = vh - 40; y > 200; y -= 20) {
        for (let x = 300; x < vw - 100; x += 40) {
          const el = document.elementFromPoint(x, y);
          if (el && (el.classList.contains('list') || el.classList.contains('main'))) {
            cands.push({x, y, hit: el.className});
            if (cands.length >= 1) break;
          }
        }
        if (cands.length) break;
      }
      return cands[0] || null;
    })()""")
    if not blank:
        print("\n3) !!! 找不到可命中的空白区域，跳过")
    else:
        print(f"\n   空白点: {blank}（视口 {t.js('innerWidth')}x{t.js('innerHeight')}）")
        t.right_click(blank["x"], blank["y"])
        print("3) 空白处右键:", t.ctx_items())
        t.js("document.body.click()")
        time.sleep(0.6)

    # 4) Esc 关闭（用文件夹行做载体，一定命中）
    p3 = t.row_pos("文档库")
    t.right_click(p3["x"], p3["y"])
    print("\n4) Esc 关闭:", end=" ")
    t.js("document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))")
    time.sleep(0.5)
    print("菜单还在吗 ->", t.js("!!document.querySelector('.ctx-menu')"))

    # 5) 贴右下角右键，菜单应翻转到鼠标左上
    w = int(t.js("innerWidth"))
    h = int(t.js("innerHeight"))
    t.right_click(w - 12, h - 12)
    print("\n5) 贴右下角右键:", t.ctx_items())
    print("   菜单几何:", t.js("""(() => {
      const m = document.querySelector('.ctx-menu');
      if (!m) return null;
      const r = m.getBoundingClientRect();
      return {left: Math.round(r.left), top: Math.round(r.top),
              right: Math.round(r.right), bottom: Math.round(r.bottom),
              vw: innerWidth, vh: innerHeight,
              ok: r.right <= innerWidth && r.bottom <= innerHeight};
    })()"""))


if __name__ == "__main__":
    main()