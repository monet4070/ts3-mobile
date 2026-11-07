# A2：调音参数持久化与失败状态

日期：2026-11-07（Asia/Shanghai）。基于A1/Q1提交`b1b44a9`，工作分支`codex/microphone-preferences`。本轮按维护者要求仅做离线与模拟器验收。

## 实现范围

- 独立v1 Preferences DataStore保存输入音量、降噪意愿、AEC意愿和门限；默认与范围复用MicrophoneSettings。没有模式、正在采集、PTT按下或实际AEC可用状态。
- 独立控制器按字段合并晚到读取，包括用户明确选择当前默认值；旧UI快照不会重写其他已加载字段。内存revision与durable revision分离，滑杆合并写入，松开／关闭请求最新保存。
- 设置通过服务主线程队列按发布顺序应用，沿用MicrophoneController.configure，不请求采集。新服务仍默认PTT。保存状态使用独立Binder StateFlow，不会被连接状态重置覆盖。
- 未知版本／字段非法／文件损坏不自动覆盖；显式恢复默认仅能重置本独立调音文件，物理损坏无法编辑时保留原文件并报告失败。UI区分载入、待保存、保存中、完成及读写失败，并提供重试／恢复默认。
- 为该文件增加云备份和设备传输排除项；同步中英文说明，并标明这些是开发分支新增，未宣称公开code14已有此能力。

详见[ADR-0023](../decisions/0023-persistent-microphone-preferences.md)。现有身份、表单、收藏格式、协议、音频算法和运行依赖未改。

## 新执行验证

| 检查 | 结果 |
| --- | --- |
| 新偏好／并发JVM回归 | 12通过，包含真实文件DataStore重建、四字段、未知schema、非法范围和损坏字节保全 |
| 全应用JVM | 253通过，0失败／错误／跳过，任务强制新执行 |
| 定向Android | 18通过：麦克风设置、既有控制器生命周期和新偏好生命周期 |
| 完整应用Android | 141通过，0失败／错误／跳过 |
| 格式、应用Debug/Release Lint | 通过；保留原有3个资源Warning |
| Debug、minified Release、测试APK | 构建通过；Release unsigned，不是正式发布 |
| 实际Activity/Binder/服务重启 | 不填端点、不连接服务器，在英文设置把输入音量调至147%，保存完成后force-stop并重开；恢复147% |
| 不自动采集 | Android真实DataStore加载／重置接fake capture，start计数和权限检查均为0，模式保持PTT；实际界面未请求录音，AppOps显示ignore且未列出使用记录 |
| UI视觉 | 查看英文实际Activity和中文1.8倍字号顶部／底部截图；保存状态、滑杆、开关与关闭操作可见／可滚动 |
| 隐私／配置 | 原PowerShell扫描、YAML解析、shell语法与空白检查通过；CI纳入新增设置／生命周期类 |

设备为专用API35 / Android15 / arm64-v8a / 4KiB模拟器，1080×2340、density420。没有真实语音、服务器、麦克风录音或物理路由测试。中文截图来自合成fixture；英文截图与147%重启检查来自真实MainActivity与Service接线。

物理录音文件不会作为证明手段。新偏好首次缺失时保留原默认行为；进程在“待保存／保存中”结束可能丢失最后一笔，只有DataStore事务完成才标保存完成。

## 命令与证据

```sh
./gradlew --offline --max-workers=2 :app:ktlintFormat
./gradlew --offline --max-workers=2 quality :app:testDebugUnitTest --rerun :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
pwsh -NoProfile -File tools/check-repository-privacy.ps1
```

最后门禁255个任务：59执行、1缓存、195复用；应用JVM和141项Android为本次实际执行。没有把复用编译当作新测试。一次格式检查检出超长错误分支，拆行后重新通过；没有关闭规则。

Gradle测试结束会卸载测试应用，因此视觉检查另外安装刚构建的Debug／test APK，在同一专用模拟器直接运行中文大字测试并提取PNG；没有为取图修改生产代码。随后用真实MainActivity操作验证147%保存与进程重启，完成后未执行连接。

本机忽略目录`build/improvement-2026-11-07/`保存`a2-final-gates.log`、`a2-junit-accepted/`、`a2-android-accepted/`、431份输入的`a2-source-before.sha256`、`a2-process-restart.txt`、视觉仪器日志和三份设置截图。结束后核对输入指纹。Release依赖／R8规则未变，本阶段未将P0的身份／codec探针算作新执行；候选R1补组合与升级。

## 变更分类

- 生产：新增偏好状态／存储／控制器与状态操作UI；Activity/Service/MainScreen/设置对话框窄接线。
- 测试：新增12项JVM与2项Android生命周期回归，设置UI追加失败／重置2项，fixture增加保存状态与动作计数。
- 配置：新偏好文件的备份排除；CI追加MicrophoneSettingsTest与MicrophonePreferenceLifecycleTest。
- 文档：ADR、双语README／指南／隐私、架构、进度及本记录。无依赖、协议或旧存储迁移。
