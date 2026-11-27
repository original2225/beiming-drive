"""回收站 30 天自动清理 + blob 释放 的端到端验证。

全程用一个独立测试用户（随机邮箱）+ 他自己的文件，不动线上真实数据。
测试用户临时提成 ADMIN，才能打 /admin/trash/cleanup 这个手动触发口
（也就是凌晨 3 点 cron 走的同一段代码）；结束连账号一起删掉。

验的东西：
  1. 删非空文件夹 → 回收站只列顶层 1 条
  2. 彻底删除 → 磁盘 blob 真的没了
  3. 同一内容两个节点 → 删一个，另一个还能用
  4. trashed_at 推到 31 天前 → 清理任务带走，blob 释放
  5. 恢复时原位置被占用 → 自动改名，不 500
  6. trashed_at 29 天的不能被清（边界）
  7. 孤儿对账收掉没人引用的，up-*.part 不碰
  8. 非 ADMIN 打管理接口要被 403 挡住
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
STORE = os.path.dirname(HERE)

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("  " + detail if detail else ""))


def http(method, path, body=None, token=None, files=None):
    headers = {}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if files is not None:
        boundary = "----b" + str(random.randint(10 ** 8, 10 ** 9))
        chunks = []
        for k, v in files.items():
            chunks.append(
                (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"; "
                 f"filename=\"{v[0]}\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
                + v[1] + b"\r\n")
        chunks.append(f"--{boundary}--\r\n".encode())
        data = b"".join(chunks)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300].decode("utf-8", "replace")}


def psql(sql):
    p = subprocess.run(COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "beiming",
                                  "-d", "beiming_drive", "-tAc", sql],
                       cwd=STORE, capture_output=True, text=True)
    return p.stdout.strip()


def in_app(cmd):
    p = subprocess.run(COMPOSE + ["exec", "-T", "app", "sh", "-c", cmd],
                       cwd=STORE, capture_output=True, text=True)
    return p.stdout.strip()


def blob_exists(sha):
    return in_app(f"test -f /data/blobs/{sha[:2]}/{sha[2:4]}/{sha} && echo yes || echo no") == "yes"


def blob_count():
    return int((in_app("find /data/blobs -type f -name '[0-9a-f]*' -size +0c | wc -l") or "0").strip())


def main():
    print("\n== 准备测试账号 ==")
    email = f"trashtest{random.randint(10 ** 6, 10 ** 7)}@local"
    st, r = http("POST", "/auth/register", {"email": email, "nickname": "回收站测试",
                                            "password": "TestPass123!"})
    if st != 200 or r.get("code") != 0:
        print("注册失败:", st, r)
        return 1
    token = r["data"]["token"]
    uid = psql(f"select id from drv_user where email='{email}'")
    print(f"  uid={uid}  email={email}")

    st, r = http("POST", "/admin/trash/cleanup", token=token)
    check("普通用户打 /admin/trash/cleanup 被拒（403）", st == 403, f"HTTP {st}")

    psql(f"update drv_user set role='ADMIN' where id={uid}")
    st, r = http("POST", "/auth/login", {"email": email, "password": "TestPass123!"})
    token = r["data"]["token"]
    check("提权后重新登录拿到可用 token", st == 200 and r.get("code") == 0, str(r.get("message")))

    def cleanup():
        return http("POST", "/admin/trash/cleanup", token=token)

    try:
        print("\n== 1. 删非空文件夹：回收站只该列顶层 ==")
        st, r = http("POST", "/files/mkdir", {"parentId": None, "name": "整层"}, token)
        folder = r["data"]["id"]
        payload_a = ("content-A-" + str(random.random())).encode() * 200
        st, r = http("POST", f"/files/upload?parentId={folder}", token=token,
                     files={"file": ("a.txt", payload_a)})
        sha_a = r["data"]["sha256"]
        st, r = http("POST", "/files/mkdir", {"parentId": folder, "name": "子目录"}, token)
        child_dir = r["data"]["id"]
        http("POST", f"/files/upload?parentId={child_dir}", token=token,
             files={"file": ("b.txt", payload_a + b"diff")})

        st, r = http("DELETE", f"/files/{folder}", token=token)
        check("删非空文件夹成功", st == 200 and r.get("code") == 0, str(r.get("message")))

        st, r = http("GET", "/files/trash", token=token)
        names = [i["name"] for i in r["data"]]
        check("回收站只列 1 条顶层", len(names) == 1 and names[0] == "整层", f"实际: {names}")
        in_db = psql(f"select count(*) from drv_node where owner_id={uid} and status='TRASHED'")
        check("库里 4 条 TRASHED（子树都标记，界面只显顶层）", in_db == "4", f"实际 {in_db}")

        st, r = http("GET", "/files/trash/summary", token=token)
        check("summary 条目数=1（口径与列表一致）", r["data"]["entries"] == 1, str(r["data"]))
        check("summary 带回 retentionDays=30", r["data"]["retentionDays"] == 30, str(r["data"]))

        print("\n== 1b. 删掉后再建同名（原 500 bug，回收站独立命名空间后不该再报错） ==")
        st, r = http("POST", "/files/mkdir", {"parentId": None, "name": "重名目录"}, token)
        check("建目录成功", r.get("code") == 0, str(r.get("message")))
        http("DELETE", f"/files/{r['data']['id']}", token=token)
        st, r = http("POST", "/files/mkdir", {"parentId": None, "name": "重名目录"}, token)
        check("同名目录能再建（不撞唯一约束）", st == 200 and r.get("code") == 0,
              f"HTTP {st} {r.get('message')}")
        if r.get("code") == 0:
            check("新目录没被莫名加上序号", r["data"]["name"] == "重名目录", r["data"]["name"])
        st, r = http("POST", "/files/upload", token=token, files={"file": ("重名.txt", b"v1")})
        http("DELETE", f"/files/{r['data']['id']}", token=token)
        st, r = http("POST", "/files/upload", token=token, files={"file": ("重名.txt", b"v2")})
        check("同名文件能再上传（不撞唯一约束）", st == 200 and r.get("code") == 0,
              f"HTTP {st} {r.get('message')}")
        if r.get("code") == 0:
            check("新文件没被莫名加上序号", r["data"]["name"] == "重名.txt", r["data"]["name"])
        st, r = http("GET", "/files/trash", token=token)
        trashed_paths = psql(f"select path from drv_node where owner_id={uid} and status='TRASHED'")
        print(f"  回收站里的 path 样例: {trashed_paths.splitlines()[:3]}")

        print("\n== 2. 彻底删除要真的释放磁盘 ==")
        before = blob_count()
        st, r = http("DELETE", f"/files/{folder}/purge", token=token)
        check("彻底删除成功", st == 200 and r.get("code") == 0, str(r.get("message")))
        d = r.get("data") or {}
        check("响应报告释放字节数 > 0", d.get("freedBytes", 0) > 0, str(d))
        check("a.txt 的 blob 已从磁盘消失", not blob_exists(sha_a), f"sha={sha_a[:12]}…")
        after = blob_count()
        check("blob 总数少了 2", after == before - 2, f"{before} → {after}")
        # 只查被删的那棵子树：1b 里为了验重名建过「重名目录」「重名.txt」，它们该留着
        check("被删子树的节点记录全清（含子孙）",
              psql(f"select count(*) from drv_node where owner_id={uid} "
                   f"and (path like '/.trash/{folder}%' or path like '/整层%')") == "0")

        print("\n== 3. 同一内容两个节点：删一个，另一个还在 ==")
        same = ("dedupe-" + str(random.random())).encode() * 100
        st, r = http("POST", "/files/upload", token=token, files={"file": ("x1.txt", same)})
        n1 = r["data"]
        st, r = http("POST", "/files/upload", token=token, files={"file": ("x2.txt", same)})
        n2 = r["data"]
        check("两次上传拿到同一个 sha（去重成立）", n1["sha256"] == n2["sha256"],
              n1["sha256"][:12] + "…")
        sha_shared = n1["sha256"]
        http("DELETE", f"/files/{n1['id']}", token=token)
        http("DELETE", f"/files/{n1['id']}/purge", token=token)
        check("删掉一份后 blob 仍在（另一个节点还引用）", blob_exists(sha_shared),
              f"sha={sha_shared[:12]}…")
        st, r = http("GET", f"/files/{n2['id']}", token=token)
        check("另一份条目还能取到", st == 200 and r["data"]["id"] == n2["id"])

        print("\n== 4. 推到 31 天前 → 清理任务带走 ==")
        http("DELETE", f"/files/{n2['id']}", token=token)
        psql(f"update drv_node set trashed_at = now() - interval '31 days' "
             f"where owner_id={uid} and id={n2['id']}")
        st, r = cleanup()
        check("手动触发清理接口返回成功", st == 200 and r.get("code") == 0, str(r.get("message")))
        expired = (r.get("data") or {}).get("expiredPurged", -1)
        check("本轮清掉 ≥1 条到期条目", expired >= 1, f"expiredPurged={expired}")
        check("到期条目已被删除",
              psql(f"select count(*) from drv_node where owner_id={uid} and id={n2['id']}") == "0")
        check("它的 blob 也释放了", not blob_exists(sha_shared), f"sha={sha_shared[:12]}…")

        print("\n== 5. 恢复时原位置被占用 → 自动改名，不 500 ==")
        st, r = http("POST", "/files/upload", token=token,
                     files={"file": ("撞名.txt", b"original-version")})
        old_id = r["data"]["id"]
        http("DELETE", f"/files/{old_id}", token=token)
        st, r = http("POST", "/files/upload", token=token,
                     files={"file": ("撞名.txt", b"newer-version")})
        check("同名新文件建成功，占住原路径", r.get("code") == 0, str(r.get("message")))
        st, r = http("POST", f"/files/{old_id}/restore", token=token)
        check("恢复没被唯一约束打回 500", st == 200 and r.get("code") == 0,
              f"HTTP {st} {r.get('message')}")
        if st == 200 and r.get("code") == 0:
            # 回收站用独立命名空间后，同名文件上传不再撞唯一约束，
            # 于是能同时存在「撞名.txt」和回收站里的老版本。
            # 恢复时原位置被占 → 老版本自动改名（撞名 (1).txt），新的保留原名。
            check("恢复成功且拿到一个新名字", r["data"]["name"] != "撞名.txt", r["data"]["name"])
            check("恢复后状态 READY", r["data"]["status"] == "READY", r["data"]["status"])
            st2, r2 = http("GET", "/files", token=token)
            names_now = sorted(i["name"] for i in r2["data"]["items"])
            check("两个文件都在", len([n for n in names_now if "撞名" in n]) == 2, str(names_now))

        print("\n== 6. 29 天的不许被清（边界） ==")
        st, r = http("POST", "/files/upload", token=token,
                     files={"file": ("未到期.txt", b"keep-me-please")})
        keep_id = r["data"]["id"]
        sha_keep = r["data"]["sha256"]
        http("DELETE", f"/files/{keep_id}", token=token)
        psql(f"update drv_node set trashed_at = now() - interval '29 days' "
             f"where owner_id={uid} and id={keep_id}")
        cleanup()
        check("29 天的条目还在（不能提前清）",
              psql(f"select count(*) from drv_node where owner_id={uid} and id={keep_id}") == "1")
        check("它的 blob 也还在", blob_exists(sha_keep), f"sha={sha_keep[:12]}…")

        print("\n== 7. 孤儿对账：收掉没人引用的，不认识的形状不碰 ==")
        orphan = "e" * 64
        # 用 touch -d 把时间推旧：collect 有 30 分钟冷静期（保护正在上传、节点还没入库的文件），
        # 刚 echo 出来的文件会被正确跳过 —— 那是代码对，测试得模拟「一个早就存在的孤儿」。
        in_app(f"mkdir -p /data/blobs/{orphan[:2]}/{orphan[2:4]} && "
               f"echo garbage > /data/blobs/{orphan[:2]}/{orphan[2:4]}/{orphan} && "
               f"touch -d '2020-01-01 00:00:00' /data/blobs/{orphan[:2]}/{orphan[2:4]}/{orphan} && "
               "echo tmp > /data/blobs/up-notablob.part")
        check("孤儿 blob 已就位", blob_exists(orphan))
        st, r = http("GET", "/admin/storage/gc?dryRun=true", token=token)
        samples = (r.get("data") or {}).get("samples") or []
        check("dryRun 预演能报出这个孤儿", st == 200 and orphan in samples, f"HTTP {st}")
        check("dryRun 之后孤儿仍在磁盘上（预演不动手）", blob_exists(orphan))
        cleanup()
        check("真跑之后孤儿被收掉", not blob_exists(orphan))
        check("up-*.part 这种临时文件没被碰",
              in_app("test -f /data/blobs/up-notablob.part && echo yes || echo no") == "yes")

    finally:
        print("\n== 收尾：清掉测试用户 ==")
        psql(f"delete from drv_node where owner_id={uid}; delete from drv_user where id={uid};")
        in_app("rm -f /data/blobs/up-notablob.part")
        check("测试用户已删除", psql(f"select count(*) from drv_user where id={uid}") == "0")

    print("\n" + "=" * 62)
    failed = [n for n, ok, _ in results if not ok]
    print(f"共 {len(results)} 项，通过 {len(results) - len(failed)}，失败 {len(failed)}")
    for n in failed:
        print("  失败: " + n)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())