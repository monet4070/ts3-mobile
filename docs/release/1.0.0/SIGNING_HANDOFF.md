# 1.0.0 异机签名交接

候选包为 `io.github.ts3mobile.app` / `1.0.0` / versionCode `13`。
本文保留未签名阶段的异机交接步骤。随后维护者已提供原密钥，最终包已签名并完成
证书比对及模拟器升级；当前状态见[发布清单](CHECKLIST.md)。以下**未签名候选包**不是公开分发 APK。
完整准备与测试记录见 [PREPARATION_2026-11-04.md](PREPARATION_2026-11-04.md)。

## 1. 转移并校验

把本机 `build/releases/1.0.0/ts3-mobile-1.0.0-signing-handoff.zip` 复制到签名电脑，
解压到一个新目录。保留原用户应用及数据。

macOS/Linux 在解压目录校验：

```sh
shasum -a 256 -c SHA256SUMS-UNSIGNED.txt
```

Windows PowerShell 7：

```powershell
Get-Content SHA256SUMS-UNSIGNED.txt | ForEach-Object {
    $digest, $file = $_ -split '  ', 2
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant() -ne $digest) {
        throw "Checksum mismatch: $file"
    }
}
```

`UNSIGNED_BUILDINFO.json` 标明实际冻结源码提交、未签名 APK 摘要、版本和工具链。
如果要重编译，必须重新执行候选检查、探针及摘要绑定；直接签名这份 APK 无需重编译。
`mapping.txt` 对应本次候选，保存在私有发布档案，不作为普通下载资产。

## 2. 使用旧公开版对应的密钥签名

使用 JDK 17、Android build-tools 35.0.0；设置本机 `JAVA_HOME`、`ANDROID_HOME`、
`TS3_KEYSTORE_PATH`、`TS3_KEY_ALIAS`。只在本机设置实际路径/别名，密码由 apksigner
交互询问，不写入命令、仓库或交接文件。不要使用 QA/debug 测试包的另一份密钥。

macOS/Linux：

```sh
: "${TS3_KEYSTORE_PATH:?Set the original release keystore path}"
: "${TS3_KEY_ALIAS:?Set the original release key alias}"
TS3_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
"$TS3_BUILD_TOOLS/apksigner" sign --ks "$TS3_KEYSTORE_PATH" --ks-key-alias "$TS3_KEY_ALIAS" --out ts3-mobile-1.0.0-release.apk ts3-mobile-1.0.0-unsigned.apk
"$TS3_BUILD_TOOLS/apksigner" verify --verbose --print-certs ts3-mobile-1.0.0-release.apk
"$TS3_BUILD_TOOLS/zipalign" -c -P 16 4 ts3-mobile-1.0.0-release.apk
```

Windows PowerShell 7：

```powershell
if (-not $env:TS3_KEYSTORE_PATH -or -not $env:TS3_KEY_ALIAS) { throw 'Set the original keystore path and alias.' }
$ts3BuildTools = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
& (Join-Path $ts3BuildTools 'apksigner.bat') sign --ks $env:TS3_KEYSTORE_PATH --ks-key-alias $env:TS3_KEY_ALIAS --out ts3-mobile-1.0.0-release.apk ts3-mobile-1.0.0-unsigned.apk
if ($LASTEXITCODE -ne 0) { throw 'Signing failed.' }
& (Join-Path $ts3BuildTools 'apksigner.bat') verify --verbose --print-certs ts3-mobile-1.0.0-release.apk
if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
& (Join-Path $ts3BuildTools 'zipalign.exe') -c -P 16 4 ts3-mobile-1.0.0-release.apk
if ($LASTEXITCODE -ne 0) { throw 'Alignment verification failed.' }
```

证书 SHA-256 必须与旧公开 `v0.11.1-m10` 相同：

```text
e239102412f17ddd1909bd323b3825831b153fec4602ec00abb359da7d8be592
```

若不匹配，停止该包的公开和升级验证，找回正确密钥；不通过卸载、清数据或使用 QA
签名绕过冲突。旧公开证书主题为 Android Debug；此处沿用其升级身份，不宣称新生产证书。

## 3. 绑定最终签名 APK

在源码仓库检出 `UNSIGNED_BUILDINFO.json` 的 `sourceCommit`，确认工作树干净且提交一致。
把 `TS3_RELEASE_DIR` 指向解压交接目录，按 [PUBLISHING.md](PUBLISHING.md) 第 4、5 步：

1. 对**这份签名 APK**执行身份/INI、Opus ART 探针。
2. 从实际签名 APK 生成 `BUILDINFO.json` 和 `SHA256SUMS.txt`，记录证书与签名后摘要。
3. 在旧公开包上覆盖安装，确认身份、收藏、表单保持；复核今天的新蓝牙断开静音逻辑。
4. 保留最终 mapping、摘要与证据；签名后不再修改 APK。

`SHA256SUMS-UNSIGNED.txt` 不能代替最终 `SHA256SUMS.txt`，
`UNSIGNED_BUILDINFO.json` 也不能改名冒充最终签名构建信息。

## 4. 合入与公开

候选源码的 PR 与 CI、签名与升级证据齐备后，按现有发布清单合入审核源码。
若合并产生新的 commit SHA，tag 与 BUILDINFO 的 `releaseCommit` 使用实际发布提交；
`sourceCommit` 保留实际构建提交。仅文档差异时核对全部已记录构建输入，
行为/依赖/配置变化时重新构建和绑定证据；不得把未重建的 APK 描述为来自新构建提交。

最终 GitHub 资产只有：签名 APK、`SHA256SUMS.txt`、`BUILDINFO.json` 与双语 Release 正文。
未签名包、交接 ZIP、私有 mapping 和测试日志留在维护者归档，不上传作用户安装包。
公开前的草稿、tag 和 Latest 操作按 [PUBLISHING.md](PUBLISHING.md) 执行。
