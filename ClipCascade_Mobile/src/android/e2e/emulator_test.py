#!/usr/bin/env python3
"""End-to-end test on an Android emulator against the official server image.

Runs in CI inside the emulator step. Drives the real UI to log in, then checks what the service
does. This covers stock Android only. One UI and Shizuku still need the phone, see TESTING.md.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = "com.clipcascade.plus"
OUT = os.environ.get("E2E_OUT", "e2e-out")
IMAGE = "sathvikrao/clipcascade:latest"
HOST_URL, PHONE_URL = "http://127.0.0.1:18093", "http://10.0.2.2:18093"
USER, PASSWORD = "admin", "admin123"
PEER = [sys.executable, os.path.join(os.path.dirname(__file__), "..", "clipcascade-core", "compat", "desktop_peer.py")]
failures = []
os.makedirs(OUT, exist_ok=True)


def run(*args, check=False, timeout=120):
    r = subprocess.run(args, capture_output=True, text=True, timeout=timeout, errors="replace")
    if check and r.returncode:
        raise RuntimeError(f"{args}: {r.stderr[:500]}")
    return r.stdout


def adb(*args, **kw):
    return run("adb", *args, **kw)


def shell(command):
    return adb("shell", command)


def step(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (f"  ({detail})" if detail else ""), flush=True)
    if not ok:
        failures.append(name)
    return ok


def wait(check, seconds, every=2):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if check():
            return True
        time.sleep(every)
    return False


def log():
    return shell(f"cat /data/data/{PKG}/files/sync.log 2>/dev/null")


def count(text):
    return log().count(text)


def snap(name):
    run("sh", "-c", f"adb exec-out screencap -p > {OUT}/{name}.png")


def nodes():
    shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    xml = shell("cat /sdcard/ui.xml")
    start = xml.find("<?xml")
    try:
        return list(ET.fromstring(xml[start:]).iter("node")) if start >= 0 else []
    except ET.ParseError:
        return []


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_node(node):
    x, y = center(node)
    shell(f"input tap {x} {y}")


def find_text(text):
    for node in nodes():
        if node.get("text") == text or node.get("content-desc") == text:
            return node
    return None


def tap_text(text, seconds=20):
    found = {}

    def look():
        found["node"] = find_text(text)
        if not found["node"]:
            shell("input swipe 500 1500 500 700 300")
        return found["node"] is not None

    if not wait(look, seconds, 3):
        return False
    tap_node(found["node"])
    return True


def replace_field(node, value):
    tap_node(node)
    time.sleep(1)
    shell("input keyevent KEYCODE_MOVE_END")
    shell("input keyevent " + " ".join(["KEYCODE_DEL"] * 40))
    shell(f"input text '{value}'")
    time.sleep(1)


def peer(mode, text):
    return subprocess.Popen(PEER + [HOST_URL, USER, PASSWORD, mode, text], stdout=subprocess.PIPE, text=True)


def peer_sends(text):
    process = peer("send", text)
    process.communicate(timeout=60)
    return process.returncode == 0


def start_server():
    run("docker", "rm", "-f", "cc-e2e")
    run("docker", "run", "-d", "--name", "cc-e2e", "-p", "18093:8080", "-e", "CC_P2P_ENABLED=false",
        "-e", "CC_MAX_MESSAGE_SIZE_IN_MiB=1", IMAGE, check=True, timeout=300)
    return server_up()


def server_up():
    return wait(lambda: subprocess.run(["curl", "-sf", "-o", "/dev/null", "-m", "2", HOST_URL + "/login"]).returncode == 0, 180, 3)


def grab_clipboard_and_send():
    shell(f"am start -W -n {PKG}/com.clipcascade.sync.ClipGrabActivity")


def main():
    assert start_server(), "server did not start"
    adb("root")
    time.sleep(3)
    adb("install", "-r", "-g", sys.argv[1], check=True, timeout=300)
    shell(f"dumpsys deviceidle whitelist +{PKG}")
    shell("settings put global window_animation_scale 0; settings put global transition_animation_scale 0")

    shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    field_ready = wait(lambda: any(n.get("class") == "android.widget.EditText" for n in nodes()), 120, 3)
    snap("1-login")
    if not step("App launches to the login page", field_ready):
        return

    fields = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
    print("Login fields:", [f.get("text") for f in fields], flush=True)
    url_field = next((f for f in fields if "http" in (f.get("text") or "")), None)
    if url_field is None:
        raise RuntimeError("server URL field not found")
    replace_field(url_field, PHONE_URL)
    fields = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
    others = [f for f in fields if "http" not in (f.get("text") or "")]
    replace_field(others[0], USER)
    fields = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
    others = [f for f in fields if "http" not in (f.get("text") or "") and f.get("text") != USER]
    replace_field(others[0], PASSWORD)
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    snap("2-filled")
    tap_text("Log in")
    logged_in = wait(lambda: find_text("Start sync") is not None, 120, 3)
    snap("3-after-login")
    if not step("Login through the UI", logged_in):
        return

    tap_text("Start sync")
    connected = wait(lambda: "Connected" in log(), 90)
    snap("4-started")
    if not step("Service connects", connected, log()[-300:] if not connected else ""):
        return
    step("Service runs as a foreground service", "isForeground=true" in shell(f"dumpsys activity services {PKG}"))
    notification = shell("dumpsys notification --noredact")
    step("Notification shows the connection state", "Connected" in notification)

    # Both directions, through the clipboard: the peer sends, then the phone's clipboard is sent back.
    text = "e2e-from-desktop-code"
    receiver = peer("recv", text)
    assert receiver.stdout.readline().strip() == "READY"
    peer_sends(text)
    step("Receives a clip from desktop code", wait(lambda: "Applied a received text clip" in log(), 30))
    grab_clipboard_and_send()
    try:
        receiver.wait(30)
        step("Sends the clipboard, which desktop code decrypts", receiver.returncode == 0)
    except subprocess.TimeoutExpired:
        receiver.kill()
        step("Sends the clipboard, which desktop code decrypts", False, "timed out")
    # Later steps would match the old text.
    before = count("Connected")

    # Process death: the system should bring a sticky service back.
    pid = shell(f"pidof {PKG}").strip()
    shell(f"kill -9 {pid}")
    back = wait(lambda: shell(f"pidof {PKG}").strip() not in ("", pid) and count("Connected") > before, 180, 3)
    step("Comes back after the process is killed", back)
    before = count("Connected")

    # Server restart. Sessions are in memory here, so this is the bug the fork set out to fix.
    run("docker", "restart", "cc-e2e", timeout=180)
    server_up()
    step("Reconnects and logs in again after a server restart", wait(lambda: count("Connected") > before, 180, 3), log()[-400:])
    before = count("Connected")

    # Airplane mode.
    shell("cmd connectivity airplane-mode enable")
    lost = wait(lambda: "No network" in shell("dumpsys notification --noredact"), 45)
    step("Shows no network in airplane mode", lost)
    shell("cmd connectivity airplane-mode disable")
    step("Reconnects when the network returns", wait(lambda: count("Connected") > before, 90, 3))
    before = count("Connected")

    # Swiping from recents is close to what Samsung does to background apps.
    shell(f"am stack remove $(am stack list | grep {PKG} | sed -E 's/.*taskId=([0-9]+).*/\\1/' | head -1) 2>/dev/null")
    time.sleep(5)
    step("Service survives leaving the app", shell(f"pidof {PKG}").strip() != "")

    # Boot receiver, without a full reboot.
    shell(f"am force-stop {PKG}")
    time.sleep(2)
    shell(f"am broadcast -a android.intent.action.BOOT_COMPLETED -p {PKG}")
    step("Starts by itself on the boot broadcast", wait(lambda: count("Connected") > before, 90, 3), log()[-300:])

    step("Log holds no clip text", text not in log())
    step("No crash", "FATAL EXCEPTION" not in adb("logcat", "-d", "-b", "crash"))


try:
    main()
except Exception as e:
    step("Test script finished", False, repr(e))
finally:
    snap("9-final")
    open(f"{OUT}/sync.log", "w").write(log())
    open(f"{OUT}/logcat.txt", "w").write(adb("logcat", "-d", timeout=60))
    open(f"{OUT}/crash.txt", "w").write(adb("logcat", "-d", "-b", "crash", timeout=60))
    open(f"{OUT}/ui.xml", "w").write(shell("cat /sdcard/ui.xml"))
    run("docker", "rm", "-f", "cc-e2e")

print(f"\n{len(failures)} failed: {failures}" if failures else "\nAll passed")
sys.exit(1 if failures else 0)
