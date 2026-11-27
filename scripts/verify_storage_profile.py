# -*- coding: utf-8 -*-
"""验收：存储空间页 + 个人信息页（接口 + 真实交互）。"""
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
    if "exceptionDetails" in r: return f"<ERR>{str(r['exceptionDetails'])[:200]}"
    return r.get("result",{}).get("value")

cmd("Page.enable"); cmd("Runtime.enable")
cmd("Network.setCacheDisabled", cacheDisabled=True)
cmd("Emulation.setDeviceMetricsOverride", width=1440, height=900, deviceScaleFactor=2, mobile=False)
cmd("Page.navigate", url=B+"/"); time.sleep(3.5)

tok = js("localStorage.getItem('drive_token') || ''")
print("token 取到:", bool(tok))

# --- 接口直测 ---
def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(B+"/api/v1"+path, method=method)
    req.add_header("Authorization", "Bearer "+(token or ""))
    data=None
    if body is not None:
        data=json.dumps(body).encode()
        req.add_header("Content-Type","application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=20) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")

print("\n--- GET /files/storage ---")
st, r = api("/files/storage", token=tok)
print("  HTTP", st)
if st==200:
    d=r["data"]
    print("  used:", d["used"], "quota:", d["quota"], "trashBytes:", d["trashBytes"], "trashFiles:", d["trashFiles"])
    print("  buckets:")
    for b in d["buckets"]:
        print(f"    {b['key']:8} {b['label']:5} files={b['files']:<4} bytes={b['bytes']}")
else:
    print("  ", r)

print("\n--- GET /auth/profile ---")
st2, r2 = api("/auth/profile", token=tok)
print("  HTTP", st2)
if st2==200:
    print("  ", {k:v for k,v in r2["data"].items() if k!='avatarKey'})
else:
    print("  ", r2)
