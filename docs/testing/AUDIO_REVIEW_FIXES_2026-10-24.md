# 音频审查修复记录

日期：2026-10-24。设计与逐项处置见[修复方案](../plans/AUDIO_REVIEW_FIXES_2026-10-24_ZH.md)。本轮没有提交或发布；保留工作区原有功能改动。

证据更正：本文件的最终 gate 日志中协议测试为 UP-TO-DATE，下面 JVM 数字包含旧结果复用，不能作为该轮全量重新执行的证明；协议预算测试还存在真实时钟相关断言缺陷。新测试及强制执行证据见[更正验收](AUDIO_EVIDENCE_CORRECTION_2026-10-24_ZH.md)。旧证据保留，不覆盖历史日志。

## 生产代码

- `PlaybackSession` 集中处理起播、焦点退避、输出失败与资源退休。写入超时或负返回会先关闭旧输出，再以 250/500 ms 退避等待新媒体；连续三次输出失败进入可读 `FAILED` 状态。持续成功写入一秒后清连续失败计数。焦点拒绝单独指数退避，上限两秒，退避期间不创建 decoder。
- 输出释放失败时禁止创建新输出；保留旧 worker 存活时禁止重启的保护。`PlaybackPacer` 在落后时至少等待一毫秒。Android facade 保留焦点、实际路由回调和线程所有权，输入解析移出生命周期锁，入队重新检查代次。
- `MicrophoneVoiceSource` 在调用线程立即关闭发送代次；`MicrophoneController` 在 IO dispatcher 和同一 mutex 下释放采集资源。启动需要发送代次 CAS，快速关开不会放行旧启动。关闭模式先关闭端口，不等待 capture 配置锁。
- 设备偏好、输入实际路由、输出路由通知均失效采集缓存；只变输出、输入偏好仍为 null 也会生效。`CaptureInputAssembler` 隔离跨路由读取的半帧，pipeline revision 清起声、组帧、增益/限幅历史，重置 RNNoise/编码器并重配效果。
- 空混音拍按样本数推进限幅 hold/release。媒体预算记录未消费 lease，以最旧媒体到达时间淘汰；PCM FIFO 显式限制为 240 ms，超限整块丢旧并计数/释放预算。去重历史继续独立有界，不将它当作媒体淘汰。
- FEC 异常计数并重置 decoder，从下一真实包继续、短淡入；不继续调用未知状态的 PLC。重置成功且下一包有效时不移除话者；重置或正常解码仍失败时保留安全丢弃该流的边界。默认 TS3 仍不假定固定帧长、不启用 FEC。
- Opus encoder/decoder、RNNoise、实验 APM 的 JNI 调用与 close 使用实例监视器；实验 pipeline/worker 生命周期也串行化。参考组帧继续单写者模型，跨线程失效仅推进原子代次。
- 接收/APM 阈值集中配置，移除重复序号转发和无生产用途成员。会话结束输出固定类别累计计数和年龄高水位，启动记录 codec 版本；无身份、地址、PCM 或协议载荷。
- RNNoise JNI 两个逐调用 vector 改为固定 array，未改上游源码或模型。

## 测试

新增/扩展独立回归覆盖：播放零写/负返回、重建失败上限、释放失败禁止重叠、焦点退避、长 tick 调度、强制失效与关闭；阻塞 fake stop、快速关开、输出单独路由变化；采集半帧、旧起声/限幅、效果重配置；最旧媒体淘汰、FIFO 超载预算释放；FEC 修改状态后抛错必须先 reset；自适应目标跨 discontinuity/gap/完整重置保持；参考跨线程失效；真实 JNI 并发 close/use；工具缺失/歧义/非默认版本。

最终结果：

|检查|结果|
|---|---|
|历史 JVM 汇总（含旧结果复用）|161 / 226 / 126，共 513 项；撤回“该轮全部重新执行并通过”的表述|
|默认音频 Android|26 项通过，含真实 JNI 并发关闭|
|fake 麦克风 Controller Android|9 项通过，含阻塞停机、快速关开和输出单独切换|
|实验 APM Android|9 项通过，含并发 close/use；随后恢复默认构建|
|Python 配置/工具|5 项通过|
|格式化 / quality / Kotlin 编译|通过|
|Android Lint|audio-opus 0 项问题；app 0 错误、36 项既有警告|
|Debug / 压缩 Release|通过；R8 仍有依赖已有规则/服务描述符警告|
|真实 Release codec probe|arm64-v8a，20 个生命周期 × 10 帧；JNI 返回 libopus 1.6.1|
|真实 Release identity / INI probe|通过|
|原生 / APK 对齐|自有 JNI 四 ABI 的 LOAD ≥16 KiB；包内全部 64 位原生库 ≥16 KiB；zipalign -P 16 通过|
|默认包 / 隐私 / 文档链接 / diff 空白|无 APM 库；隐私扫描、受影响本地链接及 git diff --check 通过|

Android 运行环境为 API 35、arm64-v8a 模拟器。默认音频 Android 回归和 fake 麦克风测试只使用合成 PCM、虚拟时间与本地模拟器，没有录音或连接服务器。NDK 自带 32 位 libc++_shared 的 LOAD 为 4 KiB；16 KiB 系统页兼容检查针对 64 位库，自有 JNI 的 32 位构建也已使用 16 KiB。

命令沿用 CONTRIBUTING 的完整离线门禁，并分别选择 `NativeAudioLifecycleTest`、`MicrophoneControllerTest` 和实验 `NativeApmExperimentTest`。本地证据位于忽略目录 `build/audio-review-2026-10-24/`：`final-gates.log`、三个 `android-*` 结果目录、`release-codec.log`、`release-identity.log`、`python.log`、`privacy.log`、`elf-alignment.json` 和 `results.json`。

## 文档

同步 README 中英文、架构、贡献检查、Changelog、ADR-0018、升级方案状态和 A0 历史指纹解释。历史指纹当前 33/40 匹配：原 A3/A4a 的六项变化之外，新增 RNNoise JNI 固定缓冲一项；上游 RNNoise 源码/模型指纹仍匹配。

## 配置和工具

无新增依赖、网络格式或持久化设置。CMake 的生产 Opus 版本选择成为 Release 工具的来源，仍以锁文件哈希校验归档。Python helper 从 Gradle 读取 SDK/minSdk/NDK/CMake，并明确诊断缺失或歧义目录；Release probe 通过实际 JNI 返回值验证版本，去掉 `.so` 字符串 grep。

首次运行新版本 probe 发现 `nativeVersion` 被 R8 删除，因为它没有生产调用；改为启动诊断读取它，未加入测试专用保留规则。最终结果只采信重建后实际 APK 的验证。

## 未覆盖边界

- 不能从 Java 安全强杀永久阻塞的 OEM HAL；旧 worker/输出没有确认退出时继续阻止重叠资源，失败有诊断。
- 真机有线/蓝牙切换、声学 AEC 效果、主观听感、功耗和长期热状态仍需 A5。
- APM 仍为默认不打包的离线原型，实际采集双向时钟接线待单独验证。FEC 异常回归不证明真实网络中“坏 LBRR 但正常部分有效”的发生率。
