# D2：会话约束的连接监测提取

日期：2026-11-08。状态：实现与定向离线／模拟器验收完成，R4继续最终组合。

已读协调器入口、identity job登记／取消／真正完成、网络状态类型、活性策略、重连／退出所有权、
协议端口及已有fake／可控时钟测试。按[ADR-0034](../decisions/0034-session-bound-connection-monitoring.md)
先在原实现补充非合作式旧probe、终态与transport read竞争、task removal与观测竞争回归，再迁移。
超过1,000行协调器保留状态机与job所有权；新小组件只报告带session／listener／epoch／network代次的事实，
不引入十余个Host转发。大测试保留共享fake，仅新增独立小型observer测试。

风险是晚结果误伤新会话、取消误当完成、监听器认领改变、时间常量／重试策略漂移；
原套件及新增独立虚拟时钟测试共同约束。只改app监测边界，不改协议、音频、数据或用户策略。
所有现有1s／10s／5s／30s／2s时间常量保持。

## 实现与结果

`ConnectionMonitor`约百行，只依赖协议端口和单调时钟。默认网络转换、单次bounded probe、
watch循环／间隔观测可独立测试；没有Service／Host转发接口，没有scope／job或断开／重连入口。
`MonitoredSession`显式捕获session／listener／epoch，probe另带networkGeneration；
协调器在结果使用前复核身份，监测也在clock／transport读取后丢弃失效binding。
这些过期事实不刷新替换会话的诊断。原listener原子认领与终态分类代码保持，transport结束仍同步认领。

connectedWatchJob／livenessJob仍由原launchIdentityWork登记、启动与取消，不把取消当真正完成。
非合作式probe被取消并退出连接后，维护资格仍为CONNECTION，直到该probe真正返回才释放为IDLE。
默认boolean网络兼容入口仍沿用同一代次规则，pending／blocked、静默／unknown transport、
负时间跳变、新watch基线和已确立连接才重试的规则均由原回归约束。

| 验证 | 实际结果 |
| --- | --- |
| 原实现对照 | 原inline协调器搭配新增竞态共71项全部通过，`d2-original-coordinator-gate.log`／XML |
| app JVM | 384项全部通过：新增4项协调器竞态＋10项独立monitor；原退出／维护／频道／重试套件保持 |
| 时间与策略 | 源码逐项核对watch1000ms、gap10000ms、秒换算1000、probe5000ms、stable30000ms、exit2000ms未变 |
| 质量／类型／静态 | quality、Debug／Release Lint及构建通过，沿用原3 Warning／1 Information |
| Android接线 | 真实Main→Appearance→Identity共用／无采集与maintenance拒绝FGS／12秒保全，两项通过 |
| 数据／API | 协议／binder端口、存储格式、资源／权限／依赖未变；没有新监测设置或保活包 |

证据`build/improvement-2026-11-07/d2-{before-extraction,original-coordinator-gate,unit,final-gate,android-integration}.log`，
原实现71项XML另存；`d2-source-sha256.json`固定572个输入，验收后再次核对。
完整Android、音频、升级／进程及最终minified用户流程集中到R4，不把D1旧产物当D2新产物验收。

变更分类：生产为ConnectionMonitor与协调器接线；测试为4项真实协调器fake竞态与独立10项monitor；
文档为ADR、本记录／架构／进度；配置无变化。回退可恢复原inline监测，不涉及数据迁移。
本项收益为监测可独立理解／测试，未声称运行时更省电或必然更稳定。没有合并、发布或改版本。
