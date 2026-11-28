# ADR 0039: Mix voice with foreground media

- Status: accepted for implementation; physical-device acceptance pending
- Date: 2026-11-20

## Decision

Use the Android media stream for incoming speech by default and do not request audio focus. In
media mode, connecting, listening and recording do not set communication mode or start SCO.
Use device preferences on the app's own `AudioTrack` and `AudioRecord`. Offer A2DP and supported
LE Audio media outputs rather than SCO-only endpoints. With Bluetooth selected, prefer the
phone microphone; continue using the existing capture source and effects configuration.

The input/output device page lists Android's current selectable media outputs and physical
inputs separately. An explicit LE Audio input uses the app's `AudioRecord` preference;
it does not request communication mode, focus or SCO. The actual route is reported separately
from the preference. Device IDs are temporary, not persisted. Losing an explicit microphone
returns to automatic input without altering output safety. The service watches the list while
bound offline, but only an active connection applies the playback route safety policy. The
settings list names built-in microphones by position when known and numbers any identical
input labels. Labels do not change routing, and raw device addresses are not shown.

Offer a separate, explicit **Bluetooth call (use headset microphone)** output when Android
exposes a classic Bluetooth communication device. Never auto-select it. While selected, Android
routes call playback and the paired microphone together. TS3 waits for SCO playback and does
not transmit from a fallback microphone. Leaving the route, stopping the session, or detecting
output failure releases the call request. The previous media microphone choice is kept, subject
to device availability. Call selection is not saved for future sessions.

Preserve playback privacy. On Android 10+, the speech track disallows playback capture.
A selected Bluetooth route must be verified on the actual output before any speech is written.
Wait up to five seconds on an unconfirmed route with the output silent, then latch listening mute.
Removing the selected device or falling back from a confirmed Bluetooth output also latches mute.
On route changes, the producer revokes the old input while the worker retires the old output;
the worker cannot remove packets admitted for the new route before it processes the change.
Reconnecting never clears it. Existing diagnostic focus fields remain readable; focus counters
stay at zero and playback no longer produces focus events.

## Trade-offs and verification

Incoming voice shares system media volume. Bluetooth headset microphones that require a call
profile are not available in this path. Android still makes the final routing decision and
device AEC performance may differ from communication mode, particularly on speaker. No
preference migration is needed. This replaces the routing and focus policy in [ADR 0020](0020-bluetooth-playback-protection.md).

- Regression tests cover media focus ownership, unchanged communication mode, and Bluetooth
  route approval, timeout, removal and manual acknowledgement.
- On a physical device, start a video or music in another app before connecting. Test idle TS3,
  incoming speech, and push-to-talk. Confirm the foreground media does not pause, duck or switch
  to call-quality audio and that the other TS3 participant hears the phone microphone. Repeat on
  speaker, wired/USB, and a classic Bluetooth or LE Audio headset where available.
- To qualify LE Audio duplex, compare the automatic and explicit headset microphone selections
  on the same phone and headset while another app plays a stereo sample. During overlapping
  capture and playback, confirm both actual devices report LE Audio and the remote listener
  hears the headset microphone. A selectable port is not proof of duplex or acoustic quality.
- Disconnect the headset during speech and while idle. Confirm no TS3 speech comes out of the
  speaker, reconnect keeps TS3 muted, and a fresh utterance plays after manual unmute.
- On a physical classic headset, switch from media to the call option without leaving the
  channel. During speech and PTT, verify SCO input and output and confirm remote pickup from
  the headset. Switch back and verify media output and the previous microphone. Disconnect the
  headset and the session separately to verify call release and listening safety.
