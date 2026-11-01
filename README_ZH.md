# TS3 Mobile

[![Android CI](https://github.com/monet4070/ts3-mobile/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/monet4070/ts3-mobile/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/monet4070/ts3-mobile?label=latest%20release)](https://github.com/monet4070/ts3-mobile/releases/latest)

English documentation: [README.md](README.md)

TS3 Mobile 是一个实验性的开源 Android TeamSpeak 3 客户端，基于
[Manevolent/ts3j](https://github.com/Manevolent/ts3j) 提供的完整客户端协议构建。

**[下载最新 APK](https://github.com/monet4070/ts3-mobile/releases/latest)**
（Android 8.0+，约 14 MB）。请勿安装旧的 **v0.1** 版本：它早于服务器密码认证支持，
也没有 0.11.1-m10 的身份读取崩溃修复。详见[下载与安装](#下载与安装)。

本项目是非官方社区项目，与 TeamSpeak Systems GmbH 没有隶属、认可或赞助关系。
TeamSpeak 及相关名称和商标归其各自所有者所有。

## 项目状态

最新 M10 维护版本为 **0.11.1-m10**，已作为常规 GitHub Release 发布（未标记为
预发布），但应用本身仍属预发布质量。该版本修复 issue #3 的 Release 身份读取崩溃，
新增连接表单加密记忆、连接限制后手动重试指引，并改善断开按钮。已有 M10 安装可直接
覆盖升级，无需清除应用数据或重建身份。

下一阶段的功能设计优先考虑文字聊天、常用服务器收藏与语音激活，详见
[功能路线图（2026-10-23）](docs/plans/NEXT_PHASE_2026-10-23_ZH.md)。频道聊天、私聊、戳一下、音量门控和收藏已在开发工作区接入，尚未改变已发布版本。
具体任务、接口和验收拆分见[详细执行方案](docs/plans/FEATURE_EXECUTION_PLAN_2026-10-23_ZH.md)。

功能开发与 M11 production-beta 发布验收分别维护。自动化构建门禁和 M10 代码加固基线
已经完成，完整的实体设备验收矩阵仍待补齐，不作为新功能开发的前置条件。剩余发布证据请参阅
[持续开发计划](docs/release/CONTINUATION_PLAN_ZH.md)、
[M11 发布清单](docs/release/M11_RELEASE_CHECKLIST.md) 和
[M10 稳定性矩阵](docs/testing/M10_STABILITY_MATRIX.md)。

一次短时 Android 16 真机冒烟测试已经验证：密码保护服务器连接、主动断开与重连、
频道和用户加载、前台服务与通知，以及英文和简体中文资源。原生设备测试套件也已
通过 Opus、RNNoise、音频焦点和路由选择检查。这些结果不替代仍待完成的长时间运行、
实体 Bluetooth/热插拔、密码频道恢复和第二台 Android 设备测试。

本应用仍属于预发布软件。在重要服务器上测试时，请保留其他可用客户端。

## 下载与安装

当前版本为 **0.11.1-m10**，请从[最新 Release](https://github.com/monet4070/ts3-mobile/releases/latest)下载：

- APK：`ts3-mobile-0.11.1-m10-release.apk`（约 14 MB）
- Android 8.0（API 26）及以上，包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`
- SHA-256 校验值见 Release 说明和 `SHA256SUMS.txt`
- 已有 M10 安装可直接覆盖安装，身份和设置会保留

APK 使用 M10 开发签名证书，不是正式生产签名。将来切换到生产签名时可能需要先卸载
当前版本。在重要服务器上请保留其他可用客户端。

应用目前不会自动检查更新。请关注 [Releases 页面](https://github.com/monet4070/ts3-mobile/releases)，
或在 GitHub 上选择 **Watch → Custom → Releases**。

如果你是从 **v0.1** 的 Release 页面进来的：那是 2026 年 8 月的第一个实验版本，
早于服务器密码认证支持，也没有 0.11.1-m10 的身份读取修复，请改用最新版本。如果因为
签名不一致无法覆盖安装，需要先卸载旧版本；卸载也会删除本地保存的 TeamSpeak 身份。

## 功能

界面采用 TailSync 风格的暖米白主题，以奶油色卡片与陶土橙作为视觉层次，使用 Lucide 精细线性图标。

### 连接与会话

- 使用 Android Keystore 加密存储 TeamSpeak 身份，采用 AES-GCM
- 前台 UDP 连接服务，并提供通知栏断开操作
- 服务器地址、端口、TeamSpeak 服务器密码和昵称输入，重启后从本地加密快照恢复
- 保存最近十个成功连接的地址与端口，启动预填最近地址，支持选择和逐条删除
- 使用 TeamSpeak Base64(SHA1) 表示进行服务器密码认证
- 连接、取消、断开和错误状态
- 支持可取消的自动重连，网络恢复后的退避时间为 1–30 秒
- 收到 Android 任务移除回调时主动退出会话，协议退出预算为两秒
- 收到同一身份连接数量限制后停止自动重试，提示等待后手动重连
- 跟踪默认网络身份和应用网络阻止状态，网络切换后进行有时限的协议控制响应检查
- 拒绝过期会话回调，并在内存中恢复上一次频道
- 层级频道视图和直接频道成员列表
- 单击展开频道，双击加入频道
- 支持公开频道和密码保护频道切换，并显示当前频道

### 收藏、状态与频道查找（开发工作区）

- 更多选项 → 服务器收藏：新增、编辑、删除最多 50 项，保存名称、昵称和默认频道 ID
- 可选记住服务器/默认频道密码；收藏整体独立 AES-GCM 加密，排除备份和设备迁移
- 支持填入表单和快捷连接；切换前先使用现有“断开”结束当前服务器连接
- 更多选项 → 暂离与状态：设置暂离留言，显示服务端对麦克风/收听静音状态的同步结果
- 频道行的信息按钮：查看主题、纯文本描述、当前人数和人数上限
- 更多选项 → 查找频道与用户：搜索当前已加载的频道名/主题、用户昵称，并加入目标频道

收藏不会自动从历史或表单搬运密码；关闭“记住密码”清除该收藏密码，独立表单记忆仍保留。
松开按住说话或音量门控安静时，不会反复切换服务端输入静音。当前只做离线验证，真实服务器
状态互通和频道详情权限待验，见[交付记录](docs/testing/FEATURES_134_2026-10-23.md)。
后续[工作区质量审查](docs/testing/REVIEW_2026-10-24.md)记录会话替换、恢复频道后的聊天、
权限同步和同身份多客户端列表的修复与回归结果。
用户最新选择的范围仅为收藏、状态和频道查找；身份导入导出、好友/屏蔽、管理操作、连接质量面板
和文件传输不在当前范围内。

### 语音与音频

- 使用官方 libopus 支持 OPUS_VOICE 和 OPUS_MUSIC 接收
- 每位说话者独立的抖动缓冲、丢包隐藏和 PCM 混音
- 前台播放、扬声器静音、用户静音和每位用户 0–200% 播放增益
- 麦克风关闭、按住说话、常开和按音量阈值自动发送模式
- 48 kHz 单声道、20 ms、64 kbps Opus 语音编码和 ts3j 语音发送
- 受限 VBR、fullband Opus、声学回声消除和 RNNoise v0.2
- 0–200% 麦克风输入音量、可开关的 10 ms RNNoise 降噪及设备回声消除
- 可调音量门控，包含有界起声缓存、收尾延迟及监听/发送状态
- 可控时关闭 Android AGC 和系统降噪，显示设备实际回声消除状态
- 有界采集和编码帧队列，避免延迟无限增长
- 采集期间保留麦克风前台服务，包含门控静音监听

麦克风参数入口：更多选项 → 麦克风设置。设置保留到语音服务停止；服务重建恢复
100% 音量、降噪/回声消除开启和按住说话模式。音量门控的初始阈值未经真实环境调校，
回声消除实际效果仍需真机验证。[实现与离线验证](docs/testing/MICROPHONE_2026-10-23.md)。

- 支持听筒、扬声器、有线、Bluetooth 和 USB 通信设备选择
- 所选设备断开时自动回退到系统路由

[音频质量升级方案](docs/plans/AUDIO_QUALITY_UPGRADE_2026-10-24_ZH.md)已开始分阶段实施：
A0 建立基线与离线 fixture；A1 保留短时缺包期间的输出，按实际写入计数并等待尾音播放完毕，
明确停止仍立即丢弃。见[离线验证与待验收项](docs/testing/AUDIO_A0_A1_2026-10-24.md)。
A2 加入浮点增益、20 ms 音量斜坡和 -1 dBFS 限幅；混音保留单人单位增益，
多人先相加再总线限幅，取消按路数切换音量。见 [A2 实现与验证](docs/testing/AUDIO_A2_2026-10-24.md)。
限幅参数仍待真机验收。A3 提供按开发命令构建的 standalone APM 对照原型、
有界播放参考和合成 AEC/NS 验证；默认包不包含 APM 原生库。见 [A3 构建与证据](docs/testing/AUDIO_A3_2026-10-24.md)。
Opus 已采用固定 SHA-256 的 1.6.1 源码构建，保留现有编码参数并加入新旧单/双声道、DTX 兼容 fixture，
见 [A4a 验证](docs/testing/AUDIO_A4A_2026-10-24.md)。A4b 加入全局输入 60 ms 年龄限制、
每人跨队列共享 240 ms 媒体预算及 40–120 ms 自适应起播缓冲；FEC 需已确认帧长和实际冗余，
未知 TS3 帧长仍使用有界 PLC。见 [A4b 实现与轨迹](docs/testing/AUDIO_A4B_2026-10-24.md)。
APM 真机采集接入与实际路由验收仍待完成，
采集默认值、权限与协议格式未变。

### 界面与诊断

- 默认使用英文；中文系统或应用语言环境会自动选择简体中文
- 服务状态、通知、音频路由和错误消息均已本地化
- “更多选项”菜单中提供诊断和后台语音检查，减少主界面干扰
- 服务器信息右侧显示带强调底色的“断开连接”按钮
- 后台语音运行检查、Android 电池优化入口、系统应用设置入口，以及可选的会话 CPU 唤醒锁申请（系统或厂商省电策略可能禁用该申请）
- 最多 128 条脱敏本地事件及 Android 进程退出原因，重启后仍可导出
- 后台重连先恢复收听；已停止的麦克风采集在回到应用后恢复
- 脱敏 JSON 导出，不包含服务器、密码、昵称、频道或用户数据
- 基于 fixture 的协议映射回归测试和脱敏失败日志

当前没有应用内语言切换按钮。也尚未实现 Whisper 发送、自动语音激活、
文件传输、权限管理和多服务器标签页。

## 频道聊天和私聊（开发工作区）

音频复审的资源生命周期、失败提示与设置连续性修复见[补充方案](docs/plans/AUDIO_REVIEW_FOLLOWUP_2026-10-24_ZH.md)。真机声学和功耗仍待 A5 验收。

开发代码现已接入“频道 / 用户 / 聊天”，支持纯文本收发、发送状态、未读、长按复制和新消息入口。
在“用户”页打开某个用户的操作菜单，选择“发送私信”；聊天页顶部可切换频道和私聊。
同一菜单提供“戳一下”，可附最多 100 UTF-8 字节的留言；收到后显示应用内弹窗，并保留在对应私聊中。
戳一下同样核对当前目标身份，发送结果未知时不会自动重试，不新增声音、振动或后台文字通知。
各会话分别保留草稿、未读和滚动位置。同名用户显示当前客户端编号；目标离线、身份未确认或映射冲突时暂停发送。
最多 20 个会话共用 500 条 / 2 MiB 消息预算；关闭私聊可清除该会话内容并释放名额。
单条输入最多 1,024 UTF-8 字节，同时只处理一次发送任务。
普通重连保留消息与草稿并暂停发送；手动退出、新手动连接和终态结束清空。
输入焦点和页签切换释放 PTT，键盘展开时保留紧凑语音控制。结果未知时不会自动重发。
聊天内容不落盘；用户主动复制只把所选正文写入系统剪贴板。

此能力尚未发布到 0.11.1-m10；频道聊天已通过 Android 16 独立 Debug 包真实收发，
私聊和 Release 文字互通仍待实测。详见[私聊交付记录](docs/testing/F1B_PRIVATE_CHAT_2026-10-23.md)
与[真机记录](docs/testing/F1A_DEVICE_2026-10-23.md)。收藏和语音激活仍在后续计划中。
戳一下的实现和离线验证见[独立交付记录](docs/testing/POKE_2026-10-23.md)，真实互通尚未实测。
详见[本轮交付与离线验证](docs/testing/F1A_CHANNEL_CHAT_2026-10-23.md)及[ADR-0013](docs/decisions/0013-bounded-channel-text-chat.md)。

## 从最近任务列表移除应用

从最近任务列表移除应用时，若 Android 交付回调，应用会提交断开请求。
按 Home、切换应用或锁屏继续保持既有后台会话。退出结果分别记录请求发送与
服务器确认；网络不可用时，服务器可能仍需等待旧会话超时。强行停止或厂商清理
可能没有任何回调。重新打开应用不会自行连接。

在本次实测的小米 Android 16 手机上，桌面左右划掉任务会先杀死进程，再向新建服务
交付回调；新服务无法使用已丢失的连接通知服务器退出。省电策略“无限制”和最近任务
加锁均未改变这个顺序。要获得服务器确认的正常退出，请先使用应用内或通知栏的断开
按钮。详见[真机报告](docs/testing/TASK_REMOVAL_DEVICE_2026-10-17.md)。

收到已识别的同一身份连接数量限制后，应用停止自动重试，解释旧连接暂留和
其他客户端占用两种可能。建议至少等待 60 秒，再手动点击“重新连接”；这只是
等待建议，服务器实际清理可能更久，反复连接可能触发临时封禁。普通网络中断
仍保留自动恢复。使用应用内或通知栏按钮断开后再划掉应用。完整提示随表单
滚动，不截断。详见 [ADR-0011](docs/decisions/0011-manual-refusal-and-form-memory.md)。

当前实现已有本地回归和 Debug 构建验证。回调是否交付、实际麦克风停止耗时和
服务器释放仍待实机验收，详见[复核记录与验收矩阵](docs/testing/TASK_REMOVAL_DISCONNECT_REVIEW_2026-10-13.md)。

## 隐私

项目不包含分析、广告、遥测、崩溃上报 SDK 或项目自建后端。应用只直接连接用户
选择的服务器。TeamSpeak 身份使用 Android Keystore 在本地加密。成功连接的
服务器地址与端口最多保存十条到本地；上次表单的地址、端口、昵称和服务器密码
另用 AES-GCM 与 Android Keystore 加密保存，并排除 Android 备份。编辑后短暂延迟
保存，点击连接会等待保存尝试。用于恢复频道的密码只保留在前台服务内存中。
本地诊断最多保留 128 条
固定事件类别和数字系统状态，不包含连接信息或音频。

完整数据处理说明请参阅 [PRIVACY.md](PRIVACY.md)。

## 架构

- `app`：Compose UI、Android 生命周期、前台服务和身份保险库
- `ts3-protocol`：仅 JVM 的 ts3j 门面、模型、会话代次和频道排序
- `audio-opus`：JNI libopus 编解码、采集、降噪、抖动缓冲、混音和 Android 音频 I/O

Android 界面层依赖协议和音频适配器；JVM 协议模块不依赖 Android。服务状态以不可变
`StateFlow` 数据暴露给 Compose，用户可见消息在 Android 边界统一解析。详细边界和
设计理由请参阅 [ARCHITECTURE.md](ARCHITECTURE.md) 以及
[`docs/decisions`](docs/decisions/) 下的决策记录。

兼容层通过 JitPack 固定使用 ts3j 提交
`db57d60c989e399626aa16d921390f5033e6cdeb`。每个模块的依赖图都以 STRICT 模式锁定
在已提交的 `gradle.lockfile` 中，ts3j 的传递依赖还通过显式 Gradle 约束限制版本。
升级依赖时必须使用 `--write-locks` 构建，并审查和提交 lockfile 差异。稳定产品发布
前仍需要维护 ts3j fork，详见
[ADR-0004](docs/decisions/0004-take-ownership-of-ts3j-dependency.md)。

应用从 SHA-256 固定的官方源码包构建 BSD 许可证的 Xiph libopus 1.6.1，并在提交
`904a876d` 处引入官方 Xiph RNNoise v0.2 模型。许可证文本和完整来源记录见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 构建与验证

要求：

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1

在未跟踪的 `local.properties` 中设置 `sdk.dir`，然后检查本地环境：

```powershell
.\tools\check-build-environment.ps1
```

运行完整自动化门禁：

```powershell
.\gradlew.bat quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace
.\tools\check-repository-privacy.ps1
```

启动 Android 模拟器或连接已授权设备后，检查开启混淆的 Release APK 是否能读取身份
（需要 JDK 17、PowerShell 7；设置 `ANDROID_HOME` 或通过 `-SdkRoot` 指定 SDK 路径）：

```powershell
.\tools\check-release-identity.ps1 -DeviceSerial emulator-5554
```

设备序列号以 `adb devices` 的结果为准。检查直接加载 APK，无需安装应用，全程离线
验证临时身份的重复读取及 INI 解析、写入。探针单独编译，不会影响 R8 的可达性分析。
日志保存在 `build/release-identity-smoke/`，详见 [Issue #3 修复报告](docs/testing/ISSUE_3_RELEASE_IDENTITY_FIX.md)。

连接已授权的实体 Android 设备后，运行原生设备测试套件：

```powershell
.\gradlew.bat :audio-opus:connectedDebugAndroidTest
```

显式开启的麦克风信号对比方法与本次真机结果，见
[音质自动诊断记录](docs/testing/AUDIO_QUALITY_DIAGNOSIS_2026-10-13.md)。

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 会在每个
Pull Request 和推送到 `main` 时运行隐私扫描、格式检查、JVM/单元测试、Android Lint、
Debug/Release 构建以及混淆后的身份读取检查。

## Android 支持

APK 仍可安装在 Android 8.0（API 26）及以上版本。Beta 质量目标为 Android 10 及以上；
Android 8/9 会在可行时获得兼容性修复，但不属于主要实体设备矩阵。

## 贡献与安全

提交修改前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，并遵守
[行为准则](CODE_OF_CONDUCT.md)。发现疑似漏洞时不要创建公开 Issue，请按照
[SECURITY.md](SECURITY.md) 中的流程报告。

感谢 [Rupert Polley（@polleyr）](https://github.com/polleyr) 在
[PR #1](https://github.com/monet4070/ts3-mobile/pull/1) 中报告并修复 TeamSpeak
服务器密码认证问题，也感谢他提供英文本地化工作的参考实现。

里程碑历史和未发布改动请参阅 [CHANGELOG.md](CHANGELOG.md)。

## 许可证

TS3 Mobile 使用 [Apache License 2.0](LICENSE)。其他项目组件仍遵循各自的许可证，
详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
