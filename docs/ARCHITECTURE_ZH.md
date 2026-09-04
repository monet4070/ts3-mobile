# TS3 Mobile 系统架构设计文档 (中文版)

---

## 1. 系统概述

**TS3 Mobile** 是一个基于 [Manevolent/ts3j](https://github.com/Manevolent/ts3j) 完整客户端协议构建的开源、高性能 Android 平台 TeamSpeak 3 客户端。

### 核心设计目标
- **超低延迟与高保真**：基于官方 libopus 与 RNNoise 深度学习降噪实现全双工语音通信。
- **健壮的协议状态机**：基于单调递增世代令牌门禁（Generational Token Gate），彻底杜绝异步 UDP 延迟回调污染 UI 状态。
- **严格的分层依赖隔离**：遵循 `interface -> application -> domain -> infrastructure` 清晰边界，确保协议与业务层可在纯 JVM 环境下进行毫秒级自动化单测。
- **零隐私侵入**：TeamSpeak 身份私钥依托 Android Keystore 硬件级 AES-GCM 加密存储，无任何外部遥测、广告或追踪 SDK。

---

## 2. 整体架构与模块边界

![整体架构图](images/01_overall_architecture.png)

### 模块职责划分表

| 模块 | 层次划分 | 技术选型 | 核心职责 |
| :--- | :--- | :--- | :--- |
| **`app`** | Android 应用与 UI 层 | Jetpack Compose, Coroutines/StateFlow, Android Keystore, Foreground Service | UI 渲染与交互、ViewModel 状态分发、前台服务生命周期编排、Keystore 硬件加密身份库、网络感知重连与频道现场恢复。 |
| **`ts3-protocol`** | 纯 JVM 领域与适配层 | Kotlin JVM, ts3j 协议库, Coroutines | 协议门面 Facade 封装、单调世代回调过滤、频道树拓扑算法、日志隐私脱敏（纯 JVM 实现，完全不依赖 Android 框架）。 |
| **`audio-opus`** | 音频基础设施与本地层 | C++17, CMake, NDK, libopus, RNNoise, AudioTrack/AudioRecord | Native Opus 编解码、10ms 神经网络降噪、发话人独立抖动缓冲（Jitter Buffer）、丢包补偿（PLC）、多路 PCM 混音与通信硬件路由。 |

---

## 3. 核心子系统深度解析

### 3.1 协议与并发时序管理

![协议时序图](images/02_protocol_sequence.png)

- **世代控制门禁 (`SessionGenerationGate`)**：每次发起新连接均生成单调递增的 Epoch 世代令牌。所有异步 UDP 回调（如服务器踢出、断开连接、频道修改）在修改状态快照前，必须通过 `withCurrent(token)` 比对。若属于已废弃的旧连接，则自动静默丢弃，杜绝时序竞争导致的状态错乱。
- **连接状态原子门禁 (`ConnectionAttemptGate`)**：原子化协调握手超时、网络异常与 `CONNECTED` 成功事件的触发顺序，防止并发场景下连接失败状态被延迟的成功通知覆盖。
- **频道树拓扑算法 (`ChannelTree`)**：TeamSpeak 服务端通过 `orderAfterId`（同级单向链表）组织频道结构。算法使用链表重建与递归深度遍历，自动修复环形引用与乱序断链，并输出扁平化列表供 UI 高效渲染。

---

### 3.2 双引擎低延迟音频处理管线

![双引擎音频处理管线图](images/03_audio_pipeline.png)

1. **Opus 核心编码配置**：
   - 48 kHz 采样率、单声道、20 ms 帧长（960 个采样点）、64 kbps 目标码率；
   - 启用 `OPUS_APPLICATION_VOIP` 模式、Fullband 全频带、最高复杂度 10、约束型动态码率（Constrained VBR）。
2. **深度学习降噪 (RNNoise)**：
   - 源码级集成官方 Xiph RNNoise v0.2 模型，使用 C99/C++17 编译优化（`-O3`）；
   - 以 10 ms（480 采样点）为窗口原地降噪，并实时计算语音活动（VAD）概率，彻底过滤环境底噪。
3. **有界队列防延迟堆积**：
   - 采集端编码输出队列严格限制为 3 帧（60 ms）。若 UDP 发送拥塞，主动丢弃最旧帧，从根源上杜绝麦克风延迟持续累加。
4. **独立 Jitter Buffer 与丢包补偿 (PLC)**：
   - 为每个远程说话者独立维护缓冲队列与 `TalkerState`，首帧到达后延迟 60 ms 播放以平滑网络抖动；
   - 使用 `PacketSequenceUnwrapper` 处理 16 位包序号回环；
   - 检测到丢包 ≤3 帧时，通过 `opus_decode(null)` 触发 libopus 隐式波形外推丢包补偿，防止声音产生断续爆音。
5. **多路 PCM 软混音与音量增益**：
   - 每 10 ms 混音滴答（480 样本）对所有说话者进行 PCM 饱和加法混合；
   - 支持为每个成员独立调节 0%~200% 增益，内置防溢出截断（`clamp to [-32768, 32767]`）。
6. **通信硬件路由 (`AudioDeviceRouter`)**：
   - 针对 Android 12+ (API 31) 接入标准 `setCommunicationDevice` 规范；
   - 兼容旧版本 SCO 蓝牙与听筒/外放路由，具备设备断开自动回退与系统热插拔监听机制。

---

### 3.3 Android 生命周期、安全性与前台服务

![生命周期与安全图](images/04_lifecycle_security.png)

- **硬件级身份保管库 (`IdentityVault.kt`)**：TeamSpeak 客户端身份私钥由 Android Keystore 硬件生成的 AES-256 密钥保护，采用 AES-GCM 加密存储于 DataStore，不落地明文，不向外上传。
- **Android 14+ 前台服务合规**：常驻连接时仅声明 `mediaPlayback | dataSync` 类型；仅在用户真正按下 PTT 发话或开启持续推流时，才动态附加 `microphone` 服务类型，严格遵循 Google Play 最新隐私规范。
- **网络感知智能重连 (`ReconnectPolicy.kt`)**：实时监听 `ConnectivityManager.NetworkCallback`。设备离线时挂起重连计时器，检测到网络恢复时以 1~30 秒指数退避立即触发重连。
- **频道现场记忆与恢复 (`ChannelRestorePolicy.kt`)**：在服务内存中暂存用户断线前所在的频道 ID 与密码，重连成功后全自动切回原频道。

---

## 4. UI 架构与单向数据流 (MVI)

![UI架构单向数据流图](images/05_ui_mvi.png)

- **单向数据流**：`TeamSpeakService` 为唯一可信状态源，通过只读 `StateFlow` 发布不可变状态；UI 组件只能读取状态，并通过 Binder 发送操作指令。
- **细粒度组件解耦**：界面严格拆分为独立的面板文件，每个组件代码文件严格控制在 300 行以内。

---

## 5. 工程质量门禁与未来演进

```text
构建与质量门禁流程:
1. KtLint 代码风格规范检查 (:quality)
2. JVM 纯协议与算法单元测试 (:ts3-protocol:test, :audio-opus:testDebugUnitTest, :app:testDebugUnitTest)
3. Android Lint 静态分析 (:app:lintDebug, :audio-opus:lintDebug)
4. CMake & NDK 本地共享库编译 (libts3opus_jni.so)
5. 物理真机稳定性验收测试 (:audio-opus:connectedDebugAndroidTest)
```

| 维度 | 当前技术基线 (M10/M11) | 未来规划演进 (Roadmap) |
| :--- | :--- | :--- |
| **服务器会话** | 单服务器稳定全双工连接 | 多服务器标签页与并发会话多路复用 |
| **语音通信** | 48kHz Opus + RNNoise + 有界队列 | 自适应网络码率调整与基于 VAD 的自动声控激活 |
| **频道与数据** | 拓扑重排与断线自动切回原频道 | 服务器书签收藏夹加密持久化与权限管理 |
| **文字与扩展** | 聚焦低延迟双工语音通信 | 文本聊天、耳语（Whisper）与文件传输协议 |

