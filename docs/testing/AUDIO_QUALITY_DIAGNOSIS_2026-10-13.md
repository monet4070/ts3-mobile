# 音质自动诊断记录 — 2026-10-13

## 结论和证据边界

基线为 `e9e0b41`，原工作区干净。手机为 Xiaomi 14（23127PN0CC），
Android 16 / API 36。本轮成功通过手机中的历史地址连接服务器；未将地址、
身份、昵称、频道名或密码写入报告。未重装或替换手机上的原客户端 APK。

**已复现发送时序缺陷，并重复测到采集音源带来的约 11 dB 电平差异。
尚未锁定用户此前 AirPods 下“发闷、含糊”的唯一原因。**

本轮只增加独立诊断测试，没有修改生产音频策略。附件中的实施顺序和指标是
待验证建议，不作为本轮自动修改生产行为的指令。

## 真机采集对比

Mac 内置扬声器播放固定的 14.22 秒中文系统合成语音；手机分别使用
`VOICE_COMMUNICATION` 和 `MIC` 采集。两种配置均使用项目的
`MicrophoneAudioEffects` 和 `NativeRnNoiseProcessor`，保持 48 kHz 单声道、
PCM16、同一播放音量。每次记录 18 秒，保存同一次采集的处理前后 PCM。
第二轮反转音源顺序。原客户端麦克风关闭，测试活动在前台，确认录音未被系统静音。

| 顺序 | 音源 | 处理前 RMS | RNNoise 后 RMS |
| --- | --- | ---: | ---: |
| 第一轮，先通信音源 | VOICE_COMMUNICATION | 153.7 | 151.8 |
| 第一轮，后 MIC | MIC | 544.3 | 526.0 |
| 第二轮，先 MIC | MIC | 537.0 | 519.7 |
| 第二轮，后通信音源 | VOICE_COMMUNICATION | 158.4 | 156.7 |

通信音源在进入 RNNoise **之前**已低 10.98 / 10.61 dB。第一轮对齐语音时段后，
通信音源的 RNNoise 电平变化约 -0.10 dB；300–1000、1000–3000、3000–7000 Hz
三个频段变化分别约 -0.12、-0.11、-0.05 dB。相对于 MIC，通信音源各频段下降
约 10–11 dB，当前样本主要表现为整体电平差异，不能据此宣称高频被明显切除。

实际状态回读：

- 两种输入均为 `TYPE_BUILTIN_MIC`，客户端与设备录音格式均为 48 kHz。
- 通信音源的录音配置包含 AEC 和平台 NS；MIC 对照只包含 AEC。
- AEC 回读 `enabled=true`、`hasControl=true`。
- `AutomaticGainControl.isAvailable()` 为 **false**。在本机简单把 AGC 的
  `enabled=false` 改为 `true` 不会提供可用的硬件增益。
- 固定源与录到的语音相关性约 0.50，确认有效采集包含播放的测试语音。

这使**采集增益和系统处理链**成为当前最有直接实测支持的调查方向。
平台 NS 与 RNNoise 同时存在已经确认，但“叠加必然导致发闷”仍未证实。
蓝牙设置中存在已保存的 AirPods；本轮尝试连接后，仍没有建立蓝牙音频连接，
也没有出现可用的 Bluetooth SCO/BLE 音频路由。因此无法用内置麦克风的结果
替代用户原来的 AirPods 实测。
Mac 扬声器到手机麦克风的声学路径、系统 MIC/通信处理差异都包含在测量中；
它不是耳机人声测试，也不是 MOS 或可懂度评分。

## 降噪和编码的对照

使用仓库原版 RNNoise C 源码和模型编译离线探针。干净中文语音按原电平、
降低 12 dB、降低 24 dB 三档输入，进行了 20 ms 算法延迟对齐：
各档总电平变化约 +0.01 dB，三个语音频段变化均在约 0.02 dB 内。
噪声阳性对照衰减约 32.8 dB，证明探针确实运行了降噪。

同一语音再用手机上的生产 JNI 编解码器运行：

| 链路 | 对齐后的总电平变化 | 3–7 kHz 频段变化 | 算法延迟 |
| --- | ---: | ---: | ---: |
| RNNoise | +0.013 dB | +0.010 dB | 20 ms |
| Opus，现有 64 kbps 配置 | -0.073 dB | -1.419 dB | 约 6.42 ms |
| RNNoise → Opus | -0.055 dB | -1.435 dB | 约 26.42 ms |

RNNoise 10 ms 帧真机基准：P50 200 μs、P95 221 μs、最大 337 μs。
Opus 编码 P95 约 467–475 μs / 20 ms 帧。
这组输入没有显示实时计算超预算，或 RNNoise 对干净低电平语音的严重压制。
不能把这些有限样本外推为所有背景噪声、耳机输入或中文说话人的质量保证。

## 发送端的可执行复现

使用 Gradle 实际解析的固定版本 ts3j，替换网络写出，运行其真实
`LocalTeamspeakClientSocket.MicrophoneTask`，依次提供“有帧 → 暂空 → 有帧”。
得到语音包负载长度：

```text
[4, 0, 4]
```

中间空包是讲话结束包，而非普通 Opus 静音。生产
`OpusMicrophoneCapture.isReady()` 直接使用 `encodedFrames.isNotEmpty()`，
因此瞬时采集/发送调度错位能够进入此路径。
该缺陷可以解释断续、讲话边界反复重启和吞字；它对持续发闷的解释力较弱。
本轮没有测出用户实际蓝牙会话中该事件的发生频率。

