# 麦克风音量、处理开关与 Volume Gate 交付

日期：2026-10-23（Asia/Shanghai）。基于 `c83aa45051bdc32cf8a090c082363bca16e356fa`
及未提交的 F1a/F1b/Poke 工作区，按用户追加要求实现。本次继续离线验证，未连接真实
服务器、未发送真实消息或语音，未使用手机/模拟器的麦克风采集。无提交、发布或手机安装。

## 功能与入口

更多选项 → 麦克风设置：

- 输入音量 0–200%，默认 100%；PCM16 饱和保护防止整数溢出，放大仍可能产生可听失真。
- RNNoise 噪声抑制默认开启，可关闭并直接绕过；处理仍按 10 ms 块执行。
- 设备回声消除默认请求开启，展示待检测、已开启、已关闭、不可用或配置失败。
  只有 Android setEnabled 成功且 readback 一致才显示成功。失败时继续采集并明确提示。
- 音量门控阈值 -60 至 -15 dBFS，默认 -40；在主麦克风模式选择“音量门控”后生效。
  阈值测量点位于降噪与输入增益之后，RNNoise 概率不决定开门。

处理顺序为 VOICE_COMMUNICATION / 平台 AEC → 可选 RNNoise → 数字增益 → RMS 门控 → Opus。
可控制时关闭平台 AGC/NS，避免叠加两套降噪或自动改变手动增益。厂商底层处理仍可能不同。
设置仅保留在语音服务内存，连接切换保留，服务重建恢复默认参数和按住说话，不自动采集。

门控采用 40 ms 连续触发、6 dB 迟滞、300 ms 收尾、最多 120 ms 起声缓存（包含触发帧）。
门打开期间队列短暂为空仍维持讲话；关闭后先排空有界尾帧，再发送结束标记。
编码队列最多 9 帧 / 180 ms，包含起声积压；过载丢最旧帧。实际发送结束还受队列排空影响。
这些是首轮离线初始值，未校准真实房间/人声。

安静时显示“正在监听”，仍在使用麦克风并保留麦克风前台类型；发声后显示“正在说话”。
通知保留停麦操作，聊天紧凑栏也可立即关闭。已停止采集不能在后台自行重启，需要回到应用。
设置修改不启动采集；修改清除旧帧/半帧/起声缓存，旧 worker 的队列和回调不能影响新采集。
显式关闭停止采集并清空缓存。不增加录音文件、PCM 日志、依赖、权限或配置格式。

## 生产代码

- `audio-opus`：独立设置/采集状态与 capture port；纯 Kotlin `VolumeGate`、增益/起声选择、
  10 ms → 20 ms 管线和单次采集所有的有界队列；采集生命周期保持在 `OpusMicrophoneCapture`。
- `MicrophoneAudioEffects`：检查实际 AEC 返回值与 readback；尽力关闭平台 AGC/NS，脱敏报告失败。
- `app`：模式、权限、采集/发送分离、前台类型/通知、Binder/Activity 接线及独立可滚动设置弹窗。
  窄屏或大字体使用模式菜单，常规宽度保留分段选择。连接状态重建保留参数但不复制活跃采集标记。
- `ts3-protocol` 生产代码和 wire format 本轮未变；保留 ADR-0011 的 talkspurt 语义。
  旧 `OpusMicrophoneCapture(context, onFailure)` 构造形式保留，新状态回调采用新增重载。

拆分评估和兼容规则见 [ADR-0016](../decisions/0016-microphone-processing-and-volume-gate.md)。

## 验证

| 检查 | 结果 |
| --- | --- |
| JVM | 364 项通过，0 失败/跳过：协议 117、音频 45、App 202 |
| 纯音频策略 | 增益/静音/正负削波、RMS、连续触发、迟滞/收尾、起声顺序/容量、无输入别名、设置/修订重置、旧 worker 拒收、异常可见 |
| 实际 pinned ts3j 发送器 | 静音监听无包，起声有序，队列空隙不断句，尾帧发完后仅一个结束标记，可再次发声 |
| 完整离线 App 仪器测试 | 63 项通过，包含 6 项 fake 采集服务测试与 4 项麦克风 UI 测试；其他聊天/Poke/连接界面回归保留 |
| 320dp / 1.8 倍字体 | 麦克风设置与服务 10 项通过；中文滚动、滑块、状态提示和关闭按钮可见，截图已复查 |
| 最终控制器复测 | 最终 Debug APK 的 6 项 fake 采集测试通过，覆盖静音监听的前台类型、权限、后台延迟恢复、断开/失败、设置不启动、提升前台期间点击关闭 |
| 原生合成音频 | 6 项通过：生产管线接真实 RNNoise/Opus、旁路不改 PCM、起声/收尾/静音、既有降噪/实时预算及 60 秒编码解码样本量 |
| 格式/静态检查/编译 | quality、音频/App 单元测试、音频 Lint、App Debug/Release Lint、Debug/混淆 Release 与仪器测试包构建通过 |
| Lint | 0 errors；音频 0 warnings，App Debug 36 / Release 25 项既有 warnings |
| 混淆 Release 身份探针 | IDENTITY_ROUNDTRIP_OK、INI_STORE_ROUNDTRIP_OK、RELEASE_IDENTITY_SMOKE_OK |
| 隐私/文档/空白检查 | 隐私扫描、当前文档相对链接和 git diff --check 通过 |

上述原生测试只生成确定性 PCM，不使用 AudioRecord；原有显式开启的 `MicrophoneSignalProbeTest`
仅适配 effect 生命周期 API，未执行。它仍属于单独授权的真实录音对比工具。

## 文档、配置与产物

文档同步 README（中/英）、ARCHITECTURE、PRIVACY、CHANGELOG 和 F3 计划进度，并新增 ADR/本报告。
本轮不改依赖、持久化格式、生产包名、权限或发布版本。独立旁装副本只改包名/显示名：

- APK：`build/microphone/TS3-Mobile-microphone-debug.apk`。
- applicationId：`io.github.ts3mobile.app.f1adevicetest`；中文名称“TS3 Mobile 麦克风调试”。
- 与此前旁装调试包保持签名兼容，和手机原应用独立；未卸载、清数据或覆盖手机原应用。
- 主工作区 Debug/Release APK 仍在 `app/build/outputs/apk/`；Release 为未签名开发构建。

忽略目录 `build/microphone/` 保存构建、测试、探针、隐私扫描日志，中文大字体截图、
源码一致性/包名/签名与 SHA-256 记录（`artifacts.json`、`check-results.json`）。

## 尚未覆盖

没有实测真实说话的清晰度、削波听感、房间噪声误触发、扬声器回声、蓝牙/USB/有线耳机下 AEC
或长时后台耗电。设备承认启用 AEC 不等于实际回声已消除；本轮不宣称这些验收完成。
独立调试 APK 尚未安装到手机，真实服务器语音互通仍需后续单独验证。

旁装产物的 195 个生产文件一致性检查通过（仅归一化显示名）；包名、显示名和此前旁装包签名匹配检查通过。
验证结束后停止专用模拟器，保留 AVD 和本地产物。
