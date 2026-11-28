# 文档中心 / Documentation

当前文档覆盖 **TS3 Mobile 1.3.0（versionCode 17）**，对比 **1.2.0（versionCode 16）** 的更新内容与验收状态见下方入口。
These docs cover **TS3 Mobile 1.3.0 (versionCode 17)**; see the links below for 1.3.0 changes vs **1.2.0 (versionCode 16)** and verification status.

---

## 用户文档 / User Documentation

| 主题 / Topic | 简体中文 | English |
| --- | --- | --- |
| 项目总览与 1.3.0 亮点 / Overview & 1.3.0 highlights | [README（中文）](../README_ZH.md) | [README (English)](../README.md) |
| 1.3.0 更新说明 / 1.3.0 release notes | [1.3.0 中文更新说明](release/1.3.0/RELEASE_NOTES_ZH.md) | [1.3.0 English release notes](release/1.3.0/RELEASE_NOTES.md) |
| 日常使用指南 / User guide | [使用指南](USER_GUIDE_ZH.md) | [User guide](USER_GUIDE.md) |
| 安装、校验与保留数据升级 / Install & upgrade | [安装与升级](INSTALLATION.md) | [Installation & upgrades](INSTALLATION.md#english) |
| 故障排查 / Troubleshooting | [常见问题](TROUBLESHOOTING_ZH.md) | [Troubleshooting](TROUBLESHOOTING.md) |
| 设备兼容性与验证范围 / Compatibility & validation | [兼容性与验证范围](COMPATIBILITY.md) | [Compatibility & validation](COMPATIBILITY.md#english) |
| 隐私与本地数据 / Privacy & local data | [隐私说明](../PRIVACY_ZH.md) | [Privacy](../PRIVACY.md) |
| 问题反馈与支持 / Support & bug reports | [获取支持](../SUPPORT.md) | [Support](../SUPPORT.md#english) |
| 安全漏洞私密报告 / Security policy | [安全政策（中文）](../SECURITY.md#中文说明) | [Security policy](../SECURITY.md) |
| 界面截图说明 / UI screenshots | [1.3.0 界面截图索引](images/README.md) | [1.3.0 screenshot index](images/README.md) |

---

## 版本与发布 / Releases & Changelog

| 资料 / Document | 说明 / Description |
| --- | --- |
| [发布资料总索引 / Releases index](release/README.md) | 1.3.0、1.2.0、1.1.0、1.0.0 版本表、发布资产规范与历史归档 |
| [1.3.0 验收状态与发版门禁 / 1.3.0 verification status](release/CANDIDATE_STATUS_2026-11-25.md) | 1.3.0 功能边界、CI 与真机验收进展及最终签名包门禁 |
| [1.2.0 发布说明 / 1.2.0 release notes](release/1.2.0/RELEASE_NOTES_ZH.md) | 公开 1.2.0 稳定版中文说明 · [English](release/1.2.0/RELEASE_NOTES.md) |
| [完整更新日志 / Changelog](../CHANGELOG.md) | 按版本记录全部新增、变更与修复 |

---

## 开发者与架构 / Architecture & Contributing

- [系统架构说明（ARCHITECTURE.md）](../ARCHITECTURE.md)：当前维护中的模块边界、并发状态机、音频管线、文件传输、浮窗 PTT 与身份事务设计。
- [双语历史架构快照（ARCHITECTURE_BILINGUAL.md）](ARCHITECTURE_BILINGUAL.md)：早于 1.1.0 的中英文架构图与设计快照索引。
- [贡献与本地检查指南（CONTRIBUTING.md）](../CONTRIBUTING.md)：本地构建、质量门禁、模拟器回归、升级兼容检查与 PR 要求。
- [协议适配模块说明（ts3-protocol/README.md）](../ts3-protocol/README.md)：纯 JVM 协议门面、有界断开、聊天／戳一下与只读文件边界。
- [TS3j 源码恢复与补丁说明（tools/ts3j-patches/README.md）](../tools/ts3j-patches/README.md)：离线源码回退构建与可选 QA 补丁。
- [架构决策记录（docs/decisions/）](decisions/) · [便携身份备份格式 v1](IDENTITY_BACKUP_FORMAT_V1.md) · [第三方许可](../THIRD_PARTY_NOTICES.md)

---

## 1.3.0 验证记录与历史工程归档 / Verification & Engineering Archive

以下工程记录与测试报告均保留各自日期与对应工件边界，供追溯实现与复跑参考，不代表最终签名发布包的全部真机验收：

### 1.3.0 专项排查与验证记录

- [Wi-Fi ↔ 蜂窝切网失败排查（2026-11-28）](testing/NETWORK_HANDOFF_2026-11-28_ZH.md)：短时切网保活、握手拒绝 `521` 竞态修复与实机复验判据。
- [1.3.0 验收交接（2026-11-26）](release/HANDOFF_1.3.0_2026-11-26_ZH.md)：早期原签名包实机基线、DTX/FEC 夹具修复与文件探针收口记录。
- [第三轮审计修复交接](plans/ROUND_3_REMEDIATION_HANDOFF_ZH.md)：审计编号对应的修复方案、回归测试与剩余验收项。
- [只读频道文件探针（2026-11-24）](testing/CHANNEL_FILES_PROBE_2026-11-24_ZH.md) · [同服聊天附件探针（2026-11-24）](testing/CHAT_ATTACHMENT_PROBE_2026-11-24_ZH.md) · [按需头像和图标预览（2026-11-24）](testing/SERVER_IMAGE_PREVIEW_2026-11-24_ZH.md)
- [浮窗 PTT 系统资格探针（2026-11-23）](testing/FLOATING_PTT_P0_2026-11-23_ZH.md) · [收藏凭据隔离验证（2026-11-23）](testing/BOOKMARK_CREDENTIAL_SCOPE_2026-11-23_ZH.md) · [桌面身份离线验收（2026-11-23）](testing/DESKTOP_IDENTITY_IMPORT_2026-11-23_ZH.md)
- [协议计时与实际音频设备验证（2026-11-23）](testing/VOICE_TIMING_AND_ACTUAL_DEVICES_2026-11-23_ZH.md) · [蓝牙输出对照（2026-11-23）](testing/BLUETOOTH_OUTPUT_AB_2026-11-23_ZH.md) · [TS3j 握手修复候选（2026-11-24）](testing/TS3J_WIDTH_CANDIDATE_2026-11-24_ZH.md)
- [设置界面复核（2026-11-25）](testing/SETTINGS_UI_REVIEW_2026-11-25_ZH.md) · [小米 14 早期 QA 基线（2026-11-25）](testing/PHONE_CANDIDATE_2026-11-25_ZH.md)

### 历史目录

- [所有测试记录（docs/testing/）](testing/)：涵盖 1.0.0–1.3.0 各阶段音频、蓝牙、后台、更新与 UI 回归记录。
- [历史开发计划（docs/plans/）](plans/)：各版本需求拆解、代码对照与执行进度归档。
