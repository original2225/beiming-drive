"""用 CDP 直接截图 beiming-drive 各页面，不走 browser_harness（那个 daemon 会卡死）。

流程：连 9333 → 开新 tab → 导航 → 执行 JS（登录/进目录/开弹窗）→ 截图
输出：docs/screenshots/*.png
"""
import json, time, base64, os, sys, urllib.request
from websocket import create_connection   # websocket-client

CDP = "http://127.0.0.1:9333"
BASE = "http://127.0.0.1:8140"
OUT = r"C:\Users\15780\dev\beiming-drive\docs\screenshots"
os.makedirs(OUT, exist_ok=True)

def get_ws_url():
    tabs = json.loads(urllib.request.urlopen(CDP + "/json/list", timeout=10).read())
    page = next((t for t in tabs if t["type"] == "page"), None)
    if page:
        return page["webSocketDebuggerUrl"]
    # 新版 chrome/edge 只允许 PUT 建 tab，GET 会 405
    req = urllib.request.Request(CDP + "/json/new?about:blank", method="PUT")
    return json.loads(urllib.request.urlopen(req, timeout=10).read())["webSocketDebuggerUrl"]


class Tab:
    def __init__(self, ws_url):
        self.ws = create_connection(ws_url, timeout=60)
        self.i = 0

    def cmd(self, method, **kw):
        self.i += 1
        self.ws.send(json.dumps({"id": self.i, "method": method, "params": kw}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.i:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def js(self, expr, await_promise=True):
        r = self.cmd("Runtime.evaluate", expression=expr, returnByValue=True,
                     awaitPromise=await_promise)
        if "exceptionDetails" in r:
            raise RuntimeError("JS 异常: " + str(r["exceptionDetails"])[:300])
        return r.get("result", {}).get("value")

    def goto(self, path, wait=3):
        url = path if path.startswith("http") else BASE + path
        self.cmd("Page.navigate", url=url)
        time.sleep(wait)

    def shot(self, name):
        # 2x 像素密度，README 里看着清晰
        r = self.cmd("Page.captureScreenshot", format="png", captureBeyondViewport=False)
        path = os.path.join(OUT, name)
        with open(path, "wb") as f:
            f.write(base64.b64decode(r["data"]))
        kb = os.path.getsize(path) / 1024
        print(f"  {name}  {kb:.0f} KB")
        return path


def click_row(kind, name):
    """kind: 'row' 点名字 / 'preview' 点预览 / 'menu' 点 ⋯"""
    return f"""(async () => {{
      const row = [...document.querySelectorAll('.row')].find(x => {{
        const n = x.querySelector('.row-name');
        return n && n.innerText.trim() === {json.dumps(name)};
      }});
      if (!row) return 'not-found';
      const k = {json.dumps(kind)};
      if (k === 'row') row.querySelector('.row-name').click();
      else if (k === 'preview') {{
        const b = [...row.querySelectorAll('button')].find(x => x.innerText.trim() === '预览');
        if (!b) return 'no-btn'; b.click();
      }} else if (k === 'menu') {{
        const b = [...row.querySelectorAll('button')].find(x => x.innerText.trim() === '⋯');
        if (!b) return 'no-btn'; b.click();
      }}
      await new Promise(r => setTimeout(r, 300));
      return 'ok';
    }})()"""


def main():
    ws_url = get_ws_url()
    t = Tab(ws_url)
    t.cmd("Page.enable")
    t.cmd("Runtime.enable")
    t.cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900,
          deviceScaleFactor=2, mobile=False)

    print("1) 登录页")
    # 关键：先清登录态。否则有 token 时导航 /login 会被路由守卫直接送回首页，
    # 截出来的「登录页」其实是文件列表（这个坑真踩过一次）。
    t.cmd("Page.navigate", url=BASE + "/login")
    time.sleep(1)
    t.js("""(async () => {
      localStorage.clear(); sessionStorage.clear();
      try {
        const rs = await navigator.serviceWorker.getRegistrations();
        for (const r of rs) await r.unregister();
        const ks = await caches.keys();
        for (const k of ks) await caches.delete(k);
      } catch(e) {}
      return 'ok';
    })()""")
    t.goto("/login", 5)
    types = t.js("JSON.stringify([...document.querySelectorAll('input')].map(x=>x.type))")
    print("   input 类型:", types)
    # 必须出现 email+password，否则截到的是别的东西
    if "email" not in str(types) or "password" not in str(types):
        sys.exit(f"当前不是登录页（input 类型 {types}），检查路由守卫或服务状态")
    t.shot("01-login.png")

    print("2) 登录")
    # 等表单真正挂上（SPA 首屏有时比 wait 慢），否则 input[0] 是 undefined
    for _ in range(20):
        if t.js("document.querySelectorAll('input').length") >= 2:
            break
        time.sleep(0.5)
    ok = t.js("""(() => {
      const ins = document.querySelectorAll('input');
      if (ins.length < 2) return 'only ' + ins.length + ' inputs';
      const set = (el,v) => {
        const proto = el instanceof HTMLTextAreaElement
          ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
        const s = Object.getOwnPropertyDescriptor(proto,'value').set;
        s.call(el,v); el.dispatchEvent(new Event('input',{bubbles:true}));
      };
      set(ins[0],'admin@beiming.local'); set(ins[1],'Container2026!');
      return 'filled: ' + ins[0].value + ' / ' + ins[1].value.length + ' chars';
    })()""")
    print("   ", ok)
    if not str(ok).startswith("filled"):
        sys.exit("填表失败：" + str(ok))
    time.sleep(0.5)
    t.js("""(() => { const b=[...document.querySelectorAll('button')].filter(x=>x.closest('form'))[0]; b.click(); return true; })()""")
    time.sleep(6)
    print("   url:", t.js("location.href"))

    print("3) 首页")
    t.goto("/", 4)
    # 等列表真正渲染出来（.row 或空态），否则会截到骨架/空白
    for _ in range(30):
        if t.js("""(() => document.querySelectorAll('.row').length > 0
                      || !!document.querySelector('.empty, .empty-plain'))()"""):
            break
        time.sleep(0.5)
    print("   root rows:", t.js("document.querySelectorAll('.row').length"))
    t.shot("02-home.png")

    print("4) 文档库")
    if t.js(click_row("row", "文档库")) == "ok":
        time.sleep(3)
        print("   条目:", t.js("JSON.stringify([...document.querySelectorAll('.row-name')].map(x=>x.innerText.trim()))"))
        t.shot("03-doclib.png")
        if t.js(click_row("row", "2-原版群组服")) == "ok":
            time.sleep(3)
            t.shot("04-subdir.png")
    else:
        print("   跳过（没有「文档库」目录）")

    print("5) 在线预览")
    t.goto("/", 4)
    t.js(click_row("row", "文档库")); time.sleep(3)
    if t.js(click_row("preview", "index.md")) == "ok":
        time.sleep(3)
        if t.js("!!document.querySelector('.preview-modal')"):
            t.shot("05-preview.png")
            t.js("""(() => { const b=[...document.querySelectorAll('.pv-actions button')].find(x=>/关闭/.test(x.innerText)); if(b)b.click(); })()""")
            time.sleep(1)
        else:
            print("   预览弹窗没出来")
    else:
        print("   跳过")

    print("6) 移动到…")
    t.goto("/", 4)
    t.js(click_row("row", "文档库")); time.sleep(3)
    if t.js(click_row("menu", "1-基本信息")) == "ok":
        time.sleep(1)
        t.js("""(() => { const it=[...document.querySelectorAll('.menu-item')].find(x=>/移动到/.test(x.innerText)); if(it)it.click(); })()""")
        time.sleep(2)
        if t.js("!!document.querySelector('.dir-picker')"):
            t.shot("06-move.png")
            t.js("""(() => { const b=[...document.querySelectorAll('.modal-foot button')].find(x=>/取消/.test(x.innerText)); if(b)b.click(); })()""")
            time.sleep(1)
        else:
            print("   选择器没出来")
    else:
        print("   跳过")

    print("7) 专属下载器")
    t.goto("/downloader", 4)
    # 等异步数据落地：任务列表要么有 .job 卡片，要么显示空态；侧栏用户名必须回来。
    # 少了这层等待会截到「头像只有首字母、列表还在转」的中间态（踩过）。
    for _ in range(30):
        ready = t.js("""(() => {
          const hasJobs = document.querySelectorAll('.job').length > 0;
          const empty = /还没有任务/.test(document.body.innerText || '');
          const userOk = !!(document.querySelector('.user, .sidebar-user, .user-info')
                            && !document.querySelector('.user, .sidebar-user, .user-info').innerText
                                 .trim().startsWith('U') &&
                            /@/.test(document.querySelector('.user, .sidebar-user, .user-info').innerText || ''));
          return (hasJobs || empty) && userOk;
        })()""")
        if ready:
            break
        time.sleep(0.5)
    print("   任务卡片:", t.js("document.querySelectorAll('.job').length"))
    t.shot("07-downloader.png")

    print("\n完成，输出：", OUT)
    return t


if __name__ == "__main__":
    tab = main()