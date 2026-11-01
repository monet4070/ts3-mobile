# 开发分支同步与离线检查

日期：2026-11-01（Asia/Shanghai）。基线为 `main` / `c83aa45`，同步分支为
`codex/chat-bookmarks-audio-upgrades`。本轮将原有工作区功能、测试、文档和配置
整理到独立分支，不修改发布版本号；真机发布验收继续单独维护。

## 同步范围

- 生产代码：频道聊天、私聊、Poke、加密服务器收藏、暂离/静音状态同步、频道详情与搜索；
  麦克风增益、降噪/AEC 设置、音量门控，以及 A0–A4b 音频升级和生命周期修复。
- 测试：对应的独立 JVM、Compose、Keystore、原生音频、协议 fixture 和 Release 探针。
- 文档：中英文 README、架构、隐私、许可证、更新记录、ADR、计划和各阶段验收记录。
- 配置/工具：CI、依赖锁、Opus 源码构建、可选 APM 工具、备份排除规则。

架构保持 Compose/Activity → 前台服务控制器 → JVM 协议端口，音频实现位于
`audio-opus`。本轮重新检查前，425 个源码、测试和配置输入与 10 月 24 日最终验收
指纹一致；本轮额外修改仅为下面的测试滚动步骤、Git 属性和本记录。

## 本轮收尾修改

首次全量 App 仪器测试在 320 × 640、160 dpi 的模拟器上运行 79 项，78 项通过。
`largeTextParticipantVolumeFitsWithoutClipping` 在 1.8 倍字体下直接查找尚在可见区域
之外的控件，未执行列表滚动。只滚动查找滑块仍然失败：部分可见的展开按钮也需要
先滚动到可见区域后再点击。本轮为按钮补充 `performScrollTo`，为滑块补充
`performScrollToNode`，保留原来的 200% 音量、可见性和文字裁切断言。单项确认
通过后，再重跑完整套件；两次失败日志均保留。

Git 暂存检查还发现 `legacy-build.patch` 的空白上下文行被当作尾随空格。
统一 diff 的这些行必须保留一个空格，因此 `.gitattributes` 仅对这份补丁关闭
`blank-at-eol` 检查，其他文件的空白检查保持有效。

## 重新执行的检查

| 检查 | 结果 |
| --- | --- |
| quality / Kotlin 编译 / 三模块 JVM | 526 项通过：协议 128、音频 166、App 232；禁用缓存并强制执行 |
| Debug / 混淆 Release / Lint | 通过，主门禁 238 个任务实际执行 |
| Lint 问题 | 音频 0；App Debug 36、Release 25 项既有 warning，0 error |
| 默认音频 Android 离线套件 | ARM64 / API 35 模拟器 31 项通过 |
| App Android 完整套件 | 修正测试滚动后，同一 320dp 窄屏模拟器 79 项通过，0 跳过 |
| 测试修改后的格式检查 | App ktlintCheck 通过 |
| Python 工具测试 | 7 项通过 |
| Opus 新旧版本兼容 | 48 组组合及合成 fixture 校验通过 |
| 实际 Release 身份 / INI 探针 | 通过 |
| 实际 Release codec 探针 | ARM64，运行时 libopus 1.6.1，编解码往返通过 |
| 默认 APK 打包 | 四 ABI，不含实验 APM 库，zipalign 16 KiB 检查通过 |
| 仓库隐私 / 本地文档链接 / 暂存空白 | 通过 |

主门禁使用项目本地 JDK 17 和 Android SDK：

```sh
./gradlew --no-daemon --max-workers=2 --no-build-cache --rerun-tasks quality :ts3-protocol:test :audio-opus:testDebugUnitTest :app:testDebugUnitTest :audio-opus:lintDebug :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

Android、原生兼容、Release 探针及隐私命令按 CONTRIBUTING 执行。
日志和原始失败结果保存在忽略目录 `build/github-sync-2026-11-01/`，不随分支上传。
首次使用系统 Java 11 的构建在配置阶段失败；切换项目本地 JDK 17 后才产生上述通过结果。

## 剩余边界

本轮没有连接真实服务器、录制真实麦克风或验证实体音频路由；未重跑可选 APM 实验。
私聊/Poke/状态互通、默认密码频道、真实蓝牙/有线、AEC 听感、长时间运行和功耗
仍待后续设备验收。默认 TS3 接收未确认缺失帧时长时仍不启用 FEC；APM 不进入默认包。
既有 Lint/R8 警告和历史测试证据的适用边界继续保留，分支同步不代表完成发布验收。
