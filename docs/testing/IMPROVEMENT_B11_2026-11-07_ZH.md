# B1.1：单人静音隔离量化关卡

日期：2026-11-08。状态：离线／模拟器量化关卡完成。仅离线／模拟器；不播放真实语音或录音。

## 修改前理解与方案

真实链路已复核：replaceParticipantGains任一跨零增益变化递增全局admission／write revision，
clear输入及全部budget，worker执行PlaybackSession.invalidate，关闭全部talker／decoder并退役输出。
这意味着B的未混音有效样本也会因A的静音和解除静音失效。混合待写与已交给HAL的样本是另一个边界，不能混算为可保留队列。

本关卡只加测试，不加生产计数器／decoder测试分支。通过既有outputFactory注入fake output，
实际OpusAudioPlayer.submit输入A/B两路可区分的合成Opus，分别停在混合待写、部分写入和已进入阻塞write。
worker停在测试barrier时，只读观察现有queue预算、talker PCM／pending及decoder引用，记录精确样本账本。
反射仅在测试内用于观测，worker未停稳时不读取其可变map；不向生产接口加入测量负担。

OpusAudioPlayer约300行，B1.1不修改。若后续收益关卡通过，新增失效命令／准入资格模型将独立成小文件，
player只接线；不改接收调度、音频算法、逐人输出轨道或已有公共API。

## 预先固定的判据与口径

- 保留现有“已进入native那一次可返回，其后不得开始旧代次write”的安全语义；主线程变更不得等待fake HAL barrier。
- B输入5×960样本；第一混合块480样本已从B租约消费，余量必须由input／encoded pending／PCM三部分守恒解释。
- 先记录B未混音预算在A静音和解除静音时的损失、B decoder是否被关闭／重建、输出退役／重建、首次恢复write延迟。
- 已混合／已交输出的B部分单列，不能以保留decoder掩盖那部分损失；fake head／write数据不称为真实可闻缺口。
- 后续若选择保留B，目标为有效未混音样本／预算零额外丢弃、B不因A的变更重建decoder，缓存／延迟上界不扩大。
- 原执行计划的“不flush轨迹最大静音相关缺口降低至少50%”不放宽。当前AndroidPlaybackOutput.discard后永久silenced，
  未经设计不能直接复用；必须flush／重建的轨迹单列，不冒充不flush达标。物理听感按用户范围保持未验证。
- 无可重复样本损失／收益证据或需要重写调度／逐人轨道时停在研究交付；有证据才决定后续有界实现。


## 实测基线

真实player／Native Opus1.6.1，fake output，无AudioTrack、录音或服务器。两次独立instrumentation运行，
每次三场景各两条，共12条。所有初次B账本均为：input 960 + encoded pending 960 + PCM 2400 = 4320样本（90ms），
再加已混合480样本（10ms），与已提交5×960媒体样本严格守恒。

| 边界 | 条数 | A静音后B未混音有效样本 | B解码器 | 首次B恢复write进入延迟（静音／解除静音） | 首输出接受样本 |
| --- | --- | --- | --- | --- | --- |
| PENDING（零写后混合块待写） | 4 | 4320→0 | 两次均关闭并重建 | 72.69–85.50ms／79.16–90.97ms | 0 |
| PARTIAL（120已接受、后续写阻塞） | 4 | 4320→0 | 两次均关闭并重建 | 68.96–77.97ms／72.50–88.13ms | 120 |
| BLOCKED（首次已进入write） | 4 | 4320→0 | 两次均关闭并重建 | 68.51–80.14ms／78.96–87.87ms | 480，属于已进入的那一次返回 |

解除静音时B的当时有效未混音预算也全部清零；各轨迹输出创建3次。
主线程两类操作最大0.956ms，均在fake HAL仍阻塞时返回；之后没有新的旧混音write开始。
上述延迟是write入口时间，fake head不代表真实硬件播放，不称为可闻中断或HAL性能。
最早混合块中的10ms B会随着输出退役丢失，未列入可保留的90ms，已交输出的部分亦单列。

证据：`build/improvement-2026-11-07/b11-baseline-{1,2}.json`、
`b11-baseline{-repeat,}-instrument.log`、`b11-baseline-audio-test.apk`及`b11-source-sha256.json`。
测量仅新增Android测试／只读观测器，不修改生产代码；运行时保留原JNI和接收预算。

## 关卡决策

存在可重复、可归因的额外90ms未混音损失和两次B decoder重建，收益关卡通过，继续有界B1.2。
方案只分离参与者准入／失效命令，保留B队列、租约和decoder；沿用已验证的混合块／输出退役，
不添加逐人输出轨道、不改调度／音频算法、不扩大缓存或初始等待。

本轮先保留现有flush／重建输出合同，因为AndroidPlaybackOutput.discard会永久silence，
直接复用需要另行设计复位、路由与render reference合同。不会为显示“零重建”绕过这些安全边界。
不flush的50%改善目标仍未验收，不能把本轮必须退役的轨迹算作不flush达标；真实听感／物理路由继续未验证。
后续必须实际证明B有效未混音零额外丢弃及decoder不重建，并在同一测量器上报告候选变化，不能只数decoder。

变更分类：生产无；测试新增四个小文件，单一测量器同时用于基线与候选；文档为本记录／进度；
CI新增该无录音的Android测量类。依赖／权限／版本不变。

最终门禁：`:audio-opus:ktlintCheck`、204项JVM测试（0失败／跳过）、
`:audio-opus:lintDebug`及仓库隐私扫描通过；Android测量类已独立执行两次，
合计12条轨迹均通过fixture／旧write安全断言。日志为`b11-gate.log`，
云端组合验收须以本阶段PR的实际运行结果为准。
