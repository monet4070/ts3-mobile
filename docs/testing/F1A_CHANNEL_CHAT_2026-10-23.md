# F0 / F1a 当前频道聊天开发交付

日期：2026-10-23（Asia/Shanghai）。源基线：`c83aa45051bdc32cf8a090c082363bca16e356fa`。
本记录描述工作区开发代码，尚未提交或发布；原有未提交路线图、README 和发布续接文档保留。
本记录的原交付范围为执行计划 F0、F1-01 至 F1-06；后续用户要求增加私聊，
进度另见 [F1b 记录](F1B_PRIVATE_CHAT_2026-10-23.md)。F2/F3/F4 尚未实现。
频道聊天随后通过了 Android 16 独立 Debug 包真实互通，详见[真机记录](F1A_DEVICE_2026-10-23.md)。

## 交付行为

- 当前频道纯文本收发、固定发送结果、长按复制、序号未读、新消息入口和滚动位置。
- 正文保留首尾空白，按 UTF-8 字节验证；默认单条输入 1,024 字节，入站/草稿保留上限 16 KiB。
- 同时限制 500 条 / 2 MiB 消息正文及发送者文本；优先淘汰已完成记录，溢出只显示固定提示。
- 一次发送任务，操作目标预算五秒；服务器成功仅表示接受。未知结果不自动重发，也不被迟到完成改写。
- 普通重连保留本次手动会话内存；旧 listener/attempt、旧目标和过期结果不能写入替换会话。
- 手动连接、终态、退出和服务销毁清理内容；无聊天落盘、SavedState 正文或后台文本通知。
- 中英文、深浅色主题、紧凑键盘布局；切换页签或聚焦输入释放 PTT，常开模式由显式停麦控制。

## 变更分类和边界

| 类别 | 文件 / 职责 |
| --- | --- |
| 生产：协议 | 新增 `TextMessage.kt`、`Ts3jTextMessageMapper.kt`、`Ts3jTextCommands.kt`；会话/窄 socket/事件适配器仅增加文字端口、捕获与转发 |
| 生产：应用 | 新增 `chat/ChatMessage.kt`、`ChatState.kt`、`ChatStore.kt`、`SessionChatController.kt`；Coordinator、Service、Activity 只做连接绑定、生命周期和 binder/StateFlow 接线 |
| 生产：界面 | 新增 `ChatPanel.kt`、`ChatComposer.kt`、`ChatMessageRow.kt`、`CompactChatVoiceBar.kt`；扩展显式页签和重连呈现；双语资源 |
| 测试 | 新增协议 mapper/真实 pinned 命令队列测试、Store/controller 单测、ChatPanel 仪器测试；扩展既有 facade/coordinator/离线 fixture |
| 测试：既有适配 | `MainScreenTest` 查找屏外频道前先通过 LazyColumn 的 `performScrollToNode` 滚动；保留原加入/密码/取消断言。fixture 销毁时关闭测试键盘，聊天测试窗口与实际 Activity 的 edge-to-edge/resize 对齐 |
| 文档 | 双语 README、CHANGELOG Unreleased、ARCHITECTURE、PRIVACY、协议 README、ADR-0013、本记录及执行计划进度 |
| 配置 | MainActivity 显式 `adjustResize`；本地 JDK/SDK/PowerShell 和日志均在忽略的 `build/f1a/`。没有依赖、锁文件、存储 schema、签名或音频配置变更 |

已有大文件拆分评估见 ADR-0013；新职责均落在独立文件。没有为文字功能重写连接状态机。
协议 API 为源码兼容的增量扩展：监听器默认空实现，不支持发送的适配器明确返回 Unsupported。

## F0 环境与基线

- JDK：Corretto 17.0.21 arm64；Gradle Wrapper 8.9；PowerShell 7.4.13。
- SDK 35、Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1；API 35 default ARM64 模拟器。
- 宿主使用匹配的 ARM64 镜像；不连接真实 TeamSpeak 服务，不需要手机/凭据。验证后已停止本轮创建的无窗口模拟器，保留 AVD 与工具链供后续使用。
- 原始 HEAD 通过 `git archive` 展开到忽略目录独立验证，避免生产改动污染基线。
- 基线 JVM 261 个：协议 92、音频 20、App 149；完整格式/Lint/Debug/Release 构建通过。
- 首次启动检查遇到正在安装的 NDK 缺 `source.properties`；安装完成后重新运行原始 HEAD，门禁通过。
- 默认 320×640 模拟器下原始 UI 28 个中有 2 个失败；1080×2400 / 420 dpi 下原始 MainScreen 16 个中仍有 1 个失败：屏外 `Private room` 尚未被 LazyColumn 创建。已修正测试滚动方式，不删断言或跳过用例。

## 验证命令与结果

