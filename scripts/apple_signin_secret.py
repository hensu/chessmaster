#!/usr/bin/env python3
"""Make the Sign in with Apple client secret Supabase needs for the WEB flow (Android).

  scripts/apple_signin_secret.py            # prints the secret (a JWT)
  scripts/apple_signin_secret.py --apply    # also writes it to the Supabase Apple provider

Apple caps the secret's life at 6 months, so run with --apply again before it
expires (the expiry date is printed). Needs PyJWT + cryptography, the key
~/.config/chessai/AuthKey_6Z3TGRJT59.p8 ("Chess AI Sign in with Apple", primary
App ID com.hensu.chessmaster) and, for --apply, the Supabase CLI login.
"""
import datetime, json, os, subprocess, sys, time, urllib.request, base64
import jwt

TEAM = "BD3U76LB98"
KEY_ID = "6Z3TGRJT59"
SERVICES_ID = "com.hensu.chessmaster.signin"   # OAuth client id (web flow)
BUNDLE_ID = "com.hensu.chessmaster"            # native iOS sign-in audience; must stay allowed
PROJECT = "ucpaqfjmicxfygzhquqo"
KEY = os.path.expanduser(f"~/.config/chessai/AuthKey_{KEY_ID}.p8")

now = int(time.time())
exp = now + 180 * 86400
secret = jwt.encode({"iss": TEAM, "iat": now, "exp": exp, "aud": "https://appleid.apple.com", "sub": SERVICES_ID},
                    open(KEY).read(), algorithm="ES256", headers={"kid": KEY_ID})
print("expires", datetime.date.fromtimestamp(exp), file=sys.stderr)

if "--apply" not in sys.argv:
    print(secret)
    sys.exit()

token = subprocess.run(["security", "find-generic-password", "-s", "Supabase CLI", "-w"],
                       capture_output=True, text=True, check=True).stdout.strip()
if token.startswith("go-keyring-base64:"):
    token = base64.b64decode(token.split(":", 1)[1]).decode()
req = urllib.request.Request(
    f"https://api.supabase.com/v1/projects/{PROJECT}/config/auth", method="PATCH",
    data=json.dumps({"external_apple_enabled": True, "external_apple_client_id": SERVICES_ID,
                     "external_apple_additional_client_ids": BUNDLE_ID,
                     "external_apple_secret": secret}).encode(),
    headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
             "User-Agent": "chessai-scripts"})
body = json.loads(urllib.request.urlopen(req).read())
print("supabase:", body["external_apple_client_id"], "+", body["external_apple_additional_client_ids"],
      "secret set" if body.get("external_apple_secret") else "SECRET MISSING")
