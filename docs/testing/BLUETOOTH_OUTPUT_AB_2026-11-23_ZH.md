# A1 蓝牙普通／低延迟 A/B：离线准备

2026-11-23。继续使用 `bluetoothStandardOutputExperiment`，不改变默认路由、编解码、收听档或缓冲。`tools/compare-bluetooth-output.py` 仅读取用户主动导出的两份诊断 JSON，输出实际模式、粗路由、接收档、故障标记相对时间、欠载/轨道/恢复计数和退役原因。不连接设备或网络，不把原始导出回显。计数按播放代次取累计下界，日志上限可能丢失事件，不用它推断声学延迟。

基线 `8bd1427` 加工作区变更。构建验证发现旧常量切换能产出哈希相同的混淆 APK，不能用于 A/B；改为保留在包内的布尔资源后，两个 APK 的哈希不同，`aapt` 确认 A 为 `false`、B 为 `true`。产物、构建日志和 SHA-256 在本地 `build/audio-output-ab-2026-11-23/`。这是**请求模式**证据；实际 `AudioTrack` 模式仍必须在真机日志中不同。

```sh
./gradlew :app:assembleRelease
# 另存上一份，再构建 B。
./gradlew -PbluetoothStandardOutputExperiment=true :app:assembleRelease
python3 tools/compare-bluetooth-output.py baseline.json standard.json
```

导出含多段连接时传 `--baseline-session` 和 `--standard-session`，不要让工具猜测。脚本直接接受应用 Save file 产生的完整 JSON。未见 A 实际低延迟与 B 实际普通模式、未从两份 [A2 设备采样](VOICE_TIMING_AND_ACTUAL_DEVICES_2026-11-23_ZH.md) 中确认实际 A2DP、改接收档或比较过程网络切换时标记无效。实际 A2DP 类别仍不证明同一副耳机。成功退出仅证明日志支持对比，不是听感或发布验收。

## 一轮人工采集

固定 Xiaomi 14、同一 AirPods A2DP 路由、音量、网络、接收档、远端声源。每包分别跑冷启动首句、短间隔、连续段和按下 PTT 至对端出声；各段故障当场标记，每包导出一次离线 JSON。再用有线或 USB 测对照。实际性能模式相同则停止该配对，不补造结论。只回报漏首句次数、声学延迟 p50/p95 和对照脚本结果；不要把播放头或写入时间当实际出声。没有这些证据之前不改默认性能策略，也不启动需要真机基线的 A3 验收。
