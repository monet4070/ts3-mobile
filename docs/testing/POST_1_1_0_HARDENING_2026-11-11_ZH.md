# 1.1.0 后续加固

日期：2026-11-11。状态：代码与文档已修改；本地门禁与模拟器仪器回归通过，未发布新版本。

## 范围与起因

1.1.0 发布后复核发现五处问题，均存在于发布的 versionCode 15 中，但没有一处属于发布阻断缺陷。
本轮只做最小修改，不改变已发布的行为契约：音频统计字段、更新检查的用户可见分类、存储格式与版本号均不变。

## 变更

1. **跨模块事件映射**：`AudioDiagnosticEvent.valueOf(kind.name)` 改为 `audioDiagnosticEvent(kind)` 的穷尽 `when`。
   音频模块新增 kind 时变为应用模块编译失败，而不是在音频 worker、持有 `outputLock` 的路由回调或
   系统焦点回调上抛 `IllegalArgumentException`。持久化的名称文本不变。
2. **导出的保留上限单一来源**：`AudioDiagnosticFormat.json` 不再硬编码 5000/3/120，改为引用
   `AudioDiagnosticController.SAMPLE_INTERVAL_MS` 与 `AudioDiagnosticArchive` 常量，并新增
   `maxEventsPerSession` 与 `maxMarksPerSession`。事件槽语义：**64 个事件槽 = 16 个标记槽 + 48 个普通事件槽**，
   `eventLimit(USER_MARK)` 是这条保留规则的结果。属向后兼容的字段新增，schema 仍为 `ts3-mobile-audio/v1`。
3. **档案读取移出主线程**：此前 `AudioDiagnosticRepository` 构造函数同步读取至多 1 MiB 的历史文件，
   而该对象由 `TeamSpeakService.onCreate` 在主线程首次触发。现在 `Ts3MobileApplication` 以 `Deferred`
   在既有 IO scope 上加载（首次访问时启动，读取线程由 `audioDiagnosticDispatcher` 暴露并测试）；
   `AudioDiagnosticsAttachment` 在加载完成后切回主线程安装，
   使“检查是否已销毁”与“安装协作者”在同一线程的同一段不可分割执行中完成，与 `onDestroy` 严格串行。
4. **更新检查预算与失败分类**：一次检查共享 20 秒预算，单次请求上限 10 秒；预算耗尽时不再发起新请求，
   也不跟随重定向。BUILDINFO 抓取的 `404/410` 记为 `INVALID_RELEASE`，`5xx`、`408` 与其余 4xx 记为 `SERVER`。

## 行为边界

- 20 秒是**预算**而非严格墙钟上限：阻塞中的 socket 读不会被主动中止，实际耗时可能超出一个等待量级。
- 取消 `serviceScope` 只取消等待与接线；应用级加载和仓库持久化循环继续运行，历史不会因服务销毁丢失。
- 安装完成前开始的播放代次：控制器接入后会立即采样并收录**当前代次**；无法补回的只有安装前音频侧发出的
  固定事件，以及已被后续代次替换的快照。
- 导出与“标记刚才断音”现在会等待初始化完成；加载失败时导出失败并沿用既有失败提示，不再静默缺少 `audio` 字段。

## 验证

本机默认 JDK 为 Corretto 11，但仓库 `build/f1a/toolchain/` 内有 Corretto 17 与 Android SDK；以下命令均使用该工具链离线执行。

```bash
JAVA_HOME=build/f1a/toolchain/amazon-corretto-17.jdk/Contents/Home \
ANDROID_HOME=build/f1a/toolchain/sdk \
./gradlew --offline quality :ts3-protocol:test :audio-opus:testDebugUnitTest \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

实际结果：格式、协议/音频/应用 JVM 测试（应用 430 项，0 失败）、Debug Lint、Debug APK、测试 APK 与 Release Lint 全部通过。
模拟器 `ts3-f1a-api35`（API 35 ARM64）上运行 23 项仪器测试，0 失败、0 跳过。

新增回归：
- JVM `AudioDiagnosticControllerTest.everyAudioDiagnosticKindIsPersistedWithItsName`
- JVM `AudioDiagnosticArchiveTest.exportedRetentionLimitsMatchTheConstantsActuallyEnforced`
- JVM `AudioDiagnosticsAttachmentTest`（加载门闩、加载期间销毁、重建、初始化期间导出、加载失败、并发等待）
- JVM `GitHubHttpClientTest`：单次请求上限、预算耗尽不拨号、重定向分支内耗尽预算、
  **多次读取累计耗尽**（慢速输入流逐块推进时钟）、多请求共享预算
- Android `GitHubReleaseJsonTest`：慢请求消耗共享预算后，第二个请求的 deadline 被预算夹住而非重新起算；
  预算耗尽后不再发起请求；BUILDINFO 状态分类
- Android `OfflineAudioDiagnosticsTest`：同一仓库实例、导出 schema，并在应用 IO 调度器上断言读取线程不是主线程

未覆盖：`assembleRelease` 签名包、真实弱网下的更新检查耗时、以及 `GitHubUpdateSource` 的
仪器用例只在模拟器上验证过，未做实体设备复测。CI 的完整模拟器矩阵作为合并门禁。
