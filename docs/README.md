# 文档导航 / Documentation

| 内容 | 中文 | English |
| --- | --- | --- |
| 项目介绍与版本状态 | [README](../README_ZH.md) | [README](../README.md) |
| 日常操作 | [使用指南](USER_GUIDE_ZH.md) | [User guide](USER_GUIDE.md) |
| 1.2.0 界面截图 | [简体中文](images/1.2.0/zh-CN/) | [English](images/1.2.0/en/) |
| 安装、升级、签名 | [双语说明](INSTALLATION.md) | [Bilingual guide](INSTALLATION.md) |
| 故障排查 | [常见问题](TROUBLESHOOTING_ZH.md) | [Troubleshooting](TROUBLESHOOTING.md) |
| 设备、协议与验证覆盖 | [兼容性说明](COMPATIBILITY.md) | [Compatibility](COMPATIBILITY.md) |
| 支持渠道 | [支持](../SUPPORT.md) | [Support](../SUPPORT.md) |
| 隐私 | [隐私说明](../PRIVACY_ZH.md) | [Privacy](../PRIVACY.md) |
| 安全漏洞 | [私密报告流程](../SECURITY.md) | [Security](../SECURITY.md) |
| 稳定版发布 | [1.2.0 发布资料](release/README.md) | [Release pack](release/README.md) |
| 待发布开发状态 | [候选状态与实机验收](release/CANDIDATE_STATUS_2026-11-25.md) | [Candidate status](release/CANDIDATE_STATUS_2026-11-25.md) |

维护与来源：

