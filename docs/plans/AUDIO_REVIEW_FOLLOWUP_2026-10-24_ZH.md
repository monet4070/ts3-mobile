# 音频复审修复补充

状态：实现与离线验收完成，结果见[验收记录](../testing/AUDIO_REVIEW_FOLLOWUP_2026-10-24_ZH.md)。保留原协议、依赖、默认关闭 APM/FEC 的策略。范围为两份复审中经代码/探针证实的问题。

- 播放 facade 不再持输出锁执行控制操作。worker 独占 session；停止先使写入失效再 interrupt，主线程不 join，后台 join 有界。旧输出释放失败保留所有权，后续 stop 可重试退休，未退休不允许 start。
- 输出代次失效覆盖参与者静音的 pending 混音；写入前复核代次。成功写入累计样本证明恢复，正常短语音不累积历史失败。自然空闲根据时间推进限幅释放。
- `PlaybackStatus` 通过回调接入服务状态、固定类别日志、全页面错误提示和通知。失败提示引导切换输出或重新连接，不新增持久化设置。
- 停麦立即关闭发送和采集请求，串行后台释放后才摘除前台类型。销毁不在主线程等待 HAL；清理完成回调由服务主线程执行，超时明确诊断，旧采集不重叠。
- `OpusMicrophoneCapture` 提取 `CaptureResources` 管理构造回滚、设备效果与资源销毁。后续将原 361 行 `MicrophoneController` 的权限与状态反馈提取为 `MicrophoneFeedback`，控制器保留采集/发送编排，当前 296 行；发送端口和 teardown 保持独立。大型服务只接线，不改无关功能。
- 参数变化清过期队列/起声，但不重建 RNNoise/encoder；路由 revision 才重置声场。实际输入/输出路由和选中路由变化触发，路由列表或错误文本变化不触发。效果状态统一进入 PENDING 并在 worker 重配。
- `PlaybackStreams.reset` 与 close 分开；删除只写不读计数，区分 reset 丢弃/FIFO 溢出指标；准备包只复制解析一次。移除死方法和误命名测试。
- APM build fingerprint 包含源码锁、ABI、工具链、编译器与构建选项，变更后 wipe Meson 缓存；配置辅助测试覆盖缓存复用/失效。
- 补跨状态 JVM、fake capture 和 JNI 合成信号回归；更新 CONTRIBUTING/CI 设备门禁，执行格式化、quality、三模块测试、Lint、Debug/Release、选定 Android 和实际 Release probes。

复核说明：没有反向锁嵌套不代表 stop 有界，等待输出锁发生在 join 超时之前。实际路由回调原本会间接重新 configure 效果器，报告中的“完全不重配”不成立；本轮统一其状态入口。现有 26/9/9 Android 数字来自模拟器，不能称为真机证据。A5 真机路由、声学和功耗仍单独验收。

兼容性：保留旧 OpusAudioPlayer 构造方式，新增可选状态回调；MicrophoneCapture 新增带默认实现的 requestStop/isReleased，外部适配器应重编译，并在有异步资源时覆盖这两个生命周期契约。无持久化配置或协议变更。

Android onDestroy 不能推迟框架的销毁决策。本轮不承诺其返回时 OEM HAL 必然已释放；提供非阻塞停止请求、独立清理完成回调、超时诊断和禁止资源重叠。正常停止路径的前台类型仅在实际释放后移除。

原 JVM 汇总包含任务复用，强制执行和源码指纹的更正记录见[门禁证据验收](../testing/AUDIO_EVIDENCE_CORRECTION_2026-10-24_ZH.md)。
