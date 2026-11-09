# A5：输入电平与调音中的正常 PTT

日期：2026-11-07。范围：离线、合成 PCM、fake capture 与 API 35 arm64 模拟器；不录音、不连接服务器。设计及大文件评估见 [ADR-0026](../decisions/0026-live-microphone-levels.md)。本记录区分完整套件、最终差异补验和合成性能边界。

## 实现

- 复用降噪／增益后、limiter 前的 `VolumeGate.rmsDb`，PCM16 饱和定义一致。Gate 仍按原 40 ms attack、6 dB 回差、300 ms hold 判断；非 Gate 模式仅观测时新增 RMS。打开观测不改 PCM、编码或 onset／queue。
- `MicrophoneLevelMeter` 只保留最新标量快照，不保存 PCM／轨迹，单调时间每 100 ms 最多发布一次。采集 generation、处理 revision、route revision 共同比较，停止／路由／设置／末订阅关闭立即清空，晚到结果不得重新发布。
- 音频模块通过 closeable subscription 提供读值，不新增协程依赖；应用层 cold Flow 每 100 ms 读取，仅指示器局部按 lifecycle 收集。取消会 finally 关闭订阅，不进入 coarse capture state／整屏／前台通知更新链路。
- 对话框内新增有明确“发送到当前频道”提示的正常 PTT，不是本地试音；打开设置不采集。松开、手势取消、失焦、关闭、Activity 暂停释放 PTT；常开／门控有停止按钮。
- 指示器显示 dBFS 和真实门控／采集／发送状态；屏幕阅读器只得到稳定状态摘要，无逐帧 live announcement。门限仍由同一设置滑杆显示。

## 验证方法与过程

独立纯 JVM 回归覆盖静音 -96 dBFS、饱和、高增益限幅前测量、真实 Gate 时序、观测开关的 PCM／起声输出一致性、降噪后完整 20 ms 测量、10 Hz 与设置快速变化、无采集订阅、末订阅清空以及过期代次拒绝。

Android 使用 fake capture 验证 cold collection／取消不触发 capture I/O，合成标量到 Flow 延迟；Compose 验证不采集、真实按下／松开／cancel、原生覆盖 Dialog 引起的失焦、关闭、Activity lifecycle 订阅／释放、门控状态、停止、断线及中文 1.8 倍字体。单独检查标量更新只重组指示器、无父级重组及 250 ms 的发布到可见目标。

性能探针运行真实 Opus 和合成 1 kHz PCM，分别关闭／开启真实 RNNoise，先预热，每条件 2,000 个 20 ms 块；记录聚合 p95、线程分配计数和编码帧数。它证明模拟器上的合成处理预算，不能证明音频 HAL、物理欠载、端到端麦克风延迟或听感。

首次完整回归发现把失焦释放放在公共 PTT 控件会使其他弹窗多发一次停止回调。最终设计收回到调音对话框，保留其他页面原行为，而不改旧测试来掩盖影响。采集停止路径也复核为不能等待持有 AudioRecord 初始化的 lifecycle lock；通过快照锁内再次检查活动资格防止 configure／stop 竞争后复活旧电平。

## 最终结果

| 检查 | 最终结果 |
| --- | --- |
| 完整 JVM | app282、audio204 全部通过，两任务明确 `--rerun`；音频新增10项 |
| 完整 Android | XML共178项，177通过、0失败；1个通知策略专用fixture默认跳过，A4已外部验收 |
| 最终发送边界补验 | 27项通过：电平UI、调音UI、MicController与观测取消；新增1项防止缓冲就绪误报发送 |
| 离线 native | 真实Opus／起声／收尾／连续性、PlaybackLifecycle、基准共10项通过 |
| 默认降噪性能补验 | 新版合成基准1项通过，RNNoise开／关各与观测开／关配对；最后独立导出记录 |
| 格式／Lint／构建 | quality、audio Lint Debug、app Lint Debug／Release、Debug／minified Release均通过；app仍为原3 warning、1 information |
| 其他 | 隐私脚本、Python工具9项、YAML、文档相对链接、whitespace通过 |

