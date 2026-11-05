# 1.0.0 语音断续：播放时钟回归排查

日期：2026-11-05。比较基线：`v0.11.1-m10`（2026-10-18）与
`v1.0.0`（2026-11-04，`be1d6c2`）。本文记录工作区修复，不代表已发布修复版。

后续用户实机复测仍有断续，因而第一轮节拍器修复不是完整解释。本日继续录制
实际播放音频并附加调试器，确认另一个接收队列并发过期误判；见“后续实机排查”。

## 结论与证据边界

已经离线复现一个足以导致连续语音断续的播放端回归：`PlaybackPacer`
在短暂线程调度停顿后丢失原来的媒体截止时间，让实际 PCM 消费率落后于
48 kHz 输出时钟。积压随后触发接收预算失效，连同尚未混音的已解码 PCM
一起丢弃，再重新等待启动缓冲。

这不是仅从代码推测：修复前新增的调度器测试和真实 JVM 播放链路测试失败，
相同输入在修复后通过。测试没有网络、真实服务器、麦克风或 Android 音频设备。
因此能确认代码回归和丢帧机制，不能据此断言用户设备上不存在额外的蓝牙、
音频焦点、网络或麦克风门限问题，也不能把模拟输出的统计称为声学测量。

## 版本差异与定位

- 上一版在 `OpusAudioPlayer` 中维护绝对 `nextTickNanos`，每提交 480 个样本
  推进 10 ms；仅落后超过 50 ms 时才重新对齐当前时间。
- 提交 `2c7c0a8`（2026-11-01）提取 `PlaybackSession` 和 `PlaybackPacer`，
  同时引入覆盖输入、待解码包和 PCM 的共享接收预算。
- 新版 `PlaybackPacer.completed()` 使用
  `maxOf(nextTick + TICK_NANOS, now + MIN_WAIT_NANOS)`。
  正常的短停顿就会把过期截止时间向后移动，无法补足此前少提交的媒体 tick。
- 新版 `ReceiveMediaBudget.reserve()` 在单个说话人的待消费媒体超过
  11,520 个样本（240 ms）时使旧代次失效。`TalkerJitterPipeline` 因而清理
  待解码包和 PCM、重置解码器，并重新等待 40–120 ms 启动目标，形成明显断点。

实际播放路径为：

```text
VoiceFrame -> VoiceReceiveQueue -> ReceiveMediaBudget
           -> TalkerJitterPipeline -> ReceivePcmBuffer
           -> PlaybackStreams.mix(480) -> PlaybackTimeline -> PlaybackOutput
```

这条链路每次工作只混出 480 个样本。网络抖动缓冲能吸收包到达时间变化，
不能补偿播放 worker 的长期消费速率不足。单纯增加缓冲或回滚 Opus 并不修复
本次已复现的时钟错误。

## 确定性复现

输入为 1000 个 20 ms 包，共 20 秒单人连续语音，按时到达、无丢包、无重复。
每第 10 次 worker 唤醒额外延迟 25 ms，模拟操作系统线程调度停顿。
测试使用生产 `PlaybackPacer`、`VoiceReceiveQueue`、`PlaybackSession`、
`PlaybackStreams`、抖动管线和预算代码；仅用 fake decoder/output 替换 JNI 和 HAL。
模拟输出的播放头按流逝时间推进，而不是让它决定 worker 何时执行。

| 指标 | 未修复的 1.0.0 逻辑 | 修复后相同轨迹 |
| --- | ---: | ---: |
| 10 秒内完成的媒体 tick（独立调度器测试） | 865 | 至少 998 |
| 收到的语音包 | 1000 | 1000 |
| 正常解码的语音包 | 847 | 1000 |
| 期望提交的 PCM 样本 | 960000 | 960000 |
| 实际被模拟输出接收的 PCM 样本 | 771840 | 960000 |
| 共享预算重置 | 17 | 0 |
| 输入过期包 | 0 | 0 |
| 最大应用内等待 | 232 ms | 96 ms |

修复前缺失 188160 个样本，即原始媒体的 **19.6% / 3.92 秒**。
其中部分是已解码但因预算代次失效而未输出的 PCM，不能只统计正常解码包数。
本轨迹的重置不是输入 60 ms 过期规则导致：过期包计数为 0。

## 最小修复与覆盖

仅修改 `PlaybackSession.kt` 中的播放节拍策略：

1. 首次完成 tick 时以实际单调时钟建立下一个 10 ms 截止时间，不假设时间从 0 开始。
2. 落后不超过 50 ms 时保留原始截止时间，每次按 10 ms 推进，允许有界追赶。
3. 每次仍至少等待 1 ms；落后超过 50 ms 时重新对齐，避免长停顿后无限追赶。

