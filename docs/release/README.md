# 首个稳定版发布资料 / First stable release pack

**TS3 Mobile 1.0.0** · tag `v1.0.0` · versionCode **13** · **2026-11-04**。
[公开 Release 与签名 APK](https://github.com/monet4070/ts3-mobile/releases/tag/v1.0.0)。

维护者已要求公开首个稳定版，原密钥签名与旧公开证书比对通过，最终 APK 模拟器保留数据升级通过。
小米 14 / Android 16 的昨日 QA 包功能实测确认继续保留；最终包蓝牙真机与真实数据升级、
第二设备等未执行范围随 [ADR-0021](../decisions/0021-stable-release-acceptance.md)公开披露。

## 文件入口

| 文件 | 用途 |
| --- | --- |
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

- APK：`ts3-mobile-1.0.0-release.apk`；校验表：`SHA256SUMS.txt`；构建信息：`BUILDINFO.json`。
- 正式应用 ID：`io.github.ts3mobile.app`；不分发 QA/调试旁装包作为正式升级。
- 构建源码：`eb7e87d82c5b9aadaa46ddd2258b7de7f475b109`；最终 tag 的发布提交另记在 BUILDINFO。
  后续差异仅为文档与合并提交，全部 434 个已记录源码/配置输入需一致。
- 证书与公开 0.11.1-m10 一致；保留历史 Android Debug 主题，不声称建立新生产证书。
- Android 8.0+ 可安装，实际覆盖单独披露；不声称所有设备、蓝牙或真实数据升级均已验证。
- mapping、未签名交接包、密钥和未经审核的诊断日志保留在私有档案，不作为公开安装资产。

较早的 [M11 清单](M11_RELEASE_CHECKLIST.md)与持续开发计划作为历史背景，
未执行的旧验收项不会因本次发布自动变为通过。GitHub tag、Release 与分发构建信息记录最终上线结果。

## English

[TS3 Mobile 1.0.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.0.0) is the
first stable release, dated 2026-11-04, versionCode 13. Both language versions share
one release with the signed APK, `SHA256SUMS.txt` and `BUILDINFO.json`.

The original public certificate is retained. Exact signed-APK probes and an API 35
emulator update preserving synthetic data passed. Maintainer-confirmed Xiaomi 14 /
Android 16 feature tests apply to the November 3 QA package. Final-APK physical
Bluetooth/real-data upgrade and second-device coverage remain follow-up work under
[ADR-0021](../decisions/0021-stable-release-acceptance.md); they are not reported as passes.

BUILDINFO keeps the actual build source separately from the merged/tagged release
commit and verifies all 434 source/configuration inputs across documentation-only
changes. It does not claim that the unchanged APK was rebuilt from a later commit.
The inherited certificate subject is Android Debug. Private keys, mapping, unsigned
handoffs and private diagnostic logs are excluded from public assets.
