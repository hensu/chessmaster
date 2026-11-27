#!/usr/bin/env python3
"""Call the App Store Connect API with the team key.

  scripts/asc.py GET /v1/apps/6790178402/subscriptionGroups
  scripts/asc.py POST /v1/subscriptions body.json

Key: ~/.appstoreconnect/private_keys/AuthKey_736XCSM36H.p8; issuer id in
~/.config/chessai/asc-issuer (not committed). Needs PyJWT + cryptography.
"""
import json, os, sys, time, urllib.error, urllib.request
import jwt

KEY_ID = "736XCSM36H"
KEY = os.path.expanduser(f"~/.appstoreconnect/private_keys/AuthKey_{KEY_ID}.p8")
ISSUER = os.path.expanduser("~/.config/chessai/asc-issuer")
BASE = "https://api.appstoreconnect.apple.com"


def token():
    now = int(time.time())
    return jwt.encode({"iss": open(ISSUER).read().strip(), "iat": now, "exp": now + 1100,
                       "aud": "appstoreconnect-v1"}, open(KEY).read(), algorithm="ES256",
                      headers={"kid": KEY_ID})


def call(method, path, body=None, tok=None):
    url = path if path.startswith("http") else BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + (tok or token()), "Content-Type": "application/json"})
    try:
        text = urllib.request.urlopen(req, timeout=120).read().decode()
        return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} {e.code} {e.read().decode()[:1500]}")


if __name__ == "__main__":
    method, path = sys.argv[1], sys.argv[2]
    body = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else None
    print(json.dumps(call(method, path, body), indent=1))
