# 安装与升级 / Installation and upgrades

适用于 GitHub 分发的 APK。当前稳定版为
[1.0.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.0.0)（2026-11-05 修复重发，versionCode 14）。
实际包名、版本、签名与校验值以对应 Release 的资产为准；验证范围见[发布资料](release/README.md)。

## 下载与校验

1. 打开 [GitHub Releases](https://github.com/monet4070/ts3-mobile/releases)，检查版本与预发布标识。
2. 在 Assets 中下载 `ts3-mobile-<version>-release.apk` 和同一 Release 的 `SHA256SUMS.txt`。
   GitHub 自动附带的 Source code ZIP/TAR 是源码，不是安装包。
3. 比对 SHA-256。只校验你要安装的文件；不要使用另一个版本的校验表。
4. 从你信任的浏览器/文件管理器安装，按需允许该来源安装应用。

macOS：

```sh
shasum -a 256 ts3-mobile-1.0.0-release.apk
```

Linux：

```sh
sha256sum ts3-mobile-1.0.0-release.apk
```

Windows PowerShell：

```powershell
Get-FileHash .\ts3-mobile-1.0.0-release.apk -Algorithm SHA256
```

文件摘要标识某个 APK 的字节内容，证书指纹标识签名证书。1.0.0 同时提供 `BUILDINFO.json`，
记录实际版本、构建源码提交、发布提交与证书 SHA-256；较早 Release 不一定提供这个文件。

本次 code 14 的 APK 摘要以重新下载的 `SHA256SUMS.txt` 和 `BUILDINFO.json` 为准。
原 11 月 4 日 code 13 已撤回；不要使用其 APK、旧校验表或旧缓存源码验证修复包。
版本名仍为 1.0.0，内部 versionCode 已提高，使用相同公开证书覆盖升级，不需要卸载。

## 系统要求与首次安装

- Android 8.0（API 26）及以上；通用 APK 包含四种 ABI，具体实测范围见[兼容性](COMPATIBILITY.md)。
- 有可访问目标 TeamSpeak 3 服务器语音端口的网络，不需要项目账号或 ServerQuery 凭据。
- 只收听无需开启麦克风；发送语音需要录音权限。通知权限用于连接状态与戳一下后台提醒。
- 应用不会自动连接。填写服务器地址、语音端口、昵称和必要密码后手动连接。

## 从已有版本升级

Android 覆盖安装需要相同的应用 ID、兼容签名和允许升级的版本号。
正式应用 ID 为 `io.github.ts3mobile.app`。只有确认签名链兼容，才能承诺保留数据升级。

| 现有安装 | 处理方式 |
| --- | --- |
| 已撤回的 1.0.0 / versionCode 13 | 重新下载 code 14 APK；包名和公开证书不变，覆盖安装，不卸载或清数据 |
| GitHub 0.11.1-m10 / versionCode 12 | 与 1.0.0 证书相同；API 35 模拟器保留 UID、合成私有文件/偏好及 Keystore 密文的升级通过，真机原有数据复验尚未记录 |
| 其他 GitHub 0.11.x / 同一正式应用 ID | 先核对旧包证书与版本号，不从单个旧版的结果推导全部兼容 |
| TS3 Mobile UI / F1a 等旁装调试应用 | 应用 ID、身份与存储独立，正式包并非该应用的升级，数据不会自动迁移 |
| 很早的 v0.1 或第三方重签包 | 签名兼容性未知；不要直接卸载，先核对来源与证书 |
| 比将要安装的版本更新的应用 | Android 通常拒绝降级，尤其 versionCode 更低时；优先安装后续修复版本 |

**不要为解决安装冲突直接卸载或清空存储。** 本地身份、加密收藏、连接表单与其他本机设置会丢失。
当前应用没有身份导入导出、收藏迁移或应用内备份恢复工具；Android 备份/设备迁移也被限制。
旧身份关联的服务器权限可能无法由新身份继承。

1.0.0 沿用旧公开证书，SHA-256 为：

```text
e239102412f17ddd1909bd323b3825831b153fec4602ec00abb359da7d8be592
```

该历史证书主题为 Android Debug；沿用它以保持升级身份，不将其宣传为新建立的生产证书。
换签名时维护者必须单独公布迁移方案；换包名的测试安装不是保留数据的升级证据。

## 更新、回退与问题报告

应用不会自动检查更新。GitHub **Watch → Custom → Releases** 可订阅发布通知。
更新前正常断开服务器；保留旧安装包有助于核对证书，但不保证能原地降级。
遇到严重回归，优先等待兼容签名、versionCode 更高的修复包；不建议卸载后回退，
因为没有内置身份备份。提交问题时提供版本和安装错误类型，不上传私钥、身份或私人服务器信息。

## English

Download an APK and `SHA256SUMS.txt` from the same
[GitHub Release](https://github.com/monet4070/ts3-mobile/releases). Source archives
are not installers. Compare the digest using the platform commands above, then
allow installation from the trusted browser/file manager you used. Android 8.0+
is required. See [compatibility](COMPATIBILITY.md) for tested coverage.

Stable 1.0.0 (reissued 2026-11-05, versionCode 14) provides `ts3-mobile-1.0.0-release.apk`,
`SHA256SUMS.txt` and `BUILDINFO.json`. The latter records the build source commit,
release commit, toolchain and certificate. The actual APK SHA-256 and certificate
SHA-256 are recorded in the newly downloaded assets. Code 13 was withdrawn;
download the replacement and checksums again, then update in place without uninstalling.
An APK digest identifies bytes; a certificate fingerprint
identifies the certificate. They are not interchangeable.

An in-place update needs the same application ID (`io.github.ts3mobile.app`), a
compatible signing lineage and an acceptable versionCode. The public 0.11.1-m10
(code 12) and 1.0.0 certificates match. An API 35 emulator upgrade preserved the UID,
synthetic private files/preferences and Keystore ciphertext; real-device data
retention has not been recorded for the final APK. Other old versions still need
their own certificate/version checks. The inherited certificate subject is Android
Debug; it is retained for update continuity, not presented as a new production
certificate. Separate TS3 Mobile UI/F1a test packages have independent identities
and storage; they do not migrate into the release. Old v0.1/re-signed packages
have unknown compatibility. Android normally rejects lower versionCodes.

Do not resolve a signature conflict by immediately uninstalling or clearing data.
That removes local identity, encrypted bookmarks/form memory and settings; a new
identity may lose server-granted permissions. There is no identity import/export,
bookmark transfer or in-app backup/restore tool, and Android backup/transfer is
restricted. A certificate change needs its own migration announcement.

Disconnect normally before updates. Keep the old APK for certificate comparison,
but do not assume in-place downgrades work. Prefer a higher-versionCode hotfix
signed compatibly. Subscribe with GitHub Watch → Custom → Releases; the app has no
automatic update checker. Report installation failures through [support](../SUPPORT.md)
without posting credentials or identity material.
