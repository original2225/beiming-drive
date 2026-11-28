"""游客公开链路（is_public）端到端验证。

口径（V9 迁移定的，别改）：is_public **只对节点自己生效，不继承**。
母目录公开、子文件不公开时，游客点进去看到的是空壳 —— 这是设计不是 bug。

验的东西：
  1. 游客（无 token）只能看到 is_public=true 的条目
  2. 私有条目不出现在公开列表里 / 按 id 直取 404
  3. 公开文件游客能 Range 下载（206 + Content-Range），能下到首字节
  4. 私有文件游客下载 404
  5. **不继承**：母目录公开，里面没标公开的子文件，游客在母目录里看不到它，
     且按 id 直取 404 —— 这一条要明写，不然下一轮会被当 bug 改回去
  6. 目录不公开时，带 parentId 枚举其子项 → 404（不能当目录枚举器）
  7. 设置公开接口：非 owner/管理员改不了（403）
全程用独立测试用户，结束连账号带文件一起删。
"""
import json
import os
import random
import subprocess
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8140/api/v1"
PUB = "http://127.0.0.1:8140/api/public"
COMPOSE = ["docker", "compose", "-f", "compose.prod.yml"]
HERE = os.path.dirname(os.path.abspath(__file__))
STORE = os.path.dirname(os.path.dirname(HERE))

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f"  — {detail}" if detail else ""))


def http(method, url, body=None, token=None, raw=False, headers=None):
    h = dict(headers or {})
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            payload = r.read()
            if raw:
                return r.status, payload, dict(r.headers)
            return r.status, (json.loads(payload) if payload else {})
    except urllib.error.HTTPError as e:
        payload = e.read()
        if raw:
            return e.code, payload, dict(e.headers)
        try:
            return e.code, json.loads(payload or b"{}")
        except Exception:
            return e.code, {"raw": payload[:300].decode("utf-8", "replace")}


def psql(sql):
    p = subprocess.run(COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "beiming",
                                  "-d", "beiming_drive", "-tAc", sql],
                       cwd=STORE, capture_output=True, text=True)
    return p.stdout.strip()


def allow_signup(email, kind="email"):
    psql(f"insert into drv_register_whitelist(pattern,kind,note) "
         f"values('{email}','{kind}','verify_public temp') on conflict do nothing")


def cleanup_all():
    """按前缀兜底清理：不管测试崩在哪一步，都能把本脚本造的东西清干净。
    （之前崩在中途会留下 pubtest* 账号和公开节点，游客页就露测试垃圾。）"""
    uids = psql("select id from drv_user where email like 'pubtest%' or email like 'railcheck%'").split()
    for uid in uids:
        for t in ("drv_download_log", "drv_star", "drv_password_reset", "drv_reset_attempt",
                  "drv_security_answer", "drv_audit", "drv_session"):
            psql(f"delete from {t} where user_id={uid}")
        for t in ("drv_share", "drv_remote_job", "drv_upload"):
            psql(f"delete from {t} where owner_id={uid}")
        psql(f"delete from drv_node where owner_id={uid}")
        psql(f"delete from drv_user where id={uid}")
    psql("delete from drv_register_whitelist where pattern like 'pubtest%' or pattern like 'railcheck%'")
    left = psql("select count(*) from drv_user where email like 'pubtest%' or email like 'railcheck%'")
    print(f"  清理后残留账号: {left}")
    return left


def main():
    try:
        return _run()
    finally:
        cleanup_all()


