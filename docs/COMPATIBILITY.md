# 兼容性与验证范围 / Compatibility and validation

更新日期：2026-11-05。对应修复重发 **1.0.0 / versionCode 14**，替代撤回的 code 13。

本次音频修复新增 11 项独立回归，全量 JVM 测试为 562 项。Android 16 调试包的
两段约 30 秒播放录音启动后未见不少于 20 ms 全零段，维护者反馈“现在好多了”；
仍有少量过期包与设备欠载计数，不承诺完全无断音。正式包的新门禁、探针和升级证据
以[重发记录](release/1.0.0/REISSUE_2026-11-05.md)为准，下面的首次发布统计保留历史范围。
构建及签名包证据、维护者实测确认与保留缺口按
[发布决定 ADR-0021](decisions/0021-stable-release-acceptance.md)公开；未执行项目不记为通过。

## 平台与协议

| 项目 | 范围与边界 |
| --- | --- |
| 安装下限 | Android 8.0 / API 26；Android 8/9 尚无本轮真机验收 |
| 主要设备验收目标 | Android 10+；不表示这些系统已经全部实测 |
| 模拟器 | ARM64 / Android 15 / API 35，16 KiB；完整 App Android 129 项、默认音频 Android 32 项通过 |
| 已有真机 | 小米 14 / Android 16；维护者确认完成 11 月 3 日隔离 QA 包的剩余功能实测 |
| 其他厂商/第二 Android 代际 | 尚待覆盖，不从单台手机推导 |
| 架构 | 通用 APK 含 arm64-v8a、armeabi-v7a、x86、x86_64；四 ABI 构建不等于全部真机验收 |
| 16 KiB | ZIP 与 64 位 ELF LOAD 对齐通过；32 位 NDK libc++ LOAD 仍为 4 KiB |
| 服务端 | TeamSpeak 3 完整客户端协议，经固定 ts3j 适配；不是 ServerQuery，也不声明 TS5/TS6 全功能兼容 |
| 语音 | Opus 语音/音乐接收与 Opus 语音发送；不承诺全部旧编码或服务器插件兼容 |
| 并发服务器 | 一个活动会话，切换前先断开 |
| 语言 | 英文、简体中文，按系统/应用语言环境选择；无应用内语言切换按钮 |

## 首次发布历史证据（code 13，不代替修复包验收）

| 领域 | 已有证据 | 保留边界 |
| --- | --- | --- |
| 格式、编译与构建 | 238 个主门禁任务实际重跑；Debug/Release Lint 和混淆 APK 构建通过 | 既有依赖/工具链/资源警告保留，非零警告不等于错误 |
| 单元及原生回归 | 551 JVM、129 App Android、32 默认音频 Android、48 组 Opus 兼容及 7 Python 测试通过 | 默认音频套件不含可选 APM/麦克风采样探测，不证明全部声学场景 |
| 确切签名 APK | 身份/INI、Opus ART 1.6.1、冷启动、签名与对齐通过 | 探针不连接真实服务器 |
| 签名与升级 | 公开 0.11.1-m10 与 1.0.0 证书相同；模拟器 code 12 → 13 保留 UID、合成私有文件/偏好和 Keystore 密文 | 没有访问真实用户数据；最终包真机原有身份/收藏/表单复核待补 |
| 连接、频道、聊天/戳一下、状态、收藏及语音后台 | 维护者确认小米 14 上昨日 QA 包剩余实测完成；保留历史界面/通知/真实服务记录 | QA 为隔离应用 ID，早于今日蓝牙保护；未提供第二设备与细分通话时长，不补造结果 |
| 蓝牙路由 | 自动选择、断开/回退静音、重连保持静音的 JVM/Android 回归通过 | 最终 APK 的实体 HFP/SCO/BLE 建立、双向通话和热插拔待复核 |

