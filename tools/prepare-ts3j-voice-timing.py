"""Build a local TS3j timestamp artifact from the locked fork without publishing it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

BASE = "db57d60c989e399626aa16d921390f5033e6cdeb"
ARTIFACT = "ts3j-ts3mobile-1"
BASE_JAR_SHA256 = "23cd4c615cb4cff454fef493e202b6b52879e7371bb80b33bee7fdc498459837"
BASE_POM_SHA256 = "7a8a8245e803d54dd8d52596dc8e4cdee272b506f8f3bd634e30fa9a7b576b62"
DEPENDENCIES = {
    "org.bouncycastle/bcprov-jdk15on": "1.67",
    "org.ini4j/ini4j": "0.5.1",
    "commons-lang/commons-lang": "2.6",
    "dnsjava/dnsjava": "2.1.8",
}
PACKAGE = "src/main/java/com/github/manevolent/ts3j/protocol/"
SOURCES = [PACKAGE + part for part in (
    "NetworkPacket.java", "packet/PacketBody.java",
    "socket/client/AbstractTeamspeakClientSocket.java", "socket/client/LocalTeamspeakClientSocket.java",
)]


def cached(cache, directory, name):
    matches = list((cache / directory).glob("*/" + name))
    if len(matches) != 1:
        raise RuntimeError("Resolve the ordinary Gradle dependencies first: " + name)
    return matches[0]


def sha(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def write_overlay(base_jar, classes, target):
    replacements = {path.relative_to(classes).as_posix(): path for path in classes.rglob("*.class")}
    with zipfile.ZipFile(base_jar) as original, zipfile.ZipFile(target, "w") as merged:
        for entry in original.infolist():
            if entry.filename not in replacements:
                merged.writestr(entry, original.read(entry))
        for name, path in sorted(replacements.items()):
            merged.writestr(zipfile.ZipInfo(name), path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)


def main():
    repository = Path(__file__).resolve().parent.parent
    patch = repository / "tools/ts3j-patches/0001-voice-udp-timing.patch"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fork", type=Path, required=True, help="Local checkout containing the pinned source commit")
    parser.add_argument("--out", type=Path, default=repository / "build/ts3j-voice-timing-maven")
    args = parser.parse_args()
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    module = "com.github.monet4070/ts3j/ts3mobile-1"
    jar = cached(cache, module, ARTIFACT + ".jar")
    pom = cached(cache, module, ARTIFACT + ".pom")
    if (sha(jar), sha(pom)) != (BASE_JAR_SHA256, BASE_POM_SHA256):
        raise RuntimeError("Cached TS3j artifacts do not match the pinned baseline")
    dependencies = [cached(cache, item + "/" + version, item.split("/")[1] + "-" + version + ".jar")
                    for item, version in DEPENDENCIES.items()]
    java_root = os.environ.get("JAVA_HOME")
    java = str(Path(java_root) / "bin/java") if java_root else "java"
    javac = str(Path(java_root) / "bin/javac") if java_root else "javac"
    classpath = os.pathsep.join(str(path) for path in [jar, *dependencies])
    with tempfile.TemporaryDirectory(prefix="ts3j-voice-timing-") as temporary:
        root = Path(temporary)
        for name in SOURCES:
            destination = root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(subprocess.check_output(["git", "-C", args.fork.resolve(), "show", BASE + ":" + name]))
        subprocess.run(["git", "apply", "--whitespace=error", patch], cwd=root, check=True)
        compiled = root / "classes"
        compiled.mkdir()
        subprocess.run([javac, "--release", "8", "-cp", classpath, "-d", compiled, *(root / name for name in SOURCES)], check=True)
        probe = patch.with_name("VoiceUdpBoundaryProbe.java")
        subprocess.run([javac, "--release", "8", "-cp", str(compiled) + os.pathsep + classpath,
                        "-d", root, probe], check=True)
        subprocess.run([java, "-cp", os.pathsep.join([str(root), str(compiled), classpath]), "VoiceUdpBoundaryProbe"], check=True)
        local_module = args.out.resolve() / "com/github/monet4070/ts3j/ts3mobile-1"
        local_module.mkdir(parents=True, exist_ok=True)
        target = local_module / (ARTIFACT + ".jar")
        write_overlay(jar, compiled, target)
        shutil.copyfile(pom, local_module / (ARTIFACT + ".pom"))
    evidence = {"sourceCommit": BASE, "patchSha256": sha(patch), "jarSha256": sha(target)}
    (args.out / "voice-timing-build.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print("Use only for local QA: -Pts3jVoiceTimingRepository=" + str(args.out.resolve()))


if __name__ == "__main__":
    main()
