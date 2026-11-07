import subprocess
import sys
import tempfile
from pathlib import Path
import unittest


class IdentityCrashSafetyTest(unittest.TestCase):
    def reject_before_android_access(self, serial):
        script = Path(__file__).resolve().parents[1] / "check-identity-crash.py"
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, str(script), "--device", serial, "--sdk", temporary,
                 "--app-apk", str(Path(temporary) / "missing.apk")],
                capture_output=True, text=True,
            )
        self.assertEqual(2, result.returncode)
        self.assertIn("never a physical device", result.stderr)
        self.assertNotIn("Required Android tool/file is missing", result.stderr)

    def test_physical_device_is_rejected_before_any_tools_or_apks(self):
        self.reject_before_android_access("synthetic-usb-device")

    def test_malformed_emulator_serial_is_rejected_before_any_tools_or_apks(self):
        self.reject_before_android_access("emulator-not-a-port")


if __name__ == "__main__":
    unittest.main()
