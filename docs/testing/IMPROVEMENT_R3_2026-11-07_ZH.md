# R3：身份候选组合验收

日期：2026-11-08。状态：离线／模拟器候选验收完成。范围仅离线与模拟器；不合并／发布／版本升级。

## 计划与影响范围（修改前记录）

生产代码沿用C2.3并冻结其已实际验收的Debug／测试签名minified产物，不添加生产崩溃开关或R8测试keep。
补充独立Android scratch DataStore包装器，在真实持久边界写出固定ready标记后等待外部进程终止；
新测试进程调用实际IdentityStore恢复。夹具文件、marker和alias均为UUID且带ts3_test前缀，
不触碰默认身份、表单、收藏或生产alias。外部runner只接受全新可丢弃模拟器，拒绝物理序号和已有包，
不得卸载原应用以通过检查。

拟覆盖真实进程终止：事务前、事务执行中、首次事务ack后、pending清理前、清理ack后／UI交付前；
并验证pending active损坏可回退、双副本损坏／钥丢失保留失败证据且不生成。各用例核对原key挑战、
有效身份精确摘要、pending／rollback、失败文件字节和无额外alias创建。

补充下一次手动连接读取新身份的JVM组合回归。ConnectionCoordinatorTest已超过1,500行，
本次仅复用其现有FakeHost/FakeSession添加一个行为用例与捕获的identity参数；不复制大型fake，
不借验收重构生产状态机。D2监测提取时再评估拆出共享测试fixture，按职责拆而不是单纯压行数。

同步双语安装／兼容／隐私和进度，明确公开code14仍没有本批候选功能、测试签名范围和外部备份边界。
已有C2.2／C2.3真实独立安装SAF／minified材料证据列入候选；新增崩溃测试不能替代那些用户流程。


## 已执行结果

| 门禁 | 结果 |
| --- | --- |
| 质量／类型／静态 | quality、Debug／Release Lint、audio Lint通过；原有app3 Warning、1 Information |
| JVM | app356／protocol133／audio204全部通过，明确--rerun |
| 完整app Android | XML208项：206通过、2跳过、0失败；外部OS通知策略及本次进程终止夹具默认跳过 |
| 离线音频Android | 选定无录音的Focus／NativeLifecycle／PlaybackLifecycle／MicrophonePipeline，14项全部通过 |
| 连接组合 | 替换／回退均不自动connect；下一次手动连接分别使用新／回退身份，不读旧协调器缓存 |
| 外部进程夹具 | 下表8个独立UUID用例，实际ready边界→确认PID→force-stop→确认旧PID不存在→新instrumentation验证，全部预期通过 |
| 工具安全 | Python12项；物理／伪造模拟器序号在SDK／APK访问前拒绝，新设备已有app/test包拒绝，不用uninstall绕过 |
| 文档／其他 | 双语安装／兼容资料、隐私同步、本地链接、YAML、隐私扫描及whitespace通过 |

| 实际终止点 | 新进程验证的预期结果 |
| --- | --- |
| before-transaction | 原身份A保留，无自动创建 |
| during-transaction（进入actor但未执行变换） | 原身份A保留 |
| after-transaction（首次ack后） | 新身份B有效，验证后清pending，rollback A保留 |
| before-cleanup | 重启验证B并清pending，rollback A保留 |
| after-cleanup／UI交付前 | B与已清pending保持，rollback A保留 |
| damaged-active | pending active失败，恢复有效A并保留证据 |
| damaged-both | 明确UNREADABLE，原文件字节保持，原key挑战仍通过，不生成 |
| missing-key | 明确UNREADABLE，原文件字节保持，alias仍不存在，不创建新钥／身份 |

真实存储：`build/identity-crash/0212703962ec4da6a1ba97ccb29f6786/`，8个case各有seed、被终止进程、重启验证原日志；
result.json固定候选Debug SHA为`a9fdeedb5f5db73f239fedfa0d53bbad1e8f7f7f69209e38d7076903abaa6d03`，
新test APK SHA为`da709c187064c01ba6f348c83bb64a6c47874a56052b986230b63f833e0561e0`。
默认身份文件从未创建，未访问真实数据／服务器／录音。新AVD `ts3-r3-crash-api35`／5588（API35、arm64、4KiB）已停止保留。
这些是进程终止与DataStore已确认边界；不声称电源故障、任意文件系统半写点或云端持久性验收。

## 精确候选和组合证据

R3仅新增测试／runner／文档／CI，不修改生产代码。逐一比较C2.3冻结输入，生产源、资源、manifest、构建配置保持。
继续冻结C2.3已实际运行的候选：Debug上述a9fdeedb、unsigned minified
`ceccce75cffe9ca5ec4a76a9ed56d745b818ffad849c5fd88a9b78db852f1c0e`，标准debug测试签名副本
`cb287597e6113167cd5cf228adf22500720b03c5d04876c5b87b67cc498a35b2`。
没有把重打包后未经用户流程的产物替代已验证候选。

[C2.2实际导出](IMPROVEMENT_C22_2026-11-07_ZH.md)及[C2.3实际跨安装／minified导入回退](IMPROVEMENT_C23_2026-11-07_ZH.md)
均含真实SAF、force-stop与独立Node/OpenSSL精确私钥材料核对。C2.3升级前读取原默认三Vault；维护后明确允许身份变化，
仍验证三套原密钥、原表单／收藏／调音字节及UID；标准严格probe不放宽。

`build/improvement-2026-11-07/r3-full-gate.log`、`r3-audio-offline.log`、`r3-full-results/`保存原XML，
`r3-source-sha256.json`记录511个输入，`r3-process-crash.log`定位外部独立结果。
CI在app connected测试前于全新CI模拟器执行同一外部runner，保存JSON／日志；保持既有APK签名／版本／依赖／权限。

## 变更分类与剩余边界

- 生产：无。
- 测试：独立进程fixture／包装器；一项手动连接组合回归及fake捕获身份参数；Python物理设备拒绝回归。
- 文档：本记录、安装、兼容、进度；明确公开code14不含草稿候选功能。
- 配置／工具：独立只接受fresh emulator的进程runner，CI调用与报告保留。

R3离线与模拟器关卡完成。生产签名覆盖升级、实体/OEM／低端、API26运行时、云提供方持久性和独立安全审计不在已通过证据中。
没有合并、公开发布或版本升级。下一阶段B1先做静音量化关卡，依据可重复收益决定是否改生产音频路径。
