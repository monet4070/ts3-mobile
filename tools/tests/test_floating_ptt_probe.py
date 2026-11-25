import runpy
import sys
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-floating-ptt-probe.py"
sys.path.insert(0, str(SCRIPT.parent))
try:
    PROBE = runpy.run_path(str(SCRIPT))
finally:
    sys.path.remove(str(SCRIPT.parent))


class FloatingProbeParsingTest(unittest.TestCase):
    def test_hidden_and_non_probe_windows_are_ignored(self):
        window = """  Window #2 Window{fixture}:
    package=io.github.ts3mobile.app
    mAttrs={ty=APPLICATION_OVERLAY}
    Frames: frame=[640,1100][1080,1200]
    mDrawState=HAS_DRAWN
    isVisible=true"""
        find = PROBE["overlay_frame"]
        self.assertEqual((640, 1100, 1080, 1200), find(window))
        self.assertIsNone(find(window.replace("true", "false")))
        self.assertIsNone(find(window.replace("HAS_DRAWN", "READY_TO_SHOW")))
        self.assertIsNone(find(window.replace("io.github.ts3mobile.app", "io.github.ts3mobile.app.mediaqa")))
        self.assertIsNone(find(window.replace("package=io.github.ts3mobile.app", "package=other")))

    def test_expanded_panel_only_targets_the_header(self):
        control = PROBE["overlay_control"]
        frame = (380, 1000, 1054, 1470)
        self.assertEqual(("548", "1071"), control(frame, 142))
        self.assertEqual(("983", "1071"), control(frame, 142, microphone=True))

    def test_historical_stop_is_not_a_live_capture_and_missing_dump_fails(self):
        head = "RecordActivityMonitor dump time: fixture\n"
        tail = "Events log: recording activity\nrec stop pack:io.github.ts3mobile.app"
        live = PROBE["recording_is_active"]
        self.assertFalse(live(head + tail))
        self.assertTrue(live(head + "pack:io.github.ts3mobile.app\n" + tail))
        with self.assertRaises(RuntimeError):
            live("truncated")
