# Changelog

**1.0.0**, released on **2026-11-04**, is the first stable release. Earlier versions
used milestone naming. After 1.0.0, patch versions are intended for compatible fixes
and minor versions for compatible features; Android versionCode increases for
each distributed update.

## 1.3.0 (versionCode 17)

[English Release Notes](docs/release/1.3.0/RELEASE_NOTES.md) · [中文更新说明](docs/release/1.3.0/RELEASE_NOTES_ZH.md) ·
[Verification Status & Release Gates / 验收状态](docs/release/CANDIDATE_STATUS_2026-11-25.md)

### Added

- **Connected floating PTT:** Included in standard builds with **Standard** and **Minimal** overlay styles, a compact fixed-height expanded speaker strip (up to three active speakers plus `+N`), silent notification recall, and optional **Auto-show when leaving the app** (Home or Back on the main screen on Android 12+, off by default, never starts recording). Hold mode transmits while pressed; other modes display live microphone state. The local-only recorder lab remains Debug-only.
- **Read-only channel files, chat attachments, and server images:** Browse channel files and explicitly download files or supported same-server chat attachments (20 MiB per-file limit, length-verified before saving via Android's file picker). Tap an available avatar in the Users list or a displayed custom channel icon to preview it; channel member rows keep their voice indicators. No uploads, automatic downloads, or cross-server retrieval.
- **Opt-in local chat history and BBCode formatting:** Off-by-default bounded encrypted archive (newest 7 days, max 500 messages / 512 KiB encoded total) for eligible new text messages, browsable and deletable offline per identity. Chat renders basic TeamSpeak BBCode, confirms external link destinations before opening, and preserves raw text on copy.
- **Bookmark credential isolation and offline channel lists:** Editing a bookmark's host or port clears saved passwords and the old default channel from the draft; editing only the default channel clears only its password. Unchanged bookmarks and the independent connection form are not modified. Added optional offline channel list saving (up to 100 channels per endpoint, max 6 endpoints) under bookmark More settings.
- **On-demand feature help:** Added **Settings → Help and about → Feature help** with offline guides and in-app control highlights; no tutorial opens automatically on launch.
- **Separate input/output audio devices and Bluetooth headset microphone options:** Added **Settings → Input and output devices** to choose physical microphones (with built-in position labels and duplicate numbering, without raw addresses) separately from playback devices, including independent **Bluetooth LE Audio** input/output when supported and an explicit, non-persisted **Bluetooth call (use headset microphone)** output option for classic SCO headsets (never auto-selected; releases call mode on switch-back, disconnect, or output failure). Losing an explicit microphone returns to automatic input; Bluetooth output loss retains safety muting.
- **Actual audio devices panel and timing diagnostics:** Added a read-only **Audio devices in use** panel (under both Input and output devices and Voice playback, reporting Bluetooth media A2DP, Bluetooth call SCO, and Bluetooth LE Audio) and redacted timing buckets/device categories in offline audio diagnostics (exact UDP timing remains in the separate local TS3j QA build).
- **Debug/QA desktop INI preview boundary:** Debug and opt-in QA builds can preview and confirm single desktop TS3 `.ini` imports separately from encrypted `.ts3mbak` backups; standard release builds keep desktop INI import disabled by default.

### Changed

- **Voice playback default:** Unset installations now default to **Stability first** without overwriting previously saved profiles.
- **Settings and Identity UI:** Direct **Create encrypted backup** and **Restore from backup** actions on **Identity and backups**; unified switches, choice cards, status readouts, and action layouts across Settings with narrow-screen and large-font reflow.
- **Safe bookmark connect:** Bookmark **Connect now** will not tear down or replace an active session.
- **Backup exclusions:** Included playback and update DataStores in Android backup/device-transfer exclusion rules.

### Fixed

- **Wi-Fi ↔ mobile data handoff and clone-limit (`521`) recovery:** Hold the original server session briefly (up to 2 seconds) during unblocked default-network drops and probe it on the new network to continue without reconnecting when possible. Fixed a ts3j initialization race that masked server rejections (including error `521`) as generic interrupts; a confirmed network-switch reconnect hitting `521` now waits 60 seconds and retries once cleanly instead of failing in a fast loop.
- **Bluetooth and playback safeguards:** Continuously detect unnotified Bluetooth auto-route fallbacks at output boundaries, cleanly retire idle delayed Bluetooth tracks, bound stale-packet eviction to 360 ms, distinguish expired voice from stalled outputs, and move local microphone trial cleanup and diagnostics I/O off the main thread.
- **Session exit, reconnect, and concurrency hardening:** Preserve user microphone settings and diagnostics across user disconnects; bound exit reclamation and safely recover from stale or failed cleanup without leaving connections stuck; preserve a second disconnect during reconnect handoff; stop obsolete callbacks from interrupting replacement sessions or reopening completed disconnects; ensure capture-release failures still run playback and route cleanup.
- **Channel, bookmark, download, and notification reliability:** Bound channel subscription and channel move waits without repeating unconfirmed moves; preserve the existing bookmark archive if a save exceeds its size limit; block floating capture while a local microphone trial owns input; keep verified downloads across screen recreation and warn with retry-to-new-destination on potentially incomplete writes; isolate per-operation diagnostic exports; recover only complete committed diagnostic checkpoints, report missed playback generations, and avoid blocking service teardown on disk writes; reconcile final notification actions after rapid updates and prevent retired services from restarting playback.
- **Chat history clock rollback safety:** Preserve saved chat history across device clock rollbacks, verify writability before retrying, and immediately free queue capacity when clearing the archive.
- **File transfer and handshake compatibility:** Accept empty, wildcard, and dual-stack multi-address server download offers via offline numeric validation against the active peer address; add bounded retries for recognized handshake unsigned-width errors while keeping pinned `ts3mobile-1`.

### 中文摘要（1.3.0 对比 1.2.0）

- **新增功能**：标准构建内置连接浮窗 PTT（标准／极简样式、紧凑发言栏、通知召回、可选 Android 12+ 退后台自动显示）；新增 **输入与输出设备** 页面支持分别选择麦克风（标注内置位置并编号）与播放设备，支持独立选择 **蓝牙 LE Audio** 输入／输出与手动开启 **蓝牙通话（使用耳机麦克风）**（绝不自动启用或跨会话保存）；支持只读频道文件与同服聊天附件校验后下载（限 20 MiB）及用户头像／频道图标预览；新增默认关闭的本地加密聊天历史（7 天 / 500 条 / 512 KiB 配额）、基础 BBCode 展示与外链核对；收藏支持改址／改频道凭据隔离与离线保存频道列表；新增「设置 → 帮助与关于 → 功能帮助」按需引导；语音播放默认采用稳定优先（不覆盖旧设置），并新增只读「实际使用的音频设备」面板（含蓝牙 A2DP / SCO / LE Audio）与脱敏计时诊断；身份与备份及各项设置完成交互统一与大字号／窄屏适配；桌面 INI 导入在标准正式包中默认关闭。
- **重点修复与加固**：短暂切网（Wi-Fi ↔ 移动数据）优先探测并保留原服务器会话，修复握手拒绝（含 `521` 同身份限制）被中断掩盖的问题并优化切网重连恢复；加强无回调蓝牙回退防护、空闲延迟音轨回收、360 ms 过期包淘汰及主线程 I/O 解耦；加固会话退出／重连并发与频道切换超时保护，确保主动断开保留麦克风参数与诊断记录，修复聊天历史时钟回拨、验证文件重建保存、收藏超限保旧档、通知最终状态同步与备份排除规则。

## 1.2.0 — 2026-11-20

- Mix incoming voice with foreground media without taking audio focus or entering
  Android's communication mode. Prefer the phone microphone for Bluetooth listening.
- Protect the first utterance while a confirmed Bluetooth route comes out of standby,
  without delaying ready outputs or weakening the safety mute on route loss.
- Add Balanced and Stability first receive profiles under Voice playback. Saving a
  profile resets listening immediately without disconnecting or restarting capture.
- Open, paste and share TeamSpeak server/channel invites with explicit confirmation
  and no link credentials or automatic form updates.
- Keep session actions in a shorter More menu, and find microphone, notifications,
  appearance, identity, diagnostics and update options on a dedicated Settings page.
- Test the microphone before or after connecting, without playback, encoding,
  or sending voice. An active call still requires turning its microphone off first.
- Preview all three appearance choices in distinct cards, and open background
  voice and diagnostics as full-screen settings pages. Use visible buttons or
  full-row targets in subpages, while retaining compact borderless channel controls.
- Use the same fixed back arrow for identity backups and updates. Remove redundant
  captions from settings rows and theme choices, retaining status and safety notices.
- Use a compact, borderless chat conversation selector with a single touch target
  spanning the icon, title and chevron.
- Adjust microphone input volume from a vertical slider in the bottom voice
  controls. The shortcut uses the same saved setting and never enables the mic.
- Use Disconnect and Mute or Resume listening directly in the connection
  notification. The default connection channel uses normal importance without
  sound or vibration. Headset protection still requires checking the route in
  the app.
- Keep a confirmed Bluetooth playback track through short audio gaps to reduce
  interruptions from repeated track creation. Route loss still stops playback.
- Load the bounded (up to 1 MiB) audio diagnostics archive off the service's main
  thread and keep the process-owned reader shared across service replacement.
- Publish the enforced retention limits in the audio diagnostics export and derive
  them from a single source.
- Give one update check a shared 20-second budget, cap each request at 10 seconds,
  and report BUILDINFO transport failures as server errors instead of inconsistent
  release metadata.

## 1.1.0 — 2026-11-09

VersionCode 15 integrates the previously staged improvements. The official package and
public certificate are retained. See [release acceptance](docs/release/1.1.0/CHECKLIST.md)
for final artifact, CI and physical-upgrade scope. Weak-network audio interruption remains
an open diagnostic investigation.

- Retain offline audio receive/playback snapshots and route/focus events across
  disconnect/reopening; add interruption marks and JSON file export. Keep the
  existing report fields and clarify that callback rejection is not network loss.
- Add a manual GitHub release checker with a remembered prerelease choice,
  versionCode comparison, release notes and explicit browser downloads.
- Keep update-page close and full-note collapse controls fixed above scrolling
  content; render Markdown release notes and separate channel, result and note cards.
- Distinguish idle Bluetooth SCO suspension from headset loss. Restore the selected
  headset after playback obtains focus, while preserving speaker fallback protection.
- Refine visual hierarchy with grouped microphone settings, a shared live-level/
  threshold track, structured search rows, left/right chat bubbles and centered
  system notes. Keep sending, capture and persistence rules unchanged.

- Preserve each chat's reading position across tabs; remember microphone tuning;
  save the current channel in bookmarks and offer an explicit channel Join action.
- Add private-message notifications with stale-click protection and bounded live
  microphone-level feedback during existing capture.
- Add verified encrypted `.ts3mbak` identity backup, explicit import/replacement and
  local rollback. Preserve existing encrypted storage and keys; desktop identity formats are unsupported.
- Keep unrelated participants' valid unmixed audio and decoder state when muting
  one participant. Output still retires safely; physical listening gaps remain unverified.
- Add persistent Light/Dark/System appearance, keep Light when unset, and fix
  dialog actions obscured by a settled software keyboard.
- Extract session-bound connection monitoring while preserving existing timing,
  reconnect policy and coordinator ownership. Expand offline CI regressions.

R4 local acceptance, before the UI follow-up: 736 JVM and 228 Android passes, with two external-fixture
tests skipped; four fresh API35 ARM64 emulator scenarios passed. See the
[R4 record](docs/testing/IMPROVEMENT_R4_2026-11-07_ZH.md) for exact artifacts, upgrade
and 16-KiB scope. Physical devices, live servers and production-signed upgrades
were outside this round.
The subsequent UI changes have their own [validation record](docs/testing/UI_HIERARCHY_2026-11-08_ZH.md).

## 1.0.0 reissue — 2026-11-05

The maintainer withdrew the original code 13 release after an audio regression and
explicitly requested reuse of `v1.0.0`. The replacement keeps versionName `1.0.0`
and the public signing certificate, increases versionCode to **14**, and retargets
the tag to the reviewed fix. Download the APK and checksums again; old code 13
files and cached source archives do not identify this build.

- Keep absolute 10 ms playback deadlines across bounded scheduler stalls instead
  of permanently slowing media consumption and repeatedly discarding buffered PCM.
- Treat arrivals newer than the worker's time snapshot as fresh, not expired;
  avoid invalidating a talker's buffered audio because of a negative computed age.
- Add 11 independent regression tests, including complete playback-chain traces.
  The fresh JVM suite contains 562 passing tests. Device recordings and the
  maintainer's improved-listening confirmation apply to the debug fix, not an
  unperformed release-APK acoustic acceptance.
- Preserve codec settings, receive-budget limits, protocol, permissions and data
  formats. Remaining physical Bluetooth, background/load and real-data upgrade
  coverage is not promoted to a pass.

See the [reissue record](docs/release/1.0.0/REISSUE_2026-11-05.md) and
[audio investigation](docs/testing/AUDIO_PLAYBACK_REGRESSION_2026-11-05_ZH.md).
This is an explicitly authorized exception to the normal immutable-tag policy,
not a convention for future releases.

## 1.0.0 — 2026-11-04

Tag: [`v1.0.0`](https://github.com/monet4070/ts3-mobile/releases/tag/v1.0.0),
versionCode 13. The signed APK keeps the public 0.11.1-m10 certificate; an API 35
emulator upgrade retained the UID and synthetic private files, preferences and
Keystore ciphertext. Physical-device coverage and remaining checks are disclosed
in [compatibility](docs/COMPATIBILITY.md) and the
[release decision](docs/decisions/0021-stable-release-acceptance.md).
[中文 Release 文案](docs/release/1.0.0/RELEASE_NOTES_ZH.md) ·
[English release copy](docs/release/1.0.0/RELEASE_NOTES.md).

### Added

- Channel and private chat with independent drafts, unread positions, copy,
  identity-aware targets and explicit send results; no automatic retry.
- Named encrypted server bookmarks, optional default channel/password storage,
  per-entry revision guards and explicit disconnect before switching.
- Channel-member poke shortcut, optional reason, private history, in-app receive
  notices and generic background notifications without sender/reason content.
- Away/message and server-visible mute synchronization, local channel/user
  search, and a project repository menu entry.
- Microphone input gain, RMS volume gate, RNNoise and device AEC controls.
- Bilingual user guides, install/upgrade instructions, troubleshooting, support,
  compatibility matrix and first stable release/signing documentation.

### Changed

- Warm elevated surfaces in both palettes; clearer connection/session hierarchy,
  compact navigation, fine poke/away icons and large-text/keyboard layouts.
- Bookmark cards separate the primary Connect action from management menus;
  Add remains outside scrolling, and the editor groups optional settings.
- Poking preserves the current page and conversation with brief result feedback.
- Build official pinned Opus 1.6.1 for four ABIs; improve receive buffering,
  bounded queues, mixing/limiting, gain transitions and audio resource lifetimes.
- Keep chat through ordinary reconnect, clear it on manual/new/terminal sessions
  and reject stale session/target callbacks.

### Fixed

- Automatically select available Bluetooth communication headsets. Mute listening
  on device removal, Bluetooth fallback or failed route restoration; reconnecting
  selects the headset without clearing the safety mute. Resume listening manually.
- Accept incoming pokes that omit the optional reason; add background alerts
  and cleanup/deduplication across visibility and session boundaries.
- Restore chat availability after channel restoration; prevent old channel work
  from replacing a new connection's restore settings.
- Refresh permission-derived microphone mute and avoid duplicate roster keys
  when several clients use the same identity.
- Bound audio recovery/focus retries, preserve microphone teardown ownership,
  invalidate old route/capture data and improve playback failure feedback.

### Removed and limits

- Remove channel-detail entry points; expansion, joining and search remain.
- Default packages exclude the optional APM experiment. Identity export,
  Whisper, file transfer, administration and multi-server tabs remain absent.
- Tested coverage and remaining physical-device checks are recorded in
  [compatibility](docs/COMPATIBILITY.md); automated results do not establish
  acoustic behavior on every phone or headset.

### 中文摘要

首个稳定版 1.0.0（versionCode 13）于 2026-11-04 发布，带来频道/私聊、独立草稿与未读、
服务器加密收藏、戳一下及后台提醒、暂离与状态同步、界面优化和 Opus 1.6.1 音频改进。
蓝牙通话耳机自动选择，断开或意外回退时收听静音，重连后需手动恢复。
沿用公开 0.11.1-m10 证书，模拟器保留数据升级通过；最终包蓝牙真机复核和第二设备覆盖仍待补齐。
完整[中文发布说明](docs/release/1.0.0/RELEASE_NOTES_ZH.md)与英文说明同步维护。

Detailed pre-1.0 implementation notes are preserved in the
[development archive](docs/release/DEVELOPMENT_NOTES_PRE_1_0.md).

## 0.11.1-m10 — 2026-10-18

- Fix the minified Release crash when reading an existing TeamSpeak identity
  (#3), preserving ini4j reflection constructors and adding an actual APK smoke
  check to CI. No identity reset or app-data deletion is required.
- Preserve active voice transmission across temporarily empty encoded queues.
- Stop automatic retries after identity connection-limit refusals; show localized
  wait/manual-retry guidance and keep ordinary network recovery automatic.
- Prioritize server exit before audio cleanup, preserving server-confirmed results.
  OEM task killing can still bypass task-removal callbacks: disconnect explicitly.
- Remember the last address, port, nickname and server password with a separate
  Keystore-backed AES-GCM snapshot, excluded from Android backup/transfer.
- Refresh the warm cream UI, emphasize Disconnect next to the server and move
  diagnostics/background checks into More options.
- Include successful endpoint history, network-switch/silent-loss recovery,
  bounded background power handling and privacy-safe persistent diagnostics
  added since the previous published M10 build.
- Local verification: 261 JVM tests, 28 offline UI tests, minified APK identity/INI
  smoke and two live-server Release connections/server-confirmed exits on Android 16.
  Actual voice listening, long-duration Bluetooth and Android 14 acceptance remain open.

## Earlier milestone development notes

### Added

- A warm cream Compose interface with terracotta accents and licensed Lucide
  outline icons, retaining server history, background voice checks and the
  microphone-paused state from the latest connection implementation.

- English as the default UI language with Simplified Chinese kept under
  `values-zh/`. All user-facing text now lives in string resources, and the
  service and audio layers emit typed `UserMessage` / `AudioRoutingError`
  values that are resolved to text at the Android boundary (ADR-0006).
  The English wording follows @polleyr's `fb_localization_english` branch.
- High-volume 16-bit voice packet sequence regression coverage.
- Repeated reconnect backoff verification and a 60-second native Opus device soak.
- A privacy-safe M10 physical-device stability and audio test matrix.
- A thread-safe session-generation gate with concurrent stale-callback tests.
- Device coverage that verifies route selection reaches Android's active
  communication device and that playback uses the voice-communication strategy.
- Audio-focus policy and device arbitration regression coverage for transient
  interruptions and recovery.
- A focused channel-restore policy with password-preservation regression cases.
- Protocol session-generation gating that rejects stale ts3j snapshot and voice
  callbacks during reconnect races.
- Atomic connection-attempt status ordering so asynchronous protocol failures
  cannot be overwritten by a racing connected notification.
- A narrow internal ts3j socket port with fake-backed facade race regression tests;
  the public session-client construction remains unchanged.
- An M11 release checklist and privacy-safe local build-environment diagnostic
  covering the required Java, Android SDK and UDP bind prerequisites.

### Fixed

- Keep a voice talkspurt active across temporary encoded-frame queue gaps; only
  an explicit capture stop emits the end marker. Exercise the actual pinned
  ts3j sender with offline datagram fixtures.

- Send the captured session's exit before audio cleanup or session-mutex waiting
  consumes its budget; preserve server confirmation when later cleanup times out
  or fails. Xiaomi Android 16 testing confirms that launcher process killing can
  still precede the task-removal callback; this is not a guaranteed swipe exit.

- Release connection failure while reading a TeamSpeak identity: preserve ini4j's
  reflective parser, builder and formatter instead of only its public `Ini` entry
  point (#3). CI now builds release and checks offline identity/INI roundtrips
  against the actual minified APK.

- Password-protected servers rejected the connection because the server password
  was handed to ts3j in plaintext. ts3j forwards `client_server_password`
  verbatim, so it now receives the TeamSpeak Base64(SHA1) representation.
  Thanks to @polleyr for the report and fix (#1).
- Removed an accidentally pasted SVG data-URI line from the packet-sequence
  unwrapper test that broke Kotlin compilation.
- The build-environment check no longer aborts on Windows PowerShell 5.1 when
  `java -version` writes its version banner to stderr.
- The repository privacy scan no longer fails when a tracked file is deleted
  in the working tree but not yet committed.

### Changed

- Stop automatic retries after identity connection-limit refusals. Localized
  guidance recommends waiting at least 60 seconds before manual retry, explains
  temporary bans from repeated attempts, and recommends explicit disconnect
  before task removal. Ordinary network recovery remains automatic.
- Move diagnostics/background checks to More options; emphasize Disconnect to
  the right of the connected server information.
- Restore the last address, port, nickname and server password from a separate
  Keystore-backed AES-GCM form snapshot, excluded from backup. Existing identity
  and successful endpoint history formats remain unchanged.

- Completed the architecture decomposition plan for the oversized first-party
  files: the reconnect loop and channel-restore bookkeeping moved out of
  ConnectionCoordinator into ReconnectEngine and ChannelRestoreTracker
  (678 to 568 lines), ts3j event-to-snapshot mapping moved into
  Ts3jEventAdapter (Ts3jSessionClient 487 to 405 lines), and the microphone
  effect chain and measurement bookkeeping moved into MicrophoneAudioEffects
  and CaptureSessionStats (OpusMicrophoneCapture 373 to 275 lines, now below
  the review boundary). All existing tests pass unchanged; the tracker and
  stats gained dedicated JVM tests.
- Extracted the per-talker jitter/reordering/concealment pipeline from
  OpusAudioPlayer into a JVM-testable TalkerJitterPipeline behind an
  OpusPacketDecoder port (the native decoder remains the production
  implementation); OpusAudioPlayer dropped from 668 to 492 lines with frame
  timing unchanged, and the reordering, duplicate/stale rejection,
  initial-hold gating, talkspurt-finish and packet-concealment behavior now
  has fake-decoder unit coverage.
- Extracted the connection/reconnect state machine from TeamSpeakService into
  a JVM-testable ConnectionCoordinator behind an internal host seam, per
  ADR-0005; TeamSpeakService dropped from 1018 to 494 lines and now owns only
  the binder, notification, participant settings and components. The
  coordinator is covered by new fake-host unit tests for stale-callback
  rejection, user disconnect, network-driven reconnect and terminal failure.
- Consolidated the duplicated sanitized failure-type logging helper into the
  protocol module as a public API; the audio module now imports the single
  implementation instead of maintaining a byte-identical copy.
- Split the Compose connection, connected-session, channel, participant,
  microphone, diagnostics and top-level screen surfaces into focused files.
- Moved microphone permission, push-to-talk intent, serialized capture lifecycle
  and failure recovery into a focused controller with a unit-tested capture
  decision policy.
- Route remote voice through Android's voice-communication audio strategy so
  earpiece, speaker and communication-device selection affect actual playback.
- Isolated audio-focus signal classification so unknown framework callbacks do
  not unexpectedly mute playback.
- Isolated channel restore decisions from the foreground service so reconnect
  callbacks cannot overwrite a pending password target.
- Bound channel-switch snapshot updates to the socket generation that performed
  the blocking join, preventing an old session from changing a replacement session.

## [0.10.0-m9] - 2026-08-15

### Added

- In-app connection diagnostics with thread-safe counters and a redacted JSON export.
- Protocol event fixtures and mapping regression tests that do not require an online server.
- Repository architecture documentation, decision records, configuration example,
  root AI/maintainability policy, and a Kotlin formatting gate.

### Changed

- Split microphone mode, participant audio settings, protocol mapping, and diagnostics
  into focused production files.
- Sanitized protocol and audio logs so they contain failure types instead of server,
  participant, password, or exception-message data.
- CI now checks Kotlin formatting before tests, Android Lint, and assembly.

## [0.9.0-m8] - 2026-08-10

### Added

- Expandable channel rows with direct member rosters.
- Single-tap expansion and double-tap channel joining.
- Password prompt for protected channels reached by double tap.
- Public-repository license, notices, privacy and security policies,
  contribution guidance, templates, and CI configuration.

### Changed

- The current channel expands automatically after connection or a channel move.
- Channel member rows expose talking, microphone-muted, output-muted, and own
  client states.

## [0.8.0-m7] - 2026-08-09

### Added

- Per-user mute and 0-200% playback gain keyed by stable TeamSpeak identity.

## Earlier milestones

M0-M6 established ts3j protocol connectivity, foreground lifecycle,
bidirectional Opus audio, RNNoise denoising, microphone modes, audio routing,
reconnection, and channel switching.
