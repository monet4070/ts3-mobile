# TS3-Mobile 第三轮审计修复交接

**状态：21 项确认缺陷均已修复，通过本地软件验收；这不是发布批准。** 对应第三轮技术仲裁，基线为 `8bd1427f8f40b39da63b222fbd1bbef3d045ffce` 加审计开始时的工作树。在 `codex/release-1.2.0` 的当前工作树交接，未提交、签名或发布。按要求跳过 Cyber 专项，未使用子代理；未确认的兼容性和性能观察留在发布前验收清单，不当作已确认缺陷。

## 修复清单

| 编号 | 问题 | 状态 | 代码和验收入口 |
| --- | --- | --- | --- |
| 001 | 录音释放失败卡住连接 | 已修，工作树已验证 | `SessionAudioRelease.releaseCapture`；`ConnectionCoordinatorTest` 录音释放的手动连接、重连、退出用例 |
| 002 | 重连成功交接丢失后续断线 | 已修，定向回归通过 | `ReconnectEngine.registerLoss`、`continueAfterSuccess`；`SessionRecoveryRaceTest` 的重连、显式取消用例 |
| 003 | 旧失败回调停止新会话媒体 | 已修，定向回归通过 | `ConnectionCoordinator.onEstablishedSessionEnded`、`suspendAudioForReconnect`；`SessionRecoveryRaceTest.obsoleteFailureCannotStopReplacementPlaybackOrTransmitGate` |
| 004 | 订阅和频道移动未限定命令完成时间 | 已修，协议与协调器回归通过 | `Ts3jChannelCommands`、`ChannelMoveFeedback`；区分未提交与结果未知，不自动重发频道移动 |
| 005 | 时钟回拨导致历史永久删除 | 已修，工作树已验证 | `ChatHistoryCodec.retain`；`ChatHistoryVaultTest.readingAfterClockRollbackKeepsAllIdentitiesAndPreservesTheArchive` |
| 006 | 历史重试未实际写入却报告恢复 | 已修，工作树已验证 | `ChatHistoryVault.recover`；`ChatHistoryVaultTest.recoveryMustCommitWithoutChangingExistingRecords` |
| 007 | Android 样式门禁失败 | 已修，工作树已验证 | `IdentityManagementScreen`、`GuideDestinationCard`；`test_android_button_style.py` |
| 008 | 文档选择器返回前重建丢失暂存 | 已修，API 35/36 回归通过 | `VerifiedFileSaveHost`、`DocumentSavesViewModel`；Activity 重建且原对话框退出仍收取结果 |
| 009 | provider 部分写入后无法可靠重试 | 已修，API 35/36 回归通过 | `VerifiedFileSaves`、`copyVerifiedDocument`；检测残件，改选 URI 重试 |
| 010 | 试麦未释放仍可尝试浮窗采集 | 已修，API 35/36 定向回归通过 | `MicrophoneTrial.isInputReleased`、`MicrophoneController.checkTrialInputReleased` |
| 011 | 收藏写出超限、无法重新打开的归档 | 已修，API 35/36 存储回归通过 | `BookmarkVault`、`BookmarkCodec.MAX_ARCHIVE_BYTES`；归一化与写前检查，失败不替换旧档 |
| 012 | 晚到网络观察倒退主动断开终态 | 已修，定向回归通过 | `SessionGeneration.activeEpoch`、`ConnectionCoordinator.onNetworkUnavailable`；`SessionRecoveryRaceTest.offlineObservationCannotOverwriteACompletedExit` |
| 013 | 音频线程晚醒被误判为设备超时 | 已修，JVM 回归通过 | `PlaybackTimeline`、`PlaybackSession`；晚醒、过期和真实停滞分开验证 |
| 014 | 通知丢掉最终一次更新 | 已修，API 35/36 各六轮通过 | `SessionNotifications`、`LatestNotificationDelivery`；最终 revision 核对、丢投递补发、类型即时生效 |
| 015 | 服务销毁后可再启动播放 worker | 已修，API 35/36 回归通过 | `TeamSpeakService`、`OpusAudioPlayer`；屏障验证终态拒绝晚启动 |
| 016 | 诊断读取接受未提交的结束记录 | 已修，JVM 回归通过 | `AudioDiagnosticStorage`；截断及损坏标记不应用未提交批次 |
| 017 | 旧导出结果清理新导出的暂存 | 已修，JVM 回归通过 | `DiagnosticReportActions`、`DiagnosticExportOperations`；UUID 隔离暂存和结果 |
| 018 | 服务退场同步等待诊断刷盘 | 已修，API 35/36 回归通过 | `SessionDiagnostics`、`SessionDiagnosticWriter`；阻塞写入不妨碍退出或替代服务记录 |
| 019 | 清空后的旧工作占用新历史队列 | 已修，工作树已验证 | `ChatHistoryController.invalidatePending`；`ChatHistoryControllerTest.clearReleasesAllQueueSlotsForNewGeneration` |
| 020 | 慢加载期间的播放代际漏归档 | 已修，API 35/36 回归通过 | `AudioDiagnosticController`；迟挂接时报告遗漏数，重复结束不重记 |
| 021 | 身份 DataStore 排除规则引用错误目录 | 已修，打包配置已验证，设备迁移待验 | `IdentityVault`、两份备份规则 XML；`IdentityBackupRulesTest` 检查打包配置 |

