#!/usr/bin/env python3
"""
End-to-end check on a real Android device, with no taps needed from you. When no device is connected and ready,
it starts an emulator instead (and shuts it down afterwards).

It installs "Hok6 (test)" (the debug build, a separate app from your own "Hok6", with its own data),
copies the book in test/ to Download/StudyBookCheck on the device, and then checks:

  1. the chapters are listed in natural order (Chapter_2 before Chapter_10)
  2. a chapter lists all its files
  3. a PDF opens and shows its pages
  4. Practice Selector: tapping a character on the page lists the page's characters and opens writing practice
  5. the practice panel shows that character's stroke order (from the bundled stroke data)
  6. Export → Print / Save as PDF produces a US Letter PDF
  7. Export → Share PDF opens Android's share menu with a US Letter PDF drawn as lines and text (not a picture)
  8. writing on a worksheet is kept in app storage when the word is practiced again; Clear erases it and Undo restores it
  9. a stylus stroke on a worksheet is drawn (not taken as scrolling)
 10. typing English ("thank you") suggests Chinese words
 11. pinching while dragging a PDF (zooming while scrolling) doesn't stretch its pages, so tracing stays on the page

Screenshots and a summary go to build/device-check/. Exit code 0 means every check passed.

Usage:
  python3 tools/device_check.py                 # build, install and check on the connected device
  python3 tools/device_check.py --no-build      # use the APK already built
  python3 tools/device_check.py --serial ID     # pick a device when several are connected
  python3 tools/device_check.py --connect IP:PORT   # use a device paired for wireless debugging

One-time device setup is described in README.md ("Checking on a device").
"""
import argparse
import base64
import hashlib
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "build", "device-check")
PACKAGE = "com.studybook.reader.debug"
MAIN = PACKAGE + "/com.studybook.reader.MainActivity"
DEVICE_BOOK = "/sdcard/Download/StudyBookCheck"
DEVTOOLS_PORT = 9333


def natural_key(name):
    return [(0, int(p), "") if p.isdigit() else (1, 0, p.lower()) for p in re.split(r"(\d+)", name) if p]


class Failed(Exception):
    pass