def _run():
    print("\n== 准备测试账号 + 目录结构 ==")
    email = f"pubtest{random.randint(10**6, 10**7)}@local"
    allow_signup(email)
    st, r = http("POST", BASE + "/auth/register",
                 {"email": email, "nickname": "公开测试", "password": "TestPass123!"})
    if st != 200 or r.get("code") != 0:
        print("注册失败:", st, r)
        return 1
    token = r["data"]["token"]
    uid = psql(f"select id from drv_user where email='{email}'")
    print(f"  uid={uid} email={email}")

    # 文件夹只有管理员能建（既定权限模型），主测试账号提成 ADMIN 再重新登录
    psql(f"update drv_user set role='ADMIN' where id={uid}")
    st, r = http("POST", BASE + "/auth/login", {"email": email, "password": "TestPass123!"})
    token = r["data"]["token"]

    def mkdir(parent, name):
        st, r = http("POST", BASE + "/files/mkdir", {"parentId": parent, "name": name}, token)
        assert st == 200 and r["code"] == 0, r
        return r["data"]["id"]

    def upload(parent, name, content):
        boundary = "----pubtest" + str(random.randint(10**6, 10**7))
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                f"filename=\"{name}\"\r\nContent-Type: text/plain\r\n\r\n").encode() \
            + content + f"\r\n--{boundary}--\r\n".encode()
        qs = "" if parent is None else f"?parentId={parent}"
        req = urllib.request.Request(
            f"{BASE}/files/upload{qs}", data=body, method="POST",
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                     "Authorization": "Bearer " + token})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())["data"]["id"]

    def set_public(nid, val):
        return http("PATCH", f"{BASE}/files/{nid}/visibility", {"isPublic": val}, token)

    # 根下建：公开文件 pub.txt、私有文件 priv.txt、公开目录 pubdir（内含 公开 pubin.txt + 私有 privin.txt）
    pubfile = upload(None, "pub.txt", b"hello-public-content-0123456789")
    privfile = upload(None, "priv.txt", b"secret-private-content")
    pubdir = mkdir(None, "公开资料夹")
    pubin = upload(pubdir, "pubin.txt", b"inside-public")
    privin = upload(pubdir, "privin.txt", b"inside-private")

    print("\n== 1. 设为公开 ==")
    st, r = set_public(pubfile, True)
    check("公开 pub.txt 成功", st == 200 and r["data"]["isPublic"] is True, f"HTTP {st}")
    st, r = set_public(pubdir, True)
    check("公开 pubdir 成功", st == 200 and r["data"]["isPublic"] is True, f"HTTP {st}")
    st, r = set_public(pubin, True)
    check("公开 pubin.txt 成功", st == 200 and r["data"]["isPublic"] is True, f"HTTP {st}")
    # privfile / privin 保持不公开

    print("\n== 2. 游客列表（无 token）==")
    st, r = http("GET", PUB + "/nodes")
    ids = [x["id"] for x in r.get("data", {}).get("items", [])]
    check("游客列公开内容 200", st == 200 and r.get("code") == 0, f"HTTP {st}")
    check("列表含 pub.txt", pubfile in ids, f"ids={ids[:20]}")
    check("列表含 pubdir", pubdir in ids)
    check("列表含 pubin.txt（任意层平铺）", pubin in ids)
    check("列表不含私有 priv.txt", privfile not in ids)
    check("列表不含私有 privin.txt", privin not in ids)

    print("\n== 3. 游客按 id 直取 ==")
    st, r = http("GET", f"{PUB}/nodes/{pubfile}")
    check("公开文件按 id 可取", st == 200 and r["data"]["id"] == pubfile, f"HTTP {st}")
    st, r = http("GET", f"{PUB}/nodes/{privfile}")
    check("私有文件按 id 取 404", st in (403, 404), f"HTTP {st}")

    print("\n== 4. 游客下载（Range）==")
    st, body, hdrs = http("GET", f"{PUB}/nodes/{pubfile}/dl", raw=True)
    check("公开文件下载 200 且内容对", st == 200 and body == b"hello-public-content-0123456789",
          f"HTTP {st} len={len(body)}")
    st, body, hdrs = http("GET", f"{PUB}/nodes/{pubfile}/dl", raw=True,
                          headers={"Range": "bytes=0-4"})
    check("Range 下载 206 + Content-Range", st == 206 and body == b"hello" and
          hdrs.get("Content-Range", "").startswith("bytes 0-4/"),
          f"HTTP {st} range={hdrs.get('Content-Range')} body={body}")
    st, body, hdrs = http("GET", f"{PUB}/nodes/{privfile}/dl", raw=True)
    check("私有文件下载 404", st in (403, 404), f"HTTP {st}")

    print("\n== 5. 不继承（核心口径，不是 bug）==")
    # pubdir 公开，但里面 privin.txt 没标公开 → 游客进目录只该看到 pubin
    st, r = http("GET", f"{PUB}/nodes?parentId={pubdir}")
    sub_ids = [x["id"] for x in r.get("data", {}).get("items", [])]
    check("公开目录内只列公开子项", st == 200 and sub_ids == [pubin],
          f"HTTP {st} sub={sub_ids}（应为 [{pubin}]，privin={privin} 不该出现）")
    st, r = http("GET", f"{PUB}/nodes/{privin}")
    check("母目录公开≠子文件公开：privin 按 id 仍 404", st in (403, 404), f"HTTP {st}")

    print("\n== 6. 目录不公开时不能枚举 ==")
    st, r = http("GET", f"{PUB}/nodes?parentId={pubfile}")  # 文件不是目录
    check("对文件当目录枚举 → 400", st == 400, f"HTTP {st}")
    # 造一个私有目录，带 parentId 枚举应 404
    privdir = mkdir(None, "私有目录")
    st, r = http("GET", f"{PUB}/nodes?parentId={privdir}")
    check("私有目录带 parentId 枚举 → 404（不当枚举器）", st in (403, 404), f"HTTP {st}")

    print("\n== 7. 非 owner 不能设置公开 ==")
    email2 = f"pubtest2{random.randint(10**6, 10**7)}@local"
    allow_signup(email2)
    st, r = http("POST", BASE + "/auth/register",
                 {"email": email2, "nickname": "路人", "password": "TestPass123!"})
    token2 = r["data"]["token"]
    st, r = http("PATCH", f"{BASE}/files/{privfile}/visibility", {"isPublic": True}, token2)
    check("别人给我的私有文件设公开 → 403", st == 403, f"HTTP {st} {r.get('message')}")

    print("\n== 收尾：清理测试账号与文件 ==")
    for e in (email, email2):
        u = psql(f"select id from drv_user where email='{e}'")
        if not u:
            continue
        for t, col in [("drv_download_log", "user_id"), ("drv_star", "user_id"),
                       ("drv_password_reset", "user_id"), ("drv_reset_attempt", "user_id"),
                       ("drv_security_answer", "user_id"), ("drv_share", "owner_id"),
                       ("drv_remote_job", "owner_id"), ("drv_upload", "owner_id"),
                       ("drv_audit", "user_id"), ("drv_session", "user_id")]:
            psql(f"delete from {t} where {col}={u}")
        psql(f"delete from drv_node where owner_id={u}")
        psql(f"delete from drv_user where id={u}")
        psql(f"delete from drv_register_whitelist where pattern='{e}'")
    left = psql(f"select count(*) from drv_user where email in ('{email}','{email2}')")
    print(f"  清理后残留账号: {left}")

    ok = sum(1 for _, o, _ in results if o)
    total = len(results)
    print(f"\n===== {ok}/{total} 通过 =====")
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())
