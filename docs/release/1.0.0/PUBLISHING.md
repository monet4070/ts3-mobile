# 1.0.0 打包、签名与 GitHub 上线

本手册供维护者操作，不会由文档任务自动执行。先完成 [CHECKLIST.md](CHECKLIST.md)，
再公开首个稳定版。示例以 macOS/Linux、JDK 17、Android SDK 35、PowerShell 7、
Python 3 和 GitHub CLI 为前提；Windows 可使用对应 `.bat`/`.exe` 与 PowerShell 命令。
所有示例从仓库根目录执行，`ANDROID_HOME` 与 `JAVA_HOME` 已正确配置。

## 1. 冻结完整源码

当前工作区包含未提交的功能与 UI 修改，不能直接给旧 HEAD 打 tag。审查、运行检查、
通过仓库既有 PR 流程纳入全部目标代码；不要把发布文档单独打成应用版本。
任何行为/依赖/版本变动都会使相关旧候选证据失效。

确认 `app/build.gradle.kts` 中正式应用 ID 不变，versionName 为 `1.0.0`。
候选 versionCode 已设置为 `13`；已知公开旧包最高为 12，仍需核对本地私发包。
确认长期签名和升级策略；新证书并不会自动兼容旧安装。

正式发布前同步 README、CHANGELOG 与 SECURITY 的状态和真实发布日期。
发布操作期间先保留为草稿，只有最后公开才能声称已上线。

## 2. 在候选源码执行门禁

```sh
pwsh -NoProfile -File tools/check-build-environment.ps1
./gradlew --no-daemon --max-workers=2 --no-build-cache --rerun-tasks quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
python3 -m unittest discover -s tools/tests -p 'test_audio_build_config.py'
pwsh -NoProfile -File tools/check-repository-privacy.ps1
git diff --check
```

按 [CONTRIBUTING.md](../../../CONTRIBUTING.md) 执行离线 UI、服务、通知、原生音频、
Opus 兼容和真机检查。目标设备上有活动会话时先正常断开；不可为解决签名冲突卸载用户应用。
自动化记录需保存命令、退出结果、测试 XML、源码/配置哈希，不能只统计旧缓存报告。
最终检查范围由发布清单决定；UI 调试包的结果要标明，不能当正式包升级证据。

## 3. 对齐与签名

本轮维护者已确认密钥在另一台电脑，可按[异机交接说明](SIGNING_HANDOFF.md)签名已准备的
未签名候选包；交接摘要与最终签名摘要分别保存。

候选输出默认为 `app/build/outputs/apk/release/app-release-unsigned.apk`。
使用经过确认的发布密钥；不要临时生成新密钥来绕过升级冲突，也不要将密码写入命令、
仓库、日志或环境示例。`apksigner` 会交互询问密码。

以下 `TS3_KEYSTORE_PATH` 和 `TS3_KEY_ALIAS` 由维护者在本机设置，指向已确认的密钥。
代码块会因未设置而停止；不要把实际本机路径提交到文档。

```sh
: "${TS3_KEYSTORE_PATH:?Set the approved local keystore path}"
: "${TS3_KEY_ALIAS:?Set the approved key alias}"
export TS3_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
export TS3_RELEASE_DIR="$PWD/build/releases/1.0.0"
mkdir -p "$TS3_RELEASE_DIR"
"$TS3_BUILD_TOOLS/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$TS3_RELEASE_DIR/aligned-unsigned.apk"
"$TS3_BUILD_TOOLS/apksigner" sign --ks "$TS3_KEYSTORE_PATH" --ks-key-alias "$TS3_KEY_ALIAS" --out "$TS3_RELEASE_DIR/ts3-mobile-1.0.0-release.apk" "$TS3_RELEASE_DIR/aligned-unsigned.apk"
"$TS3_BUILD_TOOLS/apksigner" verify --verbose --print-certs "$TS3_RELEASE_DIR/ts3-mobile-1.0.0-release.apk"
"$TS3_BUILD_TOOLS/zipalign" -c -P 16 4 "$TS3_RELEASE_DIR/ts3-mobile-1.0.0-release.apk"
"$TS3_BUILD_TOOLS/aapt" dump badging "$TS3_RELEASE_DIR/ts3-mobile-1.0.0-release.apk"
```

比对旧公开 APK 的 `apksigner verify --print-certs` 输出，并实际升级测试；仅比对 APK 文件哈希
不能证明签名兼容。检查正式包名、`1.0.0`/versionCode、API、ABI、权限与非 debuggable。
保留 R8 mapping、测试日志、源码哈希及签名记录在私有构建档案，不将私钥或未经审核的诊断上传。
对齐完成后再签名，签名完成后不要修改 APK。

## 4. 验证确切签名产物

在已授权设备或模拟器上执行（用实际序列号替换示例）：

```sh
pwsh -NoProfile -File tools/check-release-identity.ps1 -DeviceSerial emulator-5554 -SdkRoot "$ANDROID_HOME" -ApkPath "$TS3_RELEASE_DIR/ts3-mobile-1.0.0-release.apk"
python3 tools/check-release-codec.py --device emulator-5554 --sdk "$ANDROID_HOME" --apk "$TS3_RELEASE_DIR/ts3-mobile-1.0.0-release.apk"
```

探针无需安装或连接服务器，但不代替最终包真实连接、聊天/戳一下、音频、后台和升级检查。
真机验收结束后再次比对设备 APK 与最终产物；任何重编译、重签或内容修改都需要重新绑定证据。

