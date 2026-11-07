import subprocess
import sys
import tempfile
from pathlib import Path
import unittest


class UpgradeDeviceSafetyTest(unittest.TestCase):
    def reject_without_android_tools(self, serial):
        script = Path(__file__).resolve().parents[1] / "check-upgrade-compat.py"
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, str(script), "--device", serial, "--sdk", temporary,
                 "--baseline-apk", str(Path(temporary) / "missing.apk")],
                capture_output=True, text=True,
            )
        self.assertEqual(2, result.returncode)
        self.assertIn("never a physical device", result.stderr)
        self.assertNotIn("Required Android tool/file is missing", result.stderr)

    def test_physical_serial_is_rejected_before_accessing_sdk_or_apks(self):
        self.reject_without_android_tools("synthetic-usb-device")

    def test_malformed_emulator_serial_cannot_bypass_the_device_guard(self):
        self.reject_without_android_tools("emulator-not-a-port")


if __name__ == "__main__":
    unittest.main()
