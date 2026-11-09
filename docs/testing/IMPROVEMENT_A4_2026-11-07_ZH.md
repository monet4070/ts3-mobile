# A4：后台私信通知与安全定位

日期：2026-11-07。范围：离线、合成消息、API 35 Android 15 arm64 模拟器；不连接服务器、不录音。实现参见 [ADR-0025](../decisions/0025-private-message-notifications.md)。全部生产入口已实现，本记录区分完整套件、最终差异补验和外部系统策略 fixture。

## 实现与边界

- 普通入站私信被 ChatStore 接受时递增全局及会话修订号；摘要受原 20 会话上限约束，不保留额外正文。StateFlow 合并和 500 条历史淘汰不丢失仍存在会话的提醒意图。
- 首次订阅建立高水位；前台页签只保留未读。首条开始固定 2 秒后汇总，后续更新 `onlyAlertOnce`，回前台同步取消窗口并清通知，未读独立保留。
- 独立通知频道 `ts3_private_messages`、ID 4105；正文通用、公开锁屏可见。显式 immutable PendingIntent 的 data URI 只带随机令牌，不同令牌具有不同 identity。
- 服务内映射最多 16 项、单调时间 TTL 5 分钟、一次消费；校验 sessionToken、断线 generation 和全部 conversation ID。断开、关闭目标、切服、销毁和过期令牌不能定位新会话；不会自动连接。
- onCreate / onNewIntent 共用 ViewModel 中的单请求入口，绑定前暂存，用户导航或新 Intent 覆盖旧请求。单目标等待选中后才挂载消息；多目标显示可恢复的会话列表，不直接挂载任何消息。点击本身不改 readThrough。
- 新增系统频道设置入口。权限拒绝、系统总开关、频道、勿扰由 Android 决定，无新权限或依赖。

## 回归与实际问题

纯 JVM 覆盖固定窗口、连续流、首订阅旧未读、收发／频道／POKE 筛选、合并更新／淘汰、前后台竞争、断开重连、关闭、TTL／容量／一次消费、服务 scope 和 ID 重用、同 attempt 客户端占用者变更，以及绑定前请求和用户导航取消。

Android 覆盖真实 Activity / Binder 冷入口与 PendingIntent 热入口、旧会话拒绝、单目标已读范围、多会话列表／恢复、通用文案／不可变 Intent／身份区分、权限拒绝和勿扰策略。通知总开关撤权会终止应用进程，因此 `PrivateMessageDisabledPolicyTest` 默认跳过，必须在进程外先准备真实系统策略，再用参数单独执行；不能在正在运行的仪器中撤权。

初次 ActivityScenario 热入口测试因 Activity 更新 Intent 后框架无法继续匹配 launch Intent 而超时，测试在最终清理前恢复框架匹配并结束真实 Activity，不改生产入口。实际 PendingIntent 还包含 native pause/resume，新增有 5 秒上限的可见性等待；仍断言复用同一 resumed Activity、列表可见且不误读。

A3 第二次云端验收暴露收藏保存测试的 IME 触摸竞争。保存／重试行为改用真实语义 OnClick，保留独立大字体键盘布局触摸测试。修复已回传原 A3 PR；随后同一 CI 还暴露频道发送的同类 IME 触摸问题，也采用语义发送并保留原键盘布局／复制断言。没有用无限重跑掩盖失败。

## 结果与证据

| 检查 | 实际结果 |
| --- | --- |
| 格式／quality | 通过；格式化后顺序执行检查 |
| 应用 JVM | 282 项通过，含本阶段新增 19 项；明确 `--rerun` |
| 完整 Android | JUnit XML 共 168 项，167 通过、0 失败、1 个外部策略用例按预期跳过 |
| 最终通知／导航补验 | 13 项通过，覆盖列表边距的最后生产修改及实际 PendingIntent 热入口 |
| 频道发送测试修复 | 独立 11 项通过；仅测试语义点击变化，不再重复整套 |
| 真实系统策略 | 外部撤销通知权限／关闭应用通知、系统设置关闭单频道两种 fixture 各执行一次专用用例，均通过；恢复后投递通过 |
| Lint Debug／Release | 0 error，原有 3 warning、1 information；没有新增抑制 |
| Debug／minified Release | 两种 APK 构建通过，未签生产证书／发布 |
| 隐私／工具／文档 | 隐私脚本通过；Python 工具 9 项通过；YAML、相对链接和 whitespace 检查通过 |

