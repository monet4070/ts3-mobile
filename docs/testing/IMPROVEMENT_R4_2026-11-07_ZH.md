# R4：最终组合候选验收

日期：2026-11-08。状态：本轮授权的全部离线／模拟器工作包及最终本地组合验收完成。
最终生产候选为D2 `417ebf1ed6614601bd7a0a1a1d61f97e1465f88d`；R4只增强测试夹具与同步文档。
最终草稿PR／云端门禁在文末单独记录；本记录不是公开发布授权。

## 修改前理解与方案

最终生产候选沿用D2 `417ebf1`，不在验收阶段更改业务、版本、权限、依赖或生产签名。
已读升级／进程终止runner、测试专用UpgradeDataProbe、证书解析、真实Vault／调音／身份事务夹具及
发布探针。runner明确只接受fresh emulator，已有app／probe不能卸载绕过；每次独立创建测试AVD。
主模拟器上的完整Android和探针完成后停机保留，再顺序运行新AVD，避免Gradle安装到多个设备。

测试专用UpgradeDataProbe修改前259行、修改后275行，本次只增加明确的record-appearance阶段与可选原文件摘要校验，
仍低于约300行；不迁移已有Vault／Keystore验证，以免验收工具发生多余变化。
默认严格校验仍要求原三Vault、原密钥、UID及可选调音字节保持；appearance marker存在时必须另外保持。
记录阶段只接受已经seed的合成夹具且只记录一次，不读取真实用户数据，不加入生产测试开关。
未来如继续增加职责，应先按seed／raw验证／反射适配拆测试工具，再以原fixture对照迁移。

## 固定验收项

- 全部JVM、格式／Lint／类型构建、18项选定离线音频Android、完整app Android；保留外部夹具默认跳过。
- 明确冻结D2 Debug／unsigned minified／test APK；实际minified身份与Opus探针，不用重打包替代已测候选。
- fresh AVD：B1 Debug→最终Debug与B1 Debug→最终测试签名minified；原Vault／keys／UID／调音保全。
- fresh AVD：D1 Debug实际UI选择Dark、记录appearance字节→最终Debug→最终minified，
  两次覆盖升级后实际UI／force-stop读回Dark，原全部数据与appearance保全；故意改主题时严格校验必须拒绝。
- fresh AVD：八个真实identity事务边界进程终止后恢复／损坏与缺钥保全，不生成默认身份。
- 汇总全部阶段草稿、精确源码／APK、云端报告、回退及未验证范围；无合并、发布、版本提升。

## 全量回归与最终产物

| 门禁 | 实际结果及范围 |
| --- | --- |
| JVM | app384／protocol133／audio219，共736项，零失败／错误；本轮三个test任务实际执行，未用旧XML替代 |
| 格式／类型／静态 | quality、audio Debug Lint、app Debug／Release Lint、Debug／Release及test APK构建通过；保留原app3 Warning／1 Information |
| 完整app Android | API35 ARM64／4KiB：XML212项，210通过／2外部fixture默认跳过／零失败；不是CI选定139项的替代统计 |
| 离线音频Android | 同一4KiB模拟器，选定18项全部通过；不运行录音／可选APM实验 |
| 最终native player复核 | 六条JSON：B有效未混音4320样本保持，不因A跨零销毁／重建B decoder；输出仍安全退役 |
| minified身份 | 最终unsigned Release实际ART身份／INI往返及Release反射探针通过 |
| minified Opus | 最终unsigned Release在4KiB及另一fresh16KiB模拟器实际JNI往返，libopus1.6.1 |
| 工具 | Python18项全部通过；独立备份向量核对、隐私／XML／YAML／本地链接／空白校验见收尾门禁 |

主要命令在已配置JDK17／SDK35／NDK27环境下顺序执行：

```sh
./gradlew quality :ts3-protocol:test --rerun :audio-opus:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :audio-opus:lintDebug :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --max-workers=2 --no-build-cache
./gradlew :app:connectedDebugAndroidTest --max-workers=2
./gradlew :audio-opus:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ts3mobile.audio.opus.OpusAudioPlayerFocusTest,io.github.ts3mobile.audio.opus.NativeAudioLifecycleTest,io.github.ts3mobile.audio.opus.PlaybackLifecycleTest,io.github.ts3mobile.audio.opus.MicrophonePipelineTest,io.github.ts3mobile.audio.opus.ParticipantMuteMeasurementTest,io.github.ts3mobile.audio.opus.ParticipantMuteIsolationTest --max-workers=2
python3 -m unittest discover -s tools/tests -p 'test_*.py'
```

