# 0037: Confirmed invites, local microphone trial, and notification listening controls

- Date: 2026-11-20
- Status: Implemented on the development branch; physical-device acceptance pending

## Decision

- Parse opened, pasted and generated `ts3server` links with one bounded parser.
  Accept host, one explicit port (authority or `port`, default 9987), and a
  positive `cid`. Reject ambiguous or malformed inputs. Render only the target
  and the names of unsupported fields, never their values. Sharing requires
  an active connection and uses only its endpoint and optional channel ID.
- The activity owns link delivery; `InviteEntry` owns a single-use preview in
  view-model memory. A link does not connect, prefill connection memory, or
  disconnect an existing session. A confirmed invite is still checked against
  the live session at service delivery. The standard `ServerConfig` and channel
  join path are unchanged; a failed channel join leaves the server connection
  usable. Invite credentials are entered anew; invite connections also skip
  recent-server history, so they cannot indirectly prefill the remembered form.
- The bound service owns `LocalMicrophoneTrial`, separate from the session's capture
  controller. Microphone settings opens it offline or in an established session.
  Starting still requires a visible activity, permission and released voice input;
  an established session must also have voice capture off. The trial uses the
  preferred input and current gain, denoising, echo and gate settings
  and publishes only input type and levels for up to 30 seconds. It does not open
  an Opus encoder or attach to the network voice source. Closing or pausing the
  UI, changing input/route, losing or replacing the session, or losing capture
  ends the trial. It never turns the voice microphone back on. A failed release
  blocks new capture or a new connection until cleanup succeeds.
- Notification playback actions use opaque in-memory command IDs and immutable
  pending intents. The current manual-session token, expected mute state
  and route must still match. A command is consumed before application. On
  safety mute, only a return-to-app action is offered; the notification never
  acknowledges the route guard or changes microphone state. The connected notification renders Disconnect and the
  current listening action as clickable custom content in its collapsed view;
  the expanded view keeps the standard action row. Both views use the same
  pending intents. The new connection channel uses normal importance without
  sound or vibration. An existing channel is migrated only when observable
  settings still match the old defaults and Android can report user-selected
  importance. Customized or blocked channels stay unchanged. Android and OEM
  filtering still control placement and whether the custom view is displayed.

## Boundaries

`MainActivity` and `TeamSpeakService` wire these components without adding a
parallel connection or voice pipeline. No protocol API, user-data format,
preferences schema, or migration changes. The manifest adds a browsable
`ts3server` intent filter; external inputs still require in-app confirmation.
An implementation may report a different actual microphone during a call than
during a local trial because the route in the trial is the system's current
input, not a guarantee about the call route.

SRV/TSDNS, desktop identities, cross-app PTT, Whisper and channel shortcuts
remain undecided. No changes to weak-network buffering or Bluetooth fallback
policy are part of this work.
