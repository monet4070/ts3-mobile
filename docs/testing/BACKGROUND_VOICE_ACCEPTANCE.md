# Background voice and server history acceptance

Date: 2026-10-08. This is a reproducible device checklist, not a claim that all
device scenarios have passed. Record each device run and its limitations separately.
Use disposable test credentials and omit endpoints and personal data from reports.

## Device scenarios

| Scenario | Procedure | Expected result |
| --- | --- | --- |
| Bright-screen background | Connect, leave microphone off, use another app for 10 minutes | Session remains available; notification disconnect works |
| Silent locked channel | Lock for 30 minutes with no remote speakers; repeat with CPU-awake setting enabled | Silence never triggers liveness failure; compare connection and battery evidence |
| Long session | Background listening for more than six hours on Android 15+ | No dataSync service type or dataSync timeout |
| Wi-Fi/mobile handover | Switch the default network ten times, including a switch where both remain available | Bounded control check preserves a healthy session or reconnects an expired session |
| Silent transport loss | With unchanged default network, drop UDP in both directions for at least 35 seconds, then restore it | ts3j watchdog closure produces one TRANSPORT_DISCONNECTED event and retryable recovery; the UI does not remain CONNECTED |
| Recovery power budget | Keep a connected, CPU-awake session's transport unreachable for three minutes; include failed retries | Within 120 seconds of entering recovery the CPU lock releases and stays released; transient ERROR and settings changes cannot restart the budget; restoring connectivity still allows recovery |
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

TRANSPORT_DISCONNECTED means the local transport closed without a protocol
failure callback. It does not identify the root cause; its failure category is
0 (other), even when a controlled test demonstrates a watchdog timeout. Polling
adds at most one second to detection after the pinned ts3j 30-second watchdog.
Keep a reachable, quiet session for at least 60 seconds to check for false positives.
Test-only UDP relays verify timeout/recovery, but cannot establish that an OEM
allows the app UID's network access in the background. Use direct connections
for background and Doze tests, and always restore device settings.

The delayed server-disconnect race is covered by deterministic protocol and
coordinator regression tests: transport polling defers to pending terminal
callbacks, so no silent-loss event or retry is fabricated for kick/ban. During
the recovery budget, a transient ERROR may release and reacquire the lock;
after budget expiry, failed retries must not reacquire it.
