"""
Revoke the Apple Development certificates that earlier GitHub Actions runs created.

Every App Store build runs on a fresh Mac, so Xcode creates a new "Created via API"
development certificate each time (its private key is lost when the runner ends).
Apple allows only a few, and then the archive fails with
"Your account has reached the maximum number of certificates".
These certificates can never be used again, so they are removed before each build.
Certificates made by a person (Xcode on a Mac, AltStore) and distribution
certificates are never touched.
Design & Development Hemn A.Muhammad
"""
import json, os, sys, time, urllib.request
import jwt  # PyJWT

key_id, issuer = os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"]
key = open(sys.argv[1]).read()
token = jwt.encode({"iss": issuer, "iat": int(time.time()), "exp": int(time.time()) + 600,
                    "aud": "appstoreconnect-v1"}, key, algorithm="ES256",
                   headers={"kid": key_id, "typ": "JWT"})

def call(method, url):
    req = urllib.request.Request(url, method=method, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req) as r:
        body = r.read()
        return json.loads(body) if body else None

certs = call("GET", "https://api.appstoreconnect.apple.com/v1/certificates?limit=200")["data"]
removed = 0
for c in certs:
    a = c["attributes"]
    kind, name = a.get("certificateType", ""), (a.get("displayName") or a.get("name") or "")
    if kind in ("DEVELOPMENT", "IOS_DEVELOPMENT") and name == "Created via API":
        call("DELETE", "https://api.appstoreconnect.apple.com/v1/certificates/" + c["id"])
        removed += 1
        print(f"revoked {kind} {c['id']} (expires {a.get('expirationDate')})")
    else:
        print(f"kept    {kind} {name!r}")
print(f"{removed} old CI development certificate(s) revoked")
