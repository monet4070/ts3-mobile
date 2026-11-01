# 音频实现与可维护性复审

日期：2026-10-24。范围：A0–A4b 音频升级、审查修复、app 麦克风/播放接线、JNI、实验 APM、相关构建工具和测试。没有将工作区中无关聊天、书签等功能的差异计入本轮审查，也没有逐行审计第三方编解码器源码。

后续处置：以下问题与建议已纳入[补充修复验收](AUDIO_REVIEW_FOLLOWUP_2026-10-24_ZH.md)。本文件保留审查当时的代码位置和复现结果，当前行号可能变化。

结论：保留现有接收队列、话者 pipeline、混音、时间线和 Android facade 的分层，但仍有七项需要修正。上一轮“经核实问题已全部修复”的结论过早：单路径回归通过，没有覆盖跨空闲、跨重启、参数变化和释放失败的组合。

本轮未修改生产代码、仓库测试源或构建配置，只新增本审查记录；最小探针与日志位于忽略目录 `build/audio-maintainability-review/`。

## 需要修正的实现问题

### F1 · P1 · stop 的超时并不能约束调用线程

位置：`audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusAudioPlayer.kt:114–125`。

`stop()` 先取得 `lifecycleLock`，再等待 worker 正在持有的 `outputLock`，取得后才将 running 置 false、close、interrupt 和 join。`playbackLoop()` 在同一 outputLock 内执行整个 tick，包括创建设备、解码和输出操作。输出阻塞时，stop 卡在进入 join 之前，连“禁止后续写入”和 interrupt 都未发出；同一锁也会阻塞静音/路由回调。

隔离探针直接调用已有编译产物中的真实 `OpusAudioPlayer.stop()`：让模拟 worker 持有 outputLock，1500 ms 后 stop 线程仍为 BLOCKED、running=true、workerInterrupted=false。为避免创建 Android 音频设备，探针通过反射构造运行上下文；这验证锁顺序，不是一次真实 HAL 卡死实验。

建议：调用线程先推进失效代次并置停止状态、interrupt，再有限等待 worker；输出资源由 worker 负责退出清理。超时保留旧资源所有权并禁止新代次，不能让主线程等设备锁。新增“worker 持锁时 stop 仍在期限内返回”的回归。

### F2 · P1 · 调整音量会反复重启 RNNoise 并插入空白块

位置：`OpusMicrophoneCapture.kt:248–252`；`MicrophoneCapturePipeline.kt:24–29`；`app/.../ui/MicrophoneSettingsDialog.kt:48–50`。

任何 options/revision 变化都进入同一 onConfiguration，销毁/重建 RNNoise 和 Opus encoder。音量滑块每次变化都会更新设置，不只是路由切换才触发。默认开启 RNNoise 时，这意味着普通音量调整也重复清除降噪器的延迟和 RNN 历史。

使用仓库内实际 RNNoise 源码和模型、与生产一致的 portable 编译配置进行主机合成信号验证：稳定输出峰值约 12930.8；destroy/create 后首帧 480 个样本全部为零，即 10 ms 空白。JNI 转 PCM16 后这段零值仍成立。这里没有测量真人语音或真机听感。

建议：区分参数修订、路由代次和采集生命周期。音量只更新增益目标；gate 阈值按门控规则更新；在明确需要重置声场时重置降噪状态，避免无关设置重建编码器。测试应覆盖持续非零输入下连续调节音量，不只核对假回调次数。

### F3 · P1 · 零散输出错误会累积到会话永久停止播放

位置：`PlaybackSession.kt:65–74、81–94、125–127`。

错误计数只在同一个输出生命周期持续成功满一秒后清零。每次自然结束又清除 healthySince。对于每次不足一秒的正常语音，成功播放再多次也不能清掉历史错误，“连续失败三次”实际可能成为“整次连接累计三次”。到 FAILED 后 start() 看到 worker 仍 running 会直接返回，下一批语音也不会自行恢复。

