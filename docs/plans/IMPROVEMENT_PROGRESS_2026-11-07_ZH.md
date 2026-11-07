# 提升计划执行进度

日期：2026-11-07（Asia/Shanghai）。维护者已要求逐阶段完成[执行计划](IMPROVEMENT_EXECUTION_PLAN_2026-11-07_ZH.md)。此表记录实际进展，估算和设计文档不作为完成证明。

维护者随后明确：本轮只做离线与模拟器验收。实体设备、锁屏听感和物理路由验证留待后续；不会作为本轮已通过的证据，也不再阻止本轮离线阶段推进。

| 工作包 | 状态 | 证据／下一步 |
| --- | --- | --- |
| P0 | 离线基线完成 | [本次基线](../testing/IMPROVEMENT_P0_2026-11-07_ZH.md)；物理设备和其他API验证按功能项安排 |
| A1 | 离线实现与验收完成 | [4项旧实现失败、8项新增回归及137项完整套件](../testing/IMPROVEMENT_A1_Q1_2026-11-07_ZH.md) |
| Q1 | 离线／云端验收完成 | [PR #8 CI](https://github.com/monet4070/ts3-mobile/actions/runs/42348440916)通过；下载核对报告含导航8项与关键UI57项 |
| A2 | 离线／云端验收完成 | [253 JVM／141 Android、真实Activity服务进程重启](../testing/IMPROVEMENT_A2_2026-11-07_ZH.md)；[PR #9 CI](https://github.com/monet4070/ts3-mobile/actions/runs/42357799673)通过并核对报告 |
| A3a / A3b | 离线／云端验收完成 | [263 JVM／154 Android、事务guard和可见Join](../testing/IMPROVEMENT_A3_2026-11-07_ZH.md) |
| R1 | 离线／模拟器验收完成 | [Debug及minified候选同开发证书覆盖升级、真实Vault及原密钥保全](../testing/IMPROVEMENT_R1_2026-11-07_ZH.md)；不包含生产签名／实体设备 |
| A4 | 离线／模拟器／云端完成 | [282 JVM、完整 167 Android 通过／1 外部 fixture 默认跳过，真实系统开关两 fixture 通过](../testing/IMPROVEMENT_A4_2026-11-07_ZH.md) |
| A5 | 离线实现与模拟器验收完成 | [282 app／204 audio JVM、完整 177 Android 通过／1 外部 fixture 默认跳过及最终 27 项补验](../testing/IMPROVEMENT_A5_2026-11-07_ZH.md) |
| R2 | 离线／模拟器验收完成 | [第一批→第二批Debug／minified覆盖升级、四调音参数／原密文／原密钥／UID保全与组合流程](../testing/IMPROVEMENT_R2_2026-11-07_ZH.md) |
| C1 | 离线原型／参数关卡完成 | [132 protocol／295 app JVM、4 Android及跨提供者／资源／资格／计时证据](../testing/IMPROVEMENT_C1_2026-11-07_ZH.md) |
| C0 | 离线／模拟器兼容验收完成 | [299 JVM／188 Android通过、三真实Vault双向兼容／缺钥保全及默认alias覆盖升级](../testing/IMPROVEMENT_C0_2026-11-07_ZH.md) |
| C2.1 | 离线／模拟器验收完成 | [325 JVM、完整195 Android通过／1跳过及最终12补验、独立覆盖升级](../testing/IMPROVEMENT_C21_2026-11-07_ZH.md) |
| C2.2 | 离线／模拟器验收完成 | [340 app／133 protocol JVM、完整201 Android通过／1跳过与最终7补验、实际Debug／minified SAF导出](../testing/IMPROVEMENT_C22_2026-11-07_ZH.md) |
| C2.3 / R3 | 待实施 | 导入／替换／回退与身份批验收 |
| B1 / D1 / D2 / R4 | 待实施 | 静音量化关卡、主题与监测提取 |

P0/A1/Q1已经提交到草稿[PR #8](https://github.com/monet4070/ts3-mobile/pull/8)，固定源码`b1b44a9`；远端CI通过且报告已核对。A2是后续草稿[PR #9](https://github.com/monet4070/ts3-mobile/pull/9)，基于PR #8，源码`d3f481c`；为连续阶段PR补充`codex/**`目标分支CI触发。没有合并或公开发布，实际结果以各阶段记录及对应源码指纹为准。

A3 草稿 [PR #10](https://github.com/monet4070/ts3-mobile/pull/10) 生产源码 `827f851`，
追加小屏惰性列表及 IME 行为测试修复至 `5636d3d`；最新 CI 待确认。
R1 草稿 [PR #11](https://github.com/monet4070/ts3-mobile/pull/11) 初始 `e7b4241`，
[云端组合 CI](https://github.com/monet4070/ts3-mobile/actions/runs/42362487981) 263／129／194 JVM、81／11 Android 通过且下载核对；
后续仅同步上述频道测试修复至 `7eb183d`。所有 PR 仍为草稿，没有合并或公开发布。

A4 已提交草稿 [PR #12](https://github.com/monet4070/ts3-mobile/pull/12)，生产及回归源码 `0ca3f1c`，
[离线／模拟器验收](../testing/IMPROVEMENT_A4_2026-11-07_ZH.md)完成，云端 CI 待确认。下一阶段 A5。

A3 最新[CI 42363545006](https://github.com/monet4070/ts3-mobile/actions/runs/42363545006)与
R1 最新[CI 42363547532](https://github.com/monet4070/ts3-mobile/actions/runs/42363547532)均通过，下载核对了263／129／194 JVM、81／11 Android。
A4 测试修复至 `fac612b`，[CI 42364912604](https://github.com/monet4070/ts3-mobile/actions/runs/42364912604)通过，
报告核对为282／129／194 JVM、96／11 Android，0失败。上述最新证据取代之前“待确认”状态。

A5 已提交草稿 [PR #13](https://github.com/monet4070/ts3-mobile/pull/13)，生产与回归源码 `3adcbdb`，
[离线／模拟器验收](../testing/IMPROVEMENT_A5_2026-11-07_ZH.md)完成，云端CI待确认。R2继续组合与覆盖升级验收。

R2 已提交草稿 [PR #14](https://github.com/monet4070/ts3-mobile/pull/14)，测试／工具源码 `c95935d`；
[第二批验收](../testing/IMPROVEMENT_R2_2026-11-07_ZH.md)完成，生产仍沿用A5。下一阶段 C1。

A5 [CI42366139680](https://github.com/monet4070/ts3-mobile/actions/runs/42366139680)与
R2 [CI42366823505](https://github.com/monet4070/ts3-mobile/actions/runs/42366823505)通过且下载核对：
两者282／129／204 JVM；A5 app107／audio14 Android，R2 app108／audio14 Android，全部零失败。

C1 已提交草稿 [PR #15](https://github.com/monet4070/ts3-mobile/pull/15)，源码 `c844b9a`，
[离线关卡](../testing/IMPROVEMENT_C1_2026-11-07_ZH.md)完成，云端CI待确认。C0继续旧Vault兼容收敛。

C1 [CI42368616849](https://github.com/monet4070/ts3-mobile/actions/runs/42368616849)通过且下载核对：
295／132／204 JVM，app112／audio14 Android，全部零失败；本地参数和资源关卡证据仍见C1记录。

C0 已提交草稿 [PR #16](https://github.com/monet4070/ts3-mobile/pull/16)，源码 `3a3c10e`，
[兼容验收](../testing/IMPROVEMENT_C0_2026-11-07_ZH.md)完成；云端 CI 待确认。开始 C2.1 存储事务与维护互斥。

C0 [CI42371137285](https://github.com/monet4070/ts3-mobile/actions/runs/42371137285)通过并下载核对：
299／132／204 JVM、app117／audio14 Android，全部零失败。C2.1正在完成事务与维护资格验收。

C2.1 已提交草稿 [PR #17](https://github.com/monet4070/ts3-mobile/pull/17)，源码 `440dfcc`。
[事务／维护验收](../testing/IMPROVEMENT_C21_2026-11-07_ZH.md)完成，云端CI待确认；继续C2.2。

C2.1 [CI42388366562](https://github.com/monet4070/ts3-mobile/actions/runs/42388366562)重试后通过并下载核对：
325／132／204 JVM、app124／audio14 Android，全零失败；首次失败为既有Opus源码下载连接重置。