没有调整 Opus/RNNoise、音量门限、FEC、接收预算、蓝牙安全静音、协议格式、
公开 API 或持久化配置。生产文件约 200 行，仍是单一 worker 会话职责，
不需要为此次修复另行拆分或迁移。

独立新增测试文件：

- `PlaybackPacerTest.kt`：正常处理耗时、25 ms 短停顿追赶、重复短停顿的
  10 ms 媒体时钟，以及 500 ms 长停顿后的有界等待。
- `PlaybackSchedulingRegressionTest.kt`：完整 JVM 播放链路，10/20 ms 包，
  0/5/10/25/40/50 ms 停顿，验证零预算重置、零输入过期、所有包解码且全部
  PCM 恰好提交一次，只创建一次输出。

原有连续性测试直接以 `tick * 10 ms` 调用播放链路，绕过了 `PlaybackPacer`；
旧节拍器测试只检查等待范围在 1–10 ms，没有验证媒体样本吞吐率。
因此这些离线检查此前通过，并不能排除此回归。

## 后续实机排查：新到达的包被误判为过期

### 方法与观察

在用户连接的 Android 16 手机上，先保留正在运行的调试版应用，不先改动
音频算法、路由或连接。使用官方 scrcpy 5.0.1 的 `playback` 复制源录制
30 秒双声道 48 kHz PCM WAV，同时保持设备播放。不录麦克风，不转录内容，
不上传音频、端点、身份、私密截图或原始系统转储。

录制产物验证：30.101 秒，峰值 24051，非零样本 1751588，证明不是空录音。
逐帧检查发现 7 段不少于 20 ms 的全零区间，共 2.186 秒，包含录制
首尾静音。这些区间本身不能区分自然语音停顿、流切换和客户端丢弃，不能
只凭波形把全部静音算作故障。

对当前调试进程只读抽样 `ReceiveStats`，20 个样本间 `stalePackets` 从
732 增至 786（+54），`budgetResets` 不变，`outputFailures` 为 0。
两个说话人管线的累计 `resetDroppedPcmSamples` 最后分别为 32640 和
235200。样本期间两个计数分别增加 13440 与 21600，共 35040 个样本；
这是所有管线重置清理的累计量，不把它误当成仅由网络丢包产生的 PLC。

然后在独立于上述录音的诊断窗口，短暂对
`ReceiveMediaBudget.invalidate()` 设置断点，在其调用方
`VoiceReceiveQueue.prune()` 中读取已经完成分类的 `now` 与 `stale`。
避免读取媒体载荷或参与者身份。三次真实误判的 `now - arrivalNanos` 为：

```text
-258021 ns
-170104 ns
-330417 ns
```

即到达时间比 worker 的时间快照晚约 0.17–0.33 ms 的新包，被走入了
“过期”代次失效路径。断点会短暂停顿进程，故不把断点窗口的音频当作
无干扰录音证据；读取的是断点触发前已经计算好的分类结果。

### 因果链与修复

`PlaybackSession.tick(now)` 在查询路由、设置音量及后续处理前取得时间。
与此同时，协议线程可入队带有更晚单调到达时间的新包。
旧 `VoiceReceiveQueue.prune(now)` 使用：

```kotlin
now - queued.arrivalNanos !in 0L..ReceivePolicy.INPUT_AGE_NANOS
```

这会把“晚于旧时间快照的新包”与“真正超过 60 ms 的旧包”同等处理，
并使该说话人的所有共享租约失效，清空待解码音频及已解码 PCM，重新缓冲。
生产者之间的锁竞争也能触发相同错误。测试以单线程事先入队再推进理想
时钟，不能覆盖这个真实并发交错。

最小修复只修改 `VoiceReceiveQueue.kt`：统一使用
`now - arrivalNanos > INPUT_AGE_NANOS` 判断真正过期，并让准入、清理、
重新准入共用这个规则。负包龄只表示时间快照落后于入队，不再触发失效。
仍保留严格 60 ms 正包龄边界、共享预算、去重和大小限制，不放宽积压预算。

新增独立 `ReceiveQueueClockRaceTest.kt` 四项回归：worker 较早时间快照、
生产者较早时间快照、真正旧包与更新到达并存，以及在真实 `PlaybackSession`
路由查询中插入新包的连续播放链路。四项修复前全部失败，修复后与原有队列
测试一起通过。生产文件仍少于 150 行，单一队列职责，无需拆分或 API 迁移。

已保留第一轮节拍器修复，两个错误分别处理短停顿吞吐率和并发错误丢包，
不能把其中一个当作另一个的替代品。新包保留应用数据覆盖安装，签名与本次
调试安装一致；后续录音与计数对比如下，不预先宣称实机听感已解决。

