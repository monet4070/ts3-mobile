# C2.2：加密身份导出与SAF回读验证

执行跨至2026-11-08（Asia/Shanghai）。离线与模拟器范围；合同见[ADR-0030](../decisions/0030-verified-document-backups.md)。本阶段只接入导出；C2.3继续导入和回退。

## 行为与边界

“更多选项 → 身份与备份”打开独立、不可外部启动的Activity。页面显示UID、offset、安全等级及本地旧副本提示，不展示私钥，不会创建身份或连接服务器。需先结束连接任务，然后设置／确认12–1,024字符口令。加密在IO执行；系统CreateDocument选择新`.ts3mbak`文件，写入关闭后重新打开、有界读取、认证并核对精确原身份摘要后才显示“备份已验证”。无法回读、关闭失败、损坏或被替换成另一个有效加密文件都不能显示成功。Provider云同步不在close／本次验证的保证中。

口令输入只remember，不保存到实例状态。控制器只有状态／摘要／不透明请求id对外可见；敏感数组在私有操作对象中持有并尽力清零。不存在明文临时文件、saved state、剪贴板、通知／诊断或持久URI授权。FLAG_SECURE保护身份页截图与最近任务截图；不宣称JVM／输入法内存绝对擦除。失败文件不自动删除，用户可选择新文件重试。

选择器每次仅一项未决请求；取消后仍等旧选择器返回才允许新请求。晚到文件结果及IO完成不能覆盖新操作。旋转只保留ViewModel私有内存，进程丢失后忽略无主回调。取消正在写文件的操作只使结果失效，资源由实际worker结束后清理，不在阻塞provider使用中擦除数组。

## 自动验收

- JVM新增15项：写／关闭／回读／认证／实际字节限界、内容错配、资源销毁、相同输入数组；错误口令／确认、忙碌拒绝、取消／晚选择／重复回调、孤立进程结果、scope预取消清理、公有状态脱敏。
- Android新增7项：实际ContentResolver与独立Java测试Provider正常及拒绝回读，Compose明文输入清空与资格禁用，真实Activity旋转／FLAG_SECURE／身份不变，MainActivity菜单及返回，真实系统CreateDocument取消。
- Provider仅在独立instrumentation APK：最初Kotlin版本在独立进程因没有目标APK runtime而失败，改为无目标依赖的Java测试夹具；生产不添加依赖或测试分支。
- 不把合成read port的系统取消测试描述成默认身份实际导出；实际默认身份Debug／minified流程另记。

## 变更分类

- 生产：独立Activity／ViewModel、导出控制器／状态、独立Compose页面、备份Document端口／Android适配／资源对象；MainActivity／MainScreen仅菜单回调；双语文案。
- 测试：独立JVM控制器／文件验证和协议UID格式回归，Android页面／生命周期／菜单／系统取消及test-only Provider。
- 配置：不可外部启动的Activity和test-only Provider manifest；CI选类。不增加权限、依赖、版本或生产签名。
- 文档：ADR、本记录、架构、双语README／隐私、进度。

保留边界：云提供方长期持久性、物理/OEM／API26运行时、独立外部安全审计尚未验证；跨安装导入／替换／回退与最终候选崩溃矩阵由C2.3/R3完成。

默认身份实际页面发现C1摘要把库Uid对象toString当成UID，界面出现类名／对象hash。
改用既有连接路径使用的toBase64，并新增20字节／28字符及offset变化稳定性回归。
此前C1摘要往返相等不能证明UID呈现正确；本次修正与真实页面证据替代该部分结论。
身份原文、真实UID字节、协议连接及冻结容器格式没有改变。

UID修正后完整门禁：133 protocol／339 app JVM通过；完整Android202项中201通过、1外部策略fixture跳过。
最终两处UI／错误文案差异另补验，完整源码／XML见`c22-full-{source-sha256.json,results/}`，不混称最终源码全量。

最终补验：133 protocol／340 app JVM、7项导出Android全部通过；quality、Debug／Release Lint、双构建再次通过。
Debug／Release Lint仍为3 Warning、1 Information；隐私、Python工具10项、YAML和本地链接／whitespace检查通过。
最终差异仅修正损坏身份的空页面文案及系统选择器不可用的显式失败，完整套件证据与最终补验分开保留。

## 最终候选与实际文件验证

独立新AVD `ts3-c22-final-api35`，API35／arm64／4KiB，端口5582，已停止并保留。
升级证据`build/upgrade-compat/15afbde0d0ec4311b43279d8838050c1/`：R2 Debug→C2.2 Debug→测试签名minified，
原Vault实际Debug读回；minified原文件／原密钥挑战／UID／四调音字节均保持。

Debug和minified均从实际主菜单进入默认身份页面，UID显示为28字符Base64；设置合成口令，
通过真实CreateDocument在Downloads各创建新文件，页面实际写入、关闭、回读、认证并显示已验证。
独立Node/OpenSSL从实际导出文件验证header／KDF／AAD／tag并解密，明文SHA-256与升级前fixture原身份完全一致。
随后force-stop并再次核对原Vault文件／原钥／UID／调音保持；未连接服务器／录音，未添加R8测试keep。
身份页FLAG_SECURE保留，因此以合成UI树与独立文件结果验收，没有关闭安全标志来截图。

| 最终产物 | SHA-256 |
| --- | --- |
| Debug | `87e4d0f22d90ad285bc4a66e44e635a24cd855037267123ff6e34d88549e1ca0` |
| Unsigned minified | `d15a22ec199ea293db24ea2d36ffed3d0b7aa330cc1f345bb292a9b2aaa7e5ba` |
| 测试签名minified | `16cb0c0b5d5305bb1455db04ef8b4465f7b0a2894a66b99ccb80fd989154221b` |

标准Android debug测试证书；签名副本逐一核对unsigned原ZIP条目内容一致。版本仍code14。
当次打包身份／INI／Opus探针也通过。APK扫描确认test-only Provider及向量没有进入生产产物。

证据位于`build/improvement-2026-11-07/`：
`c22-full-gate.log`／`c22-full-results/`，`c22-final-focused.log`／`c22-final-results/`，
`c22-{full-,}source-sha256.json`（最终498文件），`c22-final-artifacts.json`，
`c22-{debug,release}-ui-result.txt`／`-post-ui-vaults.log`／`-verified-ui.json`，
`c22-{debug,release}-ui.ts3mbak`及独立验证脚本；原文件均为合成测试数据。

C2.2离线／模拟器验收完成；下一阶段C2.3导入／替换／回退，不将本阶段加密导出误称为已完成恢复功能。
