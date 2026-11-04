import sys
import tempfile
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audio_build_config import android_config, build_tools, ndk_host_tools, production_opus_version, require_file, configure_meson


class AudioBuildConfigTest(unittest.TestCase):
    def test_cached_build_is_wiped_when_toolchain_or_source_changes(self):
        build = self.root / "build"
        calls = []
        def setup(wipe):
            calls.append(wipe)
            build.mkdir(exist_ok=True)
            (build / "build.ninja").write_text("configured")
        first = {"ndk": "27", "source_lock": "one", "min_sdk": "26", "compiler": "compiler-one"}
        self.assertTrue(configure_meson(build, first, setup))
        self.assertFalse(configure_meson(build, first, setup))
        for key in first:
            changed = dict(first, **{key: "changed"})
            self.assertTrue(configure_meson(build, changed, setup))
            self.assertTrue(calls[-1])
            configure_meson(build, first, setup)
        self.assertFalse(calls[0])

    def test_old_build_without_stamp_is_not_trusted_and_failed_setup_does_not_stamp(self):
        build = self.root / "build"
        build.mkdir()
        (build / "build.ninja").write_text("old")
        def setup(wipe):
            self.assertTrue(wipe)
            raise RuntimeError("setup failed")
        with self.assertRaisesRegex(RuntimeError, "setup failed"):
            configure_meson(build, {}, setup)
        self.assertFalse((build / "ts3-build-config.json").exists())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)

    def write(self, relative, text=""):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_reads_nondefault_production_settings(self):
        self.write("app/build.gradle.kts", 'compileSdk = 36\nminSdk = 28\n')
        self.write("audio-opus/build.gradle.kts", 'ndkVersion = "28.1.2"\nversion = "4.0.0"\n')
        self.write("audio-opus/src/main/cpp/opus-source.cmake", 'set(TS3_OPUS_VERSION "9.8.7")\n')
        self.assertEqual({"compile_sdk": "36", "min_sdk": "28", "ndk": "28.1.2", "cmake": "4.0.0"}, android_config(self.root))
        self.assertEqual("9.8.7", production_opus_version(self.root))

    def test_build_tools_use_numeric_stable_version_order(self):
        for version in ["9.0.0", "35.0.0", "36.0.0-rc1"]:
            (self.root / "build-tools" / version).mkdir(parents=True)
        self.assertEqual("35.0.0", build_tools(self.root).name)

    def test_missing_tools_have_actionable_errors(self):
        with self.assertRaisesRegex(RuntimeError, "No stable Android build-tools"):
            build_tools(self.root)
        with self.assertRaisesRegex(RuntimeError, "found 0"):
            ndk_host_tools(self.root, "28.1.2", "Darwin")
        with self.assertRaisesRegex(RuntimeError, "missing"):
            require_file(self.root / "d8")

    def test_missing_configuration_does_not_guess(self):
        self.write("app/build.gradle.kts", 'compileSdk = providers.gradleProperty("sdk").get().toInt()\n')
        with self.assertRaisesRegex(RuntimeError, "Missing literal Gradle setting: compileSdk"):
            android_config(self.root)

    def test_host_selection_ignores_other_platforms_and_rejects_ambiguity(self):
        prebuilt = self.root / "ndk/28.1.2/toolchains/llvm/prebuilt"
        for host in ["darwin-x86_64", "linux-x86_64"]:
            (prebuilt / host / "bin").mkdir(parents=True)
        self.assertEqual("darwin-x86_64", ndk_host_tools(self.root, "28.1.2", "Darwin").parent.name)
        (prebuilt / "darwin-arm64/bin").mkdir(parents=True)
        with self.assertRaisesRegex(RuntimeError, "found 2"):
            ndk_host_tools(self.root, "28.1.2", "Darwin")


if __name__ == "__main__":
    unittest.main()
