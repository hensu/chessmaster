#!/usr/bin/env python3
"""Upload an Android App Bundle to Google Play and put it on a track.

  scripts/play_upload.py <bundle.aab> --notes "What's new text" [--track production] [--draft]

Without --draft the release is sent for review as soon as the edit commits.
Signs in with the service account key at ~/.config/chessai/play-service-account.json
(chessai-play@chessmaster-502217, access to this app only). Needs PyJWT + cryptography.
"""
import argparse, json, os, sys, time, urllib.error, urllib.parse, urllib.request
import jwt

PACKAGE = "com.hensu.chessmaster"
KEY = os.path.expanduser("~/.config/chessai/play-service-account.json")
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"


def token():
    sa = json.load(open(KEY))
    now = int(time.time())
    assertion = jwt.encode(
        {"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
         "aud": sa["token_uri"], "iat": now, "exp": now + 3000},
        sa["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}).encode()
    return json.loads(urllib.request.urlopen(sa["token_uri"], body).read())["access_token"]


def call(tok, method, url, data=None, ctype="application/json"):
    if data is not None and ctype == "application/json":
        data = json.dumps(data).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": ctype})
    try:
        text = urllib.request.urlopen(req, timeout=900).read().decode()
        return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url.split('/applications/')[-1]} {e.code} {e.read().decode()[:900]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bundle")
    ap.add_argument("--notes", required=True, help="en-US release notes (500 characters at most)")
    ap.add_argument("--track", default="production")
    ap.add_argument("--draft", action="store_true", help="leave the release as a draft in Play Console")
    ap.add_argument("--check", action="store_true", help="only check that the key can open the app")
    a = ap.parse_args()
    if len(a.notes) > 500:
        sys.exit(f"release notes are {len(a.notes)} characters; Play allows 500")

    tok = token()
    edit = call(tok, "POST", f"{API}/edits")["id"]
    if a.check:
        tracks = call(tok, "GET", f"{API}/edits/{edit}/tracks").get("tracks", [])
        for t in tracks:
            print(t["track"], [(r.get("name"), r.get("status"), r.get("versionCodes")) for r in t.get("releases", [])])
        call(tok, "DELETE", f"{API}/edits/{edit}")
        return
    with open(a.bundle, "rb") as f:
        code = call(tok, "POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", f.read(),
                    "application/octet-stream")["versionCode"]
    print("uploaded version code", code)
    call(tok, "PUT", f"{API}/edits/{edit}/tracks/{a.track}", {"track": a.track, "releases": [{
        "versionCodes": [str(code)], "status": "draft" if a.draft else "completed",
        "releaseNotes": [{"language": "en-US", "text": a.notes}]}]})
    call(tok, "POST", f"{API}/edits/{edit}:commit")
    print(f"{a.track}: version code {code}", "saved as a draft" if a.draft else "sent for review")


if __name__ == "__main__":
    main()
