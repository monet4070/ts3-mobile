"""Seed real Debug vaults, then verify an in-place Debug or minified APK update on a fresh emulator."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid
import zipfile

from audio_build_config import android_config, build_tools, require_file


def main():
    repository = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--baseline-apk", required=True, type=Path)
    parser.add_argument("--include-tuning", action="store_true", help="Also seed and preserve A2+ microphone preferences")
    parser.add_argument("--candidate-apk", type=Path, default=repository / "app/build/outputs/apk/debug/app-debug.apk")
    arguments = parser.parse_args()
    if not arguments.sdk:
        parser.error("Set ANDROID_HOME or --sdk")
    if not re.fullmatch(r"emulator-[0-9]+", arguments.device):
        parser.error("This synthetic fixture requires a fresh disposable emulator, never a physical device")
    config = android_config(repository)
    sdk = Path(arguments.sdk).resolve()
    tools = build_tools(sdk)
    executable = ".exe" if os.name == "nt" else ""
    adb = require_file(sdk / ("platform-tools/adb" + executable))
    aapt = require_file(tools / ("aapt" + executable))
    d8 = require_file(tools / ("d8.bat" if os.name == "nt" else "d8"))
    apksigner = require_file(tools / ("apksigner.bat" if os.name == "nt" else "apksigner"))
    zipalign = require_file(tools / ("zipalign" + executable))
    android_jar = require_file(sdk / "platforms" / ("android-" + config["compile_sdk"]) / "android.jar")
    java_root = os.environ.get("JAVA_HOME")
    javac = str(Path(java_root) / ("bin/javac" + executable)) if java_root else shutil.which("javac")
    if not javac:
        parser.error("Set JAVA_HOME or provide javac")
    baseline = require_file(arguments.baseline_apk.resolve())
    candidate = require_file(arguments.candidate_apk.resolve())
    package = "io.github.ts3mobile.app"
    probe_package = package + ".upgradefixture"
    serial = [str(adb), "-s", arguments.device]
    root = repository / "build/upgrade-compat" / uuid.uuid4().hex
    classes = root / "classes"
    classes.mkdir(parents=True)

    def run(command, allowed_codes=(0,)):
        result = subprocess.run([str(part) for part in command], capture_output=True, text=True)
        with (root / "operations.log").open("a") as log:
            log.write(result.stdout + result.stderr + "\n")
        if result.returncode not in allowed_codes:
            raise RuntimeError("Upgrade check command failed; inspect local operations.log")
        return result.stdout

    def certificate(apk):
        report = run([apksigner, "verify", "--print-certs", apk])
        matches = re.findall(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)", report)
        if len(matches) != 1:
            raise RuntimeError("Expected one APK signer")
        return matches[0].lower()

    metadata = []
    for apk in (baseline, candidate):
        badging = run([aapt, "dump", "badging", apk])
        match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)'", badging)
        if not match or match[1] != package:
            raise RuntimeError("Both APKs must be the expected application")
        metadata.append({"versionCode": int(match[2]), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                         "debuggable": "application-debuggable" in badging})
    if not metadata[0]["debuggable"]:
        raise RuntimeError("Baseline must be Debug so the isolated fixture can use its real vault interfaces")
    if metadata[1]["versionCode"] < metadata[0]["versionCode"]:
        raise RuntimeError("Candidate versionCode must not require a downgrade")
    expected_certificate = certificate(baseline)
    if certificate(candidate) != expected_certificate:
        raise RuntimeError("Baseline and candidate signing certificates differ")
    if run(serial + ["shell", "getprop", "ro.kernel.qemu"]).strip() != "1":
        raise RuntimeError("Target is not an emulator")
    if run(serial + ["shell", "getprop", "sys.boot_completed"]).strip() != "1":
        raise RuntimeError("Emulator has not completed booting")
    for name in (package, probe_package):
        # Android's pm path returns1 when the requested package is absent.
        if run(serial + ["shell", "pm", "path", name], allowed_codes=(0, 1)).strip():
            raise RuntimeError("Target already contains the app or probe; use a new emulator, do not uninstall to pass")

    source = repository / "app/src/upgradeSmokeTest/java/io/github/ts3mobile/app/upgrade/UpgradeDataProbe.java"
    manifest = repository / "app/src/upgradeSmokeTest/AndroidManifest.xml"
    run([javac, "--release", "8", "-classpath", android_jar, "-d", classes, source])
    dex = root / "probe.jar"
    run([d8, "--min-api", config["min_sdk"], "--lib", android_jar, "--output", dex, *classes.rglob("*.class")])
    unsigned = root / "probe-unsigned.apk"
    run([aapt, "package", "-f", "-M", manifest, "-I", android_jar, "-F", unsigned])
    with zipfile.ZipFile(dex) as archive, zipfile.ZipFile(unsigned, "a") as apk:
        for entry in archive.infolist():
            if entry.filename.endswith(".dex"):
                apk.writestr(entry.filename, archive.read(entry))
    aligned = root / "probe-aligned.apk"
    run([zipalign, "-f", "4", unsigned, aligned])
    probe = root / "upgrade-probe.apk"
    # Android's standard development test key only; never accept/retrieve a production key.
    debug_key = require_file(Path.home() / ".android/debug.keystore")
    run([apksigner, "sign", "--ks", debug_key, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", probe, aligned])
    if certificate(probe) != expected_certificate:
        raise RuntimeError("APK signing does not match the standard development key; no installation performed")

    component = probe_package + "/io.github.ts3mobile.app.upgrade.UpgradeDataProbe"
    run(serial + ["install", "-t", baseline])
    run(serial + ["install", "-t", probe])
    seed_arguments = ["-e", "includeTuning", "true"] if arguments.include_tuning else []
    seeded = run(serial + ["shell", "am", "instrument", "-w", "-e", "stage", "seed", *seed_arguments, component])
    if "UPGRADE_REAL_VAULT_FIXTURE_SEEDED" not in seeded:
        raise RuntimeError("Baseline fixture creation failed; preserve the emulator and inspect local results")
    run(serial + ["install", "-r", "-t", candidate])
    verify_stage = "verify" if metadata[1]["debuggable"] else "verify-raw"
    expected_marker = "UPGRADE_REAL_IDENTITY_FORM_BOOKMARKS_UID_OK" if metadata[1]["debuggable"] else "UPGRADE_REAL_VAULT_FILES_KEYS_UID_OK"
    verified = run(serial + ["shell", "am", "instrument", "-w", "-e", "stage", verify_stage, component])
    if expected_marker not in verified:
        raise RuntimeError("Real vault verification failed after in-place update")
    evidence = {
        "baseline": metadata[0], "candidate": metadata[1],
        "testCertificateSha256": expected_certificate,
        "api": run(serial + ["shell", "getprop", "ro.build.version.sdk"]).strip(),
        "abi": run(serial + ["shell", "getprop", "ro.product.cpu.abi"]).strip(),
        "sameApplicationUid": True, "encryptedFilesAndOriginalKeysPreserved": True,
        "identityMaterialDigestVerifiedThroughVault": metadata[1]["debuggable"],
        "formAndBookmarkValuesVerifiedThroughVault": metadata[1]["debuggable"],
        "realUserDataAccessed": False, "serverConnected": False,
        "microphonePreferenceFilePreserved": arguments.include_tuning,
        "microphonePreferencesReadBack": arguments.include_tuning and metadata[1]["debuggable"],
    }
    (root / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print("Upgrade retained encrypted vault files, original keys and application UID; evidence: " + str(root))


if __name__ == "__main__":
    main()
