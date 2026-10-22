#!/bin/bash
# Makes the S/MIME fixtures for SmimeMessagesTest (#47): a CA, the person's
# certificate (ada@example.org, wrapped into smime-keyring.json under the
# fixture user key from ../pairing.json, as Keyring/get serves it) and a
# contact's (grace@example.net), and messages as OpenSSL writes them -- what
# Thunderbird and Outlook produce. Needs openssl and python3 with
# `cryptography`. Run from this directory; the outputs are checked in.
set -euo pipefail
cd "$(dirname "$0")"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT

cat > "$work/ext.cnf" <<CNF
[leaf]
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = emailProtection
subjectAltName = email:\$ENV::ADDR
CNF

openssl req -x509 -newkey rsa:2048 -nodes -keyout "$work/ca.key" -out ca.pem -days 7300 \
    -subj "/CN=plMail Test CA" 2>/dev/null

leaf() { # name address
    openssl req -newkey rsa:2048 -nodes -keyout "$work/$1.key" -out "$work/$1.csr" \
        -subj "/CN=$1/emailAddress=$2" 2>/dev/null
    ADDR=$2 openssl x509 -req -in "$work/$1.csr" -CA ca.pem -CAkey "$work/ca.key" \
        -CAcreateserial -out "$1.pem" -days 7300 -extfile "$work/ext.cnf" -extensions leaf 2>/dev/null
}
leaf ada ada@example.org
leaf grace grace@example.net
cp "$work/grace.key" grace.key   # signs the contact's messages in tests
openssl pkcs8 -topk8 -nocrypt -in "$work/ada.key" -outform DER -out "$work/ada.p8"

# The inner message, with the protected headers of RFC 9788.
printf '%s\r\n' \
  'Content-Type: multipart/mixed; boundary="mixed"; protected-headers="v1"' \
  'Subject: Quarterly report, by S/MIME' \
  'From: grace@example.net' \
  'To: ada@example.org' \
  '' \
  '--mixed' \
  'Content-Type: text/html; charset=utf-8' \
  '' \
  '<p>Gr&uuml;&szlig;e, the <b>numbers</b> are attached.</p>' \
  '--mixed' \
  'Content-Type: text/plain; charset=utf-8; name="notes.txt"' \
  'Content-Disposition: attachment; filename="notes.txt"' \
  '' \
  'Q3 numbers' \
  '--mixed--' > "$work/inner.eml"

headers=(-from grace@example.net -to ada@example.org -subject "...")

openssl smime -sign -in "$work/inner.eml" -signer grace.pem -inkey "$work/grace.key" \
    -certfile ca.pem -md sha256 -out "$work/signed.eml"
openssl smime -encrypt -aes256 -in "$work/signed.eml" "${headers[@]}" -out signed-encrypted.eml ada.pem
openssl smime -sign -nodetach -in "$work/inner.eml" -signer grace.pem -inkey "$work/grace.key" \
    -md sha256 -out "$work/opaque.eml"
openssl smime -encrypt -aes128 -in "$work/opaque.eml" "${headers[@]}" -out opaque-encrypted.eml ada.pem
openssl cms -encrypt -aes192 -recip ada.pem -keyopt rsa_padding_mode:oaep -in "$work/inner.eml" \
    "${headers[@]}" -out oaep-encrypted.eml
openssl smime -encrypt -des3 -in "$work/inner.eml" "${headers[@]}" -out des3-encrypted.eml ada.pem
openssl smime -encrypt -aes256 -in "$work/inner.eml" "${headers[@]}" -out not-for-us.eml grace.pem
openssl smime -sign -nodetach -in "$work/inner.eml" -signer grace.pem -inkey "$work/grace.key" \
    -md sha256 "${headers[@]}" -out opaque-signed.eml

python3 - "$work/ada.p8" <<'PY'
import base64, json, os, sys, hashlib
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography import x509
pairing = json.load(open("../pairing.json"))
user_key = base64.b64decode(pairing["userKey"])
pkcs8 = open(sys.argv[1], "rb").read()
iv = os.urandom(12)
sealed = AESGCM(user_key).encrypt(iv, pkcs8, b"plmail/smime-key")
pem = open("ada.pem").read()
cert = x509.load_pem_x509_certificate(pem.encode())
from cryptography.hazmat.primitives.serialization import Encoding
fp = hashlib.sha256(cert.public_bytes(Encoding.DER)).hexdigest()
entry = {
    "id": 12, "userKeyId": 3, "fingerprint": fp, "certificate": pem,
    "chain": open("ca.pem").read(),
    "privateKey": "v1." + base64.b64encode(iv).decode() + "." + base64.b64encode(sealed).decode(),
    "addresses": ["ada@example.org"], "subject": "ada", "issuer": "plMail Test CA",
    "notBefore": cert.not_valid_before.replace(tzinfo=__import__("datetime").timezone.utc).isoformat(), "notAfter": cert.not_valid_after.replace(tzinfo=__import__("datetime").timezone.utc).isoformat(),
    "status": "active", "createdAt": "2026-10-01T10:00:00+00:00",
}
json.dump({"smimeCertificates": [entry]}, open("smime-keyring.json", "w"), indent=2)
grace = x509.load_pem_x509_certificate(open("grace.pem").read().encode())
json.dump({"fingerprint": hashlib.sha256(grace.public_bytes(Encoding.DER)).hexdigest()},
          open("grace.json", "w"))
PY
rm -f ca.srl