首次 code 13 正式签名 APK 的构建源码为 `eb7e87d82c5b9aadaa46ddd2258b7de7f475b109`。
[该提交 CI](https://github.com/monet4070/ts3-mobile/actions/runs/41761559408)通过。
发布 tag 可能包含后续文档和合并提交；`BUILDINFO.json` 分别记录实际构建与发布提交，
并核对 434 个源码/配置输入未变，不将已有 APK 描述为一次新构建。

来源：[准备记录](release/1.0.0/PREPARATION_2026-11-04.md)、
[昨日完整回归](testing/STABLE_FULL_2026-11-03.md)、
[蓝牙修复回归](testing/BLUETOOTH_ROUTING_2026-11-04.md)、
[发布清单](release/1.0.0/CHECKLIST.md)。旧记录保持其日期与工件边界，不把多次测试数简单相加。

## 已知使用限制

- 蓝牙断开或意外回退触发收听静音，重连后确认设备并手动恢复。Android/HAL 可能在回调前
  将已缓冲 PCM 切至外放，软件无法保证所有设备上零瞬时外放。
- 厂商清理任务可能跳过正常退出回调；用应用/通知的断开操作结束会话。
- Android 通知权限、频道、勿扰和电池策略可能抑制提醒或中断后台连接。
- 音量门控在安静时持续采集；设备 AEC 支持与实际声学效果因设备不同。
- 聊天/戳一下记录与草稿只在服务内存；不提供永久历史、云同步或已读回执。
- 公开code14没有身份导入导出；本分支C2草稿候选支持自有加密格式，验证边界见下方。收藏跨包迁移、Whisper、文件传输、管理和多服务器标签页仍未实现。
- 默认 APK 不包含 APM 实验库，不将开发实验结果宣传为默认能力。
- ts3j 使用维护者 fork 的不可移动标签 `ts3mobile-1`，源码与原固定提交相同；
  JitPack 可用性仍是构建边界，见 [ADR-0004](decisions/0004-take-ownership-of-ts3j-dependency.md)。

## English

Stable **1.0.0 / code 14**, reissued November 5 after withdrawal of code 13,
supports installation from API 26. Its audio fix adds 11 independent regressions
for a total of 562 passing JVM tests. Android 16 debug recordings and maintainer
feedback confirm improvement, not a universal glitch-free guarantee. Replacement
build acceptance is recorded separately in the [reissue record](release/1.0.0/REISSUE_2026-11-05.md).
The automated and signed-APK results below are historical code 13 evidence.
Android 8/9 and a
second manufacturer/Android generation have not been physically accepted in this
round. Xiaomi 14 / Android 16 hands-on confirmation applies to the November 3
isolated QA APK, which predates the new Bluetooth protection. Detailed duration
records and final-APK real-device Bluetooth/data-upgrade checks remain open under
[ADR-0021](decisions/0021-stable-release-acceptance.md).

Fresh automated evidence: 238 gate tasks executed; 551 JVM, 129 app Android,
32 default audio Android, 48 Opus compatibility combinations and 7 Python tests
passed. The ARM64 API 35 emulator uses 16 KiB pages. The exact signed APK passed
identity/INI, runtime Opus 1.6.1, launch, signature and alignment checks. An update
from the public code 12 APK retained the UID, synthetic private files/preferences
and Keystore ciphertext; no real user data was accessed. The certificates match.
Existing toolchain/dependency/resource warnings remain; there were no gate errors.

Four packaged ABIs do not establish physical coverage of every architecture.
ZIP and 64-bit ELF LOAD alignment are 16 KiB; the 32-bit NDK libc++ LOAD remains
4 KiB. Optional APM/microphone probes are excluded from the default audio suite.
Build source and final release commits are recorded separately in `BUILDINFO.json`;
all 434 source/configuration inputs must match across later documentation changes.

The app targets TeamSpeak 3 via a pinned ts3j client protocol, one active server
and Opus audio. It is not ServerQuery and makes no full TS5/TS6, legacy-codec or
plugin compatibility promise. Locale follows Android, with English and Simplified
Chinese supported. Dependency ownership is completed under ADR-0004; JitPack
availability remains a build dependency.

Bluetooth loss/fallback mutes listening; reconnecting does not unmute it. Android/
HAL may reroute buffered PCM before callbacks, so zero transient speaker output
cannot be guaranteed. OEM task removal, notification/DND/battery restrictions and
device AEC quality remain system-dependent. Volume gate captures during silence.
Chat/poke history is memory-only with no read receipts or cloud history. Identity
export/transfer, Whisper, file transfer, administration, multi-server tabs and the
optional APM experiment are outside the default release.


## 身份改进候选范围 / Identity candidate scope

C2候选在API35／arm64／4KiB独立模拟器验证自有备份的实际SAF导出、跨安装导入、明确确认、
force-stop后精确身份材料和本地回退，含测试签名minified实际页面。具体SHA及source约束见
[C2.2](testing/IMPROVEMENT_C22_2026-11-07_ZH.md)、[C2.3](testing/IMPROVEMENT_C23_2026-11-07_ZH.md)及
[R3候选](testing/IMPROVEMENT_R3_2026-11-07_ZH.md)。公开1.0.0/code14尚未包含这些功能。
测试没有使用生产签名私钥、真实个人身份、线上服务器或录音。API26运行时、低端物理设备、
OEM／云文件提供方持久性、公开签名覆盖升级和独立安全审计仍未验收；构建minSdk26不是运行时证明。

The unshipped identity candidate is verified on independent API35 arm64 4-KiB
emulators, including actual SAF export/import, explicit confirmation, exact private
material after force-stop, and local rollback in test-signed minified builds.
Published code 14 does not include it. Source/APK fingerprints and limits are in
the linked C2.2/C2.3/R3 records. No production key, personal identity, live server
or recording was used. API26 runtime, low-end/OEM hardware, cloud-provider durability,
published-signature upgrades and an independent security audit remain unverified.
