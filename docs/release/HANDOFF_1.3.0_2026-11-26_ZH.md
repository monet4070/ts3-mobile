# TS3 Mobile 1.3.0 验收交接（2026-11-26）

> 下文保留 11 月 26 日早期原签名工件的实机记录。11 月 28 日的 `e348a0c` 原签名基线与后续隔离包另有验收结果，均不等于最终签名包验收；见[候选状态](CANDIDATE_STATUS_2026-11-25.md#当前验收--current-verification)。

**结论：原发布签名覆盖安装、实服单端静音冒烟、蓝牙空闲断开保护已通过；还不能完成发布验收。** 本轮确认有两项 Android 音频回归稳定失败，另有播放中蓝牙断开、文件下载和部分设备场景未签收。不要用已通过的空闲蓝牙用例替代播放中防外放验收。

## 工件与通过范围

- 候选：`io.github.ts3mobile.app`，`1.3.0` / `versionCode 17`。真机为小米 14（23127PN0CC）、HyperOS、Android 16 / API 36，使用实体蓝牙耳机。签名证书 SHA-256 与手机上的上一版正式签名包一致：`e239102412f17ddd1909bd323b3825831b153fec4602ec00abb359da7d8be592`。签前、签后 APK 的非签名内容逐项校验一致。
- 真机验收包：`build/device-qa-2026-11-26/1.3.0-device-candidate-release-signed.apk`，SHA-256：`f254963b134081843afb19b97d6a734d711fa21b2428e39afd914489fef76ea5`。构建基线为 `8939589` 加当时的审计修复工作树，**不是**仅从 `8939589` 构建，也尚未绑定最终发布提交。上一版对照包基于 `26dec7b`，同样显示 `1.3.0` / `17`，不能只凭版本号识别包。

| 检查 | 已通过的范围 |
| --- | --- |
| 原签名保数据覆盖 | 已核对手机安装包摘要。`17 → 17` 同版本码覆盖，应用 UID 不变；未卸载、未清数据。身份 UID、收藏、连接表单和外观设置均保留。早先旧候选的公开版 `16 → 17` 证据不转用到这份新工件。 |
| 新签名包实服 | 用保留的收藏连服，确认切入一个原先无人的频道，主动断开后重连。测试没有发音或发送消息。 |
| 新签名包蓝牙 | 无人频道、**无正在播放的语音**时，耳机断开触发自动收听静音及防外放提示；耳机重连后仍保持静音。扬声器媒体音量为 0，未切换勿扰；结束时已断开会话并恢复麦克风权限策略与耳机连接。 |
| 同批代码的设备与离线检查 | 隔离 Debug 包的本机试麦通过：识别内置麦克风、停止后重启、退后台停止、30 秒自动停止。应用定向仪器测试 26 项通过；存储与备份组 16 项首次通过、余下 2 项放宽测试超时后通过。Release 包内的 Opus 和合成身份读写探针在真机通过。JVM 979 通过、6 跳过，Python 34 通过；格式检查、Android Lint（无错误，仍有警告）、Debug/Release 构建通过。 |

## 已发现的问题

| 优先处理 | 证据 | 接手后的验收条件 |
| --- | --- | --- |
| DTX 和 FEC 两项 Android 音频回归稳定失败 | 音频全套最终 36/38。相同设备和测试筛选下，旧基线四项对照全过，当前版本只有 DTX、FEC 失败。DTX 预期输出 480 个采样，实际 0；FEC 预期正常解码 34 次，实际 16。两项夹具把包的到达时间写死为 0，播放时钟继续走，触发本批新增的 360 ms 过期淘汰。这不是实际通话丢音的证据。 | 让夹具的包到达时间与模拟播放时钟一致，独立保留真正过期包应淘汰的覆盖。不要为了让用例通过而移除过期保护。重跑两个定向用例及音频全套，确认 DTX、FEC 和丢包恢复行为。 |
| 文件下载与静音语音并行的真机探针未通过 | Android USB 回环用例在旧基线与当前版都卡在 `verifyOverlap()`：5 秒内未观察到暂存文件进入增长区间。当前版主机 JVM 回环协议用例通过；Android 探针已走到文件列表与静音播放启动，但未完成下载校验。现有证据不能归因于本批代码。 | 先区分夹具传输速度、暂存与文件传输错误，再复跑。通过条件是下载增长期间静音输出持续、最终保存字节数和 SHA-256 匹配；另验取消清理、权限拒绝及离开频道后旧下载入口失效。 |
| 通知、浮窗与全量应用仪器测试未完成 | 小米设备上的部分用例等不到测试 Activity 启动，初次全量运行也出现触控注入失败。停止测试进程后出现的 runner 结束提示不算生产崩溃。定向服务用例通过不代表真实通知按钮或浮窗操作通过。 | 在支持稳定启动测试界面的环境重跑自动化；再用原签名候选实点通知静音／恢复／检查路由、浮窗召回、Home/返回、前后台 PTT 与止录。需要切换系统勿扰的自动化用例不要在这台用户手机上运行。 |
| `AUD-004` 仍是缓解，并非上游根修 | 默认依赖仍是 `ts3mobile-1`；对已识别的握手定宽异常最多额外重试两次。单次实服连接成功不能给出故障率，也不证明定宽编码已修。 | 记录受控重复连接中的重试、最终失败和耗时。若要替换依赖，另做协议互通、回退构建和真实服务器复验。 |

音频用例入口：[`NativeOpusCompatibilityTest.kt`](../../audio-opus/src/androidTest/java/io/github/ts3mobile/audio/opus/NativeOpusCompatibilityTest.kt)、[`NativeOpusReceiveTest.kt`](../../audio-opus/src/androidTest/java/io/github/ts3mobile/audio/opus/NativeOpusReceiveTest.kt)。文件探针入口：[`LocalChannelFilesAndroidProbeTest.kt`](../../app/src/androidTest/java/io/github/ts3mobile/app/ui/LocalChannelFilesAndroidProbeTest.kt)。

## 还剩什么，按顺序做

1. **修复并复跑音频测试。** 收口 DTX、FEC 两项，记录最终 Android 音频全套结果。两项播放生命周期用例首跑失败，定向和全套复跑已通过，不另列为稳定失败；若再现，记录真实路由和 AudioTrack 启停时序。
2. **补当前签名候选的蓝牙播放中防外放。** 用隔离端发送零 PCM 编码帧，先证明耳机实际输出且 AudioTrack 播放头推进，再断开蓝牙，验证停止写入或退休旧输出、不回放缓存语音、重连不会自行解除静音。实体设备覆盖冷启动、主动断连与回退；回调迟到或缺失另用可控夹具验证。全过程不切换勿扰，保持扬声器零音量，不把无人频道的空闲保护结果重复算作通过。
3. **补当前签名候选的联机与系统场景。** 验证 Wi‑Fi ↔ 蜂窝、弱网和快速重连、4 秒订阅等待边界、锁屏至少 30 分钟及进程终止后脱敏诊断；完成通知与浮窗实点、文件下载和双端语音验收。实服只用保存服务器的无人频道；需要听感或第二端时另安排，不用真人语音来触发防外放。另按[候选门禁](CANDIDATE_STATUS_2026-11-25.md#发版门禁--release-gates)补实体云备份／换机、缺密钥恢复、窄屏键盘下加密频道加入。任何真实数据升级验证不得为了回到旧版本而卸载当前手机应用或清数据，使用安全的旧版基线设备。
4. **冻结最终源码、工件和 CI。** 纳入本地未跟踪的 `PlaybackRouteCheckThrottleTest.kt` 等实际构建与回归输入，核对完整提交和构建输入清单；复跑最终提交的 CI（含 Linux/x86_64、默认和 TS3j 源码回退构建），重新产出或核对同源的原签名 APK，记录实际 `BUILDINFO.json`、`SHA256SUMS.txt`。若生产输入或分发版本变更，重新构建并补相关真机证据。未通过前不打发布标签或公开分发，不将本轮本地签包误记为已发布。

## 证据索引

以下脱敏证据及候选 APK 位于本机 `build/device-qa-2026-11-26/`（忽略目录，不提交）。

- 覆盖、实服、蓝牙：`signed-upgrade-phone.json`、`signed-real-server-bluetooth.json`。
- 音频对照：`audio-comparison-baseline.log`、`audio-comparison-current.log`、`audio-final-rerun.log`。
- 回环文件和应用仪器测试：`loopback-baseline-instrumentation.log`、`loopback-current-retry-instrumentation.log`、`protocol-loopback.log`、`app-focused-instrumentation.log`、`app-notification-fixtures.log`、`app-no-dnd-suite.log`。
- 试麦与发行包探针：`local-microphone-final.log`、`release-codec-phone.log`、`release-identity-phone.log`。

交接时只在维护者受控位置传递 APK 与必要的脱敏证据，不附密钥库、口令、服务器地址、频道名称、用户身份材料、原始音频或未审查的系统日志。签名以已验证证书为准，不以本机默认 Debug 签名或文件名代替校验。

## 2026-11-26 后续修复与验证收口

- **DTX / FEC 音频夹具与原生过期淘汰验证**：[NativeOpusCompatibilityTest.kt](../../audio-opus/src/androidTest/java/io/github/ts3mobile/audio/opus/NativeOpusCompatibilityTest.kt) 与 [NativeOpusReceiveTest.kt](../../audio-opus/src/androidTest/java/io/github/ts3mobile/audio/opus/NativeOpusReceiveTest.kt) 已对齐 20 ms 模拟包到达时钟并新增独立 360 ms 过期淘汰测试，未削弱过期保护；在无界面 API 35 arm64 模拟器（`ts3-f1a-api35`）上全套非手工音频测试 **`OK (39 tests)`** 通过。
- **文件下载并行探针夹具定位与修复**：确认卡在 `verifyOverlap()` 系 `9f7b859` 引入 `TestVerifiedFileSaveHost`（暂存目录位于 `cacheDir/fixture-verified-files`）与 `LocalChannelFilesAndroidProbeTest.stagedBytes()` 扫描根目录不一致所致，非生产传输退化。已抽取 [TestVerifiedFileSaveHost.kt](../../app/src/androidTest/java/io/github/ts3mobile/app/ui/TestVerifiedFileSaveHost.kt) `testVerifiedFileStagingDir()` 并修正另 3 处硬编码测试 Provider Authority；在 `ts3-f1a-api35` 上复跑隔离回环服务端+USB 桥接实测 **`OK (1 test)`**（`TS3_FILE_DEVICE: output=SPEAKER outputSamples=402720`），文件列表／备份 Provider／通知栏定向集 **`OK (15 tests)`** 通过。
- **仓库隐私扫描与发布文档准备**：清理 `TS3_MOBILE_CODE_AUDIT_REPORT.md`、`Ts3jChannelFileTcp.kt` 注释与 `Ts3jChannelFilesTest.kt` 测试字面量中的 IPv4 文本以消除 PR CI 隐私检查误阻断，补充 1.3.0 中英文草稿发布说明与 `CHANGELOG.md` 条目；真机 Xiaomi 14 因测试末段未连接 ADB 仍待复跑确认上述 39 项音频、文件探针及第 2–4 项真机/最终签名门禁。
