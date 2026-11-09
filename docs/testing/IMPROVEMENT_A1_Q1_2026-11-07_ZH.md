# A1 / Q1：聊天导航与关键UI门禁

日期：2026-11-07（Asia/Shanghai）；基于`7a1e36e`的独立工作分支。维护者已明确本轮仅做离线和模拟器验收，物理设备验证另行安排。

## 修复与回归

旧实现上新增4项入口级回归全部失败：频道历史主页面往返、隐藏期间追加、私聊主页面往返／恢复、手动会话替换。失败分别为返回后原消息不可见，以及新会话仍处于旧聊天页。

修复把会话和聊天保存容器提升到跨主页面存续的位置，以每次手动连接的新随机token隔离；不改聊天内存保留、已读与协议规则。滚动保存消息序号和偏移，离页期间淘汰消息后按仍保留的锚点恢复；关闭会话清理保存桶。详见[ADR-0022](../decisions/0022-session-scoped-chat-navigation.md)。

新增8项Compose回归和2项JVM token生命周期回归，覆盖频道／私聊、两个主页面、偏移、隐藏追加、Activity保存恢复、普通重连、新手动连接、同scope新服务、仍保留锚点和锚点被淘汰。消息正文、草稿、UID不写入保存状态。

Q1把这些路径和现有频道／私聊、收藏列表／编辑器接入CI，保留原有麦克风、音频生命周期、Release身份与codec探针。报告在成功／失败时均收集，保存7日，名称包含源码SHA与run attempt；上传范围仅测试／Lint XML及新建模拟器启动日志，不上传APK、身份或设备私密转储。

## 验证结果

| 检查 | 本次结果 |
| --- | --- |
| 原逻辑回归 | 4/4失败，已保存XML |
| 修复后初始相关Android套件 | 24通过 |
| 扩展后的独立导航套件 | 8通过 |
| 应用JVM | 241通过，含新增2项；0失败／错误／跳过 |
| 格式、应用Debug/Release Lint | 通过，维持原有3个资源Warning |
| minified Release构建 | 通过；unsigned候选，不是正式发布 |
| 最终全应用Android套件 | 137通过，0失败／错误／跳过 |
| 最终CI相同选类套件 | 57项连续3次通过，分别33／32／32秒的Gradle总耗时 |
| Workflow结构与shell语法 | Ruby YAML解析与`bash -n`通过；新增action固定完整SHA并核对其输入 |
| 原PowerShell隐私扫描／空白检查 | 通过 |

API35 / Android15 / arm64-v8a / 4KiB专用模拟器，1080×2340、density420；没有真实服务器、录音或实体设备。时长是本机测量，不能作为Linux CI耗时保证。协议129／音频194 JVM和32音频Android的结果属于[P0](IMPROVEMENT_P0_2026-11-07_ZH.md)，未改模块沿用该基线，不声称本阶段重新执行。

## 测试时序修正与失败记录

连续套件与完整套件分别发现既有PrivateChatPanelTest在输入法窗口移动期间进行坐标点击／立即可见性判断的竞争：一次发消息未命中按钮，一次会话菜单未打开，一次大字输入栏尚未就位。保留失败XML，没有用无限重试掩盖。

行为测试改用真实的无障碍OnClick动作发送和选择会话；键盘布局测试仍保留输入、发送、关闭与停止按钮断言，并以5秒上限等待必要控件同时可见。修正后重新运行完整137项及相同源码57项连续3次。实际键盘触摸体验仍不等同于离线语义动作验证。

## 命令与证据

```sh
./gradlew --offline --max-workers=2 :app:ktlintFormat
./gradlew --offline --max-workers=2 :app:testDebugUnitTest
./gradlew --offline --max-workers=2 quality :app:lintDebug :app:lintRelease :app:assembleRelease :app:connectedDebugAndroidTest
```

格式修改先于检查。CI选类包含MicrophoneControllerTest、PlaybackFailureTest、ChatNavigationStateTest、ChatPanelTest、PrivateChatPanelTest、BookmarkEditorTest、BookmarkListTest；对`connectedDebugAndroidTest --rerun`连续运行3次并分别保存结果。源码及测试仍是未提交工作区时，以424份源码／测试／配置SHA-256清单绑定；提交后由PR固定源提交。

本机证据在忽略的`build/improvement-2026-11-07/`：`a1-before/`、`a1-reproduction.log`、`a1-full-accepted/`、`a1-q1-full-fixed.log`、`q1-accepted-{1,2,3}-results/`、对应日志及`a1-q1-source.sha256`。之前失败记录保留，不能混入最终通过汇总。

后续[PR #8云端CI](https://github.com/monet4070/ts3-mobile/actions/runs/42348440916)已通过，包含新增报告上传。head为`b1b44a9`，GitHub测试合并提交为`282ad486d489bbf2b1b7e619a19e01dc2890a89f`。已下载并核对94,679字节报告产物：协议129／音频194／应用241 JVM、应用57／音频11 Android，均零失败／错误／跳过；其中新增导航8项存在于报告。云端音频只选原CI的3类，不能描述为本机完整32项。原有Release与codec探针也通过。

第一批A3与R1仍需独立验收，没有宣称整个第一批交付；A2的后续证据单独记录。

## 变更分类

- 生产：ChatState/ChatStore的随机会话token、ChatSessionState、ConnectedPanel与ChatConversationContent。
- 测试：ChatSessionTokenTest、ChatNavigationStateTest、PrivateChatPanelTest的输入法时序与无障碍动作。
- 配置：CI选类及限时保留的XML／启动日志产物。
- 文档：ADR、双语指南、架构、贡献指南、计划进度与本记录。架构文件只补相应责任边界，不顺带重构整份长文。
