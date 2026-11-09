# ADR 0020: Automatic Bluetooth selection and protected listening

- Status: accepted for implementation; physical Bluetooth acceptance remains open
- Date: 2026-11-04

## Problem

The router previously started on the system route, did not choose a newly connected
Bluetooth headset, and cleared device selection after removal. Playback rebuilt its
output after an actual route change and accepted fresh voice on the fallback route.
This could send incoming channel voice to the phone speaker without user intent.

## Decision

Select an available Android Bluetooth **communication** route at session start and
when a new Bluetooth communication device appears. Keep an existing Bluetooth
selection when another headset appears. An explicit speaker/system selection stays
in effect through unrelated device-list callbacks until a new Bluetooth device
connects. A2DP-only media endpoints are not added to the duplex communication list.

Latch listening mute before clearing a removed selected route. Switch refusal or
exception, a five-second Bluetooth confirmation timeout, and loss of an established
Bluetooth communication route during playback also latch mute. Android 12+ communication-device
callbacks and older SCO/noisy broadcasts supply early loss signals. Communication
callbacks preserve the reported transition so a brief loss during playback followed
by a fast reconnect still latches mute; the router separately classifies idle suspension.
The playback
output checks the actual `AudioTrack` route before writes and on route callbacks;
Bluetooth-to-speaker/earpiece/other fallback silences the output and requests the
same safety mute. The guard survives output recreation between talkspurts, including
Bluetooth reached through the system route.

### Idle SCO suspension correction (2026-11-08)

Xiaomi 14 system history and a diagnostic build reproduced SCO being released
after about 60 seconds while the selected headset remained available and playback
was idle. Treating every communication-route release as a disconnect latched mute
on a normally connected headset. This behavior was already present in v1.0.0.

An idle release with the selected Bluetooth device still available now retires old
PCM and arms a fresh Bluetooth-only attempt without latching user mute. It does
not keep SCO alive or retry while idle. Fresh playback first obtains audio focus,
then requests the same Bluetooth route. A new attempt revokes the retired track's
confirmation and cannot write to the speaker/earpiece while Bluetooth settles.
Removal, noisy broadcasts, setup timeout, refused recovery and loss during active
playback retain the manual-acknowledgement protection. Manual route selection
cancels the pending recovery. Public constructors remain available; the additional
output-preparation callback is additive and does not change persisted formats.

See [the mobile RX audit](../testing/MOBILE_RX_AUDIT_2026-11-08_ZH.md) for evidence,
regression tests and physical verification limits.

A route transition invalidates queued voice and silences the previous output before
changing the platform route. A pending Bluetooth output starts without audible PCM;
voice received while waiting is discarded instead of exhausting write-retry limits
or replaying after the headset becomes ready. An active output whose route becomes
unknown is retired with its queued audio. Playback can resume with fresh voice only.

Reconnecting chooses Bluetooth again but does not clear safety mute. Only the
existing manual listening control acknowledges it; if Bluetooth remains selected,
unmuting retries communication-device selection. A denied retry stays muted.
Microphone mode, recording permissions, protocol and persisted preferences do not
change. The service publishes listening mute through its existing UI/presence state.

## Maintainability and compatibility

The original `AudioDeviceRouter` was 317 lines and mixed Android operations with
selection decisions. Extract Android calls into `AudioRoutePlatform`, classification
into `AudioRouteDevices`, and framework event monitoring into `BluetoothRouteMonitor`.
Keep orchestration in the router and use a fake platform in JVM regression tests.
`BluetoothPlaybackGuard` contains the small independent actual-output policy.
The existing service is already over 300 lines; keep its routing integration small
and leave its unrelated lifecycle responsibilities for a separate migration. The
player remains approximately 300 lines after extracting the guard; its worker,
focus and generation ownership stay together to avoid a second synchronization
boundary. Further growth should move routing integration behind a player-owned
component while preserving the current worker/generation contract.

Existing router/player constructors and device-selection methods remain available.
Routing state gains defaulted safety/switching fields and one error kind; consumers
with exhaustive error matching add a localized route-loss case. No configuration or
storage migration is required. This intentional user-visible change supersedes the
old unmuted-removal fallback in earlier acceptance/release documents.

## Verification and limits

See [the test record](../testing/BLUETOOTH_ROUTING_2026-11-04.md) for executed checks.
JVM fixtures cover automatic selection, manual overrides, removal ordering, switch
failure, reconnect mute persistence, silent fallback, route readiness and fresh-frame
recovery. Emulator playback/lifecycle checks cannot establish physical Bluetooth
behavior or sound quality.

Android can reroute already-buffered PCM before delivering a callback, and hardware
may add its own delay. Actual-route checks, immediate track volume zero and buffer
retirement reduce this window; Java-level tests cannot prove zero audible samples
on every vendor HAL. Physical acceptance must cover connection before/during a
session, headset power-off/out-of-range, Bluetooth toggle, SCO disruption by another
communication app, screen-off operation, reconnect and deliberate manual recovery
on Android 8–11 and Android 12+ (including BLE where available).
