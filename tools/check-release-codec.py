import argparse
import os
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile

from audio_build_config import android_config, build_tools, production_opus_version, require_file


def main():
    repository = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--apk", type=Path, default=repository / "app/build/outputs/apk/release/app-release-unsigned.apk")
    arguments = parser.parse_args()
    if not arguments.sdk:
        parser.error("Set ANDROID_HOME or --sdk")
    sdk = Path(arguments.sdk).resolve()
    config = android_config(repository)
    expected_version = production_opus_version(repository)
    executable = ".exe" if os.name == "nt" else ""
    adb = require_file(sdk / ("platform-tools/adb" + executable))
    d8 = require_file(build_tools(sdk) / ("d8.bat" if os.name == "nt" else "d8"))
    java_root = os.environ.get("JAVA_HOME")
    javac = str(Path(java_root) / ("bin/javac" + executable)) if java_root else shutil.which("javac")
    if not javac:
        parser.error("Set JAVA_HOME or provide javac on PATH")
    serial = [str(adb), "-s", arguments.device]
    abi = subprocess.check_output(serial + ["shell", "getprop", "ro.product.cpu.abi"], text=True).strip()
    if abi not in {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}:
        raise RuntimeError("Unsupported device ABI")
    run_id = uuid.uuid4().hex
    root = repository / "build/release-codec-smoke" / run_id
    classes = root / "classes"
    libraries = root / "lib"
    classes.mkdir(parents=True)
    libraries.mkdir()
    with zipfile.ZipFile(arguments.apk) as apk:
        entries = [entry for entry in apk.infolist() if entry.filename.startswith("lib/" + abi + "/") and entry.filename.endswith(".so")]
        for entry in entries:
            (libraries / Path(entry.filename).name).write_bytes(apk.read(entry))
    codec = libraries / "libts3opus_jni.so"
    if not codec.exists():
        raise RuntimeError("Release APK does not contain the Opus JNI library")
    android_jar = require_file(sdk / "platforms" / ("android-" + config["compile_sdk"]) / "android.jar")
    source = repository / "app/src/releaseSmokeTest/java/io/github/ts3mobile/app/release/OpusRoundTripProbe.java"
    subprocess.run([javac, "--release", "8", "-classpath", str(android_jar), "-d", str(classes), str(source)], check=True)
    probe = root / "probe.jar"
    probe_class = classes / "io/github/ts3mobile/app/release/OpusRoundTripProbe.class"
    subprocess.run([str(d8), "--min-api", config["min_sdk"], "--lib", str(android_jar), "--output", str(probe), str(probe_class)], check=True)
    remote = "/data/local/tmp/ts3-release-codec-" + run_id
    subprocess.run(serial + ["shell", "mkdir", remote], check=True)
    try:
        subprocess.run(serial + ["push", str(probe), remote + "/probe.jar"], check=True)
        subprocess.run(serial + ["push", str(arguments.apk), remote + "/app.apk"], check=True)
        subprocess.run(serial + ["push", str(libraries), remote + "/lib"], check=True)
        subprocess.run(serial + ["shell", "chmod", "444", remote + "/app.apk", remote + "/probe.jar"], check=True)
        command = "CLASSPATH=" + remote + "/probe.jar app_process /system/bin io.github.ts3mobile.app.release.OpusRoundTripProbe "
        command += remote + "/app.apk " + remote + "/lib"
        result = subprocess.run(serial + ["shell", command], text=True, capture_output=True)
        output = result.stdout + result.stderr
        (root / "result.log").write_text(output)
        print(output, end="")
        if result.returncode != 0 or "RELEASE_OPUS_ROUNDTRIP_OK" not in output:
            raise RuntimeError("Actual minified APK codec regression failed")
        if ("RELEASE_OPUS_VERSION=libopus " + expected_version) not in output.splitlines():
            raise RuntimeError("Runtime Opus version differs from production CMake configuration")
        print("Packaged codec smoke passed on " + abi + "; evidence: " + str(root))
    finally:
        subprocess.run(serial + ["shell", "rm", "-rf", remote], check=True)


if __name__ == "__main__":
    main()
