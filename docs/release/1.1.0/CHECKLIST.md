# 1.1.0 发布验收

日期：2026-11-09（Asia/Shanghai）。versionName `1.1.0`，versionCode `15`，新标签 `v1.1.0`。
维护者已明确要求安装到 Xiaomi 14、同步 GitHub 文档并发布正式 Release。
本记录在发布前准备，后续按实际结果更新；线上状态以 GitHub Release 和 BUILDINFO 为准。

## 范围与架构

1.1.0 集成前期 P0–R4 改进，以及已完成的 UI 层级/门控刻度/聊天气泡、蓝牙 SCO 空闲误判修复、
离线音频诊断、正式/测试版更新检查与 Markdown 更新说明。
应用层继续负责 UI、偏好和诊断存储；协议层不依赖 Android，音频层通过现有端口接线。
本轮发布只调整版本配置、文档和必要的验收，不增加新的产品行为或存储迁移。

README、架构、MainActivity、TeamSpeakService 等大文件的职责评估沿用已有 ADR：
发布摘要保持简短，详细验收放在本目录；不在版本发布中重构生命周期或拆分服务。
更新界面、诊断存储、身份维护已有独立协作者及测试，避免继续扩张入口文件。

## 兼容与风险

- 保持正式 applicationId、原公开证书、API 26/35 和四 ABI；code 15 高于公开 code 14。
- 不卸载/清数据；手机有活动会话时先正常断开，再覆盖安装、检查 UID 与记忆表单。
- 新增偏好和私有诊断档案已在各自功能验收中验证；无数据库或公共协议格式迁移。
- 不能把本次发布称为移动网络断续已彻底修复。现有诊断用于下一次真实弱网留证。
- 蓝牙物理热插拔、长时间锁屏、电量/写入开销、更多 Android/OEM 与独立安全审计仍有覆盖边界。
- 不公开密钥、密码、mapping、设备标识、原始设备 UI/日志、服务器或参与者信息。

## 发布门禁

