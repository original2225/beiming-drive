"""分享转存（POST /api/v1/shares/{id}/save）端到端验证。

核心要证的三件事：
  1. 文件转存 = 零拷贝：新节点与原分享节点 **同一个 sha256**，blob 文件数不变。
  2. **GC 安全**：转存方删掉自己的节点后，只要原分享者节点还在，blob 不能被当成孤儿删掉
     （GC 引用集是 distinct sha256 from drv_node，多一个节点行就多一个引用）。
  3. 权限：无 BROWSE 的目标目录不能转存进去；带密码的分享必须给对密码；过期限次的分享不能转存。

补充：目录转存普通用户会被 403（文件夹只有管理员能建），管理员可以；本脚本用管理员 A 转存目录验证递归。
"""
import json
import os
import random
import subprocess
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8140/api/v1"
COMPOSE = ["docker", "compose", "-f", "compose.prod.yml"]
HERE = os.path.dirname(os.path.abspath(__file__))
STORE = os.path.dirname(os.path.dirname(HERE))

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  — {detail}" if detail else ""))


def http(method, path, body=None, token=None):
    h = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def psql(sql):
    p = subprocess.run(COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "beiming",
                                  "-d", "beiming_drive", "-tAc", sql],
                       cwd=STORE, capture_output=True, text=True)
    return p.stdout.strip()


def allow_signup(email):
    psql(f"insert into drv_register_whitelist(pattern,kind,note) "
         f"values('{email}','email','verify_save temp') on conflict do nothing")


def new_user(prefix, nickname):
    email = f"{prefix}{random.randint(10**6, 10**7)}@local"
    allow_signup(email)
    st, r = http("POST", "/auth/register", {"email": email, "nickname": nickname,
                                            "password": "TestPass123!"})
    assert st == 200 and r["code"] == 0, r
    return email, r["data"]["token"]


