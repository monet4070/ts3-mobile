# D1：持久外观主题

日期：2026-11-08。状态：离线／模拟器验收完成。

修改前已读Application、Main／Identity Activity、主题token／系统栏、菜单、偏好存储／控制器、
相关单测／Android测试、Manifest、备份排除和模块依赖。界面已有深浅token，但默认固定浅色。
按[ADR-0033](../decisions/0033-persistent-appearance.md)，新增独立appearance文件／controller／小屏幕，
大文件仅接入口和主题包装，不迁移连接／采集逻辑。风险是默认行为改变、异步读写覆盖、未知格式被覆盖、
不同Activity颜色不一致和外观操作误触服务生命周期；分别由独立回归验证。

验收口径：未设置浅色；仅SYSTEM跟随系统；三模式持久／重建、错误可见；主界面／身份／外观页共用，
深浅系统栏、菜单／弹窗／IME、中英文大字可用；主题选择本身不重建Activity／服务、不启动采集。
实际完成证据见下文；物理／OEM与未测API不作为通过项。

## 实现与阶段结果

Application独立持有appearance controller／DataStore（schema1：version、theme），
加载／保存期间禁用选择，保存失败保留当前视觉并显示重试；reset授权在一次失败后仍保留。
未知schema／模式／类型和损坏文件不自动覆盖；仅明确reset可替换可读的逻辑格式。
`ApplicationTheme`仅包装Main／Identity／Appearance，raw theme的显式预览API保持。
系统栏颜色／图标对比随主题更新；主题没有任何service/audio依赖。

初次367 app JVM、三个新增Android、Debug／minified构建与Lint通过；真实Debug及测试签名minified
菜单三模式、force-stop重启、固定深浅不随系统变与SYSTEM随系统变已执行。
这些早期产物保存在`build/improvement-2026-11-07/d1-pre-ime/`，不能代替最终修复后产物。
截图已经检查中英文、1.8倍字、错误／重试／关闭；系统栏及Main／Identity／Appearance共用由真实Activity测试验证。

## 最终IME检查发现的现有缺陷

等到真实键盘完全展开后，共用InputDialog操作区仍可能伸到键盘下面；原来的“有显示”检查
没有验证键盘最终可见边界。新增独立`InputDialogImeTest`：开启软件键盘、等待insets稳定，
测量按钮在屏幕上的底边与Dialog实际visible frame。旧实现可重复失败：frame.bottom=1019，
save／cancel.bottom=1102；旧失败XML／局部日志保存在`d1-ime-before-results/`。

按[ADR-0033](../decisions/0033-persistent-appearance.md)新增小型DialogViewport适配器，
把实际窗口遮挡与safeDrawing做union，不改全局窗口flag、表单回调／校验或公共API。
修复后save／cancel.bottom=914、frame.bottom=1019，聚焦字段仍可见；8项弹窗／书签／真实主题回归通过。
新增三项JVM边界测试避免对已经resize的窗口重复加键盘高度。
独立主题测试另修复关闭页面后短暂没有Compose root的有界窗口等待，原颜色／服务断言不放宽。

## 最终门禁与精确候选

| 门禁 | 结果 |
| --- | --- |
| app JVM | 370项全部通过；新增14项独立controller／store／viewport边界 |
| 质量／类型／静态 | quality、Debug／Release Lint与构建通过；原app 3 Warning／1 Information，无新增依赖／权限 |
| 完整app Android | XML212项：210通过／2外部夹具默认跳过／0失败；含新增四项主题／真实IME测试 |
| 定向补验 | 同一真实键盘边界回归旧实现失败，修复后连同六项书签与真实主题共8项通过，原断言保留 |
| 最终minified真实UI | fresh安装在系统深色时未设置仍选Light；三模式选择、force-stop重启后的选择核对、固定Dark不随系统变、SYSTEM随系统变、真实键盘与书签弹窗完成 |
| 布局／生命周期 | 中英文、1.8倍字、错误／重试／关闭截图检查；三个Activity系统栏一致，选择不重建Appearance Activity，同一bound Service无新连接／采集 |
| 其他 | 隐私扫描、本地链接、XML／YAML／shell及whitespace检查通过；源码指纹验收前后保持 |

`build/improvement-2026-11-07/d1-final-gate.log`、`d1-app-android.log`、`d1-final-results/`保存原门禁／XML。
`d1-source-sha256.json`固定570个输入；`d1-final-minified-ui.log`与`d1-minified-final-*.png`
记录最终测试签名minified的真实用户流程，最终图中Save／Cancel完整位于键盘上方。
系统键盘自身配色由Android／输入法管理，未承诺强制跟随应用颜色；没有录音或连接服务器。

最终APK SHA256：Debug `ebdb62c3efe4ce40870bda98032b210d8be83f39f0c43d660bd2db5240ac2dec`；
unsigned minified `7a8ce219b637db9193b4acb802cab9e69f759c15f0b193b2bfeba802181f50d4`；
标准Android debug证书签名副本 `c3ea9da6a5404de772125f8a55793b7c240b55002438706d9fd3994fe65202fa`。
生产签名私钥未使用，未合并、发布或改版本。云端结果单独记录；下一阶段D2。

变更分类：生产为独立外观模型／store／controller／screen／Activity、共享主题包装与小型窗口边界适配器；
测试为三份JVM及三份Android测试，窗口恢复采用有界等待，未放宽原颜色／所有权／边界断言；
文档为本记录、ADR、架构、双语README／指南／隐私和贡献说明；
配置为private Activity、双语文案、独立偏好备份排除与CI无录音测试选择。依赖和已有数据格式保持。
