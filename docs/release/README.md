# 发布资料 / Releases

**当前稳定版：[TS3 Mobile 1.1.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.1.0)** ·
2026-11-09 · versionCode **15** · tag `v1.1.0`。

- [1.1.0 中文说明](1.1.0/RELEASE_NOTES_ZH.md) / [English notes](1.1.0/RELEASE_NOTES.md)
- [1.1.0 发布验收、签名与升级范围](1.1.0/CHECKLIST.md)
- [安装与升级](../INSTALLATION.md)、[兼容性](../COMPATIBILITY.md)、[使用指南](../USER_GUIDE_ZH.md)

每个正式版和测试版提供对应的 APK、`SHA256SUMS.txt` 和 `BUILDINFO.json`，
保持递增 versionCode、实际源码绑定和原公开签名。旧版本资料按日期保留，不代表新版实测。

---

# 1.0.0 历史发布资料 / Historical first stable release

**TS3 Mobile 1.0.0** · tag `v1.0.0` · versionCode **14** · **2026-11-05 音频修复重发**。
[公开 Release 与签名 APK](https://github.com/monet4070/ts3-mobile/releases/tag/v1.0.0)。

原 code 13 已撤回，维护者明确要求继续使用同一标签重发。当前检查以
[重发记录](1.0.0/REISSUE_2026-11-05.md)为准；下述首次发布证据保留历史边界。
首次发布的原密钥签名与旧公开证书比对通过，原 APK 模拟器保留数据升级通过。
小米 14 / Android 16 的昨日 QA 包功能实测确认继续保留；最终包蓝牙真机与真实数据升级、
第二设备等未执行范围随 [ADR-0021](../decisions/0021-stable-release-acceptance.md)公开披露。

## 文件入口

| 文件 | 用途 |
| --- | --- |
| [音频修复重发](1.0.0/REISSUE_2026-11-05.md) | code 14、重用标签授权、修复包门禁及实际发布状态 |
| [中文 Release 正文](1.0.0/RELEASE_NOTES_ZH.md) | 中文功能、安装、签名、验证与使用限制 |
| [English release body](1.0.0/RELEASE_NOTES.md) | 同一版本英文说明，与中文合并在同一 GitHub Release |
| [发布清单与验收表](1.0.0/CHECKLIST.md) | 打标前冻结的源码、签名、自动化及实际覆盖记录 |
| [发布验收决定](../decisions/0021-stable-release-acceptance.md) | 维护者公开指示、本次验收范围与保留缺口 |
| [打包与上线手册](1.0.0/PUBLISHING.md) | 版本冻结、签名、校验、草稿和正式公开步骤 |
| [历史未签名准备](1.0.0/PREPARATION_2026-11-04.md) | 密钥提供前的准备与昨日 QA 工件定位 |
| [异机签名交接](1.0.0/SIGNING_HANDOFF.md) | 已完成签名前的交接流程参考；未签名包不公开分发 |
| [安装升级说明](../INSTALLATION.md) | 下载校验、证书兼容、旁装包与身份保留 |
| [用户资料](../USER_GUIDE_ZH.md) | 中英文使用指南及常见问题入口 |
| [兼容性与验证范围](../COMPATIBILITY.md) | 自动化、维护者实测与未执行项目的边界 |

## 发布资产与来源

开发候选的应用内检查依赖同一 Release 的 `BUILDINFO.json`（schemaVersion 1），
其中须包含正式 applicationId、versionName/versionCode、minSdk、实际 apkFile/apkSha256 和
signingCertificateSha256；文件名必须与 APK 资产一致。正式版与公开 prerelease 都应提供这些资产，
每个分发构建的 versionCode 应递增，正式版也应高于此前测试版，不以修改说明或重命名旧包代替更新。
应用不会扫描未发布的 CI 工件。规则与验证见[ADR 0034](../decisions/0034-manual-update-checks.md)。

- APK：`ts3-mobile-1.0.0-release.apk`；校验表：`SHA256SUMS.txt`；构建信息：`BUILDINFO.json`。
- 正式应用 ID：`io.github.ts3mobile.app`；不分发 QA/调试旁装包作为正式升级。
- 当前构建源码、发布提交和工件摘要以新 `BUILDINFO.json` 为准；实际构建输入需全部核对。
  `eb7e87d` 与原 434 个输入属于 code 13 历史，不能冒充 code 14 的重新构建证据。
- 证书与公开 0.11.1-m10 一致；保留历史 Android Debug 主题，不声称建立新生产证书。
- Android 8.0+ 可安装，实际覆盖单独披露；不声称所有设备、蓝牙或真实数据升级均已验证。
- mapping、未签名交接包、密钥和未经审核的诊断日志保留在私有档案，不作为公开安装资产。

较早的 [M11 清单](M11_RELEASE_CHECKLIST.md)与持续开发计划作为历史背景，
未执行的旧验收项不会因本次发布自动变为通过。GitHub tag、Release 与分发构建信息记录最终上线结果。

## English

[TS3 Mobile 1.0.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.0.0) is the
first stable release, reissued 2026-11-05 as versionCode 14 after withdrawal of code 13.
See the [reissue record](1.0.0/REISSUE_2026-11-05.md). Both language versions share
one release with the signed APK, `SHA256SUMS.txt` and `BUILDINFO.json`.

The original public certificate is retained. Exact signed-APK probes and an API 35
emulator update preserving synthetic data passed. Maintainer-confirmed Xiaomi 14 /
Android 16 feature tests apply to the November 3 QA package. Final-APK physical
Bluetooth/real-data upgrade and second-device coverage remain follow-up work under
[ADR-0021](../decisions/0021-stable-release-acceptance.md); they are not reported as passes.

BUILDINFO keeps the actual build source separately from the merged/tagged release
commit and verifies all recorded source/configuration inputs across documentation-only
changes. It does not claim that the unchanged APK was rebuilt from a later commit.
The inherited certificate subject is Android Debug. Private keys, mapping, unsigned
handoffs and private diagnostic logs are excluded from public assets.
