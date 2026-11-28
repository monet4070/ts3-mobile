# 兼容性与验证范围 / Compatibility and Validation

[English](#english) · [1.3.0 更新说明](release/1.3.0/RELEASE_NOTES_ZH.md) · [安装与升级](INSTALLATION.md) · [验收状态与发版门禁](release/CANDIDATE_STATUS_2026-11-25.md)

本页说明 **TS3 Mobile 1.3.0（versionCode 17）** 的平台要求、协议范围、当前已验证范围与已知使用边界。未执行的真机测试不记为通过；各版本最终签名工件的精确门禁以[验收状态](release/CANDIDATE_STATUS_2026-11-25.md)与[发布资料索引](release/README.md)为准。

## 平台与协议范围（1.3.0）

| 项目 | 支持范围与边界 |
| --- | --- |
| 最低系统要求 | Android 8.0（API 26）及以上；Android 8/9 暂无本轮真机实测验收 |
| 主要验收环境 | Android 15（API 35）模拟器与小米 14（Android 16 / API 36）；单台真机不代表全部厂商 ROM |
| CPU 架构与对齐 | 通用 APK 包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`；ZIP 与 64 位 ELF LOAD 满足 16 KiB 对齐（32 位 NDK `libc++` 仍为 4 KiB） |
| 服务端协议 | TeamSpeak 3 完整客户端协议（基于固定 `ts3mobile-1` 版本的 `ts3j` 适配）；非 ServerQuery，不承诺 TS5/TS6 全功能兼容 |
| 语音编解码 | 官方 Opus 1.6.1（语音／音乐接收与 Opus 语音发送）与 RNNoise 降噪；不承诺旧编码或服务器插件兼容 |
| 活动会话数 | 同时保持一个活动服务器会话；切换服务器前需先断开 |
| 界面语言 | 简体中文、English（随 Android 系统／应用语言环境自动切换，无应用内切换按钮） |

## 1.3.0 验证范围与剩余门禁

| 领域 | 已完成的验证证据 | 保留边界（尚未代签） |
| --- | --- | --- |
| 自动化与软件回归 | 候选源提交 `e348a0c` 通过 GitHub Linux/x86_64 Android CI（含固定 TS3j 源码回退构建）；独立本地运行 JVM 979+（含后续工作树预检 993）、Python 34、API 35 arm64 应用 331 项与音频 39 项（含 DTX、FEC 与 360 ms 过期淘汰）以及单模拟器回环文件下载与静音语音并行探针；自动浮窗定向用例 11 项通过 | 模拟器与早期提交 CI 不能代替最终发布提交的 CI 与实体设备签收 |
| 签名与保留数据升级 | API 36 隔离模拟器从公开 `1.2.0` 覆盖升级到最终签名 `1.3.0` 成功，原应用 UID、合成文件均保留，升级后可冷启动 | 最终包尚未完成真实身份、收藏和设置的全量升级复验；证书与 APK 摘要以发布资产中的 `BUILDINFO.json` 和 `SHA256SUMS.txt` 为准 |
| 蓝牙、切网与浮窗真机 | 早期原签名基线在小米 14 上验证了播放中蓝牙断开触发安全静音、重连保持静音以及浮窗按住发话；独立手机 QA 包反馈后台音频与切网短停后恢复 | 最终签名包仍需复验双向 Wi-Fi ↔ 蜂窝原会话保留（核对服务端客户端 ID）、播放中蓝牙防外放、冷启动、锁屏 ≥30 分钟、文件真机保存、浮窗 Home/返回手势与系统备份／换机 |
| 功能与构建边界 | 标准构建包含连接浮窗 PTT、只读频道文件／附件、可选本地聊天历史与按需功能帮助 | 桌面 `.ini` 导入与本机录音实验室不包含在标准正式包中；精确 UDP 计时字段仅在独立本地 TS3j QA 构建可用 |

详细数据、提交哈希与逐条放行条件见[1.3.0 验收状态与发版门禁](release/CANDIDATE_STATUS_2026-11-25.md)与[11-26 验收交接](release/HANDOFF_1.3.0_2026-11-26_ZH.md)。

## 已知使用限制（1.3.0）

- **蓝牙安全静音与系统缓冲**：设备断开或蓝牙自动回退（含无回调回退）会立即触发收听安全静音，重连后需手动确认路由再恢复。Android / HAL 在回调到达前可能将已缓冲 PCM 送往扬声器，软件无法保证所有机型零瞬时外放。
- **厂商后台与划卡杀进程**：部分厂商 ROM 清理最近任务会跳过退出回调，请在应用或通知栏使用 **断开连接**；Android 通知权限、频道设置、勿扰与省电策略可能抑制提醒或限制长时后台。
- **语音与音频设备**：音量门控在安静时持续采集；设备回声消除（AEC）效果因机型与路由而异；默认蓝牙媒体模式下自动麦克风优先使用手机麦克风，也可在 **设置 → 输入与输出设备** 手动选择 LE Audio 耳机麦或传统 **蓝牙通话（使用耳机麦克风）**（传统通话模式音质可能下降，且 LE Audio 双工与传统 SCO 实机听感仍待不同机型与耳机验证，见 [ADR-0039](decisions/0039-media-friendly-voice.md)）。
- **切网保活边界**：短暂默认网络丢失时先保留原会话最多 2 秒并探测，但能否跨网络无缝续用取决于服务端与网络路径（如源 IP 变更）；无法维持时会走正常断线恢复。
- **文件、聊天与历史边界**：频道文件与同服聊天附件均为**只读下载**（单文件上限 20 MiB），不支持上传、自动下载或跨服取件；实时聊天仅存服务内存，可选本地聊天历史默认关闭（最近 7 天 / 500 条 / 512 KiB 限额，无云同步），且无已读回执。
- **身份与暂未提供能力**：`.ts3mbak` 身份备份不包含收藏与连接表单；标准正式包不含桌面 `.ini` 导入、Whisper、服务器管理、多服务器标签页或可选 APM 实验库。
- **协议依赖**：1.3.0 保持固定 `ts3mobile-1` 依赖（见 [ADR-0004](decisions/0004-take-ownership-of-ts3j-dependency.md)）；构建时如 JitPack 不可用，可按[贡献指南](../CONTRIBUTING.md)执行离线源码回退构建。

---

## English

[简体中文](#兼容性与验证范围--compatibility-and-validation) · [1.3.0 Release Notes](release/1.3.0/RELEASE_NOTES.md) · [Installation & Upgrades](INSTALLATION.md#english) · [Verification Status](release/CANDIDATE_STATUS_2026-11-25.md)

This page records platform requirements, protocol scope, verified coverage, and known limits for **TS3 Mobile 1.3.0 (versionCode 17)**. Unperformed physical-device checks are never reported as passes; exact release-gate status for the final signed artifact is tracked in [1.3.0 Verification Status](release/CANDIDATE_STATUS_2026-11-25.md) and the [Releases Index](release/README.md).

### Platform and protocol scope (1.3.0)

| Area | Scope and limits |
| --- | --- |
| Minimum Android version | Android 8.0 (API 26)+; Android 8/9 have not been physically accepted in this cycle |
| Primary test environments | Android 15 (API 35) emulators and Xiaomi 14 (Android 16 / API 36); single-device tests do not generalize to all OEM ROMs |
| Packaged ABIs & alignment | Universal APK with `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`; ZIP and 64-bit ELF LOAD segments are 16 KiB aligned (32-bit NDK `libc++` remains 4 KiB) |
| Server protocol | Full TeamSpeak 3 client protocol via pinned `ts3mobile-1` (`ts3j`); not ServerQuery, and no full TS5/TS6 compatibility claim |
| Voice codecs | Official Opus 1.6.1 (voice/music receive and voice send) and RNNoise; no legacy-codec or server-plugin guarantee |
| Active sessions | One active server session at a time; disconnect before switching servers |
| Languages | English and Simplified Chinese, selected from Android system/app locale (no in-app switcher) |

### 1.3.0 verification coverage and open gates

| Area | Verified evidence | Remaining limits |
| --- | --- | --- |
| Automated regressions | Candidate commit `e348a0c` passed GitHub Linux/x86_64 Android CI (including pinned TS3j source fallback); local runs passed 979+ JVM tests (993 in later worktree preflight), 34 Python checks, 331 app and 39 audio API 35 arm64 Android tests (including DTX, FEC, and 360 ms stale eviction), single-emulator loopback file-download + silent-voice overlap, and 11 focused floating-PTT/auto-show regressions | Emulator runs and earlier commit CI do not sign off the final release commit or replace signed-device checks |
| Signing & upgrades | An isolated API 36 emulator upgraded in place from public `1.2.0` to the final signed `1.3.0` APK, retained the app UID and a synthetic file, and cold-launched | Full real-data migration of identities, bookmarks, and settings is not yet verified for the final APK; see `BUILDINFO.json` and `SHA256SUMS.txt` for the public artifact |
| Physical Bluetooth, handoff & floating PTT | Earlier public-cert baseline verified in-playback Bluetooth safety mute, mute retention on reconnect, and floating PTT hold-to-talk on Xiaomi 14; isolated phone QA reported passing background audio and brief pause/resume on network switch | Final signed APK still requires bidirectional Wi-Fi ↔ cellular session-ID verification, in-playback Bluetooth & cold-start recheck, ≥30 min screen-off soak, on-device file save, both Home/Back auto-show gestures, and backup/transfer checks |
| Feature scope | Standard builds ship connected floating PTT, read-only channel files/attachments, opt-in local chat history, and on-demand feature help | Desktop `.ini` import and the local recorder lab are excluded from standard releases; exact UDP timestamps exist only in the separate local TS3j QA build |

### Known limits (1.3.0)

- **Bluetooth safety mute & HAL buffering:** Headset removal or unexpected Bluetooth route fallback (including unnotified auto-route fallbacks) triggers a listening safety mute; confirm the route and unmute manually after reconnecting. Android/HAL may play already-buffered PCM on speaker before callbacks arrive, so zero transient speaker output cannot be guaranteed on every phone.
- **OEM task killers & background policies:** Swiping the app from Recents can kill the process before exit callbacks run—disconnect via the app or connection notification. Android notification permissions, channel settings, Do Not Disturb, and OEM battery policies can limit background sessions or alerts.
- **Voice & audio hardware:** Volume gate captures continuously during silence; real device AEC effectiveness varies by phone and route; in default Bluetooth media mode, automatic microphone prefers the phone mic, while **Settings → Input and output devices** allows manually choosing an LE Audio headset mic or explicit **Bluetooth call (use headset microphone)** (classic call mode may lower audio quality, and physical-device LE Audio duplex / SCO acoustics still require hardware verification; see [ADR-0039](decisions/0039-media-friendly-voice.md)).
- **Network handoff boundaries:** Brief default-network drops (up to 2 seconds) hold and probe the original session, but whether the server accepts continuation across path/IP changes depends on server and network behavior; normal reconnect runs if continuation fails.
- **Files, chat & history boundaries:** Channel files and same-server chat attachments are **read-only downloads** (20 MiB per file; no uploads, automatic downloads, or cross-server retrieval). Live chat is memory-only with no read receipts; opt-in local chat history is off by default (7 days / 500 messages / 512 KiB limit, no cloud sync).
- **Identity & out-of-scope features:** `.ts3mbak` identity backups exclude bookmarks and connection forms. Desktop `.ini` import, Whisper, server administration, multi-server tabs, and the optional APM experiment are outside standard release builds.
- **Protocol dependency:** 1.3.0 keeps pinned `ts3mobile-1` ([ADR-0004](decisions/0004-take-ownership-of-ts3j-dependency.md)); if JitPack is unavailable when building from source, see [Contributing](../CONTRIBUTING.md) for the offline TS3j source fallback.

---

## 历史版本验收归档 / Historical Release Validation

- **1.2.0（code 16，2026-11-20）**：[中文发布说明](release/1.2.0/RELEASE_NOTES_ZH.md) · [English Release Notes](release/1.2.0/RELEASE_NOTES.md)
- **1.1.0（code 15，2026-11-09）**：[1.1.0 发布清单与验收](release/1.1.0/CHECKLIST.md) · [R4 候选验收](testing/IMPROVEMENT_R4_2026-11-07_ZH.md)
- **1.0.0（code 14 重发，2026-11-05）**：[重发记录](release/1.0.0/REISSUE_2026-11-05.md) · [1.0.0 发布清单](release/1.0.0/CHECKLIST.md) · [ADR-0021 稳定版发布决定](decisions/0021-stable-release-acceptance.md)
- **历史界面截图说明**：[文档截图索引](images/README.md)（仅展示界面，不扩大实机验收范围）
