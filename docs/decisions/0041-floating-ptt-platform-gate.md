# ADR 0041: Separate platform microphone qualification from floating PTT

Status: accepted for standard-build connected floating PTT on November 24, 2026. Local recorder qualification remains Debug-only.

The user-visible Debug lab requests overlay and recording permissions separately. An explicit action in a resumed Activity starts a private microphone-type foreground service before leaving the app. Idle readiness means a foreground type and a truthful notification, not an `AudioRecord` or live transmission. Each press has a separate token, a release fence and a hard deadline. Lock, overlay removal, expiry and permission loss disarm. No voice is encoded, stored or sent. The probe refuses arming alongside a TeamSpeak connection, so it cannot interfere with listening or capture.

`SYSTEM_ALERT_WINDOW` and the private connected settings activity are in the main manifest. The exported launcher alias and local recorder service stay in `src/debug`. The separate development-signed `io.github.ts3mobile.app.mediaqa` build uses the ordinary connected overlay and does not add the lab. The emulator runner grants permissions only to its disposable installation; those grants are not part of production app behavior.

Android limits background foreground-service starts and while-in-use capabilities independently. In particular, the Android 15 visible-overlay rule is not general authorization for background recording. See the official [foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) and [Android 15 changes](https://developer.android.com/about/versions/15/behavior-changes-15).

The connected settings and recall action prepare the existing connected
service while the private Activity is visible. It holds the microphone foreground type
without holding an input device. The service keeps a connection/channel lease
and an independent press token. The existing microphone controller and sender
are reused; a frame is checked against its token again after dequeue. This does
not make the main Activity appear visible, change playback protection, or start a
new microphone foreground service for each background press. Disarm restores
playback-only eligibility once capture resources are released.

Synthetic sender handoff, platform-only qualification, datagram transmission and
audible reception are distinct evidence. Inclusion in a standard build is not publication,
live audio certification or an OEM compatibility guarantee.

The connected overlay owns a temporary microphone mode inside its existing
connection/channel lease. Selecting continuous or volume gate explicitly revokes
the previous token and starts the selected mode, without a second microphone
toggle or changing the main application's mode. Hold opens a 60-second token;
continuous and volume gate use a five-minute token. Arming starts silently in
Off or Hold; an existing main-app recording must be stopped first. Keep mode and
deadline ownership in the lease; the larger microphone controller only reconciles
the current token through the existing capture pipeline. The card delegates the
mode controls to a separate view; channel summaries and drag geometry keep their
existing ownership. The status tile is noninteractive except in Hold mode. No audio
subsystem split or public configuration migration is needed.

The overlay card owns layout and presents microphone action and state through
a separate view. Its speaker preview and expanded speaker area do not change
capture tokens, permissions, session lifetime, or public preferences.

The connected notification offers a direct activity action when the channel
is ready and no microphone capture or floating lease is active. The destination
is a private activity, not the exported lab alias or a service trampoline. Only
an eligible, focused settings activity prepares the same lease and returns to the previous
task; when permission is missing it remains on the setup page. The action never
requests access, starts a capture, or enables sending by itself.

The device-local auto-show option defaults off. On an explicit user departure,
the main screen or floating PTT settings prepares the same silent lease while focused; the host
shows it only once the owner stops. App-initiated activity launches do not count as
user departure. While MainActivity is visible, a prepared lease cannot accept presses
or mode changes. Any app activity resuming closes the overlay; an already busy input
is left alone. Neither the option nor dock position is included in backup or transfer.
The existing large MainActivity and MicrophoneController only gain lifecycle wiring
and an arm eligibility check, respectively. Keep capture policy in the controller and
leases in `FloatingPttPresses`; extract a lifecycle coordinator if additional entry
paths or session policies are needed rather than growing either file further.
