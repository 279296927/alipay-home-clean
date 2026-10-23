#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub REST API 提交文件，完全绕开被阻断的 github.com:443。

背景：本机网络对 github.com:443 间歇性超时（直连 31s 失败），
      但 api.github.com 始终可达，于是改用 Git Data API 提交。

用法：
    python3 gh_api_publish.py <owner/repo> <本地目录> <分支> <提交信息>

  只依赖 api.github.com，token 从环境变量 GITHUB_TOKEN 读取。

实现即 Git 的对象模型，四步：
    blob（文件内容） → tree（目录快照，基于 base_tree） → commit（快照+父提交） → ref（移动分支指针）
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def call(method, path, token, payload=None, tolerate=()):
    """发一个 API 请求；tolerate 里的状态码不视为错误。"""
    url = API + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(body) if body.strip() else {})
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        if e.code in tolerate:
            return e.code, (json.loads(body) if body.strip() else {})
        raise RuntimeError("%s %s → HTTP %s\n%s" % (method, path, e.code, body[:400]))


def load_token():
    """优先环境变量，其次 ~/.gh_token（内容形如 export GITHUB_TOKEN=...）。"""
    tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if tok:
        return tok
    path = os.path.join(os.path.expanduser("~"), ".gh_token")
    if os.path.isfile(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line.startswith("export GITHUB_TOKEN="):
                    return line.split("=", 1)[1].strip().strip("'\"")
    return ""


def collect(root):
    """收集目录下的文件（跳过 .git），返回 {相对路径: bytes}。"""
    out = {}
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d != ".git"]
        for fn in files:
            full = os.path.join(base, fn)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            with open(full, "rb") as f:
                out[rel] = f.read()
    return out


def main():
    if len(sys.argv) < 5:
        print("用法: gh_api_publish.py <owner/repo> <目录> <分支> <提交信息>")
        return 2
    repo, root, branch, message = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    token = load_token()
    if not token:
        print("✗ 缺少 GITHUB_TOKEN（可写入 ~/.gh_token，或用 bash set_token.sh 安装）")
        return 1
    if not os.path.isdir(root):
        print("✗ 目录不存在：%s" % root)
        return 1

    files = collect(root)
    if "index.html" not in files:
        print("✗ 目录里没有 index.html，拒绝提交")
        return 1
    print("  提交 %d 个文件" % len(files))

    # 当前分支指向的 commit
    st, ref = call("GET", "/repos/%s/git/ref/heads/%s" % (repo, branch), token)
    parent = ref["object"]["sha"]
    st, pcommit = call("GET", "/repos/%s/git/commits/%s" % (repo, parent), token)
    base_tree = pcommit["tree"]["sha"]

    # 逐个文件建 blob（内容相同的文件 sha 也相同，Git 自动去重）
    tree_items = []
    for path in sorted(files):
        b64 = base64.b64encode(files[path]).decode("ascii")
        st, blob = call("POST", "/repos/%s/git/blobs" % repo, token,
                        {"content": b64, "encoding": "base64"})
        tree_items.append({"path": path, "mode": "100644",
                           "type": "blob", "sha": blob["sha"]})

    # 用 base_tree 建树：未列出的文件保持原样
    st, tree = call("POST", "/repos/%s/git/trees" % repo, token,
                    {"base_tree": base_tree, "tree": tree_items})

    if tree["sha"] == base_tree:
        print("  ✓ 内容与远端完全一致，无需提交")
        with open("/tmp/gh_api_sha", "w") as f:
            f.write(parent)
        return 0

    st, commit = call("POST", "/repos/%s/git/commits" % repo, token,
                      {"message": message, "tree": tree["sha"], "parents": [parent]})
    # force=False：若期间有他人推送则会失败，避免覆盖
    call("PATCH", "/repos/%s/git/refs/heads/%s" % (repo, branch), token,
         {"sha": commit["sha"], "force": False})

    print("  ✓ 提交完成 %s" % commit["sha"][:12])
    with open("/tmp/gh_api_sha", "w") as f:
        f.write(commit["sha"])
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as e:
        print("  ✗ %s" % e)
        sys.exit(1)