完整套件通过后，最终复核确认“capture缓冲可发送”不能代替应用 `MicrophoneVoiceSource` 的发送资格。
仅对 `MicrophoneLevelIndicator`／`MicrophoneTuningCapture` 增加coarse发送资格参数，及对应独立UI回归；
再次执行27项相关Android、quality、双变体Lint和两种APK构建，全部通过。没有把先前完整套件当成此差异的重跑结果。
性能测试另外扩充默认RNNoise组合并独立执行，生产音频源码保持完整门禁时的版本。

| 合成处理条件 | 观测关闭 p95 | 观测开启 p95 | off／on线程分配计数 | off／on编码块 |
| --- | --- | --- | --- | --- |
| RNNoise关闭 | 0.152 ms | 0.164 ms | 14,269／16,008 | 2,000／2,000 |
| RNNoise开启 | 0.276 ms | 0.286 ms | 18,002／20,014 | 2,000／2,000 |

数据来自最后独立执行，单位和分配计数按Android Debug接口原样记录；分配包含整个合成pipeline，不仅是电平快照。
每个处理条件预热400块；生产标量最多10 Hz分配最新快照，不逐帧创建协程或新增PCM数组。
均满足本探针20 ms块预算，但不能外推成低端实体设备性能／HAL欠载通过。
合成标量到Flow收集为80.22 ms；Flow发布到可见指示器为79.14 ms，父级重组0次；两段各自满足250 ms目标，
加上生产最多100 ms的发布间隔仅构成设计预算，不冒充实际麦克风端到端延迟。

复现完整门禁：

```sh
source build/improvement-2026-11-07/env.sh
./gradlew --offline --max-workers=2 :audio-opus:ktlintFormat :app:ktlintFormat
./gradlew --offline --max-workers=2 quality :audio-opus:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :audio-opus:lintDebug :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
```

原始证据在忽略目录 `build/improvement-2026-11-07/`：

- `a5-full-gate-final.log`、`a5-full-results/`：最终完整门禁；263 actionable tasks，86 executed／177 up-to-date；XML为实际测试数，不采用Gradle控制台“Finished179”作为总数。
- `a5-final-source-sha256.json` 与 `a5-delivery-source-sha256.json`：474个生产／测试／构建文件，最后差异仅两处UI生产接线、一个UI测试及纯测试benchmark扩充。
- `a5-send-boundary-final.log`：最终27项补验、双变体Lint／构建；`a5-audio-offline-final.log`：离线native10项。
- `a5-benchmark-default.log`、`a5-benchmark-final-instrument.log`、`a5-final-microphone-level-benchmark.txt`：RNNoise开关的最终合成基准。
- `a5-final-preview-tests.log`：最终电平／观测11项再次执行；`a5-microphone-{poll,ui}-latency.txt`为聚合时延，不存电平轨迹。
- `a5-tuning-zh-large.png`：中文1.8倍深色调音/PTT/关闭按钮，已目视查看；另有自动滚动到阈值并断言可见的覆盖。

设备为本任务API35／Android15／arm64-v8a／4KiB模拟器。没有打开AudioRecord、没有模拟器音频输入或线上服务器。
独立native测试直接运行合成信号；不可将全套audio androidTest无差别运行，因为仓库还含需显式选择的录音实验。

## 交付分类与边界

- 生产：audio-opus 的共享测量与标量订阅；app cold Flow、局部电平与调音 PTT；既有服务／Activity 只接线。
- 测试：独立音频 JVM、合成 native 性能／连续性、fake capture、Compose 安全和生命周期测试。
- 文档：ADR、本记录、双语 README／指南／隐私、架构与进度。
- 配置：CI 加入离线 PCM 与电平／订阅回归；不增依赖、权限、版本或存储格式。

实体麦克风、OEM、蓝牙／USB 物理路由、HAL 欠载和采集端到端听感仍未验证。R2 负责第二批组合与覆盖升级，不用 R1 结果代替。
