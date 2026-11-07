# R1：第一批组合与同签名覆盖升级

日期：2026-11-07（Asia/Shanghai）。范围按维护者指示限定离线与模拟器，不含公开发布或物理设备。

## 产物与方法

- 旧基线从`7a1e36e`通过`git archive`生成独立源码目录，使用相同JDK17／SDK35／NDK／CMake与锁定依赖构建Debug，指定arm64 ABI与已校验的Opus源码包。61个任务中52执行、9缓存；它是新构建，不是新测试。注入ABI使此APK标记testOnly，安装明确使用`-t`。
- 当前候选来自A3生产实现`827f851`；后续`c8f7be4`只修正UI测试与记录，没有改变候选APK生产代码。Debug与minified unsigned Release均沿用1.0.0/code14，属于同code的测试更新，不是新公开版本。
- 用两个独立、首次没有目标包的API35 / Android15 / arm64 / 4KiB专用模拟器分别验证Debug与minified候选；运行中只用`install -r`覆盖，没有卸载／清空数据。
- 三份实际Vault由旧Debug应用代码创建：合成身份、合成加密表单和含两项合成密码／默认频道42的收藏。测试探针仅在独立instrumentation APK中编译，未修改生产代码或R8规则。
- minified APK使用标准开发测试证书签名副本，与基线／probe相同。逐一比较unsigned与签名副本的每个ZIP条目字节，生产DEX／资源／native内容完全不变；这不是生产证书验收。

| 候选 | SHA-256 |
| --- | --- |
| Debug | `e51aed7e0d1f954a9034055334e310bc968f1461e411645627571f35950c895c` |
| Minified Release测试签名副本 | `07e668d2b8a32e90bfed5ed7c09353b447550f9675e9268aab88b0953fa02dad` |

## 结果与证明边界

| 验证 | 本次结果 | 边界 |
| --- | --- | --- |
| Debug基线→Debug候选 | 实际Vault读回身份材料摘要一致、表单字段一致、收藏字段／密码／revision一致，应用UID不变 | 仅合成数据与开发证书 |
| 三份密文及原密钥 | 文件SHA-256保持一致；各自原Keystore alias的AES-GCM挑战可解密；没有重新生成密钥来掩盖失败 | 挑战证明原密钥仍有效，不声称可抵抗设备／Keystore丢失 |
| Debug基线→minified测试签名候选 | 三份密文、原密钥与UID验证通过 | 压缩版使用独立的原始存储／密钥探针；不反射已被R8优化的Vault接口 |
| Minified实际UI | 原表单的合成端点／昵称可读，收藏可打开编辑，默认频道42和显式密码保存状态保留 | 不发起连接，未验证线上权限 |
| 新调音与旧数据组合 | 在minified实际Activity/Binder/Service里保存输入音量147%，force-stop／重开恢复147%；之后三份旧密文／原密钥／UID仍验证通过 | 其余参数重建由A2独立测试覆盖 |
| 无自动录音／连接 | 未输入连接请求；AppOps为RECORD_AUDIO ignore且无使用记录 | 不等同于真实语音设备验收 |
| 测试隔离 | Debug／unsigned Release的DEX均不含upgrade probe标识或密钥挑战字符串 | 未为通过探针增加keep规则 |
| 工具安全边界 | 已有目标包时runner拒绝再运行，原fixture仍可读；2项CLI单测证明物理／非法serial在SDK／APK访问前被拒绝 | 不支持在用户现有安装上运行此脚本 |
| 工具检查 | Python语法、全部9项工具单测、独立Java编译／dex／签名与实际instrumentation通过 | 不计为新增应用行为单测 |

第一批A1/A2/A3的组合UI已通过A3完整154项应用套件；JVM为263项。对应[聊天记录](IMPROVEMENT_A1_Q1_2026-11-07_ZH.md)、[调音记录](IMPROVEMENT_A2_2026-11-07_ZH.md)、[收藏与加入记录](IMPROVEMENT_A3_2026-11-07_ZH.md)分别保留各自产物与新执行情况。本记录不把多轮数字相加。

minified身份／INI与Opus1.6.1 ART探针在A3最终APK上通过；本阶段没有通过测试引用改变R8可达性。minified升级不声称完成了Vault身份接口的反射读回，身份完整序列化摘要读回来自Debug路径；其Release保证由旧密文／原密钥保全与独立解析探针共同提供。

## 复现

选择新建且已启动的独立模拟器，以及使用标准开发测试证书的APK副本：

```sh
python3 tools/check-upgrade-compat.py --device emulator-5560 --sdk "$ANDROID_HOME" --baseline-apk /path/to/baseline-debug.apk --candidate-apk /path/to/candidate-debug.apk
python3 tools/check-upgrade-compat.py --device emulator-5564 --sdk "$ANDROID_HOME" --baseline-apk /path/to/baseline-debug.apk --candidate-apk /path/to/minified-test-signed.apk
python3 -m unittest discover -s tools/tests -p 'test_*.py'
```

运行器检查包名、versionCode不降低、证书一致、真实emulator属性、boot完成与目标包尚不存在；失败保留现场，不卸载重试。首次工具接线修正了`pm path`缺包时返回1以及testOnly APK需要`-t`的环境语义，之后在新环境执行正式结果。一台新模拟器曾未注册ADB，停止本任务持有的该进程、用新端口重启同一尚未安装应用的AVD后通过；没有把环境失败计作业务通过。

## 本机证据

- `build/upgrade-compat/ff08d47c3c044d4d84a273a637847c71/`：最终Debug结果与运行日志。
- `build/upgrade-compat/f3bb31a5f1fe4e5897f4ef43302fe95d/`：最终minified结果与运行日志。
- `build/improvement-2026-11-07/r1-baseline-build.log`、`r1-verified-{debug,release}-upgrade.log`、`r1-existing-install-rejection.log`、`r1-minified-ui-result.txt`与合成截图。
- 三个独立probe／runner输入的SHA-256另存本机清单；源码随本阶段提交保留，密文、身份材料、APK、测试密钥和设备数据均不提交。

## 交付分类与剩余项

- 生产代码：无新增修改；沿用A3候选。
- 测试：新增独立upgradeSmokeTest Java／manifest和2项工具安全回归。
- 配置／工具：新增显式fresh-emulator升级runner；CI工具测试发现模式扩为`test_*.py`，不在每次PR额外构建历史APK。
- 文档：贡献指南、进度与本记录。

按本轮约定，第一批离线与模拟器组合验收完成。公开Release生产证书、递增versionCode、公开APK覆盖升级、实体设备以及未知历史版本并未在本记录中通过；没有合并或发布。下一批继续A4/A5，新增证据逐项绑定源码。
