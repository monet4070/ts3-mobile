import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

from audio_build_config import android_config, ndk_host_tools, require_file, configure_meson


ABI_TARGETS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64", "aarch64"),
    "armeabi-v7a": ("armv7a-linux-androideabi", "arm", "armv7"),
    "x86": ("i686-linux-android", "x86", "i686"),
    "x86_64": ("x86_64-linux-android", "x86_64", "x86_64"),
}


def fetch(artifact, downloads):
    target = downloads / artifact["filename"]
    if not target.exists():
        temporary = target.with_suffix(target.suffix + ".partial")
        with urllib.request.urlopen(artifact["url"], timeout=60) as response:
            with temporary.open("wb") as destination:
                shutil.copyfileobj(response, destination)
        temporary.replace(target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != artifact["sha256"]:
        raise RuntimeError("Archive checksum mismatch: " + target.name)
    return target


def unpack(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as source:
            for entry in source.namelist():
                if not (destination / entry).resolve().is_relative_to(destination.resolve()):
                    raise RuntimeError("Unsafe zip member")
            source.extractall(destination)
    else:
        with tarfile.open(archive) as source:
            for entry in source.getmembers():
                if entry.issym() or entry.islnk() or not (destination / entry.name).resolve().is_relative_to(destination.resolve()):
                    raise RuntimeError("Unsafe tar member")
            source.extractall(destination)


def write_notices(source, destination):
    destination.mkdir(parents=True, exist_ok=True)
    notices = {
        "webrtc.txt": "webrtc/LICENSE",
        "webrtc-patents.txt": "webrtc/PATENTS",
        "pffft.txt": "webrtc/third_party/pffft/LICENSE",
        "rnnoise-vad.txt": "webrtc/third_party/rnnoise/COPYING",
        "ooura.txt": "webrtc/common_audio/third_party/ooura/LICENSE",
        "spl-sqrt.txt": "webrtc/common_audio/third_party/spl_sqrt_floor/LICENSE",
        "fft.txt": "webrtc/modules/third_party/fft/LICENSE",
        "abseil.txt": "subprojects/abseil-cpp-20230125.1/LICENSE",
    }
    for name, relative in notices.items():
        content = (source / relative).read_bytes()
        try:
            decoded = base64.b64decode(content, validate=True)
        except ValueError:
            decoded = content
        (destination / name).write_bytes(decoded)


def source_hashes(source):
    return {
        str(path.relative_to(source)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(source.rglob("*"))
        if path.is_file() and path.name != ".wraplock" and "__pycache__" not in path.parts
    }


def main():
    repository = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--root", type=Path, default=repository / "build/apm-experiment")
    parser.add_argument("--abi", nargs="+", choices=list(ABI_TARGETS), default=list(ABI_TARGETS))
    parser.add_argument("--jobs", type=int, default=4)
    arguments = parser.parse_args()
    if not arguments.sdk:
        parser.error("Set ANDROID_HOME or --sdk")
    if arguments.jobs < 1:
        parser.error("--jobs must be positive")
    root = arguments.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    sdk = Path(arguments.sdk).resolve()
    config = android_config(repository)
    host_tools = ndk_host_tools(sdk, config["ndk"])
    ninja = require_file(sdk / "cmake" / config["cmake"] / "bin" / ("ninja.exe" if os.name == "nt" else "ninja"))
    for abi in arguments.abi:
        compiler = ABI_TARGETS[abi][0] + config["min_sdk"] + "-clang"
        require_file(host_tools / (compiler + (".cmd" if os.name == "nt" else "")))
    lock_path = repository / "tools/apm-experiment.lock.json"
    lock = json.loads(lock_path.read_text())
    downloads = root / "downloads"
    downloads.mkdir(exist_ok=True)
    archives = {name: fetch(artifact, downloads) for name, artifact in lock.items()}
    sources = root / "source"
    source = sources / "webrtc-audio-processing-1.3"
    lock_hash = hashlib.sha256(lock_path.read_bytes()).hexdigest()
    stamp = root / "source-lock.sha256"
    manifest = root / "source-manifest.json"
    if not stamp.exists() or stamp.read_text().strip() != lock_hash or not manifest.exists():
        if sources.exists():
            shutil.rmtree(sources)
        unpack(archives["apm"], sources)
        unpack(archives["abseil"], source / "subprojects")
        unpack(archives["abseil_meson"], source / "subprojects")
        unpack(archives["meson"], root / "tooling")
        stamp.write_text(lock_hash + "\n")
        manifest.write_text(json.dumps(source_hashes(source), indent=2) + "\n")
    if json.loads(manifest.read_text()) != source_hashes(source):
        raise RuntimeError("Extracted APM sources differ from the verified archive/patch manifest")
    write_notices(source, root / "notices")
    shutil.copyfile(lock_path, root / "source-lock.json")
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(root / "tooling")
    environment["PATH"] = str(ninja.parent) + os.pathsep + environment.get("PATH", "")
    meson = [sys.executable, "-m", "mesonbuild.mesonmain"]
    for abi in arguments.abi:
        target, family, cpu = ABI_TARGETS[abi]
        cross = root / (abi + ".ini")
        cross.write_text(
            "[binaries]\n"
            + "c = " + repr(str(host_tools / (target + config["min_sdk"] + "-clang" + (".cmd" if os.name == "nt" else "")))) + "\n"
            + "cpp = " + repr(str(host_tools / (target + config["min_sdk"] + "-clang++" + (".cmd" if os.name == "nt" else "")))) + "\n"
            + "ar = " + repr(str(host_tools / "llvm-ar")) + "\n"
            + "strip = " + repr(str(host_tools / "llvm-strip")) + "\n"
            + "pkg-config = '/usr/bin/false'\n"
            + "[host_machine]\nsystem = 'android'\n"
            + "cpu_family = " + repr(family) + "\ncpu = " + repr(cpu) + "\nendian = 'little'\n"
            + "[properties]\nneeds_exe_wrapper = true\n"
        )
        build = root / abi
        options = [
                "--buildtype=release", "--default-library=static", "--wrap-mode=nodownload",
                "--force-fallback-for=absl_base,absl_flags,absl_strings,absl_synchronization,absl_bad_optional_access",
                "-Db_staticpic=true", "-Dgnustl=disabled", "-Dneon=no",
        ]
        fingerprint = {
            "source_lock": lock_hash,
            "source_manifest": hashlib.sha256(manifest.read_bytes()).hexdigest(),
            "abi": abi,
            "android": config,
            "cross_file": cross.read_text(),
            "compiler_version": subprocess.check_output([str(host_tools / (target + config["min_sdk"] + "-clang" + (".cmd" if os.name == "nt" else ""))), "--version"], text=True),
            "ninja": str(ninja),
            "options": options,
        }
        def setup(wipe):
            subprocess.run(meson + ["setup"] + (["--wipe"] if wipe else []) + [
                str(build), str(source), "--cross-file", str(cross),
            ] + options, env=environment, check=True)
        configure_meson(build, fingerprint, setup)
        subprocess.run(meson + ["compile", "-C", str(build), "-j", str(arguments.jobs), "webrtc-audio-processing-1"], env=environment, check=True)
        print("Built APM experiment: " + abi, flush=True)


if __name__ == "__main__":
    main()
