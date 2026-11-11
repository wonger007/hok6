"""Creates a GitHub release for a pushed tag and uploads files to it. The token comes from git's credential helper."""
import json, subprocess, sys, urllib.request, urllib.error, os
repo, tag, name, notes, files = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:]
out = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", text=True,
                     capture_output=True, timeout=60, env={**os.environ, "GIT_TERMINAL_PROMPT": "0"}).stdout
token = dict(l.split("=", 1) for l in out.splitlines() if "=" in l).get("password")
if not token:
    sys.exit("no GitHub credential from git's helper")
def call(url, data, ctype):
    req = urllib.request.Request(url, data=data, method="POST", headers={
        "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "Content-Type": ctype,
        "User-Agent": "release-script"})
    try:
        return json.load(urllib.request.urlopen(req, timeout=900))
    except urllib.error.HTTPError as e:
        sys.exit(f"{e.code}: {e.read().decode()[:400]}")
r = call(f"https://api.github.com/repos/{repo}/releases", json.dumps({
    "tag_name": tag, "name": name, "body": open(notes, encoding="utf-8").read(), "draft": True}).encode(), "application/json")
print("draft release", r["id"])
for f in files:
    a = call(f"https://uploads.github.com/repos/{repo}/releases/{r['id']}/assets?name={os.path.basename(f)}",
             open(f, "rb").read(), "application/vnd.android.package-archive")
    print("uploaded", a["name"], a["size"])
req = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases/{r['id']}", method="PATCH",
    data=json.dumps({"draft": False, "make_latest": "true"}).encode(), headers={"Authorization": "Bearer " + token,
    "Accept": "application/vnd.github+json", "Content-Type": "application/json", "User-Agent": "release-script"})
p = json.load(urllib.request.urlopen(req, timeout=120))
print("published", p["html_url"], "draft:", p["draft"])
