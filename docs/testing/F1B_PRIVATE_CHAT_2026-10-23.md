# F1b 私聊与会话切换交付

日期：2026-10-23（Asia/Shanghai）。基线：`c83aa45051bdc32cf8a090c082363bca16e356fa`
及尚未提交的 F1a 工作区。用户追加要求给单独用户发消息，本次推进执行计划 P1–P4。
原有未提交文档保留；没有提交、发布或推进 F2/F3/F4。

## 使用与行为

- 在“用户”页打开目标用户的操作菜单，选择“发送私信”，进入对应私聊。
- 聊天页顶部切换频道和私聊；各自保留草稿、未读、历史滚动位置与跟随末尾意图。
- 在线私聊标题显示昵称及当前客户端编号，便于区分同名用户；昵称不作为身份键。
- 收到新私信自动建立会话，聊天页签显示全会话未读总数；查看一个会话不会读掉其他会话的未读。
- 稳定 UID 在新连接尝试中重新可靠映射后才能发送。离线或重复 UID 映射暂停发送，保留可编辑草稿和只读消息。
- 缺 UID 会话限定当前尝试及观察到的客户端占用；离开后重进不重新绑定旧会话，尝试结束关闭该类会话。
- 最多 20 个会话（1 个频道、19 个私聊），共用 500 条 / 2 MiB 消息文本预算；草稿各自最多 16 KiB，发送输入最多 1,024 UTF-8 字节。
- 全局只允许一次待确认发送。切换会话不改变已捕获目标；结果回到原会话。成功表示服务器接受，未知结果不自动重发。
- 关闭空私聊立即释放名额；有内容或草稿时先确认。待确认发送所在会话不能关闭。频道会话始终保留。
- 内容只留在服务内存，手动退出、终态、新手动会话和进程结束清空；没有聊天落盘、文本通知或已读回执。

## 变更分类

| 类别 | 文件与职责 |
| --- | --- |
| 生产：协议 | `TextMessage.kt` 扩展 Private 的可选 expectedUid；`Ts3jTextCommands` 编码 mode 1/client ID，socket/facade 复用捕获、超时、FIFO 和队列认领边界，并核对当前身份 |
| 生产：状态 | 新增 `ChatConversation.kt`、`PrivateChatDirectory.kt`、`ChatRetentionPolicy.kt`；Store 负责同步发布和会话操作，controller 捕获会话与目标 |
| 生产：接线 | Coordinator 传入参与者快照和可空频道；Service 新增通用发送/私聊会话 binder；旧频道专用发送入口保持频道专用；Activity 转发回调 |
| 生产：界面 | 新增 `ParticipantChatAction.kt`、`ChatConversationPicker.kt`、`ChatConversationContent.kt`；扩展用户列表、聊天页、输入状态、短屏会话头和双语文案 |
| 测试 | 新增 PrivateChatStore、PrivateChatDirectory、ChatRetentionPolicy 和 PrivateChatPanel 测试；扩展 pinned 编码、facade、controller、coordinator 和离线 UiFixture |
| 文档 | ADR-0014、本记录、双语 README、CHANGELOG、ARCHITECTURE、PRIVACY、协议 README、计划状态，以及 F1a 真机结果入口修正 |
| 配置 | 仓库没有新增依赖、权限、锁文件、存储格式或签名变更；忽略目录旁装副本仅改 applicationId 和应用显示名称 |

拆分评估见 [ADR-0014](../decisions/0014-session-owned-private-conversations.md)：
目录解析和跨会话淘汰独立，ChatStore 保持在约 300 行以内；已有大型服务与协调器只增加窄转发。
协议的 Private 构造函数新增默认参数，既有 Kotlin 调用保持源码兼容，各模块一起重建，不承诺预编译 ABI。

## 回归与检查

本地运行先 `source build/f1a/env.sh`，格式化与门禁分开执行。
通用回归全部使用 fixture/fake，不依赖真实服务或开发者本机端点。

```sh
./gradlew :app:ktlintFormat :ts3-protocol:ktlintFormat --max-workers=2
./gradlew quality :ts3-protocol:test :audio-opus:testDebugUnitTest \
  :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug \
  :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:assembleDebugAndroidTest --max-workers=2 --stacktrace
adb -s <emulator> shell am instrument -w -r \
  io.github.ts3mobile.app.test/androidx.test.runner.AndroidJUnitRunner
pwsh -NoProfile -File tools/check-release-identity.ps1 \
  -DeviceSerial <emulator> -SdkRoot "$ANDROID_HOME"
pwsh -NoProfile -File tools/check-repository-privacy.ps1
git diff --check
```

