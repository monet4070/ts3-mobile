# B1.2：有界参与者静音隔离

日期：2026-11-08。状态：B1.2实现及B1.3离线／模拟器验收完成。

## 修改前理解与影响范围

已阅读player入口、session、streams、timeline、receive queue／budget、mixer、
相关JVM与Android生命周期测试、模块构建和执行计划。跨零增益目前走全局准入／write代次，
清除所有人的队列及decoder；这正是B1.1重复观测到的90ms额外未混音损失。

只修改audio-opus播放失效边界，不改协议、服务、公共API、配置格式和采集。
约300行player先按[ADR-0032](../decisions/0032-participant-playback-invalidation.md)
拆出两个小型纯JVM策略模型；session共享输出退役，streams定向清理。
主要风险是旧A包跨静音／解除静音复活、晚命令删除新A包、旧混音继续写入及输出释放失败时重叠创建。
独立测试覆盖这些风险；全局路由／焦点／停止保护保持。

## 预先固定的验收口径

- 同一B1.1测量器；B有效未混音样本零额外丢弃、B decoder不因A静音／解除静音重建。
- 样本账本区分input、encoded pending、PCM和已混音／HAL；不能只数decoder。
- 旧输出依然安全退役；已进入那一次write可返回，之后不得开始旧代次write；主线程不等HAL。
- 不增加缓存／初始等待上界，不改音频算法／调度。焦点退避不被定向命令缩短。
- 零写、短写、阻塞、异常／释放失败及全局失效回归，保留原生命周期断言。
- 本方案必须退役输出；不flush的缺口降低50%及物理听感仍未验证，不计作达标。

## 实现

`PlaybackAdmission`的ticket只在一次包准备期间持有；只撤销A的ticket，B的在途准备仍有效。
mute→unmute不能复活旧A包；finally释放记录，历史client不累积。全局路径仍撤销所有ticket。
`PlaybackInvalidation`短锁合并参与者集合，全局覆盖参与者命令并保留focusInterrupted；
worker取出命令后才调用音频I/O，revision在每次write前检查。

主线程只删除A旧队列／预算；worker清理A被撤销的talker／gain，保留B input／encoded pending／PCM／decoder。
若旧tick已经接纳了新A代次，pipeline已reset旧decoder状态，晚命令不能再次关闭新代次或删除其队列。
输出退役复用session唯一流程，render reference丢弃、close失败阻止重建、新timeline以新head原点计数。
只reset共用limiter历史，B gain ramp不重置；参与者命令不缩短已有焦点／输出退避。

## 同测量器对照

B1.1夹具进一步把“解除静音前快照→主线程操作→后快照”置于合成feeder短锁内，
worker仍停在原输出barrier，避免新B包在两张快照间造成账本误差。
为保持可比性，用原生产代码重新构建control并独立运行两次，然后候选独立运行两次，
每组12条；不是拿修改夹具后的候选与旧夹具直接比较。

| 边界／每组条数 | 原实现 A静音后B有效未混音 | 候选 | 原实现恢复write入口（静音／解除） | 候选恢复write入口（静音／解除） |
| --- | --- | --- | --- | --- |
| PENDING／4 | 4320→0 | 4320→4320 | 74.04–84.25ms／77.87–92.99ms | 10.58–11.77ms／8.98–12.45ms |
| PARTIAL／4 | 4320→0 | 4320→4320 | 71.14–81.88ms／76.41–91.96ms | 8.45–9.42ms／9.87–10.48ms |
| BLOCKED／4 | 4320→0 | 4320→4320 | 67.72–82.45ms／78.66–90.02ms | 6.32–8.38ms／8.25–9.55ms |

初始账本两组相同：input960 + encoded pending960 + PCM2400 = 4320（90ms），
另列已混音480（10ms），合计5×960守恒。解除静音时control3360→0，candidate4800→4800；
两者因首次恢复所需包数不同，不能将两组当时的总budget直接相减为收益。
原实现12条均在静音和解除时关闭／重建B decoder；候选12条均无B decoder关闭／重建。
主线程最大control0.940ms、candidate0.836ms，均在fake HAL阻塞期间返回。
旧输出接受样本PENDING0、PARTIAL120、BLOCKED480，两组一致；之后没有新的旧代次write。