class Device:
    def __init__(self, adb, serial):
        self.adb = [adb] + (["-s", serial] if serial else [])
        self.step = 0

    def run(self, *args, check=True, timeout=120, binary=False):
        result = subprocess.run(self.adb + list(args), capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            raise Failed(f"adb {' '.join(args)}: {result.stderr.decode(errors='replace').strip()}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, command, **kw):
        return self.run("shell", command, **kw)

    # ---- screen

    def screenshot(self, name):
        self.step += 1
        path = os.path.join(OUT, f"{self.step:02d}-{name}.png")
        with open(path, "wb") as f:
            f.write(self.run("exec-out", "screencap", "-p", binary=True))
        return path

    def nodes(self):
        """The views on screen: (text, description, centre x, centre y, enabled)."""
        for _ in range(5):
            self.shell("uiautomator dump /sdcard/sbcheck-ui.xml", check=False, timeout=60)
            xml = self.shell("cat /sdcard/sbcheck-ui.xml", check=False)
            if xml.strip().startswith("<?xml"):
                break
            time.sleep(1)
        else:
            return []
        out = []
        for n in ET.fromstring(xml).iter("node"):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
            out.append((n.get("text", ""), n.get("content-desc", ""), (x1 + x2) // 2, (y1 + y2) // 2, n.get("enabled") == "true"))
        return out

    def find(self, pattern, nodes=None):
        rx = re.compile(pattern, re.I)
        for node in nodes if nodes is not None else self.nodes():
            if rx.fullmatch(node[0]) or rx.fullmatch(node[1]):
                return node
        return None

    def wait_for(self, pattern, timeout=40):
        end = time.time() + timeout
        while time.time() < end:
            nodes = self.nodes()
            self.dismiss_not_responding(nodes)
            node = self.find(pattern, nodes)
            if node:
                return node
            time.sleep(1)
        raise Failed(f"nothing matching {pattern!r} appeared on screen")

    def tap(self, pattern, timeout=40):
        node = self.wait_for(pattern, timeout)
        self.shell(f"input tap {node[2]} {node[3]}")
        return node

    def dismiss_not_responding(self, nodes):
        # Slow devices and emulators sometimes show "isn't responding"; waiting is always the right answer here.
        if self.find(r".*isn.t responding", nodes):
            wait = self.find(r"wait", nodes)
            if wait:
                self.shell(f"input tap {wait[2]} {wait[3]}")

    def focused(self):
        out = self.shell("dumpsys window")
        m = re.findall(r"mCurrentFocus=Window\{[^ ]+ u0 ([^}]+)\}", out)
        return m[-1] if m else ""


# ---- Chrome DevTools, to look inside writing practice (a web page in the app)

def devtools_eval(device, expression, timeout=30):
    pid = device.shell(f"pidof {PACKAGE}").strip().split()[0]
    device.run("forward", f"tcp:{DEVTOOLS_PORT}", f"localabstract:webview_devtools_remote_{pid}")
    pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{DEVTOOLS_PORT}/json", timeout=10))
    page = next(p for p in pages if p.get("type") == "page")
    url = page["webSocketDebuggerUrl"]
    host, port_path = url[len("ws://"):].split(":", 1)
    port, path = port_path.split("/", 1)
    with socket.create_connection(("127.0.0.1", int(port)), timeout=timeout) as s:
        key = base64.b64encode(os.urandom(16)).decode()
        s.sendall((f"GET /{path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            head += s.recv(1)
        message = json.dumps({"id": 1, "method": "Runtime.evaluate",
                              "params": {"expression": expression, "awaitPromise": True, "returnByValue": True}}).encode()
        mask = os.urandom(4)
        frame = bytearray([0x81])
        if len(message) < 126:
            frame.append(0x80 | len(message))
        elif len(message) < 65536:
            frame += bytes([0x80 | 126]) + struct.pack(">H", len(message))
        else:
            frame += bytes([0x80 | 127]) + struct.pack(">Q", len(message))
        frame += mask + bytes(b ^ mask[i % 4] for i, b in enumerate(message))
        s.sendall(frame)

        def exact(n):
            data = b""
            while len(data) < n:
                chunk = s.recv(n - len(data))
                if not chunk:
                    raise Failed("DevTools connection closed")
                data += chunk
            return data

        while True:
            b1, b2 = exact(2)
            length = b2 & 0x7F
            if length == 126:
                length = struct.unpack(">H", exact(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", exact(8))[0]
            payload = exact(length)
            if b1 & 0x0F != 1:
                continue
            reply = json.loads(payload)
            if reply.get("id") == 1:
                result = reply["result"]
                if "exceptionDetails" in result:
                    raise Failed("page error: " + json.dumps(result["exceptionDetails"])[:300])
                return result["result"].get("value")


# ---- the checks

def find_adb():
    sdk = os.environ.get("ANDROID_HOME", "/opt/android-sdk")
    for candidate in (os.path.join(sdk, "platform-tools", "adb"), "adb"):
        try:
            subprocess.run([candidate, "version"], capture_output=True, check=True)
            return candidate
        except (OSError, subprocess.CalledProcessError):
            pass
    sys.exit("adb not found: install the Android platform tools or set ANDROID_HOME")


USBIPD = "/mnt/c/Program Files/usbipd-win/usbipd.exe"


def attach_usb_device(adb):
    """WSL: attach an Android device that Windows has shared (usbipd bind, done once as admin)."""
    if not os.path.exists(USBIPD):
        return
    listing = subprocess.run([USBIPD, "list"], capture_output=True, text=True).stdout
    for line in listing.splitlines():
        m = re.match(r"\s*(\d+-\d+)\s+([0-9a-f]{4}):([0-9a-f]{4})\s+(.*?)\s{2,}(Shared|Attached)", line, re.I)
        name = m.group(4).lower() if m else ""
        android = m and (m.group(2).lower() == "18d1" or "android" in name or "razer edge" in name or "adb" in name)
        if android and m.group(5).lower() == "shared":
            print(f"attaching {m.group(4).strip()} (USB {m.group(1)}) to WSL…")
            subprocess.run([USBIPD, "attach", "--wsl", "--busid", m.group(1)], capture_output=True)
            for _ in range(20):
                out = subprocess.run([adb, "devices"], capture_output=True, text=True).stdout
                if re.search(r"\n\S+\s+(device|unauthorized)", out):
                    return
                time.sleep(1)


def adb_devices(adb):
    out = subprocess.run([adb, "devices"], capture_output=True, text=True).stdout
    ready = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
    unauthorized = [l.split()[0] for l in out.splitlines()[1:] if "unauthorized" in l]
    return ready, unauthorized


def start_emulator(adb, avd):
    """Boots an Android emulator with no window and waits until it is idle enough to test on."""
    emulator = os.path.join(os.environ.get("ANDROID_HOME", "/opt/android-sdk"), "emulator", "emulator")
    avds = subprocess.run([emulator, "-list-avds"], capture_output=True, text=True).stdout.split()
    if not avds:
        sys.exit("No device and no emulator (AVD) to fall back on; create one with avdmanager.")
    avd = avd if avd in avds else ("tab" if "tab" in avds else avds[0])
    print(f"no device ready — starting the '{avd}' emulator (a few minutes)…")
    log = open(os.path.join(OUT, "emulator.log"), "w")
    subprocess.Popen([emulator, "-avd", avd, "-no-window", "-no-audio", "-no-boot-anim", "-gpu", "swiftshader_indirect",
                      "-memory", "3072", "-no-snapshot"], stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    end = time.time() + 600
    serial = None
    while time.time() < end:
        ready, _ = adb_devices(adb)
        serial = next((d for d in ready if d.startswith("emulator-")), None)
        if serial:
            booted = subprocess.run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"], capture_output=True, text=True)
            if booted.stdout.strip() == "1":
                break
        time.sleep(5)
    else:
        sys.exit("the emulator did not start; see build/device-check/emulator.log")
    # Let the first-boot work settle, and turn off animations so the checks don't wait on them.
    while time.time() < end:
        load = subprocess.run([adb, "-s", serial, "shell", "cat", "/proc/loadavg"], capture_output=True, text=True).stdout
        if load and float(load.split()[0]) < 3:
            break
        time.sleep(5)
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        subprocess.run([adb, "-s", serial, "shell", "settings", "put", "global", setting, "0"], capture_output=True)
    return serial


def choose_device(adb, serial, connect, use_emulator, avd):
    """A real device when one is connected and ready, otherwise an emulator. Returns (serial, started_emulator)."""
    if connect:
        subprocess.run([adb, "connect", connect], check=False)
        serial = serial or connect
    if not use_emulator:
        attach_usb_device(adb)
    ready, unauthorized = adb_devices(adb)
    if serial:
        if serial not in ready:
            sys.exit(f"device {serial} is not connected")
        return serial, False
    physical = [d for d in ready if not d.startswith("emulator-")]
    emulators = [d for d in ready if d.startswith("emulator-")]
    if physical and not use_emulator:
        if len(physical) > 1:
            sys.exit("Several devices are connected; choose one with --serial: " + ", ".join(physical))
        return physical[0], False
    if unauthorized and not use_emulator:
        print("note: a device is connected but hasn't allowed USB debugging yet (unlock it and tap "
              "\"Always allow from this computer\" → Allow); using an emulator this time.")
    elif not use_emulator:
        print("note: no device ready (see README.md, \"Checking on a device\"); using an emulator.")
    if emulators:
        return emulators[0], False
    return start_emulator(adb, avd), True


def install(device, build):
    if build:
        print("building…")
        subprocess.run([os.path.join(ROOT, "gradlew"), "assembleDebug", "-q"], cwd=ROOT, check=True)
    abis = device.shell("getprop ro.product.cpu.abilist").strip().split(",")
    folder = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug")
    apk = next((os.path.join(folder, f"app-{abi}-debug.apk") for abi in abis
                if os.path.exists(os.path.join(folder, f"app-{abi}-debug.apk"))), None)
    if not apk:
        raise Failed(f"no debug APK for this device's CPU ({', '.join(abis)}); build first")
    with open(apk, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    marker = "/data/local/tmp/studybook-check.sha256"
    installed = device.shell(f"pm path {PACKAGE}", check=False).strip()
    if installed and device.shell(f"cat {marker}", check=False).strip() == digest:
        print(f"{os.path.basename(apk)} already installed")
        return

    def updated():
        out = device.shell(f"dumpsys package {PACKAGE}", check=False, timeout=60)
        m = re.search(r"lastUpdateTime=(.+)", out)
        return m.group(1).strip() if m else ""

    before = updated()
    print(f"installing {os.path.basename(apk)}…")
    # Large transfers over USB passed through to WSL occasionally stall; check whether the install landed anyway,
    # and retry once without streaming.
    for flags in ([], ["--no-streaming"]):
        try:
            device.run("install", "-r", *flags, apk, timeout=300)
            break
        except subprocess.TimeoutExpired:
            if updated() != before:
                break
    else:
        raise Failed("installing the test app did not finish")
    device.shell(f"echo {digest} > {marker}")


def push_book(device, book):
    files = []
    for folder, _, names in os.walk(book):
        for name in names:
            if not name.endswith(":Zone.Identifier"):
                files.append(os.path.relpath(os.path.join(folder, name), book))
    have = device.shell(f"find {DEVICE_BOOK} -type f 2>/dev/null | wc -l", check=False).strip()
    if have == str(len(files)):
        return
    print(f"copying {len(files)} book files to the device…")
    device.shell(f"rm -rf {DEVICE_BOOK}", check=False)
    for rel in files:
        target = f"{DEVICE_BOOK}/{rel}"
        local = os.path.join(book, rel)
        device.shell(f"mkdir -p \"{os.path.dirname(target)}\"")
        for attempt in range(3):
            try:
                device.run("push", local, target, timeout=120)
                break
            except subprocess.TimeoutExpired:
                # Over USB passed through to WSL, adb sometimes never reports a finished copy: check the file itself.
                size = device.shell(f"stat -c %s \"{target}\"", check=False, timeout=30).strip()
                if size == str(os.path.getsize(local)):
                    break
        else:
            raise Failed(f"could not copy {rel} to the device")


def chapter_layout(book):
    chapters = sorted((d for d in os.listdir(book) if os.path.isdir(os.path.join(book, d))), key=natural_key)
    for chapter in chapters:
        files = sorted((f for f in os.listdir(os.path.join(book, chapter)) if not f.endswith(":Zone.Identifier")), key=natural_key)
        pdfs = [f for f in files if f.lower().endswith(".pdf")]
        if pdfs:
            return chapters, chapter, files, pdfs[0]
    raise Failed("the test book has no chapter with a PDF")


def check(name, results, device, fn):
    print(f"• {name} … ", end="", flush=True)
    try:
        detail = fn()
        shot = device.screenshot(re.sub(r"\W+", "-", name.lower()).strip("-"))
        results.append((name, True, detail or "", shot))
        print("PASS" + (f" ({detail})" if detail else ""))
        return True
    except Exception as e:  # noqa: BLE001 - report every failure the same way
        shot = device.screenshot("FAILED-" + re.sub(r"\W+", "-", name.lower()).strip("-"))
        results.append((name, False, str(e), shot))
        print(f"FAIL: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", help="device to use (see `adb devices`)")
    parser.add_argument("--connect", help="IP:PORT of a device paired for wireless debugging")
    parser.add_argument("--no-build", action="store_true", help="don't build; install the existing debug APK")
    parser.add_argument("--book", default=os.path.join(ROOT, "test"), help="book folder to test with (default: test/)")
    parser.add_argument("--emulator", action="store_true", help="use an emulator even if a device is connected")
    parser.add_argument("--avd", default="tab", help="emulator to start when no device is ready (default: tab)")
    parser.add_argument("--keep-emulator", action="store_true", help="leave an emulator this script started running")
    args = parser.parse_args()

    os.makedirs(OUT, exist_ok=True)
    for old in os.listdir(OUT):
        os.remove(os.path.join(OUT, old))
    if not os.path.isdir(args.book):
        sys.exit(f"book folder {args.book} not found")

    adb = find_adb()
    serial, started_emulator = choose_device(adb, args.serial, args.connect, args.emulator, args.avd)
    device = Device(adb, serial)
    model = device.shell("getprop ro.product.model").strip()
    android = device.shell("getprop ro.build.version.release").strip()
    print(f"device: {model}, Android {android}")

    device.shell("input keyevent KEYCODE_WAKEUP")
    if "mDreamingLockscreen=true" in device.shell("dumpsys window") or "isStatusBarKeyguard=true" in device.shell("dumpsys window"):
        device.shell("wm dismiss-keyguard", check=False)
        time.sleep(1)
    install(device, not args.no_build)
    push_book(device, args.book)
    chapters, chapter, chapter_files, pdf = chapter_layout(args.book)

    results = []
    device.shell(f"am force-stop {PACKAGE}")
    device.shell(f"touch {DEVICE_BOOK}/.check-started")
    device.shell(f"am start -n {MAIN}")
    time.sleep(3)

    # First run only: the welcome screen (downloads aren't needed for these checks), then access to the book folder
    # (the picker starts in Download).
    if device.find(r"skip"):
        device.tap(r"skip")
        time.sleep(2)
    if device.find(r"choose book folder"):
        print("granting access to the book folder…")
        device.tap(r"choose book folder")
        device.tap(r"StudyBookCheck", timeout=60)
        time.sleep(2)
        device.tap(r"use this folder")
        device.tap(r"allow")
        time.sleep(3)

    def chapters_in_order():
        device.wait_for(re.escape(chapters[0]))
        shown = [t for t, *_ in device.nodes() if t in chapters]
        if not shown:
            raise Failed("no chapters on screen")
        if shown != chapters[: len(shown)]:
            raise Failed(f"order on screen {shown} ≠ expected {chapters[: len(shown)]}")
        return f"{len(shown)} shown, in order"

    def chapter_lists_files():
        device.tap(re.escape(chapter))
        device.wait_for(re.escape(chapter_files[0]))
        on_screen = {t for t, *_ in device.nodes()}
        missing = [f for f in chapter_files if f not in on_screen]
        if missing:
            raise Failed(f"files not listed: {missing}")
        return f"{chapter}: {len(chapter_files)} files"

    def pdf_opens():
        device.tap(re.escape(pdf))
        node = device.wait_for(r"1 / \d+", timeout=60)
        return f"{pdf} — page indicator {node[0]}"

    practised = {}

    def practise_from_pdf():
        # Practice Selector on, then tap a character of the first page's title (centred near the top of the page).
        device.tap(r"practice selector.*")
        time.sleep(1)
        xml = device.shell("cat /sdcard/sbcheck-ui.xml", check=False)
        frame = next((n for n in ET.fromstring(xml).iter("node") if n.get("resource-id", "").endswith("/pdf_frame")), None)
        if frame is None:
            raise Failed("the PDF isn't on screen")
        left, top, right, _ = map(int, re.findall(r"\d+", frame.get("bounds")))
        width = right - left
        for dy in (0.075, 0.07, 0.08, 0.065, 0.085):
            for dx in (0.48, 0.45, 0.51):
                device.shell(f"input tap {left + int(dx * width)} {top + int(dy * width)}")
                time.sleep(2)
                if device.find(r"practice writing"):
                    break
            else:
                continue
            break
        else:
            raise Failed("tapping the page title in Practice Selector mode didn't open the character list")
        chips = [n for n in device.nodes() if len(n[0]) == 1 and re.match(r"[㐀-鿿\U00020000-\U0003ffff]", n[0])]
        if not chips:
            raise Failed("no Chinese characters listed for the page")
        # The character tapped on the page is already chosen.
        xml = device.shell("cat /sdcard/sbcheck-ui.xml", check=False)
        ticked = [n.get("text") for n in ET.fromstring(xml).iter("node")
                  if n.get("checked") == "true" and len(n.get("text", "")) == 1]
        chip = next((c for c in chips if c[0] in ticked), None)
        if chip is None:
            chip = next((c for c in chips if c[0] not in "姓名日期"), chips[0])
            device.shell(f"input tap {chip[2]} {chip[3]}")
        device.tap(r"practice \(1\)")
        end = time.time() + 90
        while "TrainingActivity" not in device.focused():
            if time.time() > end:
                raise Failed("writing practice did not open")
            time.sleep(2)
        practised["char"] = chip[0]
        return f"{len(chips)} characters on the page; chose {chip[0]}"

    def stroke_order_shown():
        ch = practised["char"]
        end = time.time() + 60
        while True:
            try:
                value = devtools_eval(device, "(async()=>{ if(!state.ws||document.getElementById('practice').hidden) return null;"
                                              " const d=await loadChar(state.ws.chars[pIndex]); return {ch: state.ws.chars[pIndex],"
                                              " strokes: d ? d.strokes.length : 0, status: document.getElementById('pStatus').textContent} })()")
                if value:
                    break
            except Exception:  # noqa: BLE001 - the page may still be loading
                value = None
            if time.time() > end:
                raise Failed("practice panel did not open")
            time.sleep(2)
        if value["ch"] != ch:
            raise Failed(f"practising {value['ch']}, expected {ch}")
        if value["strokes"] <= 0:
            raise Failed(f"no stroke data for {ch}")
        return f"{ch}: {value['strokes']} strokes — {value['status']}"

    def save_pdf():
        devtools_eval(device, "(()=>{ closePractice(); document.getElementById('printBtn').click();"
                              " document.querySelector('input[name=exportInk][value=blank]').checked = true;"
                              " setTimeout(()=>document.querySelector('[data-action=print]').click(), 300); return 1 })()")
        device.wait_for(r"save as pdf|select (a )?printer|all printers.*", timeout=60)
        if not device.find(r"save as pdf"):
            device.tap(r"select (a )?printer|.*printer.*")
            device.tap(r"save as pdf")
        device.tap(r"save to pdf", timeout=60)
        # Samsung asks for a folder in My Files ("Select folder" … Done) instead of Android's Save screen.
        node = device.wait_for(r"save|select folder", timeout=60)
        if re.fullmatch(r"select folder", node[0] or node[1], re.I):
            device.tap(r"download")
            time.sleep(1)
            device.tap(r"done")
        else:
            device.tap(r"save", timeout=60)
        end = time.time() + 60
        newest = ""
        while time.time() < end:
            newest = device.shell(f"find /sdcard/Download /sdcard/Documents -iname '*.pdf' -newer {DEVICE_BOOK}/.check-started 2>/dev/null",
                                  check=False).strip().splitlines()
            if newest:
                break
            time.sleep(2)
        if not newest:
            raise Failed("no PDF was saved")
        target = newest[0]
        # Android creates the file first and fills it in afterwards: wait until its size stops changing.
        last = -1
        while time.time() < end + 60:
            size = int(device.shell(f"stat -c %s \"{target}\"", check=False).strip() or 0)
            if size > 0 and size == last:
                break
            last = size
            time.sleep(2)
        local = os.path.join(OUT, "worksheet.pdf")
        device.run("pull", target, local)
        device.shell(f"rm \"{target}\"", check=False)
        data = open(local, "rb").read()
        box = re.search(rb"/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+([\d.]+)\s*\]", data)
        if not box:
            raise Failed("saved file is not a readable PDF")
        size = (round(float(box.group(1))), round(float(box.group(2))))
        if size != (612, 792):
            raise Failed(f"page size {size} points, expected US Letter (612, 792)")
        return f"US Letter, {len(data) // 1024} KB"

    def pdf_size(data):
        box = re.search(rb"/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+([\d.]+)\s*\]", data)
        return (round(float(box.group(1))), round(float(box.group(2)))) if box else None

    def share_pdf():
        # Back in writing practice after printing; Share PDF makes the file and opens Android's share menu.
        end = time.time() + 60
        while "TrainingActivity" not in device.focused():
            if time.time() > end:
                raise Failed("writing practice is not showing")
            device.shell("input keyevent KEYCODE_BACK")
            time.sleep(2)
        device.shell(f"run-as {PACKAGE} rm -rf cache/exports", check=False)
        devtools_eval(device, "(()=>{ document.getElementById('printBtn').click();"
                              " setTimeout(()=>document.querySelector('[data-action=share]').click(), 300); return 1 })()")
        device.wait_for(r"share worksheet|.*share.*", timeout=90)
        files = device.shell(f"run-as {PACKAGE} ls cache/exports", check=False).strip().splitlines()
        if not files:
            raise Failed("no PDF was made for sharing")
        data = device.run("exec-out", "run-as", PACKAGE, "cat", f"cache/exports/{files[0]}", binary=True)
        device.shell("input keyevent KEYCODE_BACK")
        with open(os.path.join(OUT, "shared.pdf"), "wb") as f:
            f.write(data)
        if pdf_size(data) != (612, 792):
            raise Failed(f"page size {pdf_size(data)}, expected US Letter (612, 792)")
        if re.search(rb"/Subtype\s*/Image", data):
            raise Failed("the shared PDF is a picture of the page, not lines and text")
        if not re.search(rb"/Font\b", data):
            raise Failed("the shared PDF has no text")
        return f"share menu opened with {files[0]} ({len(data) // 1024} KB, US Letter, lines and text)"

    def writing_kept_and_cleared():
        # Write a stroke on the word shown, practice the same word again, then Clear and Undo.
        value = devtools_eval(device, "(async()=>{ const p=state.row, ws=state.ws;"
                                      " (state.ink[p]=state.ink[p]||[]).push({c:'#212121',w:0.8,p:[40,60,60,80,80,70]});"
                                      " redrawInk(p); saveInkSoon(p); saveInk();"
                                      " const stored=Native.storeGet(inkKey(p))!=null;"
                                      " closeSheet(); await openSheet(ws); const kept=(state.ink[p]||[]).length;"
                                      " document.getElementById('clearBtn').click();"
                                      " const left=(state.ink[p]||[]).length+pageSvg(p).querySelectorAll('.ink path').length;"
                                      " undo(); const back=pageSvg(p).querySelectorAll('.ink path').length;"
                                      " document.getElementById('clearBtn').click(); saveInk();"
                                      " return {stored, kept, left, back, gone: Native.storeGet(inkKey(p))==null} })()", timeout=60)
        if not value or not value["stored"]:
            raise Failed(f"writing was not saved in app storage: {value}")
        if value["kept"] < 1:
            raise Failed("writing was gone when the word was practised again")
        if value["left"] != 0 or value["back"] < 1 or not value["gone"]:
            raise Failed(f"Clear / Undo didn't work: {value}")
        return f"kept {value['kept']} stroke(s) after reopening; Clear erased them, Undo brought them back"

    def stylus_writes():
        # A stylus stroke in an empty writing square is drawn and kept: it must not scroll the page instead.
        # Phones show one row at a time; the check draws on the whole page, so it switches there and back.
        view = devtools_eval(device, "(()=>{ const v=state.view; if(v==='row') setView('page'); return v })()")
        spot = devtools_eval(device, "(()=>{ const pages=document.getElementById('pages');"
                                     " const svg=pageSvg(state.row); let r=svg.getBoundingClientRect();"
                                     " pages.scrollTop += r.top + 0.75*r.height - innerHeight/2; r=svg.getBoundingClientRect();"
                                     " return {x: r.left + 0.3*r.width, y: r.top + 0.75*r.height, w: r.width, dpr: devicePixelRatio,"
                                     " before: (state.ink[state.row]||[]).length} })()")
        device.shell("uiautomator dump /sdcard/sbcheck-ui.xml", check=False, timeout=60)
        xml = device.shell("cat /sdcard/sbcheck-ui.xml", check=False)
        web = next((n for n in ET.fromstring(xml).iter("node") if n.get("class") == "android.webkit.WebView"), None)
        left, top = (map(int, re.findall(r"\d+", web.get("bounds"))[:2])) if web is not None else (0, 0)
        x1, y1 = left + int(spot["x"] * spot["dpr"]), top + int(spot["y"] * spot["dpr"])
        x2, y2 = x1 + int(0.15 * spot["w"] * spot["dpr"]), y1 + int(0.03 * spot["w"] * spot["dpr"])
        device.shell(f"input stylus swipe {x1} {y1} {x2} {y2} 500")
        time.sleep(1.5)
        after = devtools_eval(device, "(state.ink[state.row]||[]).length")
        devtools_eval(device, "(()=>{ undo(); saveInk(); setView(%s); return 1 })()" % json.dumps(view))
        if after != spot["before"] + 1:
            raise Failed(f"a stylus stroke wasn't drawn ({spot['before']} strokes before, {after} after)")
        return "a stylus stroke in a writing square was drawn and kept"

    def page_shapes():
        out = device.shell("dumpsys activity top", check=False)
        start = out.rfind("ACTIVITY " + PACKAGE + "/")
        if start < 0:
            return []
        end = out.find("\n  ACTIVITY ", start + 1)
        out = out[start:end if end > 0 else None]
        return [(int(r) - int(l), int(b) - int(t)) for l, t, r, b in
                re.findall(r"InkPageView\{[^}]*? (-?\d+),(-?\d+)-(-?\d+),(-?\d+)", out) if int(r) > int(l)]

    def gesture(frames):
        # Several fingers at once, played by the test build's TestGestures receiver (adb's `input` has only one).
        js = json.dumps(frames, separators=(",", ":"))
        device.shell(f"am broadcast -n {PACKAGE}/com.studybook.reader.TestGestures --es frames '{js}'")
        time.sleep(sum(f[0] for f in frames) / 1000 + 0.8)

    def zoom_keeps_page_shape():
        # Pinching while dragging (zooming while scrolling) must not stretch the pages, or tracing lands off the picture.
        device.shell(f"am force-stop {PACKAGE}")
        device.shell(f"am start -n {MAIN}")
        device.tap(re.escape(chapter))
        device.tap(re.escape(pdf))
        device.wait_for(r"1 / \d+", timeout=60)
        time.sleep(1)
        before = page_shapes()
        if not before:
            raise Failed("no PDF pages found on screen")
        w, h = before[0]
        size = device.shell("wm size").split()[-1]
        sw, sh = map(int, size.split("x"))
        cx, cy = sw // 2, sh // 2
        for _ in range(3):
            pinch_in = [[16, [[cx - d, cy], [cx + d, cy]]] for d in range(sw // 10, sw // 3, sw // 30)] + [[16, [None, None]]]
            gesture(pinch_in)
            frames = [[16, [[cx - d, cy], [cx + d, cy]]] for d in range(sw // 3, sw // 12, -(sw // 24))]
            # One finger lifts and touches again at once: the pinch ends and the drag carries on with no pause.
            frames += [[0, [[cx - sw // 12, cy], None]], [0, [[cx - sw // 12, cy], [cx + sw // 12, cy]]]]
            frames += [[4, [[cx - sw // 12, cy - k * sh // 40], [cx + sw // 12, cy - k * sh // 40]]] for k in range(1, 9)]
            gesture(frames + [[16, [None, None]]])
        time.sleep(1)
        after = page_shapes()
        if not after:
            raise Failed("no PDF pages on screen after the gestures")
        bad = [(pw, ph) for pw, ph in after if abs(ph / pw - h / w) > 0.01]
        if bad:
            raise Failed(f"pages stretched: {bad} (should be {h / w:.3f} tall per width)")
        return f"{len(after)} pages kept their shape ({h / w:.3f}) after pinching while dragging"

    def english_lookup():
        value = devtools_eval(device, "(async()=>{ if(!document.getElementById('sheet').hidden) closeSheet();"
                                      " const f=document.getElementById('fChars'); f.value='thank you';"
                                      " f.dispatchEvent(new Event('input')); await new Promise(r=>setTimeout(r,4000));"
                                      " return [...document.querySelectorAll('#trResults .tr-word')].map(n=>n.textContent) })()", timeout=60)
        if not value:
            raise Failed("no Chinese suggestions for “thank you”")
        if value[0] != "多謝" or "謝謝" not in value:
            raise Failed(f"unexpected suggestions for “thank you”: {value}")
        return "“thank you” → " + " ".join(value[:4])

    ok = (check("Chapters are listed in order", results, device, chapters_in_order)
          and check("A chapter lists its files", results, device, chapter_lists_files)
          and check("A PDF opens", results, device, pdf_opens)
          and check("Practice writing from the PDF", results, device, practise_from_pdf)
          and check("Stroke order is shown", results, device, stroke_order_shown)
          and check("Worksheet saves as a PDF", results, device, save_pdf)
          and check("Worksheet shares as a PDF", results, device, share_pdf)
          and check("Writing is kept; Clear erases it", results, device, writing_kept_and_cleared)
          and check("A stylus writes on the worksheet", results, device, stylus_writes)
          and check("English is looked up", results, device, english_lookup)
          and check("Zooming while scrolling keeps pages in shape", results, device, zoom_keeps_page_shape))

    device.shell(f"am force-stop {PACKAGE}", check=False)
    if started_emulator and not args.keep_emulator:
        device.run("emu", "kill", check=False)
    with open(os.path.join(OUT, "summary.txt"), "w") as f:
        f.write(f"{model}, Android {android}\n")
        for name, passed, detail, shot in results:
            f.write(f"{'PASS' if passed else 'FAIL'}  {name}  {detail}  [{os.path.basename(shot)}]\n")
    print(f"\n{'All checks passed' if ok else 'Some checks FAILED'} — screenshots in {os.path.relpath(OUT, ROOT)}/")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    try:
        main()
    except Failed as e:
        sys.exit(f"error: {e}")
