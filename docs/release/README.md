# 发布资料 / Releases

[文档中心](../README.md) · [安装与升级](../INSTALLATION.md) · [兼容性与验证](../COMPATIBILITY.md) · [完整更新日志](../../CHANGELOG.md)

## 版本一览 / Version Index

| 版本 / Version | versionCode | 状态 / Status | 中文说明 | English Notes | 验收与清单 / Verification |
| --- | :---: | --- | --- | --- | --- |
| [**1.3.0**](https://github.com/monet4070/ts3-mobile/releases/tag/v1.3.0) | `17` | 稳定版 / Stable (2026-11-28) | [1.3.0 更新说明](1.3.0/RELEASE_NOTES_ZH.md) | [1.3.0 Release Notes](1.3.0/RELEASE_NOTES.md) | [验证记录与剩余范围](CANDIDATE_STATUS_2026-11-25.md) · [11-26 交接](HANDOFF_1.3.0_2026-11-26_ZH.md) |
| [**1.2.0**](https://github.com/monet4070/ts3-mobile/releases/tag/v1.2.0) | `16` | 历史稳定版 / Previous stable (2026-11-20) | [1.2.0 更新说明](1.2.0/RELEASE_NOTES_ZH.md) | [1.2.0 Release Notes](1.2.0/RELEASE_NOTES.md) | [兼容性](../COMPATIBILITY.md) |
| [**1.1.0**](https://github.com/monet4070/ts3-mobile/releases/tag/v1.1.0) | `15` | 历史稳定版 / Previous stable (2026-11-09) | [1.1.0 更新说明](1.1.0/RELEASE_NOTES_ZH.md) | [1.1.0 Release Notes](1.1.0/RELEASE_NOTES.md) | [1.1.0 发布清单](1.1.0/CHECKLIST.md) |
| [**1.0.0**](https://github.com/monet4070/ts3-mobile/releases/tag/v1.0.0) | `14` | 首个稳定版（重发）/ First stable reissue (2026-11-05) | [1.0.0 更新说明](1.0.0/RELEASE_NOTES_ZH.md) | [1.0.0 Release Notes](1.0.0/RELEASE_NOTES.md) | [重发记录](1.0.0/REISSUE_2026-11-05.md) · [1.0.0 清单](1.0.0/CHECKLIST.md) |

> **说明 / Note:** 下载以 [GitHub Releases](https://github.com/monet4070/ts3-mobile/releases) 为准。1.3.0（code 17）的正式包验证与剩余实机范围分别记录在[验证记录](CANDIDATE_STATUS_2026-11-25.md)；早期候选的结果不归入最终工件。

---

## 发布资产规范 / Release Asset Standards

每个公开发布版本（包括正式版与公开测试版）均附带以下三类资产：

- **APK 安装包**：`ts3-mobile-<version>-release.apk`（正式应用 ID：`io.github.ts3mobile.app`，沿用公开签名证书，通用四 ABI：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`）。
- **校验表**：`SHA256SUMS.txt`（记录对应 APK 的 SHA-256 摘要）。
- **构建元数据**：`BUILDINFO.json`（schemaVersion 1，记录 `applicationId`、`versionName`、`versionCode`、`minSdk`、实际源码提交、发布提交、`apkFile`、`apkSha256` 和 `signingCertificateSha256`）。应用内 **检查更新** 依赖该文件校验更新来源（见 [ADR-0036](../decisions/0036-manual-update-checks.md)）。

Every public release attaches the signed APK (`io.github.ts3mobile.app`), `SHA256SUMS.txt`, and `BUILDINFO.json` (schemaVersion 1). `versionCode` increases strictly for every distributed build. Debug/QA sideload packages, mapping files, unsigned handoff APKs, keystores, and private diagnostic logs are never distributed as public release updates.

---

## 历史发布归档 / Historical Release Archive

旧版本的发布流程、决策和验收记录按日期保留，不代表新版本的实测结果：

- **1.0.0 首个稳定版（2026-11-04 / 2026-11-05 code 14 重发）**：
  - [1.0.0 音频修复重发记录](1.0.0/REISSUE_2026-11-05.md) — 撤回 code 13 并以 code 14 重用 `v1.0.0` 标签的授权与验收
  - [1.0.0 发布清单与验收表](1.0.0/CHECKLIST.md) — 源码、签名、自动化与设备覆盖记录
  - [ADR-0021 稳定版发布验收决定](../decisions/0021-stable-release-acceptance.md) — 维护者公开决定与保留缺口
  - [打包、签名与上线手册](1.0.0/PUBLISHING.md) · [发布准备记录](1.0.0/PREPARATION_2026-11-04.md) · [异机签名交接](1.0.0/SIGNING_HANDOFF.md)
- **1.0.0 之前的里程碑归档**：
  - [M11 发布清单](M11_RELEASE_CHECKLIST.md) · [历史开发延续计划（EN）](CONTINUATION_PLAN.md) · [历史开发延续计划（ZH）](CONTINUATION_PLAN_ZH.md) · [Pre-1.0 开发日志归档](DEVELOPMENT_NOTES_PRE_1_0.md)
