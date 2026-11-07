# A3a / A3b：活动连接收藏、频道选择与可见加入

日期：2026-11-07（Asia/Shanghai）。基于A2提交`be2b74c`，工作分支`codex/channel-bookmarks`。本轮仅离线／模拟器验收。

## 改动与边界

“保存当前连接”从协调器拥有的无密码连接上下文取端点和昵称，预选当前频道；普通新增仍预填表单。匹配当前端点的编辑器提供名称／父路径选择和使用当前频道。持久化仍是原来的defaultChannelId、v1密文布局和独立key alias，没有迁移。密码不从活动连接或表单复制，改选频道清除旧频道密码。

会话token、trim／大小写／端口匹配、频道存在性在界面和真实DataStore编辑事务中重新验证；旧revision仍由原codec拒绝。过期草稿保留，可重新选择或明确清除绑定后手动／离线保存。当前频道未知时也能解除过期绑定，不会把用户困在禁用保存状态。

保存结果必须与本次提交的内容和下一revision一致，且操作未失败；不再仅凭列表出现更高revision就关闭编辑器。这防止其他写入者的结果隐藏本次未保存草稿。

频道行增加可见Join和无障碍动作，双击仍可用。三者共用原密码弹窗／join入口；密码目标按连接token隔离，连接替换或频道删除使旧目标失效。Binder将界面捕获的连接token传到协调器，再验证频道属于该连接，防止新服务器复用ID时误加入。详见[ADR-0024](../decisions/0024-current-connection-channel-bookmarks.md)。

## 新执行验证

| 检查 | 结果 |
| --- | --- |
| 全应用JVM | 263通过，0失败／错误／跳过；新增10项上下文／路径／排队保存／协调器回归 |
| 全应用Android | 154通过，0失败／错误／跳过；比A2新增13项 |
| 新UI回归 | 活动端点与表单不一致、同名路径、别服收藏、会话替换、频道删除、未知频道后断线、revision冲突、中文大字深色选择器、可见／密码／失效／窄屏Join |
| Android存储回归 | 真实DataStore事务排队期间使上下文失效，拒绝保存并保持原密文；随后有效保存成功，旧revision仍拒绝 |
| 格式与编译 | `quality`、应用JVM与Android Kotlin编译通过 |
| Lint | Debug/Release通过；维持原有3个资源Warning |
| APK | Debug、minified Release、测试APK构建通过；unsigned Release非正式发布 |
| 最终minified APK探针 | 本次身份／INI与Opus1.6.1 ART探针通过 |
| 窄屏视觉 | 同一Debug/test APK在320dp宽、中文1.8倍字号下的深色频道选择器与浅色频道加入均通过额外两项仪器检查，PNG已查看 |
| 隐私／workflow | 原PowerShell扫描、YAML解析、`bash -n`、空白检查通过；CI追加新UI与真实Vault类 |

最终完整门禁255个任务中50个执行、205个复用；应用263 JVM与154项仪器任务均为本次实际执行，不把复用编译算成新测试。最终源码／测试与436份输入清单核对一致。协议与音频生产代码未变，沿用P0记录，不冒称本阶段重新执行其全部套件。

最初新增的join测试没有先滚动LazyColumn以实例化未显示行，且既有测试使用全页面唯一Join文案假设；修正测试滚动与弹窗选择后运行。revision回归改为明确替换字段内容，避免把输入光标位置当作业务前提。没有放宽断言或删除原双击路径测试。

## 执行方式与证据

```sh
./gradlew --offline --max-workers=2 :app:ktlintFormat
./gradlew --offline --max-workers=2 quality :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
pwsh -NoProfile -File tools/check-repository-privacy.ps1
```

设备为独立API35 / Android15 / arm64-v8a / 4KiB模拟器，正常1080×2340、density420。中文大字视觉检查另在840×2340、density420（320dp宽）的同一模拟器执行，结束后恢复尺寸。所有频道、昵称、端点与密码均为合成fixture，没有真实服务器或消息，也没有实际录音。

本机忽略目录`build/improvement-2026-11-07/`保存`a3-frozen-gates.log`、`a3-junit-final/`、`a3-android-final/`、436份输入的`a3-source-before.sha256`、更早失败与定向结果，以及额外视觉检查与Release探针日志。前后指纹一致后再提交，阶段PR提供可固定的源提交。截图不进入公开测试产物。

真实服务器拒绝／密码语义复用现有join错误映射，尚未进行线上互通。R1的组合与同签名覆盖升级仍单列；单项UI通过不等于第一批已全部验收。

## 远端CI后续：小窗口的惰性列表定位

[首轮PR #10 CI](https://github.com/monet4070/ts3-mobile/actions/runs/42359736168)在81项选定Android测试中发现1项失败：中文大字深色选择器的`bookmark_channel_3`尚未被LazyColumn实例化，测试直接`performScrollTo`无法找到它。原有产物上传成功，失败XML与日志已保存；不是模拟器启动失败，也不把它当成通过。

仅修正测试：先限定到Dialog内的LazyColumn，再通过`performScrollToNode`实例化目标，然后保留可见性、关闭按钮与选择行为断言。没有修改生产逻辑、移除测试或放宽UI断言。将本机窗口设为与CI一致的320×640、density160后，CurrentConnectionBookmarkTest全部8项通过，`quality`通过；日志为`a3-ci-size-fixed.log`。更高窗口上的完整154项及生产代码结果保持原证据范围。修正提交另由远端CI验证。

## 变更分类

- 生产：密码隔离的连接上下文、频道路径／校验策略、事务内保存guard、编辑器／选择器／当前连接入口、可见与无障碍Join、过期目标错误。
- 测试：新增10项JVM及13项Android；更新旧Join弹窗选择与fixture接线，保留数字编辑和双击测试。
- 配置：CI选类追加CurrentConnectionBookmarkTest、ChannelJoinActionTest、BookmarkVaultTest；无依赖／版本／旧数据格式变化。
- 文档：ADR、双语README／指南、架构、进度与本记录；协调器和Service只增加窄接线，不在此阶段重构连接状态机。
