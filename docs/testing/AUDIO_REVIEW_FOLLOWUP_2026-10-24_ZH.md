# 音频复审补充修复验收

日期：2026-10-24。[实施方案](../plans/AUDIO_REVIEW_FOLLOWUP_2026-10-24_ZH.md)。范围包含外部六项意见与[维护性复审](AUDIO_REVIEW_MAINTAINABILITY_2026-10-24_ZH.md)七项复现，保留所有无关工作区改动；没有提交、发布、录音或连接真实服务器。

证据更正：本文件原先的 520 项来自包含 UP-TO-DATE 任务的结果汇总，不能宣称是该轮全量重新执行。协议退出预算断言依赖真实时钟，存在跨机器不稳定性；原“门禁全绿”的验收表述撤回。新实现、可控时钟回归及源码绑定的强制执行结果见[更正验收](AUDIO_EVIDENCE_CORRECTION_2026-10-24_ZH.md)。

## 外部报告真实性

|意见|核对与处置|
|---|---|
|失败状态无人消费|成立。PlaybackStatusController 将状态接到服务 StateFlow、主页面提示、通知和 PLAYBACK_FAILED 诊断；恢复状态自动撤销提示|
|异步关闭/前台类型过早移除|成立。立即关闭发送并 requestStop，阻塞释放仍在 IO/mutex；正常路径仅实际释放后移除前台类型。销毁保留独立完成回调，服务 scope 取消后清理仍继续，等待过长记录 MICROPHONE_STOP_TIMEOUT|
|实际路由不重配效果器|原判断不成立：revision 原本就会触发 pipeline 的 onConfiguration。状态入口不一致成立，现统一推进路由 revision、PENDING 并在 worker 重配|
|每次设备列表更新清缓存|成立，但并非每次一定推进两次 revision。改为比较 selectedRouteId 与解析后的输入设备 id，输入偏好变化由 capture 失效，只有输出选择变化则显式失效；实际输入/输出路由回调仍独立有效|
|死计数/重复拷贝/统计同名|成立。删除 PlaybackStreams 的只写计数；准备包作为已拥有快照入队，不再重复复制；区分 resetDroppedPcmSamples 与 overflowDroppedPcmSamples|
|设备门禁文档缺失|成立。CONTRIBUTING 写入 NativeAudioLifecycleTest、PlaybackLifecycleTest、MicrophoneControllerTest、PlaybackFailureTest；CI 接入对应类别与 Python 回归|

26/9/9 的既有结果是 arm64-v8a 模拟器证据，不能称为真机测试。discontinuity 在原实现就不改 target，未重复“修复”该误报。

## 生产代码与实现边界

- 播放 session 为 worker 独占；主线程/焦点/路由控制不等待输出锁，先使代次失效。时间线每次 write 前复核 running 和代次。主线程 stop 不 join；后台最多 join 一秒。旧输出未退休则保留 control，后续 stop 可以重试，start 仍拒绝重叠。
- Android 12+ 即使设备偏好仍为 null，也比较选中输出 routeId；失败后切换输出会触发重试。设备对象使用 id 比较，避免枚举生成不同包装对象导致无关失效。
- 参与者静音/解静音使最终混音代次失效，覆盖 pending 的部分写入块；其他话者在此边界短暂重缓冲。已进入设备的硬件缓冲、正在进行中的一次 native write 无法从 Java 撤销；应用不再开始后续旧代次写入。
- 用跨多个输出生命周期累计成功接受的 48000 样本清失败 streak，失败时清成功量。六段健康短语音之间的历史错误不会累积到永久失败。连续创建/写入失败仍有上限及退避。
- 正常排空后的空闲时间推进限幅释放，不冻结到下一次发声。超过一秒的空闲按一秒推进，误差已远小于 PCM16 分辨率；不向设备追加静音。
- CaptureResources 统一构造回滚、effect、record 和两个原生处理器清理。资源保存到 owner 后才开始录音，失败且未释放时仍可重试清理。独立清理尝试不会因前一个异常跳过其他资源。采集 facade 从 350 行缩至约 280 行。
- 声场 route revision 与设置 revision 分开。音量更新保留 RNNoise/编码器历史、20 ms 组帧和增益斜坡；清过期发送缓存及门控起声历史。门控阈值/模式变化仍重置门控，路由或 AEC/NS 意图变化重配处理器。非零音量调整不重置限幅包络。
- PlaybackStreams.reset 表达可复用清空；close 终止生命周期并阻止新 ingest。移除无调用 setVolume，序号距离测试以实际模块命名；APM 连续失败/CPU/时钟阈值有独立命名。
- Meson 指纹包括源锁/manifest、ABI、NDK/CMake/minSdk、compiler 路径/版本、cross-file 和构建选项。旧目录无指纹或内容不同执行 wipe/setup，setup 成功才写 stamp，相同配置复用。

## 独立回归与结果

|检查|结果|
|---|---|
|历史 JVM 汇总（含旧结果复用）|audio 166 + app 228 + protocol 126 = 520 项；不能作为全量重新执行证明，已更正|
|默认音频 Android|31 项，含停止被输出阻塞、参与者静音、跨 worker 释放失败、系统偏好仍为 null 时切换输出恢复，以及 RNNoise 参数连续性|
|app Android|11 项，含 fake 麦克风销毁/前台类型/快速关开及 Compose 失败提示/恢复|
|可选 APM Android|9 项，仍使用显式实验配置；之后恢复默认构建|
|Python 工具|7 项，含有缓存时的指纹变化与失败 setup 不写 stamp|
|Meson 实际缓存实验|先用 compiler-one 配置，再切 compiler-two；wipe 后 Ninja 实际使用 two，相同指纹不再次 setup|
|APM 实际 arm64 构建|旧目录无 stamp 触发完整重配编译，第二次相同配置无需编译|
|quality / 编译 / Lint / Debug / Release|通过；audio Lint 0 项问题，app 0 错误、36 项既有警告|
|压缩 Release probes|实际 JNI 版本 libopus 1.6.1、20 × 10 帧 codec，以及 identity/INI 回归通过|
|默认包 / 对齐 / 隐私|默认无 APM；四 ABI 自有 JNI 及包内 64 位库 LOAD ≥16 KiB，zipalign -P 16；隐私扫描及 diff 空白通过|

真实 RNNoise 参数连续性回归使用两份实际 native 实例处理相同合成输入：其中一份每个 10 ms 块更新音量，160 块降噪输出与不变设置的控制实例逐样本相同，完整产生 80 个编码块，处理器配置仅发生一次。

可复跑命令见 CONTRIBUTING。本地证据位于忽略目录 `build/audio-followup-2026-10-24/`，包括 results.json、final-gates.log、三个 android-* 目录、release probes、Python/隐私及 Meson 缓存记录。生产代码、仓库测试、文档和 CI/工具变更分别记录在本文件；无新增依赖、网络协议或持久化配置。

## 剩余设备边界

- Java 无法安全强杀永久阻塞的 OEM HAL。requestStop 立即停止发送和继续采集请求，正常 stop 完成前保留前台类型，超时明确诊断且禁止重叠资源。
- onDestroy 的框架生命周期不能由应用异步回调延长，也不能承诺其返回前硬件已经释放。本轮没有在主线程同步等待 HAL；保留释放完成回调和资源所有权，不把“异步关闭”伪装成“硬件已经关闭”。
- 真机蓝牙/有线/系统自动切换、AEC 听感、功耗及长时间热状态仍属于 A5；模拟器与合成测试无法代替。
