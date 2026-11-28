# 1.3.0 验证记录 / Verification record · 2026-11-28

正式版见 [v1.3.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.3.0)（versionCode 17），延续 [v1.2.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.2.0)（versionCode 16）的公开签名。正式 APK 通过签名、四 ABI、对齐、Opus 和身份探针；API 36 隔离模拟器从公开 1.2.0 就地升级，保留应用 UID 和合成测试文件，两版均能冷启动。未用最终包完整复验真实用户数据。

The signed release artifact passed certificate, four-ABI, alignment, Opus, and identity checks. An isolated API 36 emulator upgraded from public 1.2.0 in place, preserving the app UID and a synthetic file, and both builds cold-launched. This is not full real-user-data migration verification of the final APK.

| 能力 / Feature | 待发布标准构建 / Standard candidate | 边界 / Limits |
| --- | --- | --- |
| 收藏 / Bookmarks | 收藏快捷连接、凭据隔离、显式保存离线频道 | 不自动刷新离线频道；不迁移跨包数据 |
| 频道文件和媒体 / Files and media | 频道文件、同服附件下载及按需头像／图标 | 只读，单个文件限 20 MiB；不支持上传、跨服附件和自动下载；由服务器决定权限 |
| 连接浮窗 / Floating PTT | 标准／极简、紧凑发言栏、通知召回、可选按 Home 显示 | 默认为手动；显示不录音，本机实验室仅 Debug |
| 切网保护 / Network handoff | 短暂切网时先探测并尝试保留原会话 | 语音可能短暂停顿；保留失败则走断线恢复 |
| 桌面身份 / Desktop INI | 保持默认关闭；仅 Debug 或独立开发 QA 可预览 | 不将合成及隔离测试等同于官方桌面工作流验收 |
| 设置 / Settings | 紧凑首页、身份备份分区、统一开关和读数、字号适配 | 不改加密备份格式或系统权限策略 |
| 本地聊天历史 / Local chat history | 可选保存符合条件的新文本并离线浏览 | 默认关闭，不补存旧消息，不保存附件数据，不云同步 |
| 功能帮助 / Feature help | 「设置 → 帮助与关于 → 功能帮助」按需查阅 | 启动时不弹出，不代用户执行操作 |

证据入口：[小米 14 早期 QA 基线（早于第三轮修复和指引改版，非 1.3.0 验收）](../testing/PHONE_CANDIDATE_2026-11-25_ZH.md) ·
[界面复核](../testing/SETTINGS_UI_REVIEW_2026-11-25_ZH.md) ·
[只读文件互通](../testing/CHANNEL_FILES_PROBE_2026-11-24_ZH.md) ·
[浮窗证据](../testing/FLOATING_PTT_P0_2026-11-23_ZH.md) · [Changelog](../../CHANGELOG.md)。

## 当前验收 / Current verification

