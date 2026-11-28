# TS6_Droid 源码对照：TS3 Mobile 值得补哪些能力

2026-11-24。以公开源码静态审阅为准；没有安装上游 APK、连接真实服务器、采集音频或测试性能。

## 结论

**借鉴聊天和频道文件的交互，不换协议栈，不移植音频。** 对 TS3 Mobile 的三项直接收益：先补无需联网预览的富文本和收藏快捷连接；并行验证既有 ts3j 能否完成只读频道文件；若验证通过，再做按需头像、图标和附件。聊天历史有用户价值，但应另外设计授权、留存和清理，不应随附件功能自动开启。

### 源码边界

- Android：[`flamme-demon/TS6_Droid@f8a3c9c`](https://github.com/flamme-demon/TS6_Droid/tree/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1)。`v1.0.0` 是 `ac083da`（2026-02-26）；截至审阅日，`main` 相对标签仅有 README 链接变动。该快照没有应用测试目录或独立授权文件，不复制 Android 层实现。
- Rust：[`flamme-demon/tslib_multi@9326ed2`](https://github.com/flamme-demon/tslib_multi/tree/9326ed2669055092b8e96ca1de466fc2a09fdc8e)，审的是当前配套源码，不是从发行 APK 逆推依赖。它有 MIT／Apache-2.0 声明、库和测试套件；这不覆盖 Android 仓库，也不证明所有传递依赖的授权。Gradle 的 `buildRustLibs` 依赖相邻 `../tslib`，没把版本固定成同一个可复现构建图；Cargo 对 `ReSpeak/tsclientlib` 等用未固定 `rev` 的 Git 依赖，仓库不提交 `Cargo.lock`。
- 我们：`8bd1427` 加当前工作区。保留 `app → ts3-protocol → 固定 ts3j`、`audio-opus`、现有会话/身份/音频发布门槛。对照源码实现不等于推断两项目的用户规模、运行质量或互通范围。

## 功能和代码对照

| 功能 | TS6_Droid 实现 | TS3 Mobile 可得到的改进与边界 |
| --- | --- | --- |
| 文本格式、链接、附件呈现 | [`MessageBubble.kt`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/ui/component/MessageBubble.kt#L103-L221) 把样式文本、链接缩略图、附件状态分开。BBCode 另有 Java/JNI/Rust 路径和 [解析测试](https://github.com/flamme-demon/tslib_multi/blob/9326ed2669055092b8e96ca1de466fc2a09fdc8e/crates/tslib-chat/src/bbcode.rs)。链接缩略图默认关，图片附件自动载入默认开。 | **优先做。** 在 [`ChatMessageRow`](../../app/src/main/java/io/github/ts3mobile/app/ui/ChatMessageRow.kt#L46) 接一个纯 Kotlin 有界解析器，只做常用粗体、斜体、代码、显式 HTTP(S) 链接。原文继续用于复制与发送，不在渲染时联网。保留无效标签的原文降级。视频缩略图、图像标签和外链探测不作为首版。 |
| 收藏和进服前频道选择 | [`ConnectionViewModel`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/viewmodel/ConnectionViewModel.kt#L150-L168) 收藏一按即连；频道浏览另开一个真实 `Client`，等待频道后离开，并把父路径拼为默认频道字符串。所谓启动自动重连按最近点击的收藏地址查找，不是我们已有的网络恢复状态机。 | **先做显式一键连接。** 复用 [`BookmarkDraft`](../../app/src/main/java/io/github/ts3mobile/app/bookmarks/BookmarkDraft.kt#L3) 的凭据清除规则、现有预填和连接门禁。已有 [`BookmarkChannelPicker`](../../app/src/main/java/io/github/ts3mobile/app/ui/BookmarkChannelPicker.kt#L24) 按当前同端点快照显示父路径和 ID，不重做。优先增加“上次成功获取的频道”离线辅助，标明可能过期、按规范化 host+port 隔离；无快照时才评估用户主动连接后浏览。不要在后台探服或用斜杠路径取代现有 ID。 |
| 频道文件 | [`FileManagerDialog`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/ui/component/FileManagerDialog.kt#L74-L133) 有文件夹导航、目录优先和权限提示；代码接了列表、下载、上传、重命名、删除、新建目录以及分享到私聊/频道。UI 在权限位未知时不显示写操作；权限提示不能当服务端授权。Rust [列表和传输命令](https://github.com/flamme-demon/tslib_multi/blob/9326ed2669055092b8e96ca1de466fc2a09fdc8e/crates/tslib-core/src/client.rs#L1080-L1168) 和传输事件确实存在，不只是屏幕设计。 | **跑只读能力验证，再决定做 UI。** 当前 [`Ts3SessionClient`](../../ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3SessionClient.kt#L15) 无文件接口。对本机固定 `ts3mobile-1` JAR 的 `javap` 检查见 `FileTransfer`／`FileTransferParameters` 数据包装，但 `LocalTeamspeakClientSocket` 未暴露现成可调用的文件传输入口。先验证通过现有命令适配完成列表、授权和独立有界传输；不能把有模型类写成已有文件客户端。 |
| 聊天附件互通 | [`ServerViewModel.parseFileAttachment`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/viewmodel/ServerViewModel.kt#L409-L440) 识别 `ts3file://` 及 `ts.file.myts` JSON；后续 `downloadAttachment` 仍走当前 TS 客户端的频道文件下载，没有对应 myTeamSpeak 云下载实现。 | 把聊天卡片与传输任务分离；第一版只接受可核实的**当前服务器**引用、明确频道与路径，由用户点击下载。其他服务器或无法确认的引用显示普通内容，不切服、不访问外站、不落地。与桌面/TS6 的具体附件格式先用独立样本核对再承诺。 |
| 头像、频道图标与服务器图标 | [`AvatarCache`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/bridge/AvatarCache.kt#L24-L101) 由 UID 映射到服务器头像路径；`IconCache` 按图标 ID 拉取，收藏会用服务器图标。缓存 ID 未包含服务器作用域，内存 Map 无容量上限。 | 无文件能力时先用本地首字母／状态徽标。文件能力通过后再做可见项按需加载、请求合并、有界缓存和失败冷却；区分服务器端点、资源类型、资源 ID/版本，不跨服务器复用。预览缓存用应用私有空间，“保存到…”由用户主动选位置。第一版不预抓整台服务器的头像。 |
| 本地聊天历史 | [`MessageStore`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/data/MessageStore.kt#L12-L78) 可重开旧消息，每串保存末 500 条；手写 JSON、普通文件覆盖、按地址改写文件名、私聊以会话内数字 ID 分组，限条数不是全局字节限额。 | 价值在断连后可回查，不是照搬存储。现有 [`ChatConversationKey`](../../app/src/main/java/io/github/ts3mobile/app/chat/ChatConversation.kt#L6) 已区分稳定 UID 和只适用于本次连接的临时 ID，[`ChatRetentionPolicy`](../../app/src/main/java/io/github/ts3mobile/app/chat/ChatRetentionPolicy.kt#L4) 已控制全局条数/字节。若做磁盘历史：默认关；按服务器、自己的身份、频道或对端 UID 隔离；临时数字 ID 不跨连接存，明确过期、清除和备份排除。不要把历史草稿/待发送消息当作重发队列。 |
| 会话与权限提示 | `TsClient` 将 native 事件映射到多个 `StateFlow`，文件等待用延迟结果；权限不足时写操作默认隐藏，服务端仍判定。`processEvents` 循环主动泵事件并频繁刷新快照。 | 借鉴可取消、可重试的 UI 状态，以及不可用原因，不复制手动轮询或全量状态刷新。新能力使用 `Unknown / Allowed / Denied`，明确显示服务器拒绝，所有回调受会话代次和请求 ID 限制。保留 [`ConnectionCoordinator`](../../app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt#L333) 的有界退出和跨网络会话规则。 |

## 不适合直接移植的实现

1. **不是 TS6 原生协议证据。** Android 调 `dev.tslib.Client`，Rust [连接构造](https://github.com/flamme-demon/tslib_multi/blob/9326ed2669055092b8e96ca1de466fc2a09fdc8e/crates/tslib-core/src/client.rs#L114-L138) 交给 `tsclientlib`；Cargo 自称 TeamSpeak 3 库。它可作为 TS3 服务器功能参考，不能据此宣布 TS6 专有能力、云附件或账号功能兼容。引入 native 还涉及 `cargo-ndk`、OpenSSL、当前四 ABI（上游脚本输出两 ABI）、Android 内存页兼容、混淆/JNI 和依赖固定。源码中的 [`Client` JNI](https://github.com/flamme-demon/tslib_multi/blob/9326ed2669055092b8e96ca1de466fc2a09fdc8e/crates/tslib-jni/src/client.rs#L9-L17) 从裸指针取得可变引用，调用方音频、事件和 UI 路径没有统一的所有权串行化；静态看到的并发风险是换栈前的审计项，不声称已在设备复现。
2. **不拿它解决 AirPods、延迟或浮窗。** [`AudioBridge`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/bridge/AudioBridge.kt#L80-L220) 是 48 kHz/20 ms、单声道、每人 FIFO 和硬裁剪混音；队列满实际拒收新包，与注释“丢最旧”不一致。录音循环可在 PTT 静音时继续 `AudioRecord.read`，按键主要控制是否发包。低延迟是 `AudioTrack` 的请求值，没有我们 A1/A2 要求的实效、路由保护和诊断证据。没有用于验证游戏浮窗的入口或权限；服务仅声明 `mediaPlayback`。继续使用自己的 [`OpusAudioPlayer`](../../audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusAudioPlayer.kt#L100)、[浮窗与设备验收交接](DESKTOP_PTT_AUDIO_EXECUTION_STATUS_2026-11-23_ZH.md) 和真机门槛。
3. **下载不能“取前 N 字节也算成功”。** Rust [`FileDownload` 分支](https://github.com/flamme-demon/tslib_multi/blob/9326ed2669055092b8e96ca1de466fc2a09fdc8e/crates/tslib-core/src/client.rs#L395-L435) 把声明大小截到 10 MiB 后 `read_exact` 这部分即可发成功事件，按该分支可把较大文件的前缀当完整下载；没有实测该情形。Android 也通过整份 `ByteArray` 过 JNI，上传用 `readBytes()`、下载回调按路径登记，资源缓存只用主机/路径。我们应实读限额、超限显式失败、独立串流和取消清理；同名不同频道、端口或请求不能共用结果。禁止凭附件给出的地址直连，实际传输端点由当前会话协商并验证。
4. **保留现有凭据边界。** 对方 [`BookmarkStore`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/data/BookmarkStore.kt#L97-L128) 把密码写进普通 Preferences JSON；[`ConnectionViewModel.getOrCreateIdentity`](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/viewmodel/ConnectionViewModel.kt#L285-L294) 写本地 `identity.ini`，Manifest 允许备份。与本项目 Keystore、备份排除、凭据作用域及桌面身份确认流的约束不同。只参照交互，不照搬存储或日志。

## 建议执行顺序

| 优先级 | 最小交付 | 验收与止损 |
| --- | --- | --- |
| P0 · 可独立交付 | 聊天只读 BBCode/安全链接；收藏显式“立即连接”，保留原编辑和填表路径。分别开小改动，不和浮窗/音频混发。 | 富文本测试格式错乱、嵌套、非法 scheme、超长、多语言、一字不丢的复制、渲染无网络；收藏测试更换端点不携带旧密码、忙碌不重复连接、失败不更新“上次成功”。 |
| P1 · 能力验证 | 现有 TS3j 建一个 **只读** 文件端口的最小验证，先用 fake 列表和传输流确认错误模型、权限、单任务取消和完整性，再在隔离 TS3 服务端做一次互通。 | 根/子目录、同名不同频道、会话更换、拒绝、超时、少读、多读、超限、取消后无半文件；另测大文件传输调度不阻塞语音事件泵。失败先停在研究分支，不换整个协议栈。 |
| P2 · 能力过门后 | 频道文件只读浏览/手动保存，随后是频道/服务器图标与附件卡片。上传、删除、重命名和外部 URL 缩略图分开验收。 | 当前会话隔离、明确下载位置、缓存可清理；权限未知不显示写操作，服务器拒绝不伪装成功。 |
| 独立选项 | 默认关闭的本地聊天历史；先确认用户需求和隐私交互，再选持久化方案。 | 跨服务器/身份/频道不串台；不把临时 ID 和发送中队列持久化；共享容量、精确删除、升级/损坏恢复和备份排除可测。 |

如果只能做一个 PR，选 **聊天 BBCode + 明确点击打开安全链接**：它直接改善桌面用户发来的频道消息，无需新权限、Rust、外部网络预取或物理设备验收。更有差异化但未知数更大的下一项，是在不动现有语音栈的前提下验证频道文件能力。
