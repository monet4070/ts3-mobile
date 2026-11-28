# Documentation Images / 文档截图

当前文档默认使用 **1.3.0（versionCode 17）** 签名发布包截取的最新中英文界面图；1.2.0 与 1.1.0 截图集作为历史界面记录保留。
Current documentation uses the **1.3.0 (versionCode 17)** localized screenshot sets captured directly from the signed release APK; 1.2.0 and 1.1.0 sets remain archived for historical reference.

## 1.3.0 Release UI / 1.3.0 正式版界面

英文文档使用 [`1.3.0/en/`](1.3.0/en/)，简体中文文档使用 [`1.3.0/zh-CN/`](1.3.0/zh-CN/)。两套截图各包含 11 张裁剪去除状态栏与系统导航栏的最新界面图：

- `settings.png` — 独立设置首页（语音、通知、个性化与身份分组）
- `audio-devices.png` — 输入与输出设备（麦克风、播放设备与实际使用的音频设备）
- `voice-playback.png` — 语音播放（稳定优先默认档位、平衡档位与实际音频设备）
- `floating-ptt.png` — 浮窗 PTT 设置（标准／极简预览卡片、退后台自动显示、会话与权限）
- `microphone-test.png` — 麦克风设置中的本机试麦确认对话框
- `local-chat-history.png` — 本地聊天历史（默认关闭与 7 天 / 500 条 / 512 KiB 配额说明）
- `bookmarks-and-editor.png` — 服务器收藏列表与编辑收藏对话框（含展开后的“更多设置”与“浏览已保存频道”，使用合成示例 `Team Voice` / `voice.example.com:9987` 与 `Squad Room` / `squad.example.com:9987`）
- `appearance.png` — 外观主题预览卡片（浅色、深色、跟随系统）
- `feature-help.png` — 按需功能帮助主题列表
- `offline-diagnostics.png` — 全屏诊断页（标记断音、复制／保存文件、本地音频记录与连接诊断）
- `updates.png` — 检查更新页（显示已安装版本 `1.3.0 (17)` 与测试版渠道开关）

截图于 2026-11-28 采自运行签名版 `ts3-mobile-1.3.0-release.apk`（`versionCode 17`）的 Android 15（API 35）独立模拟器，未连接任何 TeamSpeak 服务器、未启动麦克风采集，不包含真实身份、服务器或用户数据。截图仅用于界面说明，不替代[1.3.0 验收状态与发版门禁](../release/CANDIDATE_STATUS_2026-11-25.md)。

Use [`1.3.0/en/`](1.3.0/en/) in English docs and [`1.3.0/zh-CN/`](1.3.0/zh-CN/) in Simplified Chinese docs. Captured on 2026-11-28 from the signed `ts3-mobile-1.3.0-release.apk` (`versionCode 17`) on a clean Android 15 (API 35) emulator with no server connected, no audio captured, and synthetic bookmark entries (`voice.example.com`, `squad.example.com`). Screenshots illustrate the UI only and do not extend [1.3.0 verification status](../release/CANDIDATE_STATUS_2026-11-25.md).

## 1.2.0 Release UI / 1.2.0 历史界面

[`1.2.0/en/`](1.2.0/en/) 与 [`1.2.0/zh-CN/`](1.2.0/zh-CN/) 保留自 1.2.0（versionCode 16）在小米 14（Android 16）上截取的设置、语音播放与本机试麦界面图。

## 1.1.0 Release UI / 1.1.0 历史界面

[`1.1.0/en/`](1.1.0/en/) 与 [`1.1.0/zh-CN/`](1.1.0/zh-CN/) 保留自 2026-11-20 的 1.1.0（`4b0084e`，versionCode 15）界面图；签名包验收见 [1.1.0 发布验收](../release/1.1.0/CHECKLIST.md)。

## Historical Illustrations / 历史图示

[`interface-overview.png`](interface-overview.png) 和 [`bookmarks-and-editor.png`](bookmarks-and-editor.png) 为 1.0.0 时期保留的历史图示；`.mmd` 与对应架构 PNG 属于[历史双语架构快照](../ARCHITECTURE_BILINGUAL.md)。

Application UI compositions are first-party project material under the repository license; Lucide-derived glyphs retain their upstream license (see [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)).
