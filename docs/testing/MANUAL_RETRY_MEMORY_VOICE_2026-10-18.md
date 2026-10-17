# 2026-10-18 连接限制、表单记忆与语音修复报告

## 本次范围和结论

按用户确认，仅移除连接数量限制后的自动重试；正常网络中断后的自动恢复保留。
诊断和后台运行检查收进“更多选项”，断开按钮移至服务器信息右侧并使用强调底色。
新增上次表单的地址、端口、昵称和服务器密码记忆；修复暂空音频队列提前结束讲话的问题。
未修改版本号、身份格式、成功服务器历史格式或依赖版本，未提交或发布 Release。

## 根因与实现

### 连接限制和临时封禁

小米桌面划掉任务可能先杀死持有 UDP 连接的进程，后给新服务交付任务移除回调。
新进程没有旧 socket，无法补发有效退出；服务器只能等旧会话超时。此前固定 60 秒
恢复窗口内按 1/2/4/8 秒重试，与手动重连叠加，曾观察到 28.82 秒内七次尝试。
用户报告随后暂时被 ban，稍后自行恢复；未捕获服务器具体反滥用规则，不能断言其阈值。

协议分类保留 CLONES_LIMIT，但标记为不可重试。协调器收到拒绝后原子解绑监听器，
停止重连，关闭对应 socket 后取消握手，保留原始拒绝说明；旧回调不能复活连接，
也不能取消新请求。删除原来的 ClonesRecoveryEngine/Policy 和过时测试。
其他协议命令拒绝原本已是终止行为；正常网络恢复策略未扩大或关闭。

中英文提示解释旧连接暂留与其他同身份客户端占用，建议至少等 60 秒后手动重连，
提醒频繁尝试可能触发临时封禁，并指导先点击应用内/通知栏断开再划掉应用。
60 秒是等待建议，不是自动任务、强制冷却或服务器解封承诺。提示随表单滚动。

### 表单记忆

MainViewModel 管理表单和串行保存；独立 settings 包负责快照、恢复合并与加密存储。
整个快照使用 AES-GCM、随机 IV 和独立 Android Keystore 密钥保存于应用私有 DataStore。
编辑后约 150 毫秒合并写入，点击连接会等待保存尝试；保存失败显示提示，仍允许连接。
恢复不会覆盖已编辑字段，切换地址/端口或选择另一服务器会清空旧密码，避免串用。

首次升级没有快照时使用原有成功地址历史；旧版本未保存的昵称和密码无法凭空恢复，
需要重新输入一次。当前表单和十条成功地址历史相互独立，删除历史项不会清空当前表单。
清空字段并保存会替换记忆；清除应用数据/卸载会删除快照。云备份和设备迁移显式排除快照。
身份使用原来的保险库，不重新生成。进程在延迟保存完成前被杀时，最后编辑可能丢失。

### 语音队列

旧适配器把“编码队列有帧”当作 ts3j Microphone.isReady。编码线程和发送定时器有调度差，
队列暂空属于正常情况，但固定版本的 ts3j 将 isReady=false 解释为讲话结束并发送空结束包。

新增独立 TalkspurtMicrophone，以采集是否仍在运行判断发送状态。暂时无帧时保持 ready，
provide 返回空数组，让 ts3j 跳过这个 tick；停止采集后 ready=false，才发送结束标记。
采集源异常只报告一次并结束 readiness。EncodedVoiceSource 新属性有默认实现，保持源码兼容；
所有模块一起重新构建，不承诺对外部已编译实现的二进制兼容。

## 测试与修复过程

- 先用实际固定版本 ts3j 的 MicrophoneTask 加假 datagram 入口写回归，旧适配器失败；修复后通过。
  连续十次空队列间隔后恢复语音，实际有效包长度为 [4, 4]，明确停止后才变为 [4, 4, 0]，结束包仅一次。
- 协调器假 socket/虚拟时钟验证拒绝后五分钟无自动重试、手动新请求、晚到成功/快照隔离、
  已连接会话恢复中收到限制同样终止；真实线程 fixture 验证阻塞握手关闭顺序和新请求隔离。
- 表单 JVM 测试验证序列化、Unicode、空密码、版本校验、脱敏字符串、晚到读取与跨服务器密码隔离。
- 真机使用独立应用 ID io.github.ts3mobile.app.verify18，只有 example.com 和虚构密码，不连接服务器。
  安装曾被小米记住的拒绝阻断，换名称/包名后安装成功。Windows 模拟器因 WHPX 权限错误无法启动。