虚拟时间探针中，三个创建错误分别发生在相隔十秒的位置；前两次之间各成功播放三段 600 ms 语音，总计成功提交 172800 样本（3.6 秒），第三次仍进入 FAILED，recovered=0。

建议：明确断路器的恢复规则，例如累计成功播放量或故障时间窗口，使短 talkspurt 也能证明恢复；限制连续重建失败，不把健康短语音之间的旧错误无限累计。补“多段短语音恢复后再故障”的回归。

### F4 · P2 · 参与者静音遗漏时间线的未完成混音块

位置：`OpusAudioPlayer.kt:90–95`；`PlaybackSession.kt:69–71`。

参与者静音只标记 discontinuity 并清队列/话者；已经进入 PlaybackTimeline.pending 的 PCM 是最终混音，与原话者不再关联。当 acceptsPcm=false 时，下一 tick 不重新混音，直接继续写旧 pending。总播放静音有 session.invalidate，但参与者静音没有对应边界。

探针先让设备返回零接受，留下 480 样本 pending；按生产路径设置参与者 gain=0、标记 discontinuity、removeClient，再允许写入。下一 tick 仍提交 480 个值为 1000 的旧样本。这不是设备已经接收的硬件尾音，而是静音后应用继续提交旧 PCM。

建议：参与者静音在输出代次处立即失效未完成混音及必要的已混合队列，并与 worker 串行确认；需要明确其他话者在此边界的短暂重缓冲行为。补部分/零写入期间的参与者静音回归。

### F5 · P2 · 输出释放失败后，跨 stop/start 的保护不完整

位置：`PlaybackSession.kt:116–134`；`OpusAudioPlayer.kt:42、173–181`。

close() 在实际释放之前先设 closed=true。retireOutput() 失败会保留 output 并标记 FAILED，但再次 close() 会立即返回，不能重试退休。随后播放器 finally 清掉 control/worker；新的 start() 只检查旧线程是否存活，没有保留“旧输出尚未确认释放”的状态。单个 PlaybackSession 内禁止替换，不能保证跨会话仍成立。

探针令 close 第一次抛错，随后恢复可关闭能力再调用 close，结果旧 output.closed 仍为 false。实际 Android 释放失败后资源是否仍活动取决于失败位置；本项不把所有 release 异常都等同于仍在发声，而是指出无法确认退出时保护状态被丢失。

建议：区分停止请求、退休中、已退休、退休失败，保留失败资源的所有权与可重试清理入口；只有确认退休才允许新的输出生命周期。补 stop/start 穿过释放失败的 facade 回归，而不只测试单个 session 内的重试限制。

### F6 · P2 · 限幅包络在完全空闲阶段被冻结

位置：`PlaybackSession.kt:47–48`；`ParticipantGainMixer.kt:32–34`。

空 mix tick 的 advanceSilence 已正确实现，但输出自然排空后，等待起播的路径会在调用 streams.mix 之前返回。自然空闲期间不再推进限幅器，旧衰减会保留到下一次发声。

探针先混合八路大信号、正常自然结束，五秒后输入新的低电平 1000，恢复的首样本仅为 610，约 -4.3 dB。空拍与等长静音等效的单独 mixer 测试无法覆盖 session 的提前返回。

建议：明确以实际空闲时间或等效样本推进释放，或定义自然结束时的适当状态处理；不能只在 mix 被调用时才经过释放阶段。补重过载→自然 IDLE→长空闲→新话者起声的 session 级回归。

### F7 · P2 · APM 工具链变更未使 Meson 缓存失效

位置：`tools/build-apm-experiment.py:133–153`。

脚本会从 Gradle 读取新 NDK/minSdk 并改写 cross-file，但只在 build.ninja 不存在时 setup。Meson 缓存的 compiler 不会仅因改写 cross-file 自动换成新的 compiler；有 build.ninja 时直接 compile 可能仍然使用旧工具链。

