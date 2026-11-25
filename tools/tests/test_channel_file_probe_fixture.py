import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


TOOL = Path(__file__).resolve().parents[1] / "prepare-channel-file-fixture.py"


class ChannelFileProbeFixtureTest(unittest.TestCase):
    def test_builds_expected_readable_and_denied_fixtures_without_replacing_existing_data(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "fixture"
            command = [sys.executable, str(TOOL), "--out", str(output)]
            subprocess.run(command, check=True, capture_output=True)
            readable = output / "readable" / "ts3mobile-fixture"
            self.assertEqual((readable / "probe.txt").read_bytes(), b"TS3 Mobile file acceptance\n")
            self.assertEqual((output / "denied" / "probe.txt").read_bytes(), (readable / "probe.txt").read_bytes())
            self.assertEqual(list((readable / "empty").iterdir()), [])
            voice = readable / "voice.bin"
            self.assertEqual(voice.stat().st_size, 8_388_608)
            self.assertEqual(hashlib.sha256(voice.read_bytes()).hexdigest(),
                             "7d212b9c884f5c77896de960ae17cc341cda43b14d6a971f34ca29ebd4badf7f")
            (readable / "probe.txt").write_bytes(b"untouched")
            repeated = subprocess.run(command, check=False, capture_output=True)
            self.assertEqual(repeated.returncode, 2)
            self.assertEqual((readable / "probe.txt").read_bytes(), b"untouched")


if __name__ == "__main__":
    unittest.main()
