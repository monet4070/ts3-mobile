# TS3 Mobile

[![Android CI](https://github.com/monet4070/ts3-mobile/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/monet4070/ts3-mobile/actions/workflows/android.yml)

English documentation: [README.md](README.md)

TS3 Mobile 是一个实验性的开源 Android TeamSpeak 3 客户端，基于
[Manevolent/ts3j](https://github.com/Manevolent/ts3j) 提供的完整客户端协议构建。

本项目是非官方社区项目，与 TeamSpeak Systems GmbH 没有隶属、认可或赞助关系。
TeamSpeak 及相关名称和商标归其各自所有者所有。

## 项目状态

当前开发重点是 M11 production-beta 准备阶段。自动化构建门禁和 M10 代码加固基线
已经完成，但完整的实体设备验收矩阵尚未完成。剩余发布证据请参阅
[M11 发布清单](docs/release/M11_RELEASE_CHECKLIST.md) 和
[M10 稳定性矩阵](docs/testing/M10_STABILITY_MATRIX.md)。

一次短时 Android 16 真机冒烟测试已经验证：密码保护服务器连接、主动断开与重连、
频道和用户加载、前台服务与通知，以及英文和简体中文资源。原生设备测试套件也已
通过 Opus、RNNoise、音频焦点和路由选择检查。这些结果不替代仍待完成的长时间运行、
实体 Bluetooth/热插拔、密码频道恢复和第二台 Android 设备测试。

本应用仍属于预发布软件。在重要服务器上测试时，请保留其他可用客户端。

## 功能

### 连接与会话

- 使用 Android Keystore 加密存储 TeamSpeak 身份，采用 AES-GCM
- 前台 UDP 连接服务，并提供通知栏断开操作
- 服务器地址、端口、TeamSpeak 服务器密码和昵称输入
- 使用 TeamSpeak Base64(SHA1) 表示进行服务器密码认证
- 连接、取消、断开和错误状态
- 支持可取消的自动重连，网络恢复后的退避时间为 1–30 秒
- 拒绝过期会话回调，并在内存中恢复上一次频道
- 层级频道视图和直接频道成员列表
- 单击展开频道，双击加入频道
- 支持公开频道和密码保护频道切换，并显示当前频道

### 语音与音频

- 使用官方 libopus 支持 OPUS_VOICE 和 OPUS_MUSIC 接收
- 每位说话者独立的抖动缓冲、丢包隐藏和 PCM 混音
- 前台播放、扬声器静音、用户静音和每位用户 0–200% 播放增益
- 麦克风关闭、按住说话和持续发送模式
- 48 kHz 单声道、20 ms、64 kbps Opus 语音编码和 ts3j 语音发送
- 受限 VBR、fullband Opus、声学回声消除和 RNNoise v0.2
- 始终启用的 10 ms 神经降噪，并禁用 Android 自动增益控制
- 有界采集和编码帧队列，避免延迟无限增长
- 仅在发送语音期间启用麦克风前台服务
- 支持听筒、扬声器、有线、Bluetooth 和 USB 通信设备选择
- 所选设备断开时自动回退到系统路由

### 界面与诊断

- 默认使用英文；中文系统或应用语言环境会自动选择简体中文
- 服务状态、通知、音频路由和错误消息均已本地化
- 应用内诊断页，记录线程安全的连接和音频计数器
- 脱敏 JSON 导出，不包含服务器、密码、昵称、频道或用户数据
- 基于 fixture 的协议映射回归测试和脱敏失败日志

当前没有应用内语言切换按钮。也尚未实现 Whisper 发送、自动语音激活、文字聊天、
文件传输、权限管理、书签和多服务器标签页。

## 隐私

项目不包含分析、广告、遥测、崩溃上报 SDK 或项目自建后端。应用只直接连接用户
选择的服务器。TeamSpeak 身份使用 Android Keystore 在本地加密。连接流程需要的
连接信息只保存在应用或服务内存中，不会主动持久化。用于恢复频道的密码也只保留
在前台服务内存中。

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

应用使用 BSD 许可证的 Xiph libopus 1.3.1 Prefab 包，并在提交
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
.\gradlew.bat quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
.\tools\check-repository-privacy.ps1
```

连接已授权的实体 Android 设备后，运行原生设备测试套件：

```powershell
.\gradlew.bat :audio-opus:connectedDebugAndroidTest
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 会在每个
Pull Request 和推送到 `main` 时运行隐私扫描、格式检查、JVM/单元测试、Android Lint
和 Debug 构建。

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
