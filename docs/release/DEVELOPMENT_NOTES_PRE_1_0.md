# Development notes before the first stable release

Archived on 2026-11-02 from CHANGELOG's former Unreleased section. These are
historical implementation notes, not a release acceptance statement. Later UI,
notification and channel-detail changes supersede portions of this text; the
[current changelog](../../CHANGELOG.md) and
[1.0.0 release pack](README.md) describe the final target.

- Make disconnect-budget tests deterministic with an injected internal monotonic
  clock, covering time spent before socket submission and an exhausted deadline.
  Require fresh JVM test execution for acceptance evidence; correct earlier cached
  summaries. Hide playback recovery guidance after disconnect while retaining
  diagnostics, and separate microphone feedback from capture orchestration.

- Complete audio follow-up boundaries: report playback failure in UI/notifications,
  make playback stop independent of the output lock, preserve failed retirement
  ownership, clear failure streaks across healthy short speech and release limiting
  through long idle periods. Retain the microphone foreground type through teardown,
  avoid restarting RNNoise for volume changes, filter unrelated route events and
  invalidate cached APM builds when toolchains change.

- Repair audio review findings: bound playback output recovery and focus retries,
  invalidate microphone sending before background teardown, clear capture history
  on input/output route changes, preserve limiter release through empty ticks,
  evict oldest outstanding media and recover safely from FEC errors. Add explicit
  PCM capacity, serialized native lifetimes and runtime Release codec-version checks.

- Implement A4b receive scheduling: expire global input older than 60 ms, share
  per-speaker 240 ms media leases across queues/PCM, preserve fresh packets on
  affected-stream resets and adapt startup buffering within 40–120 ms. Add real
  packet metadata and conditional FEC with synthetic recovery evidence. Unconfirmed
  TS3 frame duration uses bounded PLC; production sender parameters stay unchanged.

- Upgrade libopus from the 1.3.1 Prefab package to SHA-256-pinned official 1.6.1
  sources, retaining encoder parameters and one codec per APK. Build four ABIs
  with codec optimization in Debug/Release and 16 KiB native LOAD alignment.
  Add old/new cross-codec, stereo downmix, DTX, duration and malformed-packet
  fixtures plus a real minified APK codec smoke check. Neural features stay off.

- Add the A3 opt-in APM development prototype: pinned standalone native builds for
  four ABIs, accepted-sample render references, monotonic delay estimates and bounded
  DSP consumption/fallback. Add synthetic echo, double-talk, noise and lifecycle
  checks. Default packages exclude the APM library; live capture integration and
  physical-route acceptance remain pending.

- Start audio-quality phases A0/A1: freeze baseline fingerprints, extract Android
  playback output and a deterministic timeline, preserve short-gap continuity and
  drain accepted tails before releasing focus. Bound partial/zero writes and PLC
  duration; count FIFO overflow instead of truncating newly decoded blocks.
  Forced mute, focus loss, route changes and stop discard old output immediately.
  Physical-route and acoustic acceptance remain pending.
- Implement audio phase A2: float manual/participant gain with 20 ms ramps and
  immediate mute, fixed-unity mixing and a -1 dBFS peak limiter. Keep gate levels
  before limiting and discard old onset/partial audio on settings revisions.
  Limiting adds no PCM delay; parameters remain pending physical-device acceptance.

- Fix channel-restore completion leaving chat disabled, and bind manual/restore
  channel commands and their results to the originating session so old work cannot
  replace a new connection's default channel or password.
- Refresh server-visible microphone mute after Android permission changes without
  starting idle push-to-talk capture; clear resolved permission errors.
- Keep user-list rows distinct when several clients share a TeamSpeak identity,
  avoiding duplicate-key crashes while retaining ambiguous-private-target guards.

- Add named encrypted bookmarks with revision checks, optional saved passwords,
  default channel ID, fill/quick connect and explicit disconnect before switching.
- Add away/message and server-visible input/output mute synchronization with
  pending/confirmed/uncertain outcomes, reconnect application and stale-result guards.
- Add bounded on-demand plain-text channel information and local channel/user search,
  including identity-checked user-channel resolution and password-protected joins.
- Restrict the current requested feature scope to bookmarks, presence and discovery;
  identity transfer, friends, moderation, quality dashboards and file transfer are excluded.

- Add microphone input volume (0–200%), RNNoise and device AEC controls with actual
  effect status, plus an RMS volume gate with adjustable dBFS threshold, bounded
  onset/tail, separate listening/sending states and silent-capture foreground type.
  Parameters stay in service memory; acoustic/device-route acceptance remains open.

- Add TeamSpeak poke through user actions with an optional bounded reason,
  identity/attempt checks, explicit send outcomes, private history and an in-app
  receive dialog. Poke and text share the pending-send and retention budgets;
  no automatic retry, sound, vibration or new permission. Live poke remains unverified.
- Add development current-channel text chat with bounded service memory, unread
  positions, long-press copy and explicit server-accepted/rejected/unknown states.
- Add private text through user actions and a conversation picker, independent
  drafts/unread/scroll positions, identity-aware targets and confirmed close.
  Up to 20 conversations share the existing message budget; offline or ambiguous
  targets pause sending, and missing-UID conversations end with their attempt.
- Preserve chat and drafts through ordinary reconnect, reject old attempt callbacks,
  and clear text on new manual sessions, terminal failure and exit; never auto-resend.
- Preserve text whitespace through the pinned encoder; bound command waiting without
  blocking disconnect or moving commands to replacement sockets.
- Add bilingual keyboard-aware chat UI and compact voice controls, plus offline
  protocol, state/controller and Compose regressions. Channel text passed real-server
  roundtrip on the Android 16 Debug package; private and Release text roundtrip
  remain unverified. The published 0.11.1-m10 package is unchanged.
