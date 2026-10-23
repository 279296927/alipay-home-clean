# -*- coding: utf-8 -*-
"""创建 GitHub Release 并上传附件（走 api.github.com / uploads.github.com）。"""
import json
import os
import sys
import urllib.request
import urllib.error

OWNER = "279296927"
REPO = "alipay-home-clean"
TAG = "v3.1"
NAME = "AliClean v3.1"
NOTES = os.path.expanduser("~/.release-notes-v3.1.md")
ASSETS = ["alipay-home-clean/releases/AliClean-v3.1.apk"]


def token():
    with open(os.path.expanduser("~/.gh_token"), "r", encoding="utf-8") as f:
        s = f.read().strip()
    if "=" in s:
        s = s.split("=", 1)[1]
    return s.strip().strip('"').strip("'")


TOK = token()


def req(method, url, payload=None, raw=None, ctype=None):
    data = raw
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Authorization", "token " + TOK)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "aliclean-release")
    if payload is not None:
        r.add_header("Content-Type", "application/json")
    if ctype:
        r.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(r, timeout=180) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body.strip() else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise SystemExit("HTTP %s %s\n%s" % (e.code, url, body[:900]))


def main():
    root = os.getcwd()
    with open(os.path.join(root, NOTES), "r", encoding="utf-8") as f:
        body = f.read()

    base = "https://api.github.com/repos/%s/%s" % (OWNER, REPO)

    # 已存在则先删（幂等重跑）
    try:
        old = req("GET", base + "/releases/tags/" + TAG)
        print("existing release id=%s, deleting..." % old["id"])
        req("DELETE", base + "/releases/" + str(old["id"]))
    except SystemExit as e:
        if "HTTP 404" not in str(e):
            raise
        print("no existing release")

    rel = req("POST", base + "/releases", {
        "tag_name": TAG,
        "target_commitish": "main",
        "name": NAME,
        "body": body,
        "draft": False,
        "prerelease": False,
        "make_latest": "true",
    })
    print("release created: id=%s url=%s" % (rel["id"], rel["html_url"]))

    for a in ASSETS:
        full = os.path.join(root, a)
        name = os.path.basename(a)
        with open(full, "rb") as f:
            raw = f.read()
        up = ("https://uploads.github.com/repos/%s/%s/releases/%s/assets?name=%s"
              % (OWNER, REPO, rel["id"], urllib.parse.quote(name)))
        res = req("POST", up, raw=raw, ctype="application/vnd.android.package-archive")
        print("  asset %-28s %6d B  state=%s" % (name, len(raw), res.get("state")))
        print("  download: %s" % res.get("browser_download_url"))

    print("DONE")


if __name__ == "__main__":
    import urllib.parse
    main()