# TS3-Mobile 全量工程级代码审计报告

> **审计对象**：`TS3-mobile`（分支 `codex/release-1.3.0`，Commit `26dec7b7feac54e88c4f215e73074dea626b6b6b`，版本 `1.3.0` / `versionCode = 17`）  
> **审计日期**：2026-11-26  
> **审计性质**：独立只读全量代码、架构、音频实时链路、协议集成、并发生命周期与对抗性验证审计（不修改仓库源码/配置/测试，未接入真实线上服务器）

---

## 1. Executive Summary

`TS3-mobile` 是一个工程纪律显著高于同类社区 Android 语音客户端的原生 Kotlin + C/C++（JNI）项目：它围绕三层模块边界（`app` / `audio-opus` / `ts3-protocol`）沉淀了明确的架构决策记录（ADR）、严格的隐私脱敏策略、Keystore 加密与双副本原子回滚的身份存储、带 AAD 与高迭代 PBKDF2 的便携备份容器，以及高达 **3.6 万余行离线单测与测试资产**（939 项 JVM 单测全通过，6 项显式本地实服探针按设计跳过）。

然而，围绕 `1.3.0` 候选版本的深度对抗审计显示：**核心会话控制与音频/传输边界仍存在 4 项 P1 级（可导致主线程崩溃、假连接僵死、用户麦克风配置回退、1/256 握手异常且永久断重连）与 8 项 P2 级（标准 TS3 服务端文件传输失败、蓝牙延迟路由后 `AudioTrack` 永久泄漏、60 ms 队列过期引发级联重置、10 ms 热路径设备枚举 IPC、退出超时绕过互斥锁、主线程麦测阻塞等）已确认缺陷**，且受限于缺少物理真机与模拟器实时运行环境，**多机种蓝牙/听筒/ROM 后台与长时间多人混音稳定性尚处于未验证状态**。

| 分类 | 数量 | 明细 |
| --- | ---: | --- |
| **已确认缺陷总数（Confirmed）** | **15** | **0 P0 / 4 P1 / 8 P2 / 3 P3**（其中 **8 项已通过独立 `/tmp` 测试程序复现验证**，**7 项有完整静态/字节码证据链**） |
| **待验证风险（Probable / Unverified）** | **5** | 涵盖 FGS 超时边缘竞态、ADR-0039 听筒/LE Audio 实机行为、Doze/无 `WifiLock` 长期功耗、真机 ROM 与高并发混音、密码频道文件参数 |
| **对抗证伪（Disproved）** | **6** | 对最初怀疑的 AB-BA 死锁、系统广播 `RECEIVER_NOT_EXPORTED`、悬浮 PTT 采集泄漏、主线程 `close()` ANR、日志泄露、10–30 ms 唤醒丢帧逐一证伪 |

**发布就绪性总判断：`Conditionally Ready`（有条件发布——当前状态下不建议直接面向大众公开打标发布 `1.3.0`，需先关闭 Stage A 的 4 项 P1 缺陷、核心文件/蓝牙 P2 项，并补齐文档要求的物理机实测门禁）。**

---

## 2. 审计基线与覆盖范围

### 2.1 基线信息与构建配置

