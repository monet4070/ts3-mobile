# 1.0.0 发布准备记录（2026-11-04）

目标：`v1.0.0`，正式应用 ID `io.github.ts3mobile.app`，versionName `1.0.0`，
versionCode `13`。当前阶段为**未签名候选包交接**，尚未创建正式 tag 或公开 Release。

## 架构理解与范围

保持 Compose/Activity → 前台服务 → JVM 协议端口及独立音频基础设施边界。
本轮不增加功能，不变更协议实现、存储和权限；协议依赖仅迁移维护归属，字节码不变。现有功能、UI、通知、文档与蓝牙保护
共同进入发布候选；版本元数据与相应说明同步调整。候选源码通过现有草稿
[PR #5](https://github.com/monet4070/ts3-mobile/pull/5)进入审查，保留正常 PR/CI 流程。

README、ARCHITECTURE 已较长，本轮只同步少量状态；发布证据、签名交接及剩余门禁
集中在本文件和现有发布手册，避免继续扩大这些入口文件或应用服务职责。

## 维护者实测确认与已定位工件

维护者于 2026-11-04 确认已自行完成剩余实际测试，设备为 **Xiaomi 14**，
测试包为前一天的包。已从本地 2026-11-03 证据目录定位并检查：

| 工件 | 实际内容 |
| --- | --- |
| 昨日 `qa-release.apk` | 混淆 Release；隔离应用 ID `io.github.ts3mobile.app.stableqa`；`0.11.1-m10` / 12 |
| 昨日 QA APK SHA-256 | `2c4c740ec3ec571745010c8cdfddd276eb9449f99030b17ac5665e8331d62071` |
| 昨日 QA 证书 SHA-256 | `d204d1ba57de3d353b6f1871aef5fefc0d47d6b51a889e9f8990fd2eff1a32e5` |
| 旧公开 `v0.11.1-m10` | 正式应用 ID；`0.11.1-m10` / 12；已从 GitHub 重新下载核对 |
| 旧公开 APK SHA-256 | `f91f9d1a95be0fb4173493004a0399f860b6a99ed1ee16e8cac03103dbad63b7` |
| 需要沿用的旧公开证书 SHA-256 | `e239102412f17ddd1909bd323b3825831b153fec4602ec00abb359da7d8be592` |

旧公开证书主题为 Android Debug；本记录不将其描述为新建立的生产证书。
长期密钥的保管、访问控制和备份仍由维护者确认。维护者已说明旧公开版密钥在**另一台电脑**；
本机未取得该密钥，也不使用 QA 证书替代它。

原测试报告记录该 Xiaomi 14 为 Android 16。维护者的补测确认作为既有功能实测依据，
不再笼统标为“没有实际测试”。昨天的 QA APK 早于今天新增的蓝牙收听保护；不能将
该旧包的测试自动解释为新路由代码或最终签名 1.0.0 APK 的实测。最终包应复核蓝牙
断开保护及保留数据覆盖升级，原有功能的实测结论继续保留。

来源：[昨日全量记录](../../testing/STABLE_FULL_2026-11-03.md)、
[今天蓝牙修复与回归](../../testing/BLUETOOTH_ROUTING_2026-11-04.md)及本次维护者说明。
公开记录不包含设备序列号、服务器、身份、对端或私人日志。

## 本轮候选检查

首次 320dp 全量测试 129 项中有两项定位失败：LazyColumn 的屏外频道尚未加载，
以及窄屏麦克风菜单被当作宽屏按钮检查。仅修测试：先滚动容器、在弹出菜单内逐项
检查相同的单行/裁切断言，并对弹出菜单截图。修正后完整 129 项通过；原始失败
日志/XML 保留在 `validation/initial-app-failure/`。生产 UI 未为测试改变。

全部 Gradle 主门禁禁用构建缓存并强制执行，238 个任务实际执行；
包含格式、三模块单元测试、Kotlin/Java 编译、Debug/Release Lint、Debug 与混淆 Release。

| 检查 | 结果 |
| --- | --- |
| 仓库格式 / 编译 / Debug 与混淆 Release 构建 | 通过 |
| 三模块 JVM | 551 项通过：协议 129、音频 183、App 239 |
| Android Lint | 0 错误；已有依赖/版本和资源建议保留 |
| Python 构建工具 | 7 项通过 |
| 完整 App Android 离线回归 | 新依赖消费后完整 129 项通过，0 失败/跳过 |
| 默认原生音频 Android 回归 | 32 项通过，0 失败/跳过；可选 APM 与麦克风采样探测不在该默认套件 |
| 实际候选 APK 身份/INI、Opus ART 探针 | 未签名候选通过；ARM64、16 KiB ART，运行时 libopus 1.6.1 |
| APK 元数据、权限、四 ABI、16 KiB、默认包不含 APM | 全部核对；zipalign 16 KiB 与 64 位 ELF LOAD 16 KiB 通过 |
| 隐私扫描、文档链接/YAML、空白、源码/配置指纹 | 通过：374 个本地链接/锚点、4 个 YAML；434 个输入前后相同 |

本机使用项目已有 JDK 17、SDK 35、NDK 27.0.12077973、CMake 3.22.1；
模拟器为 ARM64 / Android 15 / API 35，16 KiB page size。Windows 专用环境检查脚本
中的 `java.exe` 路径不适用于本机；JDK/SDK 实际路径和 UDP 能力使用本机工具确认。
日志、测试 XML、源码/配置哈希保存在忽略目录 `build/releases/1.0.0/validation/`。
Opus 新旧版本兼容的 48 组组合另已重新执行通过。32 位 NDK libc++ 的 LOAD 对齐仍为
4 KiB；16 KiB Android 运行时验证针对 64 位库，不将其表述为所有 32 位系统的声学验收。

## 签名交接目录

已检查的未签名候选 APK SHA-256：
`24913c76ddf9f318e92ddf7a80a663b496407ef6a9084b2d943f56eeafd5b8bc`。
冻结源码提交以交接包 `UNSIGNED_BUILDINFO.json` 的真实 `sourceCommit` 为唯一来源，
避免在提交自身的文档中写入循环引用的 SHA。

交接目录为 `build/releases/1.0.0/handoff/`，包含：

- `ts3-mobile-1.0.0-unsigned.apk`：通过 16 KiB zipalign 的正式包名候选，尚未签名。
- `UNSIGNED_BUILDINFO.json`、`SHA256SUMS-UNSIGNED.txt`：未签名阶段的真实摘要、版本、
  源码与工具链信息。不是正式分发用的最终签名摘要。
- `mapping.txt`：该候选实际 R8 mapping。
- `source.tar.gz`：对应冻结源码，不包含密钥、本地配置和构建缓存。
- 双语 Release 正文及 `SIGNING_HANDOFF.md`：在另一台电脑完成签名、旧证书核对、
  最终摘要和升级复核的操作步骤。

未签名 APK 不可作为 GitHub 正式分发资产。最终签名后重新验证证书、签名、包名、
版本、对齐和实际探针，再生成 `BUILDINFO.json` 与 `SHA256SUMS.txt`。
不生成新密钥，不从 QA 签名推导旧公开版可升级。

## 依赖归属门禁

已完成 [ADR-0004](../../decisions/0004-take-ownership-of-ts3j-dependency.md) 的维护者工件归属：

- fork：[monet4070/ts3j](https://github.com/monet4070/ts3j)。
- 不可移动标签 `ts3mobile-1`：`db57d60c989e399626aa16d921390f5033e6cdeb`，
  与原上游固定提交相同，源码 tree 为 `215ef0a871fd95929ef521f79319515b55e5288e`。
- JitPack 坐标：`com.github.monet4070:ts3j:ts3mobile-1`；三模块锁文件已重新生成，
  差异只涉及该坐标，四个传递依赖版本未变。
- 新旧 JAR 的 **215 个 class 文件逐字节相同**，公共 ABI 相同；179 个源码文件另以
  Java 8 目标与现有锁定依赖编译通过。协议实现没有升级或打补丁。
- 新 JAR SHA-256：`23cd4c615cb4cff454fef493e202b6b52879e7371bb80b33bee7fdc498459837`。
- 新 POM SHA-256：`7a8a8245e803d54dd8d52596dc8e4cdee272b506f8f3bd634e30fa9a7b576b62`。

完整 `dependencies` 报告包含不能匹配项目 variant 的非消费 metadata 配置；
该报告的成功退出不作为构建验收。锁文件只保留 ts3j 坐标变化，既有配置与其他版本不变，
实际消费编译和完整回归验证新坐标与 STRICT 锁定。维护策略已写入第三方说明：
保留固定标签，对未来协议/安全修复使用新的审查提交与标签。JitPack 服务可用性仍是边界。
原始编译、JAR/ABI 对比和下载证据保存在本地 `dependency-review/` 目录。

## 变更分类与下一步

- 生产代码：本轮无新增行为；现有功能及今天的蓝牙修复纳入候选。
- 测试：重跑既有完整单元、界面、原生与实际 APK 探针，保留新鲜 XML。
- 文档：发布状态、维护者实测确认、双语蓝牙说明、签名交接与清单同步。
- 配置：应用版本调整为 `1.0.0` / 13；ts3j 切换到维护者坐标并同步锁文件。
  应用 ID、权限、传递依赖版本和持久化格式不变。

完成本地候选检查并冻结源码后，可在另一台电脑使用原发布密钥签名。
依赖归属已补齐；CI、最终签名/升级证据齐备后，再按
[PUBLISHING.md](PUBLISHING.md)准备 GitHub 草稿及最终公开。
