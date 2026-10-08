# Background voice and server history acceptance

Date: 2026-10-08. This is a reproducible device checklist, not a claim that the
device scenarios have passed. No Android device was attached for this change.
Use disposable test credentials and omit endpoints and personal data from reports.

## Device scenarios

| Scenario | Procedure | Expected result |
| --- | --- | --- |
| Bright-screen background | Connect, leave microphone off, use another app for 10 minutes | Session remains available; notification disconnect works |
| Silent locked channel | Lock for 30 minutes with no remote speakers; repeat with CPU-awake setting enabled | Silence never triggers liveness failure; compare connection and battery evidence |
| Long session | Background listening for more than six hours on Android 15+ | No dataSync service type or dataSync timeout |
| Wi-Fi/mobile handover | Switch the default network ten times, including a switch where both remain available | Bounded control check preserves a healthy session or reconnects an expired session |
| Network blocked | Restrict this app's background network, then restore it | Wait without spinning; reconnect after usable network returns |
| Offline recovery | Remove all networks for five minutes | No CPU lock during offline wait; retry resumes only with usable network |
| Disconnect during recovery | Disconnect while waiting/backing off or checking liveness, then restore network | No old callback or probe restarts the connection |
| Terminal failure | Simulate kick/ban or authentication failure | Reconnect stops, audio and lock release; fixed failure category is recorded |
| Connect then immediately background | Leave while connecting with continuous mode selected | Connection can finish; stopped capture waits for visible app |
| Background continuous reconnect | Start continuous capture while visible, background, cause a network interruption | Listening reconnects; stopped microphone remains stopped with a visible prompt |
| PTT release | Hold PTT then switch apps or lock | Capture releases; no stuck transmission |
| Notification mute | Start continuous capture, background, tap mute microphone | Capture stops and continuous intent becomes off |
| Process exit | Let Android reclaim/stop the process; open the app again | Journal remains exportable and Android 11+ exit reason is included when available; no hidden reconnect/capture |
| Successful history | Connect successfully, disconnect, kill/reopen app | Latest host/port prefilled; password blank; entry selectable |
| Failure/cancel history | Try an invalid/unreachable endpoint or cancel connection | No new history entry |
| Duplicate/delete history | Reconnect the same endpoint, try a different port, delete an entry and reopen | Most recent first, exact host/port de-duplication, deletion persists, at most ten entries |
| Prefill race | Type address/port immediately after launch while disk load is delayed | Late history cannot replace typed values |
| Privacy | Inspect copied diagnostics and saved history | History contains only endpoints; report has no endpoints, passwords, identities, exception text or audio |

## Doze

Connect through a USB-debugging device using a disposable server. First keep
battery optimization enabled, then repeat with an explicit user-selected
exemption if needed. Compare CPU-awake disabled/enabled separately.

```powershell
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
```

Observe the notification and app connection, restore Doze, then check recovery:

```powershell
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
```

Always restore device power simulation after the test. A wake lock can be
ignored in Doze; a restricted network must result in bounded waiting/recovery,
not a promise of continuous connectivity.

## Evidence to retain

Record commit, Android version, device model, test duration, start/end battery
percentage and the app's reviewed diagnostic export. POWER_STATE code uses
bits 1 = battery exemption, 2 = power saver, 4 = idle, 8 = interactive screen.
Failure code 1 denotes timeout, 2 network, 0 other. Process-exit codes follow
Android ApplicationExitInfo (or -1 for unavailable inspection). Foreground
type codes are Android ServiceInfo flags. Event timestamps establish lock-held
durations without recording user or server details.
