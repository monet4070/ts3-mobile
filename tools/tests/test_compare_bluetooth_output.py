import runpy
from pathlib import Path
import unittest


TOOL = runpy.run_path(str(Path(__file__).resolve().parents[1] / "compare-bluetooth-output.py"))


def fixture(mode, route="BLUETOOTH", profile=1, device="BLUETOOTH_A2DP"):
    return {
        "schema": "ts3-mobile-audio/v1",
        "sessions": [{"id": 1, "startedAtMs": 1000}],
        "counterKeys": ["OUTPUTS_CREATED", "OUTPUT_UNDERRUNS"],
        "events": [
            {"session": 1, "kind": "OUTPUT_PERFORMANCE_MODE", "value": mode},
            {"session": 1, "kind": "RECEIVE_DELAY_PROFILE", "value": profile},
            {"session": 1, "kind": "USER_MARK", "timeMs": 2100},
            {"session": 1, "kind": "OUTPUT_RETIRED", "value": 3},
            {"session": 1, "kind": "FIRST_WRITE_TO_HEAD_US", "value": 15000},
        ],
        "samples": [
            {"session": 1, "generation": generation, "active": True, "actualRoute": route,
             "actualOutputDevice": device, "playback": "PLAYING", "networkGeneration": 1, "counters": [1, underrun]}
            for generation, underrun in [(1, 2), (1, 3), (2, 4)]
        ],
    }


class BluetoothOutputComparisonTest(unittest.TestCase):
    def test_real_modes_not_build_labels_decide_comparability(self):
        summarize, compare = TOOL["summarize"], TOOL["compare"]
        baseline = summarize(fixture(1))
        self.assertTrue(compare(baseline, summarize(fixture(0)))["supportsModeComparison"])
        self.assertFalse(compare(baseline, baseline)["supportsModeComparison"])
        self.assertFalse(compare(baseline, summarize(fixture(0, route="SPEAKER")))["supportsModeComparison"])
        self.assertFalse(compare(baseline, summarize(fixture(0, profile=2)))["supportsModeComparison"])
        self.assertFalse(compare(baseline, summarize(fixture(0, device="BLUETOOTH_SCO")))["supportsModeComparison"])
        self.assertFalse(compare(baseline, summarize(fixture(0, device="UNKNOWN")))["supportsModeComparison"])

    def test_counts_across_generations_and_relative_marks_without_duplicate_cumulative_samples(self):
        summary = TOOL["summarize"](fixture(1))
        self.assertEqual({"OUTPUT_UNDERRUNS": 7, "OUTPUTS_CREATED": 2}, summary["observedCounterLowerBounds"])
        self.assertEqual([1100], summary["observedMarkersMs"])
        self.assertEqual({"OUTPUT_FAILURE": 1}, summary["observedRetirements"])
        self.assertEqual([15000], summary["firstWriteToHeadUs"])

    def test_accepts_the_same_wrapped_report_exported_by_the_app(self):
        wrapped = {"schema": "ts3-mobile-report/v2", "audio": fixture(1)}
        self.assertEqual(TOOL["summarize"](fixture(1)), TOOL["summarize"](wrapped))

    def test_ambiguous_sessions_require_explicit_selection(self):
        export = fixture(1)
        export["sessions"].append({"id": 2, "startedAtMs": 1100})
        with self.assertRaises(ValueError):
            TOOL["summarize"](export)
        self.assertEqual(["LOW_LATENCY"], TOOL["summarize"](export, 1)["actualModes"])
