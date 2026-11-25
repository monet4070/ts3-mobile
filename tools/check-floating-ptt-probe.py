"""Run the Debug microphone-eligibility probe on a fresh emulator with host audio disabled."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from audio_build_config import build_tools, require_file

PACKAGE = "io.github.ts3mobile.app"
PROBE = PACKAGE + "/.probe.FloatingPttProbeService"
ENTRY = PACKAGE + "/.probe.FloatingPttLab"
WINDOW = re.compile(r"(?=^\s*Window #\d+ Window\{)", re.MULTILINE)
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
FINAL = re.compile(r"capture_finished starts=(\d+) usable=(\d+) failures=(\d+) startMs=([^ ]+) releaseMs=(\S+)")


def center(bounds):
    left, top, right, bottom = map(int, BOUNDS.fullmatch(bounds).groups())
    return str((left + right) // 2), str((top + bottom) // 2)


def overlay_frame(dump):
    for window in WINDOW.split(dump):
        if re.search(r"\bpackage=" + re.escape(PACKAGE) + r"\s", window) and "ty=APPLICATION_OVERLAY" in window:
            frame = re.search(r"\bframe=(\[-?\d+,-?\d+\]\[-?\d+,-?\d+\])", window)
            if frame and "isVisible=true" in window and "mDrawState=HAS_DRAWN" in window:
                return tuple(map(int, BOUNDS.fullmatch(frame[1]).groups()))
    return None


def overlay_control(frame, header_height, microphone=False):
    left, top, right, bottom = frame
    height = min(bottom - top, header_height)
    horizontal = right - height // 2 if microphone else left + (right - left) // 4
    return str(horizontal), str(top + height // 2)


def recording_is_active(dump):
    match = re.search(r"RecordActivityMonitor dump time:[^\n]*\n(.*?)Events log: recording activity", dump, re.DOTALL)
    if not match:
        raise RuntimeError("Cannot verify active recordings from this audio service dump")
    return PACKAGE in match[1]


def wait_for(operation, seconds=8, *, transition="probe state"):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = operation()
        if result:
            return result
        time.sleep(.2)
    raise RuntimeError("Timed out waiting for " + transition)


def main():
    repository = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--host-audio-disabled", action="store_true", help="Confirm emulator was started with -no-audio")
    parser.add_argument("--presses", default=20, type=int, choices=range(2, 21))
    parser.add_argument("--debug-apk", type=Path, default=repository / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--release-apk", type=Path, default=repository / "app/build/outputs/apk/release/app-release-unsigned.apk")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.device):
        parser.error("Use a fresh Android emulator, never a physical device")
    if not args.host_audio_disabled or not args.sdk:
        parser.error("Start the emulator with -no-audio and specify --host-audio-disabled and an Android SDK")
    suffix = ".exe" if os.name == "nt" else ""
    sdk = Path(args.sdk).resolve()
    adb = require_file(sdk / ("platform-tools/adb" + suffix))
    aapt = require_file(build_tools(sdk) / ("aapt" + suffix))
    debug_apk = require_file(args.debug_apk.resolve())
    release_apk = require_file(args.release_apk.resolve())
    run_id = uuid.uuid4().hex
    remote_xml = "/data/local/tmp/ts3-ptt-lab-" + run_id + ".xml"

    for artifact, dev in ((debug_apk, True), (release_apk, False)):
        report = subprocess.check_output([aapt, "dump", "badging", artifact], text=True, timeout=10)
        manifest = subprocess.check_output([aapt, "dump", "xmltree", artifact, "AndroidManifest.xml"], text=True, timeout=10)
        if not report.startswith("package: name='" + PACKAGE + "'") or ("application-debuggable" in report) != dev:
            raise RuntimeError("Expected the app's Debug and standard Release artifacts")
        for marker in ("SYSTEM_ALERT_WINDOW", "FloatingPttActivity"):
            if marker not in manifest:
                raise RuntimeError("Missing connected PTT permission or activity")
        for marker in ("FloatingPttProbeService", "FloatingPttProbeActivity", "FloatingPttLab"):
            if (marker in manifest) != dev:
                raise RuntimeError("Probe permission or component leaked across build types")

    def device(*command, allow_missing=False):
        result = subprocess.run([adb, "-s", args.device, *command], capture_output=True, text=True, timeout=20)
        if result.returncode != 0 and not (allow_missing and result.returncode == 1):
            raise RuntimeError("Emulator command failed: " + command[0])
        return result.stdout.strip()

    if device("shell", "getprop", "ro.kernel.qemu") != "1" or device("shell", "getprop", "sys.boot_completed") != "1":
        raise RuntimeError("Expected a fully booted emulator")
    if device("shell", "pm", "path", PACKAGE, allow_missing=True):
        raise RuntimeError("Use a fresh emulator that does not already have this application")

    def probe_log(pid):
        return device("shell", "logcat", "-d", "--pid=" + pid, "-s", "Ts3PttProbe:I", "*:S")

    def is_foreground():
        return "isForeground=true" in device("shell", "dumpsys", "activity", "services", PROBE)

    def overlay():
        return overlay_frame(device("shell", "dumpsys", "window", "windows"))

    def tap_arm():
        device("shell", "uiautomator", "dump", remote_xml)
        ui = ET.fromstring(device("exec-out", "cat", remote_xml))
        button = next((node for node in ui.iter("node") if node.get("text", "").casefold() == "arm for five minutes"), None)
        if button is None:
            raise RuntimeError("Expected the English Debug probe activity")
        device("shell", "input", "tap", *center(button.get("bounds")))

    def require_idle():
        if recording_is_active(device("shell", "dumpsys", "audio")):
            raise RuntimeError("Recorder still active outside a press")

    def go_home():
        device("shell", "input", "keyevent", "KEYCODE_HOME")
        wait_for(
            lambda: launcher in device("shell", "dumpsys", "activity", "activities").partition("topResumedActivity=")[2].split("\n", 1)[0],
            transition="the launcher to resume",
        )
        return wait_for(overlay, transition="a drawn overlay on the launcher")

    def screenshot():
        return subprocess.check_output([adb, "-s", args.device, "exec-out", "screencap", "-p"], timeout=10)

    launcher = device("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                      "-c", "android.intent.category.HOME").splitlines()[-1]

    installed = False
    try:
        device("install", "--no-incremental", "-t", str(debug_apk))
        installed = True
        device("shell", "pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
        device("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        device("shell", "wm", "dismiss-keyguard")
        device("shell", "am", "start", "-W", "-n", ENTRY)
        pid = device("shell", "pidof", "-s", PACKAGE)
        if not pid.isdecimal():
            raise RuntimeError("Cannot locate the installed probe's process")
        tap_arm()
        if is_foreground() or overlay():
            raise RuntimeError("Probe armed despite missing overlay permission")
        device("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
        tap_arm()
        wait_for(is_foreground)
        require_idle()
        docked = go_home()
        require_idle()
        width = docked[2] - docked[0]
        header_height = docked[3] - docked[1]
        compact_image = screenshot()
        device("shell", "input", "tap", *overlay_control(docked, header_height))
        expanded = wait_for(lambda: frame if (frame := overlay()) and frame[2] - frame[0] > width else None,
                            transition="the overlay to expand")
        if (expanded[1], expanded[2]) != (docked[1], docked[2]):
            raise RuntimeError("Expanding moved the docked edge or header")
        time.sleep(.35)
        expanded_image = screenshot()
        device("shell", "input", "tap", *overlay_control(expanded, header_height))
        wait_for(lambda: overlay() == docked, transition="the overlay to collapse into its original position")
        time.sleep(.35)
        start_x, start_y = overlay_control(docked, header_height)
        device("shell", "input", "swipe", start_x, start_y, "28", str(int(start_y) + 150), "450")
        dragged = wait_for(lambda: frame if (frame := overlay()) and frame[0] < docked[0] // 2 and frame[1] > docked[1] else None,
                           transition="a drag to the opposite side and a different height")
        require_idle()
        if FINAL.search(probe_log(pid)):
            raise RuntimeError("Opening, collapsing or dragging the panel started microphone capture")
        device("shell", "am", "start", "-W", "-n", ENTRY)
        wait_for(lambda: not overlay())
        if go_home() != dragged:
            raise RuntimeError("Overlay did not remember its dock after being hidden")
        for expected in range(1, args.presses + 1):
            point = overlay_control(wait_for(overlay), header_height, microphone=True)
            device("shell", "input", "swipe", *point, *point, "450")
            wait_for(lambda: len(FINAL.findall(probe_log(pid))) >= expected)
            require_idle()
        log = probe_log(pid)
        starts, usable, failures, opening, closing = FINAL.findall(log)[-1]
        if int(starts) != args.presses or int(failures) != 0 or closing == "—" or "ready foreground_type=128" not in log:
            raise RuntimeError("Expected all probe presses to open and release the microphone with an eligible foreground type")
        device("shell", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "ignore")
        wait_for(lambda: not is_foreground() and not overlay())
        require_idle()
        result = {
            "api": device("shell", "getprop", "ro.build.version.sdk"),
            "debugSha256": hashlib.sha256(debug_apk.read_bytes()).hexdigest(),
            "releaseSha256": hashlib.sha256(release_apk.read_bytes()).hexdigest(),
            "presses": args.presses,
            "captureStarts": int(starts),
            "nonSilentPresses": int(usable),
            "failures": int(failures),
            "openingMsP50P95": opening,
            "releaseMsP50P95": closing,
            "idleAndEveryReleaseInactive": True,
            "dragExpandCollapseDidNotRecord": True,
            "dockRestoredAfterHiding": True,
            "permissionRevokeRemovedOverlayAndForegroundType": True,
            "releaseContainsProbe": False,
            "sentVoiceFrames": 0,
        }
        evidence = repository / "build/floating-ptt-probe" / run_id
        evidence.mkdir(parents=True)
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        (evidence / "compact.png").write_bytes(compact_image)
        (evidence / "expanded.png").write_bytes(expanded_image)
        print("Floating PTT eligibility evidence: " + str(evidence / "result.json"))
    finally:
        if installed:
            device("shell", "am", "force-stop", PACKAGE)
            device("shell", "rm", "-f", remote_xml)


if __name__ == "__main__":
    main()
