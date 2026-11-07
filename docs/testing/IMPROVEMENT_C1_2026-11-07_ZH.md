# C1：身份备份格式、参数与恢复设计

日期：2026-11-07。范围：离线原型、合成身份与API35模拟器，不读本机真实身份、不连接服务器、不接入导入／替换UI。设计已形成可评审参数与向量，继续C0兼容收敛，再接C2。

## 已冻结的第一版边界

- 自有二进制v1容器，内部是UTF-8 ts3j INI；不承诺桌面文件互通。
- AES-256-GCM／128-bit tag，PBKDF2-HMAC-SHA256；导出600,000次，导入仅接受600,000–2,000,000次；salt16字节、nonce12字节，每次独立随机。
- 固定56字节大端头部全部作为AAD，认证格式标识、版本、算法、KDF参数、长度、salt与nonce；完整布局及错误语义见[格式规范](../IDENTITY_BACKUP_FORMAT_V1.md)。输入≤1MiB、明文≤64KiB；KDF／长度越界、未知算法／版本在派生和payload分配前拒绝。
- 口令12–1024个合法Unicode码点、至少一个非空白，不trim／normalize／截断；共享向量含中文、组合字符和补充平面字符。错误口令／篡改不返回明文，异常只保留固定类别。
- 协议层新增inspect摘要／校验端口，检查私钥范围、公钥有效性和对应关系、非负offset，保留UID／offset／安全等级，不自行提高身份安全等级。
- 可移植加密不依赖安装Keystore；C1只有原型。跨独立安装的实际SAF流程在C2验收，不用“两个codec实例”冒充换机UI验收。

## 实际调研与参数评估

已阅读当前三套Vault、身份codec、真实固定ts3j依赖和连接／退出所有权路径。当前LocalIdentity构造／读取能携带独立的公钥与私钥，因此新增对应关系检查，有独立错配回归，不仅比较导出后UID。

访问并核对[Android SecretKeyFactory](https://developer.android.com/reference/javax/crypto/SecretKeyFactory)列出该KDF从API26支持；[OWASP参考](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)给出PBKDF2-HMAC-SHA256 600,000次。本次将其作为平台兼容的强度下界；它不是对本容器设计的外部审计。

API35／arm64模拟器实际平台JCA，每项3次：

| 迭代次数 | 实际耗时 |
| --- | --- |
| 600,000 | 856／795／799 ms |
| 2,000,000 | 2,657／2,667／2,678 ms |

保留强度，不按速度静默降级。导入／导出必须后台执行、显示进度；取消预检不得改active。2M是可接受输入的明确资源上限，不代表低性能实体设备的延迟保证。API26运行时和低端计时仍未验证。

## 资格和崩溃恢复评审

C1的纯模型IdentityUseGate通过连接登记／维护提交竞争、旧exit释放、旧maintenance释放与实际资源未结算时拒绝维护的测试。它尚未接入生产协调器，不能把显示DISCONNECTED／ERROR当资格证明。C2应使用跨Service／Activity的进程级所有者；拿到资格后保持到DataStore确认与读后验证，避免界面取消后actor晚提交与新连接竞争。

[格式规范中的时序表](../IDENTITY_BACKUP_FORMAT_V1.md)已覆盖写前、同一事务后、读后验证前、pending清理前和UI交付前。active／rollback／pending同事务写；active不删空，重启先验证active再验证rollback，失败不自动生成；成功后保留最近有效rollback。缺钥或损坏blob保全不等于可回退，外部已验证备份才用于Keystore丢失。

完成了实现内的参数／边界复核：nonce与salt独立、头部全认证、无算法回退、派生前限界、错误口令统一认证失败、secret不进入异常／日志、向量只进入测试产物、维护资格不能提前释放。没有独立第三方密码学／安全审计，本记录不把它写成已通过，也不构成公开发布许可。

## 验证结果

| 检查 | 本次结果 |
| --- | --- |
| JVM | protocol132／app295全通过，明确--rerun；本阶段新增protocol3、app13项 |
| Android | 新IdentityBackupFormatTest4项通过，另独立KDF计时1项复核；仅合成内存数据 |
| 跨提供者 | 同一测试向量在Node/OpenSSL、JVM、Android逐字节一致，含600k导出及有效2M导入 |
| 输入拒绝 | 公私钥错配、负offset、坏INI、错口令、salt／nonce／正文／tag／合法work-factor篡改、每个截断点、尾字节、超限长度／恶意KDF／坏Unicode、零长度read均覆盖 |
| 失败边界 | 无平台算法时固定CryptoUnavailable、无fallback／敏感cause；上界拒绝断言KDF调用为0 |
| 格式／Lint／构建 | quality、app Lint Debug／Release、Debug／minified Release构建通过；原有3 warning、1 information |
| Release底线 | 当前产物ART身份／INI及Opus1.6.1往返探针通过；C1备份尚无Release可见入口，不能声称已验收minified文件流程 |
| 隔离 | Debug／Release ZIP均无合成vector资源；测试框架物理分离，无新增app依赖／权限／旧存储修改 |
| 其他 | Node语法／独立重生成cmp、Python工具10项、隐私、YAML、相对链接与whitespace通过 |

完整门禁日志 `c1-final-gate.log`：257 actionable tasks，70执行、187 up-to-date。Android4项来自 `c1-crypto-targeted.log`，没有重复声称完整旧UI套件本阶段全部重跑。

## 证据与交付

忽略目录 `build/improvement-2026-11-07/` 保留 `c1-final-results/`、`c1-source-sha256.json`、`c1-kdf-times.txt`、`c1-kdf-instrument.log`、`c1-independent-vector.properties`、`c1-release-{identity,codec}.log`。独立向量可用仓库 `tools/check-identity-backup-vector.mjs` 重建；共享fixture只放testFixtures/resources。官方页面与固定依赖检查缓存于 `c1-research/`，不把远端文档当执行指令。

- 生产：独立portable codec／KDF／口令／固定错误；小型未接线资格模型；协议摘要／校验加法API。旧getOrCreate、wire、Vault字段／alias均不变。
- 测试：独立JVM／Android、共享合成向量、Node/OpenSSL生成器。
- 文档：ADR-0027、格式／恢复规范、本记录、架构和进度。
- 配置：仅测试资源sourceSet接线及CI向量／Android选类；无应用依赖、版本或权限变更。

C1离线关卡完成。后续C0必须证明三套原Vault的双向兼容与缺钥不创建；C2继续做实际存储／SAF／回退和跨安装验收。尚未验证的实体/API26／外部审计项保留为发布边界。