## 已实现的修复与关键代码

### 001：保留退场终态，不复用失败的采集资源

生产：[`SessionAudioRelease.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/SessionAudioRelease.kt)、[`ConnectionCoordinator.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt)、[`SessionExitRunner.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/SessionExitRunner.kt)。将普通录音释放失败转换成仅含异常类型的 `UserMessage.MicrophoneFailed`，保持协程取消传播。手动连接与自动恢复先检查释放；失败不开始下一次握手，终止时仍清播放与路由。显式退出的并行清理不再因释放失败取消兄弟分支，并记录 `EXIT_CLEANUP_FAILED`。

```kotlin
val failure = host.releaseCapture() ?: return isEpochActive(epoch)
finishTerminalFailure(ConnectionStatus(ConnectionPhase.ERROR), epoch, failure)
return false
```

验证：注入释放异常后不创建身份或打开新 socket；退出仍停止播放、路由并完成终态。清除注入后可按正常路径重新连接。未声称真机音频驱动卡死可被软件强制回收。

### 002 / 003 / 012：恢复槽位、媒体所有权与网络状态提交

生产：[`ReconnectEngine.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/ReconnectEngine.kt)、[`ConnectionCoordinator.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt)、[`SessionGeneration.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/SessionGeneration.kt)。恢复期间的同代失联合并为 `pendingLoss`；当前尝试成功后必须接续它，或在同一锁中释放恢复槽。退出取消整个槽位，旧 job 完成时不清理替代槽。

```kotlin
if (recovery !== owner) return@synchronized false
if (owner.pendingLoss) {
    owner.pendingLoss = false
    true
} else {
    recovery = null
    false
}
```

连接事件提交先核对 epoch 和 listener。在短临界区关闭旧发话门并登记恢复，诊断观察者不参与决定；不再由任意协议线程直接执行等待式播放停止。恢复和终止在 `sessionMutex` 下释放采集/播放，和新尝试使用同一资源边界。离线观察只允许原 epoch、仍处于 `RECONNECTING` 且仍为当前网络观察的更新写入状态。

验证：[`SessionRecoveryRaceTest.kt`](../../app/src/test/java/io/github/ts3mobile/app/service/SessionRecoveryRaceTest.kt) 验证两次失联合并为一次新尝试、退出否决已排队的恢复、旧失败在新会话完成后不能停止其音频，以及迟到离线更新不能覆盖退出终态。全部使用假会话和有界屏障，不访问服务器。

### 004：限时等待频道控制，结果未知时以快照为准

