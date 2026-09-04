# M10 Stability and Audio Matrix

This checklist covers behavior that cannot be proven by JVM tests alone. Test
reports must omit real server addresses, passwords, TeamSpeak identities,
nicknames, channel names and participant data. Attach only the app's redacted
diagnostics export.

## Automated baseline

Run before every device session:

```powershell
.\gradlew.bat quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

With an authorized device connected:

```powershell
.\gradlew.bat :audio-opus:connectedDebugAndroidTest
```

The device suite includes native Opus/RNNoise checks, audio route selection and
a simulated 60-second continuous Opus encode/decode soak.

## Device tiers

| Tier | Android versions | Commitment |
| --- | --- | --- |
| Primary beta | 10, 12, 14, 15 | Full voice, reconnect, routing and lifecycle matrix |
| Compatibility | 8, 9 | Install/start/connect smoke test when a device is available |

Record device model, Android version, wired/Bluetooth chipset category and app
commit. Do not record device names that contain a person's name.

## Required scenarios

| Scenario | Duration/repetitions | Pass condition |
| --- | --- | --- |
| Speaker duplex | 30 minutes | No crash, latency growth, stuck microphone or audible queue buildup |
| Earpiece duplex | 15 minutes | Correct route, stable volume and no speaker leakage |
| Two simultaneous remote speakers | 10 minutes | Both remain intelligible; mixer does not clip persistently |
| Network interruption | 10 cycles | Reconnect succeeds or reports a terminal reason; no stale callbacks |
| Route hot-plug | 10 changes | Wired/Bluetooth removal falls back to system route without a crash |
| Bluetooth duplex | 15 minutes | Capture and playback use the intended communication route |
| Audio focus interruption | 5 cycles | Playback mutes/recovers and microphone state remains user-controlled |
| Screen off/background | 30 minutes | Foreground service stays alive and notification disconnect works |
| Password channel restore | 5 reconnects | Last channel restores or exposes a visible, actionable error |

## Measurements

- Start/end subjective one-way latency estimate.
- RNNoise P50/P95 timing from the instrumentation log.
- Diagnostics counters before disconnect: attempts, reconnects, failures,
  received/dropped frames, microphone errors and channel-switch failures.
- Battery percentage and thermal warning state for 30-minute scenarios.

M10 is complete only after the primary beta matrix passes on at least two
physical devices from different Android generations and one Bluetooth route.

## Run log

### 2026-08-16 - Xiaomi 23127PN0CC, Android 16 / API 36 (historical session)

This is a redacted record from the prior device session. No TeamSpeak server
connection was made during the current follow-up; the entries below are not a
new pass of the deferred matrix rows.

- Debug APK installed and connected to a real TeamSpeak server without exposing
  connection details in the report.
- Cold launch completed in 691 ms with no application fatal exception.
- Channel roster rendered four members; one public channel switch completed with
  zero channel-switch failures.
- Ten network interruption cycles recovered successfully. Recovery took 7-10
  seconds after network restoration. Diagnostics recorded 11 successful
  connections including the initial connection, 14 retry attempts, 1,701
  received voice frames, zero dropped voice frames and zero microphone errors.
- A 60-second background smoke test kept the same process, foreground service
  and connected session alive.
- A user-shortened screen-off run reached 20 minutes with the same process,
  foreground service and connected notification, with no fatal exception. This
  is useful partial evidence but does not satisfy the required 30-minute row.
  The device reported 49% battery and 30.5 C at termination.
- Microphone off, push-to-talk and continuous modes all transitioned correctly
  on the rebuilt APK. Continuous capture started without a permission error and
  was returned to push-to-talk after the smoke test.
- Five complete earpiece/speaker round trips (10 route changes) completed
  without a crash, and every selection matched both the app state and Android's
  selected communication-device ID.
- Battery remained at 44%; reported temperature changed from 29.7 C to 32.1 C
  during the reconnect run, with no thermal warning observed.
- The rebuilt app cold-launched in 785 ms and reconnected without a fatal
  exception after changing playback to Android's voice-communication strategy.
- The final native device suite passed 8/8 after installing its APK directly to
  work around the OEM split-install restriction. It covered system route
  selection, communication playback attributes, continuous Opus encode/decode,
  RNNoise timing and suppression, five Android focus-arbitration cycles, and
  five silent-player focus interruption/recovery cycles. The microphone policy
  remains independent of playback focus state.
- Channel restore policy tests passed four cases, including retaining a channel
  password when the expected snapshot arrives and refusing to overwrite it with
  a reconnect-time default-channel snapshot. A real password-protected channel
  was not used because no test password/channel was provisioned.
- Bluetooth was enabled but had zero connected audio devices during this run.
  Bluetooth/BLE route classification passed in JVM tests; physical Bluetooth
  duplex and removal fallback remain unexecuted rather than being marked passed.

This run intentionally defers the long-duration rows. The 30-minute duplex and
screen-off runs, physical Bluetooth/hot-plug coverage, password-channel device
run, and second-device matrix remain required before declaring M10 complete.