使用项目锁定的同一 Meson，在最小 C 工程中先配置 compiler-one，再把同一 cross-file 改成 compiler-two 并执行 compile。最终 cross-file 指向 two，但重新生成的 build.ninja 仍调用 one。当前 SDK 未升级，因此不是当前 APK 已混入错误 ABI 的证据；这是后续升级时可复现的构建选择错误。

建议：将源码锁、ABI、NDK/CMake、minSdk、实际编译器路径及构建选项纳入构建指纹。变化时创建新构建目录或显式 wipe/reconfigure，验证 Meson 最终采用的 compiler。新增已有 build cache 下的升级回归；现有 Python 五项测试只验证配置读取/目录选择。

## 代码卫生和可维护性

1. **close 与 reset 的语义混用。** `PlaybackStreams.close()` 清空后仍允许 ingest 并创建 decoder，`PlaybackSession` 在焦点拒绝、invalidate、恢复时都复用它。`PlaybackStreamsTest.kt:76–78` 也把 close 后复用作为正常路径。建议显式使用 reset/clear，close 只表达生命周期结束，使资源退休路径能被直接审计。
2. **采集资源清理重复且不完整。** `OpusMicrophoneCapture` 的构建失败、初始化失败、开始录制失败、监听注册失败和 finally 重复释放相同资源；部分 `record.stop/release` 异常被无记录地吞掉，后续原生 close 若抛错会跳过剩余清理与控制状态更新。建议让一个小的采集资源协作者负责构造回滚和聚合清理，逐项 finally，日志保持固定类别。这里建议的是整理资源所有权，不是扩展为通用音频框架。
3. **热路径重复快照。** `VoiceReceiveQueue.prepare()` 已复制 payload 并解析，offer 又复制一次；备用入口会重新解析另一份临时副本却丢掉该副本。建议统一“已拥有的已验证包”的入口，保留一次所有权转移、一次解析；避免为了整理而新增不必要的工厂层次。
4. **死成员、命名与参数集中尚未收尾。** `AndroidPlaybackOutput.setVolume()` 已无调用；`OpusAudioPlayerTest` 实际只测序号距离；`ApmExperimentWorker` 的 100 ms、连续 10 次、5 ms、连续 3 次阈值仍硬编码，未进入 ApmPolicy。分别删除死成员、按实际被测模块命名、为不同语义的阈值定义独立名称，不因为数值相同就共用一个配置。
5. **测试侧重单次路径，组合覆盖不足。** 应优先将上述复现转成独立回归，尤其“成功后再失败”“有 pending 时静音”“释放失败后重启”“设置变化保持处理连续性”。无需为低影响重命名机械增加测试；也不应把所有 IllegalStateException 都当作并发关闭的预期结果而不检查发生阶段。

## 本轮验证与边界

- 使用项目 JDK 17 运行 `quality`，并用任务级 `--rerun` 真正重跑 `:audio-opus:testDebugUnitTest`、`:app:testDebugUnitTest`、`:ts3-protocol:test`：161 + 226 + 126 = 513 项，0 失败/错误/跳过。
- Python 配置工具五项测试、隐私扫描和 `git diff --check` 通过。
- 五个 Java 最小探针使用当前已编译生产类和 fake 输出/解码器；不是改写一份相似算法来猜测结果。日志为 `build/audio-maintainability-review/probes.log`，源为同目录 ReviewProbe.java。
- RNNoise 探针使用本地 vendored 源码和合成调制谐波，结果为同目录 `rnnoise-reset.json`。Meson 复现见 `meson-cache-probe/result.json` 和 run.log。
- 本轮没有重新启动模拟器，没有重跑 Android Lint、APK 构建、仪器测试或 Release probe，也没有真人录音、真机 HAL 故障注入、服务器连接。上一轮 Android/Release 结果保留为既有证据，不能替代上述缺失的组合用例。

建议先修 F1–F5 的输出/采集生命周期与连续性，再处理 F6/F7，并以同一轮整理 close/reset、资源清理和测试命名。完成后重新跑受影响 Android/Release 门禁，再进入 A5 真机验收。
