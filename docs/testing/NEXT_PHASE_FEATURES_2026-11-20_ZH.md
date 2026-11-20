# 邀请、试麦与通知收听控制：开发验证

2026-11-20 · 开发工作区，未发布。基线与候选均为 `versionCode 15`。
本轮实现 [11 月 19 日计划](../plans/NEXT_PHASE_2026-11-19_ZH.md) 中的两个 P1 和通知收听 P2，
不改通话缓冲、后台采集规则或耳机回落策略；不启动第二阶段候选功能。
设计边界见 [ADR 0037](../decisions/0037-invites-local-microphone-trial-and-notification-playback.md)。

## 开发版操作

以下操作不适用于 1.1.0 正式包。

- 设置：点「更多选项」→「设置」，按语音、通知、个性化与身份、帮助与关于查找偏好和排障工具；进入和返回不会断开会话。外观提供浅色、深色和跟随系统的卡片预览，后台语音运行检查与诊断为独立全屏页。身份与备份、检查更新也用左上角箭头返回；展开更新说明时，返回先回更新摘要。
- 快捷调音量：点底部语音栏右侧的麦克风图标，拖动竖向滑杆。它和「设置 → 麦克风设置」共用输入音量，100% 为原始音量。调节不会开启麦克风，也不会改变收听音量。
- 邀请：打开 `ts3server:` 链接或在连接页点「粘贴邀请」，核对目标与昵称后确认连接。
  如有活动会话，先主动断开；链接里的密码与令牌不采用，按需手动补密码。指定频道无法加入仍保留服务器连接。
  已连接时点「更多选项」→「分享邀请」，再选择服务器或当前频道，分享文本不包含凭据。
- 本机试麦：点「更多选项」→「设置」→「麦克风设置」→「本机试麦」。未连接时可直接点「开始试麦」；入服后先按提示关闭通话麦克风，再开始。
  查看实际输入、电平和门控，不发送音频、不保存录音。关闭、切后台、改变输入路线、掉线或满 30 秒都会结束；不会自动开启通话麦克风。
- 通知收听：连接成功后，支持自定义通知布局的系统会在折叠卡片显示「断开连接」和「静音／恢复收听」，无需长按。默认连接通知使用普通优先级、无声音和振动；系统仍决定排序或是否归入「更多通知」。耳机保护触发时只能回应用检查路线，快捷操作不能解除保护。

### Development controls

- Open **More options → Settings** for voice, notifications, personalization, identity and help without disconnecting. During a session, **More options → Share invite** offers server and current-channel links.
- Tap the adjustment icon in the bottom voice controls for a vertical microphone input-volume slider. It shares the saved setting with Microphone settings and does not enable the microphone or change listening volume.
- Open **More options → Settings → Microphone settings → Test microphone** offline or while connected. If connected, turn off the voice microphone before starting. No test audio is played, sent or saved, and the voice microphone is not turned back on automatically.
- During a session, the compact connection notification shows **Disconnect** and **Mute/Resume listening** on systems that render custom notification content. The default connection channel has normal importance without sound or vibration; placement depends on the system. Headset protection still requires checking the route in the app.

## 已验证

| 门禁 | 结果 |
| --- | --- |
| 格式、编译、静态检查 | `quality`、`:audio-opus:lintDebug`、`:app:lintDebug`、Debug 与混淆 Release 构建通过；既有 Lint／R8 提示未在本轮修改。 |
| JVM | 810 项通过（协议 133、音频 234、应用 443）。覆盖默认／显式端口、IPv6、重复参数和凭据隔离、邀请确认单次消费、频道拒绝后保留连接；覆盖通知切服／重连／耳机保护以及试麦超时、抢占、释放和零编码帧。 |
| Android | API 35、`arm64-v8a` 模拟器通过应用 24 项和音频 11 项。新用例覆盖冷启动／旋转、热启动／手动粘贴、试麦暂停后不自动重启、通知动作实际投递与耳机保护提示。诊断、更新解析、表单记忆、私信通知及音频生命周期回归也通过。 |
| 混淆 Release 探针 | 独立进程从候选 APK 完成身份与 INI 往返、Opus 1.6.1 编解码往返；运行 ABI 为 `arm64-v8a`。 |
| 覆盖安装 | 在独立 API 35 模拟器用同一开发测试证书，从 `v1.1.0` 源码构建的 Debug 覆盖至混淆候选。合成身份／表单／收藏文件、原 Keystore 密钥、调音文件与应用 UID 保留；候选界面能读取旧表单和收藏，并可预览外部邀请。此步骤不是生产签名或真实用户迁移验收。 |
| 辅助检查 | 18 项 Python 工具测试、仓库隐私扫描、`git diff --check` 通过。 |

本机工件与执行日志保留在忽略的 `build/next-phase/`；覆盖安装的探针结果位于
`build/upgrade-compat/`。没有使用真实服务器、身份或凭据。上述结果不是发布验收。

## 2026-11-20 早期开发版真机抽测

设备：小米 14，Android 16（API 36），`arm64-v8a`。用独立包名 `io.github.ts3mobile.app.nextphaseqa` 旁装 Debug 候选（`versionCode 15`），未连接真实服务器。