两组每条均创建3次输出，已混音／HAL损失边界仍在。上述时间是fake output的write入口，
不是可闻缺口／HAL性能。没有扩大接收预算、初始等待或改算法／调度来换取结果；
不flush缺口降低50%与真实路由听感仍未验证，不作为本轮通过项。

证据位于`build/improvement-2026-11-07/`：`b12-{control,candidate}-{1,2}.json`、
`b12-comparison.json`、两组`*-audio-test.apk`与`*-instrument.log`。
control使用B1.1分支原生产调用链；新增独立策略类即使在测试APK中存在，也未接入该control路径。

## 自动门禁

- 219项audio JVM通过，含新增15项：ticket竞态／异常释放、命令合并／全局优先、样本逐一守恒、
  queued与已ingest的新A代次、释放失败、焦点退避、输出异常、limiter复位与B gain ramp。
  原调度、负包龄、预算及十分钟守恒模拟全部仍通过。
- 同一B回归在临时恢复旧全局清理行为后预期失败（4320→0），随后恢复候选通过。
  证据`b12-old-behavior-regression.{log,xml}`；没有提交该临时行为。
- app356／protocol133 JVM、quality、audio Lint、app Debug／Release Lint及Debug／minified构建通过。
  app沿用3 Warning／1 Information，audio Lint无issue。
- 18项选定离线audio Android全部通过，包括原`participantMuteInvalidatesPendingMixBeforeAnotherWrite`
  未改断言、实际AudioTrack焦点／路由／停止保护及新增三种边界隔离；无录音／服务器。
- 实际minified APK中的Opus1.6.1 JNI roundtrip通过（arm64-v8a），
  证据`build/release-codec-smoke/d8377f066d0547a1bb62c3e8c26d5ae0/`；不冒充minified完整播放或实体听感验收。

- 完整app Android XML208项：206通过／2外部夹具默认跳过／0失败。两跳过仍为OS通知策略与
  外部进程终止夹具，R3的独立8点终止验收另有记录，未冒充本次重跑。
- 最终仅测量日志输出改为小行JSON，再补跑quality、audio Lint及一项测量类（6条轨迹）；
  直接从工作流解析出同一提取代码，实际在Gradle卸载test包后生成JSON，6条精确保留断言通过。
  最后这个测试APK是`b12-final-audio-test.apk`；生产实现与主要对照的候选完全相同。
- 隐私扫描、18项Python工具回归、Markdown本地链接、工作流YAML／shell语法及whitespace通过。

`b12-full-gate.log`、`b12-audio-android.log`、`b12-app-android.log`与`b12-full-results/`
保存上述门禁和原XML；最终补验`b12-ci-metrics-gate.log`、`b12-ci-measurement.json`。
`b12-source-sha256.json`固定557个源码／测试／配置／工具输入，验收后再次逐一核对。
冻结Debug SHA256 `ab73d8c32181bed8849733b587a09d6b9d5ccb31721bed6c3dd9a0367da78872`，
unsigned minified `c6c7053446b265382346eb7b3ff0c90ca586bf5f327fd4359d445f52c74af2c0`。
没有用不同生产源码的重打包产物替代测量候选；CI运行结果另行记录。

## 变更分类与边界

- 生产：两个独立策略模型及player接线；streams定向清理、pipeline代次查询、session共享退役、mixer输出历史复位。
- 测试：独立JVM与Android隔离回归；同一测量器同步合成进料并输出小行JSON，方便CI只提取匿名合成指标。
- 文档：ADR、本记录／进度、架构、双语README／使用指南与贡献说明，明确尚未公开发布及输出间隙边界。
- 配置：CI选择新增无录音类并保存提取的合成JSON；依赖、权限、数据格式、版本／签名配置不变。

没有合并或发布。回退为B1.1保守全局失效路径，不涉及数据迁移。本轮不验收实体/OEM／蓝牙听感。

[CI42438462330](https://github.com/monet4070/ts3-mobile/actions/runs/42438462330)通过且下载核对：
356／133／219 JVM、app135／audio18 Android，全零失败。工作流实际保存的measurement.json
含6条有效未混音精确保留轨迹；原始报告位于`build/improvement-2026-11-07/b12-ci-42438462330/`。