- **GitHub CI**：PR #31 的候选源提交 `e348a0c` 在 [Linux / x86_64 Android CI](https://github.com/monet4070/ts3-mobile/actions/runs/45819101997) 通过。包括固定 TS3j 源码回退构建、默认构建与 API 35 x86_64 模拟器回归。后续切网、浮窗和设置修改不在该提交内，仍需最终源码的 CI。
- **独立本地复验**：同一源提交 JVM **979 通过、6 跳过**，Python **34 通过**；格式、Lint（无错误）、隐私扫描以及 Debug/Release 构建通过。API 35 / arm64 无声模拟器应用 **331/331**、音频 **39/39**；包含 DTX、FEC 和独立过期包淘汰。标准未签名 Release 的身份和 Opus 探针通过。
- **文件下载并行探针**：两次未通过，均卡在目录加载，未触达下载；随后使用单模拟器和独立回环夹具复跑，下载与静音语音并行、保存及取消清理通过。未用这一结果代替最终包的真机文件验收。
- **11 月 28 日原签名基线 `e348a0c`（非最终工件）**：小米 14 上验证了播放中蓝牙断开触发安全静音、未观察到扬声器活动音轨、耳机重连不自动解除静音；浮窗按住发话由对端确认，松手后麦克风恢复空闲。同源隔离包的 39 项真机音频用例通过。API 36 模拟器用公开 1.2.0 到该基线的原签名覆盖（`16 → 17`）保留了合成身份、连接表单、收藏、外观和应用 UID。独立报告留在私有目录 `build/device-qa-2026-11-28-e348a0c/`，更早结果见[验收交接](HANDOFF_1.3.0_2026-11-26_ZH.md)。
- **后续隔离手机包（非最终工件）**：维护者确认最新浮窗 PTT 检查通过，并分别报告后台音频通过、切网后语音短暂停顿再恢复。报告不一定来自同一次构建，不是最终签名 APK 的结果；也未记录双向切网后仍为同一个服务端客户端 ID。判据见[切网复验](../testing/NETWORK_HANDOFF_2026-11-28_ZH.md#原会话保留不是自动重连)。
- **自动浮窗修复前的工作树预检**：JVM **993 通过、6 跳过**，Python **34 通过**；格式、Android Lint、隐私扫描及 Debug / Release 构建通过。未签名 APK 核对为 `io.github.ts3mobile.app`、`1.3.0`、code `17`，仅供本地预检，不作为发布工件。报告位于 `build/release-checks/1.3.0/preflight-2026-11-28/`。
- **自动浮窗后续回归**：旧包在 API 35 模拟器能通过 Home 触发，但主界面按返回离开未显示。修复后浮窗、通知和设置定向用例 **11 项通过**；验证 Home、主界面返回、应用内返回不弹窗及显示时不录音。小米 14 已保数据覆盖独立 PTT QA 包并核对摘要及自动显示开关，尚待维护者在手机上复验两种离开手势，不作为最终签名包验收。

## 早期软件验收 / Earlier verification

- 格式、编译、Android Lint、仓库隐私扫描与 workflow 静态检查通过。JVM 939 项通过、6 项显式环境探针跳过；Python 工具测试 34 项通过。
- API 35 / arm64 模拟器按 CI 的注解筛选运行应用 329 项、音频 38 项，均通过。API 36 / arm64 模拟器通过 52 项定向回归、3 项浮窗通知用例和 8 个身份进程终止边界。
- 标准 Release 和不经 JitPack 的固定 TS3j 源码回退 Release 均通过打包身份与 Opus 探针；握手宽度候选只通过独立定宽编码检查。
- 早期本地报告位于忽略目录 `build/release-checks/1.3.0/`；这些结果不与上面的当前测试数相加。
- 2026-11-26 隔离收口复验：API 35 / arm64 应用默认集增至 331 项，全部通过；另有 16 项定向测试和 3 项显式浮窗测试通过。重新验证了 519 项应用 JVM 单测、34 项 Python 工具测试、格式、Lint、Debug／Release 构建及标准 Release 的身份与 Opus 探针。证据见上述目录的 `followup-2026-11-26/`。

## 发版门禁 / Release gates

| 门禁 | 状态与验收条件 |
| --- | --- |
| GitHub Actions | PR 源提交 `e348a0c` 已通过；发布时核对最终源码提交对应的 CI 结果，不把该运行当作以后提交的证明。 |
| 合成覆盖升级 | API 35 / arm64，开发签名、`16 → 17`：Debug 升级用真实存储接口核对原身份/连接表单/收藏/麦克风偏好，并通过旧备份预检；混淆 Release 升级核对 UID、加密文件、原密钥和偏好文件。仅合成数据，不是正式签名验收。 |
| 正式工件与覆盖升级 | 公开 `v1.2.0` APK 的版本、哈希与签名证书已核对。最终包使用同一正式签名，已验证 `16 → 17` 保留应用 UID 和合成测试文件；真实身份、收藏和设置的最终包全量复验仍未完成。发布资产的 `BUILDINFO.json` 与 `SHA256SUMS.txt` 记录对应源码和 APK 摘要。 |
| 实服、双端和切网 | 最终包待验弱网、频道订阅/切换、Wi-Fi ↔ 蜂窝（双向保留同一个服务端客户端 ID）、快速重连、双端语音、真机文件保存、浮窗与通知控制。 |
| 物理蓝牙和厂商 ROM | 原签名基线通过播放中断开防外放与重连保持静音。最终包需复验，并检查蓝牙冷启动、锁屏至少 30 分钟和真机进程终止后的诊断读取。 |
| 系统备份与换机 | 待验收；实测云备份/恢复和设备到设备迁移，确认加密身份文件未迁移；单独验证缺少密钥时的可理解提示与可恢复路径。 |
| 紧凑屏操作 | 待验收；在窄屏且软键盘打开时，从搜索结果进入加密频道，输入密码后直接点「加入」，确认按钮可见、可点。 |
| TS3j 依赖 | `1.3.0` 保持 `ts3mobile-1`。握手宽度补丁仅用于显式本地候选；定宽编码回归不代表真实握手通过。提升依赖需单独评审、实服互通和新标签。 |

维护者确认发布已验收的版本。未完成的物理设备、真实数据与实服项目继续保留为复验项，不以模拟器或早期候选结果代签；核心会话与服务拆分按 [架构计划](../../ARCHITECTURE.md) 留到发版后。