## 5. 生成构建信息与摘要

以下示例读取 APK 真实 manifest、证书、SHA-256 和干净的源码 HEAD。
保持 Gradle/工具链与上述门禁一致；若升级工具版本，同时调整本手册、门禁与字段。
执行前必须先提交并冻结所有目标代码，不能只把脏工作区的 HEAD 写成源码证据。

```sh
python3 - <<'PY'
import hashlib, json, os, re, subprocess
from pathlib import Path

def output(*args):
    return subprocess.check_output(args, text=True).strip()

if output('git', 'status', '--porcelain'):
    raise SystemExit('Freeze all intended source and documentation changes first.')
folder = Path(os.environ['TS3_RELEASE_DIR'])
tools = Path(os.environ['TS3_BUILD_TOOLS'])
apk = folder / 'ts3-mobile-1.0.0-release.apk'
badging = output(str(tools / 'aapt'), 'dump', 'badging', str(apk))
match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
if not match:
    raise SystemExit('Could not parse APK package/version.')
package, code, version = match.groups()
if package != 'io.github.ts3mobile.app' or version != '1.0.0' or int(code) < 13:
    raise SystemExit('Unexpected release identity or version.')
if re.search(r'^application-debuggable', badging, re.M):
    raise SystemExit('Debuggable APK is not a release artifact.')
certificates = output(str(tools / 'apksigner'), 'verify', '--print-certs', str(apk))
digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', certificates)
if not digests:
    raise SystemExit('No verified signing-certificate digest.')
native = re.search(r'^native-code:(.*)$', badging, re.M)
abis = re.findall(r"'([^']+)'", native.group(1)) if native else []
if set(abis) != {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}:
    raise SystemExit('Unexpected universal APK ABIs.')
minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
if not minimum or not target or minimum.group(1) != '26' or target.group(1) != '35':
    raise SystemExit('Unexpected Android SDK levels.')
info = {
    'schemaVersion': 1,
    'applicationId': package,
    'versionName': version,
    'versionCode': int(code),
    'sourceCommit': output('git', 'rev-parse', 'HEAD'),
    'sourceDirty': False,
    'minSdk': int(minimum.group(1)),
    'targetSdk': int(target.group(1)),
    'abis': abis,
    'apkFile': apk.name,
    'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'signingCertificateSha256': [x.lower() for x in digests],
}
# java's version banner is emitted on stderr; keep a compact version value.
java_binary = Path(os.environ['JAVA_HOME']) / 'bin' / 'java'
java = subprocess.run([str(java_binary), '-version'], capture_output=True, text=True, check=True)
info['toolchain'] = {
    'javaVersionBanner': (java.stderr or java.stdout).splitlines()[0],
    'gradle': '8.9', 'androidGradlePlugin': '8.7.0', 'kotlin': '2.0.0',
    'compileSdk': 35, 'buildTools': '35.0.0', 'ndk': '27.0.12077973', 'cmake': '3.22.1'
}
(folder / 'BUILDINFO.json').write_text(json.dumps(info, indent=2) + '\n')
files = [apk, folder / 'BUILDINFO.json']
(folder / 'SHA256SUMS.txt').write_text(''.join(
    f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in files))
print('Generated BUILDINFO.json and SHA256SUMS.txt from the signed APK.')
PY
```

校验表列出最终 APK 与构建信息，不为校验表自身生成循环摘要。不要将设备标识、本机路径、
密钥别名、密码或真实服务器信息写入构建信息。实际运行库版本仍需第 4 步探针验证。

## 6. GitHub 草稿与公开

到这里应已有经过审查、已推送的源码提交，tag 必须指向它。不要移动已公开 tag。
按项目规则在需要时使用签名 tag，并核对 `git rev-parse HEAD` 后再执行：

```sh
git tag -a v1.0.0 -m "TS3 Mobile 1.0.0"
git push origin v1.0.0
```

将两份 Release 正文合并成私有构建目录的 `release-notes.md`，移除准备稿提示，
并填写实测后的签名/升级结论和仍适用的限制。文案中的 tag 链接在 tag 推送后才存在。

```sh
gh release create v1.0.0 --repo monet4070/ts3-mobile --verify-tag --draft --title "TS3 Mobile 1.0.0 · 首个稳定版" --notes-file "$TS3_RELEASE_DIR/release-notes.md" "$TS3_RELEASE_DIR/ts3-mobile-1.0.0-release.apk" "$TS3_RELEASE_DIR/SHA256SUMS.txt" "$TS3_RELEASE_DIR/BUILDINFO.json"
gh release view v1.0.0 --repo monet4070/ts3-mobile --json isDraft,isPrerelease,tagName,url,assets
```

从草稿重新下载到一个新目录校验资产；确认 Release 正文、证书、摘要与源码提交一致。
只有发布清单关闭且文案可公开时，执行最终公开：

```sh
gh release edit v1.0.0 --repo monet4070/ts3-mobile --draft=false --prerelease=false --latest
gh api repos/monet4070/ts3-mobile/releases/latest --jq '{tag_name, draft, prerelease, html_url}'
```

公开后用下载资产做一次安装冒烟，验证 Latest 与双语文档、问题反馈入口。
若有阻断回归，公开说明影响并调整 Latest/预发布标识或撤下问题资产；
发布更高 versionCode 的修复包，不静默覆盖同版本二进制，也不引导无备份用户卸载降级。