录音、调试器辅助程序、采样日志、APK和原始设备转储全部位于忽略的
`build/audio-device-debug-2026-11-05/`，不纳入 Git，不上传语音或日志。
误采的私密 UI 截图已删除，后续不读取其他应用的界面。
常规 APK 没有新增录音能力、隐藏录音开关、诊断生产分支或持久化设置。

### 覆盖安装后的录音与计数

保留用户数据覆盖安装 Debug APK，并核对设备安装包与本机构建产物的
SHA-256 一致：

```text
5a155c0e972cc879cc979a15f71d9bd824be43a95492ef2ff487b6ec31ca116b
```

版本号仍为 1.0.0 / versionCode 13，不以版本号代替构建指纹。此次仅安装调试版，
第二轮未重建或安装 Release APK。用户重新连接后，完成两段约 30 秒的有效
播放复制录音；第二段不附加 JDWP 调试器，不设置断点。

| 指标 | 队列修复前，保留节拍器修复 | 队列修复后，伴随计数采样 | 队列修复后，不附加调试器 |
| --- | ---: | ---: | ---: |
| WAV 时长 | 30.101 秒 | 29.973 秒 | 29.931 秒 |
| 采样率 / 声道 / 位深 | 48 kHz / 2 / 16 bit | 同左 | 同左 |
| 峰值 | 24051 | 22555 | 27932 |
| 非零样本 | 1751588 | 2305056 | 1597568 |
| RMS | -32.97 dBFS | -29.75 dBFS | -30.22 dBFS |
| 至少 20 ms 的全零段 | 7 段，含首尾 | 仅开头 448 ms | 仅开头 149.33 ms |

两段修复后录音都不是空录音，启动静音后未发现不少于 20 ms 的全零段。
不同录制窗口没有同源逐样本参考；自然停顿、声源变化和系统采集行为均可能影响
波形，不把零值段数量当作严格的丢包率，也不据此宣称主观听感或扬声器输出
已经完全恢复。播放复制录音不是麦克风对扬声器的声学测量。

第一段修复后采样中发现两个 `ReceiveStats` 实例，一个始终为零，不能拿该实例
代表正在播放的会话。活跃实例共 31 个样本，首尾约 34.63 秒：

| 活跃会话指标 | 首样本 | 末样本 | 增量 |
| --- | ---: | ---: | ---: |
| `stalePackets` | 1 | 12 | +11 |
| `budgetResets` | 3 | 3 | 0 |
| `invalidPackets` | 12 | 17 | +5 |
| `plcPackets` | 1 | 1 | 0 |
| `outputFailures` / `focusDenials` | 0 / 0 | 0 / 0 | 0 / 0 |

`stalePackets` 的 +11 集中于约第 31.38 秒的一个采样间隔，前 30 秒样本
未增长；但没有逐包包龄证据，不能把这 +11 武断归为真正超时、录制结束、网络
或调试干扰。两个管线的 `resetDroppedPcmSamples` 增量分别为 0 和 5280。
修复前窗口增量是 +54 个过期包、+35040 个重置丢弃 PCM 样本；两次窗口时长
和输入不相同，旧日志也没有逐样本耗时，不能直接换算精确改善百分比。
新增规则消除了负包龄误判，但没有关闭真正超过 60 ms 的过期保护。

