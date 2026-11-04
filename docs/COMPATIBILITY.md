# 兼容性与验证范围 / Compatibility and validation

更新日期：2026-11-04。对应当前工作区与 1.0.0 发布准备，不是对最终签名包的验收证明。
维护者已确认在小米 14 上补完昨日 QA 包的剩余实测；具体工件与签名边界见
[发布准备记录](release/1.0.0/PREPARATION_2026-11-04.md)。今日蓝牙保护及最终签名包单独绑定证据，
下列历史记录中的缺口保留其原日期语境，不代表忽略维护者的补测确认。
“已实现”“离线通过”“历史实测”“待验收”分别表示不同证据，不能相互替代。

## 平台与协议

| 项目 | 范围与边界 |
| --- | --- |
| 安装下限 | Android 8.0 / API 26，由 minSdk 声明；Android 8/9 尚无本轮真机验收 |
| 主要设备验收目标 | Android 10+；不表示这些系统已经全部实测 |
| 模拟器 | Android 15 / API 35，当前完整离线 UI 114 项通过 |
| 已有真机 | 小米 14 / Android 16，约 400 dp 宽；本轮收藏界面 22 项及 3 项最终重验通过 |
| 其他厂商/第二 Android 代际 | 仍需验证，不能从单台手机推导 |
| 架构 | 通用包构建 arm64-v8a、armeabi-v7a、x86、x86_64；构建四 ABI 不等于每种架构都完成真机验收 |
| 服务端 | TeamSpeak 3 完整客户端连接，经固定 ts3j 协议适配；不是 ServerQuery 客户端，也不声明 TeamSpeak 5/6 全功能兼容 |
| 语音 | Opus 语音/音乐接收与 Opus 语音发送；不承诺所有旧语音编码或全部服务器插件兼容 |
| 并发服务器 | 一个活动会话，切换前需断开 |
| 语言 | 英文、简体中文，按系统/应用语言环境选择；无应用内语言切换按钮 |

## 功能证据

| 领域 | 已有证据 | 仍需补齐 |
| --- | --- | --- |
| 连接与退出 | 2026-10-18 混淆 Release 的真实连接/确认退出历史记录 | 最终 1.0.0 签名包连接、权限和退出复验 |
| 频道聊天 | 2026-10-23 独立 Debug 包真实双向收发 | 最终 Release、更多服务端与权限组合 |
| 私聊、戳一下、状态同步 | 协议/状态测试、离线 UI；真实 Android 通知集成测试 | 真实对端互通、无留言戳一下、前后台/锁屏与权限拒绝场景 |
| 收藏、搜索、布局 | 当前离线回归；真机合成数据交互与浅色/深色、大字体/键盘检查 | 最终包真实收藏连接、默认密码频道恢复 |
| 原生音频 | 分阶段 Opus/RNNoise、队列、混音、焦点与路由测试的历史记录 | 最终包听感、双向长时通话、功耗/温度、回声与门控调校 |
| 蓝牙/有线/USB | 路由代码与自动化边界测试 | 实体耳机双向通话、热插拔与回退 |
| 升级与签名 | 旧里程碑存在同签名升级历史 | 旧公开 APK → 最终稳定包的证书比对与保留数据升级 |

当前 App JVM 239 项通过、App Lint 0 错误/36 项既有警告、Debug 与混淆 Release 构建成功，
均见[收藏 UI 记录](testing/BOOKMARK_UI_2026-11-02.md)。这些结果没有覆盖全部原生设备与网络场景，
也不是最终签名包的同一次验收。

历史依据：[Release 连接](testing/RELEASE_ACCEPTANCE_2026-10-18.md)、
[频道文字真机](testing/F1A_DEVICE_2026-10-23.md)、
[戳一下通知](testing/POKE_UX_2026-11-02.md)、
[长时矩阵](testing/M10_STABILITY_MATRIX.md)、
[音频阶段](testing/AUDIO_A4B_2026-10-24.md)。

[2026-11-01 分支同步记录](testing/GITHUB_SYNC_2026-11-01_ZH.md)还保留了当时三模块
526 项 JVM、31 项原生 Android、48 组 Opus 兼容和实际 Release 探针结果。
其 App/协议计数早于本轮变更，不能与新记录简单相加作为同一次稳定包验收。

## 已知使用限制

- 厂商清理任务可能跳过正常退出回调；用应用/通知的断开操作结束会话。
- Android 通知权限、频道、勿扰和电池策略可能抑制后台提醒或中断后台连接。
- 音量门控在安静时持续采集；设备 AEC 支持与实际声学效果因设备不同。
- 聊天/戳一下记录与草稿只在服务内存中；不提供永久历史、云同步或已读回执。
- 身份导入导出、收藏跨包迁移、Whisper、文件传输、管理操作和多服务器标签页未实现。
- 默认构建不包含 APM 实验库；不以开发实验的结果宣传默认 APK 具有对应功能。
- ts3j 已改用维护者 fork 的 `ts3mobile-1` 固定标签，源码与原固定提交相同；JitPack 服务可用性
  仍是构建边界，维护策略见 [ADR-0004](decisions/0004-take-ownership-of-ts3j-dependency.md)。

## English

Installability starts at API 26; it is not proof of physical testing on every
Android version. The primary acceptance target is Android 10+. Current UI
evidence comes from an API 35 emulator and one Xiaomi Android 16 device.
Four packaged ABIs do not mean four physical architectures were validated.

The app targets TeamSpeak 3 through a pinned ts3j client protocol, one active
server and Opus audio. It is not a ServerQuery client and makes no full TS5/TS6,
legacy-codec or plugin compatibility promise. English and Simplified Chinese are
selected from Android's locale; there is no in-app language selector.

The linked records distinguish old live Release connection checks, Debug-only
channel-text interoperability, current fixture UI/notification checks and staged
native regressions. Private text/pokes/presence live interoperability, final signed
APK smoke and data-preserving upgrade, 30-minute duplex/background, real Bluetooth/
hot-plug, password-channel restore and a second device remain acceptance work.
Current 239 app JVM / 114 UI / 22 phone UI checks are not a substitute.

Task removal may skip exit callbacks; notification/battery/DND policy affects
background behavior. Gate listens during silence; AEC quality varies. Chat is
memory-only with no read receipts or cloud history. Identity export/transfer,
Whisper, file transfer, administration and multi-server tabs are absent. Default
builds exclude the APM experiment. Dependency ownership remains tracked by ADR-0004.
