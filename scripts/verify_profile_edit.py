# -*- coding: utf-8 -*-
"""验证个人页三条真实交互：改昵称（含侧栏同步）、密码校验、头像入口。"""
import json, urllib.request, time
from websocket import create_connection
B="http://127.0.0.1:8140"; CDP="http://127.0.0.1:9444"
tabs=json.loads(urllib.request.urlopen(CDP+"/json/list",timeout=10).read())
page=next(t for t in tabs if t["type"]=="page")
ws=create_connection(page["webSocketDebuggerUrl"],timeout=120); _id=[0]
def cmd(m,**kw):
    _id[0]+=1; ws.send(json.dumps({"id":_id[0],"method":m,"params":kw}))
    while True:
        r=json.loads(ws.recv())
        if r.get("id")==_id[0]:
            if "error" in r: raise RuntimeError(f"{m}: {r['error']}")
            return r.get("result",{})
def js(e):
    r=cmd("Runtime.evaluate",expression=e,returnByValue=True,awaitPromise=True)
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:250]}"
    return r.get("result",{}).get("value")

def typeinto(sel, text):
    """用真实键盘输入：先聚焦、清空、再逐字敲，触发 Vue 的 v-model。"""
    js(f"""(() => {{ const e=document.querySelector(%s); e.focus(); e.value=''; }})()""" % json.dumps(sel))
    for ch in text:
        cmd("Input.insertText", text=ch)
    js(f"""(() => {{ const e=document.querySelector(%s); e.dispatchEvent(new Event('input',{{bubbles:true}})); }})()""" % json.dumps(sel))

def click(sel, expect=None, wait=1.2):
    pos = js("""(() => { const el=document.querySelector(%s); if(!el) return null;
      const r=el.getBoundingClientRect();
      return {x:Math.round(r.x+r.width/2), y:Math.round(r.y+r.height/2)}; })()""" % json.dumps(sel))
    if not pos: raise RuntimeError("没命中: "+sel)
    before = js("location.pathname + location.search")
    for t in ("mousePressed","mouseReleased"):
        cmd("Input.dispatchMouseEvent", type=t, x=pos["x"], y=pos["y"], button="left",
            buttons=1 if t=="mousePressed" else 0, clickCount=1)
    time.sleep(wait)
    after = js("location.pathname + location.search")
    if expect and after!=expect:
        raise RuntimeError(f"{sel} 点了没到 {expect}，实际 {after}")
    return before, after

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B+"/profile"); time.sleep(3.5)

print("=== A. 改昵称 ===")
orig = js("document.querySelector('.hero-name')?.textContent?.trim().split(' ')[0]")
print("  原昵称:", orig)
tmp = "花蝉测试"
typeinto(".row-edit .ipt", tmp)
time.sleep(0.3)
print("  按钮可用(说明识别到改动):", js("!document.querySelector('.row-edit .btn')?.disabled"))
click(".row-edit .btn", wait=1.6)
print("  提示:", js("document.querySelector('.msg')?.textContent"))
print("  页内昵称:", js("document.querySelector('.hero-name')?.textContent?.trim().split(' ')[0]"))

# 侧栏同步（同页面就有侧栏）
print("  侧栏昵称:", js("document.querySelector('.user-name')?.textContent"))

# 改回去
typeinto(".row-edit .ipt", orig)
time.sleep(0.3)
click(".row-edit .btn", wait=1.6)
print("  已还原为:", js("document.querySelector('.hero-name')?.textContent?.trim().split(' ')[0]"))
print("  侧栏:", js("document.querySelector('.user-name')?.textContent"))

print("\n=== B. 密码校验（不真改）===")
# 两次不一致
js("""(() => { const i=[...document.querySelectorAll('.pw-grid .ipt')];
  i[0].value='whatever123'; i[1].value='newpass12345'; i[2].value='diffpass12345';
  i.forEach(e=>e.dispatchEvent(new Event('input',{bubbles:true}))); })()""")
click(".pw-foot .btn", wait=0.8)
print("  两次不一致:", js("document.querySelector('.pw-foot .msg')?.textContent"))

# 新密码太短
js("""(() => { const i=[...document.querySelectorAll('.pw-grid .ipt')];
  i[0].value='x'; i[1].value='short'; i[2].value='short';
  i.forEach(e=>e.dispatchEvent(new Event('input',{bubbles:true}))); })()""")
click(".pw-foot .btn", wait=0.8)
print("  新密码太短:", js("document.querySelector('.pw-foot .msg')?.textContent"))

# 旧密码错误（走服务端）
js("""(() => { const i=[...document.querySelectorAll('.pw-grid .ipt')];
  i[0].value='definitely-wrong-pw'; i[1].value='newpass12345'; i[2].value='newpass12345';
  i.forEach(e=>e.dispatchEvent(new Event('input',{bubbles:true}))); })()""")
click(".pw-foot .btn", wait=1.8)
print("  旧密码错误(服务端):", js("document.querySelector('.pw-foot .msg')?.textContent"))

print("\n=== C. 头像入口 ===")
print("  头像按钮存在:", js("!!document.querySelector('.ava')"))
print("  隐藏 input 存在:", js("!!document.querySelector('.ava-input')"))
print("  accept:", js("document.querySelector('.ava-input')?.accept"))
print("  点它会触发选择框(检查 click 是否被派发):", js("""(() => {
    const inp=document.querySelector('.ava-input'); let hit=false;
    inp.addEventListener('click', ()=>{hit=true}, {once:true});
    document.querySelector('.ava').click();
    return hit; })()"""))

print("\n=== D. 退出确认弹窗（不真退）===")
click(".card:last-of-type .btn-danger", wait=0.6)
print("  弹窗出现:", js("!!document.querySelector('.dlg')"))
print("  标题:", js("document.querySelector('.dlg-title')?.textContent"))
click(".dlg-ops .btn:first-child", wait=0.6)   # 取消
print("  取消后弹窗消失:", js("!document.querySelector('.dlg')"))
print("  仍在个人页:", js("location.pathname"))
