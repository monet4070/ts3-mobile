# 当前服务器聊天附件：只读探针

2026-11-24。最初在 Debug 验证，现已进入标准构建。线上聊天显示可核实的当前服务器附件；轻触只展示服务器文件名、频道和目录，点「下载附件」才暂存，然后通过系统文档选择器另选保存位置。不自动预览、下载、执行、打开链接或连接其他服务器。复制和持久聊天历史仍保留原文。

## 兼容与安全边界

- 格式按 [TeamSpeak 团队的 Copy URL 样本](https://community.teamspeak.com/t/server-and-channel-banner-direct-upload/39553/7) 构造。接受单独的 `ts3file://` 文件引用或聊天 `[url]`／`[url=…]` 包装；同一参数不能重复，频道须是正数，路径与名称分别校验，百分号只解码一次。大小和日期不作为授权、写入长度或文件版本凭证。目录、未知 UID、未知频道、截断、云附件及外链不进入附件下载。
- 当前虚拟服 UID 以这次已连接的语音会话为准。3.13.9 隔离实例的握手不含 UID，使用同一连接上一次有界 `whoami` 读取；该查询不取文件，不使用 ServerQuery。无法取得 UID 时回退为纯文本，Release 不发该查询。链接的主机和端口不用于 DNS、重连或选择文件传输目标；TCP 仍只连接语音会话的对端 IP 与本次协议协商端口。
- 附件句柄绑定服务连接代次及目标频道生命周期，即使在同一虚拟服也不自动加入目标频道。实际权限由 TS3 决定，拒绝不是成功。复用 20 MiB 限额、长度和 EOF 校验、取消清理，保存结果不可信时显示错误。消息没有可信哈希，下载的是服务器当前路径的文件，不保证还与分享时的版本相同。
- 未增加生产配置项、存储格式或权限。协议模块的 Kotlin 模型只追加带默认值的字段、句柄和探针构造参数，应用与模块一并重编。对现有大型协调器和界面文件只增加委托接缝；解析、对话框与可取消的文件保存各自独立。若再增加上传或批量传输，先抽出独立的会话文件控制器，而不是增加协调器职责。

## 验收

- 官方 3.13.9 回环服务：普通访客在正确 UID 下可下载可读文件，在无权限频道返回拒绝；换到拒绝频道后仍能手动从原可读频道取件，不切换频道。不同 UID、未知频道、失效窗口不会发起下载；断开后旧句柄不能使用。同批复跑既有频道文件／Opus 和服务器资源探针。
- API 35 模拟器连接同一官方隔离服务：第二个临时客户端真实发送测试 BBCode，Android 收到后点击附件、确认下载，通过实际 `ContentResolver` 写入测试文档提供方，读回字节匹配。服务器和频道图标也走 Android UI 与真实会话；未打开麦克风、不放声音或访问外链。该轮使用测试文档提供方替代系统选择器结果。
- 原生选择器补验：在英语 API 35 一次性模拟器上，使用默认固定 TS3j 和相同实服夹具，从聊天附件进入 Android `DocumentsUI`。确认预填 `probe.txt`，改成唯一测试文件名，点系统 `Save`，返回应用后收到已保存反馈，从 `Download` 读回 27 字节并逐字节比对通过。测试文件已删除。这不是生产设备或第三方云文档提供方的验收。
- API 35 离线回归 12 项通过：重复下载门禁、切换代次取消、原频道文件保存／失败／取消、HTTP(S) 确认、头像和图标预览。默认 CI 只跑离线用例；可选实服用例没有参数即跳过。
- 合成引用依据官方团队样本，由另一固定 TS3j 客户端发入服务器供 Android 收取。两份官方 macOS 3.6.2 原始剪贴板样本另经 JVM 实服验证，不计入合成消息的 Android 原生选择器测试。未验证 TS6 云附件、线上服或无线网络。Release APK 仍关闭该入口。
- 国际化显示修复后，API 35 上运行附件、聊天富文本、频道文件和图标的 11 项离线回归，均通过；新增用例验证聊天和确认对话框显示完整文件名。
- 单测、Ktlint、Debug／Release Lint 与混淆 Release 构建通过；成品资源表确认显式探针 Debug 为 `true`、Release 为 `false`。最终实服批次中，固定 TS3j 曾在一轮新连接握手期间异常，未进入文件阶段；保留失败证据，另启同配置隔离副本后 JVM 三项和 Android 用例均通过。不把复跑通过当成已修复握手问题。

JVM 实服复跑沿用[频道文件夹具](CHANNEL_FILES_PROBE_2026-11-24_ZH.md)和[图标夹具](SERVER_IMAGE_PREVIEW_2026-11-24_ZH.md)。在已授权的 IPv4 回环实例上运行：

```sh
./gradlew --no-build-cache -Pts3LocalFileProbePort=19987 :ts3-protocol:test \
  --tests '*LocalChannelAttachmentProbeTest' --tests '*LocalServerImagesProbeTest' --tests '*LocalChannelFilesProbeTest'
```

Android 实服使用[同一回环桥](CHANNEL_FILES_PROBE_2026-11-24_ZH.md#usb-单设备复跑)、两个临时身份和独立测试包。将 `ts3UsbFixturePort` 与隔离夹具的 `ts3FileFixtureUid` 传给 `LocalChatAttachmentAndroidProbeTest`；不要使用生产服务器 UID、地址或账号。测试结束停止实例、移除本轮转发，不发布初始化日志。

原生选择器复跑仅使用英语、初始保存位置为 `Download` 的一次性 Android 模拟器，给用例另传 `-e ts3SystemPicker true`。若需同跑真实头像，按[发布夹具步骤](SERVER_IMAGE_PREVIEW_2026-11-24_ZH.md#带真实头像通知的-android-复跑)启动发布方，并传 `-e ts3AvatarFixture true`。用例通过后确认模拟器 `Download` 没有残留 `ts3-mobile-attachment-*.txt` 测试文件。

### 桌面端样本准备

从 [GitHub 归档](https://github.com/PaulArgent/teamspeak-archive/blob/main/ts3/TeamSpeak3-Client-macosx-3.6.2.dmg) 暂存镜像，实测 SHA-256 为 `8a6873589aeb98fdbd9509f2a1163662770f1426aed0757704203de40d71d4d6`，与 [TeamSpeak 下载页](https://teamspeak.com/en/downloads) 及 [Homebrew 清单](https://formulae.brew.sh/cask/teamspeak-client) 一致。获得用户许可后，只读挂载镜像、检查应用签名，并从挂载点直接运行，未全局安装。镜像、临时用户配置和隔离服务端副本均在 Git 忽略的 `build/ts6-borrow/official/desktop-copyurl.*` 下。

桌面端使用临时身份，不登录 myTeamSpeak；沙箱限制配置写入位置和 IP 出站目标，运行中验证系统拒绝麦克风，采集时界面也静音麦克风和扬声器。客户端使用临时 UDP 接收端口，服务端只在 IPv4 回环监听，观察到服务端另有非回环出站，因此不是完全离线测试。文件浏览器成功列出 `probe.txt` 和 `QA 中 A+100%.txt`；自动化未能读取 Qt 文件右键菜单，由用户点各文件的 `Copy URL`。未使用生产服务器或真实文件。

将两个原始剪贴板样本分别保存为隔离目录中的 `ordinary.url` 和 `international.url`。复制后先确认本地采集完成，再切换回聊天或输入确认文字，以免覆盖剪贴板。采集时只保存已知夹具的链接，不记录其他剪贴板内容。然后运行：

```sh
TS3_DESKTOP_COPY_URL_DIR="$RUN/clipboard-samples" ./gradlew --no-build-cache \
  -Pts3LocalFileProbePort=19987 :ts3-protocol:test \
  --tests '*LocalChannelAttachmentProbeTest.desktopCopyUrls*'
```

两项用例均使用原始剪贴板样本实跑通过、无跳过。测试分别核对原始 BBCode 显示名、解码后的文件名、目录、当前虚拟服 UID 和默认频道；下载只使用已经建立的 IPv4 回环会话，两份文件均与 27 字节夹具逐字节一致。样本正文不进入测试错误或仓库。

两份样本都使用 `[URL=链接]标签[/URL]`，UID 的 Base64 填充经过百分号编码。国际化样本的 URL 参数正确编码了中文、空格、`+` 和 `%`，但官方客户端在显示标签里把 `%.t` 变成了 `U+FFFD`。应用只在成功核验当前服务器和频道的附件显示中，用已解析的完整文件名替换含替换字符的标签；不放宽 URI、路径或会话校验，不改存档和复制原文，HTTP(S) 标签保持原行为。

补验结束后停止桌面客户端、回环服务、采集进程和临时模拟器，卸载镜像并复核监听端口释放。未覆写用户当前剪贴板。样本及测试日志留在 Git 忽略的本地目录。
