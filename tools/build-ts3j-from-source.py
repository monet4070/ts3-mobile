"""Build the pinned TS3j revision into a local Maven repository without JitPack."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
from urllib.request import urlopen
import xml.etree.ElementTree as ET
import zipfile


BASE = "db57d60c989e399626aa16d921390f5033e6cdeb"
GROUP = "com.github.monet4070"
VERSION = "ts3mobile-1"
MAVEN = "https://repo.maven.apache.org/maven2/"
DEPS = {
    ("org.bouncycastle", "bcprov-jdk15on", "1.67"): "fa0041a36f9f20af3c6b8dbf6eb49a969e2c9cc029049d61acc526ba3247b3ef",
    ("commons-lang", "commons-lang", "2.6"): "50f11b09f877c294d56f24463f47d28f929cf5044f648661c0f0cfbae9a2f49c",
    ("dnsjava", "dnsjava", "2.1.8"): "52de1ff2a7556ac2cc4284abd7123bc3d6274210fc4e3b1d9ba90efad5f6a153",
    ("org.ini4j", "ini4j", "0.5.1"): "1c0c86469cd3b928fda138d6bc0a69cdca3969a7a91c5483db84e657dc91336a",
}


def sha(payload):
    return hashlib.sha256(payload).hexdigest()


def dependency(coords, expected, cache, output, offline):
    group, name, version = coords
    filename = f"{name}-{version}.jar"
    for candidate in (cache / group / name / version).glob(f"*/{filename}"):
        if sha(candidate.read_bytes()) == expected:
            return candidate
    if offline:
        raise RuntimeError(f"No verified cached dependency: {name}:{version}")
    with urlopen(MAVEN + f"{group.replace('.', '/')}/{name}/{version}/{filename}", timeout=30) as response:
        payload = response.read(8 * 1024 * 1024 + 1)
    if sha(payload) != expected:
        raise RuntimeError(f"Maven Central checksum mismatch: {name}:{version}")
    target = output / filename
    target.write_bytes(payload)
    return target


def extract_sources(fork, output):
    archive = subprocess.check_output(["git", "-C", fork, "archive", BASE, "src/main/java", "LICENSE", "pom.xml"])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar:
            if not member.isfile():
                continue
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise RuntimeError("Unexpected path in pinned TS3j source archive")
            target = output / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(tar.extractfile(member).read())


def write_artifact(root, classes, output):
    module = output / GROUP.replace(".", "/") / "ts3j" / VERSION
    module.mkdir(parents=True, exist_ok=True)
    artifact = module / f"ts3j-{VERSION}.jar"
    with zipfile.ZipFile(artifact, "w") as jar:
        jar.writestr(zipfile.ZipInfo("META-INF/LICENSE"), (root / "LICENSE").read_bytes())
        for path in sorted(classes.rglob("*.class")):
            name = path.relative_to(classes).as_posix()
            jar.writestr(zipfile.ZipInfo(name), path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    namespace = "http://maven.apache.org/POM/4.0.0"
    ET.register_namespace("", namespace)
    pom = ET.parse(root / "pom.xml")
    pom.find(f"{{{namespace}}}groupId").text = GROUP
    pom.find(f"{{{namespace}}}version").text = VERSION
    pom.write(module / f"ts3j-{VERSION}.pom", encoding="utf-8", xml_declaration=True)
    return artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fork", required=True, type=Path, help="Local Git checkout containing the pinned commit")
    parser.add_argument("--out", required=True, type=Path, help="Dedicated output Maven directory")
    parser.add_argument("--offline", action="store_true", help="Only accept verified Gradle-cached dependencies")
    variant = parser.add_mutually_exclusive_group()
    variant.add_argument("--voice-timing", action="store_true", help="Apply the pinned UDP timing patch and run its loopback probe (QA only)")
    variant.add_argument("--handshake-width-fix", action="store_true", help="Build the isolated unsigned-width fix candidate and check its encoder")
    args = parser.parse_args()
    if args.handshake_width_fix and args.out.exists() and (not args.out.is_dir() or any(args.out.iterdir())):
        parser.error("The handshake fix candidate requires a new or empty dedicated Maven output directory")
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    javac = Path(os.environ["JAVA_HOME"]) / "bin/javac" if "JAVA_HOME" in os.environ else "javac"
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if "JAVA_HOME" in os.environ else "java"
    patch_dir = Path(__file__).resolve().with_name("ts3j-patches")
    patch = patch_dir / "0001-voice-udp-timing.patch"
    width_patch = patch_dir / "0002-handshake-unsigned-width.patch"
    with tempfile.TemporaryDirectory(prefix="ts3j-source-") as temporary:
        root = Path(temporary)
        extract_sources(args.fork.resolve(), root)
        if args.voice_timing:
            subprocess.run(["git", "apply", "--whitespace=error", patch], cwd=root, check=True)
        if args.handshake_width_fix:
            subprocess.run(["git", "apply", "--whitespace=error", width_patch], cwd=root, check=True)
        dependencies = [dependency(coords, expected, cache, root, args.offline) for coords, expected in DEPS.items()]
        classes = root / "classes"
        classes.mkdir()
        sources = sorted((root / "src/main/java").rglob("*.java"))
        subprocess.run(
            [javac, "--release", "8", "-g", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, dependencies)),
             "-d", classes, *sources],
            check=True,
        )
        classpath = os.pathsep.join(map(str, (classes, *dependencies)))
        if args.voice_timing:
            probe = patch_dir / "VoiceUdpBoundaryProbe.java"
            subprocess.run([javac, "--release", "8", "-cp", classpath, "-d", root, probe], check=True)
            subprocess.run([java, "-cp", str(root) + os.pathsep + classpath, "VoiceUdpBoundaryProbe"], check=True)
        if args.handshake_width_fix:
            probe = patch_dir / "UnsignedWidthProbe.java"
            subprocess.run([javac, "--release", "8", "-cp", classpath, "-d", root, probe], check=True)
            probe_class = "com.github.manevolent.ts3j.protocol.packet.handler.client.UnsignedWidthProbe"
            subprocess.run([java, "-cp", str(root) + os.pathsep + classpath, probe_class], check=True)
        artifact = write_artifact(root, classes, args.out.resolve())
    evidence = {"sourceCommit": BASE, "javaSources": len(sources), "jarSha256": sha(artifact.read_bytes())}
    if args.voice_timing:
        evidence["voiceTimingPatchSha256"] = sha(patch.read_bytes())
    if args.handshake_width_fix:
        evidence["handshakeWidthPatchSha256"] = sha(width_patch.read_bytes())
    (args.out / "ts3j-source-build.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(f"Verified inputs and built local TS3j: {artifact}")


if __name__ == "__main__":
    main()