只有一台设备连接时运行上述Gradle；fresh升级与进程探针另行指定serial，过程中没有并行Gradle。
增量格式／编译／Lint的任务包含UP-TO-DATE，三个JVM test任务为本轮执行，不能把217个任务全称为重跑。
精确候选复制自已冻结的D2产物，未重打包冒充已测APK；生产输入与D2逐项一致。

证据根目录为本仓库`build/improvement-2026-11-07/`：
`r4-full-gate.log`、`r4-app-android.log`、`r4-audio-android.log`、`r4-tools.log`，
原XML保存于`r4-results/`，native合成测量为`r4-mute-measurement.json`。
minified身份为`build/release-identity-smoke/10f623115e3b4b9f91de205514144956/`；
4KiB Opus为`build/release-codec-smoke/d1dfea0dcac54973a456df8ffdab80a3/`，
16KiB Opus为`build/release-codec-smoke/125301af8fee4d3a8a43cc691ff56d64/`。

| 最终本地产物 | SHA256 |
| --- | --- |
| `r4-app-debug.apk` | `6bd31f5640209b87f5d0f7a4748ae19073260e003120b41f77e48ccbc9428686` |
| `r4-app-release-unsigned.apk` | `067eeed402fa14583ea7b04fbaa598b5eae82971642077fa1ce14fe22e4640b9` |
| `r4-app-release-test-signed.apk` | `1e67099d8552dd5f202155477ceee578ebd1768e71f64832b7d4fb07565885b0` |
| `r4-app-test.apk` | `27a71e7800f5e4bbf012c10cc3e612a83d2afa55f82ea54b5b2bdc136546b265` |

三份已签名测试APK使用相同标准Android debug证书，证书SHA256为
`d204d1ba57de3d353b6f1871aef5fefc0d47d6b51a889e9f8990fd2eff1a32e5`。
没有使用生产私钥；unsigned产物不冒充公开分发包。`r4-artifacts-sha256.json`保存原摘要。
`r4-source-sha256.json`冻结572个源码／测试／工具／配置输入，相对D2唯一差异为本次升级测试Java夹具。
该清单自身SHA256为`1888bbafbae10f6b80fe7e899019419623821b79b3668276aff7e9a8dba9932c`。

## 四台fresh模拟器验收

原主模拟器停止后数据保留；四台本任务新建API35 ARM64 AVD顺序启动，host音频输入／输出关闭。
每个独立夹具首次安装前均无app／probe，不卸载已有安装绕过fresh限制。
全部结束后均停机并保留数据，清单为`r4-avd-manifest.json`和`r4-fresh-final-summary.json`。

| AVD／serial | 页大小 | 已通过证据 |
| --- | --- | --- |
| `ts3-r4-crash-16k-api35`／5590 | 16384 | 八个真实identity事务进程终止点与最终minified Opus |
| `ts3-r4-upgrade-debug-api35`／5592 | 4096 | B1 Debug→最终Debug，真实三Vault／原keys／UID／四调音值及原文件保全 |
| `ts3-r4-upgrade-minified-api35`／5594 | 4096 | B1 Debug→最终测试签名minified，原三Vault文件／原keys／UID／调音文件保全 |
| `ts3-r4-theme-upgrade-api35`／5596 | 4096 | D1 Debug选Dark→最终Debug→最终minified，两次force-stop后实际UI保持Dark，原全部数据与appearance字节保全 |

### 身份进程终止及覆盖升级

八个点为before-transaction、during-transaction、after-transaction、before-cleanup、after-cleanup、
damaged-active、damaged-both、missing-key，均终止原进程再启动验证；defaultIdentityCreated=false。
证据`build/identity-crash/ccfca5c0cfdc42a9a33d39dd49a55e45/result.json`。
本次16KiB仅证明这些身份恢复边界与minified Opus，不宣称完整16KiB UI／音频套件通过。

升级调用`tools/check-upgrade-compat.py --include-tuning`，B1基线Debug SHA为
`ab73d8c32181bed8849733b587a09d6b9d5ccb31721bed6c3dd9a0367da78872`；
最终Debug／minified摘要见上表，安装为同测试证书覆盖，code14→code14，不卸载／清数据。
Debug证据`build/upgrade-compat/a7c66b1f48144a04bfed208a3fcb12d3/result.json`；
minified证据`build/upgrade-compat/12f1a9004a28434c8186b4414b51a6e1/result.json`。
Debug通过真实Vault方法读回身份材料、连接表单、收藏与四调音参数。
minified严格验证原文件摘要、原Keystore密钥解密challenge和UID，不能称真实Vault方法解密读回；
该区别保留在原JSON的false字段，没有把raw检查升级成更强声明。

