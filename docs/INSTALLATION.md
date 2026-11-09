# 安装与升级 / Installation and upgrades

当前稳定版：[1.1.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.1.0)，
2026-11-09，versionCode **15**。正式包名为 `io.github.ts3mobile.app`，沿用原公开签名。
实际包名、版本、证书与摘要以同一 Release 的 `BUILDINFO.json` 为准，验证范围见
[发布验收](release/1.1.0/CHECKLIST.md)和[兼容性](COMPATIBILITY.md)。

## 下载与校验

1. 打开 [GitHub Releases](https://github.com/monet4070/ts3-mobile/releases)，检查正式／测试版标识。
2. 下载 `ts3-mobile-1.1.0-release.apk`、`SHA256SUMS.txt` 和 `BUILDINFO.json`。
   GitHub 自动附带的 Source code ZIP/TAR 是源码，不是安装包。
3. 用同一 Release 的校验表比对 APK SHA-256，再由可信浏览器或文件管理器安装。
4. 安装前正常断开服务器，保留现有应用，按需允许该下载来源安装。

macOS：

```sh
shasum -a 256 ts3-mobile-1.1.0-release.apk
```

Linux：

```sh
sha256sum ts3-mobile-1.1.0-release.apk
```

Windows PowerShell：

```powershell
Get-FileHash .\ts3-mobile-1.1.0-release.apk -Algorithm SHA256
```

文件摘要标识 APK 字节，证书摘要标识签名。`BUILDINFO.json` 同时记录实际构建源码、
发布提交、版本、ABI 和证书。不要拿其他版本或旧缓存文件的校验表验证新包。

## 系统要求

- Android 8.0 / API 26+；通用 APK 含 arm64-v8a、armeabi-v7a、x86、x86_64。
- 可访问目标 TeamSpeak 3 语音端口的网络；不需要项目账号或 ServerQuery 凭据。
- 收听不要求开启麦克风；发送需要录音权限。通知权限控制连接和消息提醒。
- 首次启动不自动连接，不自动打开麦克风；设备覆盖见[兼容性](COMPATIBILITY.md)。

## 保留原安装升级

Android 覆盖安装需要相同包名、兼容签名和允许升级的 versionCode。

| 现有安装 | 处理方式 |
| --- | --- |
| 1.0.0 / code 14，原公开签名 | 使用 1.1.0 / code 15 覆盖；最终包实测范围见发布验收 |
| 旧 code 13 或 0.11.1-m10 / code 12 | 公共证书相同，先核对包来源与版本；不能把单一升级测试扩大为全部历史设备证明 |
| 本地同签名 code 14 候选 | code 15 可升级；仅看 1.0.0 名字无法区分旧稳定版与本地候选 |
| TS3 Mobile UI / F1a 等旁装包 | 包名、身份和存储独立，不能自动迁入正式包 |
| 第三方重签包或很早的 v0.1 | 先核对来源和证书，不通过卸载解决未知冲突 |
| 更高 versionCode 的应用 | Android 通常拒绝降级，应等待更高 code 的修复包 |

**不要为解决安装冲突直接卸载或清除数据。** 本机身份、收藏、表单和设置会丢失。
1.1.0 可从“身份与备份”主动导出自有 `.ts3mbak` 加密身份文件，必须回读验证并单独保管口令；
备份不包含收藏或连接表单，不兼容桌面身份格式。本地回退依赖原安装及密钥，不能代替外部备份。
旧 1.0.0 / code 14 没有这套导出入口。Android 备份/换机不能替代应用内已验证备份。

遇到包名、签名或版本冲突，保留原安装，附脱敏错误和包来源向维护者反馈。
不要公开密钥、身份文件、口令或服务器信息。

## 后续检查更新

1.1.0 起可在“更多选项 → 检查更新”查看正式版，可选择包含测试版并保存偏好。
只在打开页面、切换渠道或重新检查时联网；APK 由浏览器下载，系统负责安装，应用不后台轮询或自动安装。
关闭固定在顶部，更新说明支持 Markdown；完整正文的收起按钮不会随说明滚走。
旧版本需要先手动下载一次 1.1.0，之后才能使用该入口。也可通过 GitHub Watch → Custom → Releases 订阅。

## English

Stable **1.1.0 / code 15** (2026-11-09) retains the official package and public signing
certificate. Download the signed APK, `SHA256SUMS.txt` and `BUILDINFO.json` from the same release.
Compare the APK hash, disconnect from the server, then update in place. Keep the existing app;
do not uninstall or clear data to resolve an installation conflict. See
[acceptance](release/1.1.0/CHECKLIST.md) for exact tested upgrade paths.

Android 8.0+ is required. A packaged ABI does not establish physical-device coverage.
Side-loaded test packages and differently signed builds do not share the official app's data.
The new encrypted `.ts3mbak` identity backup excludes bookmarks/forms and requires a separately
kept passphrase; it is not a desktop-client format. Local rollback is not an external backup.
Older code 14 has no identity export control.

From 1.1.0, More options → Check for updates offers stable releases and an optional remembered
prerelease channel. The browser handles downloads; checks are user initiated and installation is
never automatic. Earlier builds need one manual download to gain this feature.
