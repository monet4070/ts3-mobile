import subprocess
import sys
import tempfile
import runpy
from pathlib import Path
import unittest


class UpgradeDeviceSafetyTest(unittest.TestCase):
    def reject_without_android_tools(self, serial, *extra):
        script = Path(__file__).resolve().parents[1] / "check-upgrade-compat.py"
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, str(script), "--device", serial, "--sdk", temporary,
                 "--baseline-apk", str(Path(temporary) / "missing.apk"), *extra],
                capture_output=True, text=True,
            )
        self.assertEqual(2, result.returncode)
        self.assertIn("never a physical device", result.stderr)
        self.assertNotIn("Required Android tool/file is missing", result.stderr)

    def test_physical_serial_is_rejected_before_accessing_sdk_or_apks(self):
        self.reject_without_android_tools("synthetic-usb-device")

    def test_malformed_emulator_serial_cannot_bypass_the_device_guard(self):
        self.reject_without_android_tools("emulator-not-a-port")

    def test_tuning_fixture_keeps_physical_device_guard_before_any_apk_access(self):
        self.reject_without_android_tools("synthetic-usb-device", "--include-tuning")

    def test_staged_fixture_keeps_physical_device_guard_before_any_apk_access(self):
        self.reject_without_android_tools("synthetic-usb-device", "--via-apk", "fixture.apk")

    def test_legacy_backup_fixture_refuses_physical_targets_before_any_apk_access(self):
        self.reject_without_android_tools("synthetic-usb-device", "--include-legacy-backup")

    def test_staged_paths_require_ascending_codes_but_legacy_one_step_allows_the_same_code(self):
        tools = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(tools))
        try:
            check = runpy.run_path(str(tools / "check-upgrade-compat.py"))["check_version_path"]
        finally:
            sys.path.remove(str(tools))
        check([16, 17, 18], strict=True)
        check([16, 16])
        with self.assertRaises(RuntimeError):
            check([16, 17, 17], strict=True)
        with self.assertRaises(RuntimeError):
            check([16, 18, 17], strict=True)


if __name__ == "__main__":
    unittest.main()