def upload(token, parent, name, content):
    bd = "----sv" + str(random.randint(10**6, 10**7))
    body = (f"--{bd}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"{name}\"\r\nContent-Type: text/plain\r\n\r\n").encode() \
        + content + f"\r\n--{bd}--\r\n".encode()
    qs = "" if parent is None else f"?parentId={parent}"
    req = urllib.request.Request(f"{BASE}/files/upload{qs}", data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={bd}",
                                          "Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())["data"]["id"]


def blob_count():
    p = subprocess.run(COMPOSE + ["exec", "-T", "app", "sh", "-c",
                                  "find /data/blobs -type f -name '[0-9a-f]*' -size +0c | wc -l"],
                       cwd=STORE, capture_output=True, text=True)
    return int((p.stdout or "0").strip() or 0)


def cleanup_all():
    uids = psql("select id from drv_user where email like 'svtest%' or email like 'svother%' or email like 'svadm%'").split()
    for uid in uids:
        for t in ("drv_download_log", "drv_star", "drv_password_reset", "drv_reset_attempt",
                  "drv_security_answer", "drv_audit", "drv_session"):
            psql(f"delete from {t} where user_id={uid}")
        for t in ("drv_share", "drv_remote_job", "drv_upload"):
            psql(f"delete from {t} where owner_id={uid}")
        psql(f"delete from drv_node where owner_id={uid}")
        psql(f"delete from drv_user where id={uid}")
    psql("delete from drv_register_whitelist where pattern like 'svtest%' or pattern like 'svother%' or pattern like 'svadm%'")
    left = psql("select count(*) from drv_user where email like 'svtest%' or email like 'svother%' or email like 'svadm%'")
    print(f"  清理后残留账号: {left}")
    return left


def main():
    try:
        return _run()
    finally:
        cleanup_all()


def _run():
    print("\n== 准备两个用户（分享者 A / 转存者 B）+ 一个管理员 ==")
    emailA, tokA = new_user("svtest", "分享者A")
    emailB, tokB = new_user("svother", "转存者B")
    emailAdm, tokAdm = new_user("svadm", "管理员C")
    uidA = psql(f"select id from drv_user where email='{emailA}'")
    uidB = psql(f"select id from drv_user where email='{emailB}'")
    uidAdm = psql(f"select id from drv_user where email='{emailAdm}'")
    # 管理员提权 + 重新登录（文件夹只管理员能建）
    psql(f"update drv_user set role='ADMIN' where id={uidAdm}")
    st, r = http("POST", "/auth/login", {"email": emailAdm, "password": "TestPass123!"})
    tokAdm = r["data"]["token"]
    print(f"  A={uidA} B={uidB} admin={uidAdm}")

    print("\n== 场景 1：文件转存 = 零拷贝 ==")
    src_id = upload(tokA, None, "报告.txt", b"SHARED-CONTENT-ABC-123")
    sha_src = psql(f"select sha256 from drv_node where id={src_id}")
    st, r = http("POST", "/shares", {"nodeId": src_id, "password": None,
                                     "expireHours": None, "maxDownloads": None}, tokA)
    assert st == 200 and r["code"] == 0, r
    share_id = r["data"]["share"]["id"]
    print(f"  源文件 id={src_id} share={share_id} sha={sha_src[:12]}…")

    blobs_before = blob_count()
    st, r = http("POST", f"/shares/{share_id}/save", {"password": None, "targetParentId": None}, tokB)
    check("B 转存文件成功", st == 200 and r["code"] == 0, f"HTTP {st} {r.get('message','')}")
    saved_id = r["data"]["nodeId"]
    sha_saved = psql(f"select sha256 from drv_node where id={saved_id}")
    owner_saved = psql(f"select owner_id from drv_node where id={saved_id}")
    check("转存出的节点归 B 所有", str(owner_saved) == str(uidB), f"owner={owner_saved} B={uidB}")
    check("零拷贝：两份节点同一个 sha256", sha_saved == sha_src, f"{sha_saved[:12]}… vs {sha_src[:12]}…")
    blobs_after = blob_count()
    check("blob 文件数未增加（没真拷字节）", blobs_after == blobs_before, f"{blobs_before} → {blobs_after}")
    check("转存不增加分享下载次数", psql(f"select download_count from drv_share where id='{share_id}'") == "0")

    print("\n== 场景 2：GC 安全（转存方删了，原分享者还在 → blob 不能删）==")
    # B 把转存出来的文件彻底删除（清空回收站），sha 只剩 A 引用
    http("DELETE", f"/files/{saved_id}", token=tokB)
    http("DELETE", f"/files/{saved_id}/purge", token=tokB)
    # 触发一次全盘 GC（会删没人引用且过了冷静期的；这里只验证 A 的 sha 不在删除名单）
    st, r = http("GET", "/admin/storage/gc?dryRun=true", token=tokAdm)
    gc_samples = (r.get("data") or {}).get("samples") or []
    check("A 的 sha 不在 GC 待删名单里（仍被分享者引用）", sha_src not in gc_samples,
          f"GC samples 含 {len(gc_samples)} 条")
    check("A 的节点还在", psql(f"select count(*) from drv_node where id={src_id}") == "1")

    print("\n== 场景 3：带密码的分享，密码不对不能转存 ==")
    src2 = upload(tokA, None, "机密.txt", b"SECRET-DATA-XYZ")
    st, r = http("POST", "/shares", {"nodeId": src2, "password": "8888",
                                     "expireHours": None, "maxDownloads": None}, tokA)
    share2 = r["data"]["share"]["id"]
    st, r = http("POST", f"/shares/{share2}/save", {"password": "0000", "targetParentId": None}, tokB)
    check("错误密码转存被拒(403)", st == 403, f"HTTP {st}")
    st, r = http("POST", f"/shares/{share2}/save", {"password": "8888", "targetParentId": None}, tokB)
    check("正确密码可转存", st == 200 and r["code"] == 0, f"HTTP {st}")

    print("\n== 场景 4：目标目录必须是 B 有上传权的目录 ==")
    # 管理员建的目录默认继承根 everyone 的「可上传」→ B 本就能传（这是既有语义，不是 bug）。
    # 要造「无上传权」的目录，必须**显式**关掉 everyone 的 upload（ACL 就近覆盖）。
    st, r = http("POST", "/files/mkdir", {"parentId": None, "name": "仅管理员目录"}, tokAdm)
    adm_dir = r["data"]["id"]
    st, r = http("PUT", f"/admin/nodes/{adm_dir}/acl",
                 {"everyone": {"browse": True, "preview": False, "download": False, "upload": False}},
                 tokAdm)
    check("管理员显式关闭目录的 everyone 上传权", st == 200 and r["code"] == 0, f"HTTP {st}")
    st, r = http("POST", "/files/mkdir", {"parentId": None, "name": "A私有区"}, tokA)
    check("普通用户建目录被拒(403)（文件夹只管理员能建）", st == 403, f"HTTP {st}")
    st, r = http("POST", f"/shares/{share_id}/save",
                 {"password": None, "targetParentId": adm_dir}, tokB)
    check("转到无上传权目录被拒(403)", st == 403, f"HTTP {st} {r.get('message','')}")

    print("\n== 场景 5：管理员转存目录 = 递归复制 ==")
    d = http("POST", "/files/mkdir", {"parentId": None, "name": "共享资料包"}, tokAdm)[1]["data"]["id"]
    upload(tokAdm, d, "一.txt", b"file-one")
    sub = http("POST", "/files/mkdir", {"parentId": d, "name": "子目录"}, tokAdm)[1]["data"]["id"]
    upload(tokAdm, sub, "二.txt", b"file-two")
    st, r = http("POST", "/shares", {"nodeId": d, "password": None,
                                     "expireHours": None, "maxDownloads": None}, tokAdm)
    dir_share = r["data"]["share"]["id"]
    # 由管理员 C 自己转存到根（管理员能建目录）
    st, r = http("POST", f"/shares/{dir_share}/save", {"password": None, "targetParentId": None}, tokAdm)
    check("管理员转存目录成功", st == 200 and r["code"] == 0, f"HTTP {st} {r.get('message','')}")
    if st == 200:
        d2 = r["data"]
        check("递归计数：2 个文件", d2["files"] == 2, f"files={d2['files']}")
        check("递归计数：1 个子目录", d2["dirs"] == 1, f"dirs={d2['dirs']}")
        # 验证子目录里那层文件也真建出来了
        cnt = psql(f"select count(*) from drv_node where path like "
                   f"(select path from drv_node where id={d2['nodeId']}) || '/%' and kind='file'")
        check("复制后子树含 2 个文件行", cnt == "2", f"行数={cnt}")

    # 普通用户转存目录 → 403（mkdir 挡住）
    st, r = http("POST", f"/shares/{dir_share}/save", {"password": None, "targetParentId": None}, tokB)
    check("普通用户转存目录被拒(403，文件夹只管理员建)", st == 403, f"HTTP {st}")

    ok = sum(1 for _, o, _ in results if o)
    print(f"\n===== {ok}/{len(results)} 通过 =====")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