本地环境启动：`source build/f1a/env.sh`。该脚本和下载产物均忽略，不作为仓库构建依赖。
格式化单独运行，随后运行质量门禁，避免同一 Gradle 调度内格式化和检查并发。

```sh
./gradlew :app:ktlintFormat :ts3-protocol:ktlintFormat --max-workers=2
./gradlew quality :ts3-protocol:test :audio-opus:testDebugUnitTest \
  :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug \
  :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest --max-workers=2 --stacktrace
pwsh -NoProfile -File tools/check-repository-privacy.ps1
git diff --check
```

最终门禁退出码 0（`build/f1a/final-gates.log`）：

| 检查 | 实际结果 |
| --- | --- |
| Kotlin 格式、静态/类型编译、适用完整 Gradle 门禁 | 通过 |
| JVM 测试 | 293 个全部通过：协议 106、音频 20、App 167；较基线新增 32 个 |
| API 35 ARM64 完整离线 UI | 39 个全部通过，0 跳过；1080×2400 / 420 dpi（约 411dp 宽） |
| API 35 ARM64 窄屏聊天套件 | 11 个全部通过；320×640 / 160 dpi；包括英文/中文 1.8 倍字体与实际 IME 布局 |
| Android Lint | 0 errors；Debug 36、Release 25 个既有 warnings，与基线一致 |
| 仓库隐私扫描 / `git diff --check` | 通过 |
| 当前混淆 Release APK 身份与 INI 探针 | `IDENTITY_ROUNDTRIP_OK`、`INI_STORE_ROUNDTRIP_OK`、`RELEASE_IDENTITY_SMOKE_OK` |

窄屏套件通过以下独立仪器入口执行（无服务端连接）：

```sh
./gradlew :app:installDebug :app:installDebugAndroidTest --max-workers=2
adb -s emulator-5554 shell wm size 320x640
adb -s emulator-5554 shell wm density 160
adb -s emulator-5554 shell am instrument -w -r \
  -e class io.github.ts3mobile.app.ui.ChatPanelTest \
  io.github.ts3mobile.app.test/androidx.test.runner.AndroidJUnitRunner
pwsh -NoProfile -File tools/check-release-identity.ps1 \
  -DeviceSerial emulator-5554 -SdkRoot "$ANDROID_HOME"
```

已检查导出的中文/英文大字体键盘、发送成功和深色聊天截图，位于
`build/f1a/screenshots-narrow/`。仪器测试发现并修复了键盘挤压、末条消息追加时
自动滚动意图丢失的问题；历史位置仍保留未读，不因新消息强制跳底。
日志与截图不提交。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，
Release 构建为 `app/build/outputs/apk/release/app-release-unsigned.apk`，没有签名发布。
本地 APK 的 SHA-256：

- Debug：`c2c2602d6b2dc48c9c23cb4f3beaaa3b28cecc564b4cffe545325cd0fa2316c3`
- Release unsigned：`d05193cec37dc1ba5266d3aa873866ca59bff658f6c8bca1e351b9e5a0ad83c2`


## 验收对应

| 计划用例 | 验证方式 |
| --- | --- |
| T01–T04 | 真实 pinned 参数编码、FIFO/假响应、错误分类、超时保留队列、过期前原子认领与 facade 测试 |
| T05–T08 | Store/controller 虚拟调度，Coordinator 收发/同 epoch 重连/断开测试，真实 socket 操作跟踪与有界退出测试 |
| T09–T12 | 自回显不确认/不重复，其他用户重复正文保留；双预算、时钟回拨、旧尝试拒绝、可见序号未读与 UI 历史滚动 |
| T13–T16 | 离线 ChatPanel 测试：空输入/超限/发送中/切换/恢复，复制、跳底、状态重建、双语大字体键盘、PTT 与常开停麦 |
| T17 | 模型字符串脱敏测试；新增源码无持久化/日志/正文 saveable 路径；仓库隐私扫描和 PRIVACY 同步 |
| T18 | 既有协议/音频/App JVM 回归和完整离线 UI；当前混淆 APK 身份/INI 探针 |

## 实际限制

Android 16 独立 Debug 包已与电脑客户端完成两条真实频道消息往返；
权限拒绝、更多服务器的回显字段差异和断网重连仍主要依靠离线测试。
Release 文字互通尚未实测；原安装未升级。上述原交付 APK 哈希只对应 F1a 时点，后续构建另行记录。
协议排队后的超时/取消不会撤销服务器可能处理的命令；五秒是本地等待目标，不是网络发送截止承诺。
API 35 模拟器结果不覆盖 Android 26/其他厂商的键盘差异、真实麦克风/蓝牙听感、耗电和长时稳定性。
这些项目留给后续互通/设备验收，不写成已经通过。当前依然是开发构建，不替代 M11 发布验收。
