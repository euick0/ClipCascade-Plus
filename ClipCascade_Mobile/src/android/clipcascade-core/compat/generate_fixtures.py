#!/usr/bin/env python3
"""Writes src/test/resources/desktop-fixtures.json using the desktop client's own crypto code."""
import json, os
from Crypto.Cipher import AES
from _desktop import cipher_for, CipherManager

CASES = {
    "ascii": "hello from desktop",
    "unicode": "pässwörd 日本語 🎉 ✓",
    "multiline": "line one\nline two\r\n\ttabbed \"quoted\" \\ back/slash\u0000nul",
    "empty": "",
    "large": "0123456789abcdef" * 1024,
}
USERS = [
    {"username": "admin", "password": "admin123", "salt": "", "rounds": 664937},
    {"username": "üser", "password": "pä55wörd🔑", "salt": "söme-salt", "rounds": 1000},
    {"username": "u", "password": "", "salt": "", "rounds": 1},
]

fixtures = []
for user in USERS:
    manager = cipher_for(user["username"], user["password"], user["salt"], user["rounds"])
    key = manager.config.data["hashed_password"]
    clips = []
    for name, text in CASES.items():
        sealed = manager.encrypt(text)
        assert len(sealed["nonce"]) == 16, "desktop nonce length changed"
        clips.append({"name": name, "plaintext": text, "origin": "desktop-16-byte-nonce",
                      "payload": CipherManager.encode_to_json_string(**sealed)})
        # Same layout as the official Android client: 12-byte nonce, compact JSON.
        cipher = AES.new(key, AES.MODE_GCM, nonce=os.urandom(12))
        body, tag = cipher.encrypt_and_digest(text.encode("utf-8"))
        payload = json.dumps(CipherManager.encode_to_json_string(nonce=cipher.nonce, ciphertext=body, tag=tag))
        clips.append({"name": name, "plaintext": text, "origin": "android-12-byte-nonce",
                      "payload": json.loads(payload).replace(", ", ",").replace(": ", ":")})
    fixtures.append({**user, "key_hex": key.hex(),
                     "login_hash": CipherManager.string_to_sha3_512_lowercase_hex(user["password"]),
                     "clips": clips})

extra = [{"input": t, "sha3_512": CipherManager.string_to_sha3_512_lowercase_hex(t)}
         for t in ["", "abc", "x" * 71, "x" * 72, "x" * 73, "y" * 144, "日本語" * 100]]

out = os.path.join(os.path.dirname(__file__), "..", "src", "test", "resources", "desktop-fixtures.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump({"users": fixtures, "sha3": extra}, f, ensure_ascii=False, indent=1)
print("wrote", os.path.abspath(out))
