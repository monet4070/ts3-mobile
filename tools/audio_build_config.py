"""Read audio build choices from their production configuration sources."""
import platform
from pathlib import Path
import re
import json


def gradle_value(path, name):
    match = re.search(r'^\s*' + re.escape(name) + r'\s*=\s*(?:"([^"\n]+)"|(\d+))\s*$', path.read_text(), re.MULTILINE)
    if not match:
        raise RuntimeError("Missing literal Gradle setting: " + name + " in " + path.name)
    return match.group(1) or match.group(2)


def android_config(repository):
    audio = repository / "audio-opus/build.gradle.kts"
    app = repository / "app/build.gradle.kts"
    return {
        "compile_sdk": gradle_value(app, "compileSdk"),
        "min_sdk": gradle_value(app, "minSdk"),
        "ndk": gradle_value(audio, "ndkVersion"),
        "cmake": gradle_value(audio, "version"),
    }


def production_opus_version(repository):
    source = repository / "audio-opus/src/main/cpp/opus-source.cmake"
    match = re.search(r'set\(TS3_OPUS_VERSION\s+"([^"\n]+)"\)', source.read_text())
    if not match:
        raise RuntimeError("Missing TS3_OPUS_VERSION in production CMake configuration")
    return match.group(1)


def require_file(path):
    if not path.is_file():
        raise RuntimeError("Required Android tool/file is missing: " + str(path))
    return path


def ndk_host_tools(sdk, version, host=None):
    host = host or platform.system()
    prefix = {"Darwin": "darwin-", "Linux": "linux-", "Windows": "windows-"}.get(host)
    if prefix is None:
        raise RuntimeError("Unsupported NDK host: " + host)
    prebuilt = sdk / "ndk" / version / "toolchains/llvm/prebuilt"
    candidates = sorted(path / "bin" for path in prebuilt.glob(prefix + "*") if (path / "bin").is_dir())
    if len(candidates) != 1:
        raise RuntimeError("Expected one NDK host toolchain for " + host + " under " + str(prebuilt)
                           + "; found " + str(len(candidates)))
    return candidates[0]


def build_tools(sdk):
    candidates = [path for path in (sdk / "build-tools").glob("*")
                  if path.is_dir() and re.fullmatch(r"\d+\.\d+\.\d+", path.name)]
    if not candidates:
        raise RuntimeError("No stable Android build-tools installed under " + str(sdk))
    return max(candidates, key=lambda path: tuple(int(part) for part in path.name.split(".")))


def configure_meson(build, fingerprint, setup):
    stamp = build / "ts3-build-config.json"
    configured = (build / "build.ninja").is_file()
    if configured and stamp.is_file() and json.loads(stamp.read_text()) == fingerprint:
        return False
    setup(configured)
    if not (build / "build.ninja").is_file():
        raise RuntimeError("Meson setup did not create build.ninja")
    stamp.write_text(json.dumps(fingerprint, sort_keys=True, indent=2) + "\n")
    return True
