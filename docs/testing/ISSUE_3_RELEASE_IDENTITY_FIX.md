# Issue #3：M10 Release 身份读取失败修复报告

日期：2026-10-17。问题来源：[GitHub Issue #3](https://github.com/monet4070/ts3-mobile/issues/3)。
本次修复基于 `bf3abd9`，工作分支为 `codex/fix-issue3-release-identity`。

## 结论与影响范围

根因是 Release 的 R8 规则只保留 `org.ini4j.Ini`，遗漏 ini4j 内部通过反射创建的
解析器、构建器和格式化器。R8 删除这些类的无参构造器，并将类优化成抽象类。
连接前读取 TeamSpeak 身份时，`Class.newInstance()` 因此抛出 `InstantiationException`。

修复将保留范围扩展到 `org.ini4j.**` 及其成员。对当前同一份代码做修复前后对照：
修复前 Release 可成功构建，但离线身份读取失败；修改规则后，开启 R8 的 Release
通过身份重复读取、密钥一致性和 INI 读写检查。APK 增加 16 KiB。

生产业务代码、公共 API、依赖版本、身份存储格式和 Android 权限均未改变，
无需迁移身份或清除应用数据。修复范围是打包配置、独立回归检查、CI 和文档。

## 用户报告与定位证据

Issue 报告 Android 14 连接失败，同一服务器可被 Windows 客户端和 M8 访问。
错误为：

```text
java.lang.InstantiationException:
java.lang.Class<i3.g> cannot be instantiated
```

Issue 的版本字段与步骤描述不完全一致；本次核对的实际发布包为
`v0.11.0-m10`，发布提交 `9af47e8`。本地 APK 的 SHA-256 与 GitHub 发布资产一致。

证据链如下：

1. 发布包对应的 R8 映射将 `org.ini4j.spi.IniParser` 映射为 `i3.g`。
2. 对发布 APK 的 DEX 检查显示该类为 `PUBLIC ABSTRACT`；裁剪记录列出了被删除的无参构造器。
3. `IniBuilder` 和 `IniFormatter` 同样受到裁剪，说明只修复解析器会留下相同类型的隐患。
4. 在本机 Android 15 / API 35 模拟器上加载发布 APK，生成临时身份并执行导出、读取，
   得到与 Issue 完全一致的异常。生成及导出成功，失败发生在读取阶段。
5. 未混淆 Debug APK 的同类离线身份往返检查通过。
6. 本次新增的正式回归检查也在当前代码的未修复 Release 上失败，在修复后 Release 上通过。

混淆名称随构建变化，当前代码的构建不必仍然使用 `i3.g`。回归检查验证行为及退出状态，
没有把某次构建的混淆名称写死到测试中。

## 为什么会失败

实际调用顺序是：

```text
Ts3jSessionClient.connect
  -> Ts3IdentityCodec.decode
  -> LocalIdentity.read
  -> new Ini / Ini.load
  -> IniParser.newInstance
  -> ServiceFinder.findService
  -> Class.newInstance
  -> InstantiationException
```

`Ts3jSessionClient` 在解析服务器地址、发起 UDP 连接之前就会解码身份。因此该异常可在
完全离线的情况下触发，服务器配置和密码没有参与这次失败。

R8 根据静态可达性优化代码。ini4j 的对象创建经过通用 `ServiceFinder` 和动态类查找，
仅保留上层入口 `Ini` 无法保证反射目标及其构造器被保留。最终 APK 内的类虽然仍有名字，
却已不能实例化。原有 `-dontwarn org.ini4j.**` 只控制警告，不能保留这些构造器。

旧门禁主要运行 JVM/Debug 测试；它们没有执行 R8 后的 APK。ADR-0004 曾以 Release
构建成功作为保留规则验证依据，本次证明编译、打包成功不能验证运行时反射。
该 ADR 已补充纠正说明。

## 修复设计与实施过程

在 `app/proguard-rules.pro` 中，用以下规则替换只保留 `org.ini4j.Ini` 的规则：

```proguard
-keep class org.ini4j.** { *; }
-dontwarn org.ini4j.**
```

保留类和全部成员覆盖了反射所需的构造器及内部实现，整个应用仍开启 R8。
选择覆盖这个小型、已锁定版本的依赖，可以避免逐个列举反射类造成再次遗漏。
同代码构建的实际体积增量为 16,384 字节，约为原 APK 的 0.11%。

实施顺序：

1. 保持旧规则，构建当前代码的 Release，保存 APK、映射和构建日志。
2. 新增独立测试程序与运行脚本，确认其对未修复 APK 返回非零退出码，堆栈含身份读取路径和 `InstantiationException`。
3. 只修改 ini4j 保留规则，重新构建 Release。
4. 在同一模拟器用同一测试程序验证修复后的 APK，并检查三个 SPI 类的构造器已列入保留结果。
5. 执行完整质量门禁，补充 CI 检查及开发、发布文档。

## 回归检查如何防止漏检

测试程序位于 `app/src/releaseSmokeTest/java/io/github/ts3mobile/app/release/IdentityRoundTripProbe.java`。
运行入口为 `tools/check-release-identity.ps1`。

- Java 探针独立编译，通过 ART 的 `app_process` 加载已经构建好的 APK。
  测试代码不进入生产编译或 R8 输入，避免其引用反过来保留缺失的类而造成假通过。
- 类路径只有探针和待测 APK，没有额外的未混淆依赖 JAR。
- 生成一个仅存在于内存的临时身份，两次导出、读取并比较密钥字节，覆盖首次读取和再次读取。
- 使用固定、无敏感信息的 INI fixture 验证解析、构建、写出和重新读取，覆盖三个反射辅助类。
- 不安装应用、不访问用户身份、不连接服务器；不输出身份或异常消息，只输出异常类型和堆栈位置。
- 错误返回非零退出码；脚本同时检查成功标记，并清理本次创建的设备临时文件。

本机运行方法（JDK 17、PowerShell 7、Android SDK Platform 35 / Build Tools 35.0.0）：

```powershell
.\gradlew.bat :app:assembleRelease
.\tools\check-release-identity.ps1 -DeviceSerial emulator-5554 -SdkRoot $env:ANDROID_HOME
```

序列号使用 `adb devices` 的实际结果；SDK 参数可换成自己的路径。
默认检查 `app/build/outputs/apk/release/app-release-unsigned.apk`，也可用 `-ApkPath` 指定 APK。
日志写入 `build/release-identity-smoke/<run-id>/result.log`。

CI 增加 Release 构建与 API 35 模拟器上的同一检查，启动等待和整个步骤均有时间上限。
CI 流程的 YAML 和 Bash 语法已本地检查；GitHub 托管 runner 上的执行仍需提交后验证。

## 本次验证结果

环境：Windows、JDK 17、Gradle 8.9、R8 8.7.14、Android 15 / API 35 x86_64 模拟器。

| 检查 | 结果 |
| --- | --- |
| 当前代码未修复 Release | 构建成功；身份回归失败，退出码 1 |
| 当前代码修复后 Release | 身份重复读取及 INI 读写通过，退出码 0 |
| SPI 构造器保留 | `IniParser`、`IniBuilder`、`IniFormatter` 均确认保留 |
| 协议 JVM 测试 | 89 项通过 |
| 音频单元测试 | 20 项通过 |
| 应用单元测试 | 161 项通过 |
| Kotlin 格式检查 / 编译 | 通过 |
| Android Lint | 应用 Debug/Release、音频 Debug 均无错误；应用分别有 36 / 3 个警告 |
| Debug / Release 构建 | 均通过 |
| 隐私扫描 / diff 空白检查 | 通过 |
| R8 警告 | 修复前后列表相同；仍有上游构造器规则及 DNS service descriptor 警告 |

修复后探针输出：

```text
IDENTITY_ROUNDTRIP_OK
INI_STORE_ROUNDTRIP_OK
RELEASE_IDENTITY_SMOKE_OK
```

同代码体积对照：修复前 14,685,819 字节，修复后 14,702,203 字节。
本地对照 APK、映射和日志保存在被 Git 忽略的 `build/issue3/`。
修复后的默认产物是未签名 Release APK；本次未发布新版本。

## 变更分类与尚未覆盖范围

- 生产业务代码：无修改。连接状态机、身份生成/存储和音频模块保持原行为。
- 测试：新增独立的 APK 身份/INI 回归程序及 PowerShell 运行脚本。
- 配置：补全 ini4j 保留规则；CI 增加 Release 构建和模拟器检查。
- 文档：同步中英文 README、ARCHITECTURE、CONTRIBUTING、CHANGELOG、ADR-0004、M11 发布清单和本报告。

已验证的是 APK 内的真实身份解析路径。尚未执行报告机型上的 Android 14 真机验收、
完整 UI 连接流程、真实服务器认证和音频通话；该离线测试也不覆盖 Android Keystore。
后续发布应使用新版本号与正常签名流程，并对确切候选 APK 重跑检查和连接验收。


## 2026-10-18 真实服务器补充验收

同一工作树的开启 R8、非 debuggable Release 在 Android 16 真机上使用原有身份，
两次连接原服务器成功，均正常退出并收到服务器确认；身份加密存储保持一致。
包使用与既有安装兼容的本机签名，尚未完成独立正式发布密钥审核。完整范围、
无声测试结果及限制见[短时 Release 验收报告](RELEASE_ACCEPTANCE_2026-10-18.md)。
