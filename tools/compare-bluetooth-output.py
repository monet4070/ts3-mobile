"""Compare two isolated, user-exported TS3 Mobile audio diagnostic sessions offline."""

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

SCHEMA = "ts3-mobile-audio/v1"
MODES = {0: "STANDARD", 1: "LOW_LATENCY", 2: "POWER_SAVING"}
RETIREMENTS = {1: "DRAINED", 2: "ROUTE_UNAVAILABLE", 3: "OUTPUT_FAILURE", 4: "INVALIDATED", 5: "STOPPED"}
COUNTERS = ("OUTPUT_UNDERRUNS", "OUTPUTS_CREATED", "OUTPUT_FAILURES", "OUTPUT_RECOVERIES", "REBUFFERS")
ROUTES = ("BLUETOOTH", "WIRED", "USB")
MAX_EXPORT_BYTES = 2 * 1024 * 1024


def load_export(path):
    with path.open("rb") as stream:
        contents = stream.read(MAX_EXPORT_BYTES + 1)
    if len(contents) > MAX_EXPORT_BYTES:
        raise ValueError("Diagnostic export exceeds the supported size")
    return json.loads(contents)


def summarize(export, session_id=None):
    if export["schema"] == "ts3-mobile-report/v2":
        export = export["audio"]
    if export["schema"] != SCHEMA:
        raise ValueError("Unsupported audio diagnostic schema")
    sessions = export["sessions"]
    if session_id is None and len(sessions) != 1:
        raise ValueError("Select the session ID explicitly when the export does not contain exactly one session")
    selected = [session for session in sessions if session_id is None or session["id"] == session_id]
    if len(selected) != 1:
        raise ValueError("Expected exactly one matching session")
    session = selected[0]
    events = [event for event in export["events"] if event["session"] == session["id"]]
    samples = [sample for sample in export["samples"] if sample["session"] == session["id"]]
    observations = {event["value"] for event in events if event["kind"] == "OUTPUT_PERFORMANCE_MODE"}
    profiles = sorted({event["value"] for event in events if event["kind"] == "RECEIVE_DELAY_PROFILE"})
    retirements = Counter(RETIREMENTS.get(event["value"], "UNKNOWN") for event in events if event["kind"] == "OUTPUT_RETIRED")
    route_samples = [sample for sample in samples if sample["active"] and sample["actualRoute"] != "UNKNOWN"]
    actual_routes = sorted({route if route in ROUTES else "OTHER" for route in (sample["actualRoute"] for sample in route_samples)})
    comparable = [sample for sample in route_samples if sample.get("playback") == "PLAYING"]
    exact_devices = {sample.get("actualOutputDevice", "UNKNOWN") for sample in comparable} - {"UNKNOWN"}
    exact_devices = sorted({device if device in ("BLUETOOTH_A2DP", "BLUETOOTH_SCO", "BLUETOOTH_OTHER", "WIRED", "USB") else "OTHER"
                            for device in exact_devices})
    networks = {sample["networkGeneration"] for sample in comparable}
    counter_keys = export["counterKeys"]
    generation_totals = defaultdict(dict)
    for sample in samples:
        latest = generation_totals[sample["generation"]]
        for counter in COUNTERS:
            if counter in counter_keys:
                value = sample["counters"][counter_keys.index(counter)]
                if type(value) is not int or value < 0:
                    raise ValueError("Invalid cumulative counter")
                latest[counter] = max(value, latest.get(counter, 0))
    return {
        "actualModes": sorted({MODES.get(value, "UNKNOWN") for value in observations}),
        "actualRoutes": actual_routes,
        "actualOutputDevices": exact_devices,
        "receiveProfileCodes": profiles,
        "playingSamplesWithKnownRoute": len(comparable),
        "networkGenerationChanged": len(networks) > 1,
        "observedMarkersMs": sorted(event["timeMs"] - session["startedAtMs"] for event in events if event["kind"] == "USER_MARK"),
        "observedRetirements": dict(retirements),
        "firstWriteToHeadUs": [event["value"] for event in events if event["kind"] == "FIRST_WRITE_TO_HEAD_US"],
        "observedCounterLowerBounds": {
            key: sum(totals.get(key, 0) for totals in generation_totals.values())
            for key in COUNTERS if key in counter_keys and samples
        },
    }


def compare(baseline, standard, expected_route="BLUETOOTH"):
    reasons = []
    if baseline["actualModes"] != ["LOW_LATENCY"] or standard["actualModes"] != ["STANDARD"]:
        reasons.append("EXPECTED_DISTINCT_ACTUAL_OUTPUT_MODES_NOT_OBSERVED")
    if any(run["actualRoutes"] != [expected_route] or not run["playingSamplesWithKnownRoute"] for run in (baseline, standard)):
        reasons.append("EXPECTED_ROUTE_NOT_CONFIRMED")
    if expected_route == "BLUETOOTH" and any(run["actualOutputDevices"] != ["BLUETOOTH_A2DP"] for run in (baseline, standard)):
        reasons.append("A2DP_NOT_CONFIRMED_BY_BOTH_RUNS")
    if len(baseline["receiveProfileCodes"]) != 1 or baseline["receiveProfileCodes"] != standard["receiveProfileCodes"]:
        reasons.append("EXPECTED_ONE_MATCHING_RECEIVE_PROFILE")
    if baseline["networkGenerationChanged"] or standard["networkGenerationChanged"]:
        reasons.append("NETWORK_CHANGED_DURING_COMPARISON")
    return {
        "supportsModeComparison": not reasons,
        "reasons": reasons,
        "baseline": baseline,
        "standardRequest": standard,
        "limitations": [
            "A2DP_CATEGORY_IS_NOT_PHYSICAL_DEVICE_IDENTITY",
            "NETWORK_GENERATION_IS_NOT_NETWORK_EQUIVALENCE",
            "COUNTERS_AND_MARKERS_ARE_BOUNDED_AND_DO_NOT_MEASURE_ACOUSTIC_LATENCY",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("standard_request", type=Path)
    parser.add_argument("--baseline-session", type=int)
    parser.add_argument("--standard-session", type=int)
    args = parser.parse_args()
    try:
        baseline = summarize(load_export(args.baseline), args.baseline_session)
        standard = summarize(load_export(args.standard_request), args.standard_session)
        result = compare(baseline, standard)
    except (OSError, ValueError, TypeError, KeyError, IndexError) as error:
        parser.error("Unable to read or compare the two selected diagnostic sessions (" + type(error).__name__ + ")")
    print(json.dumps(result, indent=2))
    return 0 if result["supportsModeComparison"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
