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
| 已撤回的 1.0.0 / versionCode 13 | 重新下载 code 14 覆盖安装；本次 API 35 / 16 KiB 模拟器保留 UID、合成私有文件/偏好与 Keystore 密文通过；不卸载或清数据 |
| GitHub 0.11.1-m10 / versionCode 12 | 证书相同；历史 code 12 → 13 模拟器保留数据升级通过，未单独执行本次直接 code 12 → 14 或真机原有数据复验 |
| 其他 GitHub 0.11.x / 同一正式应用 ID | 先核对旧包证书与版本号，不从单个旧版的结果推导全部兼容 |
| TS3 Mobile UI / F1a 等旁装调试应用 | 应用 ID、身份与存储独立，正式包并非该应用的升级，数据不会自动迁移 |
| 很早的 v0.1 或第三方重签包 | 签名兼容性未知；不要直接卸载，先核对来源与证书 |
| 比将要安装的版本更新的应用 | Android 通常拒绝降级，尤其 versionCode 更低时；优先安装后续修复版本 |

**不要为解决安装冲突直接卸载或清空存储。** 本地身份、加密收藏、连接表单与其他本机设置会丢失。
公开1.0.0/code14没有身份导入导出或收藏迁移；Android备份／设备迁移也被限制。
本分支C2改进候选已支持自有加密身份备份和本地回退，尚未公开发布，见下方候选说明。
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
公开code14没有内置身份备份；候选的本地回退也不能抵抗卸载／密钥丢失。提交问题时提供版本和安装错误类型，不上传私钥、身份或私人服务器信息。

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
(code 12) and 1.0.0 certificates match. The historical code 12 → 13 and current
code 13 → 14 API 35 emulator upgrades preserved the UID, synthetic private
files/preferences and Keystore ciphertext. A direct code 12 → 14 check and real-device data
retention has not been recorded for the final APK. Other old versions still need
their own certificate/version checks. The inherited certificate subject is Android
Debug; it is retained for update continuity, not presented as a new production
certificate. Separate TS3 Mobile UI/F1a test packages have independent identities
and storage; they do not migrate into the release. Old v0.1/re-signed packages
have unknown compatibility. Android normally rejects lower versionCodes.

Do not resolve a signature conflict by immediately uninstalling or clearing data.
That removes local identity, encrypted bookmarks/form memory and settings; a new
identity may lose server-granted permissions. Published code 14 has no identity import/export or bookmark transfer, and Android backup/transfer is
restricted. The unshipped C2 candidate adds encrypted identity backup/restore and
local rollback; see the candidate scope below. A certificate change needs its own migration announcement.

Disconnect normally before updates. Keep the old APK for certificate comparison,
but do not assume in-place downgrades work. Prefer a higher-versionCode hotfix
signed compatibly. Subscribe with GitHub Watch → Custom → Releases; the app has no
automatic update checker. Report installation failures through [support](../SUPPORT.md)
without posting credentials or identity material.


## 身份备份改进候选 / Identity backup candidate

本分支C2候选从“更多选项 → 身份与备份”提供自有`.ts3mbak`口令加密导出／导入和确认后本地回退。
公开1.0.0/code14没有这些入口。当前候选为草稿PR，版本暂仍code14，仅在标准开发测试证书下验证，
没有用公开发布私钥签名、合并或发行。不要根据相同版本名／code就推断APK包含候选功能，
按具体源码／APK SHA及[身份候选验收](testing/IMPROVEMENT_R3_2026-11-07_ZH.md)判断。

换安装前先用候选完成导出、关闭、回读认证；口令单独保管。目标安装选择文件后先校验，
核对当前／新UID、offset、安全等级及权限影响，再确认替换，之后需手动连接。
本地回退只依赖本安装中最后有效旧身份和同一Keystore密钥，不能代替外部备份。
忘记口令无法恢复。签名冲突、物理坏文件或不可用现有密钥应保留原安装，用备份在另一全新安装恢复，
不要为通过升级测试自动卸载／清空旧数据。本轮只验证离线／模拟器和测试签名，不代表公开证书／实体设备升级已验收。

The unshipped C2 source candidate provides passphrase-encrypted `.ts3mbak` export,
confirmed import and local rollback from **More options → Identity and backups**.
Published 1.0.0/code 14 does not include these controls. Draft candidates still use
code 14 and were tested only with the standard development certificate, not the
published release private key. Identify exact source/APK fingerprints, not just
the version label; see [R3 acceptance](testing/IMPROVEMENT_R3_2026-11-07_ZH.md).

Export and verify the reopened file before moving installations; keep its passphrase
separately. On the target, review both identities and permission impact, confirm,
then connect manually. Local rollback needs this installation’s key and is not an
external backup. Preserve the original installation on signature conflicts or
unreadable files/keys and recover the external backup on another fresh installation.
No production-signing or physical-device upgrade result is implied by emulator tests.
