# -*- coding: utf-8 -*-
"""通过 GitHub Git Data API 一次性提交整个目录（绕开 git push 的 443 不通问题）。"""
import base64
import json
import os
import sys
import urllib.request
import urllib.error

OWNER = "279296927"
REPO = "alipay-home-clean"
BRANCH = "main"
API = "https://api.github.com"


def token():
    p = os.path.expanduser("~/.gh_token")
    with open(p, "r", encoding="utf-8") as f:
        s = f.read().strip()
    if "=" in s:
        s = s.split("=", 1)[1]
    return s.strip().strip('"').strip("'")


TOK = token()


def req(method, path, payload=None):
    url = API + path
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Authorization", "token " + TOK)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "aliclean-publish")
    if data:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=90) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body.strip() else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise SystemExit("HTTP %s %s %s\n%s" % (e.code, method, path, body[:800]))


SKIP_DIRS = {".git", "build", "out", "__pycache__"}
SKIP_EXT = {".idsig", ".class", ".pyc"}


def collect(root):
    out = []
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if d not in SKIP_DIRS]
        for fn in fns:
            if os.path.splitext(fn)[1] in SKIP_EXT:
                continue
            full = os.path.join(dp, fn)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if rel in (".gitignore",) or not rel.startswith("."):
                out.append((rel, full))
            elif rel.startswith(".") and rel.count("/") == 0:
                out.append((rel, full))
    return sorted(out)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    msg = sys.argv[2] if len(sys.argv) > 2 else "update"

    files = collect(root)
    print("files to upload: %d" % len(files))

    # 1) 取远端分支现有 tree 作为 base（保留 MIT LICENSE 初始文件时会被覆盖，随后处理）
    try:
        ref = req("GET", "/repos/%s/%s/git/ref/heads/%s" % (OWNER, REPO, BRANCH))
        base_commit_sha = ref["object"]["sha"]
        print("remote head:", base_commit_sha)
        commit = req("GET", "/repos/%s/%s/git/commits/%s" % (OWNER, REPO, base_commit_sha))
        base_tree_sha = commit["tree"]["sha"]
    except SystemExit:
        base_commit_sha = None
        base_tree_sha = None
        print("no remote head yet")

    # 2) 逐个上传 blob
    tree_entries = []
    for rel, full in files:
        with open(full, "rb") as f:
            raw = f.read()
        b = req("POST", "/repos/%s/%s/git/blobs" % (OWNER, REPO), {
            "content": base64.b64encode(raw).decode("ascii"),
            "encoding": "base64",
        })
        tree_entries.append({
            "path": rel,
            "mode": "100755" if rel.endswith(".sh") else "100644",
            "type": "blob",
            "sha": b["sha"],
        })
        print("  blob %-60s %6d B" % (rel, len(raw)))

    # 3) 建 tree
    payload = {"tree": tree_entries}
    if base_tree_sha:
        payload["base_tree"] = base_tree_sha
    tree = req("POST", "/repos/%s/%s/git/trees" % (OWNER, REPO), payload)
    print("tree:", tree["sha"])

    # 4) 建 commit
    cpayload = {"message": msg, "tree": tree["sha"]}
    if base_commit_sha:
        cpayload["parents"] = [base_commit_sha]
    new_commit = req("POST", "/repos/%s/%s/git/commits" % (OWNER, REPO), cpayload)
    print("commit:", new_commit["sha"])

    # 5) 更新/创建 ref
    if base_commit_sha:
        req("PATCH", "/repos/%s/%s/git/refs/heads/%s" % (OWNER, REPO, BRANCH),
            {"sha": new_commit["sha"], "force": False})
    else:
        req("POST", "/repos/%s/%s/git/refs" % (OWNER, REPO),
            {"ref": "refs/heads/" + BRANCH, "sha": new_commit["sha"]})
    print("PUSHED OK -> https://github.com/%s/%s" % (OWNER, REPO))


if __name__ == "__main__":
    main()