生产：[`Ts3jChannelCommands.kt`](../../ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jChannelCommands.kt)、[`Ts3jClientSocket.kt`](../../ts3-protocol/src/main/kotlin/io/github/ts3mobile/protocol/Ts3jClientSocket.kt)、[`ChannelMoveFeedback.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/ChannelMoveFeedback.kt)。复用 `BoundedSessionCommands`，让命令认领、TS3j 可靠提交和等待回复共享一个 4 秒单调预算。频道命令与文本命令各有一个有界 worker，但互相保持原有 FIFO 提交顺序。保留 `joinChannel` 原接口；新增 `joinChannelIfCurrent`，实际认领时再次检查协议代次、应用 listener 和会话。

```kotlin
when (val outcome = runner.execute(command, timeoutMs, canSubmit)) {
    TextSendOutcome.Accepted -> Unit
    is TextSendOutcome.Rejected -> throw CommandException("Channel move rejected", checkNotNull(outcome.errorCode))
    is TextSendOutcome.NotSent, TextSendOutcome.Unsupported -> throw ChannelMoveNotSubmittedException()
    TextSendOutcome.Unknown -> throw ChannelMoveUnconfirmedException()
}
```

显式拒绝订阅继续走原本的降级路径；订阅没有及时确认则关闭本次连接并发布错误，不把半就绪会话标为 `CONNECTED`。手动和自动移频道都清理 pending 与互斥，未确认不自动重发、不合成新频道。服务端同一 listener 的同目标快照可消除警告；未知的自动恢复只保存实际观察到的频道作为下一次恢复目标。协议队列可能已经接受命令，客户端停止等待不等于撤销；因此提示用户先核对当前频道。

验证：[`Ts3jChannelCommandsTest.kt`](../../ts3-protocol/src/test/kotlin/io/github/ts3mobile/protocol/Ts3jChannelCommandsTest.kt) 使用不接网的命令响应，核对旧参数、计时读取、结果区分及取消；会话夹具核对订阅失败释放和迟到结果代际，`ConnectionCoordinatorTest` 核对手动、首次自动恢复、后续重连不重发、当前快照和退出。未调整真实服务器的发布延迟预算，跨网络实服尾延迟仍待测。

### 005 / 006 / 019：聊天历史的到期、持久化恢复和容量换代

生产：[`ChatHistoryCodec.kt`](../../app/src/main/java/io/github/ts3mobile/app/chat/ChatHistoryCodec.kt)、[`ChatHistoryVault.kt`](../../app/src/main/java/io/github/ts3mobile/app/chat/ChatHistoryVault.kt)、[`ChatHistoryController.kt`](../../app/src/main/java/io/github/ts3mobile/app/chat/ChatHistoryController.kt)。保留未来时间戳，仅淘汰早于留存边界的项目；500 条和 512 KiB 限额不变。重试在事务中解密、重新加密并持久化原档，成功前不重新发放写入票据；失败不得覆盖旧档。清空换代和非阻塞 `trySend` 共享短锁，阻止旧票据回填已清空槽位。

```kotlin
if (record.receivedAtMs < nowMs - LIFETIME_MS) continue
```

```kotlin
if (preferences[ENABLED] == true) {
    preferences[PAYLOAD] = encrypt(decode(preferences[PAYLOAD]))
}
```

验证：两个合成身份在回拨及重新打开后均可读；持续写失败时票据为空，写入恢复后只保存新批；满队列清空后新消息进入、旧消息不复活。未改变加密格式、密钥或默认关闭策略；详见 [ADR 0042](../decisions/0042-opt-in-local-chat-history.md)。

### 007：保持按钮语义并恢复样式门禁

生产：[`IdentityManagementScreen.kt`](../../app/src/main/java/io/github/ts3mobile/app/identity/IdentityManagementScreen.kt)、[`GuideCards.kt`](../../app/src/main/java/io/github/ts3mobile/app/ui/GuideCards.kt)。将身份刷新和关闭提示换为 `OutlinedButton`，保留原有回调、文字和启用条件，不放宽扫描规则。第一批验证：34 项 Python 门禁和 26 项定向 API 35 模拟器测试通过。

```kotlin
OutlinedButton(onClick = onRefresh, enabled = idle) { IdentityActionLabel(UiIcon.Refresh, R.string.identity_refresh) }
OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.guide_dismiss_tip)) }
```

### 010：试麦持有输入时不启动会话采集

生产：[`MicrophoneTrial.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/MicrophoneTrial.kt)、[`MicrophoneController.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/MicrophoneController.kt)。试麦的输入所有权以仍持有的 source 为准，而不是 `IDLE/FAILED` UI 状态，只有关闭成功才能清空。浮窗准备仍不录音；启用录音模式、按下 PTT 以及实际启动采集前，均检查试麦所有权。释放失败时保持发话门关闭，向会话状态报告 `MicrophoneTrialReleaseFailed`，不改 Android 权限或前台录音类型的判定。

```kotlin
if (appVisible.get() || !floatingPttValid || !checkTrialInputReleased()) return null
val token = whenInputReleased { floating.begin() } ?: return null
```

验收入口：`MicrophoneControllerTest.retainedTrialInputBlocksFloatingCaptureUntilTheSourceCloses`。以会抛出关闭错误的假试麦 source 注入故障；浮窗仍可展示，但切换录音模式和按键均不会创建采集，释放后才能恢复。设备厂商的真实麦克风行为仍不由此夹具替代。

### 011：收藏写入使用与读取一致的容量契约

生产：[`ServerBookmark.kt`](../../app/src/main/java/io/github/ts3mobile/app/bookmarks/ServerBookmark.kt)、[`BookmarkVault.kt`](../../app/src/main/java/io/github/ts3mobile/app/bookmarks/BookmarkVault.kt)、[`BookmarkEditor.kt`](../../app/src/main/java/io/github/ts3mobile/app/ui/BookmarkEditor.kt)。新建和更新对主机与昵称使用实际连接的归一化规则，再按存储开关移除不保存的密码。保留旧版本可读档案中的原始字段；编辑器和生产保存使用同一归一化结果判断成功。编码与解码共用 256 KiB 明文上限，Vault 共用 512 Ki 字符密文上限。任一越界都在 DataStore 事务提交前失败，原文件和版本保持不变。

```kotlin
require(bytes.size() <= MAX_ARCHIVE_BYTES) { "Bookmark archive exceeds its size limit" }
```

验证：`BookmarkCodecTest.writerAndReaderShareTheExactArchiveLimit` 检查恰好达到上限的可读档；`BookmarkVaultTest.sizeFailureKeepsReadableCiphertextAndRemovingAnEntryAllowsRetry` 以测试档模拟接近上限，核对越界失败后密文完全一致、删除后可以正常重试。没有自动修改、删除或尝试恢复已损坏的旧档案。

### 008 / 009：跨界面重建保存，目标部分失败后可安全重试

所有权分离：[`VerifiedFileTransfer.kt`](../../app/src/main/java/io/github/ts3mobile/app/ui/VerifiedFileTransfer.kt) 只管理下载，交接后的已验证文件由 Activity 的 [`DocumentSavesViewModel.kt`](../../app/src/main/java/io/github/ts3mobile/app/documents/DocumentSavesViewModel.kt) 持有。根级 [`VerifiedFileSaveHost.kt`](../../app/src/main/java/io/github/ts3mobile/app/ui/VerifiedFileSaveHost.kt) 固定注册选择器并保存请求 ID；关闭原对话框或配置重建不会撤销已交接的保存。文件和附件的 `isCurrent()` 仍按会话、监听器及频道绑定验证。核心入口在 [`VerifiedFileSaves.accept`](../../app/src/main/java/io/github/ts3mobile/app/documents/VerifiedFileSaves.kt)：

```kotlin
val current = operation?.takeIf { it.phase == Phase.PICKING && it.pickerId == requestId } ?: return false
```

源有效且目标未被本次事务标记失败时才写。`copyVerifiedDocument` 在打开输出前、每个 64 KiB 数据块以及完成后复核源所有权，用 SAF 的 `wt` 写入。provider 不提供可依赖的原子替换；如果可能已写入前缀，明确提示目标可能不完整，保留本地源最多 10 分钟，只允许选择不同 URI 重试，不自动覆写或删除原目标。结果失效时可能留下选择器已经创建的文件，也会提示；不会重新下载。进程死亡后不能恢复旧会话所有者，拒绝执行这类结果；缓存中遗弃的暂存另行清理。

证据：`VerifiedFileSavesTest` 覆盖迟到/重复结果、频道变化、失败前缀及过期；`ChannelFilesDialogTest` 实际重建 Activity、移除原对话框后才交付结果，并用本地 provider 写入前缀后失败，再改选 URI 并核对两个文档的字节；`ChatAttachmentDialogTest` 验证附件共用入口。

### 013 / 015：区分媒体新鲜度与设备进展，封闭销毁后的播放器

[`PlaybackTimeline.kt`](../../audio-opus/src/main/java/io/github/ts3mobile/audio/opus/PlaybackTimeline.kt) 以播放头前进或实际接受 PCM 为设备进展。晚醒后用新鲜 PCM 发起一次有界探测；只有零写入确认超过 100 ms 无进展（蓝牙冷启动保留受限宽限）才交给 `PlaybackSession` 计入设备恢复预算。已知来源时间且超过约 240 ms 的未写尾段不参与探测，只记 `OUTPUT_EXPIRED_PCM_SAMPLES`，不补播前缀、不清其他流；过期丢弃本身不重置无进展计时。

```kotlin
if (playedFrames > previouslyPlayed) writeBlockedSince = null
// Expired PCM is dropped before calling output.write().
val blockedSince = writeBlockedSince ?: now.also { writeBlockedSince = it }
check(now - blockedSince < WRITE_TIMEOUT_NANOS || awaitingBluetoothStart(now)) { "Audio output write timed out" }
```

[`OpusAudioPlayer.kt`](../../audio-opus/src/main/java/io/github/ts3mobile/audio/opus/OpusAudioPlayer.kt) 保留可复用的 `stop()`；`close()` 在 `lifecycleLock` 内先设置 `closed` 再停止当前 owner；`start(profile)` 在同一把锁内拒绝晚到的启动。`TeamSpeakService.onDestroy` 在其他资源退场前封闭播放器，主线程不等待 worker `join`。

```kotlin
fun stop() = stop(terminal = false)
override fun close() = stop(terminal = true)
```

证据：`PlaybackTimelineTest` 分别覆盖头前进、过期尾段、真实停滞及蓝牙启动，`PlaybackSessionTest` 核对晚醒没有消耗恢复预算且真实零写仍可回退；`OpusAudioPlayerFocusTest` 用屏障暂停在读取配置之后，让主线程先关闭，验证代际仍为 0、替代播放器可启动，另有 stop/start 重复循环。

### 014：合并通知更新，核对最后一次送达

[`SessionNotifications.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/SessionNotifications.kt) 将普通内容送给 [`LatestNotificationDelivery.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/LatestNotificationDelivery.kt)：仅保留最新内容，常规更新合并窗口 350 ms，投递后用系统当前通知的 revision 核对，未确认时按 600 ms 间隔、每个最终意图最多 3 次。立即提升前台类型或停止的路径不经过合并等待；提升发布的内容若丢失，只补 `notify()`，不会再次提升类型。停止清除待办及动作绑定，`PlaybackNotificationActions` 仍按实时安全静音、会话 token 和路由拒绝旧操作。

```kotlin
when {
    attempts > 0 && confirmed(content) -> desired = null
    attempts >= MAX_ATTEMPTS -> { desired = null; onUndelivered() }
    else -> { attempts++; deliver(content); scheduleOnce(RETRY_MS) }
}
```

证据：`LatestNotificationDeliveryTest` 丢掉发布并核对补投、替换/停止与重试上限；`PlaybackNotificationDeliveryTest` 用系统可见通知核对文案、实际按钮、快速变更后的最终路由动作与过期恢复按钮。若系统禁用通知或持续丢弃全部尝试，仅记录未确认，不无限重试或绕过权限。

### 016 / 018 / 020：按提交恢复，让退场不等待磁盘，显式标记漏采

[`AudioDiagnosticStorage.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/AudioDiagnosticStorage.kt) 在内存暂存 `S/P/E`，直到 `M` 行字段和换行都完整有效才应用。尾端半行、坏标记和无提交的行计入 `invalidRecords`；未提交的 `endedMs` 不会遮蔽 `PREVIOUS_RUN_INTERRUPTED`。完整旧归档沿用相同的 `M` 边界；恢复后先按原有规则重写基线，再追加检查点。报告使用既有 schema，新增计数/事件按 `counterKeys` 和 `kind` 读取；不承诺旧版应用能读回新版扩充后的本地档。

