#!/usr/bin/env python3
"""Set GitHub Actions secrets for arzdevapp/apk-installer-app via REST API."""
import base64
import json
import re
import subprocess
import urllib.error
import urllib.request

from nacl import encoding, public

REPO = "arzdevapp/apk-store-tv"

def get_token():
    creds = open("/root/.git-credentials").read()
    m = re.search(r"https://arzdevapp:([^@]+)@github.com", creds)
    if not m:
        raise SystemExit("no PAT found in ~/.git-credentials")
    return m.group(1)

def api(path, token, method="GET", body=None):
    req = urllib.request.Request(
        f"https://api.github.com{path}",
        method=method,
        headers={
            "Authorization": f"token {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "kronos",
        },
    )
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        return {"error": e.code, "body": e.read().decode()[:300]}

def encrypt(public_key_b64, secret):
    pub = public.PublicKey(public_key_b64, encoding.Base64Encoder())
    box = public.SealedBox(pub)
    return base64.b64encode(box.encrypt(secret.encode())).decode()

token = get_token()

# Repo public key for secret encryption
pub = api(f"/repos/{REPO}/actions/secrets/public-key", token)
key_id = pub.get("key_id")
key = pub.get("key")
if not key:
    print("FAILED to get public key:", pub)
    raise SystemExit(1)
print("public key id:", key_id)

# Secret values
ks_b64 = base64.b64encode(open("/root/apk-installer-app/release.keystore", "rb").read()).decode()
ks_pass = open("/root/apk-installer-app/.ks_pass").read().strip()
key_pass = open("/root/apk-installer-app/.key_pass").read().strip()

secrets = {
    "ANDROID_KEYSTORE_B64": ks_b64,
    "KEYSTORE_PASSWORD": ks_pass,
    "KEY_ALIAS": "apkinstaller",
    "KEY_PASSWORD": key_pass,
}

for name, value in secrets.items():
    enc = encrypt(key, value)
    res = api(
        f"/repos/{REPO}/actions/secrets/{name}",
        token,
        method="PUT",
        body={"encrypted_value": enc, "key_id": key_id},
    )
    print(f"{name}: {'OK' if not res.get('error') else res}")

# Verify
for name in secrets:
    res = api(f"/repos/{REPO}/actions/secrets/{name}", token)
    print(f"verify {name}: created_at={res.get('created_at')}")
