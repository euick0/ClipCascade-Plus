#!/usr/bin/env python3
"""A peer built from the desktop client's cipher and STOMP framing, for tests against a real server.

usage: desktop_peer.py URL USERNAME PASSWORD send TEXT
       desktop_peer.py URL USERNAME PASSWORD recv TEXT   (prints READY once subscribed)
"""
import http.cookiejar, json, re, sys, time, urllib.parse, urllib.request
import websocket
from _desktop import cipher_for, CipherManager, Frame

url, username, password, mode, text = sys.argv[1:6]
manager = cipher_for(username, password)
jar = http.cookiejar.CookieJar()
web = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
page = web.open(url + "/login", timeout=20).read().decode()
token = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page).group(1)
form = {"username": username, "password": CipherManager.string_to_sha3_512_lowercase_hex(password), "_csrf": token}
web.open(url + "/login", urllib.parse.urlencode(form).encode(), timeout=20).read()
cookie = "; ".join(f"{c.name}={c.value}" for c in jar)

ws = websocket.create_connection(url.replace("http", "ws", 1) + "/clipsocket", cookie=cookie, timeout=30)
ws.send(Frame.marshall("CONNECT", {"accept-version": "1.0,1.1", "host": url, "heart-beat": "0,20000"}, None))
assert Frame.unmarshall_single(ws.recv()).command == "CONNECTED"
ws.send(Frame.marshall("SUBSCRIBE", {"id": "0", "destination": "/user/queue/cliptext"}, None))
time.sleep(0.5)

if mode == "send":
    payload = CipherManager.encode_to_json_string(**manager.encrypt(text))
    ws.send(Frame.marshall("SEND", {"destination": "/app/cliptext"}, json.dumps({"payload": payload, "type": "text"})))
    time.sleep(0.5)
    sys.exit(0)

print("READY", flush=True)
deadline = time.time() + 30
while time.time() < deadline:
    raw = ws.recv()
    if raw == "\n":
        continue
    frame = Frame.unmarshall_single(raw)
    if frame and frame.command == "MESSAGE":
        parts = CipherManager.decode_from_json_string(json.loads(frame.body)["payload"])
        if manager.decrypt(parts["nonce"], parts["ciphertext"], parts["tag"]) == text:
            sys.exit(0)
sys.exit(1)
