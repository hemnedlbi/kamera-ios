#!/usr/bin/env python3
"""Revoke the "Apple Development" certificate this CI run created.

Each GitHub Actions run starts on a fresh Mac, so automatic signing asks Apple
for a new development certificate every time. Apple caps how many an account
may hold, and once the cap is reached every build fails with
"Your account has reached the maximum number of certificates".

This script looks up the development certificates in the runner's keychain
(their private keys vanish with the runner anyway) and revokes the matching
certificates with the same serial number through the App Store Connect API.
Only certificates whose serial number is in this runner's keychain are touched,
so the Distribution certificate and certificates made by hand stay untouched.

Needs: ASC_KEY_ID, ASC_ISSUER_ID and AUTH_KEY_PATH (the .p8 file).
Design & Development Hemn A.Muhammad
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

import jwt
from cryptography import x509

API = "https://api.appstoreconnect.apple.com/v1"


def token():
    with open(os.environ["AUTH_KEY_PATH"]) as f:
        key = f.read()
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 600,
         "aud": "appstoreconnect-v1"},
        key, algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})


def call(method, url, tok):
    req = urllib.request.Request(url, method=method,
                                 headers={"Authorization": f"Bearer {tok}"})
    with urllib.request.urlopen(req) as r:
        body = r.read()
        return json.loads(body) if body else {}


def keychain_serials():
    out = subprocess.run(
        ["security", "find-certificate", "-a", "-c", "Apple Development", "-p"],
        capture_output=True, text=True).stdout
    serials = set()
    for pem in re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
                          out, re.S):
        cert = x509.load_pem_x509_certificate(pem.encode())
        serials.add(format(cert.serial_number, "X").lstrip("0"))
    return serials


def main():
    serials = keychain_serials()
    if not serials:
        print("No Apple Development certificate in this runner's keychain; nothing to revoke.")
        return
    print(f"Keychain development certificate serials: {sorted(serials)}")
    tok = token()
    data = call("GET", f"{API}/certificates?limit=200", tok)
    revoked = 0
    for c in data.get("data", []):
        a = c["attributes"]
        serial = (a.get("serialNumber") or "").upper().lstrip("0")
        kind = a.get("certificateType") or ""
        print(f"  account cert {c['id']}: type={kind} name={a.get('name')!r} serial={serial}")
        # Match on serial only: it is unique, and only this runner holds the
        # private key. Distribution certificates are never touched.
        if serial in serials and "DISTRIBUTION" not in kind:
            call("DELETE", f"{API}/certificates/{c['id']}", tok)
            print(f"Revoked CI certificate {c['id']} ({a.get('certificateType')}).")
            revoked += 1
    print(f"Done: {revoked} certificate(s) revoked.")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # never fail the build over cleanup
        print(f"::warning::Could not revoke the CI certificate: {e}")
        sys.exit(0)