- [x] 冻结完整源码与版本，执行格式、编译、全模块 JVM、Lint、Debug/Release 构建。
- [x] 执行相关 Android 离线回归、Python 工具、隐私和文档检查。
- [x] 原公开证书签名，核对版本/非 debuggable/ABI/权限/16 KiB ZIP 对齐与原 ZIP 条目。
- [x] 确切签名 APK 的身份/INI、Opus ART 与启动探针。
- [x] Xiaomi 14 保留数据升级及更新页面实测。
- [x] GitHub 集成 PR CI 通过并正常合入 main。
标签和资产流程：新建不可移动的 v1.1.0，绑定实际构建/发布提交和工件摘要；草稿下载复核后
公开为非 prerelease / Latest，并复核公开下载。本记录在打标前冻结，不预报上线。
最终执行状态以 [GitHub Release](https://github.com/monet4070/ts3-mobile/releases/tag/v1.1.0)
及其 `BUILDINFO.json` 为准。

## 变更分类

- 生产：本次集成此前已验收的功能与修复，不在发布阶段重写音频或连接行为。
- 测试：沿用独立 JVM/Android 回归，并对最终 1.1.0 工件重新执行适用门禁和升级验收。
- 文档：双语 README/使用指南、安装、兼容性、隐私、安全、CHANGELOG、发布记录和导航。
- 配置：versionName/code 为 1.1.0/15；保留已锁定依赖和测试 CI，原包名/权限/签名不变。

本地非公开证据保留在忽略目录 `build/releases/1.1.0/`。
前期功能证据见 [R4](../../testing/IMPROVEMENT_R4_2026-11-07_ZH.md)、
[蓝牙/移动网络排查](../../testing/MOBILE_RX_AUDIT_2026-11-08_ZH.md)、
[离线诊断](../../testing/OFFLINE_AUDIO_DIAGNOSTICS_2026-11-09_ZH.md)、
[更新界面](../../testing/UPDATE_CHECK_2026-11-09_ZH.md)。

## 最终验收结果

实际构建源码：`4b0084e99990a2d7390e50bc3a9da71cfc624de0`，构建时工作树干净。
记录 634 个非 Markdown、非 docs 构建输入；之后的文档补充/普通合并不得改变这些输入。
最终发布提交与实际构建提交分别写入 `BUILDINFO.json`，不把已有 APK 称作后续文档提交的重新构建。

| 检查 | 结果与范围 |
| --- | --- |
| 完整本地门禁 | 禁用构建缓存并重跑 292 项任务，格式、Kotlin 编译、全模块 JVM、音频/App Lint、Debug/Release 与测试 APK 构建通过 |
| JVM | 应用 421、音频 233、协议 133，共 787 项通过，0 失败/错误/跳过 |
| API 35 ARM64 / 4 KiB 应用 Android | 246 项通过，2 项要求外部夹具的用例按设计跳过；不将跳过计作通过 |
| 音频 Android | 20 项离线 native player/路由/诊断/生命周期/个人静音回归通过；使用合成输入，无实体声学测量 |
| API 35 ARM64 / 16 KiB 身份事务 | fresh 模拟器上 8 个真实终止/重开边界通过，不创建默认身份、不连接服务器 |
| 工具 | 18 项 Python、48 组 Opus 跨版本组合、独立身份备份向量、隐私扫描、YAML/文档链接及 whitespace 通过 |
| Lint | 无 Error；既有 Debug 36 Warning/1 Information，Release 25 Warning/1 Information 保留 |
| 最终 APK | 原公开证书、非 debuggable、四 ABI、API 26/35、16 KiB ZIP 对齐；168 个原 ZIP 条目签名前后相同，权限与公开 code 14 相同 |
| 最终包探针 | Xiaomi 14 / API 36 与独立 API 35 / 16 KiB 模拟器：身份/INI 和原生 Opus 1.6.1 往返通过 |
| 公开 code 14 → 15 | 独立模拟器实际安装下载的 1.0.0，再覆盖最终签名 1.1.0；UID、合成私有文件/偏好与 Keystore 密文解密保留，冷启动通过；不称真实身份 Vault 读回 |
| Xiaomi 14 候选 code 14 → 15 | 正常断开后覆盖，安装二进制摘要一致，UID 与 4 项记忆连接字段一致；更新页面显示 1.1.0/15、真实检查/Markdown/固定关闭/收起通过，首次验证后回到 chat；最终仅提交元数据变化的同版本包再次保留数据覆盖，核对新摘要/UID/4 项表单后保持补装前的未连接状态 |

最终 APK SHA-256：

```text
0287568eee4c42587ed4e714dc8dc6524a8f2ca1151955a9b57f68ce6258b2a5
```

证书 SHA-256：

```text
e239102412f17ddd1909bd323b3825831b153fec4602ec00abb359da7d8be592
```

云端：集成 [PR #26](https://github.com/monet4070/ts3-mobile/pull/26) 的
[Android CI](https://github.com/monet4070/ts3-mobile/actions/runs/42607171229) 通过；
报告下载核对记录保存在本机发布档案。文档验收补充后的构建输入逐项不变。
这些结果不扩大前述真实弱网、物理蓝牙/声学、长时锁屏或其他 API/OEM 的覆盖范围。

首次云端运行 [42603824867](https://github.com/monet4070/ts3-mobile/actions/runs/42603824867)
在 3 项布局断言失败：短视口未组合全部聊天气泡，以及搜索结果跨帧/裁剪坐标比较。
只修改两个 Android 测试文件：先显式滚动到虚拟化项目，再在同一 UI 帧读取未裁剪的布局位置/尺寸；
保留原有几何和加入操作断言，没有禁用或跳过失败用例，也没有改产品代码。
修正后 360×640 dp 小屏的 4 项定向测试通过，随后重跑门禁；首次失败报告保留在本地和 Actions 历史。