- **Git 分支与提交**：`codex/release-1.3.0` @ `26dec7b7feac54e88c4f215e73074dea626b6b6b`
- **工作区状态**：审计开始前与复现收尾后 `git status --short` 均为空（最终仅新增本报告文档 [TS3_MOBILE_CODE_AUDIT_REPORT.md](file:///Users/monet/Documents/TS3-mobile/TS3_MOBILE_CODE_AUDIT_REPORT.md)，未改写任何项目源码/配置/测试）
- **应用标识与目标平台**（[app/build.gradle.kts](file:///Users/monet/Documents/TS3-mobile/app/build.gradle.kts#L8-L37)）：`applicationId = "io.github.ts3mobile.app"`，`versionCode = 17`，`versionName = "1.3.0"`，`minSdk = 26`，`compileSdk = 35`，`targetSdk = 35`，Release 启用 `isMinifyEnabled = true`
- **工具链与关键依赖**（[gradle/libs.versions.toml](file:///Users/monet/Documents/TS3-mobile/gradle/libs.versions.toml)、[audio-opus/build.gradle.kts](file:///Users/monet/Documents/TS3-mobile/audio-opus/build.gradle.kts)、[ts3-protocol/build.gradle.kts](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/build.gradle.kts)）：
  - AGP `8.7.0` / Kotlin `2.0.0` / Coroutines `1.9.0` / Compose BOM `2024.10.01` / DataStore `1.1.1` / Markwon `4.6.2` / ktlint Gradle `12.1.2`
  - NDK `27.0.12077973` / CMake `3.22.1` / Opus `1.6.1`（源码 SHA-256 校验构建） / RNNoise（vendored C）
  - 协议库：`com.github.monet4070:ts3j:ts3mobile-1`（Strict Gradle Dependency Locking，传递依赖约束：`bcprov-jdk15on:1.67`、`commons-lang:2.6`、`dnsjava:2.1.8`、`ini4j:0.5.1`）
- **实验开关默认值**：`channel_files_probe_enabled = true`（Debug/Release 默认开启）、`desktop_identity_import_enabled` Debug 为 `true` / Release 默认 `false`、`bluetooth_standard_output_experiment` 默认 `false`

### 2.2 全量代码与资产盘点

| 目录 / 范围 | 文件数 | 代码行数（实测） | 审阅深度 |
| --- | ---: | ---: | --- |
| `ts3-protocol/src/main`（`.kt`） | 40 | 3,920 | **100% 逐行精读** |
| `audio-opus/src/main`（`.kt`） | 56 | 4,739 | **100% 逐行精读** |
| `audio-opus/src/main/cpp`（一方 `.cpp`/`.h`/`.cmake`） | 5 | 548 | **100% 逐行精读** |
| `app/src/main/java`（`.kt`） | 239 | 24,561 | **全量关键链路精读 + 辅助 UI/数据流全覆盖排查** |
| `app/src/debug/java`（`.kt`） | 5 | 662 | **100% 边界核查（含 `FloatingPttProbeService`）** |
| `tools/`（`.py` + `ts3j-patches/`） | 13 + 补丁 | 1,461+ | **构建/签名/补丁脚本重点精读 + 全单测运行** |
| JVM 单元测试（`*/src/test`） | 169 | 21,513 | **全量运行 + 核心用例对照精读** |
| 仪器测试（`*/src/androidTest`） | 118 | 14,481 | **静态检索与覆盖映射（未在真机运行）** |
| Python 工具测试（`tools/tests/`） | 10 | 461 | **100% 运行通过** |
| 外部依赖 `ts3j-ts3mobile-1.jar` | 关键协议类 | — | **使用 JDK 17 `javap -c -p` 与 `jshell` 字节码级核验** |

### 2.3 未审阅 / 排除项与环境限制

1. **第三方 `third_party/rnnoise` 内部 C 源码与 Opus 1.6.1 源码内部实现**：未做逐行 DSP 算法审计，仅审阅了 CMake 编译参数与一方 JNI 封装边界（[rnnoise_jni.cpp](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/cpp/rnnoise_jni.cpp)、`opus_jni.cpp`、`opus_receive_jni.cpp`）。
2. **Android 真机 / 模拟器缺失**：本机无已连接的物理 Android 设备或运行中的模拟器，因此 **118 项 `androidTest`**、真机 BLE/A2DP/LE Audio HAL 行为、厂商后台杀进程策略（MIUI/ColorOS/OneUI/EMUI）、真实屏幕闪烁与长时功耗发热**均未实测**，并在报告第 5、7、9 章如实标明置信度边界。
3. **禁止连接真实生产服务器**：严格遵守只读与隔离原则，不连接线上 TS3 服务器；6 项需显式 `-Pts3LocalFileProbePort` 挂载本地 TS3 实例的 JVM 探针用例在标准构建中按预期跳过。

### 2.4 已执行命令与实测结果

| 命令 | 结果 | 关键实测输出 |
| --- | --- | --- |
| `./gradlew --offline :ts3-protocol:test :audio-opus:testDebugUnitTest :app:testDebugUnitTest :audio-opus:lintDebug :app:lintDebug :app:lintRelease --rerun-tasks --console=plain` | **通过（exit 0，1m）** | 161 actionable tasks 全量重跑；四 ABI（`arm64-v8a`/`armeabi-v7a`/`x86`/`x86_64`）Native CMake 编译通过；JVM 测试合计 **945 项：939 通过、6 跳过、0 失败**（`ts3-protocol` 163/169 in 4.25s、`audio-opus` 257/257 in 6.34s、`app` 519/519 in 18.19s）。 |
| Lint XML 汇总（同上任务生成） | **0 Error** | `audio-opus:lintDebug` 0 条；`app:lintRelease` 9 条（8 Warning + 1 Info）；`app:lintDebug` 11 条（较 Release 多 `FloatingPttProbeService.kt:141` `InlinedApi` 与 1 条 `PluralsCandidate`）。另测试编译有 1 条 Kotlin 警告：`SessionRecoveryRaceTest.kt:157:9` 继承不稳定协程 API。 |
| `./gradlew --offline quality --console=plain` | **通过（exit 0）** | `:ts3-protocol:ktlintCheck`、`:audio-opus:ktlintCheck`、`:app:ktlintCheck` 全部通过（29 tasks up-to-date）。 |
| `python3 -m unittest discover -s tools/tests -v` | **通过（exit 0，0.342s）** | **34 / 34 通过**，涵盖 APK 签名解析、Opus 构建配置校验、TS3j 源码回退保护、USB 夹具桥接等。 |
| `/tmp/ts3_audit_repro` 独立编译运行 `AuditRepro.kt` | **8/8 复现成功** | 针对仓库已编译字节码执行 8 个最小复现场景全部命中预期故障（随后立即清理 `/tmp/ts3_audit_repro`）。 |

---

## 3. 系统架构与关键链路分析

### 3.1 模块边界与依赖方向

```
app/src/main (Android UI / ForegroundService / DataStore / Keystore)
   ├──▶ ts3-protocol/src/main (纯 JVM 协议适配层，封装 ts3j-ts3mobile-1)
   └──▶ audio-opus/src/main   (Android AudioRecord/AudioTrack/路由 + JNI libopus 1.6.1 / RNNoise)
           └──▶ ts3-protocol/src/main (仅依赖纯 JVM 语音帧模型 VoiceFrame / TimedEncodedVoiceSource)
```

架构解耦度优秀：`ts3-protocol` 不含任何 Android SDK 依赖；`audio-opus` 将核心抖动队列（`VoiceReceiveQueue`、`TalkerJitterPipeline`、`PlaybackStreams`、`PlaybackTimeline`）抽象在平台接口之后；`app` 将重状态机逻辑从 `TeamSpeakService` 抽离至 `ConnectionCoordinator`、`ReconnectEngine`、`ConnectionMonitor`、`SessionExitRunner` 与 `IdentityConnectionLease`。

### 3.2 核心状态机、世代门禁与资源所有权

1. **连接生命周期四重门禁**：
   - **进程级身份互斥（`IdentityUseGate` + `IdentityConnectionLease`）**：阻止在活动连接或退出清理结束前执行身份导入/回滚等维护操作；每个连接作业需向 `lease.prepare()` 注册。
   - **请求代际排序（`SessionRequestOwnership`）**：在主线程同步递增 `connectEpoch` 与 `exitToken`，保证“断开后立刻重连”场景下旧退出任务不会误删新连接的通知或状态。
   - **会话世代门禁（`SessionGeneration` + `sessionMutex`）**：状态更新和硬件音频启停原则上受世代检验与串行互斥锁保护（但 `AUD-001` 与 `AUD-009` 破坏了这两条不变量）。
   - **传输内部世代（`Ts3jSessionClient.generation: SessionGenerationGate` + `ConnectionAttemptGate`）**：隔离过期 Socket 回调与连接中途异常。

### 3.3 线程/协程模型与锁顺序

| 线程 / 调度器 | 主要职责 | 潜在阻塞 / 竞争点 |
| --- | --- | --- |
| **Main Thread** | Compose 渲染、`TeamSpeakService` 入口/绑定、`SessionNotifications`、`AudioDeviceCallback` / `BecomingNoisy` 回调、`MainHandler` 调度 | **`LocalMicrophoneTrial` / `MicrophoneTrial` 同步启停阻塞 `join(1500)`（`AUD-010`）**；`SessionDiagnosticWriter` 初始化同步读 32 KiB TSV（`AUD-014`）；`requestExit()` 与后台 IO 争夺 `exitLock`（`AUD-001`）。 |
| **`serviceScope` (`Dispatchers.IO`)** | `performConnectionAttempt`、重连循环、`ConnectionMonitor.watch()`（1 s）、网络探活（`verifyLiveness`）、频道/文件 TCP、加密持久化 | 文件下载与头像共用单锁 `busy` 串行阻塞。 |
| **`ts3-opus-playback` (`MAX_PRIORITY`)** | `PlaybackSession.tick()` 每 10 ms 驱动混音、解码、AEC 渲染参考、非阻塞写入 `AudioTrack` | `write()` 每 10 ms 调 `track.routedDevice` / `setVolume`（`AUD-008`）；冷建/启停 `AudioTrack` 超 60 ms 触发输入队列整客户端失效（`AUD-007`）。 |
| **`ts3-opus-capture` (`THREAD_PRIORITY_AUDIO`)** | `AudioRecord.read(READ_BLOCKING)` 480 samples (10 ms) → RNNoise 降噪 → Gate/Gain → 20 ms Opus 编码写入 `CaptureVoiceBuffer` | `publishState()` 与热路径竞争 `OpusMicrophoneCapture.lifecycleLock`。 |
| **`ts3j` 内部线程** | `NetworkReader`（UDP `receive` 入队）、`NetworkHandler`（解密/拆包/`TS3Listener` 分发）、`HighPrecisionTimer`（`TS3J AudioThread` 20 ms 轮询） | **`TS3J AudioThread` 无语音发射需求时依然常驻自旋（`AUD-011`）**。 |

**已验证的正向锁顺序不变量**：跨 `AudioDeviceRouter` 与 `OpusAudioPlayer` 的回调（`onOutputRouteChanged` / `onUnsafeOutputRoute`）均已先退出 `OpusAudioPlayer.lifecycleLock` 再回调路由器，`PlaybackSession` 调用 `prepareOutputRoute()` 时也不持有播放器锁，锁序稳定为 `Router -> Player -> Output`。

### 3.4 语音接收、自适应抖动与蓝牙防外放链路（含 10–30 ms 调度抖动分析）

1. **正常与轻度晚醒路径（10–30 ms 表现优异）**：UDP 收包经 `Ts3jSessionClient.forwardVoiceFrame` 拷贝入 `OpusAudioPlayer.submit` → `VoiceReceiveQueue`（入队前在锁外完成 TOC `NativeOpusInfo.inspect`）。`PlaybackPacer` 对 10 ms 周期设置了 `MAX_CATCH_UP_NANOS = 50 ms`（[PlaybackSession.kt#L206-L230](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L206-L230)），因此 **10–30 ms 的线程晚醒可以被无缝追赶吸收，不丢帧、不重置 `PlaybackTimeline`**。
2. **重度抖动退化断崖（>60 ms）**：若 `AudioTrack` 冷建/蓝牙建轨（`SLOW_OUTPUT_MS = 30 ms`）或系统调度卡顿超过 `INPUT_AGE_NANOS = 60 ms`，`VoiceReceiveQueue.prune()` 会以单个超时包为由调用 `budget.invalidate(clientId)`，连带废除已排入 `TalkerJitterPipeline` 甚至已解码的 PCM 缓冲（详见 `AUD-007`）。
3. **媒体流兼容与蓝牙隐私门禁（ADR-0039）**：使用 `USAGE_MEDIA + CONTENT_TYPE_SPEECH` 避免切入通话模式影响前台游戏/音乐；按发言段按需建立/排空并销毁 `AudioTrack`；`BluetoothPlaybackGuard` 要求路由实测确认为蓝牙后再放开音量 `1f`（否则静音等待最高 5 s 后强锁 `safetyMuted`）。但当蓝牙在短发言结束后才就绪时，存在永久泄漏播放中音轨的边界缺口（详见 `AUD-006`）。

### 3.5 显著工程亮点与系统性风险

- **工程亮点**：① 全仓日志零敏感信息泄露（42 处 `Log.`/`println` 全部采用闭集类别与脱敏摘要）；② `IdentityStore` 双副本 + 待确认 SHA-256 + 损坏证据保留设计极严谨；③ `IdentityBackupCodec` 采用 AES-256-GCM + AAD 头校验 + PBKDF2 600,000 次迭代 + 明确明文数组清零；④ 核心并发/网络切换逻辑（`DefaultNetworkMonitor`、`ChannelFilesDialog` 配置重建清理）均有高质量回归测试覆盖。
- **系统性隐患**：① “主状态流快照重置（`SessionExitRunner`）”与“长寿控制器局部状态（`MicrophonePreferenceController` / `AudioDiagnosticRepository`）”之间缺乏单一真相源或重置后同步契约；② 三方协议核心依赖 `ts3j-ts3mobile-1` 已显露陈旧（未合并 `0002` 握手修复、弱 RNG、20 ms 忙等自旋、EOL 传递库），需要规划独立维护或替换边界。

---

## 4. 已确认缺陷清单

> 本节**仅包含 `Confirmed — Reproduced` 与 `Confirmed — Static Proof` 缺陷**，严格按 **P0 → P1 → P2 → P3** 排列，已合并同源问题。

---

### P0 级缺陷

本次审计**未发现** P0 级（稳定必现的灾难性数据损毁、明文凭证泄露或全员不可用）已确认缺陷。

---

### P1 级缺陷

#### [AUD-001] `ConnectionCoordinator.requestExit()` 与 `finishTerminalFailure()` 竞态触发主线程 `IllegalStateException` 崩溃

- **ID**：`AUD-001`
- **标题**：`ConnectionCoordinator.requestExit()` 与 `finishTerminalFailure()` 在 `exitLock` 释放窗口交错触发主线程未捕获 `IllegalStateException`
- **严重级别**：`P1`
- **置信度**：`Confirmed — Reproduced`
- **所属模块**：`app` (`io.github.ts3mobile.app.service` / `io.github.ts3mobile.app.identity`)
- **涉及文件与行号**：
  - [ConnectionCoordinator.kt#L342-L397](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L342-L397)（`requestExit`）
  - [ConnectionCoordinator.kt#L1030-L1035](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L1030-L1035)（`finishTerminalFailure`）
  - [IdentityConnectionLease.kt#L15-L40](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/identity/IdentityConnectionLease.kt#L15-L40)（`prepare` / `retire`）
- **问题归属**：TS3-Mobile 一方代码缺陷
- **问题描述**：`requestExit()` 在未失效当前世代且退出锁已释放的窗口期内，假设第一次获取的活跃 `IdentityConnectionLease` 在二次加锁时必定尚未 `retire()`，并对其调用了非空断言 `checkNotNull(lease.prepare(...))`。当后台 `Dispatchers.IO` 线程正并发收尾一次终态连接失败并先步入 `synchronized(exitLock)` 执行 `identityLease?.retire()` 时，主线程后续 `lease.prepare()` 返回 `null`，直接在主线程抛出 `IllegalStateException` 导致进程崩溃。
- **复现路径或触发时序**：
  1. 后台 `serviceScope` 连接尝试失败（如端口关闭、不可重试异常或克隆超限）进入 `finishTerminalFailure(status, epoch)`，运行至 [L1029](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L1029) 后即将申请 `exitLock`；
  2. 用户恰好于此时点击断开 / 从近期任务划掉卡片，主线程进入 `requestExit()`；
  3. 主线程进入第一个 `synchronized(exitLock)`（L342–354），拿到活跃旧 `lease` 后**释放了 `exitLock`**，随后尚未执行 L359 `host.sessionGeneration.invalidate()`；
  4. IO 线程此刻获得 `exitLock`（L1030），检查 `host.sessionGeneration.isActive(epoch)` 为 `true`，执行 `identityLease?.retire()` 将 `lease.retiring = true`；
  5. 主线程继续执行至 L359 置失效世代，然后在 L379 重获 `exitLock`，于 L381 调用 `checkNotNull(lease.prepare(...))`——由于 `retiring == true`，`prepare()` 返回 `null`，主线程触发崩溃。
- **影响分析**：连接失败或认证/克隆拒绝瞬间，若用户同时操作断开、通知栏断开或划掉任务，将导致 `TeamSpeakService`（与绑定在同一进程的 `MainActivity`）在主线程直接崩溃，引发系统强退及本地未落盘会话状态中断。
- **根因分析**：`requestExit()` 将“读取/获取 `lease` 并推进 `ownership`”、“`sessionGeneration.invalidate()` 阻止终态收尾退租”、“在 `lease` 上注册退出任务 `prepare()`”拆成两段锁区间，且未遵守单一事务性状态迁移，导致 `lease` 的生命周期在两次加锁之间被外部 `retire()` 抢占。
- **代码证据**：
  - [ConnectionCoordinator.kt#L342-L359](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L342-L359)：先出 `synchronized(exitLock)` 后才调 `host.sessionGeneration.invalidate()`。
  - [ConnectionCoordinator.kt#L379-L388](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L379-L388)：重锁后调用 `checkNotNull(lease.prepare(host.serviceScope) { ... })`。
  - [ConnectionCoordinator.kt#L1030-L1035](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L1030-L1035)：以 `isActive(epoch)` 保护 `identityLease?.retire()`。
- **复现/验证证据**：通过 `/tmp/ts3_audit_repro/AuditRepro.kt` 编译链接仓库真实 `app/build/tmp/kotlin-classes/debug` 类文件执行 `repro1_requestExitVsFinishTerminalFailureCrash()` 实测命中：
  `java.lang.IllegalStateException: Required value was null. at io.github.ts3mobile.app.service.ConnectionCoordinator.requestExit(ConnectionCoordinator.kt:382)`。
- **建议修复方案**：在 `requestExit()` 的同一原子步（或先调用 `host.sessionGeneration.invalidate()` / 直接在第二个 `synchronized(exitLock)` 内若原 `lease.prepare()` 返回 `null` 则重新调用 `IdentityConnectionLease.acquire(identityGate)` 获取新租约并挂接）消除空窗期，严禁在 UI/Service 主线程对并发可退租的 `prepare()` 结果使用 `checkNotNull`。
- **修复风险与兼容性注意**：注意 `IdentityUseGate` 锁与 `SessionGeneration` 锁嵌套顺序（代码注释 L1045–1046 明确提醒避免 `SessionGeneration -> exitLock` 锁序冲突），推荐在 `exitLock` 释放后若 `lease.prepare()` 返回 `null` 再安全重取新 `lease`（仅当 `!closed && identityGate.beginConnection() != null`），避免扩大锁作用域引入死锁。
- **回归测试建议**：在 `ConnectionCoordinatorTest` 中构造可控钩子让 `finishTerminalFailure` 停在 `finishTerminalOnMainThread` 后、`exitLock` 前，同时并发发起 `requestExit`，断言不抛异常且最终 `IdentityUseGate.state == IDLE`、所有退出清理完成。
- **关联历史/ADR**：关联 ADR-0005 / 身份租约模型设计。

---

#### [AUD-002] `Ts3jSessionClient.connect()` 在握手后订阅期断连时仍发射 `CONNECTED` 并致盲 `ConnectionMonitor`

- **ID**：`AUD-002`
- **标题**：`subscribeAll()` 窗口期发生服务端踢出/断连后，`Ts3jSessionClient.connect()` 仍发射 `CONNECTED` 与 `transportConnected == null`，形成僵尸已连接状态
- **严重级别**：`P1`
- **置信度**：`Confirmed — Reproduced`
- **所属模块**：`ts3-protocol`（`Ts3jSessionClient`）连带影响 `app`（`ConnectionCoordinator` / `ConnectionMonitor`）
- **涉及文件与行号**：
  - [Ts3jSessionClient.kt#L129-L157](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L129-L157)
  - [Ts3jSessionClient.kt#L427-L440](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L427-L440)
  - [Ts3jSessionClient.kt#L508-L529](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L508-L529)
  - [ConnectionCoordinator.kt#L722-L751](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L722-L751)
  - [ConnectionMonitor.kt#L82-L87](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionMonitor.kt#L82-L87)
- **问题归属**：TS3-Mobile 一方协议适配层与会话协调器交互缺陷
- **问题描述**：`client.connect(...)` 握手成功后，`Ts3jSessionClient.connect()` 紧接着执行最长可达数秒的 `client.subscribeAll(...)` 订阅事件。若服务器在此期间发送断开通知或踢出客户端，`handleServerDisconnect()` 会关闭 `client`、将 `socket` 置 `null` 并把 `failureReported` 设为 `true` 后发射 `DISCONNECTED`，但**既未向 `connectionAttempt` 记录失败，也未注销 `generation`**；同时 `ConnectionCoordinator.SessionListener` 因当时 `connected == false` 而未摘除监听器。随后 `subscribeAll()` 正常返回（或吞掉 `CommandException`），`connect()` 继续执行并先后发射空快照与 **`ConnectionPhase.CONNECTED`**；此后 `transportConnected` 因 `failureReported == true` 永远返回 `null`，彻底致盲 `ConnectionMonitor`。
- **复现路径或触发时序**：
  1. 发起连接，UDP 握手完成，`client.connect()` 返回；
  2. 执行 [Ts3jSessionClient.kt#L135](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L135) `client.subscribeAll(...)` 期间，ts3j 读线程收到服务端 `DisconnectedEvent`（如被踢、密码/权限后置拒绝或服务端关闭）；
  3. `handleServerDisconnect()` 将 `failureReported = true`、`socket = null`、关闭底层 `client` 并回调 `onStatusChanged(DISCONNECTED)`；
  4. `ConnectionCoordinator.SessionListener.onStatusChanged`（[L742-L749](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L742-L749)）因 `connected == false` 仅更新状态而不退场也不失败返回；
  5. `subscribeAll()` 结束，`Ts3jSessionClient.connect()` 见 `generation.isCurrent(token)` 仍为 `true`、且 `connectionAttempt.beginConnectedEmission()` 成功返回 `true`，进而回调 `CONNECTED`；
  6. Coordinator 误进入 `onSessionConnected`：UI 显示“已连接”（实则通道/成员列表为空且所有操作报错 `Not connected to a TeamSpeak server`），`ConnectionMonitor` 每秒轮询 `transportConnected` 永远得到 `null`（`connected == false` 分支永不触发），不会自动重连，也不会报断线。
- **影响分析**：连接中途遭遇服务端踢出或断流时，客户端陷入“UI 永久显示已连接、无任何声音与数据、后台保活持续运行且不会触发自动重连”的静默僵尸态，必须由用户手工点击断开才能恢复。
- **根因分析**：① `handleServerDisconnect()` 置空 `socket` 与设置 `failureReported = true` 时，没有同步通知 `connectionAttempt` 中止尚未完成的连接发射流程；② `Ts3jSessionClient.connect()` 在 L139 仅检查 `generation.isCurrent(token)` 而未核验 `socket === client && client.isConnected && !failureReported`；③ `ConnectionCoordinator.SessionListener` 对首连握手末期收到 `DISCONNECTED` 未走失败终止路径。
- **代码证据**：
  - [Ts3jSessionClient.kt#L134-L157](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L134-L157)
  - [Ts3jSessionClient.kt#L427-L440](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L427-L440)
  - [Ts3jSessionClient.kt#L508-L529](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L508-L529)
- **复现/验证证据**：通过 `/tmp/ts3_audit_repro/AuditRepro.kt` 中 `repro2_ts3jMidConnectServerDisconnectZombie()` 使用假 `Ts3jClientSocket` 在 `subscribeAll` 内触发 `onDisconnected` 实测输出：
  `phases=[CONNECTING, DISCONNECTED, CONNECTED], socketClosed=true, transportConnected=null (null blinds ConnectionMonitor!), joinChannelError=Not connected to a TeamSpeak server`。
- **建议修复方案**：
  1. 在 `Ts3jSessionClient.connect()` 的 `generation.withCurrent(token)` 块（L144）内强制要求 `socket === client && !failureReported && client.isConnected` 才允许 `beginConnectedEmission()`；否则立即抛出（或映射）对应的断开异常终止 `connect()`；
  2. 将正在进行的 `connectionAttempt` 挂接至世代门禁状态，使 `handleServerDisconnect()` 在 `beginConnectedEmission()` 前到达时能直接终止 `connect()` 并按 `event.reasonId` 决定是否允许重试；
  3. `ConnectionCoordinator.performConnectionAttempt()`（L655）增加检查 `newSession.transportConnected != false` 且不得吞并前置 `DISCONNECTED` 状态。
- **修复风险与兼容性注意**：需注意不要把原本由 `handleServerDisconnect` 负责上报的 `TERMINAL_DISCONNECT_REASONS`（reason 4/5 封禁/踢出）重复报告或误覆盖为可重试异常。
- **回归测试建议**：在 `:ts3-protocol:test` 新增用例模拟 `subscribeAll` 阶段收到 `DisconnectedEvent(reasonid=8)` 和 `DisconnectedEvent(reasonid=5)`，验证绝不发射 `CONNECTED`、`connect()` 抛出明确异常且保留正确的 `retryable` 属性。
- **关联历史/ADR**：与前期修复看门狗不可见断连的文档实践直接相关。

---

#### [AUD-003] `SessionExitRunner` 断开时抹除 `TeamSpeakServiceState` 中已加载的麦克风参数、模式与音频诊断状态

- **ID**：`AUD-003`
- **标题**：正常断开重置 `TeamSpeakServiceState` 丢弃 `microphoneSettings`、`microphoneMode` 与 `audioDiagnostics`，导致二次连接回退默认值及 UI 状态分裂
- **严重级别**：`P1`
- **置信度**：`Confirmed — Reproduced`
- **所属模块**：`app` (`io.github.ts3mobile.app.service` / `settings` / `ui`)
- **涉及文件与行号**：
  - [SessionExitRunner.kt#L98-L104](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt#L98-L104)
  - [ConnectionCoordinator.kt#L267-L286](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L267-L286)
  - [MicrophonePreferenceController.kt#L40-L42](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/settings/MicrophonePreferenceController.kt#L40-L42)、[#L147-L153](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/settings/MicrophonePreferenceController.kt#L147-L153)
  - [MicrophoneController.kt#L127-L131](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/MicrophoneController.kt#L127-L131)、[#L268-L271](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/MicrophoneController.kt#L268-L271)
  - [MicrophoneSettingsScreen.kt#L51](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/ui/MicrophoneSettingsScreen.kt#L51)
  - [ServiceAudioDiagnostics.kt#L34](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ServiceAudioDiagnostics.kt#L34)
  - [DiagnosticsPanel.kt#L38](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/ui/DiagnosticsPanel.kt#L38)
- **问题归属**：TS3-Mobile 一方代码缺陷
- **问题描述**：用户在前台停留在 `MainActivity` 时点“断开”，`TeamSpeakService` 因仍被 Activity 绑定（`isBound == true`）不会调用 `onDestroy()`。此时 `SessionExitRunner` 以全新构造的 `TeamSpeakServiceState(diagnostics = ..., backgroundRuntime = ...)` 整体覆盖 `host.mutableState.value`，将进程存活期内的 **`microphoneSettings`（增益、RNNoise、AEC、音量门限）、`microphoneMode` 及 `audioDiagnostics` 全部清零为构造默认值**。然而 `MicrophonePreferenceController` 仅在 Service `onCreate()` 加载过一次偏好（且内部状态仍保留用户旧设置），`AudioDiagnosticRepository.status` 也仅在档案变更时才再推送——因此**下次在同一进程重连时，`ConnectionCoordinator.beginConnection()` 会复制已被清零的默认 `microphoneSettings`，并在采集启用时用默认值静默覆盖并下发给 `OpusMicrophoneCapture`**！
- **复现路径或触发时序**：
  1. 启动 App 绑定 `TeamSpeakService`，`MicrophonePreferenceController` 加载已保存的麦克风配置（例如 `volumePercent = 165, noiseSuppression = false, echoCancellation = false, gateThresholdDb = -25`）并推入 `TeamSpeakServiceState`；用户在断开或连接中切换到 `VOLUME_GATE`；
  2. 连接任意服务器后点击“断开”（保持停留在 App 内使 Service 存活）；
  3. `SessionExitRunner` 执行 [L99-L103](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt#L99-L103)，`mutableState.value` 中的 `microphoneSettings` 回到 `MicrophoneSettings(100, true, true, -40)`，`microphoneMode` 回到 `PUSH_TO_TALK`，`audioDiagnostics.sessions` 归零；
  4. 用户直接再次发起连接（未重启进程）：`beginConnection()`（L268）读取已经归零的默认值；进入麦设置页可见界面（读 `state.microphoneSettings`，L51）显示回默认，且诊断页“标记问题”按钮（`audio.sessions > 0`，L38）变为置灰不可用；
  5. 更诡异的是：若用户此时在麦设置页仅拖动“音量门限”，`MicrophoneSettingsScreen.kt:89` 以界面默认对象 `settings.copy(gateThresholdDb = it)` 传入 `edit(GATE_THRESHOLD, ...)`，但 `MicrophonePreferenceController.edit()` 只取该字段合并回控制器内部仍存留的旧设置 `165% / NS=false / AEC=false` 并 `applySettings`，界面所有其余开关会在毫无提示下突然跳回。
- **影响分析**：① 已持久化的核心语音降噪/AEC/增益/门限配置与发言模式在不断杀进程的重连间静默失效；② `TeamSpeakServiceState` 与 `MicrophonePreferenceController` 产生双真相源错乱；③ 用户刚断开无法在诊断页点击“标记音频问题”，必须先再产生活动音频会话。
- **根因分析**：`SessionExitRunner` 在清理“单次会话态”时采用“白板重建 `TeamSpeakServiceState(...)`”而非仅替换会话字段或显式保留所有进程级/用户级偏好字段（与 `ConnectionCoordinator.beginConnection()` L267–281 已保留 `microphoneMode`/`microphoneSettings`/`audioRouting` 形成对比，而 `audioDiagnostics` 甚至在 `beginConnection` 和 `SessionExitRunner` 两处均漏传，且还漏掉了 `audioRouting`、`identityReady` 等持久态）。
- **代码证据**：
  - [SessionExitRunner.kt#L98-L104](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt#L98-L104)：仅保留了 `diagnostics` 与 `backgroundRuntime`。
  - [ConnectionCoordinator.kt#L267-L286](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L267-L286)：保留了麦配置、麦模式、听筒静音、`audioRouting`，但同样遗漏 `audioDiagnostics`。
  - [MicrophoneController.kt#L268-L271](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/MicrophoneController.kt#L268-L271)：每次启动录音直接取 `state.value.microphoneSettings`（而非 `microphonePreferences`），放大了状态抹除破坏。
- **复现/验证证据**：通过 `/tmp/ts3_audit_repro/AuditRepro.kt` 的 `repro3_sessionExitWipesMicSettingsAndAudioDiag()` 实测输出：
  `BEFORE: mode=VOLUME_GATE, settings=MicrophoneSettings(volumePercent=165, noiseSuppression=false, echoCancellation=false, gateThresholdDb=-25), audioDiag.sessions=3`
  `AFTER : mode=PUSH_TO_TALK, settings=MicrophoneSettings(volumePercent=100, noiseSuppression=true, echoCancellation=true, gateThresholdDb=-40), audioDiag.sessions=0`。
- **建议修复方案**：在 `TeamSpeakServiceState` 上抽出显式的会话重置辅助函数（如 `resetForDisconnectedSession(diagnosticsSnapshot)` / `resetForConnectingSession(...)`），保证断开和重连只清零连接与单次会话瞬态（`status`、`snapshot`、`serverLabel`、`isTransmitting`、`isCapturing`、`echoCancellationState`、`microphoneError`、`switchingChannelId`、`channelError`、`presence`、`bookmarkConnection`、`participantAudioSettings`、`actualAudioDevices`），**保留** `microphoneMode`、`microphoneSettings`、`audioDiagnostics`、`backgroundRuntime`、`audioRouting`（视需要还原 `playbackMuted = false` / 安全静音约束）与 `identityReady`；同时考虑在用户点击断开并保留默认 `microphoneMode` 策略上明确产品约定（若刻意重置 `microphoneMode` 至 `PUSH_TO_TALK`，必须与保留持久化 `microphoneSettings` 和 `audioDiagnostics` 分开处理）。
- **修复风险与兼容性注意**：现有测试（[SessionExitRunnerTest.kt](file:///Users/monet/Documents/TS3-mobile/app/src/test/java/io/github/ts3mobile/app/service/SessionExitRunnerTest.kt) 与 [ConnectionCoordinatorTest.kt#L199-L216](file:///Users/monet/Documents/TS3-mobile/app/src/test/java/io/github/ts3mobile/app/service/ConnectionCoordinatorTest.kt#L199-L216)）需核对是否断言 `SessionExitRunner` 清除了某些预期字段（如 `playbackMuted` 或 `participantAudioSettings`），避免测试冲突。
- **回归测试建议**：补充单元测试与 Service 集成测试：先将非默认 `MicrophoneSettings`、非零 `AudioDiagnosticStatus` 注入状态并执行完整连接→`requestDisconnect()`→二次连接流程，断言自定义麦配置与诊断档案数均保持不变。
- **关联历史/ADR**：参考 [ConnectionCoordinatorTest.kt#L199](file:///Users/monet/Documents/TS3-mobile/app/src/test/java/io/github/ts3mobile/app/service/ConnectionCoordinatorTest.kt#L199) 已有 `manualConnectionPreservesMicrophoneParametersWithoutCopyingActiveCaptureFlags`（恰好漏了退出路径对照）。

---

#### [AUD-004] 默认依赖 `ts3j-ts3mobile-1` 保留 RSA 握手定宽拷贝越界缺陷（~1/256），且一方异常分类将其误判为不可重试死刑

- **ID**：`AUD-004`
- **标题**：固定 `ts3j-ts3mobile-1` 未启用 `0002` 无符号定宽握手补丁引发随机 `ArrayIndexOutOfBoundsException`，且 `ConnectionFailure` 将其判为非可重试而永久中断后台重连
- **严重级别**：`P1`
- **置信度**：`Confirmed — Reproduced`
- **所属模块**：外部库 `com.github.monet4070:ts3j:ts3mobile-1` + `ts3-protocol` (`ConnectionFailure`) + `app` (`ReconnectEngine`)
- **涉及文件与行号**：
  - 外部 `ts3j-ts3mobile-1.jar`：`LocalClientHandlerConnecting.handlePacket`（`javap -c` 字节码偏移 248–321）
  - [gradle/libs.versions.toml#L14](file:///Users/monet/Documents/TS3-mobile/gradle/libs.versions.toml#L14)、[tools/ts3j-patches/0002-handshake-unsigned-width.patch](file:///Users/monet/Documents/TS3-mobile/tools/ts3j-patches/0002-handshake-unsigned-width.patch)
  - [docs/testing/TS3J_WIDTH_CANDIDATE_2026-11-24_ZH.md](file:///Users/monet/Documents/TS3-mobile/docs/testing/TS3J_WIDTH_CANDIDATE_2026-11-24_ZH.md)、[tools/ts3j-patches/README.md#L55-L60](file:///Users/monet/Documents/TS3-mobile/tools/ts3j-patches/README.md#L55-L60)
  - [ConnectionFailure.kt#L16-L36](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/ConnectionFailure.kt#L16-L36)
  - [ReconnectEngine.kt#L130-L134](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ReconnectEngine.kt#L130-L134)
- **问题归属**：**合并归因**：第三方库固有 Bug（`ts3j-ts3mobile-1` 默认故意未合入 `0002` 补丁） + TS3-Mobile 一方错误分类放大故障
- **问题描述**：TS3 UDP 握手第 3/4 步需计算 `x.modPow(2^level, n)` 并编码为 64 字节无符号大端序数组。由于 `BigInteger.toByteArray()` 采用最小有符号补码，当高字节全 0（约 $1/256$ 概率）时返回数组长度 $\le 63$ 字节，而未打补丁的 `ts3j` 以 `Math.abs(len - 64)` 作为源数组偏移拷贝 `len` 字节，必然越界抛出 `ArrayIndexOutOfBoundsException`。更严重的是，一方 `ConnectionFailure.kt` 仅将 `IOException`、`TimeoutException`、`InterruptedException` 归为可重试异常；一旦该随机握手异常发生在“掉线后后台自动重连”阶段，`ReconnectEngine` 立即调用 `finishTerminalFailure()` **彻底杀死自动重连与声音会话**。
- **复现路径或触发时序**：
  1. 客户端连接或切网自动重连任意真实 TS3 服务器（`INIT1` Step 3→Step 4）；
  2. 服务端下发随机挑战 `(x, n, level)`，若 `x.modPow(2^level, n)` 有效高位最高 8 位以上全 0，则 `solution.length <= 63`（仓库测试文档已在 2026-11-24 连接官方 TS3 Server 3.13.9 时实际遭遇该异常）；
  3. `LocalClientHandlerConnecting` 执行 `System.arraycopy(solution, Math.abs(len - 64), ...)` 抛 `ArrayIndexOutOfBoundsException`（注：`> 64` 字节即 65 字节带前导 `0x00` 符号位虽 `srcPos=1, copyLen=64` 不崩溃，但 `< 64` 必崩）；
  4. 首连表现为用户莫名失败、重点一次又成功；**若在自动重连期间遇到**，一方 `isRetryableConnectionFailure()` 返回 `false`，`ReconnectEngine`（[L131-L134](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ReconnectEngine.kt#L131-L134)）判定为不可恢复终态失败，弹出错误通知并放弃重连。
- **影响分析**：按每次独立连接/重连概率估计约有 ~1/256（假设挑战结果在 512 bit 上近均匀）的随机握手失败率；对长期依赖后台自动重连的移动用户而言，单次不凑巧的挑衅数即可永久掐断重连恢复能力。
- **根因分析**：① 团队虽已定位问题并编写完备的 `0002-handshake-unsigned-width.patch`，但在 `1.3.0` 候选基线中决定仅保留为本地构建选项而不升级默认坐标 `ts3mobile-1`；② 一方错误分类对连接握手内部非协议拒绝类 `RuntimeException` 缺省一律标为 `retryable = false`，在未解决握手越界前缺乏降级重试兜底。
- **代码证据**：
  - `ts3j-ts3mobile-1.jar` `LocalClientHandlerConnecting.handlePacket` 字节码实测：
    `arraylength` < 64 时计算 `iload len; bipush 64; isub; invokestatic Math.abs` 得 $\ge 1$，而 `length` 取 `len`，`System.arraycopy(src, >=1, dst, >=1, len)` 越界。
  - [ConnectionFailure.kt#L32-L35](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/ConnectionFailure.kt#L32-L35)：仅判断 3 种受检异常 `IOException / TimeoutException / InterruptedException`。
- **复现/验证证据**：
  - 仓库自身实服测试已复现记录于 [TS3J_WIDTH_CANDIDATE_2026-11-24_ZH.md#L3](file:///Users/monet/Documents/TS3-mobile/docs/testing/TS3J_WIDTH_CANDIDATE_2026-11-24_ZH.md#L3)；
  - `/tmp/ts3_audit_repro/AuditRepro.kt` 的 `repro4_ts3jHandshakeAioobeNonRetryable()` 对 `byte[63]` 实测：`error=java.lang.ArrayIndexOutOfBoundsException: arraycopy: last source index 64 out of bounds for byte[63], isRetryableConnectionFailure=false`。
- **建议修复方案**：
  - **根本修复（推荐）**：按照 `tools/ts3j-patches/README.md` 与 `docs/decisions/0004` 流程发布合入 `0002-handshake-unsigned-width.patch` 的新 `ts3j` 正式标签（如 `ts3mobile-2`）并在 `1.3.0` 正式发版前完成实服互通验收后升级 `libs.versions.toml` 与锁文件；
  - **纵深防御**：在 `Ts3jSessionClient.connect()` 捕获并包装握手阶段意外的运行时异常（非 `CommandException` / `IllegalArgumentException` 参数校验错误）前，或者在补丁正式合入前识别握手阶段 `ArrayIndexOutOfBoundsException`，标记有限可重试以免直接掐断重连循环（注意限制次数避免针对永久编程 bug 无限重试）。
- **修复风险与兼容性注意**：更换 `ts3j` 标签需同步校验 `gradle.lockfile`、严格锁定四件传递库哈希以及 R8 Release 构建与实服连接稳定性测试。
- **回归测试建议**：在 `ts3j` 仓库与 `ts3-protocol` 侧除已有 `encodeUnsigned` 数值单测外，增加构造 62/63 字节 `modPow` 输出的完整 Step 3/4 协议包回环测试；并在 `ConnectionFailureTest` 中补充对应异常重试分类断言。
- **关联历史/ADR**：`docs/decisions/0004-take-ownership-of-ts3j-dependency.md`、`docs/testing/TS3J_WIDTH_CANDIDATE_2026-11-24_ZH.md`。

---

### P2 级缺陷

#### [AUD-005] 频道文件与头像/图标下载拒绝标准 TS3 服务器常见的空或通配 `ip=` 响应，阻断正常文件与图片获取

- **ID**：`AUD-005`
- **标题**：`Ts3jChannelFileReplies` / `Ts3jChannelFileTcp` 未兼容服务器返回 `ip=`、`ip=0.0.0.0` 或多地址 `ip=0.0.0.0, ::` 的常规 `notifystartdownload` 报文
- **严重级别**：`P2`
- **置信度**：`Confirmed — Reproduced`
- **所属模块**：`ts3-protocol` (`io.github.ts3mobile.protocol`)
- **涉及文件与行号**：
  - [Ts3jChannelFileReplies.kt#L120-L129](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jChannelFileReplies.kt#L120-L129)
  - [Ts3jChannelFileTcp.kt#L55-L56](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jChannelFileTcp.kt#L55-L56)
- **问题归属**：TS3-Mobile 一方协议解析与校验缺陷
- **问题描述**：客户端为防止重定向 SSRF 攻击，在 `Ts3jChannelFileTcp.receive()` 中约束 `if (offer.address != null && offer.address != peer.hostAddress) throw FileReplyFailure(INVALID_RESPONSE)`，且直接原样保存 `fields["ip"]` 到 `Ts3DownloadOffer.address`。然而按照 TeamSpeak 3 Server 默认配置（`filetransfer_ip=0.0.0.0` 或 `0.0.0.0, ::` 或为空 `ip=` 省略具体接口），服务端在 `notifystartdownload` 中可能返回空字符串 `ip=`（`fields["ip"]` 是 `""` 而非 `null`）、未指定地址 `"0.0.0.0"`、`"::"`，或逗号分隔的多绑定地址列表 `"0.0.0.0, ::"`；此时客户端仍然连向本会话已连接的 `peer`，却会在握手检查处全部直接报错 `INVALID_RESPONSE` 拒绝下载。
- **复现路径或触发时序**：
  1. 连接使用默认 `filetransfer_ip=0.0.0.0` / `ip=` / 双栈绑定的公网或内网（含经过端口映射/NAT/域名的）TS3 服务器（注：`1.3.0` Release 已默认开启 `channel_files_probe_enabled=true`）；
  2. 打开频道文件、点击聊天文件附件，或在“用户”页/频道列表加载服务端头像及图标（`downloadImage` 同走该校验）；
  3. 服务端响应 `notifystartdownload ... port=30033 size=... ftkey=... proto=1 ip=0.0.0.0`（或 `ip=` / `ip=0.0.0.0,\s::`）；
  4. `Ts3jChannelFileTcp.receive()` 检查 `"0.0.0.0" != peer.hostAddress`（如 `"198.51.100.10"`），直接抛出 `INVALID_RESPONSE`，导致页面报错无法下载。
- **影响分析**：**直接破坏 `1.3.0` 新增三大可见特性（频道文件下载、聊天附件下载、头像与图标预览）在常规/默认配置 TS3 服务器上的可用性**；此前仅用单地址显式回环 `127.0.0.1` 夹具验收掩盖了该真实服务端字段形态。
- **根因分析**：作者意图防止恶意服务器下发跨服第三方 IP 进行 SSRF（如把 `127.0.0.1` 会话跳去局域网另一主机），但直接做了朴素字符串全等校验，未区分“空值 / 服务端通配监听占位符（`""`、`0.0.0.0`、`::`、或逗号列表中不含恶意第三方的通配符）”与“实际指向其它单播目标 IP”。
- **代码证据**：
  - [Ts3jChannelFileReplies.kt#L128](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jChannelFileReplies.kt#L128)：`fields["ip"]` 在 `ip=` 时返回空串 `""`；在双栈时经 ts3j `Ts3String.unescape` 成为 `"0.0.0.0, ::"`。
  - [Ts3jChannelFileTcp.kt#L56](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jChannelFileTcp.kt#L56)：`if (offer.address != null && offer.address != peer.hostAddress) throw FileReplyFailure(ChannelFileFailure.INVALID_RESPONSE)`（另同路径 `Ts3jChannelFiles.kt:162` 硬编码 `cpw=""` 见 `RISK-005`）。
- **复现/验证证据**：
  - 本次已通过 `jshell` 核对真实 `ts3j` `SingleCommand.parse` 返回 `ip=""`、`ip="0.0.0.0"`、`ip="0.0.0.0, ::"`；
  - 通过 `/tmp/ts3_audit_repro/AuditRepro.kt` 的 `repro7_channelFilesRejectsEmptyAndWildcardIp()` 实测全部 3 种格式：均返回 `Failed(reason=INVALID_RESPONSE)`。
- **建议修复方案**：保持“TCP 目标只允许已连接 `peer` 地址（不按 `offer.address` 重定向连接）”的安全边界不变，并将 `offer.address` 校验规则修改为：拆解空白及逗号分隔条目并过滤通配占位地址（`""`、`0.0.0.0`、`::`、`[::]` / `InetAddress.getByName(token).isAnyLocalAddress` 规范化后判等，且注意避免对域名做 DNS 解析），只有当报文给出具体非通配单播 IP 且其中**不包含**标准化后的 `peer` 地址时才拒收 `INVALID_RESPONSE`（例如 NAT 后服务器若下发自身内网 IP 或多网卡绑定列表需先明确安全策略；至少对空串和全零通配地址/列表放行并直连 `peer`）。
- **修复风险与兼容性注意**：严格避免在解析 `offer.address` 时触发阻塞式 DNS 解析（使用数值字面量解析或无 DNS 解析方式校验 IP 字面量）；保留对明确异机 IP（如 `127.0.0.2` 测试用例）的拒绝断言不倒退。
- **回归测试建议**：在 `Ts3jChannelFilesTest` 与 `ServerImagesTest` 中补充 `ip=""`、`ip="0.0.0.0"`、`ip="::"`、`ip="0.0.0.0, ::"` 与 `ip="${peer.hostAddress}, ::"` 通过用例，继续保留 `127.0.0.2` 与非法跨址拒绝用例。
- **关联历史/ADR**：[CHANNEL_FILES_PROBE_2026-11-24_ZH.md](file:///Users/monet/Documents/TS3-mobile/docs/testing/CHANNEL_FILES_PROBE_2026-11-24_ZH.md)。

---

#### [AUD-006] `PlaybackSession` 在蓝牙路由延迟就绪于发言结束后，永久泄漏播放态 `AudioTrack`

- **ID**：`AUD-006`
- **标题**：当短发言在蓝牙路由确认就绪前结束，`PlaybackSession` 会保留 `started=false` 与打开的 `AudioTrack` 且静音期间持续 100 Hz 轮询 `routeReady` 无法回收
- **严重级别**：`P2`
- **置信度**：`Confirmed — Reproduced`
- **所属模块**：`audio-opus` (`io.github.ts3mobile.audio.opus`)
- **涉及文件与行号**：
  - [PlaybackSession.kt#L47-L85](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L47-L85)、[#L95-L100](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L95-L100)
  - [AndroidPlaybackOutput.kt#L97](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/AndroidPlaybackOutput.kt#L97)、[#L125-L145](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/AndroidPlaybackOutput.kt#L125-L145)
  - [BluetoothPlaybackGuard.kt#L44-L59](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/BluetoothPlaybackGuard.kt#L44-L59)
- **问题归属**：TS3-Mobile 一方代码缺陷
- **问题描述**：选择蓝牙输出时，`PlaybackSession.tick()` 在 `readyToStart` 满足后新建 `AndroidPlaybackOutput` 并立即调用 `currentOutput.start()`（使 `AudioTrack` 进入 `PLAYSTATE_PLAYING` 并挂载音频 HAL），随后检查 `!currentOutput.routeReady`（`WAIT_FOR_BLUETOOTH`）。若此时返回 `false`，`tick()` **清空 `streams` 与 `input`，保持 `started = false` 且不关闭已创建的 `output`**，期望后续再通过路由检查时进入播放。但如果发言人在蓝牙切轨就绪前就停止了说话：等到蓝牙真正连好（`routeReady` 变为 `true`），由于此刻已无音频包，`tick()` 卡在 L64 `if (!streams.readyToStart(now)) return`，**永远不会创建 `PlaybackTimeline` 也永远不会触发排空回收（`retireOutput(DRAINED)`）**！并且此时每 10 ms 都在执行 L47 `output?.routeReady`（即每次调用 `checkRoute()` → 将硬件 `AudioTrack` 音量设回 `1f` 并每 10 ms 枚举系统设备+IPC），直到下一次有人发言、断开或被静音为止。
- **复现路径或触发时序**：
  1. 选定蓝牙路由（或系统默认路由含蓝牙预期）且尚处于路由就绪前缓冲期；
  2. 对方说了一句短语音（约 80 ms），`readyToStart()` 为 `true` → 创建并启动 `AudioTrack` → L75 检测到 `routeReady == false` (`WAIT_FOR_BLUETOOTH`) → 清空缓冲，维持 `output != null`（`PLAYSTATE_PLAYING`）且 `started = false`；
  3. 几百毫秒后对方停麦（频道彻底安静），随后蓝牙 A2DP 链路就绪，`checkRoute()` 评估结果为 `ALLOW`（`routeReady == true`）并将 `AudioTrack` 音量调至 `1f`；
  4. 此后频道若 10 分钟无人说话，`ts3-opus-playback` 线程每 10 ms 执行 L47 `output?.routeReady`（触发 `checkRoute()` → Binder IPC 设备查询 + `setVolume(1f)`），然后立即在 L64 返回，**`AudioTrack` 作为活跃 `PLAYSTATE_PLAYING` 流被无限期泄漏**，阻止系统/设备进入蓝牙空闲节电或导致音频焦点/混音会话异常占用。
- **影响分析**：短句或入频首句遇蓝牙切换延迟后的长时静默期，硬件播放轨无法释放且每 10 ms 持续唤醒调用音频服务 IPC（100 Hz），显著增加蓝牙连接保持电流及 AudioFlinger 资源长期占用。
- **根因分析**：`PlaybackSession` 创建与启动 `output` 时没有以“是否真正进入 `PLAYING`”作为资源看守界限，在 `WAIT_FOR_BLUETOOTH` 期间已持有物理 `AudioTrack` 却没有为其设立空闲回收状态机分支，仅当有新流触发 `readyToStart()` 才有机会构建 `PlaybackTimeline` 来排空回收。
- **代码证据**：
  - [PlaybackSession.kt#L67-L79](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L67-L79)：L68 创建并调用 L74 `currentOutput.start()`，L75 `if (!currentOutput.routeReady ...)` 清空流且不关闭 `output`。
  - [PlaybackSession.kt#L64](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L64)：当无包到来时在此早退，无法触达 L95 `retireOutput(DRAINED)`。
- **复现/验证证据**：通过 `/tmp/ts3_audit_repro/AuditRepro.kt` 的 `repro5_playbackSessionLeaksOutputAfterBtReady()` 实测：发送 4 帧后在 `routeReady=false` 清流、随后切 `routeReady=true` 并运行 5 秒静音（500 ticks）：
  `outputStillOpen=true, outputClosed=false, status=BUFFERING, routeReadyPollsDuringSilence=500`。
- **建议修复方案**：在 `PlaybackSession` 中增加空闲输出回收或看守窗口：当 `output != null && !started` 时，若 `output.routeReady == true` 且 `!streams.readyToStart(now)` 持续超过极短宽限（甚至无待播流即可直接 `retireOutput(OutputRetirement.DRAINED)`，注意确保 `BluetoothPlaybackGuard` 按设计保持或重开 attempt 语义，避免影响已验证的 `beginBluetoothAttempt()` 逻辑），或对未启动的等待输出同样受等待超时约束直接退出并回收 `output`。
- **修复风险与兼容性注意**：ADR-0039 依赖在等待蓝牙路由期间由已 `start()` 的 `AudioTrack`（volume=0）向 Android AudioFlinger 表达媒体路由需求从而拿到真实的 `routedDevice`，因此**不能在首次 `!routeReady` 时立刻销毁 `output`**，而应在 `routeReady` 迟迟未达且已无持续包后超时/静音回收，或在 `routeReady == true` 却仍无话音流需要播放时立即回收。
- **回归测试建议**：在 `audio-opus` 单测中添加场景：`routeReady` 初为 `false`、喂入一短串语音后静音、`routeReady` 转为 `true` 后若干 tick，断言 `output.close()` 被调用且不再高频轮询 `routeReady`。
- **关联历史/ADR**：[ADR 0039](file:///Users/monet/Documents/TS3-mobile/docs/decisions/0039-media-friendly-voice.md)。

---

#### [AUD-007] `VoiceReceiveQueue` 60 ms 驻留过期触发整客户端 `budget.invalidate()`，导致慢启动或调度卡顿后级联清除全部有效解码缓冲

- **ID**：`AUD-007`
- **标题**：单个接收包在入队队列停留超过 60 ms 即触发整客户端 `ReceiveMediaBudget` 令牌失效，连带清毁已入抖动队列的正常包及已解码 PCM
- **严重级别**：`P2`
- **置信度**：`Confirmed — Reproduced`
- **所属模块**：`audio-opus` (`io.github.ts3mobile.audio.opus`)
- **涉及文件与行号**：
  - [VoiceReceiveQueue.kt#L47-L52](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/VoiceReceiveQueue.kt#L47-L52)、[#L114-L142](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/VoiceReceiveQueue.kt#L114-L142)
  - [ReceivePolicy.kt#L23](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/ReceivePolicy.kt#L23)（`INPUT_AGE_NANOS = 60_000_000L`）
  - [ReceiveMediaBudget.kt#L94-L97](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/ReceiveMediaBudget.kt#L94-L97)
  - [TalkerJitterPipeline.kt#L90-L94](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/TalkerJitterPipeline.kt#L90-L94)、[#L216-L236](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/TalkerJitterPipeline.kt#L216-L236)
  - [PlaybackSession.kt#L68-L75](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L68-L75)
- **问题归属**：TS3-Mobile 一方代码缺陷
- **问题描述**：`VoiceReceiveQueue.prune(now)` 在清理等待超过 `INPUT_AGE_NANOS`（60 ms）的旧网络包时，不只是丢弃那几个陈旧包，而是调用 `budget.invalidate(clientId)` 作废该发言客户端的整个 `ClientState.token`。虽然 `prune` 会为紧跟其后的未过期队列包重新申请租约，但已经提取进入 `TalkerJitterPipeline` 内部 `pending` 或正在 `pcm` FIFO 中平滑输出的**所有之前有效已解码语音帧**，会在下一次 `fillPcm()` 检测到 `budget.generation(clientId) != token` 时被 `reset(clearSequence = true)` 整批抹除，重置 Opus 解码器状态并重新进入 60–160 ms 的冷缓冲等待，引发长达上百毫秒的人耳可感知卡顿。典型诱因除 GC/调度卡顿 >60 ms 外，还包含 `PlaybackSession.tick()`（[L68](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L68)）冷建/启动硬件 `AudioTrack`（代码特设了 `SLOW_OUTPUT_MS = 30L` 监控冷启耗时），且 L88 与下个 tick 读取的是 `createOutput()` 消耗完毕后的当前时间。
- **复现路径或触发时序**：
  1. 说话人连续发送 20 ms 帧，`TalkerJitterPipeline` 内已有 ~70 ms 已解码/待播 PCM 正常播放；
  2. `ts3-opus-playback` 线程因手机前后台切换/游戏切场景或新建低延迟 `AudioTrack` 阻塞了 65 ms（实机中 `SLOW_OUTPUT_MS=30ms` 常见）；
  3. 期间新到的包 #4（65 ms 前）与包 #5（15 ms 前）暂存于 `VoiceReceiveQueue`；
  4. 下一帧到来调用 `offer()` 或 `drainAvailable()` 执行 `prune(now)`：因包 #4 超过 60 ms 触发 `budget.invalidate(7)` 推进该客户端全局 `token`；
  5. `TalkerJitterPipeline.fillPcm()` 运行至 L90，发现自身旧 `token` 已失效，触发 `reset(clearSequence = true)`：**清空之前全部完好已解码 PCM、`pending` 及自适应延迟状态，重置解码器，强迫重新缓冲**（若 `ingest` 先塞入新生成的 `token` 包也会在 L51 触发一次 `reset` + FADE）。
- **影响分析**：本意为丢弃过老输入包的防护阈值，在高负载（游戏+语音）或新开硬件轨延迟下变成“放大器”，把单个 65 ms 调度抖动放大成解码器复位与整段缓冲丢失的明显爆音/失声空洞。
- **根因分析**：将“输入队列局部帧陈旧过期”与“客户端安全/路由/静音级的全链路令牌作废（`budget.invalidate`）”耦合到同一原语；前者仅应 `lease.release()` 释放局部陈旧包预算并在 `TalkerJitterPipeline` 靠自适应延迟与跳号恢复，不应摧毁已进入解码阶段的正常流。
- **代码证据**：
  - [VoiceReceiveQueue.kt#L114-L120](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/VoiceReceiveQueue.kt#L114-L120)：`clients.forEach(budget::invalidate)` 作废旧令牌。
  - [TalkerJitterPipeline.kt#L90-L94](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/TalkerJitterPipeline.kt#L90-L94)：令牌变动即执行 `reset(clearSequence = true)`，清空 `pending` 与 `pcm.clear()` 并调用 `decoder.reset()`。
- **复现/验证证据**：通过 `/tmp/ts3_audit_repro/AuditRepro.kt` 的 `repro6_voiceReceiveQueue60msCascadingInvalidation()` 实测：仅模拟 1 次 65 ms 调度阻塞令队列中第 4 包过期（第 5 包仅 15 ms 新鲜且之前已有完好 PCM），结果：
  `stalePackets=1, decoderResets=1, mixImmediatelyAfterStall=false`。
- **建议修复方案**：`VoiceReceiveQueue.prune()` 与 `offer()` 遇 `isExpired(queued, now)` 时，仅释放超时包自身租约 `queued.lease?.release()` 并从 `frames` 中剔除、累加 `stalePackets`，**禁止因普通入队年龄过期调用 `budget.invalidate(clientId)`**；仅在真正的预算溢出、主动静音（`removeClient`）或全局清流时作废 `clientId` 令牌。若需在丢弃旧包后防止误插 PLC，由 `TalkerJitterPipeline` 按 `MAX_FORWARD_PACKET_GAP` / 新包到达时间戳平滑跳号续播即可。
- **修复风险与兼容性注意**：确保被丢弃过期包的租约（`lease.release()`）正确归还 `samples` 与 `bytes` 避免预算记账泄漏；验证仅释放单包后，连续超时停说随后重说的场景不会遗留过期序列号阻塞后续正常收包。
- **回归测试建议**：在 `VoiceReceiveQueueTest` 与 `PlaybackPipelineTest` 中测试“模拟 65 ms 调度停顿”：验证过期的老包被计入 `stalePackets` 并平滑释放，同时先前已解码未播完 PCM 连续输出、`decoderResets == 0`。
- **关联历史/ADR**：[MOBILE_RX_AUDIT_2026-11-08_ZH.md](file:///Users/monet/Documents/TS3-mobile/docs/testing/MOBILE_RX_AUDIT_2026-11-08_ZH.md)。

---

#### [AUD-008] `AndroidPlaybackOutput.write()` 在 10 ms 实时音频热路径中每帧触发 `routedDevice` 设备列表枚举与 `setVolume()` IPC

- **ID**：`AUD-008`
- **标题**：`ts3-opus-playback` 实时音频写路径每 10 ms 调用 `checkRoute()`，引发 100 Hz `AudioTrack.getRoutedDevice()` 分配及 `setVolume()` Binder/JNI IPC
- **严重级别**：`P2`
- **置信度**：`Confirmed — Static Proof`
- **所属模块**：`audio-opus` (`io.github.ts3mobile.audio.opus`)
- **涉及文件与行号**：
  - [AndroidPlaybackOutput.kt#L110-L145](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/AndroidPlaybackOutput.kt#L110-L145)
  - [OpusAudioPlayer.kt#L246-L265](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusAudioPlayer.kt#L246-L265)
- **问题归属**：TS3-Mobile 一方代码性能与实时路径设计缺陷
- **问题描述**：`PlaybackTimeline.writePending()` 每次调用 `AndroidPlaybackOutput.write()`（正常播放期每 10 ms 一次，极端阻塞写重试可达单 tick 8 次），第一行即调用 `checkRoute()`（L115）。`checkRoute()` 在 `synchronized(outputLock)` 内调用 `track.routedDevice` 与 `track.setVolume(1f)`。参照 AOSP `AudioTrack.getRoutedDevice()` 实现（已在公开发行源码核验），其每次调用都会经 `AudioManager.getDevicesStatic(AudioManager.GET_DEVICES_OUTPUTS)` 获取全系统输出设备阵列并遍历匹配 `deviceId`，伴有 IPC/JNI 与 Java 数组分配；紧接着无条件调用 `track.setVolume(1f)` 亦每 10 ms 向 `AudioFlinger` 发起音量设置。
- **复现路径或触发时序**：任何持续语音接收场景下，每 10 ms 触发一次 `tick()` → `writePending()` → `write()` → `checkRoute()`。
- **影响分析**：正常收听期间持续以 100 Hz 频率产生本可在事件驱动回调（`OnRoutingChangedListener`）内完成的跨进程/设备数组枚举分配与 `AudioFlinger` 音量设置调用，推高小核唤醒频率、Binder 锁争用及 GC 压力（具体毫秒与电量差额需真机 Perfetto 实测，不能由静态分析捏造）。
- **根因分析**：作者将初始化/`OnRoutingChangedListener` 状态验证与实时写入热路径直接合用了同一方法 `checkRoute()`，未缓存当前已验证通过且未变更的路由状态与已施加音量，导致稳态下每 10 ms 重复探测与重复置音量。
- **代码证据**：
  - [AndroidPlaybackOutput.kt#L115-L141](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/AndroidPlaybackOutput.kt#L115-L141)：`write()` 必调 `checkRoute()`；L129 `val device = track.routedDevice`；L138 `track.setVolume(...)` 无值变化判断。
  - AOSP `AudioTrack.java`：`getRoutedDevice()` 通过 `native_getRoutedDeviceId()` 加 `AudioManager.getDevicesStatic(GET_DEVICES_OUTPUTS)` 枚举分配。
- **复现/验证证据**：静态代码调用路径与 AOSP 实现机制核验确定；实际 CPU/IPC 开销与电池增量受无真机条件所限未在本机采集 Perfetto 数值（如实标明静态证明）。
- **建议修复方案**：将路由验证与写入热路径解耦：保留 `OnRoutingChangedListener` 与启动确认期（`routeConfirmed == false` 或等待蓝牙切换期）检查 `routedDevice`，确认通过且未变更时在热路径仅做内存原子变量快速校验与体积变化时才调 `track.setVolume()`；同时若保留周期性兜底校验防极端 ROM 漏回调，降频到每 ~500 ms–1000 ms 或仅在状态切换点探测一次，且在热路径写入成功后避免持锁分配对象。
- **修复风险与兼容性注意**：ADR-0020/0039 高度重视蓝牙未通前及异常回落时绝对不能外放语音，因此优化降频**绝对不能弱化**首帧放音前与 `OnRoutingChangedListener` 同步拦截机制（`track` 初始 `setVolume(0f)` 必须在实测确认路由安全后才置 `1f`；在发生蓝牙切出且系统先切轨后发回调的 1 个 10 ms 窗口，原逻辑依赖热路径 `getRoutedDevice()` 早于 Broadcast 发现回退——若移除热路径 `getRoutedDevice()`，可考虑比对开销极低的 JNI/Java `track` 当前状态或在 Android 7+ 借助 `AudioTrack.getRoutedDevice()` 的轻量化替代与快速降频策略权衡安全性）。
- **回归测试建议**：在已有 `BluetoothPlaybackProtectionTest` 验证安全性不退步的前提下，为 `AndroidPlaybackOutput` 或其抽象补充稳态 100 次写入期间 `setVolume(1f)` 与路由查询次数的可控桩计数测试，再用真机 Perfetto 对比优化前后 Binder/GC 次数。
- **关联历史/ADR**：[ADR 0039](file:///Users/monet/Documents/TS3-mobile/docs/decisions/0039-media-friendly-voice.md)。

---

#### [AUD-009] `SessionExitRunner` 2 秒预算超时后绕过 `sessionMutex` 执行 `stopAudio()`，与在途连接操作并发冲突

- **ID**：`AUD-009`
- **标题**：`SessionExitRunner` 等待 `host.sessionMutex.lock()` 时触发 2 秒退出超时，会在未持锁情况下强行调用 `stopAudio()`
- **严重级别**：`P2`
- **置信度**：`Confirmed — Reproduced`
- **所属模块**：`app` (`io.github.ts3mobile.app.service`)
- **涉及文件与行号**：
  - [SessionExitRunner.kt#L46-L97](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt#L46-L97)、[#L115-L132](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt#L115-L132)
  - 关联操作持有 `sessionMutex`：`ConnectionCoordinator.kt#L603`、`L925`、`L1103`
- **问题归属**：TS3-Mobile 一方并发设计缺陷
- **问题描述**：为确保用户断开或划掉任务后 2 秒内必回收硬件并消去通知，`SessionExitRunner.run()` 将协议退场、`host.sessionMutex.lock()` 与 `stopAudio()` 共同包进 `withTimeoutOrNull(remaining)`；一旦在等待 `host.sessionMutex.lock()` 期间超时取消（例如在途的 `performConnectionAttempt` 正卡在不可中断的 `InetAddress.getByName()` DNS 查询或阻塞读/启动音频，虽外层已关闭旧 `captured` 但旧尝试可能处于 `createIdentity()` / `startAudioRouting()` / `startPlayback()` 尚未退出 `sessionMutex`），`audioStopped` 仍为 `false`，随后 `finally` 在 `NonCancellable` 块中**完全不持 `sessionMutex` 直接并发执行 `stopAudio()`**！此外，若超时恰好落在 `stopAudio()` 执行 `stopPlayback()` 之后、`audioStopped = true` 之前，`finally` 会再次调用第二次 `stopAudio()`。
- **复现路径或触发时序**：
  1. `performConnectionAttempt()` 正持有 `host.sessionMutex` 阻塞于 `createIdentity()`、DNS 解析或耗时音频重启；
  2. 用户触发断开（或划卡 `TASK_REMOVED`）超过剩余退出预算（2,000 ms）；
  3. `SessionExitRunner` 的 `withTimeoutOrNull` 取消等待 `host.sessionMutex.lock()`；
  4. `finally` 检查 `if (!audioStopped)` 为 `true`，跳过 `sessionMutex` 直接与仍持有互斥锁的旧操作并排调用 `releaseCapture()` / `stopPlayback()` / `stopAudioRouting()`。
- **影响分析**：① 可能在旧协程刚调用完 `startAudioRouting()`/`startPlayback()` 尚未结束锁区间时与之并排执行 `stopAudioRouting()`/`stopPlayback()`，或与快速紧随的新 `beginConnection` 退出队列前造成音频路由器、播放器和麦控制状态踩踏；② 若旧操作阻塞超过预算且未响应取消，`sessionMutex` 下一次被释放前硬件可能被错序启停。
- **根因分析**：在设计注释承认“只有互斥锁释放后才能安全回收共享音频（[L63-L64](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt#L63-L64)）”，却又为了兜底 2 秒清理而在 `finally` 中直接绕过了这把唯一护锁，且未将单个步骤幂等标记分开跟踪。
- **代码证据**：
  - [SessionExitRunner.kt#L65-L71](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt#L65-L71)：持锁路径在 `withTimeoutOrNull` 内，可被超时打断。
  - [SessionExitRunner.kt#L89-L97](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt#L89-L97)：`finally` 的 `stopAudio()` 未获取 `host.sessionMutex`。
- **复现/验证证据**：通过 `/tmp/ts3_audit_repro/AuditRepro.kt` 的 `repro8_sessionExitTimeoutRunsStopAudioWithoutMutex()` 实测：另一持有者锁住 `sessionMutex`、退出预算触发超时的情况下，`stopPlayback()` 在 `mutex.isLocked == true` 时被直接并发执行：`stopPlaybackCalledWhileMutexHeldByOther=true`。
- **建议修复方案**：
  1. 区分“用户感知通知/UI 离场（2 s 硬上限可先行完成通知与状态转移）”与“硬件音频串行互斥回收（必须经 `host.sessionMutex.withLock` 执行，确保任何时刻音频生命周期不并发）”；
  2. 将阻碍 `sessionMutex` 长时间不放的操作（如慢 DNS `InetAddress.getByName` 移到拿 `sessionMutex` 与起音轨之前执行，或可取消关闭）移出锁内；
  3. 记账拆分 `captureReleased`/`playbackStopped` 幂等标志或保证 `stopAudio()` 全程受单次状态保护，避免中途超时后重复清理。
- **修复风险与兼容性注意**：注意 `ConnectionCoordinator.beginConnection()` 开头会 `.join()` 旧 `exitJob`，因此若 `exitJob` 等着不可中断旧操作释放 `sessionMutex`，把 `disconnect` 的阻塞 DNS 操作移出 `sessionMutex` 可根本缩减锁持有时间至毫秒级。
- **回归测试建议**：补充 `SessionExitRunnerTest`：持有 `sessionMutex` 超出预算 `deadlineMs` 后放锁，验证 ① 不得在锁仍被持有时调用 `stopPlayback()`/`stopAudioRouting()`；② UI/退出通知仍可在预算点解除阻塞；③ 锁最终释放后硬件仍被安全停机。
- **关联历史/ADR**：[SessionExitRunnerTest.kt](file:///Users/monet/Documents/TS3-mobile/app/src/test/java/io/github/ts3mobile/app/service/SessionExitRunnerTest.kt)。

---

#### [AUD-010] `LocalMicrophoneTrial` / `MicrophoneTrial` 在主线程执行原生 `AudioRecord` / RNNoise 创建及最长 1.5 秒阻塞 `join()`，且 `isInputSilenced` 无锁竞态

- **ID**：`AUD-010`
- **标题**：本地麦克风试音与常规生命周期（切后台/退出/切换模式）在主线程同步调用原生采集构造及阻塞 `stop()`（`Thread.join(1500)`），且状态检查无锁读已释放对象
- **严重级别**：`P2`
- **置信度**：`Confirmed — Static Proof`
- **所属模块**：`app` + `audio-opus` (`TeamSpeakService` / `MicrophoneTrial` / `OpusMicrophoneCapture`)
- **涉及文件与行号**：
  - [TeamSpeakService.kt#L81-L92](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L81-L92)、[#L248](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L248)、[#L267](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L267)、[#L317](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L317)、[#L639](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L639)、[#L659](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L659)、[#L730](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L730)
  - [MicrophoneTrial.kt#L61-L109](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/MicrophoneTrial.kt#L61-L109)
  - [OpusMicrophoneCapture.kt#L59-L62](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusMicrophoneCapture.kt#L59-L62)、[#L173-L190](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusMicrophoneCapture.kt#L173-L190)
  - [CaptureResources.kt#L36-L81](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/CaptureResources.kt#L36-L81)
- **问题归属**：TS3-Mobile 一方代码缺陷
- **问题描述**：正式通话录音控制器 `MicrophoneController` 严谨地通过 `captureDispatcher = Dispatchers.IO` 加 `MicrophoneTeardown`（先 `requestStop()` 再异步释放）避免卡死主线程，`OpusAudioPlayer.stop()` 亦显式判别 `Looper.myLooper() != Looper.getMainLooper()` 才做 `join()`。唯独本地试麦 `microphoneTrial` 直接使用 `Dispatchers.Main.immediate`（[TeamSpeakService.kt#L82](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L82)），并在主线程同步完成：① `CaptureResources.create()`（实例化 `AudioRecord` + `NativeRnNoiseProcessor` + 原生音效 + 启动录音）；② 在 `onStartCommand`（连接/断开）、`onDestroy`、切后台 `setAppVisible(false)`、弹窗关闭、路由变化、切麦模式等所有主线程入口同步调用 `capture.close()` → `activeWorker?.join(1_500L)`！若录音线程正阻塞在 OEM HAL `AudioRecord.read(READ_BLOCKING)` / `record.stop()` 上，主线程将被卡住最长 1.5 秒；若恰逢 `onStartCommand` 处理 `startForegroundService()` 启动，还会压缩 5 秒 FGS 限期，并可能令 `OpusMicrophoneCapture.stop()` L189 `check(isReleased)` 直接在主线程抛出 `IllegalStateException`。此外，`OpusMicrophoneCapture.isInputSilenced`（[L59-L62](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusMicrophoneCapture.kt#L59-L62)）每 100 ms 在试麦协程中直接无同步访问 `resources?.record?.activeRecordingConfiguration`，而工作线程 `finally` 正在调用 `captureResources.close()`（执行 `record.release()` 且释放后才置 `resources = null`），并发时机下访问已释放 `AudioRecord` 状态存在未捕获异常风险（外层 `MicrophoneTrial` L77 捕获后误标记为 `FAILED`）。
- **复现路径或触发时序**：用户在“麦克风试音”弹窗运行中按 Home 切后台、点连接按钮，或 OEM HAL 延迟释放 `AudioRecord` 时，主线程 `onPause`/`onStartCommand` 同步进入 `microphoneTrial.stop()` → `OpusMicrophoneCapture.stop()` → `worker.join(1500)`。
- **影响分析**：导致界面掉帧/短时卡死，极端情况下诱发 ANR、主线程 `IllegalStateException`（`stop` 超 1.5s）及试麦偶发误报“麦克风试音失败”。
- **根因分析**：`LocalMicrophoneTrial` / `MicrophoneTrial` 复用旧模式把所有操作放在 `Main.immediate` 以换取“返回布尔值立即可判断 `isInputReleased`”，未沿用 `MicrophoneController` 的 `captureDispatcher = Dispatchers.IO` 异步串行互斥释放机制。
- **代码证据**：
  - [TeamSpeakService.kt#L82](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L82)：`val scope = CoroutineScope(serviceScope.coroutineContext + Dispatchers.Main.immediate)`。
  - [OpusMicrophoneCapture.kt#L173-L190](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusMicrophoneCapture.kt#L173-L190)：同步 `record.stop()` 与 `activeWorker?.join(1_500L)`。
  - [OpusMicrophoneCapture.kt#L59-L62](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusMicrophoneCapture.kt#L59-L62) vs [#L293-L299](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusMicrophoneCapture.kt#L293-L299)：`isInputSilenced` 无 `lifecycleLock` 且不校验 `!resources.released` 与 `isCapturing`。
- **复现/验证证据**：全链路代码走查确认（所有调用点均在 Main 线程且 `OpusMicrophoneCapture.stop()` 直接阻塞 `Thread.join`）。
- **建议修复方案**：
  1. 将 `MicrophoneTrial` 启停（`CaptureResources` 创建与 `capture.close()`）全部卸载至 `Dispatchers.IO` 串行互斥器执行；主线程 `stop()` 请求仅立即发送 `requestStop()` 与置失效令牌，并在需要串行等待采集归还时（例如准备连接启用通话麦之前，`MicrophoneController.reconcile` 在 IO 上已具备串行机制）异步协调等待，且在 `onStartCommand` 首行尽快完成 `startForeground()` 促进；
  2. 修复 `OpusMicrophoneCapture.isInputSilenced`，在 `synchronized(lifecycleLock)` 内检查 `if (control?.running?.get() != true || resources?.released != false) return false`，安全调用并兜底捕捉已释放状态。
- **修复风险与兼容性注意**：试麦弹窗快速关闭再打开、或关闭试麦立即切换 `CONTINUOUS` 麦模式时，必须保证基于同一 IO 串行队列或独占硬件租约等待上一试麦 `AudioRecord` 完全 `release()` 后才创建下一个 `AudioRecord`。
- **回归测试建议**：补充试麦并发切换单测：模拟慢 `AudioRecord.close()`，验证主线程调 `stop()` 立即可返回而不阻塞调用线程，且工作麦随后能正常异步接管。
- **关联历史/ADR**：与 `MicrophoneTeardown.kt` 同源设计。

---

#### [AUD-011] `ts3j` `HighPrecisionTimer` 在整段会话期间以 50 Hz 忙等自旋常驻运行且漂移钳位符号反转

- **ID**：`AUD-011`
- **标题**：`ts3j` 固定版 `HighPrecisionTimer`（`TS3J AudioThread`）在仅挂接未激活发射源时仍常驻 20 ms 轮询 + 忙等自旋，且漂移补偿算式方向写反
- **严重级别**：`P2`
- **置信度**：`Confirmed — Static Proof`
- **所属模块**：外部库 `ts3j-ts3mobile-1` + `app` / `ts3-protocol` 挂载机制
- **涉及文件与行号**：
  - 外部 `ts3j-ts3mobile-1.jar`：`HighPrecisionTimer`（`run()` 字节码 0–75；`sleepFor()` 字节码 0–40）与 `LocalTeamspeakClientSocket$MicrophoneTask.run()`
  - 一方常驻挂接点：[MicrophoneController.kt#L75-L77](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/MicrophoneController.kt#L75-L77)、[Ts3jSessionClient.kt#L193-L196](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L193-L196)
- **问题归属**：**混合归因**：第三方库实现问题 + TS3-Mobile 一方整会话常驻挂载 `voiceSource` 放大影响
- **问题描述**：`ConnectionCoordinator` 连接期间即通过 `attachMicrophoneTo()` 向 `Ts3jSessionClient` 持久挂接了 `MicrophoneVoiceSource`（即使当前处于 `OFF` / 未按 `PTT` 静默模式）。只要 `socket.setMicrophone()` 非空，固定 `ts3j` 便会启动名为 `TS3J AudioThread` 的 `HighPrecisionTimer(20ms, ...)` 永久运行至断连。反编译字节码证实两个缺陷：① `sleepFor(diff)` 用 `LockSupport.parkNanos` 后，**返回 `true` 即进入纯粹 `while (System.nanoTime() < nextTick);` 空循环忙等**；② `run()` 的漂移重置语句（字节码 34–54）写成 `if (nextTick - now > maxDrift) nextTick = now - maxDrift;`——原意应是“落后超过 `maxDrift` 时追赶重设”，却把比较式写成“超前（正常未触发或早醒）超过 `maxDrift` 时把 `nextTick` 减到当前时间之前”，触发下一次零休眠急追与调度失真；③ 发射音频期间，每隔 20 ms 唤醒即触发 `Poll` 链调用 `OpusMicrophoneCapture.pollTimedEncodedFrame()` → `publishState()` 取得 `OpusMicrophoneCapture.lifecycleLock`（与采集线程互竞同一把锁）。
- **复现路径或触发时序**：任何普通连接即使全程保持麦克风关闭（`MicrophoneMode.OFF`）纯听 1 小时，`TS3J AudioThread` 也始终每 20 ms 被唤醒一次（~180,000 次/小时）查 `isMuted()`/`isReady()` 并伴随自旋等待；若线程被调度短暂延迟后复醒或满足反符号阈值还会产生节奏重设突发请求。
- **影响分析**：在仅收听后台挂机场景增加无效小核唤醒、无谓锁竞争及额外电池消耗；发送期 20 ms 发包节拍精度与锁争用亦受其干扰。
- **根因分析**：`ts3j` 上游以独立实时自旋线程轮询拉取模型设计 `Microphone`，而一方为免去频繁切换线程选择在连接生命周期一直挂着 `voiceSource`（仅依靠 `isReady()` 返回 `false` 抑制发送）。
- **代码证据**：
  - `ts3j-ts3mobile-1.jar` `HighPrecisionTimer.run()` `javap -c` 字节码 34–75：
    `lload_3; lload_1; lsub; aload_0; getfield maximumDriftInNanos; i2l; lcmp; ifle 54; lload_1; ... lsub; lstore_3` 及字节码 64–72 忙等循环 `lload_3; System.nanoTime(); lcmp; ifle 6; goto 64`。
  - [MicrophoneController.kt#L75-L77](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/MicrophoneController.kt#L75-L77)：连接即调用 `session.setVoiceSource(voiceSource)` 且后续模式切 `OFF` 时未卸下。
- **复现/验证证据**：通过 JDK 17 `javap -c` 对锁定 JAR `ts3j-ts3mobile-1.jar` 字节码完整反汇编核验，并对照 [OpusMicrophoneCapture.kt#L198-L204](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusMicrophoneCapture.kt#L198-L204) 确认锁获取。
- **建议修复方案**：
  - **一方侧立即可做优化**：在仅当麦开启发射期（`voiceSource.tryEnable()` 成功期间）才挂接 `session.setVoiceSource(voiceSource)`，停止发送时即时设 `null` 停止 `TS3J AudioThread`（注意 `MicrophoneTask` 的停止包 `codecData = new byte[0]` 需由协议封装先发完尾包后再异步停定时器，否则对端头像发言灯可能延时至静音超时才熄灭）；同时精简 `pollTimedEncodedFrame()` 避免发声期每 20 ms 拿 `lifecycleLock` 查无关状态；
  - **上游/Fork 修复**：向自有 `ts3j` fork 提交修正 `HighPrecisionTimer` 漂移方向，并将忙等/轮询替换为精确阻塞队列或仅在存在活动发射源期间按 `ScheduledExecutor` / 阻塞读取触发发包。
- **修复风险与兼容性注意**：PTT 快速连按连放期间，`setMicrophone(null)` 与 `setMicrophone(mic)` 会触发旧 `HighPrecisionTimer` `gracefulStop()` 与新建线程（须核实 `LocalTeamspeakClientSocket.setMicrophone()` 是否发尾帧并妥善同步，避免截断对端说话结束标记）。
- **回归测试建议**：增加仅收听模式下连接期线程活跃计数及挂麦/摘麦尾帧发送测试。
- **关联历史/ADR**：[VOICE_TIMING_AND_ACTUAL_DEVICES_2026-11-23_ZH.md](file:///Users/monet/Documents/TS3-mobile/docs/testing/VOICE_TIMING_AND_ACTUAL_DEVICES_2026-11-23_ZH.md)。

---

#### [AUD-012] 第三方 `ts3j` 握手使用非密码学安全 `java.util.Random` 且缺乏服务端公钥固定机制与最新依赖升级

- **ID**：`AUD-012`
- **标题**：`ts3j-ts3mobile-1` 以 `java.util.Random` 生成握手密钥材料与临时随机数，且锁定多款已停止维护传递依赖
- **严重级别**：`P2`（按实际可控利用成本从“不可直接远程利用但属明显密码学实践缺陷与供应链债务”校准定级）
- **置信度**：`Confirmed — Static Proof`
- **所属模块**：外部库 `ts3j-ts3mobile-1` + `ts3-protocol` ([build.gradle.kts](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/build.gradle.kts#L57-L62))
- **涉及文件与行号**：
  - 外部 `ts3j-ts3mobile-1.jar`：`LocalClientHandlerConnecting`（`INIT1` Step 0 与 `createInitIv()` 字节码）
  - [ts3-protocol/build.gradle.kts#L57-L62](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/build.gradle.kts#L57-L62)
- **问题归属**：**混合归因**：第三方库密码学工程弱点 + TS3 协议生态无 CA 链机制，及一方对陈旧依赖版本约束
- **问题描述**：① 字节码核查证实，`LocalClientHandlerConnecting` 生成 Step 0 初始化随机令牌 `randomBytes`（4 字节）与进入共享对称密钥 `Ts3Crypt` 派生输入的核心客户端随机数 `alphaBytes`（10 字节，`clientinitiv`）时，均直接 `new java.util.Random().nextBytes(...)`（可预测 LCG 状态随时间推导），未使用 `java.security.SecureRandom`；② UDP 会话虽在建立后采用 AES-EAX（128-bit 键 / 64-bit truncated MAC），且支持配置加密语音（若服务端开启），但通道与音频报文本身依赖客户端与服务器 ECDH/ECC 协商而**客户端没有任何已知服务器公钥/指纹固定（TOFU / Pinning）或告警机制**（TS3 协议传统痛点叠加客户端零防护，在公共 Wi-Fi 上面临首包未加密握手/主动中间人冒充服务器风险）；③ 传递依赖锁停留在多款已 EOL 库：`bcprov-jdk15on:1.67`（2020 年，后续版本有多项解析 CVE 修正，需评估 BouncyCastle 是否存在触发面）、`commons-lang:2.6`（EOL）、`dnsjava:2.1.8`（2017 年，缺现代安全修补）、`ini4j:0.5.1`（2009 年停更）。
- **复现路径或触发时序**：每次通过 `Ts3jSessionClient` 连接服务器自动触发上述逻辑。
- **影响分析**：
  - **加密随机数弱点**：若同一毫秒窗外侧或可观测 `System.currentTimeMillis()` 与弱 LCG 种子关联，会降低客户端临时随机数 `alphaBytes` 的熵强度；
  - **无服务器认证**：主动网络中间人可在不持有用户私钥的前提下冒充任意主机/端口的 TS3 服务器骗取用户连接状态和语音频道会话内容（服务器不向客户端证明预先约定身份）；
  - **老旧传递库**：增加静态 CVE 扫描与供应链合规风险（注：本应用未开放恶意任意 JAR 插件，传递库受 R8 裁剪）。
- **根因分析**：下游严格继承自 `manevolent/ts3j` 上游历史实现及 `docs/decisions/0004` 为可复现源码构建锁定的四件历史依赖版本。
- **代码证据**：
  - `ts3j-ts3mobile-1.jar` `LocalClientHandlerConnecting` 字节码：Step 0 行 19 `new java/util/Random`；`createInitIv()` 行 8 `new java/util/Random` → `Random.nextBytes(alphaBytes)`。
  - [ts3-protocol/build.gradle.kts#L58-L61](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/build.gradle.kts#L58-L61)：明确锁定 `bcprov-jdk15on:1.67` 等版本约束。
- **复现/验证证据**：通过 `javap -c` 直接确认 `java/util/Random` 字节码调用与 `PacketTransformation` AES-EAX 算法参数。
- **建议修复方案**：
  1. 在自有 `ts3j` fork 中将所有密码学随机数源全面替换为 `SecureRandom`；
  2. 为已保存书签或历史记录补充服务器唯一身份（`serverUid`）TOFU（首次信任与变更告警）机制（`StateAwareTeamspeakClientSocket` 已解析并记录 `virtualserver_unique_identifier` 到 `Snapshot.serverUid`，当前仅用于校验附件归属，未用于端到端服务器身份变动警告！）；
  3. 有计划地评估并将 `bcprov-jdk15on` 升级至新版 `bcprov-jdk18on` 等受维护分支并更新 SHA-256 锁文件。
- **修复风险与兼容性注意**：`Ts3j` 替换 `bcprov` 需确认 `proguard-rules.pro` 保持对应类路径；引入 TOFU 变更告警时应向多台轮询/重建服务器提供明确的用户二次确认界面而非硬性阻断。
- **回归测试建议**：针对 `ts3j` 握手随机数测试验证不再引用 `java.util.Random`；若接入 TOFU，为已知 UID 变更场景编写端到端警告提示与阻断单测。
- **关联历史/ADR**：`docs/decisions/0004-take-ownership-of-ts3j-dependency.md`、[Ts3jSessionClient.kt#L539](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jSessionClient.kt#L539)。

---

### P3 级缺陷

#### [AUD-013] 已连接期每秒推送完整服务状态引发不必要重组，及悬浮 PTT 启用期每 100 ms 主线程密集轮询

- **ID**：`AUD-013`
- **标题**：诊断在线时长每秒刷新触发顶层 `TeamSpeakServiceState` 发射，悬浮 PTT 启用期间每 100 ms 在主线程进行多项系统服务查询与全成员筛选
- **严重级别**：`P3`
- **置信度**：`Confirmed — Static Proof`
- **所属模块**：`app` (`ConnectionMonitor` / `TeamSpeakService` / `ConnectedPttProbeHost`)
- **涉及文件与行号**：
  - [ConnectionMonitor.kt#L88-L89](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionMonitor.kt#L88-L89)
  - [ConnectionCoordinator.kt#L1071](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L1071)
  - [TeamSpeakService.kt#L341-L344](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L341-L344)
  - [ConnectedPttProbeHost.kt#L60-L70](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/probe/ConnectedPttProbeHost.kt#L60-L70)、[#L114-L135](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/probe/ConnectedPttProbeHost.kt#L114-L135)、[#L181-L198](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/probe/ConnectedPttProbeHost.kt#L181-L198)
- **问题归属**：TS3-Mobile 一方性能优化项
- **问题描述**：① `ConnectionMonitor.watch()` 每 1,000 ms 报告 `Tick` 调起 `host.refreshDiagnostics()`，因 `DiagnosticsSnapshot.uptimeMs` 每秒递增，致使整个 `TeamSpeakServiceState` 状态流每秒全量产生新实例，推动正收集该 `StateFlow` 的所有 Compose 订阅方（即便当前停留于频道/成员列表而非诊断页，甚至后台保活通知无关页）每秒重评估；② 当悬浮 PTT 浮窗开启并处于后台游戏等环境时，`ConnectedPttProbeHost.tick` 每 100 ms（10 Hz）在主线程连续调用 `power.isInteractive`、`keyguard.isKeyguardLocked`、`Settings.canDrawOverlays`、`notifications.areNotificationsEnabled` 与 `audio.mode`（跨系统服务 Binder 查询），同时每次状态改变触发 `connectedPttOverlayDetails()` 遍历整个快照筛选成员。
- **复现路径或触发时序**：保持连接停留前台主页面即持续每秒发生；切后台启用悬浮 PTT 即持续 10 Hz 轮询。
- **影响分析**：中大型频道或低端设备下增加小幅度主线程调度、对象分配与无效 Compose 对比/功耗（由于部分页面有 `remember` / 局部比较，未达到明显掉帧，故定级 P3）。
- **根因分析**：① 在线时长未拆作独立轻量读数流或仅在诊断页面可见时订阅，而是直接耦合进了顶层全局 `TeamSpeakServiceState`；② 悬浮窗可用性校验未完全依赖 `BroadcastReceiver`/回调驱动，采用保守 100 ms 轮询。
- **代码证据**：如上述行号所示。
- **复现/验证证据**：静态走查与代码语义确认。
- **建议修复方案**：将 `uptimeMs` 或实时诊断数字移出 `TeamSpeakServiceState`（例如独立 `StateFlow` 仅在诊断界面订阅收集），或者由 UI 端以本地 `LaunchedEffect` 计算显示时长；`ConnectedPttProbeHost` 尽可能将状态检查变为事件广播驱动或延长非按键期轮询间隔（如在锁屏/息屏已监听条件下对剩余状态降频）。
- **修复风险与兼容性注意**：确保不影响音频诊断观察或 `ActualAudioDevices` 的事件响应及时性。
- **回归测试建议**：运行已有连接悬浮窗与诊断页面回归测试，辅以真机 Android Studio Layout Inspector 重组计数对比。
- **关联历史/ADR**：无。

---

#### [AUD-014] 服务创建主线程同步读盘诊断/偏好文件，及备份排除规则遗漏两项新增 `DataStore` 文件

- **ID**：`AUD-014`
- **标题**：`sessionDiagnosticWriter` 初始化同步在主线程读盘并伴主线程首读 SharedPreferences，且 `backup_rules.xml`/`data_extraction_rules.xml` 遗漏 `ts3_playback` 与 `ts3_updates`
- **严重级别**：`P3`
- **置信度**：`Confirmed — Static Proof`
- **所属模块**：`app` (`Ts3MobileApplication` / `TeamSpeakService` / 资源配置)
- **涉及文件与行号**：
  - [Ts3MobileApplication.kt#L31-L33](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/Ts3MobileApplication.kt#L31-L33)、[TeamSpeakService.kt#L125](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L125)、[DiagnosticJournal.kt#L74-L84](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/DiagnosticJournal.kt#L74-L84)
  - [SessionDiagnostics.kt#L88-L89](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionDiagnostics.kt#L88-L89)、[BackgroundRuntimeController.kt#L30](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/BackgroundRuntimeController.kt#L30)
  - [backup_rules.xml](file:///Users/monet/Documents/TS3-mobile/app/src/main/res/xml/backup_rules.xml)、[data_extraction_rules.xml](file:///Users/monet/Documents/TS3-mobile/app/src/main/res/xml/data_extraction_rules.xml)
  - 新增遗漏点：[PlaybackPreferences.kt#L17](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/settings/PlaybackPreferences.kt#L17)、[UpdateChannelDataStore.kt#L13](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/updates/UpdateChannelDataStore.kt#L13)
- **问题归属**：TS3-Mobile 一方工程规范一致性与配置微瑕
- **问题描述**：① `Ts3MobileApplication.audioDiagnostics` 明确在注释中强调“Never read history on the service's main thread”而放在 `audioDiagnosticScope.async` 中延迟读取，然而仅隔数行的 `sessionDiagnosticWriter` 却直接在属性 getter 时于主线程 `TeamSpeakService.onCreate()` 构造 `DiagnosticJournal`，从而**在主线程同步 `File.useLines()` 读取最多 32 KiB `session-events.tsv`**；紧接着 `SessionDiagnostics.start()` 与 `BackgroundRuntimeController.start()` 在主线程首读 `diagnostic_exit` / `background_voice` SharedPreferences 并查询 `getHistoricalProcessExitReasons`；② 清单虽已全量声明 `android:allowBackup="false"` 且两个排除 XML 列出了其它 8 个 `DataStore` 与 4 个 SharedPrefs，但遗漏了后增的非敏感偏好文件 `datastore/ts3_playback.preferences_pb` 与 `datastore/ts3_updates.preferences_pb`。
- **复现路径或触发时序**：每次冷启动绑定/拉起 `TeamSpeakService` 触发主线程 I/O；备份检查规则对比可见 XML 不对称。
- **影响分析**：StrictMode DiskRead 违例；低端闪存/极忙场景下冷启耗时略有增加；备份规则在设备迁移边际（虽然 `allowBackup="false"` 通常已整体禁绝）缺乏对齐一致性，不涉密钥/敏感数据泄漏。
- **根因分析**：新增诊断日志与偏好文件时未完全统一异步加载模板与排除清单核对。
- **代码证据**：对照 [Ts3MobileApplication.kt#L31-L44](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/Ts3MobileApplication.kt#L31-L44)（第 40 行异步 vs 第 32 行同步）；以及 `xml` 列表仅包含 8 项 `datastore/*.preferences_pb`。
- **复现/验证证据**：静态走查及 XML 比对完全确立。
- **建议修复方案**：将 `sessionDiagnosticWriter` 磁盘预读（`journal` 初始化加载）与事件导出等待同步改造为同 `audioDiagnostics` 一样的 IO 作用域加载；在 XML 中补齐 `ts3_playback.preferences_pb` 与 `ts3_updates.preferences_pb`。
- **修复风险与兼容性注意**：确保早期 `Service` 启动瞬间产生的前几条内存事件在后台历史加载完成后按时间/序列合并且不丢失。
- **回归测试建议**：在 `OfflineAudioDiagnosticsTest` 同级新增测试，断言初始化 `sessionDiagnosticWriter` 不在调用线程执行文件读取（仿照其注释提到的验证法），及 XML 枚举核对工具脚本断言 DataStore 名称与 XML 1:1 匹配。
- **关联历史/ADR**：关联 `OfflineAudioDiagnosticsTest`。

---

#### [AUD-015] Debug 探针触发 `InlinedApi` Lint 告警，及自动重连退避缺少随机抖动与断网外上限熔断

- **ID**：`AUD-015`
- **标题**：`FloatingPttProbeService` 缺少 API 版本或 `@SuppressLint` 注释致 Debug Lint `InlinedApi`，且 `ReconnectPolicy` 无抖动（Jitter）与最大重试上限
- **严重级别**：`P3`
- **置信度**：`Confirmed — Static Proof`
- **所属模块**：`app` (`debug` 源集 & `service`)
- **涉及文件与行号**：
  - [FloatingPttProbeService.kt#L141](file:///Users/monet/Documents/TS3-mobile/app/src/debug/java/io/github/ts3mobile/app/probe/FloatingPttProbeService.kt#L141)（`ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE` 未注解）
  - [ReconnectPolicy.kt#L11-L18](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ReconnectPolicy.kt#L11-L18)、[ReconnectEngine.kt#L106-L139](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ReconnectEngine.kt#L106-L139)
- **问题归属**：TS3-Mobile 一方工程规范与韧性完善项
- **问题描述**：① 正式代码 `SessionNotifications.kt:134` 为 `FOREGROUND_SERVICE_TYPE_MICROPHONE` 加上了 `@SuppressLint("InlinedApi")`（由 `ServiceCompat.startForeground` 内部保护低版本），但 Debug 专用 `FloatingPttProbeService.kt:141` 未添加保护，导致 `:app:lintDebug` 报错级别 Warning `InlinedApi`（若未来开启 `warningsAsErrors` 或在 API < 30 实机静态扫描会报错）；② 生产自动重连队列 `DEFAULT_DELAYS_MS = [1s, 2s, 4s, 8s, 15s, 30s]` 既不含随机抖动（同一服务器宕机重启时全体移动端客户将在固定 15s / 30s 齐步重连冲击），也无最长总重试时间或次数限制，只要服务器长期失联且每次均发生 `TimeoutException`，客户端会每 30 秒持续在后台无限循环重连直到人工断开（注意 WakeLock 在 `SessionPowerPolicy` 上限 120s 保护，但服务与 30s 周期网络任务本身无限持续）。
- **复现路径或触发时序**：执行 `./gradlew :app:lintDebug` 直接观察 `InlinedApi` 告警；服务器长时间关机后客户端处于有网状态即按每 30 秒一直循环尝试。
- **影响分析**：Debug 静态质量门禁存在可控告警；远端服务长期维护下造成客户端无谓无线通信重连功耗及服务端恢复瞬间同步并发惊群。
- **根因分析**：`FloatingPttProbeService` 为内部 debug 探针遗漏注解；`ReconnectEngine` 刻意设计为持续尝试恢复直至成功或断网/手断，暂未规划带 Jitter 与退避退火（例如数小时后降频或暂停提示）。
- **代码证据**：Lint XML 输出与 [ReconnectPolicy.kt#L17](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ReconnectPolicy.kt#L17) 实测明确。
- **复现/验证证据**：本次实测 `app/build/reports/lint-results-debug.xml` 明确记录 `[Warning] InlinedApi @ .../FloatingPttProbeService.kt:141`。
- **建议修复方案**：为 `FloatingPttProbeService.kt:141` 外包函数补充与 `SessionNotifications` 一致的注解/API 检查；为 `ReconnectPolicy` 引入轻量全抖动（如 ±20%）并将长时失败后周期放大到 1–5 分钟或增设合理上限后给出手动重试入口。
- **修复风险与兼容性注意**：添加 Jitter 需保持 `ReconnectPolicy` 可注入随机数或范围区间，以免打破已有 `SessionRecoveryTest` 对确定性虚拟调度器退避时长的精确断言。
- **回归测试建议**：在 `ReconnectPolicyTest` 验证抖动区间有界及上限降频，并检查 `lintDebug` 清零 `InlinedApi`。
- **关联历史/ADR**：`SessionNotifications.kt:134`。

---

## 5. 待验证风险清单

> 以下条目属于 **Probable**（强推断但依赖极窄极端条件尚未跑通复现）或 **Unverified**（因缺乏真机、物理蓝牙设备、实服或高负载场景而无法给出确定性结论），**不计入第 4 节已确认缺陷**，但应在发版或进一步评估前专项验证。

### `[RISK-001]` `ACTION_CONNECT` (`requireIdle=true`) 在极限生命周期交错中提前返回的 FGS 启动异常风险（`Probable`）
- **位置**：[TeamSpeakService.kt#L244-L247](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L244-L247)、[#L795-L802](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L795-L802)、[MainActivity.kt#L349,L361](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/MainActivity.kt#L349)
- **原因**：`TeamSpeakService.connect()` 通过 `ContextCompat.startForegroundService()` 发车；若用户点收藏/邀请链接后，在 `onStartCommand` 到达前某刻并发发生状态进入 `DISCONNECTING` 且此时前台通知恰巧刚因另一并发流程移除，L247 `if (requireIdle && !mutableState.value.status.phase.allowsOfflineActions) return START_NOT_STICKY` 会不经任何 `startForeground()` 调用直接返回（此外 L244 `intent?.toServerConfig() ?: return START_NOT_STICKY` 在内部 Intent 缺参数时亦同样不调 `startForeground()`）。常规情况下此时服务通常已在前台或先检查过空闲，触发几率极低但结构上欠缺在 `startForegroundService` 入口早退时像 L260 一样做保底 `rejectStart` 促进或强制校验。
- **验证方法**：在 Android 12–16 模拟器/真机上使用测试脚本在 `startForegroundService` 发车与 `onStartCommand` 处理之间注入并发 `finishExitOnMainThread` (`stopForeground`) 与 `requireIdle=true`，观察系统 `ActiveServices` 日志是否抛 `ForegroundServiceDidNotStartInTimeException`。

### `[RISK-002]` ADR-0039 `USAGE_MEDIA` + `VOICE_COMMUNICATION` 下听筒切换、LE Audio 与免提 AEC 实机兼容性（`Unverified`）
- **位置**：[AndroidPlaybackOutput.kt#L202-L209](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/AndroidPlaybackOutput.kt#L202-L209)、[CaptureResources.kt#L63](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/CaptureResources.kt#L63)、[docs/decisions/0039-media-friendly-voice.md](file:///Users/monet/Documents/TS3-mobile/docs/decisions/0039-media-friendly-voice.md)
- **原因**：为不影响同设备前台游戏/音乐，播放轨明确选用 `USAGE_MEDIA`，采集维持 `AudioSource.VOICE_COMMUNICATION`。在部分 Android OEM HAL 上：① 带 `USAGE_MEDIA` 的 `AudioTrack` 调用 `setPreferredDevice(TYPE_BUILTIN_EARPIECE)` 时，音频策略可能仍把媒体流路由到扬声器而拒绝听筒；② 免提外放时系统仅按 `VOICE_COMMUNICATION` 回声参考（常期待 `VOICE_COMMUNICATION` 输出流作内部 AEC 参考），导致设备硬件 `AcousticEchoCanceler` 抑噪变差产生回声；③ 切换 LE Audio 耳机（`TYPE_BLE_HEADSET`/`TYPE_BLE_SPEAKER`）在不同 Android 14–16 机型上的延迟特性与 `preferredInput` 强制选内置麦是否触发采样重配置未在本机实机运行。
- **验证方法**：按照 ADR-0039 验证矩阵，准备至少 2 台不同厂商物理机（如 Pixel AOSP 与小米/三星），同时连接经典 A2DP 耳机与 LE Audio 耳机，实测听筒模式、外放免提双人连麦（观察回声）以及耳机断连不漏音。

### `[RISK-003]` 后台弱网/Doze 模式下无 `WifiLock` 与系统禁止唤醒锁引发的断连波动与长时电量表现（`Probable` / `Unverified`）
- **位置**：[BackgroundRuntimeController.kt#L31,L66](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/BackgroundRuntimeController.kt#L31)、[SessionPowerController.kt#L10-L43](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/SessionPowerController.kt#L10-L43)
- **原因**：① 仅申请了可选项 `PARTIAL_WAKE_LOCK`（默认关闭，`keepCpuAwake = false`）而全仓未使用 `WifiManager.WifiLock`（`WIFI_MODE_FULL_LOW_LATENCY` / `HIGH_PERF`），手机息屏进入 Deep Doze 后 Wi-Fi 可能进入 PS-Poll 节能或关闭扫描，诱发 `ts3j` 30 秒无包看门狗断开并结合无上限重连（`AUD-015`）反复唤醒；② 国产系统自带“神隐/省电策略”往往从外部冻结后台服务 Socket 或静默忽略唤醒锁（注：`SessionDiagnostics` 与 `PHONE_CANDIDATE` 文档已诚实提及且不掩饰此厂商限制）。
- **验证方法**：在至少两款真实手机（开启默认后台管理 vs 加白电池优化）上息屏静置挂机 ≥30–60 分钟，结合 `adb shell dumpsys battery` 与应用内置 `audio-diagnostics`/`session-events` JSON 统计 `WATCH_EXECUTION_GAP`、丢包与重连次数。

### `[RISK-004]` 高并发多人语音混音、悬浮窗在国产 ROM 下的权限/保活与 118 项仪器测试实机空白（`Unverified`）
- **位置**：[ParticipantGainMixer.kt](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/ParticipantGainMixer.kt)、[ConnectedPttProbeHost.kt](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/probe/ConnectedPttProbeHost.kt)、`app/src/androidTest/`（118 文件）
- **原因**：本机未连接设备或启动模拟器，以下项目未在本轮验证：5–20 人同时在频道讲话时的多路 Opus 解码与混音 CPU/发热/内存抖动；小米/OPPO/vivo 等后台弹出悬浮窗及 `FOREGROUND_SERVICE_MICROPHONE` 的特殊系统限制；118 项 `androidTest`（含 UI、升级兼容、DataStore 进程杀除与音频路由实测）在当前 Commit 的通过状态。
- **验证方法**：在 CI 或本机挂载 API 35/36 arm64 模拟器完整执行 `:audio-opus:connectedDebugAndroidTest` 与 `:app:connectedDebugAndroidTest`，并在真机搭建多路并发压力夹具开展 1–4 小时连续语音/播放采样对比。

### `[RISK-005]` 加密频道的文件列表浏览与文件下载始终以空密码 `cpw=""` 提交，可能被要求校验频道密码的 TS3 服务器拒绝（`Probable`）
- **位置**：[Ts3jChannelFiles.kt#L162](file:///Users/monet/Documents/TS3-mobile/ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jChannelFiles.kt#L162)
- **原因**：`Ts3jChannelFiles.command()` 固定拼接 `CommandSingleParameter("cpw", "")`，而本地只读探针 `CHANNEL_FILES_PROBE_2026-11-24_ZH.md#L30` 明确只测试了“两个无密码的合成频道”。根据 TeamSpeak 3 服务端与客户端版本差异，虽然客户已处于该频道内时部分服务端版本可能免检或强制检 `cpw`（尤其是带密码频道下的 `ftgetfilelist` / `ftinitdownload` 查询或无免密绕过权限 `b_ft_ignore_password` 的普通访客），实测待定；若服务端校验 `cpw`，则会持续返回权限错误被客户端显示为 `REJECTED`。
- **验证方法**：在本地隔离 TS3 Server 3.13.9 增加一个带密码的合成频道（普通访客无 `b_ft_ignore_password`），用已知密码加入该频道后尝试浏览列表及下载文件即可实测确认。

---

## 6. 对抗性验证结果

本轮在初审与复核期间共沉淀候选问题假设 **21 项**。经过“对照完整调用链 + 阅读原测试/设计文档 + JDK 17 `javap`/`jshell` 反编译固定依赖字节码 + `/tmp/ts3_audit_repro` 独立编译运行真代码复现 + 盲审排查遗漏路径”的对抗性检验，汇总调整如下：

- **初始候选总数**：21 项
- **最终确认保留缺陷总数**：**15 项**（见第 4 节）
- **证伪剔除数**：**6 项**
- **合并处理项**：
  - 将初审中分散的“固定版 `ts3j` RSA 握手 `ArrayIndexOutOfBoundsException`”（第三方库未打补丁问题）与“一方 `ConnectionFailure` 误将该错误标为不可重试并令 `ReconnectEngine` 终态退出”（一方放大器问题）**合并为统一缺陷 [`AUD-004`]**，厘清两层共同决定影响面（单一独立报告易割裂因果）。
  - 将“同处于 `SessionExitRunner` 白板重建语句中分别丢失 `microphoneSettings`、`microphoneMode` 与 `audioDiagnostics` 的症状”合并入 [`AUD-003`]。
- **严重级别校准项**：
  - 初审曾考虑将 `ts3j` 缺乏服务端校验及陈旧 `bcprov` 列为高危安全项，复核后因实际攻击需要主动网络中间人或服务器自身妥协且移动端本地密钥保护（Keystore AES-GCM + 备份 AAD）扎实、同时客户端并未暴露不可信反序列化入口给非认证包，将 [`AUD-012`] 校准维持为 **P2**。
  - 将初始候选 `cpw=""` 与部分未复现 FGS 时序假设从“确认缺陷”降格并划入第 5 节 **`[RISK-005]` / `[RISK-001]`**，杜绝将未证实假说写进确认表。
- **独立盲审阶段新补录收获**：
  - 通读 `SessionExitRunner`、`ServiceAudioDiagnostics`、`DiagnosticsPanel` 时新发现 `audioDiagnostics` 同步遭断开重设归零而直接导致“标记音频问题”按钮静默失效（补充进入 [`AUD-003`]）；
  - 通读 `PlaybackSession` 蓝牙滞后路径时补充发现在 `output != null && !started` 状态转向静音后每 10 ms 持续 500+ 次触发 `routeReady` / `checkRoute()`（完善 [`AUD-006`] 并复现）；
  - 逐一对比 `Ts3MobileApplication` 与各 DataStore 时补发现 `sessionDiagnosticWriter` 主线程读 32 KiB 历史与 `xml` 备份规则漏掉 `ts3_playback` / `ts3_updates`（新增 [`AUD-014`]）。

### 已被明确证伪的 6 项初审假设

| 初审假设 | 证伪关键反证与出处 |
| --- | --- |
| **1. `AudioDeviceRouter.lifecycleLock` 与 `OpusAudioPlayer.lifecycleLock` 存在 AB-BA 死锁** | 实查调用图：[OpusAudioPlayer.kt#L214-L238](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusAudioPlayer.kt#L214-L238) 刻意使用局部 `changed` 标志在**退出 `synchronized(lifecycleLock)` 后**才回调 `onRouteChanged()` / `onUnsafeRoute()`；而 `PlaybackSession`（[#L65](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L65)）调 `prepareOutputRoute()` 时亦不持有播放器锁，锁序单调一致。 |
| **2. `BluetoothRouteMonitor` 在 API 33+ 使用 `RECEIVER_NOT_EXPORTED` 会收不到 `ACTION_AUDIO_BECOMING_NOISY`** | [BluetoothRouteMonitor.kt#L30-L31](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/BluetoothRouteMonitor.kt#L30-L31) 注册的 `ACTION_AUDIO_BECOMING_NOISY` 属系统受保护广播；Android 13/14+ 规范明确允许并推荐用 `RECEIVER_NOT_EXPORTED` 接收系统广播。 |
| **3. 后台悬浮 PTT 租约超时/切回应用后导致 `AudioRecord` 永久泄漏采集** | [ConnectedPttProbeHost.kt#L60-L70](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/probe/ConnectedPttProbeHost.kt#L60-L70) 每 100 ms 校对资格，叠加 Activity 回调 L89–106 及 [TeamSpeakService.kt#L638-L645](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/TeamSpeakService.kt#L638-L645) → `MicrophoneController.setAppVisible(true)`（[#L97-L101](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/MicrophoneController.kt#L97-L101)）强制调用 `disarmFloatingPtt()` → `requestStop()`。 |
| **4. 主线程 `ConnectionCoordinator.beginConnection()`（L290）与 `TeamSpeakService.onDestroy()`（L305）调用 `session?.close()` 会阻塞主线程导致 ANR** | 经 JDK 17 `javap -c` 核验 `ts3j-ts3mobile-1.jar` 中 `LocalTeamspeakClientSocket.close()` 全部 16 字节字节码：仅调用 `DatagramSocket.close()` 与非阻塞 `ExecutorService.shutdown()`，无任何 I/O 等待或锁等待。 |
| **5. 日志系统或诊断导出可能泄露用户身份、密码或完整服务器地址/聊天内容** | 全局正则排查生产源码全部 42 处日志及持久化格式：`ServerInvite` 与 `BookmarkConnection` 均重写 `toString()` 返回 `<redacted>`；`DiagnosticJournal`、`AudioDiagnosticArchive` 与 `SanitizedLogging` 严格只写枚举名与异常类型类名。 |
| **6. 播放线程晚醒 10–30 ms 会引起 `PlaybackPacer` 丢帧或音轨重缓冲卡顿** | 实查 [PlaybackSession.kt#L215-L230](file:///Users/monet/Documents/TS3-mobile/audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackSession.kt#L215-L230)：`MAX_CATCH_UP_NANOS = 50_000_000L`（50 ms）。10–30 ms 的晚醒小于 50 ms 阈值，`completed(now)` 保持原固定 `deadline + 10 ms` 追赶对齐并正常混音排空，不丢 Opus 包也不重建硬件轨；仅 >60 ms 延迟叠加 `VoiceReceiveQueue` 过期才会跨跃触发 `AUD-007`。 |

---

## 7. 工程质量评估

> 评分采用 0–10 分主观定性工程评级，完全基于本次可读源码、构建脚本、字节码与离线实测证据；缺乏真机数据的维度明确标示置信度边界。

| 评估维度 | 评分（0–10） | 审计依据与说明（含置信局限） |
| --- | :---: | --- |
| **1. 架构清晰度与边界控制** | **8.5** | 三模块依赖方向干净，纯 JVM 协议与抖动管线极高可测性；主要短板是主协调器（`ConnectionCoordinator` ~1,188 行、`TeamSpeakService` ~827 行）与 UI 局部尚待进一步拆分。 |
| **2. 代码可维护性与可读性** | **8.0** | 命名清晰，注释解释了关键“为什么”（如世代边界、锁顺序与为何不调用 ts3j 40s 阻塞断连）；部分超长函数与状态复制逻辑易漏字段（`AUD-003`）。 |
| **3. 并发安全与生命周期控制** | **6.0** | 整体世代设计（`SessionGeneration` / `IdentityUseGate` / `DefaultNetworkMonitor`）思路扎实，但出现了 `AUD-001` 主线程空指针/状态崩溃、`AUD-009` 超时破锁与 `AUD-010` 麦测主线程阻塞等关键边界裂缝，拉低实得分。 |
| **4. 协议实现正确性与鲁棒性** | **6.0** | `StateAwareTeamspeakClientSocket` 与文件传输在协议长度、防跳转与序列隔离方面克制严谨，但存在 `AUD-002` 连接中段断连僵尸态、`AUD-005` 默认服务器 `ip=` 兼容性断档，以及 `AUD-004` 默认未合入的 1/256 握手越界。 |
| **5. 实时音频与硬件链路工程质量** | **6.5** | JNI 内存安全（`opus_jni.cpp`/`rnnoise_jni.cpp` 长度校验与资源回滚）与 `PlaybackPacer` 节拍设计专业，但 `AUD-006`/`AUD-007`/`AUD-008`/`AUD-011` 暴露热路径分配 IPC、过期级联销毁及延迟路由泄漏等明显问题。 |
| **6. 蓝牙与音频路由保护安全性** | **7.0** | 对外放隐私的保护决心强（`BluetoothPlaybackGuard` + 5 秒宽限 + `safetyMuted` 手动复位，双锁序安全），但受 `AUD-006` 静默泄漏与无真机验证限制（`RISK-002`），此项为**中置信度静态评估**。 |
| **7. 性能与资源效率** | **6.5** *(低置信)* | 受限于无真机 Perfetto / Battery Historian 实测，仅能指出静态高耗点（`AUD-008` 100 Hz 枚举+IPC、`AUD-011` 常驻 50 Hz 忙等、`AUD-013` 1 Hz/10 Hz 轮询、`AUD-014` 主线程同步读盘）。实机热节流表现**未验证**。 |
| **8. 安全性与隐私合规** | **8.0** | 一方隐私、备份容器 AES-GCM + AAD、Keystore 单用途封装、路径/尺寸校验与日志零泄漏处于高水准；失分主要是固定外部 `ts3j` 库采用 `java.util.Random`、缺服务端公钥固定（`AUD-012`）与老旧传递库。 |
| **9. 错误处理与可观测性** | **7.5** | 错误分类清晰（尤其是网络/拒绝/麦克风权限区分与闭集隐私安全诊断归档），但不应把未分类的 `RuntimeException` 一刀切算不可重连（`AUD-004`）。 |
| **10. 自动化测试与 CI 可信度** | **8.0** | 939 项离线 JVM 单测 + 34 项 Python 安全/打包测试 + 118 文件仪器测试构成极其扎实的离线防护网；缺口在于对复杂并发跨方法空窗（`AUD-001`/`002`/`009`）以及真实世界默认 TS3 `ip=0.0.0.0` 报文（`AUD-005`）缺乏测试向量。 |
| **11. 文档一致性与发布工程成熟度** | **9.0** | `ADR`、`CANDIDATE_STATUS`、测试探针记录、Strict Lockfile 与诚实列出未验收门禁的态度极专业透明，无夸大包装。 |

---

## 8. 分阶段修复路线图

### Stage A：阻断级修复（P1 止血——发版前必做）
1. **修复 `AUD-001`（`requestExit()` 与 `finishTerminalFailure()` 竞态崩溃）**
   - **改动范围**：局部修改（[ConnectionCoordinator.kt#L342-L397](file:///Users/monet/Documents/TS3-mobile/app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L342-L397)）。
   - **依赖与风险**：低；需保证不引入 `SessionGeneration` 与 `exitLock` 反转，同时移除 `checkNotNull(lease.prepare(...))` 硬崩溃。
   - **验证要求**：新增并发退出竞态单测并跑通全量 `:app:testDebugUnitTest`。
2. **修复 `AUD-002`（`Ts3jSessionClient` `subscribeAll` 窗口期断连僵尸态）**
   - **改动范围**：局部跨类（`Ts3jSessionClient.kt` + `ConnectionCoordinator.kt`）。
   - **依赖与风险**：低–中；需确认原因码 `4/5`（踢出/封禁）等拒绝路径不会重复回调通知两次。
   - **验证要求**：在 `:ts3-protocol:test` 与 `:app:testDebugUnitTest` 新增断连竞态与 `transportConnected` 非空盲点断言。
3. **修复 `AUD-003`（`SessionExitRunner` 保留用户/应用级状态不抹除配置与诊断档案）**
   - **改动范围**：局部重构（抽出 `TeamSpeakServiceState` 会话级重置函数，涉及 `SessionExitRunner.kt` 与 `ConnectionCoordinator.kt` 对齐，以及检查 `MicrophonePreferenceController` 同步一致性）。
   - **依赖与风险**：低；注意对照已有 `SessionExitRunnerTest` 核对瞬态字段（如 `playbackMuted` 与通道错）置空预期。
   - **验证要求**：新增完整“设自定义麦参数 → 连 → 断 → 再连”与断开后诊断“标记问题”可用性验证用例。
4. **解决 `AUD-004`（合入并切替修复后的 `ts3j` 正式标签 + 修正异常重连分类兜底）**
   - **改动范围**：构建配置与三方发版（在自有 `ts3j` fork 打含 `0002-handshake-unsigned-width.patch` 的新标签替代 `ts3mobile-1`，同步更新 `libs.versions.toml` 与 `gradle.lockfile`；同时完善一方 `ConnectionFailure.kt`）。
   - **依赖与风险**：中；换 `ts3j` 坐标需完整重走回环与实服握手互通验证及 Release R8 检查。
   - **验证要求**：固定新版本 `ts3j` 后复跑所有探针及真机实服连接验证。

### Stage B：核心音频、蓝牙与文件可用性修复（P2 重点——强烈建议随 1.3.0 闭合）
5. **修复 `AUD-005`（兼容 TS3 服务器 `ip=` / `0.0.0.0` / `::` 文件传输下发）** — 高优先建议立即随 `1.3.0` 合入，否则标准服务器全量 `channel_files_probe_enabled=true` 后文件、附件、头像均不可用。
6. **修复 `AUD-006`（`PlaybackSession` 蓝牙延迟就绪后及时回收空闲 `AudioTrack`）** — 局部修改，附加强断言验证既不破坏 5 s 路由探测也不再在静音期轮询与泄漏音轨。
7. **修复 `AUD-007`（解除 `VoiceReceiveQueue` 60 ms 过期导致的整客户端 `budget.invalidate` 级联失效）** — 局部修改，确保仅释放超时单包并验证重缓冲指标稳步改善。
8. **修复 `AUD-008`（解耦 `AndroidPlaybackOutput.write()` 10 ms 热路径设备枚举与无条件 `setVolume` IPC）** — 局部架构调整，须经蓝牙异常断连实机测试与 Perfetto 验证安全不降级。
9. **修复 `AUD-009`（`SessionExitRunner` 超时严禁无锁调用 `stopAudio()` + DNS 移出 `sessionMutex`）** — 局部并发拆分，清理退出超时的硬件争夺隐患。
10. **修复 `AUD-010`（本地麦测 `AudioRecord` / RNNoise 启停卸载至 IO 并发串行 + 加锁 `isInputSilenced`）** — 中小幅度修改，参照 `MicrophoneController` 统一下发 `Dispatchers.IO`。

### Stage C：第三方协议栈与安全纵深强化（可拆至紧随其后的维护小版本或与 `ts3j` 发版一并推进）
11. **推进 `AUD-011` & `AUD-012`**：升级自有 `ts3j` fork 用 `SecureRandom` 替换 `java.util.Random`、修正 `HighPrecisionTimer` 漂移算式并去除常驻盲自旋（一方仅在发送启用期绑定或按 tail 帧退出后解绑 `voiceSource`）；规划 BouncyCastle 等陈旧传递库升级及服务器 `serverUid` TOFU 变更提醒机制。
12. **闭环 `RISK-001` 与 `RISK-005`**：加固 `TeamSpeakService.onStartCommand` `ACTION_CONNECT` `requireIdle=true` 早退保底通知促进；实测确认密码频道内文件读取的 `cpw` 需求并决定是否透传当前频道已输入的密码。

### Stage D：工程打磨与真机验收门禁（发版前实测与后续演进）
13. **优化 `AUD-013`、`AUD-014`、`AUD-015`**：拆分 `uptimeMs` 独立读数脱离顶层 `TeamSpeakServiceState`、降低悬浮窗系统检查轮询频度、将 `SessionDiagnosticWriter` 读盘移入 IO、补齐备份规则 XML 遗漏项、修正 Debug `InlinedApi` 并为 `ReconnectPolicy` 补充抖动与上限。
14. **落地 `RISK-002` / `RISK-003` / `RISK-004` 实机门禁**：按 [CANDIDATE_STATUS_2026-11-25.md](file:///Users/monet/Documents/TS3-mobile/docs/release/CANDIDATE_STATUS_2026-11-25.md) 表格逐一补齐 AOSP + 主流国产 ROM 真机的蓝牙、LE Audio、听筒/免提、锁屏 30 分钟+、覆盖升级及 118 项 `androidTest` 验证。

---

## 9. 发布就绪性判断

### 9.1 分场景就绪性评估

| 使用场景 | 就绪度评估 | 核心依据与障碍 |
| --- | :---: | --- |
| **1. 日常 Wi-Fi 语音开黑** | **有条件可用** | 基础编解码与多端状态机稳健，但面临约 1/256 握手越界（`AUD-004`）、点击断开再重连即被洗掉用户自定义麦降噪/门限（`AUD-003`）、极低概率断连瞬间的进程崩溃（`AUD-001`）以及频道文件在标准 `ip=0.0.0.0` 服务器全线报错（`AUD-005`）。 |
| **2. 移动网络切换 / 弱网环境** | **尚未就绪** | 自动重连期遭遇服务端断流可能卡入永久僵尸态（`AUD-002`）或被握手 AIOOBE 误杀终身断重连（`AUD-004`）；无抖动与无上限的重连循环会在长时间弱网耗电（`AUD-015` / `RISK-003`）。 |
| **3. 蓝牙耳机 + 游戏伴随（ADR-0039）** | **尚未完全就绪** | 虽在路由切换与隐私静音逻辑上考虑周全，但存在短发言后永久泄漏播放中 `AudioTrack`（`AUD-006`）、10 ms 热路径持续设备枚举与 IPC（`AUD-008`）、慢切轨易致级联重缓冲（`AUD-007`），且本版本尚未完成物理真机蓝牙/LE Audio 组合验收（`RISK-002`）。 |
| **4. 后台长时挂机与锁屏（60+ 分钟）** | **未知 / 缺真机数据** | 常驻 `TS3J AudioThread` 即使纯听也在 50 Hz 唤醒自旋（`AUD-011`），缺少 `WifiLock` 与系统级 OEM 策略对后台 UDP 保活的影响需真实手机长测验证（`RISK-003` / `RISK-004`）。 |
| **5. 高并发多人频道（5–20 人）** | **有条件 / 缺真机压测** | 离线上限护栏设计合理（`MAX_CLIENT_SAMPLES` / `MAX_GLOBAL_BYTES` / `MAX_TRACKED_CLIENTS`），但 `VoiceReceiveQueue` 60 ms 过期连带清零机制（`AUD-007`）在多人高负载抖动下极易触发连环卡顿，且 1 Hz 全局状态重建通知界面开销尚未真机验证（`AUD-013` / `RISK-004`）。 |
| **6. 公开分发安全与隐私合规** | **总体良好 / 需清理上游债务** | 本地凭证加密（Keystore AES-GCM）、备份容器（AES-GCM + AAD + 600k 迭代）、输入边界、日志全面脱敏均显著达标；待补强上游 `ts3j` `java.util.Random` 弱随机、无服务器 UID 变更告警及 EOL 传递库升级（`AUD-012`）。 |

### 9.2 最终发布结论

- **最终判定：`Conditionally Ready`（当前 Commit `codex/release-1.3.0 @ 26dec7b7fe` 尚不建议直接作为正式版向终端用户公开发行；须完成最小阻塞放行集合与真机门禁后方可发布）。**
- **建议发版硬门槛（放行条件）**：
  1. 修复 **Stage A 全部 4 项 P1 缺陷**（`AUD-001` 主线程退出崩溃、`AUD-002` 握手期断开僵尸态、`AUD-003` 退出抹除麦配置与诊断计数、`AUD-004` 启用已验妥的 `ts3j` 定宽补丁新标签并完善异常重连分类）；
  2. 修复直接破坏 `1.3.0` 核心新特性或存在明显资源泄漏的 P2 项（**重点包含 `AUD-005` 文件/头像 `ip=`/`0.0.0.0` 兼容、`AUD-006` 蓝牙 `AudioTrack` 泄漏、`AUD-007` 60 ms 过期级联复位、以及 `AUD-009` / `AUD-010` 超时无锁与主线程阻塞**）；
  3. 跑通仓库自身的真机与官方服务器发版门禁（实际服务器文件/头像双向互通 + 多机型蓝牙/LE Audio/锁屏 30 分钟实机验收 + 全量 `androidTest` 与正式签名覆盖升级）。
