"""把北冥文档库的 markdown 真正同步进 beiming-drive（自研网盘）。

要点：
1. 走网盘自己的 REST API，不碰数据库 —— 这样 path/配额/blob 都由服务端按规矩算。
2. 保持原有目录结构，中文目录名要 UTF-8 编码。
3. 用 sha256 比对做增量：同名同大小同 hash 就跳过。
4. .vitepress 和 node_modules 必须排除，那是构建配置不是文档。

用法：
  python sync_docs.py [--dry-run]
"""
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8140"
SRC = r"C:\Users\15780\beiming-pan\docs_tmp\docs"
ROOT_NAME = "文档库"
EXCLUDE_DIRS = {".vitepress", "node_modules", ".git", "public"}

# 凭据走环境变量，不要写死在代码里（这是公开仓库）
EMAIL = os.environ.get("DRIVE_EMAIL", "admin@beiming.local")
PASSWORD = os.environ.get("DRIVE_PASSWORD", "")

if not PASSWORD:
    sys.exit(
        "缺少密码。这个脚本需要登录网盘，请先设置环境变量：\n"
        "  bash:  export DRIVE_PASSWORD='你的密码'\n"
        "  cmd :  set DRIVE_PASSWORD=你的密码\n"
        "  ps  :  $env:DRIVE_PASSWORD='你的密码'"
    )


def call(path, method="GET", body=None, token=None, raw_body=None, ctype=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if raw_body is not None:
        payload = raw_body
        headers["Content-Type"] = ctype or "application/octet-stream"
    elif body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    else:
        payload = None

    req = urllib.request.Request(BASE + path, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"http": e.code, "raw": e.read().decode("utf-8", "replace")[:400]}


def login():
    d = call("/api/v1/auth/login", "POST", {"email": EMAIL, "password": PASSWORD})
    if d.get("code") != 0:
        raise SystemExit(f"登录失败: {d}")
    return d["data"]["token"]


def list_dir(token, parent_id):
    q = "" if parent_id is None else f"?parentId={parent_id}"
    d = call("/api/v1/files" + q, token=token)
    if d.get("code") != 0:
        return []
    return d["data"]["items"]


def find_child(token, parent_id, name):
    for it in list_dir(token, parent_id):
        if it["name"] == name:
            return it
    return None


def ensure_dir(token, parent_id, name):
    """目录存在就复用，不存在就建。返回 id。"""
    ex = find_child(token, parent_id, name)
    if ex:
        return ex["id"]
    d = call("/api/v1/files/mkdir", "POST", {"parentId": parent_id, "name": name}, token=token)
    if d.get("code") != 0:
        raise SystemExit(f"建目录失败 {name}: {d}")
    return d["data"]["id"]


def upload_multipart(token, parent_id, local_path):
    """小文件直传：multipart/form-data。"""
    name = os.path.basename(local_path)
    with open(local_path, "rb") as f:
        data = f.read()
    boundary = "----beimingdrive" + hashlib.md5(name.encode()).hexdigest()[:16]
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
        f"Content-Type: text/markdown\r\n\r\n"
    ).encode("utf-8") + data + f"\r\n--{boundary}--\r\n".encode("utf-8")

    req = urllib.request.Request(
        f"{BASE}/api/v1/files/upload?parentId={parent_id}",
        data=body, method="POST",
        headers={"Authorization": "Bearer " + token,
                 "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"http": e.code, "raw": e.read().decode("utf-8", "replace")[:400]}


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def walk_docs():
    """产出 (相对目录列表, 文件名, 绝对路径)"""
    out = []
    for dirpath, dirnames, filenames in os.walk(SRC):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for fn in filenames:
            if not fn.lower().endswith(".md"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, SRC)
            parts = rel.replace("\\", "/").split("/")
            out.append((parts[:-1], parts[-1], full))
    return sorted(out, key=lambda x: x[0])


def main():
    dry = "--dry-run" in sys.argv
    token = login()
    files = walk_docs()
    print(f"待同步 {len(files)} 个 md")

    root_id = ensure_dir(token, None, ROOT_NAME)
    print(f"根目录「{ROOT_NAME}」id={root_id}")

    dir_cache = {(): root_id}
    created_dirs = uploaded = skipped = 0

    for dir_parts, name, full in files:
        # 逐级确保目录存在
        key = tuple(dir_parts)
        if key not in dir_cache:
            parent = dir_cache[tuple(dir_parts[:-1])]
            dir_cache[key] = ensure_dir(token, parent, dir_parts[-1])
            created_dirs += 1
        parent_id = dir_cache[key]

        size = os.path.getsize(full)
        digest = sha256_of(full)
        existing = find_child(token, parent_id, name)
        if existing and not existing.get("kind") == "dir" \
                and existing.get("sha256") == digest:
            skipped += 1
            continue

        if dry:
            print(f"  [dry] {('/'.join(dir_parts) + '/' if dir_parts else '')}{name}  {size}B")
            uploaded += 1
            continue

        r = upload_multipart(token, parent_id, full)
        if r.get("code") == 0:
            uploaded += 1
            print(f"  ok  {'/'.join(dir_parts)}/{name}  {size}B")
        else:
            print(f"  !!  {'/'.join(dir_parts)}/{name}  -> {r}")
            return 1

    print(f"\n完成：新建目录 {created_dirs}，上传 {uploaded}，跳过(已存在) {skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())