"""Build synthetic content for the opt-in, loopback-only channel file probe."""

import argparse
from pathlib import Path


SMALL = b"TS3 Mobile file acceptance\n"
VOICE_MIB = 8


def build(out: Path) -> None:
    out.mkdir()
    readable = out / "readable" / "ts3mobile-fixture"
    (readable / "empty").mkdir(parents=True)
    (readable / "probe.txt").write_bytes(SMALL)
    block = bytes(range(256)) * 4_096
    with (readable / "voice.bin").open("xb") as output:
        for _ in range(VOICE_MIB):
            output.write(block)
    hidden = out / "denied"
    hidden.mkdir()
    (hidden / "probe.txt").write_bytes(SMALL)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="New directory under an existing parent")
    args = parser.parse_args()
    try:
        build(args.out)
    except OSError as error:
        parser.exit(2, f"Fixture could not be generated: {error.strerror}\n")
    print("Created test content in readable/ and denied/. No server was started or modified.")


if __name__ == "__main__":
    main()
