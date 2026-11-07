"""Kill a synthetic identity transaction at real durable boundaries on a fresh disposable emulator."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

from apk_signing import apk_certificate_sha256
from audio_build_config import android_config, build_tools, require_file

POINTS = (
    "before-transaction", "during-transaction", "after-transaction", "before-cleanup",
    "after-cleanup", "damaged-active", "damaged-both", "missing-key",
)
PACKAGE = "io.github.ts3mobile.app"
TEST_PACKAGE = PACKAGE + ".test"
TEST_CLASS = PACKAGE + ".identity.IdentityProcessCrashTest"


def main():
    repository = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--app-apk", type=Path, default=repository / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path,
                        default=repository / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    args = parser.parse_args()
    # Must precede any SDK/APK access or external command.
    if not re.fullmatch(r"emulator-[0-9]+", args.device):
        parser.error("Use a fresh disposable emulator, never a physical device")
    if not args.sdk:
        parser.error("Set ANDROID_HOME or --sdk")
    sdk = Path(args.sdk).resolve()
    suffix = ".exe" if os.name == "nt" else ""
    adb = require_file(sdk / ("platform-tools/adb" + suffix))
    tools = build_tools(sdk)
    signer = require_file(tools / ("apksigner.bat" if os.name == "nt" else "apksigner"))
    aapt = require_file(tools / ("aapt" + suffix))
    app = require_file(args.app_apk.resolve())
    test = require_file(args.test_apk.resolve())
    android_config(repository)
    root = repository / "build/identity-crash" / uuid.uuid4().hex
    root.mkdir(parents=True)
    serial = [str(adb), "-s", args.device]

    def run(command, allowed=(0,), timeout=45):
        result = subprocess.run([str(p) for p in command], capture_output=True, text=True, timeout=timeout)
        with (root / "operations.log").open("a") as log:
            log.write(result.stdout + result.stderr + "\n")
        if result.returncode not in allowed:
            raise RuntimeError("Crash-check command failed; preserve the emulator and inspect local evidence")
        return result.stdout

    for apk, package in ((app, PACKAGE), (test, TEST_PACKAGE)):
        badging = run([aapt, "dump", "badging", apk])
        if not re.search(r"package: name='" + re.escape(package) + "'", badging):
            raise RuntimeError("Unexpected fixture APK package")
        if apk == app and "application-debuggable" not in badging:
            raise RuntimeError("Crash runner requires a Debug candidate; minified user flows are validated separately")
    certificates = []
    for apk in (app, test):
        certificates.append(apk_certificate_sha256(run([signer, "verify", "--print-certs", apk])))
    if certificates[0] != certificates[1]:
        raise RuntimeError("App/test signers do not match")
    if run(serial + ["shell", "getprop", "ro.kernel.qemu"]).strip() != "1":
        raise RuntimeError("Target is not an emulator")
    if run(serial + ["shell", "getprop", "sys.boot_completed"]).strip() != "1":
        raise RuntimeError("Emulator has not booted")
    for package in (PACKAGE, TEST_PACKAGE):
        if run(serial + ["shell", "pm", "path", package], allowed=(0, 1)).strip():
            raise RuntimeError("App/test already installed; use a new emulator, do not uninstall to pass")
    run(serial + ["install", "-t", app])
    run(serial + ["install", "-t", test])
    component = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"

    def instrument(stage, fixture_id, point):
        return serial + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                         "-e", "identityCrashStage", stage, "-e", "identityCrashId", fixture_id,
                         "-e", "identityCrashPoint", point, component]

    def require_pass(output):
        if not re.search(r"OK \(1 test\)", output) or "FAILURES!!!" in output:
            raise RuntimeError("Fixture stage did not pass; preserve the emulator and inspect local evidence")

    results = []
    for point in POINTS:
        fixture_id = str(uuid.uuid4())
        case = root / point
        case.mkdir()
        seeded = run(instrument("seed", fixture_id, point))
        (case / "seed.log").write_text(seeded)
        require_pass(seeded)
        ready_path = "cache/ts3_test_identity_crash_" + fixture_id + ".ready"
        with (case / "terminated-process.log").open("w") as log:
            process = subprocess.Popen(instrument("crash", fixture_id, point), stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 40
            ready = False
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    break
                marker = subprocess.run(serial + ["exec-out", "run-as", PACKAGE, "cat", ready_path],
                                        capture_output=True, text=True, timeout=5)
                if marker.returncode == 0 and marker.stdout.strip() == "IDENTITY_CRASH_BOUNDARY_READY":
                    ready = True
                    break
                time.sleep(0.2)
            if not ready:
                # This is the runner's own known fresh fixture process, never a pre-existing app.
                run(serial + ["shell", "am", "force-stop", PACKAGE])
                process.wait(timeout=10)
                raise RuntimeError("Durable crash boundary not reached; fixture evidence preserved")
            old_pid = run(serial + ["shell", "pidof", PACKAGE]).strip()
            if not old_pid or not re.fullmatch(r"[0-9]+", old_pid):
                raise RuntimeError("Expected one target process")
            run(serial + ["shell", "am", "force-stop", PACKAGE])
            process.wait(timeout=10)
            if run(serial + ["shell", "pidof", PACKAGE], allowed=(0, 1)).strip():
                raise RuntimeError("Target process survived the requested termination")
        verified = run(instrument("verify", fixture_id, point))
        (case / "restarted-verification.log").write_text(verified)
        require_pass(verified)
        results.append({"point": point, "originalProcessPid": int(old_pid), "restartedVerificationPassed": True})
        print("Verified real process termination: " + point, flush=True)
    # Fixture must never have created the production identity store.
    if subprocess.run(serial + ["shell", "run-as", PACKAGE, "test", "-e", "files/datastore/ts3_identity.preferences_pb"],
                      capture_output=True).returncode != 1:
        raise RuntimeError("Unexpected default identity store; preserve the emulator for inspection")
    evidence = {
        "device": args.device, "api": run(serial + ["shell", "getprop", "ro.build.version.sdk"]).strip(),
        "abi": run(serial + ["shell", "getprop", "ro.product.cpu.abi"]).strip(),
        "appSha256": hashlib.sha256(app.read_bytes()).hexdigest(), "testSha256": hashlib.sha256(test.read_bytes()).hexdigest(),
        "certificateSha256": certificates[0], "cases": results, "defaultIdentityCreated": False,
        "realUserDataAccessed": False, "serverConnected": False, "microphoneCaptured": False,
    }
    (root / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print("Identity process-crash evidence: " + str(root), flush=True)


if __name__ == "__main__":
    main()