### 主题升级与负向检查

先在fresh D1 Debug通过真实Vault建立合成夹具，再由实际UI选Dark并等待保存完成。
新增测试阶段`record-appearance`只可基于原合成marker记录一次，且必须先通过原raw验证。
随后独立验证：

1. 重复记录被拒绝，marker原始字节及三Vault／调音／UID保持。
2. UI改Light，独立hash证明仅appearance变化，严格`verify-raw`拒绝；没有因失败重录基线。
3. 改回Dark后全部hash恢复，严格raw再次通过。
4. 覆盖最终Debug并force-stop，真实Vault值保全，实际UI读回Dark。
5. 继续覆盖最终minified并force-stop，原文件／原keys／UID及appearance保全，实际UI仍Dark／Disconnected。

首次临时驱动误要求fixture输出完整异常文字，触发断言失败。fixture按隐私设计只报告固定
`IllegalStateException`类别，因此没有改为输出异常消息，也没有卸载／重seed。
保留首次失败日志后，从同一夹具、同一已记录marker续跑，补上独立字节／hash负向检查，最终全部通过。
这属于验收驱动断言错误，首次运行不记为全绿。
原日志`r4-fresh-acceptance.log`、`r4-theme-resume.log`及各`r4-themeUpgrade-instrument-*.log`；
最终结构化证据`r4-theme-upgrade-result.json`。
已检查`d1-r4-theme-debug-upgrade-dark.png`与`d1-r4-theme-minified-upgrade-dark.png`，
两次最终真实UI为Dark且未连接，测试未连接服务器／使用真实数据／录音。

## 全阶段完成范围、收益与回退

