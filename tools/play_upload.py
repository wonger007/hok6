#!/root/.venvs/hok6-play/bin/python
"""Uploads Hok6's Play bundle to a Google Play track with the Play Developer API, so a release needs no browser.

  tools/play_upload.py check                                     # can the key reach Hok6? lists the tracks
  tools/play_upload.py upload build/release/Hok6-1.14.aab --track internal --name 1.14 --notes notes.txt
  tools/play_upload.py upload build/release/Hok6-1.14.aab --track alpha --name 1.14 --notes "• Fixes"
  tools/play_upload.py release 16 --track alpha --name 1.14 --notes notes.txt  # a version already uploaded, on a track

Tracks: internal (Internal testing), alpha (the default Closed testing track), beta, production, or a closed track's
own name. --notes takes a file or the text itself (en-US, at most 500 characters).

The key is a Google Cloud service account's JSON key, kept outside the repo: ~/.config/hok6/play-service-account.json
(or $HOK6_PLAY_KEY). The account must be invited in Play Console → Users and permissions with release permissions for
Hok6. Needs: python3.14 -m venv /root/.venvs/hok6-play && /root/.venvs/hok6-play/bin/pip install
google-api-python-client google-auth
"""
import argparse
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

PACKAGE = "com.wonger.hok6"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
DEFAULT_KEY = os.path.expanduser("~/.config/hok6/play-service-account.json")


def service(key_path):
    if not os.path.exists(key_path):
        sys.exit(f"no service account key at {key_path} (see the top of this file)")
    creds = service_account.Credentials.from_service_account_file(key_path, scopes=[SCOPE])
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False)


def explain(e):
    """The API's own message, which says what's missing (permission, track, version code…)."""
    try:
        import json
        return json.loads(e.content)["error"]["message"]
    except Exception:  # noqa: BLE001 - fall back to the raw error
        return str(e)


def check(api):
    edits = api.edits()
    edit = edits.insert(packageName=PACKAGE, body={}).execute()
    try:
        tracks = edits.tracks().list(packageName=PACKAGE, editId=edit["id"]).execute().get("tracks", [])
        print(f"{PACKAGE}: the key works. Tracks:")
        for t in tracks:
            releases = t.get("releases", [])
            shown = ", ".join(f"{r.get('name', '?')} ({r.get('status')}, {', '.join(r.get('versionCodes', []))})"
                              for r in releases) or "no releases"
            print(f"  {t['track']}: {shown}")
    finally:
        edits.delete(packageName=PACKAGE, editId=edit["id"]).execute()


def read_notes(notes):
    if notes and os.path.exists(notes):
        notes = open(notes, encoding="utf-8").read().strip()
    if notes and len(notes) > 500:
        sys.exit(f"release notes are {len(notes)} characters; Play allows 500")
    return notes


def put_on_track(api, edit_id, code, track, name, notes, status):
    """Makes [code] the track's release (replacing what's there, e.g. an empty draft) and commits the edit."""
    edits = api.edits()
    release = {"name": name or code, "versionCodes": [code], "status": status}
    if notes:
        release["releaseNotes"] = [{"language": "en-US", "text": notes}]
    edits.tracks().update(packageName=PACKAGE, editId=edit_id, track=track,
                          body={"track": track, "releases": [release]}).execute()
    try:
        edits.commit(packageName=PACKAGE, editId=edit_id).execute()
    except HttpError as e:
        # Apps whose changes go through review in the Console can't have them sent from the API; leave them ready.
        if "changesNotSentForReview" not in explain(e):
            raise
        edits.commit(packageName=PACKAGE, editId=edit_id, changesNotSentForReview=True).execute()
        print("note: Play wants these changes sent for review from Play Console → Publishing overview")
    print(f"done: {release['name']} (versionCode {code}) on the {track} track ({status})")


def run_edit(api, status, work):
    """Runs [work](edit_id) in a new edit; on an error the edit is dropped and Play's message shown."""
    edits = api.edits()
    edit = edits.insert(packageName=PACKAGE, body={}).execute()
    try:
        work(edit["id"])
    except HttpError as e:
        edits.delete(packageName=PACKAGE, editId=edit["id"]).execute()
        message = explain(e)
        if "draft app" in message.lower() and status != "draft":
            sys.exit(f"Play says: {message}\nThe app isn't published on any track yet: run again with --status draft, "
                     "then roll the release out once in Play Console.")
        sys.exit(f"Play says: {message}")


def upload(api, aab, track, name, notes, status):
    if not os.path.exists(aab):
        sys.exit(f"{aab} not found")
    notes = read_notes(notes)

    def work(edit_id):
        print(f"uploading {os.path.basename(aab)}…")
        media = MediaFileUpload(aab, mimetype="application/octet-stream", resumable=True, chunksize=8 * 1024 * 1024)
        bundle = api.edits().bundles().upload(packageName=PACKAGE, editId=edit_id, media_body=media).execute()
        code = str(bundle["versionCode"])
        print(f"uploaded: versionCode {code}")
        put_on_track(api, edit_id, code, track, name, notes, status)
    run_edit(api, status, work)


def release(api, code, track, name, notes, status):
    notes = read_notes(notes)
    run_edit(api, status, lambda edit_id: put_on_track(api, edit_id, str(code), track, name, notes, status))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", default=os.environ.get("HOK6_PLAY_KEY", DEFAULT_KEY), help="service account JSON key")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check", help="check the key can reach Hok6 and list its tracks")
    up = sub.add_parser("upload", help="upload a bundle and release it on a track")
    up.add_argument("aab")
    up.add_argument("--track", default="internal")
    up.add_argument("--name", help="release name (default: the version code)")
    up.add_argument("--notes", help="release notes: a file, or the text")
    up.add_argument("--status", default="completed", choices=["completed", "draft"],
                    help="completed rolls the release out to the track's testers; draft leaves it for Play Console")
    rel = sub.add_parser("release", help="put a version already uploaded on a track")
    rel.add_argument("version_code", type=int)
    for p in (rel,):
        p.add_argument("--track", default="internal")
        p.add_argument("--name", help="release name (default: the version code)")
        p.add_argument("--notes", help="release notes: a file, or the text")
        p.add_argument("--status", default="completed", choices=["completed", "draft"])
    args = parser.parse_args()
    api = service(args.key)
    try:
        if args.command == "check":
            check(api)
        elif args.command == "release":
            release(api, args.version_code, args.track, args.name, args.notes, args.status)
        else:
            upload(api, args.aab, args.track, args.name, args.notes, args.status)
    except HttpError as e:
        sys.exit(f"Play says: {explain(e)}")


if __name__ == "__main__":
    main()
