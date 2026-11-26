# 1.3.0 待发布候选 / Candidate status · 2026-11-25

GitHub 最新稳定版仍为 [1.2.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.2.0)
（2026-11-20，versionCode 16）。`codex/release-1.3.0` 候选含兼容新功能，因此使用 `1.3.0` 而不是热修版本号；Android `versionCode` 为 `17`。
These changes are not in the public stable APK. The candidate is not production-signed, tagged or published.

| 能力 / Feature | 待发布标准构建 / Standard candidate | 边界 / Limits |
| --- | --- | --- |
| 收藏 / Bookmarks | 收藏快捷连接、凭据隔离、显式保存离线频道 | 不自动刷新离线频道；不迁移跨包数据 |
| 频道文件和媒体 / Files and media | 频道文件、同服附件下载及按需头像／图标 | 只读，不支持上传、跨服附件和预取；由服务器决定权限 |
| 连接浮窗 / Floating PTT | 私有设置入口、通知召回、可选按 Home 显示 | 默认为手动；显示不录音，本机实验室仅 Debug |
| 桌面身份 / Desktop INI | 保持默认关闭；仅 Debug 或独立开发 QA 可预览 | 不将合成及隔离测试等同于官方桌面工作流验收 |
| 设置 / Settings | 紧凑首页、身份备份分区、统一开关和读数、字号适配 | 不改加密备份格式或系统权限策略 |
| 本地聊天历史 / Local chat history | 可选保存符合条件的新文本并离线浏览 | 默认关闭，不补存旧消息，不保存附件数据，不云同步 |
| 功能帮助 / Feature help | 「设置 → 帮助与关于 → 功能帮助」按需查阅 | 启动时不弹出，不代用户执行操作 |

证据入口：[小米 14 早期 QA 基线（早于第三轮修复和指引改版，非 1.3.0 验收）](../testing/PHONE_CANDIDATE_2026-11-25_ZH.md) ·
[界面复核](../testing/SETTINGS_UI_REVIEW_2026-11-25_ZH.md) ·
[只读文件互通](../testing/CHANNEL_FILES_PROBE_2026-11-24_ZH.md) ·
[浮窗证据](../testing/FLOATING_PTT_P0_2026-11-23_ZH.md) · [Changelog](../../CHANGELOG.md)。

## 本地软件验收 / Local verification

- 格式、编译、Android Lint、仓库隐私扫描与 workflow 静态检查通过。JVM 939 项通过、6 项显式环境探针跳过；Python 工具测试 34 项通过。
- API 35 / arm64 模拟器按 CI 的注解筛选运行应用 329 项、音频 38 项，均通过。API 36 / arm64 模拟器通过 52 项定向回归、3 项浮窗通知用例和 8 个身份进程终止边界。
- 标准 Release 和不经 JitPack 的固定 TS3j 源码回退 Release 均通过打包身份与 Opus 探针；握手宽度候选只通过独立定宽编码检查。
- 原始报告位于本地忽略目录 `build/release-checks/1.3.0/`；本地运行不代表 GitHub Actions 的 Linux / x86_64 作业已通过。
- 2026-11-26 隔离收口复验：API 35 / arm64 应用默认集增至 331 项，全部通过；另有 16 项定向测试和 3 项显式浮窗测试通过。重新验证了 519 项应用 JVM 单测、34 项 Python 工具测试、格式、Lint、Debug／Release 构建及标准 Release 的身份与 Opus 探针。证据见上述目录的 `followup-2026-11-26/`。

## 发版门禁 / Release gates

| 门禁 | 状态与验收条件 |
| --- | --- |
| GitHub Actions | 待 PR 实跑 Linux / x86_64；核对模拟器步骤耗时，以及同一 checkout 先构建 TS3j 源码回退包、再正常构建和验证默认 Release 的两个阶段。 |
| 合成覆盖升级 | API 35 / arm64，开发签名、`16 → 17`：Debug 升级用真实存储接口核对原身份/连接表单/收藏/麦克风偏好，并通过旧备份预检；混淆 Release 升级核对 UID、加密文件、原密钥和偏好文件。仅合成数据，不是正式签名验收。 |
| 正式工件与覆盖升级 | 公开 `v1.2.0` APK 的版本、哈希与签名证书已和 `BUILDINFO.json` 核对；证书不同于开发签名。正式覆盖升级待验收：用同一正式签名安装最终候选，记录源码 SHA、APK SHA-256 与签名比对，验证身份、收藏及设置保留。 |
| 实服、双端和切网 | 待验收；弱网和高抖动下重复频道订阅/切换，记录 4 秒预算下未确认与误断连；覆盖 Wi-Fi ↔ 蜂窝、快速重连、双端听感和浮窗 PTT/通知召回、安全止录与静音。 |
| 物理蓝牙和厂商 ROM | 待验收；记录手机、系统与耳机组合，覆盖蓝牙冷启动、100 ms 播放停滞判定与蓝牙宽限、断连安全静音、锁屏至少 30 分钟、真机强杀后诊断记录可读。 |
| 系统备份与换机 | 待验收；实测云备份/恢复和设备到设备迁移，确认加密身份文件未迁移；单独验证缺少密钥时的可理解提示与可恢复路径。 |
| 紧凑屏操作 | 待验收；在窄屏且软键盘打开时，从搜索结果进入加密频道，输入密码后直接点「加入」，确认按钮可见、可点。 |
| TS3j 依赖 | `1.3.0` 保持 `ts3mobile-1`。握手宽度补丁仅用于显式本地候选；定宽编码回归不代表真实握手通过。提升依赖需单独评审、实服互通和新标签。 |

上述实际环境项目不得由模拟器结果代签。未完成前不打发布标签或公开安装包；核心会话与服务拆分按 [架构计划](../../ARCHITECTURE.md) 留到发版后。