- [小米 14 候选验收（2026-11-25）](testing/PHONE_CANDIDATE_2026-11-25_ZH.md)：覆盖安装、设置、只读文件、指引及尚待补测的真机范围。
- [开发约定](../CONTRIBUTING.md)、[架构](../ARCHITECTURE.md)、[设计决策](decisions/)
- [第三轮审计修复交接](plans/ROUND_3_REMEDIATION_HANDOFF_ZH.md)：按审计编号记录修复方式、关键代码、回归证据和剩余验收。
- [下版本执行方案：收藏、桌面身份、浮动 PTT 与音频（2026-11-21）](plans/DESKTOP_IDENTITY_FLOATING_PTT_BOOKMARKS_2026-11-21_ZH.md)：收藏独立热修；功能版包含身份导入排期、浮窗探针、音频诊断与独立立体声实验，逐项验收。
- [执行与交接（2026-11-23）](plans/DESKTOP_PTT_AUDIO_EXECUTION_STATUS_2026-11-23_ZH.md)：离线证据、剩余开发和合并后的真机操作清单。
- [TS6_Droid 源码对照（2026-11-24）](plans/TS6_DROID_CODE_REVIEW_2026-11-24_ZH.md)：聊天、收藏、频道文件与现有协议/音频边界的借鉴优先级。
- [TS6_Droid 借鉴项执行记录（2026-11-24）](plans/TS6_BORROWING_EXECUTION_2026-11-24_ZH.md)：实现、可复跑证据和尚未过门的能力。
- [按需头像和图标预览（2026-11-24）](testing/SERVER_IMAGE_PREVIEW_2026-11-24_ZH.md)：固定 TS3j 资源互通、Debug 验证与未覆盖边界。
- [当前服务器聊天附件（2026-11-24）](testing/CHAT_ATTACHMENT_PROBE_2026-11-24_ZH.md)：链接格式、会话核对和 Android 实服保存复跑。
- [TS3j 握手修复候选（2026-11-24）](testing/TS3J_WIDTH_CANDIDATE_2026-11-24_ZH.md)：独立源码构建、接口差异与实服回归；默认依赖未切换。
- [B1 收藏凭据隔离（2026-11-23）](testing/BOOKMARK_CREDENTIAL_SCOPE_2026-11-23_ZH.md)：开发验证与待完成的发行验收。
- [I1/I2 桌面身份离线验收（2026-11-23）](testing/DESKTOP_IDENTITY_IMPORT_2026-11-23_ZH.md)：严格输入、独立测试、事务与混淆包；公开入口仍关闭。
- [P0 悬浮按钮资格探针（2026-11-23）](testing/FLOATING_PTT_P0_2026-11-23_ZH.md)：API 34–36 系统采集验证，不等同于游戏内传输。
- [A1 蓝牙输出对照（2026-11-23）](testing/BLUETOOTH_OUTPUT_AB_2026-11-23_ZH.md)：配对 JSON 自动判定与一次性人工采集清单。
- [A0/A2 协议计时与实际音频设备（2026-11-23）](testing/VOICE_TIMING_AND_ACTUAL_DEVICES_2026-11-23_ZH.md)：计时边界、混淆包验证、设备面板与真机待做项。
- [1.1.0 之后的改进与功能计划（2026-11-19）](plans/NEXT_PHASE_2026-11-19_ZH.md) 根据项目实测与 TS3 用户反馈，为通话稳定性、邀请入服、本机试麦和后台收听控制排期。
- [收听稳定性与延迟档位（2026-11-20）](plans/AUDIO_PLAYBACK_STABILITY_2026-11-20_ZH.md)：开源对照、缓冲候选、真机验收与独立切网回归计划。
- [邀请、试麦与通知收听开发验证（2026-11-20）](testing/NEXT_PHASE_FEATURES_2026-11-20_ZH.md)：实现范围、模拟器／覆盖安装结果与待做的真机验收。
- [使用体验与工程质量提升计划（2026-11-07）](plans/IMPROVEMENT_PLAN_2026-11-07_ZH.md) 记录上一轮的范围、依赖和验收目标。
- [提升执行计划与工作量评估（2026-11-07）](plans/IMPROVEMENT_EXECUTION_PLAN_2026-11-07_ZH.md) 记录上一轮的任务拆分和估算，实际完成情况见下方进度记录。
- [提升计划执行进度（2026-11-07）](plans/IMPROVEMENT_PROGRESS_2026-11-07_ZH.md)：实际状态、阶段证据及待验收项。
- [更新日志](../CHANGELOG.md)、[第三方许可](../THIRD_PARTY_NOTICES.md)
- [测试记录](testing/)与[历史开发计划](plans/)保留各自日期，不自动代表最新 APK 的验收。
- [R4 Xiaomi 14 实机首轮检查（2026-11-08）](testing/DEVICE_R4_XIAOMI14_2026-11-08_ZH.md)：真实连接、采集生命周期、短时后台和 API 36 探针；远端听感待确认。
- [UI 层级、门控调节与聊天气泡（2026-11-08）](testing/UI_HIERARCHY_2026-11-08_ZH.md)：按实机反馈调整搜索、麦克风设置和聊天展示。
- [移动网络收听与蓝牙误判排查（2026-11-08）](testing/MOBILE_RX_AUDIT_2026-11-08_ZH.md)：稳定版对照、SCO 空闲释放证据及保护性恢复修复。
- [离线音频诊断（2026-11-09）](testing/OFFLINE_AUDIO_DIAGNOSTICS_2026-11-09_ZH.md)：接收/播放分时统计、断音标记、退出后保留与 JSON 文件导出。
- [应用内检查更新（2026-11-09）](testing/UPDATE_CHECK_2026-11-09_ZH.md)：正式版／测试版选择、版本比较、离线回归与真实 GitHub Release 冒烟。
- [1.0.0 语音断续回归排查与修复](testing/AUDIO_PLAYBACK_REGRESSION_2026-11-05_ZH.md)记录离线复现和实机验证边界。
- [1.0.0 / code 14 修复重发](release/1.0.0/REISSUE_2026-11-05.md)记录撤回后重用标签授权与新工件验收。
- [历史 M11 发布计划](release/CONTINUATION_PLAN.md)保留作背景。

稳定版 [1.2.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.2.0)（2026-11-20，code 16）：
见[中文更新说明](release/1.2.0/RELEASE_NOTES_ZH.md)。

See the [release notes](release/1.2.0/RELEASE_NOTES.md) for stable
[1.2.0](https://github.com/monet4070/ts3-mobile/releases/tag/v1.2.0) (2026-11-20, code 16).