| 项目 | 结果 |
| --- | --- |
| 邀请预览 | 通过：冷／热启动的外部链接只打开预览；默认／显式端口、频道 ID 和凭据忽略提示正确，取消不改原表单。从「粘贴邀请」手动输入 IPv6 链接可预览；重复指定端口会被拒绝。未测试剪贴板读取、实际连接和分享。 |
| 本机试麦 | 通过：出现 Android 麦克风权限提示；授权后显示设备内置麦克风，输入电平从 −96 dBFS 变化到 −69 dBFS，用户也确认试麦正常。手动停止、关闭、切后台或约 30 秒超时均结束采集，返回应用不会自动重启。未验证拒绝权限、门控开启或外接麦克风。 |
| 通知静音／恢复收听 | 未完成：设备拒绝安装 instrumentation 测试包，且没有受控服务器可供连接。模拟器的离线通知动作测试不计作真机结果。 |

候选 APK SHA-256：`4bdd0679ad5dd9a7376cd0255b42add52cb4857116bd817a66758f781bd1f6f3`。
本机日志位于忽略的 `build/next-phase-device/`。测试后移除了旁装包，正式版 1.1.0 的已安装 APK 校验值前后一致。

## 2026-11-20 交互调整复验（早期候选）

- 连接页将邀请移到主按钮下方，改为带图标的描边按钮；试麦改到已连接菜单，且必须先关闭通话麦克风。
- 全量 JVM 812 项、API 35 模拟器定向 5 项通过。后者使用离线合成会话：确认连接前没有试麦入口、切后台停止且不重启、通知和控制中心动作切换收听且不改变麦克风模式。控制中心用例在独立模拟器预置 `TS3 Listening` 开关并传入 `isolatedQuickSettings=true`，不在用户手机上运行。
- 格式、Lint、Debug 和混淆 Release 构建通过。使用原签名覆盖小米 14 上的上一候选包，核对 APK 校验值、原应用 UID 和控制中心服务可被系统发现。未替用户添加开关、连接服务器或播放声音；HyperOS 实际切换、连服后物理收音与对端零发送仍待实测。
- 本机日志与候选包位于忽略的 `build/connected-ux/`。候选 APK SHA-256：`c493c13303b2a3fc3a3d7871273f10c5285b28a16349840ef771dea42bbec8c1`。

## 折叠通知按钮复验

- API 35 模拟器使用离线合成会话检查系统通知栏：折叠卡片直接显示「断开连接」和「静音收听」，点击后仍在折叠态并变为「恢复收听」。自动化用例验证两种卡片复用同一通知动作，折叠态可静音与恢复，保护状态只显示「检查音频路线」。
- 格式、Lint、应用 JVM 测试、API 35 定向 Android 测试 5 项、Debug 与混淆 Release 构建通过。本机日志、界面示意与原签名候选包位于忽略的 `build/compact-notification/`。候选 APK SHA-256：`fdea14e715c850bd95521bfa5ebec649806e3440116a4e57d7fe9ce845d8af96`。
- 等小米 14 会话结束后，使用原签名覆盖上一候选包，核对 APK 校验值并验证原应用 UID 保留。未主动连接服务器或播放声音。HyperOS 上默认折叠态的显示和点击仍待验证。

## 通知归类与控制中心移除

- 小米 14 上确认上一版连接通知的有效优先级为低。新版给未改过的默认通道迁移至普通优先级、无声音及振动；关闭或手动调整过的通道保留。折叠按钮和耳机保护不变，移除了控制中心入口、磁贴服务和相关测试。
- 应用 JVM、格式、Lint、Debug 与混淆 Release 构建通过。API 35 模拟器的定向 Android 测试 7 项通过，包括默认通道迁移、保留非默认设置以及折叠卡片动作。HyperOS 的通知过滤和最终位置仍需重新连服检查。
- 原签名候选包及日志位于忽略的 `build/notification-visibility/`；候选 APK SHA-256：`f63358bca755204b7a8a12045bb301a76e1e81ca9412a66fc082c22b9e5583ea`。
- 确认小米 14 已无前台会话后原位覆盖安装；核对设备 APK 摘要、应用 UID，以及系统已找不到控制中心磁贴服务。未主动重新连服或播放声音。
- 用户重新连服后确认：按钮正常，但 HyperOS 仍把卡片归入「更多通知」。设备复查确认旧通道已停用，新通道确实为普通优先级、无声且不振动。提高通道优先级不是这台手机的通知归类修复；系统「设为重要」入口及效果尚未验证。

## 首版独立设置页真机抽测

- 小米 14（Android 16、HyperOS 3.0）使用原证书覆盖混淆候选包；设备内 APK 哈希与候选一致，应用 UID 保留，未卸载或清空数据。
- 断开状态下确认精简菜单可打开设置和服务器收藏、设置四组可见、点击卡片边缘可进入麦克风页面、离线试麦有禁用提示且点击不启动。系统返回键和应用栏返回按钮均正常；确认外观页可进入，未修改偏好。
- 本轮未连服、未开启试麦、未播放声音。连接中的菜单与页签保持由此前 API 35 离线回归覆盖，不计作真机连服结果。候选包、安装与抽测日志及截图位于忽略的 `build/settings-navigation/device/`。

## 仍需实测

- 用两台实体设备和受控服务器验证跨应用邀请、密码频道、频道拒绝后继续通话，以及邀请分享接收方的浏览器兼容性。新人试用目标按计划单独记录，不能用自动化结果替代。
- 补测有线／USB 和蓝牙实际收音；记录权限拒绝、门控开启、换设备、抢占、连接后正常 PTT 与对端实际听感。试麦所示输入不代表连接后必定采用同一路线。
- 在锁屏、关闭通知权限、前后台切换、恢复网络及物理拔耳机时检查通知入口和外放保护。
- 稳定版与候选的蜂窝／Wi-Fi 断音听感对照仍待真机执行；本轮不宣称修复断音。SRV、桌面身份、跨应用 PTT、Whisper 等待单独讨论。
