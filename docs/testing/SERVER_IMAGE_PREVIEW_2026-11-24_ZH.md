# 头像与图标：按需预览验证

2026-11-24。最初在 Debug 验证，现已进入标准构建。频道列表的服务器和频道图标仍按需点击下载，频道成员行不再显示头像按钮。只有「用户」页视口内、服务端已公告头像的用户才尝试加载左侧缩略图；缺图、加载中或读取失败时保留原状态图标。有图时点击缩略图打开预览，静音／发言状态保留为角标。没有全服预取、外部 URL、磁盘缓存、离线收藏图标或上传。

## 资源和边界

- `ServerImage` 仅产生 `cid=0` 的 `/icon_<unsigned32>` 或 `/avatar_<40个a-p字符>`。频道文件仍要求正频道 ID 和当前频道，本次未开放服务器根文件列表或任意根路径。资源通道仍用既有传输 ID、会话对端 IP、精确长度检查及服务器权限。参考 [ReSpeak 资源调用](https://docs.rs/tsclientlib/latest/tsclientlib/struct.Connection.html#method.download_file) 和 [TS6_Droid 头像路径](https://github.com/flamme-demon/TS6_Droid/blob/f8a3c9c03a5d97e31cc48ff03c4c1ddbfae7dab1/app/src/main/kotlin/dev/tsdroid/bridge/AvatarCache.kt#L25-L40)，未复制其缓存实现。
- 通知里的零图标、无效 UID 和空头像版本不产生资源；更新未携带对应字段时保留原值，UID 变化时不复用旧头像。支持有符号及无符号的 32 位图标 ID。头像版本是服务端通告的 MD5，只用于检测字节与版本不一致，不是认证机制。
- 单张源文件不超过 256 KiB，不写暂存文件。只解码 PNG、JPEG，限制原始边长至 2048、解码至最多 256，单张位图不超过 256 KiB。按传输会话隔离，内存 LRU 最多缓存 16 张位图、32 条失败记录；失败冷却 10 秒，忙碌不缓存。缩略图和预览共用缓存，同一资源请求串行去重；离开用户页或视口时取消该行的等待，头像版本或连接变化时不沿用旧图。
- 会话模型只追加带默认值的可选媒体字段，Kotlin 调用点无需迁移；仓库内 JVM 模块与 Android 一并重编，不变更配置和序列化格式。三个大文件 `Ts3jSessionClient`、`MainActivity`、`MainScreen` 只保留会话门禁或 UI 回调，视图与缓存独立；后续若继续增加传输入口，应先统一门禁并拆出界面文件操作宿主，不在此次扩大重构。

## 验证

- 左侧缩略图改版：API 35 模拟器的头像、缓存、频道文件和聊天附件 11 项离线回归通过。验证视口外头像不请求、无头像状态图标保留、缩略图打开预览不重复下载、频道成员无头像入口。旧布局已在真机连实际服务器打开头像；新版尚未覆盖手机上的旧 QA 包。
- 官方 3.13.9 IPv4 回环实例：服务端、默认频道均公告合成高位图标 ID；图标及头像读回字节匹配，错误头像版本被拒，断开后原句柄失效。原频道文件探针同次复跑通过，含下载与 Opus 并行。两项实跑，均未跳过。实例已停止，未使用真实服务器、真实头像或用户凭据。
- API 35 隔离模拟器：3 项新用例与 5 项既有目录文件用例通过。覆盖零预抓、逐个入口点击、同图标复用、资源消失关闭、跨会话隔离、有界解码和失败冷却。模拟器使用合成响应，不代替 Android 实服的整条 UI 路径。
- 附件阶段又用 Android API 35 对官方回环实例实跑：从真实会话快照点击服务器与频道图标，分别显示 PNG；二者在本夹具使用同一资源 ID。头像仍分别由 JVM 实服、模拟 UI 覆盖。
- Android 头像端到端补验：同一官方实例的第三个临时客户端进入默认频道。它看到 Android 客户端加入后才变更自己的合成头像标识。旧版已验真实会话中的头像预览；新版复跑用例验证频道页不取头像、进入用户页才加载可见头像、点击后仍复用第一次下载。没有生产账号、真实头像、麦克风或桌面程序参与。
- JVM 与 Android 单测、Ktlint、Debug／Release Lint 和混淆 Release 构建通过。同一显式探针参数的 APK 资源表实测 Debug 为 `true`、Release 为 `false`。Android 回归已接入 CI 配置，未触发托管 CI。

离线检查：

```sh
./gradlew :ts3-protocol:test :ts3-protocol:ktlintCheck :app:ktlintCheck :app:lintRelease
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ts3mobile.app.ui.ServerImagePreviewTest,io.github.ts3mobile.app.ui.ServerImagePreviewCacheTest
```

实服复跑沿用[回环实例](CHANNEL_FILES_PROBE_2026-11-24_ZH.md#官方服务端互通门槛)。只在停止状态的隔离实例副本中解码 `ts3-protocol/src/test/resources/server-image-fixture.properties` 的 `png`；放为 `files/virtualserver_1/internal/<avatar>` 及 `files/virtualserver_1/internal/icons/icon_<icon>`。将该实例的服务器图标设为夹具的无符号 `icon`，默认频道的 `i_icon_id` 设为对应有符号 int（`-1060278574`）。再运行：

```sh
./gradlew --no-build-cache -Pts3LocalFileProbePort=19987 :ts3-protocol:test --tests '*LocalServerImagesProbeTest' --tests '*LocalChannelFilesProbeTest'
```

### 带真实头像通知的 Android 复跑

用仅供测试的 `RUN` 目录，其中 `RUN/server/` 是已确认的隔离服务端副本。执行以下命令启动头像发布夹具，不接触其他实例或用户文件：

```sh
printf 'temporary ts3-mobile avatar fixture\n' > "$RUN/.avatar-fixture"
TS3_LOCAL_AVATAR_FIXTURE="$RUN" ./gradlew --no-build-cache -Pts3LocalFileProbePort=19987 \
  :ts3-protocol:test --tests '*LocalAvatarFixturePublisherTest'
```

等待 `avatar-publisher-ready`，另开终端按[附件的 Android 复跑](CHAT_ATTACHMENT_PROBE_2026-11-24_ZH.md#验收)启动 `LocalChatAttachmentAndroidProbeTest`，加上 `-e ts3AvatarFixture true`。只有测试通过后才在 `RUN` 下创建 `android-avatar-verified`；失败则创建 `abort-avatar-probe` 终止发布者。它会删除本次合成头像，不记录临时身份。两边均通过才算成功。