完整门禁：

```sh
source build/improvement-2026-11-07/env.sh
./gradlew --offline --max-workers=2 :app:ktlintFormat
./gradlew --offline --max-workers=2 quality :app:testDebugUnitTest --rerun :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
```

完整通过后仅两文件发生变化：`ChatPanelTest` 的频道发送改用语义点击；
`ChatNotificationNavigation` 的列表增加 12 dp 边距。前者再跑完整 ChatPanelTest 11 项与 quality，
后者再跑通知构建／Activity／Compose 共 13 项、quality、双变体 Lint 和 APK 构建；全部通过。
没有把先前完整套件声称为这两处最后差异的重跑结果。

原始日志与 XML 位于忽略目录 `build/improvement-2026-11-07/`：

- `a4-full-gate-final.log`、`a4-full-results/`：完整套件及 282 JVM 的原 XML；255 actionable tasks 中 58 executed、197 up-to-date。
- `a4-source-sha256.json` 与 `a4-final-source-sha256.json`：463 个生产／测试／构建输入的完整和最终差异清单，差异仅上面两文件。
- `a4-presentation-final.log`、`a4-chat-ci-fix.log`：最后差异补验。
- `a4-system-policy/`：`global-disabled.log`、`channel-disabled.log`、`restored-delivery.log` 和恢复结果；实际设置页 XML 证明频道关闭与恢复。
- `a4-summary-preview-test.log`、`a4-summary.png`：最终列表恢复／不误读测试及已目视查看的合成会话截图。

系统 fixture 使用任务模拟器 `emulator-5564`（API 35、arm64-v8a、4 KiB 页），先安装当次 Debug 和 test APK。
应用通知关闭通过进程外 `pm revoke ... android.permission.POST_NOTIFICATIONS` 准备；
频道关闭通过实际 `android.settings.CHANNEL_NOTIFICATION_SETTINGS` 页的开关准备。
随后分别执行：

```sh
adb -s "$device_serial" shell am instrument -w -r \
  -e class io.github.ts3mobile.app.service.PrivateMessageDisabledPolicyTest \
  -e disabledNotificationPolicy app \
  io.github.ts3mobile.app.test/androidx.test.runner.AndroidJUnitRunner
# 单频道关闭时参数改为 channel；完成后恢复权限和频道，并执行 granted-delivery 测试。
```

专用用例检查实际 NotificationManager 状态与未读保持，默认套件不会自行改系统权限。
AppOps 方式在本镜像不能替代权限总开关，已弃用该验收方法；撤权导致的测试进程退出也没有被算作通过。
APK、开发签名、设备文件和截图不进入仓库。

## 变更分类与剩余边界

- 生产：消息修订号／断线代次，独立策略／registry／Intent／入口及会话列表；Activity、服务与 MainScreen 只接线，中英资源。
- 测试：独立 JVM、Android、需外部准备的系统策略 fixture；已有收藏测试的 IME 稳定性修复。
- 文档：ADR、本文、进度、双语 README／指南／隐私、架构。
- 配置：CI 选入通知、入口、列表与原戳一下回归；没有版本、依赖、存储格式或权限变更。

本轮不包含 OEM 后台策略、物理锁屏声音、真实服务器投递或 API 26 的运行证据。没有改变勿扰优先级，也不承诺绕过系统限制。公开签名和升级组合验收按后续 R2 记录，不把第一批 R1 证据替代第二批验收。

## 云端收尾

草稿 [PR #12](https://github.com/monet4070/ts3-mobile/pull/12) 中生产源码为 `0ca3f1c`。
云端第一次还暴露过期通知冷入口测试在 320×640 画面直接断言下方连接按钮可见；修复 `fac612b`
先滚动到按钮再保留可见性断言，没有改变生产入口。最终
[CI 42364912604](https://github.com/monet4070/ts3-mobile/actions/runs/42364912604)通过，
下载 XML 核对 app282／protocol129／audio194 JVM，app96／audio11 Android，全部零失败。
证据保留在 `a4-ci-final-reports/`。
