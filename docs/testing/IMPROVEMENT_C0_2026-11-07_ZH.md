# C0：三套本地加密的兼容收敛

日期：2026-11-07。离线／模拟器范围，不访问真实身份或服务器。实现约束及三套冻结名称见[ADR-0028](../decisions/0028-compatible-keystore-envelopes.md)。可移植C1容器保持独立。

## 行为与兼容合同

仅提取KeystorePayloadCipher与AndroidKeystoreKeys。三套DataStore、字段、alias、AES-GCM的v1:iv:ciphertext、Base64标准padding／无换行、无AAD及各自内部codec／上界／事务保持。

已有密文读取只findExistingKey；缺钥明确MissingLocalCipherKey，不创建同名新钥、不生成身份、不覆写原blob。现存alias类型错误／不可用也不替换。显式新数据写入才允许getOrCreateKeyForNewData。Bookmark revision及saveValidated的事务内guard保留。

保留IdentityVault(Context)、ConnectionSettingsVault(Context)、BookmarkVault(Context, store=default)的调用入口；内部store/cipher构造用于测试隔离。真实旧APK→候选升级探针使用原Context／Kotlin默认参数桥接构造，验证了默认存储和原alias，而不只靠注入helper测试。

## 实际验收

| 检查 | 结果及边界 |
| --- | --- |
| JVM | app299项全通过，明确--rerun；新增4项，三codec均做旧写新读／新写旧读 |
| 完整Android | XML189项，188通过、0失败、1个外部通知策略fixture默认跳过 |
| 新增Android | LegacyVaultCipherTest3项循环覆盖三实际Vault／DataStore及真实Keystore；AndroidKeystoreKeysTest2项覆盖缺钥不创建和RSA alias不被AES替换 |
| 存储保全 | 缺钥时三份scratch文件SHA／原密文不变，alias仍不存在；错钥／tag损坏／未知版本失败后原密文保持 |
| 真实双向 | 老算法写入的身份INI、表单binary、收藏binary由各新Vault读取；各新Vault写出后用冻结旧算法解密和对应旧codec读回，频道42／密码／revision保持 |
| 既有事务 | BookmarkVaultTest4项、完整收藏UI及延迟guard／revision回归通过 |
| 格式／静态／构建 | quality、Lint Debug／Release、Debug／minified Release通过；仍为原有3 warning、1 information |
| 默认旧安装覆盖 | 第二批R2旧Debug→C0 Debug实际Vault读回身份摘要／表单／收藏及四调音值；原密文、原密钥挑战、UID保持 |
| 混淆更新 | 同一原fixture继续install-r覆盖C0测试签名minified，原三密文／原密钥／UID／调音文件仍验证通过；实际form与147%设置页两次启动／force-stop恢复、麦克风关闭 |
| Release探针 | 本次unsigned产物ART身份／INI往返和Opus1.6.1版本／编解码通过；不冒充minified身份Vault反射读回 |
| 其他 | 隐私、Python工具10项、YAML／本地链接／whitespace检查通过 |

Android测试只操作cache下UUID文件与`ts3_test_`前缀alias；清理函数断言前缀，不删除生产alias。原BookmarkVaultTest仍保留自己的scratch文件。没有通过删除应用或数据来使升级通过。

## 升级候选和证据

旧基线为第二批Debug `bc58b32d7825e3ccc9ef19b382999d4e54745c53869d6b5472cf6c5779508c42`。

| C0产物 | SHA-256 |
| --- | --- |
| Debug | `c5958ace04349401d578886df1b63a03bef8bb006f84dace808504933b78433b` |
| Unsigned minified Release | `7875b106c6c098c801714a1dcb50d7a39f0e53d316380a4f86897ab70ef5a537` |
| 测试签名minified副本 | `230055500a55097b735b92b668596b14388e7f54335b6b99ce84ab2b9a2bf38d` |

仅标准Android开发测试证书；签名与unsigned每个原ZIP条目字节完全一致，版本仍code14。Debug→minified这一步沿用相同原fixture，Debug校验已经证明没有迁移／重写原文件与密钥，minified读取仍是原旧数据；不描述成两次独立fresh基线。

- `build/improvement-2026-11-07/c0-source-sha256.json`：492个生产／测试／构建输入，最终核对一致。
- 同目录 `c0-full-gate.log`、`c0-final-results/`：完整门禁原XML；255 actionable tasks，47执行／208 up-to-date。
- `c0-targeted.log`：初始4 JVM及9 Android（5新增、4原Vault）通过。
- `build/upgrade-compat/b25cd9d12ba948fc805672f1b7281b0c/`与`c0-debug-upgrade.log`：原Context构造、默认Vault／原alias实际覆盖升级。
- `c0-release-upgrade-install.log`、`c0-release-{ui-result.txt,post-ui-vaults.log,tuning.png}`：继续覆盖minified，实际设置与force-stop后再验；合成截图已目视检查。
- `c0-release-{identity,codec}.log`：当次Release探针。任务新AVD ts3-c0-upgrade-api35／emulator-5572（API35、Android15、arm64、4KiB）已停止，数据保留。

完整适用命令：

```sh
source build/improvement-2026-11-07/env.sh
./gradlew --offline --max-workers=2 :app:ktlintFormat
./gradlew --offline --max-workers=2 quality :app:testDebugUnitTest --rerun :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest
```

## 变更分类与剩余边界

- 生产：3个小crypto文件、三Vault接入；没有协调器／UI／音频变更。
- 测试：独立JVM envelope／三codec、Android三Vault及真实密钥测试；测试helper物理隔离。
- 配置：CI增加新crypto Android选类；依赖、权限、数据格式、版本不变。
- 文档：ADR、本记录、架构／隐私／进度。

C0离线兼容关卡完成，C2开始实际身份仓储／维护资格／SAF／回退。身份首次创建并发与替换事务没有在此阶段偷偷更改；由C2明确处理。公开生产签名、API26运行时、物理/OEM及完整minified身份导入／替换入口仍待对应后续验收。
