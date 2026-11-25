import hashlib
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "build-ts3j-from-source.py"
BUILD = runpy.run_path(str(SCRIPT))


class Ts3jSourceInputTest(unittest.TestCase):
    def test_handshake_candidate_does_not_overwrite_an_existing_repository(self):
        with tempfile.TemporaryDirectory() as temporary:
            baseline = Path(temporary) / "ts3j-source-build.json"
            baseline.write_text("existing repository\n")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--fork", temporary, "--out", temporary, "--offline", "--handshake-width-fix"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(2, result.returncode)
            self.assertIn("new or empty dedicated Maven output directory", result.stderr)
            self.assertEqual("existing repository\n", baseline.read_text())

    def test_offline_checks_bytes_not_cache_filename(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            jar = cache / "fixture.group/fixture/1/metadata/fixture-1.jar"
            jar.parent.mkdir(parents=True)
            payload = b"synthetic jar bytes"
            digest = hashlib.sha256(payload).hexdigest()
            jar.write_bytes(payload)
            resolve = BUILD["dependency"]
            with patch.dict(resolve.__globals__, {"urlopen": lambda *args, **kwargs: self.fail("No network in offline mode")}):
                self.assertEqual(jar, resolve(("fixture.group", "fixture", "1"), digest, cache, cache, True))
                jar.write_bytes(b"replaced content")
                with self.assertRaisesRegex(RuntimeError, "No verified cached dependency"):
                    resolve(("fixture.group", "fixture", "1"), digest, cache, cache, True)
