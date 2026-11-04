# 收藏界面整理（2026-11-02）

## 修改前评估

原收藏弹窗将名称、地址、昵称和四个文本操作平铺，连接与删除没有清晰的主次；
新增入口混在滚动内容里，编辑器一次展示所有可选设置。

本次仅调整 Android Compose 展示与操作入口。BookmarkController 仍串行处理读写，
ServerBookmark/BookmarkCodec/Vault 继续负责校验、版本和密码策略；不改协议、
存储格式、连接回调、历史、身份或依赖。新建仍可预填当前表单，不复制密码。

拆分计划：BookmarkDialogs 只协调列表、编辑与删除确认；BookmarkListDialog 负责
列表状态和固定新增入口；BookmarkCard 负责单项层级与菜单；BookmarkEditor 负责
基础字段、展开的可选设置及保存。各文件控制在约 300 行以内，不扩展 MainScreen
或接近边界的 UiFixture。

风险集中在菜单目标错位、误触连接/删除、加载或保存时重复操作，以及大字体/键盘
遮挡。用独立 Compose 用例验证目标路由、删除确认、保存不自动连接、失败保留、
连接禁用、列表滚动和编辑器动作可达；既有纯 JVM 测试继续覆盖密码与存储约束。

## 验证结果

完成列表、单项卡片和编辑器拆分。收藏名称使用主要字号；地址独立显示，昵称作为次要
信息。连接保留为卡片唯一的强调按钮；填入表单、编辑与删除位于单项菜单，删除仍需确认。
新增固定在滚动内容之外，空列表、读取中、失败和数量上限都有独立状态。
表单只默认展示基础字段；更多设置可折叠，折叠不会丢失值，已有可选设置自动展开。
大字体时端口与昵称改为纵向布局，标题、取消与保存保留在可滚动内容之外。

执行命令（JDK 17，使用现有本机 Android SDK）：

```sh
./gradlew :app:ktlintFormat
./gradlew -I build/ui-polish-2026-11-02/ui-debug.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew quality :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest --rerun
adb -s <emulator> shell am instrument -w -r -e package io.github.ts3mobile.app.ui io.github.ts3mobile.app.uidebug.test/androidx.test.runner.AndroidJUnitRunner
```

| 检查 | 结果 |
| --- | --- |
| 全模块 Kotlin 格式检查、差异空白检查 | 通过 |
| App JVM 测试，最终源码重新执行 | 239 项通过，无跳过 |
| API 35 模拟器完整 UI 套件，最终 APK | 114 项通过 |
| 小米 14 / Android 16，400 dp 宽 | 22 项检查通过；英文计数调整后相关 3 项再次通过 |
| Android Lint | 0 错误、36 项既有警告，无新增警告 |
| Kotlin 编译、Debug、混淆 Release 构建 | 通过 |
| 仓库隐私扫描与文档本地链接检查 | 通过 |

新增 BookmarkListTest 的 12 项回归，覆盖菜单隐藏与选中项路由、连接/填表区分、
删除确认、加载/忙碌、失败恢复、连接中禁用切换、50 项滚动与上限，以及中英文、
浅色/深色和大字体。BookmarkEditorTest 的 6 项回归覆盖基础字段和密码默认策略、
折叠保留可选值、保存失败保留草稿并重试、非法端口/取消，以及普通与 1.8 倍中文
键盘布局。既有 SelectedFeaturesTest、SessionLayoutTest 与共享离线 fixture 同步更新。

第一轮 25 项定向检查中，一处测试同时匹配到背景与弹窗的端口字段；限定弹窗范围后，
最终完整套件通过。Lint 发现两处英文计数文案候选复数警告，调整为计数标签后消除；
最终 APK 重新完成完整 UI 和单元检查，相关真机用例也重新执行。

已检查真机列表、单项菜单、基础编辑器、浅色/深色与 1.8 倍中文截图。截图只使用
合成收藏；本次没有连接真实服务器，也不据此声明网络或后台语音行为已完成实测。
使用现有独立的 **TS3 Mobile UI** 调试包覆盖安装，未卸载或清除数据。MIUI 调试包
后台启动权限在仪器测试后恢复为原来的 ignore，手机最终停在真实应用的收藏弹窗。

证据存放于忽略的 `build/bookmark-ui-2026-11-02/`：最终 APK、日志、单元 XML、
Lint 报告、真机截图与对照图。最终验证前后源代码/配置 SHA-256 清单一致。

## 修改文件分类

- 生产代码：BookmarkDialogs、BookmarkListDialog、BookmarkCard、BookmarkEditor；
  AppIcon、原生新增图标及中英文资源。控制器、Vault、协议和连接回调不变。
- 测试：新增独立 BookmarkListTest、BookmarkEditorTest；更新 SelectedFeaturesTest、
  SessionLayoutTest 和 UiFixture。测试与生产代码保持物理分离。
- 文档：本记录、README.md、README_ZH.md 与 ARCHITECTURE.md。
- 配置：无受版本控制的依赖、构建、权限、版本号、签名或存储配置变更。