- 真机首次运行暴露 JUnit 方法返回类型、可编辑字段包含标签的语义断言和测试语言隔离问题，均修正测试。
  Popup/Dialog 使用 Android View 资源，测试主界面仅覆盖 Compose locale 会导致语言不一致；
  fixture 现在同步 View 资源并在销毁时恢复。小米阻止测试从后台启动 Activity 时，外部测试脚本
  仅在系统实际拒绝后用同一启动 Intent 拉起独立测试 Activity，不更改系统安全权限。

## 验证结果

- 完整门禁通过：quality、协议测试、音频单元测试/Debug Lint、App 单元测试、Debug/Release Lint、
  Debug 和开启 R8 的 Release 构建。261 项 JVM 测试：app 149、audio-opus 20、ts3-protocol 92，零失败。
- 首轮高并发构建中，五项既有 250 毫秒真实线程退出测试受调度影响失败；单独完整重跑协议测试通过，
  最终门禁以 --max-workers=2 完成。未放宽生产退出预算或测试断言。
- Lint 为零错误；Debug 36 条、Release 3 条警告，包含既有未使用资源等提示，不代表零警告。
- 实际混淆 Release APK 在手机 ART 上通过 IDENTITY_ROUNDTRIP_OK、INI_STORE_ROUNDTRIP_OK、
  RELEASE_IDENTITY_SMOKE_OK，确保 issue #3 的反射修复仍成立。
- 资源键中英文一致；仓库隐私扫描和 git diff --check 通过。
- 小米 Android 16 真机独立包：OK (28 tests)，零失败。额外结束整个独立包进程并重新启动，
  通过 PROCESS_RESTART_FORM_RESTORE_OK，四个字段均恢复，始终未触发连接。
- 更新前主应用已通过自身断开按钮退出，事件记录 EXIT_SERVER_CONFIRMED；随后 install -r
  安装正式 applicationId 的 Debug APK，已核对设备 APK 与本地产物哈希一致，启动停留未连接表单。
  原主应用数据/身份保留；新建 verify18 及其 test 包已卸载，既有其他测试包保持。
- 最后仅测试源码变更后，再次通过 quality、App Debug Lint 与标准 Debug 构建。

本机证据位于忽略目录 build/manual-retry-memory，包括最终门禁、语音红灯回归、真机测试、
离线合成界面 PNG、身份探针及隐私扫描日志。图片仅使用 fixture，不将真实连接界面纳入报告。

## 主要复核命令

```powershell
.\gradlew.bat quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease --max-workers=2 --console=plain
pwsh -NoProfile -File tools/check-release-identity.ps1 -DeviceSerial <device> -SdkRoot <android-sdk>
pwsh -NoProfile -File tools/check-repository-privacy.ps1
git diff --check
```

真机测试使用忽略目录中的 init script 为 Debug 临时增加 .verify18 后缀，正式构建不使用该脚本。
独立包装好后运行 `adb shell am instrument --no-window-animation -w -r -e package
io.github.ts3mobile.app.ui io.github.ts3mobile.app.verify18.test/androidx.test.runner.AndroidJUnitRunner`。
小米拒绝测试后台 Activity 启动时，外部脚本仅在观察到 result code=102 后发起同一 Intent。
需检查输出 `OK (28 tests)`；adb 返回码本身不能证明所有断言通过。

## 修改分类

- 生产代码：app 协调器/ViewModel/settings/Compose 界面，协议失败分类与麦克风适配器，
  audio-opus 采集活动状态。既有大文件只做当前行为所需修改；新增存储与麦克风职责放入独立文件，
  删除已废弃的重试引擎。协调器后续拆分监听器/网络检测的计划仍见 ARCHITECTURE.md。
- 测试：独立 settings/voice 回归、协调器终止与并发用例、真机表单/菜单/大字体检查，移除旧自动恢复测试。
- 文档：双语 README、PRIVACY、ARCHITECTURE、CHANGELOG、协议 README、后台验收矩阵与 ADR-0011；
  原自动重试报告和 ADR-0009 标为历史，避免混淆当前行为。
- 配置：备份/设备迁移 XML 排除新快照；独立验证构建脚本仅在忽略目录，不改变正式包 applicationId。
  无新增用户配置项，无依赖或签名更改。此前 issue #3 的 R8/CI/探针修改保持。

## 尚未覆盖与发布判断

不承诺修复厂商划掉任务造成的掉线；正常退出仍应先点击断开。此次未向真实服务器反复连接，
未主动触发 ban，也不声称证明了该服务器的具体释放时间。暂空队列修复有实际 ts3j 发送器回归，
不等于完成真实 Bluetooth、长时间双工、锁屏、密码频道恢复或第二台设备验收。
当前功能改动可交付体验，完整正式 Release 仍应完成 M11 清单中尚未通过的设备/签名/版本审核项。
