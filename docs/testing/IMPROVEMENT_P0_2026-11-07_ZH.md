# P0：提升计划工具链与离线基线

日期：2026-11-07（Asia/Shanghai）。源码基线：`7a1e36e`；工作分支：`codex/experience-improvements`。

此前默认shell的Java11阻断已解除：本次找到并启用项目忽略目录中已有的JDK17、SDK与PowerShell，使用任务级环境变量，不改系统默认Java，不提交本机路径。执行计划和原始计划已有的未提交文档保留。

## 环境与方法

- Gradle 8.9；Corretto JDK17.0.21；compile/target SDK35；NDK27.0.12077973；CMake3.22.1。
- 创建本任务专用的API35 / Android15 / arm64-v8a模拟器；未连接物理设备。界面1080×2340、density420，关闭系统动画；使用合成fixture，未连接TeamSpeak服务器。
- 生成417份既有源码／测试／配置文件的SHA-256清单，检查期间文件不变。A1的新回归文件在基线app测试APK完成后加入，不属于本次129项基线。
- JVM门禁禁用缓存并强制重新执行，186个任务全部执行。构建／仪器命令190个任务中22个执行、168个复用，不能把复用构建算成新测试。

## 新执行结果

| 检查 | 结果 |
| --- | --- |
| Kotlin格式 `quality` | 通过 |
| 协议JVM | 129通过，0失败／错误／跳过 |
| 音频JVM | 194通过，0失败／错误／跳过 |
| 应用JVM | 239通过，0失败／错误／跳过 |
| 音频Debug Lint、应用Debug/Release Lint | 通过；警告详见本次XML，不把原有警告视为新增 |
| Debug / minified Release构建 | 通过；Release为unsigned，不是正式发布产物 |
| 全应用Android套件 | 129通过，0失败／错误／跳过 |
| 离线音频Android选定套件 | 32通过，0失败／错误／跳过 |
| minified APK身份／INI ART探针 | `RELEASE_IDENTITY_SMOKE_OK` |
| minified APK codec ART探针 | Opus1.6.1；`RELEASE_OPUS_ROUNDTRIP_OK` |
| Opus主机跨版本兼容 | 48种scenario/version组合通过 |
| Python音频构建工具测试 | 7通过 |
| 原PowerShell隐私扫描 | 通过，本次已实际执行 |

## 命令

先通过环境变量选择上述工具版本，再执行：

```sh
./gradlew --offline --no-daemon --max-workers=2 --no-build-cache --rerun-tasks quality :ts3-protocol:test :audio-opus:testDebugUnitTest :app:testDebugUnitTest :audio-opus:lintDebug :app:lintDebug :app:lintRelease
./gradlew --offline --no-daemon --max-workers=2 :app:assembleDebug :app:assembleRelease :app:connectedDebugAndroidTest
pwsh -NoProfile -File tools/check-release-identity.ps1 -DeviceSerial emulator-5556 -SdkRoot "$ANDROID_HOME"
python3 tools/check-release-codec.py --device emulator-5556 --sdk "$ANDROID_HOME"
python3 tools/check-opus-compat.py --sdk "$ANDROID_HOME"
python3 -m unittest discover -s tools/tests -p 'test_audio_build_config.py'
pwsh -NoProfile -File tools/check-repository-privacy.ps1
```

音频仪器测试选择CONTRIBUTING列出的10个离线播放、路由、JNI、RNNoise、管线与生命周期类，排除opt-in真实录音／APM实验。serial是本次新建模拟器的值，其他环境从`adb devices`重新选择。

## 证据与边界

本机忽略目录`build/improvement-2026-11-07/`保存`p0-gates.log`、`p0-build-ui.log`、`p0-audio-android.log`、Release与codec日志、`p0-junit/`、`p0-android/`和源码指纹。两个ART探针的独立输出另在各自默认忽略目录保存。

本次未运行GitHub云端CI，未做物理蓝牙／有线音频、OEM锁屏通知、API26或正式签名覆盖升级。以上不是P0离线门禁的通过结论，分别留给对应功能／候选阶段。既有源码基线已可重复执行，可以开始A1；不再沿用“JDK阻断”作为后续未验证的理由。

生产代码／测试代码／配置：P0本身无修改。文档：新增本记录和执行进度。A1之后的变更须建立自己的验证记录。
