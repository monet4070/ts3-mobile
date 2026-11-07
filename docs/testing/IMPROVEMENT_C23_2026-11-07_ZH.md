# C2.3：确认后导入、替换与本地回退

日期：2026-11-08。离线与模拟器范围；[ADR-0031](../decisions/0031-confirmed-identity-restore.md)。最终身份候选组合／崩溃矩阵由R3继续收口。

## 交互与存储合同

独立身份页提供导出／导入恢复两栏。OpenDocument按实际读取字节限界，版本／算法／KDF／明文上界在派生前检查；后台认证解密后严格UTF-8解码并校验公私钥对应。选择、失败预检或取消不会生成身份、替换active或连接服务器。候选私钥字节只在私有资源对象里保留，不进入observable／saved state／日志／剪贴板／文件；口令在预检结束清理。

页面比较旧／新UID、offset、安全等级和权限影响；用户确认后调用C2.1仓储事务。连接占用或陈旧revision保留已验证候选，但必须刷新比较内容并再次确认，不自动重试提交。本地回退预览最近有效旧身份，使用同样的资格与事务。取消已在进行的IO会显示正在结束，等worker真正结算后才恢复新请求。确认写入后UI销毁不会提前释放维护资格，重新进入读取权威存储结果。

原不可读密文可保留为证据，不能广告为可用回退。已有空Preferences可显式导入；缺失alias仅为已验证新数据创建新钥。现存不可用alias和物理损坏protobuf不自动删除／重置，应保留原安装，在另一全新安装中用有效外部备份恢复。没有自动连接、桌面格式、云同步或口令恢复服务。

## 自动验收

- 新增JVM15项：精确材料／UID／offset／安全等级、严格UTF-8／有效加密内非身份、错口令／tag／未知版本／恶意KDF／超大流／关闭失败；预检不写、重复确认、BUSY／stale候选保留及显式再确认、晚回调／取消、读取消结算、提交前取消、提交后销毁资格保持、无rollback、selector失败与公有状态脱敏。
- 完整阶段门禁：354 app／133 protocol JVM通过；完整Android207项：206通过、1外部通知策略fixture默认跳过、0失败。quality、双Lint、Debug与minified构建通过。
- 取消结算提示是该全量后的唯一生产差异，增加独立JVM回归；最终JVM／定向Android与构建结果另记，不混称最终源码全量。
- 独立Android源／目标scratch DataStore与Keystore alias：目标密钥不能解源本地envelope；源通过真实resolver导出／回读验证，目标错误口令后原文件字节保持；预检比较期间目标字节保持，确认后精确原文／摘要一致，回退恢复目标原身份且保留源身份为最近rollback，源文件不变。
- Compose核对双身份／权限提示，连接忙不能确认；失败比较必须刷新再确认。真实系统OpenDocument取消不访问任何存储或密钥。

## 测试工具边界

独立升级probe新增显式`verify-after-identity-maintenance`阶段：维护后身份文件按设计改变，允许该一项字节变化，但仍验证三套原密钥挑战、应用UID、表单／收藏及调音原字节。默认verify／verify-raw不放宽。它只读，不修改fixture，也不代替实际身份材料核对。新增Java probe不参与生产／Gradle APK，另经工具javac/D8编译和真实安装执行。

## 变更分类

- 生产：独立ImportedIdentity资源、RestoreController／State、恢复Compose区；现有Activity／ViewModel接入两个独立picker，双栏互斥；双语文案。复用C2.1存储事务，不改格式／alias／权限／依赖／版本。
- 测试：独立reader／controller JVM，独立密钥实际Android、比较对话框与真实OpenDocument取消；独立Java升级probe新增明确维护阶段。
- 配置：CI新增三恢复Android选类；没有生产签名／R8测试keep变更。
- 文档：ADR、本记录、架构、双语指南／隐私、进度。

R3继续最终组合、崩溃注入、minified文件恢复及安装／兼容资料收口。未验证实体/OEM、API26运行时或独立安全审计；不将模拟器结果描述成这些已通过。

最终取消结算补验：355 app JVM、9 Android全通过，quality／双Lint／双构建再次通过。
全量阶段的133 protocol保持通过；双Lint仍为原有3 Warning、1 Information。
最终507文件指纹和补验XML独立归档；没有无依据重复全套。

## 默认安装的实际跨安装／混淆文件验收

源为C2.2在独立AVD `ts3-c22-final-api35`实际导出的文件；目标为全新AVD
`ts3-c23-final-api35`（API35／arm64／4KiB，端口5586）。目标从R2 Debug seed另一套原身份／密钥／数据，
再覆盖C2.3 Debug，升级证据`build/upgrade-compat/98f608779420430081d41d661bfa2610/`。
两安装没有复制本地envelope或Keystore；目标只接收可移植加密文档。

Debug实际主菜单→导入栏→OpenDocument选文件→比较目标旧／源新UID、offset、安全等级。
预检时目标原身份文件SHA仍与原fixture一致；明确确认后force-stop，重新进入并实际导出恢复后的身份，
独立Node/OpenSSL认证／解密后明文SHA与源安装原身份完全一致。
随后实际本地回退→确认→force-stop→重新导出，独立摘要精确匹配目标原身份。

同一目标接着覆盖测试签名minified，重复完整导入／确认／force-stop／导出和回退／确认／force-stop／导出。
两次独立验证分别匹配源和目标精确私钥材料，未添加R8测试keep。
每次确认后的三套原密钥挑战、应用UID、表单／收藏／调音原字节均通过独立probe；
身份文件本身因显式维护发生预期变化。默认verify-raw仍会拒绝该变化，新增允许阶段仅显式维护后调用。
未点击连接或采集，麦克风AppOps保持拒绝；FLAG_SECURE保留，以合成UI树和文件结果验收。

| 最终产物 | SHA-256 |
| --- | --- |
| Debug | `a9fdeedb5f5db73f239fedfa0d53bbad1e8f7f7f69209e38d7076903abaa6d03` |
| Unsigned minified | `ceccce75cffe9ca5ec4a76a9ed56d745b818ffad849c5fd88a9b78db852f1c0e` |
| 测试签名minified | `cb287597e6113167cd5cf228adf22500720b03c5d04876c5b87b67cc498a35b2` |

仅标准Android debug证书，unsigned原ZIP条目逐一核对签名副本一致；仍code14。
目标AVD已停止并保留。打包身份／INI及Opus1.6.1编解码底线探针通过。

`build/improvement-2026-11-07/`证据：`c23-full-gate.log`／`c23-full-results/`；
`c23-final-focused.log`／`c23-final-results/`；`c23-{full-,}source-sha256.json`；
`c23-final-artifacts.json`；`c23-{debug,release}-ui-result.txt`／各次`-keys-and-unrelated.log`；
四份`c23-{debug,release}-{restored,rolled-back}.ts3mbak`及UI树、独立验证工具。
本地链接、YAML、隐私、Python工具10项和git whitespace检查通过。

C2.3离线／模拟器实现验收完成。R3继续进程级崩溃矩阵、下一次连接使用新身份及候选资料收口。
