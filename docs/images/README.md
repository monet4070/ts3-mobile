# Documentation images / 文档截图

## 1.2.0 release UI / 1.2.0 正式版界面

The current README tour uses [`1.2.0/en/`](1.2.0/en/) and
[`1.2.0/zh-CN/`](1.2.0/zh-CN/). Both show the main Settings groups, Voice playback
profiles and the local microphone test dialog. The screenshots are cropped to the
relevant controls without the system status bar.

Both sets were captured on a Xiaomi 14 running Android 16 with TS3 Mobile 1.2.0
(versionCode 16). No server was contacted and microphone capture was not started.
Screens with saved servers, nicknames or identities were not included.

当前 README 导览使用 [`1.2.0/en/`](1.2.0/en/) 和 [`1.2.0/zh-CN/`](1.2.0/zh-CN/)。
两套截图均只保留设置主要分组、语音播放档位及本机试麦对话框，已裁去系统状态栏。截图来自运行 1.2.0（versionCode 16）的
小米 14（Android 16）；未连接服务器、未开启采集，也不包含已保存的服务器、昵称或身份页面。

## 1.1.0 release UI / 1.1.0 正式版界面

Use [`1.1.0/en/`](1.1.0/en/) in English documentation and
[`1.1.0/zh-CN/`](1.1.0/zh-CN/) in Simplified Chinese documentation.
Each set shows the connection form, an example channel session, the More options
menu, bookmarks and editor, the update check, diagnostics and appearance.

英文文档使用 [`1.1.0/en/`](1.1.0/en/)，简体中文文档使用
[`1.1.0/zh-CN/`](1.1.0/zh-CN/)。两套截图均包括连接页、示例频道、更多选项、收藏与编辑、检查更新、诊断及外观。

Captured on 2026-11-20 from a disposable preview build of the published 1.1.0
source (`4b0084e`, versionCode 15). Connection and appearance are Xiaomi 14 /
Android 16 captures. The other panels use offline Compose fixtures on an Android
15 emulator, including a simulated up-to-date result and sample diagnostic counts.
No TeamSpeak server was contacted. Example endpoints, names and session data are
synthetic. Images were cropped, scaled and arranged without redrawing controls.
These screenshots illustrate the UI; see [release acceptance](../release/1.1.0/CHECKLIST.md)
for evidence about the signed APK.

截图于 2026-11-20 采集，使用与 1.1.0 发布包相同的源码（`4b0084e`，versionCode 15）构建隔离预览包。
连接页与外观来自小米 14 / Android 16 实机，其余来自 Android 15 模拟器的离线合成场景。
未读取正式应用的用户数据，也未连接真实 TeamSpeak 服务器。截图不替代[签名包发布验收](../release/1.1.0/CHECKLIST.md)。

## Historical illustrations / 历史图示

[`interface-overview.png`](interface-overview.png) and
[`bookmarks-and-editor.png`](bookmarks-and-editor.png) are the November 2026
1.0.0-era illustrations retained for historical records. Use the versioned
sets above for current guides. The `.mmd` diagrams also belong to the
[historical architecture snapshots](../ARCHITECTURE_BILINGUAL.md).

The application UI/screenshot composition is first-party project material under
the repository license. Lucide-derived glyphs retain their upstream license;
see [third-party notices](../../THIRD_PARTY_NOTICES.md).