附件中的其他问题经源码复核也存在：按人数瞬时混音衰减、播放队列空时释放
焦点、没有 FEC 解码接口、较大包号缺口触发解码器重置。不过“重置必然爆音”、
“焦点释放必然令本机 SCO 掉线”以及给出的 MOS、丢包恢复率、CPU 降幅均未在本轮证实。

## 测试与实现范围

- 生产代码：无变更。
- 测试：新增 `MicrophoneSignalProbeTest`，支持显式开启的本地成对采集、
  实际设备/音效回读，以及提供语音 fixture 后的 JNI RNNoise/Opus 比较。
  新增无采集逻辑的前台 `MicrophoneProbeActivity`；录音权限和活动只存在于
  `src/androidTest` 的 manifest，正式 APK 不包含它们。
- 文档：本记录及 README 中的入口。
- 构建/生产配置：无变更。SDK 安装在机器缓存中，通过 `ANDROID_HOME` 使用。
  用本地 Gradle init 脚本给诊断测试设置独立 application ID，保留原有 App 和测试包。

执行结果：

- JVM 单元测试：协议 48、音频 20、应用 97，共 **165 个通过**。
- `quality`、音频与应用 `lintDebug`、debug 编译/组装通过。
- 隐私扫描按仓库 PowerShell 脚本的相同文件列表、敏感扩展和地址匹配规则，
  用 Python 在 macOS 上执行并通过；本机没有 PowerShell。`git diff --check` 通过。
- 真机常规 suite：原有 8 项及提供 fixture 的新增 1 项通过；默认不启用的
  麦克风采集项跳过。另有 4 次显式开启的有效采集测试通过。
- 早期无播放源、并发录音导致系统静音，以及 OEM 阻止后台启动测试活动的
  尝试均未计入有效音质对照。修改测试启动方式并关闭原客户端麦克风后重测。
- 本轮没有运行 120 分钟蓝牙/锁屏验收、KOOK 同源录音对照、真实对端人声
  可懂度或 ViSQOL/STOI。
- 收尾已确认手机显示未连接，原客户端和诊断测试均无活动录音。
  Mac 原静音状态已恢复；原 App、身份、服务器历史和原测试包保留。
  诊断类经 debug APK 的 DEX 检查确认未进入正式应用构建。

## 重复测量

常规 suite 不录音。提供以下参数才启动本地采集；文件位于测试应用私有目录，
不会经 TeamSpeak 发送。确保测试进程有录音权限、设备可进入前台，其他 App
未占用麦克风，并从外部扬声器播放相同语音。不要让手机自己播放测试源，
否则 AEC 可能把它作为回声消除。

```sh
./gradlew :audio-opus:assembleDebugAndroidTest
adb shell am instrument -w -r \
  -e class io.github.ts3mobile.audio.opus.MicrophoneSignalProbeTest#capturesPairedRawAndDenoisedMicrophoneSignal \
  -e captureProbe 1 -e source voice -e frames 1800 -e nonce repeat-voice \
  io.github.ts3mobile.audio.opus.test/androidx.test.runner.AndroidJUnitRunner
```

`source=mic` 为音源对照，`source=voice-agc` 仅在平台实际提供 AGC 时有比较意义。
安装并授予测试包录音权限后，再执行测量。测试 Activity 可以由 shell 启动，
以满足 OEM 的前台录音策略；启动这个 Activity 本身不会采集声音。
看到 `TS3_AUDIO_PROBE` 的 `READY` 后播放语音；若 `isClientSilenced=true`，
测试会失败，不能将全零输入误当成算法问题。

提供单声道 PCM16、小端、48 kHz、完整 480 样本帧的 `files/speech.pcm`，
再运行 `processesProvidedSpeechWithProductionDenoiser`，会输出
`speech-denoised.pcm`、`speech-opus.pcm`、`speech-denoised-opus.pcm`。
所有比较先对齐算法延迟；单看整段 RMS 会混入静音和尾部。

本次私有现场文件保存在已被 Git 忽略的 `captures/audio-diagnosis-2026-10-13/`。
其中包含可重跑的离线探针、发送时序复现、声学播放脚本、成对 PCM 和日志；
不应提交到仓库或公开上传。

## 后续处理优先级

1. 在真实 AirPods 通话路由可用时，记录实际输入设备、系统录音配置及协商的
   蓝牙 codec/采样率。48 kHz 客户端录音格式本身不能证明蓝牙宽带输入。
2. 用同一段人声比较系统处理和 RNNoise 前后信号；先确认电平、噪声比例和
   辅音保留，再决定平台 NS 策略、路由相关的预增益或软件 AGC。
3. 修正“队列暂空＝讲话结束”的协议适配语义，并在真实固定版本发送路径上
   加回归测试；保持用户关闭/PTT 释放可以及时结束发话。
4. 分别验证播放焦点、混音和丢包处理。引入 FEC 时需证明当前 Opus 模式实际
   产生冗余、接收端恢复有效，并测量音质/码率权衡。

不要直接把所有路由改为 MIC：它可能改变扬声器回声消除和蓝牙通话行为。
没有证据支持一次性实施附件所有参数或宣称已解决用户的 AirPods 音质问题。
