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

from apk_signing import apk_certificate_sha256
from audio_build_config import android_config, build_tools, require_file


def check_version_path(versions, strict=False):
    if any(later < earlier or (strict and later == earlier) for earlier, later in zip(versions, versions[1:])):
        raise RuntimeError("Upgrade versionCode must increase at every requested staged step")


def main():
    repository = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--baseline-apk", required=True, type=Path)
    parser.add_argument("--include-tuning", action="store_true", help="Also seed and preserve A2+ microphone preferences")
    parser.add_argument("--include-legacy-backup", action="store_true", help="Generate a synthetic backup in baseline and preflight it in each Debug update")
    parser.add_argument("--candidate-apk", type=Path, default=repository / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--via-apk", type=Path, action="append", default=[], help="Verify each intermediate APK, in ascending versionCode order")
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
    apk_path = [arguments.baseline_apk, *arguments.via_apk, arguments.candidate_apk]
    artifacts = [require_file(apk.resolve()) for apk in apk_path]
    baseline = artifacts[0]
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
        return apk_certificate_sha256(report)

    metadata = []
    for apk in artifacts:
        badging = run([aapt, "dump", "badging", apk])
        match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)'", badging)
        if not match or match[1] != package:
            raise RuntimeError("All APKs must be the expected application")
        metadata.append({"versionCode": int(match[2]), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                         "debuggable": "application-debuggable" in badging})
    if not metadata[0]["debuggable"]:
        raise RuntimeError("Baseline must be Debug so the isolated fixture can use its real vault interfaces")
    if arguments.include_legacy_backup and not all(apk["debuggable"] for apk in metadata):
        raise RuntimeError("Legacy backup preflight reflects app code: use Debug APKs for every step")
    check_version_path([apk["versionCode"] for apk in metadata], strict=bool(arguments.via_apk))
    expected_certificate = certificate(baseline)
    if any(certificate(apk) != expected_certificate for apk in artifacts[1:]):
        raise RuntimeError("APK signing certificates differ along the upgrade path")
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
    run([javac, "--release", "8", "-classpath", android_jar, "-d", classes, source, source.with_name("LegacyBackupFixture.java")])
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
    legacy_arguments = ["-e", "includeLegacyBackup", "true"] if arguments.include_legacy_backup else []
    seeded = run(serial + ["shell", "am", "instrument", "-w", "-e", "stage", "seed", *seed_arguments, *legacy_arguments, component])
    if "UPGRADE_REAL_VAULT_FIXTURE_SEEDED" not in seeded:
        raise RuntimeError("Baseline fixture creation failed; preserve the emulator and inspect local results")
    if arguments.include_legacy_backup and "UPGRADE_LEGACY_BACKUP_WRITTEN" not in seeded:
        raise RuntimeError("Baseline did not write the synthetic legacy backup")
    steps = []
    for candidate, details in zip(artifacts[1:], metadata[1:]):
        run(serial + ["install", "-r", "-t", candidate])
        verify_stage = "verify" if details["debuggable"] else "verify-raw"
        expected_marker = "UPGRADE_REAL_IDENTITY_FORM_BOOKMARKS_UID_OK" if details["debuggable"] else "UPGRADE_REAL_VAULT_FILES_KEYS_UID_OK"
        verified = run(serial + ["shell", "am", "instrument", "-w", "-e", "stage", verify_stage, *legacy_arguments, component])
        if expected_marker not in verified:
            raise RuntimeError("Real vault verification failed at versionCode " + str(details["versionCode"]))
        if arguments.include_legacy_backup and "UPGRADE_LEGACY_BACKUP_PREFLIGHT_OK" not in verified:
            raise RuntimeError("Legacy backup verification failed at versionCode " + str(details["versionCode"]))
        steps.append({"apk": details, "verified": verify_stage, "legacyBackupPreflightVerified": arguments.include_legacy_backup})
    final_debug = metadata[-1]["debuggable"]
    evidence = {
        "baseline": metadata[0], "candidate": metadata[-1], "steps": steps,
        "testCertificateSha256": expected_certificate,
        "api": run(serial + ["shell", "getprop", "ro.build.version.sdk"]).strip(),
        "abi": run(serial + ["shell", "getprop", "ro.product.cpu.abi"]).strip(),
        "sameApplicationUid": True, "encryptedFilesAndOriginalKeysPreserved": True,
        "identityMaterialDigestVerifiedThroughVault": final_debug,
        "formAndBookmarkValuesVerifiedThroughVault": final_debug,
        "realUserDataAccessed": False, "serverConnected": False,
        "microphonePreferenceFilePreserved": arguments.include_tuning,
        "microphonePreferencesReadBack": arguments.include_tuning and final_debug,
    }
    (root / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print("Upgrade retained encrypted vault files, original keys and application UID; evidence: " + str(root))


if __name__ == "__main__":
    main()