| 阶段／草稿PR | 已交付的收益及证据 |
| --- | --- |
| P0／A1／Q1，[#8](https://github.com/monet4070/ts3-mobile/pull/8) | 可执行工具链；聊天跨页签保留阅读位置，核心UI接入CI |
| A2，[#9](https://github.com/monet4070/ts3-mobile/pull/9) | 调音参数可靠恢复，晚读取不覆盖新修改 |
| A3／R1，[#10](https://github.com/monet4070/ts3-mobile/pull/10)／[#11](https://github.com/monet4070/ts3-mobile/pull/11) | 当前频道收藏与可见加入，第一批覆盖升级 |
| A4／A5／R2，[#12](https://github.com/monet4070/ts3-mobile/pull/12)／[#13](https://github.com/monet4070/ts3-mobile/pull/13)／[#14](https://github.com/monet4070/ts3-mobile/pull/14) | 私信提醒／过期点击保护、采集期间门控电平反馈，第二批升级 |
| C1／C0／C2／R3，[#15](https://github.com/monet4070/ts3-mobile/pull/15)–[#20](https://github.com/monet4070/ts3-mobile/pull/20) | 原加密格式兼容、自有加密备份／跨安装导入／明确替换／本地回退；进程终止不静默换身份 |
| B1，[#21](https://github.com/monet4070/ts3-mobile/pull/21)／[#22](https://github.com/monet4070/ts3-mobile/pull/22) | 同一输入对照由额外丢弃B未混音90ms／重建decoder变为零额外丢弃／保留decoder；不是可闻缺口证明 |
| D1，[#23](https://github.com/monet4070/ts3-mobile/pull/23) | 独立持久主题，未设置仍浅色；实际键盘不遮住弹窗操作 |
| D2，[#24](https://github.com/monet4070/ts3-mobile/pull/24) | 监测约百行可独立测试，保留协调器所有权／原时间策略，不声称省电收益 |
| R4 | 冻结最终候选、全量组合与四fresh验收，明确公开版／开发候选文档边界 |

各阶段精确提交、用例和云端证据见[执行进度](../plans/IMPROVEMENT_PROGRESS_2026-11-07_ZH.md)，
不相加历次测试数量、不把原估算人日当实际人员节省。所有PR保持草稿，按堆叠顺序评审；未合并／发布／改版本。
UI／主题／监测可按独立提交回退；新增偏好由旧版忽略，不降级删除。
B1可回退保守全局失效实现，保留测量回归。C0保留旧格式／alias；身份替换不能随APK回退自动恢复，
须用已验证的本地回退或外部备份，损坏／缺钥材料保全而不承诺可解密。正式用户回退需递增code重发，不能要求卸载清数据。

变更分类：生产代码无新增修改；测试为UpgradeDataProbe记录／校验appearance的16行，
以及下述IdentityMaintenanceServiceTest通知移除同步修正；
文档为R4、进度／执行计划、D1／D2云端结果、双语README、CHANGELOG与兼容矩阵；配置／权限／依赖／版本无变化。

## 未验证范围

实体锁屏通知、麦克风与听感、扬声器／有线／蓝牙物理路由、OEM后台、真实服务器组合、未测API、
云文件提供方持久性、生产签名升级及独立安全审计未执行。
B1仍退役输出，无flush的50%声音缺口指标未验证；不能宣传实体听感已解决。
这些边界按维护者本轮范围留待后续，不把“本轮离线阶段完成”解释为正式发布门禁全部满足。

## 收尾门禁与云端证据

原PowerShell仓库隐私脚本通过；50个仓库XML与4个GitHub YAML解析通过，
162个本次变更Markdown本地链接目标及代码围栏检查通过；独立备份向量与fixture逐字节一致，
`git diff --check`通过。572个输入及四份APK摘要再次核对保持；生产目录／构建配置无差异，ADB无活动设备。
YAML使用系统Ruby的Psych解析，未把缺少PyYAML写成校验通过。

D1[CI42442283536](https://github.com/monet4070/ts3-mobile/actions/runs/42442283536)与
D2[CI42443547332](https://github.com/monet4070/ts3-mobile/actions/runs/42443547332)均success且已下载报告，
分别核对370／133／219及384／133／219 JVM，均app139／audio18 Android，零失败／错误。
R4最终云端状态与对应报告以最终草稿PR的Checks及按提交命名的artifact为准，
不把D1／D2的旧CI当作R4新提交通过证明。云端x86_64选定套件不代替本地ARM64完整套件或上述fresh验收。

R4测试／验收源码`e477164348e3e2beee23916cd91ffe1fe3cb3396`，
已提交堆叠草稿[PR #25](https://github.com/monet4070/ts3-mobile/pull/25)，base为D2 `codex/session-monitoring`。
仅补PR链接的`ec4589f`不改变上述572个输入或本地APK；其后测试修正边界见下文，最终HEAD的Android CI及下载报告另在该PR更新。

### 最终CI发现的通知移除测试竞态

`ec4589f`的[首次最终CI42445471798](https://github.com/monet4070/ts3-mobile/actions/runs/42445471798)
失败并保留报告：384／133／219 JVM、audio18 Android与八个进程终止点通过，
app139项中138通过，只有IdentityMaintenanceServiceTest第54行立即查询activeNotifications的断言失败。
该用例仅运行0.032秒；Service先执行真实维护提示的前台提升及`stopForeground(REMOVE)`，再发布维护状态，
而Android通知服务的移除结果异步可见。原断言错误地把状态发布等同于系统通知列表已经收敛。

只修测试：保留原Disconnected／serverLabel空／identityReady=false／isCapturing=false断言，
最多5秒等待通知4103移除，仍需原assertFalse成立；保留12秒进程存活检查，并再次断言通知没有残留。
没有修改Service／SessionNotifications、放宽结果或延长生产超时。
同类Poke／私信通知测试已有有界等待，使用一致的系统异步边界。
修正后真实Appearance接线＋维护服务两项通过，quality通过，原XML保存于
`r4-notification-removal-fix-results/`，日志`r4-notification-removal-fix.log`。
两次额外独立服务仪器调用均通过12秒存活／无通知残留复核，另存`r4-notification-removal-repeat-{1,2}.log`；
不能把首次失败写成重试后原提交通过。原主模拟器再次停机保留；四台fresh验收AVD未重启或改数据。

原`r4-source-sha256.json`及四份APK作为前述完整／fresh验收证据保持不变。
测试修正后另冻结572输入`r4-validation-source-sha256.json`，相对D2仅升级probe与本项Android测试变化，
清单SHA256为`69db46f1c2a7a01b70c9ee0bbb6e7159bdb055aa3ae36a8bb754b99ef552a552`。
最终测试APK为`r4-app-test-final.apk`，SHA256
`872c6a1b8a10eeae18fdf81914dc8bc3fd5c06320b5571cac42bd474cdac6a8e`；
原Debug／unsigned minified／test-signed minified产物保持上表摘要，全部生产输入保持D2一致。
