# JVM 门禁证据更正与收尾验收

日期：2026-10-24。状态：JVM 强制门禁、五次协议重跑及受影响集成检查通过。[方案](../plans/AUDIO_EVIDENCE_CORRECTION_2026-10-24_ZH.md)。

## 原声明更正

旧 `audio-review-2026-10-24/final-gates.log` 与 `audio-followup-2026-10-24/final-gates.log` 的协议测试为 UP-TO-DATE。它们引用了旧 XML，不能支持“该轮三模块全量重新执行且通过”的声明。两份历史验收表已添加明确更正，旧日志和 results.json 不覆盖。

协议精确断言的问题成立：方法入口、清理 microphone 与切换 IO dispatcher 都会消耗真实时间，底层正确收到的是剩余预算，不能固定断言 1000 ms。本机修改前一次强制执行实际通过；这说明它依赖调度/计时，不否定其他机器上的 5/5 失败。没有将其误归因到音频修复，也没有通过放宽为 990–1000 ms 来隐藏真实时间依赖。

## 生产与测试变更

- 协议层保留预算扣减和 socket 归属逻辑；新增内部单调时钟依赖，公开无参构造保持不变。原用例固定时钟验证完整预算，新增精确 2 ms 开销扣减、预算耗尽不再提交及 captured socket 必须 close 的独立回归。
- `hasPlaybackFailure` 统一 UI/通知条件，只在 CONNECTED 显示恢复指导。停止/断开时的退休失败仍保存 FAILED 状态并记录诊断。JVM 覆盖 DISCONNECTING/DISCONNECTED/ERROR，Compose 验证主动断开时提示消失。
- `MicrophoneFeedback` 独立负责权限提示、错误计数与采集状态反馈；控制器保留发送代次与串行生命周期，由 361 行降为 296 行。三个独立单测覆盖权限错误清除边界、失败后的真实资源状态与已关发送但资源未释放的区别。没有仅为行数把主流程拆成互相调用的碎片。
- 无依赖、持久化配置或网络格式变化；CI 的三个 JVM 测试任务显式 `--rerun` 并禁用构建缓存，CONTRIBUTING 增加验收命令与源指纹要求。

## 新 JVM 证据

命令：

```sh
./gradlew --no-daemon --max-workers=2 --console=plain --no-build-cache --rerun-tasks quality :audio-opus:testDebugUnitTest :app:testDebugUnitTest :ts3-protocol:test
```

该次日志为 `73 actionable tasks: 73 executed`。audio 166 + app 232 + protocol 128 = **526 项，0 失败、0 错误、0 跳过**。三个测试任务分别满足：实际执行、新 XML 的 mtime 在运行开始之后、报告复制到独立目录。不是从测试缓存或现存 XML 汇总得出的“通过”。

证据目录：`build/audio-evidence-correction-2026-10-24/`，`latest-run.txt` 指向正式通过的独立 run 目录；包含 command.json、jvm.log、results.json、source-before.json、source-after.json 和各模块 XML。正式通过的运行前后 425 个文件 SHA-256 完全一致，覆盖模块源码/测试/资源、Gradle、工具与 CI 配置。Markdown 说明文档不作为编译输入；后续改动需对照清单判断证据有效性，不能只凭 mtime 或文件数。

首个证据收集尝试因新增 UI 回归的一行超长而在 quality 阶段失败，记录保留在另一个 run 目录。收集器将未执行任务和旧 XML 标为 executed=false/fresh_xml=false、passed=false，没有把旧结果算作该次成功。修正格式并完成格式化后，才生成上述正式通过记录。

## 集成与重复验证

|项目|本轮执行与结果|
|---|---|
|三模块 JVM + quality|526 项通过，73 个 Gradle 任务实际执行，源码/配置前后指纹一致|
|协议全量连续重跑|额外 5 次，每次 128 项、0 失败/错误/跳过；逐次保存日志与新 XML|
|app Android|模拟器 11 项通过，包含 microphone fake 生命周期及主动断开后不显示播放恢复提示|
|Lint|audio 0 项问题；app 0 错误、36 项既有 warning|
|Debug / minified Release|通过，仍有既有依赖 R8 规则/服务描述符警告|
|实际 Release codec probe|arm64-v8a，libopus 1.6.1，20 个生命周期 × 10 帧，通过|
|实际 Release identity / INI|通过|
|Python 工具|7 项通过|
|隐私 / 文档链接 / diff 空白|通过|

额外重复命令为 `./gradlew --no-daemon --max-workers=2 --console=plain --no-build-cache :ts3-protocol:test --rerun`。五轮结果位于 `protocol-repeat-1` 至 `protocol-repeat-5`，汇总为 protocol-repeats.json；重复次数不重复计入 526 个独立用例。

先前音频 Android 31 项与实验 APM 9 项属于独立历史证据，本轮没有将它们记作重新执行。新 app XML、integration.log、Release probes 和 results.json 位于本次证据根目录。验收结束再将当前 425 个输入文件与正式 JVM run 的 source-before.json 对比；最终核对后没有改动生产源码、测试或配置。后续 Markdown 说明的变动单独处理，不冒充编译输入变化。

## 边界

不录音、不连接真实服务器，不改变 A5 的真机路由、声学或功耗验收状态。Android 仪器测试使用模拟器和 fake 麦克风。既有 app Lint/R8 警告不因本次证据更正自动视为消除。
