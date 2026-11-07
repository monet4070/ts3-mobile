# C2.1：身份存储事务与维护互斥

日期：2026-11-07。离线与模拟器范围。行为合同及错误迁移见[ADR-0029](../decisions/0029-identity-maintenance-transactions.md)。本阶段没有提供面向用户的SAF导入导出入口。

## 实现与收益

Application显式持有同一仓储和资格门。连接、替换连接遗留工作、握手、自动重连、探测／watch、频道操作、终态清理和退出任务逐一登记，取消后必须等实际结算才释放；Service销毁的异步音频释放额外持有资格。维护取得资格后阻止新连接，直到持久确认与读后验证完成。页面ERROR／DISCONNECTED不再被当成维护资格。

新active、已验证旧rollback和pending摘要在同一DataStore事务中提交。读后验证或重启验证通过才清pending；pending active失效可恢复有效rollback，两者失效保留证据且不生成。显式导入可恢复可读Preferences中的损坏密文／缺钥状态，原密文保留为证据；该副本不会显示为可用rollback。真正不可读的protobuf不删除／重置；外部备份可在新安装恢复。已有零字节文件视作恢复状态；仅从未持久化身份的新安装允许自动生成。

继续使用旧DataStore、encrypted_identity、alias、v1密文格式，仅读旧数据不增加元数据或改写字节。IdentityVault.getOrCreate签名保留，错误改为无敏感cause的固定类别；旧加密异常类型的内部调用方需按ADR迁移。未更改协议／音频算法、版本、依赖或权限。

## 实际验收

| 检查 | 结果与边界 |
| --- | --- |
| 完整门禁 | quality、双Lint、Debug／minified构建、完整Android；最终完整XML196项：195通过、1外部通知策略fixture按设计跳过、0失败 |
| 最终补验 | 325 app JVM；12 Android全部通过。最终差异仅增加维护入口前ensureActive及独立回归；适用quality／双Lint／双构建再次通过 |
| JVM新增 | 总计26项（C0为299）；包括取消前／提交后、晚确认、并发首次创建、陈旧revision、原子失败、提交后／读后崩溃、pending回退、无有效副本／缺钥、无rollback、空文件、读后损坏不误报成功 |
| 真实存储 | scratch UUID文件与ts3_test_identity_ alias；重开DataStore、替换／回退、pending恢复、缺钥保留／显式导入、坏protobuf及零字节文件保全 |
| Android服务 | 真startForegroundService在维护中拒绝：不读身份、不连socket、不采集，12秒超过前台启动超时窗口后进程仍活、无残留连接通知 |
| 已修复现场 | 初始只stopSelfResult在bound Service上仍触发ForegroundServiceDidNotStartInTimeException；回归先红后绿。现在短暂提升真实维护通知并立即移除／停止startId |
| Lint | Debug／Release均仅原有3 Warning、1 Information |
| Release底线 | 最终候选实际打包身份／INI往返、Opus版本／编解码探针通过；不作为SAF或混淆仓储用户流程证据 |
| 其他 | 隐私扫描、Python工具10项、Ruby YAML解析、本地链接／git diff检查通过 |

完整门禁之后未无依据扩大测试。补验的取消入口检查对最终325项JVM和12项真实Android／构建执行；完整套件证据单独保留，不把补验误写成最终源码全量运行。

## 证据与覆盖升级

- `build/improvement-2026-11-07/c21-full-gate.log`、`c21-full-results/`、`c21-full-source-sha256.json`：完整196项XML和之前源码指纹。
- 同目录`c21-final-focused.log`、`c21-final-results/`、`c21-source-sha256.json`：最终补验与483文件指纹。
- `c21-release-{identity,codec}.log`：最终产物底线探针。
- 升级候选结果和SHA见本记录后续追加。

曾尝试重开C0保留AVD，其三个偏好文件读到零字节且原fixture验证失败；保留现场，不归因为候选成功、不清空后继续。这也促成已有空文件禁止自动生成的保护与独立回归。之后另建新AVD，从已归档R2 Debug重新seed合成fixture，最终候选独立覆盖验证。

## 变更分类与边界

- 生产：Application容器、Repository／Store／资格lease与模型；Vault兼容入口；协调器／重连／服务／通知最小接线，固定双语拒绝提示。
- 测试：独立Store／Repository／gate／协调器JVM；真实事务与前台拒绝Android；旧Vault失败断言按固定类别迁移，原密文／文件／密钥保全断言保留。
- 文档：ADR、本记录、架构、双语README／隐私、进度。
- 配置：manifest Application注册，CI选类纳入真实新测试；没有生产签名／版本或依赖变更。

后续C2.2／C2.3／R3继续SAF回读认证、导入预检／确认、回退入口、跨安装与最终minified实际文件流程；未做物理／API26运行时或独立安全审计。没有合并、公开发布或连接真实服务器。

最终独立升级：`build/upgrade-compat/e6ab8a5b444c4899b9346ba279e0117b/`，R2 Debug→C2.1 Debug→测试签名minified。
Debug实际原Vault读回，随后minified核对原文件／原钥挑战／UID／四调音字节，全通过。
API35／arm64／4KiB，新AVD `ts3-c21-final-api35`，端口5578，已停止并保留。
最终Debug SHA `f1e6063a91d6ec291caa3562f3a33600860eb54ce6ab30b5f30de70b87615cb2`；
unsigned Release `623b1a9553cad0e9c55ae5ee94451d614199c01ab29ba60b74766ba2ca62dd94`；
测试签名Release `a7536a8adc793e6174083f33df039b7b08fe7e420367064f0a15a840e18a7270`。
测试签名只用标准Android debug key，unsigned所有原ZIP条目内容与签名副本逐一核对一致，仍code14。
