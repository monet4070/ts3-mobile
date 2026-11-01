import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request


def acquire(root, version, specification):
    archive = root / ("opus-" + version + ".tar.gz")
    if not archive.exists():
        temporary = archive.with_suffix(".partial")
        with urllib.request.urlopen(specification["url"], timeout=60) as source:
            with temporary.open("wb") as destination:
                shutil.copyfileobj(source, destination)
        temporary.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != specification["sha256"]:
        raise RuntimeError("Opus archive checksum mismatch: " + version)
    source = root / ("opus-" + version)
    if source.exists():
        shutil.rmtree(source)
    with tarfile.open(archive) as contents:
        for entry in contents.getmembers():
            if entry.issym() or entry.islnk() or not (root / entry.name).resolve().is_relative_to(root):
                raise RuntimeError("Unsafe Opus archive member")
        contents.extractall(root)
    return source


def main():
    repository = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--cmake", type=Path)
    parser.add_argument("--root", type=Path, default=repository / "build/opus-compat")
    parser.add_argument("--write-fixtures", action="store_true")
    arguments = parser.parse_args()
    cmake = arguments.cmake or (Path(arguments.sdk) / "cmake/3.22.1/bin/cmake" if arguments.sdk else None)
    if cmake is None:
        parser.error("Set --cmake or ANDROID_HOME/--sdk")
    root = arguments.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    lock = json.loads((repository / "tools/opus-compat.lock.json").read_text())
    tools = {}
    fixtures = {}
    for version, specification in lock.items():
        source = acquire(root, version, specification)
        if version == "1.3.1":
            subprocess.run(["patch", "-p1", "-i", str(repository / "audio-opus/src/test/cpp/opus-compat/legacy-build.patch")],
                           cwd=source, check=True)
        build = root / ("host-" + version)
        subprocess.run([
            str(cmake), "-S", str(repository / "audio-opus/src/test/cpp/opus-compat"), "-B", str(build),
            "-DCMAKE_BUILD_TYPE=Release", "-DOPUS_SOURCE_DIR=" + str(source),
        ], check=True)
        subprocess.run([str(cmake), "--build", str(build), "--parallel", "4"], check=True)
        tool = build / "codec_fixture"
        tools[version] = tool
        generated = subprocess.check_output([str(tool), "generate"], text=True)
        fixtures[version] = json.loads(generated)
        (root / ("opus-" + version + ".json")).write_text(json.dumps(fixtures[version], indent=2) + "\n")
    results = []
    for encoded_version, corpus in fixtures.items():
        for decoded_version, tool in tools.items():
            for scenario in corpus["cases"]:
                packets = "\n".join(scenario["packets"]) + "\n"
                observed = json.loads(subprocess.check_output([str(tool), "decode"], input=packets, text=True))
                assert observed["packets"] == len(scenario["packets"])
                assert observed["samples"] == scenario["samples"] * len(scenario["packets"])
                if scenario["channels"] == 2:
                    assert observed["channelMask"] == 2, observed
                if scenario["dtxPackets"]:
                    assert observed["rms"] < 100, observed
                else:
                    if scenario["leftHz"]:
                        assert observed["tone1000"] > 1500, observed
                    if scenario["rightHz"]:
                        assert observed["tone2000"] > 1500, observed
                results.append({"encoder": encoded_version, "decoder": decoded_version, "case": scenario["name"], **observed})
            for malformed in ["ff\n", "0300\n", "033f\n"]:
                invalid = subprocess.run([str(tool), "decode"], input=malformed, text=True, capture_output=True)
                assert invalid.returncode != 0
    (root / "cross-results.json").write_text(json.dumps(results, indent=2) + "\n")
    assets = repository / "audio-opus/src/androidTest/assets/opus"
    if arguments.write_fixtures:
        assets.mkdir(parents=True, exist_ok=True)
        for version in fixtures:
            name = "opus-" + version + ".json"
            shutil.copyfile(root / name, assets / name)
        hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(assets.glob("opus-*.json"))}
        (assets / "sha256.json").write_text(json.dumps(hashes, indent=2) + "\n")
    else:
        for name, expected in json.loads((assets / "sha256.json").read_text()).items():
            assert hashlib.sha256((assets / name).read_bytes()).hexdigest() == expected
    fec_assets = assets / "fec-sha256.json"
    if fec_assets.exists():
        for name, expected in json.loads(fec_assets.read_text()).items():
            assert hashlib.sha256((assets / name).read_bytes()).hexdigest() == expected
        generated_fec = json.loads(subprocess.check_output([str(root / "host-1.6.1/fec_fixture")], text=True))
        for scenario in generated_fec["cases"]:
            assert scenario["redundantPackets"] >= 10 if scenario["fecEnabled"] else scenario["redundantPackets"] == 0
        (root / "fec-generated.json").write_text(json.dumps(generated_fec, indent=2) + "\n")
    print("Opus cross-compatibility passed: " + str(len(results)) + " scenario/version combinations")


if __name__ == "__main__":
    main()