```kotlin
if (metadata != null && !pending.corrupt) {
    pending.sessions.forEach { sessions[it.id] = it }
    samples.addAll(pending.samples)
    events.addAll(pending.events)
}
```

常规 service 日志改由应用级 [`SessionDiagnosticWriter.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/SessionDiagnosticWriter.kt) 持有一个内存日志和 IO 写者。`SessionDiagnostics.close` 只封闭服务生产者、追加 `SERVICE_DESTROYED`、发出合并信号；替代服务随后追加到同一日志，旧 writer 不会持有独立快照覆写新记录。音频采样停止时也不在主线程主动读取设备，由 worker 结束回调提供最终采样。进程在后台提交前被杀时，最终事件可能未落盘。

```kotlin
synchronized(lifecycle) {
    if (closed) return
    closed = true
    writer.append(DiagnosticEventKind.SERVICE_DESTROYED, 0L, System.currentTimeMillis())
}
```

慢加载采用 **显式缺口** 而非延长音频等待：[`AudioDiagnosticController.kt`](../../app/src/main/java/io/github/ts3mobile/app/service/AudioDiagnosticController.kt) 以播放器的单调代际序号检测第一次或中途跳号，在后一个可见代际记录遗漏计数，不伪造早期摘要。

```kotlin
val missed = snapshot.generation - generation - 1
// After beginning the new archive session:
if (missed > 0) repository.event(session, AudioDiagnosticEvent.PLAYBACK_GENERATIONS_MISSED, missed)
```

证据：`AudioDiagnosticArchiveTest` 枚举追加批次所有截断位置，检查坏标记、旧档与恢复后再写；`OfflineAudioDiagnosticsTest` 阻塞写入并在主线程关闭旧生产者，重开后验证事件顺序；另用真实播放器在推迟安装期间完成两代，核对缺口值为 1；`AudioDiagnosticControllerTest` 核对最终回调不重复归档。若服务结束前从未完成诊断安装，仍不会晚挂接或阻塞退场；不能承诺得到该服务的音频摘要。

### 017：诊断导出以操作标识隔离暂存和回调

[`DiagnosticReportActions.kt`](../../app/src/main/java/io/github/ts3mobile/app/DiagnosticReportActions.kt) 在 `SavedStateRegistry` 保存当前选择器的 UUID；[`DiagnosticExportOperations.kt`](../../app/src/main/java/io/github/ts3mobile/app/documents/DiagnosticExportOperations.kt) 从生成报告到 provider 写入及清理完成持续占用同一槽，暂存是各自的 `<UUID>.json`，旧回调不写也不删新档。

```kotlin
if (active != Operation(id, Phase.PICKING)) return false
val writing = Operation(id, Phase.WRITING)
active = writing
```

证据：`DiagnosticExportOperationsTest` 暂停旧写入并验证新导出不能抢占，旧结果抵达时仅新 UUID 能消费，另覆盖重建恢复、provider 异常和清理后的下一次导出。此操作的本地暂存隔离不等同于目标 provider 的原子写入。

### 021：修正身份档的系统备份/设备迁移排除

配置：[`backup_rules.xml`](../../app/src/main/res/xml/backup_rules.xml)、[`data_extraction_rules.xml`](../../app/src/main/res/xml/data_extraction_rules.xml)。`IdentityVault` 使用同一 DataStore 名称定位文件；以下条目分别配置于旧版规则、云备份和设备迁移。保持 `allowBackup=false`，不移动身份密文、不改 Keystore 别名，不改变手动身份导入与导出。

```xml
<exclude domain="file" path="datastore/ts3_identity.preferences_pb" />
```

[`IdentityBackupRulesTest.kt`](../../app/src/androidTest/java/io/github/ts3mobile/app/identity/IdentityBackupRulesTest.kt) 将实际 DataStore 相对路径与 APK 内三处规则比较，并核对应用标志。配置检查不同于实际 OEM 迁移和缺钥恢复验证；后者需真机。

## 交接范围

- **生产代码：** `app` 的会话、文件保存、通知、诊断和本地存储；`audio-opus` 的播放时序/生命周期；`ts3-protocol` 的频道控制及会话绑定。没有引入新依赖、数据库或用户设置。
- **测试：** 三模块 JVM 回归，`app` 与 `audio-opus` 独立 Android 夹具，既有 Python 工程门禁。没有使用生产账户、服务器或密钥。
- **文档：** 本交接、`ARCHITECTURE.md`、`CHANGELOG.md`。`TeamSpeakService` 已超过 300 行；此次仅调整组合与销毁顺序，新增诊断写者、通知投递器均独立。后续若继续扩张，按架构表先抽出 Binder 音频控制，保持入口兼容，再分批迁移调用及测试。
- **配置：** 021 修正两个 Android 备份规则资源，不更改 `allowBackup`，不迁移现存数据；审计期间工作树中已有的构建和发布配置不是本交接的改动范围。

## 复验与发布限制

使用项目支持的 JDK 17、Android SDK/NDK 和缓存的锁定制品。先跑修改涉及的测试，再跑完整门禁：

```sh
python3 -m unittest discover -s tools/tests
./gradlew quality :app:testDebugUnitTest :audio-opus:testDebugUnitTest :ts3-protocol:test
./gradlew :app:lintDebug :app:lintRelease :audio-opus:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:assembleDebugAndroidTest :audio-opus:assembleDebugAndroidTest
git diff --check
```

### 当前制品软件验收（2026-11-25）

| 门禁 | 结果 |
| --- | --- |
| JVM 单元与回归 | `app` 519、`audio-opus` 257、`ts3-protocol` 163 通过，共 **939**；零失败，另有 6 项条件式本地协议探针未启用 |
| Python 工程门禁、工作树 | 34 项通过；`git diff --check` 通过 |
| Kotlin 格式、Lint 与打包 | 三模块 ktlint、`app` Debug/Release 与 `audio-opus` Debug Lint、两个模块测试 APK、Debug 和未签名 Release/R8 均通过；未处理既有警告 |
| Android API 35 与 36 | 每个版本 52 次应用回归、7 次音频回归、22 次通知/前台相关回归，共 **162 次通过**；通知子组在每个版本重复 6 轮，每轮包含 3 个系统通知用例 |
| 打包配置 | 未签名 Release 的 `zipalign -c -P 16 4` 通过；Manifest 维持 `allowBackup=false`，云备份与设备迁移资源引用仍在 |

通知首次组合运行会读到上一绑定夹具留下的同 ID 通知；在通知夹具开始前清理该通知并确认其已移除，重新构建测试 APK。最终首轮与浮窗召回及真实前台类型测试相邻，随后独立重复五轮，两种 API 全通过。可在安装调试/测试 APK 后复跑通知子组：

```sh
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.ts3mobile.app.service.PlaybackNotificationDeliveryTest \
  io.github.ts3mobile.app.test/androidx.test.runner.AndroidJUnitRunner
```

本机证据位于忽略目录 `build/reports/round-3-remediation/`：Gradle、Python、JVM 汇总、各 API 测试和 Release 对齐/Manifest 日志。只统计 `OK` 的终验执行，未把首轮夹具失败计入通过数。

### 分批复验记录

第一批：910 项 JVM 通过，6 项协议探针因环境条件跳过；API 35 定向 26 项通过；格式、Lint、Debug 与未签名 Release 通过。

第二批：914 项 JVM 通过，6 项协议探针跳过，34 项 Python 门禁通过。API 35 跑身份备份规则、身份事务和连接浮窗，共 8 项通过；Release 合并 Manifest 保留 `allowBackup=false` 及两个资源引用。三模块格式检查、Debug/Release Lint、Debug/未签名 Release/R8 与 `zipalign -c -P 16` 通过。没有执行 OEM 设备迁移、真实服务器连接、真机长稳或同签名发布升级。

第三批：924 项 JVM 通过，6 项协议探针因环境条件跳过，34 项 Python 门禁通过。API 35 收藏／频道 23 项通过；API 35 和 36 录音／浮窗各 20 项通过。格式、Debug/Release Lint、Debug/测试 APK 与未签名 Release/R8 构建通过。全部使用假会话或隔离模拟器；没有使用真实服务器、物理蓝牙或生产签名。

### 发布前仍需验收

- 在物理设备上验证蓝牙冷启动及切路由、锁屏长稳和服务销毁耗时分布。模拟器通过不等于已验证 OEM 音频栈、省电策略和蓝牙设备。
- 验证同签名覆盖升级、受控系统备份/设备迁移及缺钥时的行为。未签名 Release 与配置核对不能替代这些场景。
- 如需启用独立协议探针，只用本地夹具或经授权的测试环境，不使用生产凭据。本轮未连接生产服务器。