| 检查 | 最终结果 |
| --- | --- |
| 格式、编译、完整适用 JVM 门禁 | 316 项通过：协议 108、音频 20、App 188；较 F1a 新增 23 项 |
| Android Lint / Debug 与混淆 Release 构建 | 通过；0 errors，Debug 36 / Release 25 项既有 warnings，与 F1a 一致 |
| API 35 ARM64 完整离线 UI，1080×2400 / 420dpi | 47 项全部通过，0 跳过，包含 8 项私聊 UI 回归 |
| API 35 ARM64 聊天窄屏，320×640 / 160dpi | 19 项全部通过，0 跳过；包括中英文 1.8 倍字体、IME、历史恢复和私聊 |
| 当前混淆 Release 身份与 INI 探针 | `IDENTITY_ROUNDTRIP_OK`、`INI_STORE_ROUNDTRIP_OK`、`RELEASE_IDENTITY_SMOKE_OK` |
| 仓库隐私扫描、文档本地链接与空白检查 | 通过 |

关键回归包括：同名用户不合并、客户端 ID 被复用、新尝试不得沿用旧 own ID、
离线到新 ID 的稳定 UID 恢复、缺 UID 占用失效、重复 UID 只读、入站目标不符、自回显、
切换会话不改投、结果回原会话、全会话容量和淘汰、关闭确认，以及中英文大字体键盘布局。

新增历史滚动回归实际复现了切换/恢复后跳底：恢复时把已有末条当成新追加，
以及逻辑滚动位置先于测量布局更新，错误标记末尾跟随。修复后按每个会话保存已处理序号和跟随意图，
用同一测量布局的可见项位置及末端几何判断，保留正在阅读的历史与新消息未读。
保存内容仅为数字会话 ID、滚动元数据、序号和布尔跟随状态；没有 UID、昵称、正文或草稿。
关闭会话时清理滚动 bucket，bucket ID 清单也可在界面状态恢复后清理。

320dp 窄屏测试还发现会话选择栏挤压消息区域；短于 700dp 的聊天页使用紧凑会话头，
保留服务器、断开入口和底部语音控制，音频路由/播放静音仍可在频道或用户页操作。
键盘弹出时继续沿用原来的紧凑输入布局；不修改音频路由或发送逻辑。

## 产物与真机边界

主工作区产物在 `app/build/outputs/apk/debug/app-debug.apk` 和
`app/build/outputs/apk/release/app-release-unsigned.apk`。
为此前手机上的独立调试包生成旁装更新：`build/f1b/TS3-Mobile-F1b-debug.apk`，
applicationId 沿用 `io.github.ts3mobile.app.f1adevicetest`，显示名为“TS3 Mobile F1b 调试”。
生产源文件与主工作区逐文件核对，仅应用名称资源不同；包名只在副本构建配置中修改。
该包使用本机开发签名，独立于原安装的 `io.github.ts3mobile.app`，不是正式签名发布。

本轮只检测到模拟器，尚未向手机安装 F1b；手机原应用及此前 F1a 调试包未改动。
前次两条真实频道消息验证见 [F1a 真机记录](F1A_DEVICE_2026-10-23.md)。
本轮没有额外发送真实频道消息或私信；真实私聊与 Release 文字互通仍未实测。
缺 UID 时无法证明未被观察到的同 ID、同快照占用变化；仅保证本尝试内已观察的占用，不能按昵称补全身份。
这些结果不替代语音听感、蓝牙、长时后台、其他 Android 版本和正式发布验收。

忽略目录 `build/f1b/` 保存构建/测试日志、截图和 `artifacts.json`，APK 哈希以该文件为准。
实际检查了 `ui-previews/chat-en-dark.png` 和 `private-zh-large-keyboard.png`：
短屏消息可见，私聊标题/目标编号、关闭、输入、发送及停麦控件可见。
完整门禁日志为 `final-gates.log`，UI 日志为 `final-ui.log`、`narrow-ui.log`，
混淆 APK 探针为 `release-identity.log`；旁装包的源码一致性、包名/显示名及此前调试包签名核对通过。
验证后停止本轮启动的模拟器，保留工具链、AVD 和产物。
