#!/usr/bin/env python3
"""Decrypts clips the Kotlin client encrypted, using the desktop client's own code."""
import json, sys
from _desktop import cipher_for, CipherManager

data = json.load(open(sys.argv[1], encoding="utf-8"))
count = 0
for user in data:
    manager = cipher_for(user["username"], user["password"], user["salt"], user["rounds"])
    for clip in user["clips"]:
        parts = CipherManager.decode_from_json_string(clip["payload"])
        text = manager.decrypt(parts["nonce"], parts["ciphertext"], parts["tag"])
        assert text == clip["plaintext"], f"mismatch for {clip['name']}"
        count += 1
print(f"desktop code decrypted {count} Kotlin clips")
