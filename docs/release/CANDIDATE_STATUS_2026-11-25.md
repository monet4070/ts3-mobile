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

证据入口：[小米 14 验收记录](../testing/PHONE_CANDIDATE_2026-11-25_ZH.md) ·
[界面复核](../testing/SETTINGS_UI_REVIEW_2026-11-25_ZH.md) ·
[只读文件互通](../testing/CHANNEL_FILES_PROBE_2026-11-24_ZH.md) ·
[浮窗证据](../testing/FLOATING_PTT_P0_2026-11-23_ZH.md) · [Changelog](../../CHANGELOG.md)。

发布前须核对确切工件、递增版本、原正式签名与覆盖升级。双端听感、真机通知召回、蓝牙切换、长时锁屏和真实切网依实测记录，不以自动化通过代替。
