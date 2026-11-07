# R2：第二批组合与覆盖升级验收

日期：2026-11-07。按维护者范围只做离线／模拟器，不连接服务器、不录音、不合并或公开发布。第二批生产候选为 A5 `3adcbdb`；R2 无生产代码变化。

## 候选与验证方法

旧版本取第一批 R1 保存的 Debug 候选，SHA-256 `e51aed7e0d1f954a9034055334e310bc968f1461e411645627571f35950c895c`，对应 A3 生产实现 `827f851`。通过两个独立新 AVD，分别覆盖到当次 Debug 和混淆 Release 测试签名副本。两者沿用1.0.0/code14，仅用于同code测试更新，不代表公开升级版本。

| 第二批产物 | SHA-256 |
| --- | --- |
| Debug | `bc58b32d7825e3ccc9ef19b382999d4e54745c53869d6b5472cf6c5779508c42` |
| Minified unsigned Release | `396dd71751b79b6cdaad912f7b4bff91fc0270ea79d2c329f063e08c086989e2` |
| Minified Release测试签名副本 | `656333ac6388e6e9679cc88fede6a0f4d88fdebdc517a6c065e4e93110822c66` |

签名只使用标准Android开发测试证书；签名副本与unsigned每个原ZIP条目的字节完全一致。没有生产密钥、R8 keep或生产fixture分支。Debug／Release的DEX均检查不含升级探针标识及挑战字符串。

## 结果

| 验证 | 结果及证明范围 |
| --- | --- |
| Debug覆盖升级 | 旧真实Vault创建合成身份、表单、收藏；身份材料摘要、表单、收藏频道42／密码／revision、UID一致。三原密文及原Keystore密钥挑战通过 |
| 旧调音兼容 | 从第一批真实偏好store写入147%、noise=false、echo=false、threshold=-28；升级后真实store四值读回一致，整个偏好文件SHA不变 |
| Minified覆盖升级 | 三原密文、原密钥挑战、UID及旧调音文件SHA保持。没有反射R8优化的Vault／调音方法；相应JSON明确标注readBack=false |
| 实际界面 | Debug实际Activity读到旧表单、147%及麦克风关闭。Minified两次实际Activity启动、中间force-stop，均读到旧表单和147%、麦克风关闭、断线时没有PTT。之后再验原Vault／密钥／UID／调音字节通过 |
| Release解析／JNI | 当次unsigned产物在ART上身份／INI往返、Opus1.6.1版本与编解码探针通过 |
| 组合流程 | 新增独立SecondBatchExperienceTest：合成通知目标经过真实registry／Intent／entry进入正确私聊→输入草稿→调音→正常PTT按住／cancel→关闭→切页→Activity暂停／恢复；其他私聊未读、草稿及停止状态保持。1项通过 |
| 测试入口边界 | 组合用例使用fake采集反馈，没有发包或录音；真实PendingIntent冷／热Activity与Binder由A4独立验收，不能把组合fixture描述为真实服务器端到端交付 |
| 测试工具 | 升级runner新增显式--include-tuning，仅支持A2+基线；默认旧基线行为不变。物理设备／已有包拒绝边界保留；Python工具10项通过 |
| 门禁 | app格式／quality、Lint Debug与组合仪器通过；Java probe在两次实际升级中编译／dex／签名／执行通过；隐私、YAML、文档链接和whitespace通过 |

第二批完整生产回归及最后差异补验见[A4](IMPROVEMENT_A4_2026-11-07_ZH.md)与[A5](IMPROVEMENT_A5_2026-11-07_ZH.md)。R2未修改生产源码，逐文件核对其SHA仍匹配A5最终清单；没有重复跑全套并把历史数量算成本阶段新执行。

## 工具与隔离

```sh
python3 tools/check-upgrade-compat.py --device "$fresh_debug_emulator" --sdk "$ANDROID_HOME" --baseline-apk /path/to/r1-debug.apk --candidate-apk /path/to/r2-debug.apk --include-tuning
python3 tools/check-upgrade-compat.py --device "$fresh_release_emulator" --sdk "$ANDROID_HOME" --baseline-apk /path/to/r1-debug.apk --candidate-apk /path/to/r2-minified-test-signed.apk --include-tuning
```

runner验证真实emulator、空白安装、包名、版本和证书，使用install-r更新，不卸载清数据来通过。只在新空白模拟器中创建合成数据；既有原文件不会被清理或覆盖来规避问题。Debug使用任务AVD `ts3-r2-debug-api35`／emulator-5568，Release使用 `ts3-r2-release-api35`／emulator-5570；均API35／Android15／arm64／4KiB。验收结束已停止本任务新建的两进程，AVD数据保留供复查。

## 本机证据

- `build/upgrade-compat/d6e5c15674234ec49bf36ce0721a20bc/`：Debug真实Vault／四调音参数读回和原字节／密钥／UID结果。
- `build/upgrade-compat/6e1a92a6d7154d10847cbe25ef16cabe/`：Minified原字节／密钥／UID／调音结果。
- `build/improvement-2026-11-07/r2-{debug,release}-upgrade.log`、`r2-*-ui-result.txt`、`r2-*-post-ui-vaults.log`：实际升级及设置页／force-stop后再验记录。
- 同目录 `r2-release-identity.log`、`r2-release-codec.log`、`r2-combined.log`、`r2-combined-results/`：Release探针和组合JUnit／Lint XML。
- `r2-release-tuning.png`及设置页XML：仅合成fixture，已目视查看，不提交设备文件／APK／签名。

## 变更分类与剩余边界

- 生产：无；沿用A5候选。
- 测试：升级探针增加可选旧调音fixture；新组合UI用例；1项CLI设备保护回归。
- 工具／配置：runner显式参数与结果边界字段；CI选入组合用例。
- 文档：贡献指南、本记录、进度。

第一、二批本轮离线／模拟器验收完成。公开生产证书、递增versionCode升级、实体锁屏／OEM后台、物理音频／路由、真实服务器均未验证。旧数据保全以同设备Keystore仍可用为前提；跨设备身份恢复由后续C1/C0/C2处理，不能从本次同签名更新推导。