第二段无调试器录制的设备前后快照显示，同一条活跃 TS3 AudioTrack 的
`Underruns` 列从 2208 增至 4128（+1920），采样率 48 kHz，输出缓冲 3844 帧，
两个快照延迟为 37 / 41 ms。AOSP `Track::appendDump()` 的该列来自
`AudioTrackServerProxy::getUnderrunFrames()`；它是累计欠载帧统计，不是
1920 次断音，也不能直接等同于一次 40 ms 的可闻中断。此值并非零，且手机的
厂商实现未经源码核对，因此仍需关注设备调度 / 输出欠载，不以应用输出失败
计数为零替代硬件验收。未为消除该统计而盲目增加缓冲或放宽接收预算。
字段定义参考 [AOSP Tracks.cpp](https://android.googlesource.com/platform/frameworks/av/+/master/services/audioflinger/Tracks.cpp)。

对应本机证据为 `system-duplicated-after.wav`、`live-receive-stats-after.log`、
`system-duplicated-after-clean.wav`、`audio-flinger-{before,after}-clean.txt`，均在
上述忽略目录。录制进程和采样调试器均已退出，JDWP 端口转发已清理；应用保持
运行，不操作连接和其他应用。实机听感仍需用户确认，未覆盖发送语音、蓝牙、
长时间后台与高负载场景。

### 第二轮修复的完整验证

四项新增队列并发回归测试在旧逻辑上全部失败，在统一过期判定后全部通过。
再次强制执行与第一轮相同的完整质量门禁，禁用缓存：共 186 个任务全部执行，
耗时 45 秒，结果如下。未复用第一轮 558 项测试来充当第二轮结果。

| 检查 | 第二轮结果 |
| --- | --- |
| Kotlin 格式检查 `quality` | 通过 |
| 协议 / 音频 / 应用 JVM 测试 | 129 / 194 / 239 通过，共 562，0 失败 / 错误 / 跳过 |
| 音频 Debug Lint、应用 Debug / Release Lint | 通过；原有应用 3 个 Warning 未修改 |
| Debug APK 构建与覆盖安装 | 通过，并核对已安装 APK 指纹 |
| 生产与测试源码 SHA-256 | 完整门禁后复核一致 |
| 仓库隐私扫描 | Python 等价扫描 586 个仓库文件通过；本机没有 `pwsh`，未直接运行 PowerShell 脚本 |
| 文档链接 / 空白检查 | 52 个本地链接通过；`git diff --check` 通过 |

日志与前后 JUnit XML 分别保存在本机忽略目录的 `full-gates.log`、
`build-clock-race-fix.log`、`reproduction/`、`fixed/`，源码指纹为
`source-tests.sha256`。本次未修改依赖、配置、公开 API 或应用权限。

## 第一轮离线验证命令和剩余风险

使用仓库已有 JDK 17 / SDK，通过环境变量选择本机工具链，不修改配置文件。
以下结果为本次执行；`UP-TO-DATE` 不计作新的测试执行。

针对性验证：

```sh
./gradlew --offline --no-daemon --max-workers=2 :audio-opus:testDebugUnitTest \
  --tests '*PlaybackPacerTest' --tests '*PlaybackSchedulingRegressionTest'
```

修复前 4 项测试中 3 项失败，修复后初始 5 项测试通过。随后增加包时长和
停顿边界覆盖，新增测试最终为 7 项，全部通过。

完整门禁强制重新执行，禁用构建缓存：

```sh
./gradlew --offline --no-daemon --max-workers=2 --no-build-cache --rerun-tasks \
  quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug \
  :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew --offline --no-daemon --max-workers=2 :app:assembleDebug :app:assembleRelease
```

| 检查 | 本次结果 |
| --- | --- |
| 全模块 Kotlin 格式检查 `quality` | 通过 |
| 协议 JVM 测试 | 129 通过，0 失败/跳过 |
| 音频 JVM 测试 | 190 通过，0 失败/跳过，包含新增 7 项 |
| 应用 JVM 测试 | 239 通过，0 失败/跳过 |
| 音频 Debug Lint | 通过，0 问题 |
| 应用 Debug / Release Lint | 通过，各 3 个 Warning，无 Error/Fatal |
| Debug / Release APK 构建 | 通过；Release 为 unsigned，不是正式签名发布 |
| 仓库隐私扫描 | 通过 |
| 已修改文档本地链接检查 | 52 个链接通过 |
| `git diff --check` | 通过 |

完整质量门禁共 186 个任务，本次全部执行，耗时 49 秒。构建 160 个任务中
29 个执行、131 个复用最新输出，耗时 35 秒；不把复用构建输出算成新测试。
Lint 的三个 Warning 位于未修改的字符串资源：两个 `PluralsCandidate`、一个
`UnusedResources`。Release 构建还输出依赖保留规则的默认构造函数提示，以及
`sun.net.spi.nameservice.NameServiceDescriptor` 缺失 service 元数据警告；本次不改动
这些无关规则，也不把成功构建等同于通过设备上的压缩 APK 回归。

本机证据位于忽略的 `build/audio-regression-2026-11-05/`：保存修复前后 JUnit XML
及生产/测试文件 SHA-256。测试后核对指纹一致。命令日志为同级
`audio-regression-{reproduction,fixed,gates,build,privacy}-2026-11-05.log`。

第一轮离线验证时 `adb devices` 没有连接设备，未执行 instrumentation、真实
AudioTrack 或压缩 APK 设备回归。之后的实机采样范围见上一节；完整验收仍需验证接收语音、发送语音、
前后台与负载切换。
此次 fake output 不测量实际 AudioTrack 欠载或硬件缓冲深度；短暂停顿仍可能
造成瞬时硬件欠载，修复保证的是不把短暂停顿永久转化为媒体速率不足与周期性
预算丢弃。若症状只出现在“别人听我的声音”，还需要单独采集发送侧证据，
不能用播放端回归宣称其已解决。

不要把真实端点、身份、凭据或语音内容写入测试记录。
