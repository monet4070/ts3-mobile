# 应用内检查更新

日期：2026-11-09。状态：本地实现与验证完成，尚未提交、合并或发布。

## 用户行为

“更多选项 → 检查更新”打开独立页面并检查一次。默认仅正式版；用户可打开
“包含测试版”，选择保存在本机，切换后重新检查。页面展示当前安装、渠道最新版本、
正式／测试版标识、发布日期与更新说明。关闭固定在顶部，渠道设置、新版本结果和说明
分组显示。说明按 Markdown 渲染，默认六行预览；完整说明使用独立滚动正文，顶部始终
保留“收起更新说明”。收起或系统返回回到摘要原位置；下载按钮位于说明之前。

更新以实际安装的 Android versionCode 比较，不按版本名字典序：同名高 code 可以提示更新，
相同或更低 code 不提供升级下载。包含测试版时，同时比较 GitHub 最新正式版与最近 100 个
公开发布中的最新非草稿候选，取两者较高的 code，避免测试版选择漏掉更高 code 的正式修复。

只有打开页面、切换渠道和重新检查会请求网络；不在应用启动、语音服务或后台定时检查。
下载由用户点击并交给浏览器，系统负责安装。未新增安装权限，不静默下载或安装，
不会为了更新自动断开服务器。安装前的正常断开由页面说明提醒用户。

## 修改前理解、范围与兼容性

已阅读 AGENTS、MainActivity/MainScreen、MainViewModel、菜单、外观偏好及 UI/单测模式，
检查既有 GitHub 发布资产和安装流程。已有公开 code 14 提供所需 BUILDINFO 元数据，
最初实现无需新增服务器或第三方依赖；后续 Markdown 修复新增固定版本的 Markwon，见下文。

较大的 MainActivity/MainScreen 只增加导航入口；状态、HTTPS、JSON、版本策略、独立偏好
分别位于 `app/updates` 的小文件，不扩展连接服务。超过 300 行的入口拆分评估与后续迁移
计划见 [ADR 0034](../decisions/0034-manual-update-checks.md)。此前 UI、蓝牙修复继续保留。

新偏好仅为独立 `ts3_updates` DataStore 的 `include_prereleases`，不修改既有身份、收藏、
连接表单、麦克风或外观格式。主界面新增回调有默认值。应用 ID、版本号、签名配置、权限、
协议和音频实现均未因本功能改变。

## 请求与失败边界

- 固定官方仓库，由既有 ProjectLinks 的仓库标识派生 API 和网页入口；不上传设备身份、
  TS3 服务器信息、聊天或录音，不使用 GitHub token。
- HTTPS 请求限制重定向、连接／读取等待与 1 MiB 响应大小，只接受官方仓库或 GitHub
  资产主机。已取消检查不会继续发起下一项元数据请求；同一页面的实际检查串行，
  切换渠道或关闭页面后的旧结果不能发布到新页面状态。
- BUILDINFO schema、应用 ID、整数 code、APK 资产、摘要和证书字段必须一致；
  GitHub 若提供 asset digest，也必须与 manifest 的 APK 摘要一致。没有在应用内下载 APK
  重新计算摘要，不能把这些检查称为对已下载 APK 的完整验签。
- 不同签名／旁装 ID 显示覆盖兼容性提示；Android 版本不足时禁用 APK 下载。
  签名完全相同仅表示基本匹配，不代替 Android 安装校验或所有历史版本的数据升级验收。
  从不建议卸载或清数据解决签名冲突。
- 网络失败、超时、限流、服务错误、发布信息异常和无可用发布是独立结果，失败不显示为
  “已是最新”。偏好读写失败有重试入口，不静默覆盖保存过的渠道。

## 执行结果

| 检查 | 实际结果 |
| --- | --- |
| 全模块格式 / 编译 / JVM | quality、app 402 / audio 225 / protocol 133，共 760 项通过，0 失败 |
| 新增 JVM 回归 | 18 项：版本/兼容性、URL、HTTP 边界、渠道状态/竞态与偏好持久化 |
| Android 离线回归 | API 35 ARM64 模拟器 29 项通过：15 项新增及 14 项已有频道/收藏/暂离/项目入口回归 |
| JSON 与源选择 | 纯内存 fixture，覆盖草稿、正式/测试、发布顺序与 code 不同、缺资产、错误摘要、同名重发等 |
| UI | 中英文、浅/深色、1.8 倍字体、长说明展开与操作可达、失败状态和不兼容提示通过 |
| Lint 与构建 | app Debug / minified Release Lint、Debug/Release 与测试 APK 构建通过；无 Lint 错误，既有提示单独保留 |
| 工具与文档 | 18 项 Python 工具测试、仓库隐私扫描、YAML 语法、本轮文档本地链接与 whitespace 检查通过 |

GitHubReleaseJsonTest 的 transport 为内存函数，所有自动回归都不依赖真实 GitHub 或 TS3 服务。
Android XML/JSON 使用实际平台实现，不以 JVM stub 作为解析通过证据。

## 独立真实网络与 minified 冒烟

另行读取真实 GitHub 最新 Release，观察到 `v1.0.0`、code 14、BUILDINFO schema 1，
APK asset digest 与 BUILDINFO 声明一致。

对同一最终 minified APK 的副本使用本机开发证书签名，仅安装到本轮新建的模拟器：
真实主菜单打开更新页、正式版检查、包含测试版检查、关闭重开后选择保持均通过。
当前公开最新仍为 code 14，页面正确显示当前安装已是最新或更新；“发现新版本”的正向 UI
由离线 fixture 覆盖，没有冒充已存在远端新发布。

该冒烟未连接 TS3 服务器、未下载或安装远端 APK、未使用生产签名密钥。
Xiaomi 14 上的正式稳定版未安装覆盖，本轮未进行 Android 8–14 或另一台实体网络测试。
已有稳定版需要先安装一次包含该功能的版本，之后才拥有应用内检查入口。

## 变更分类与证据

- 生产：独立 updates 目录、主菜单/Activity 导航、复用仓库标识和中英文字符串。
- 测试：4 个 JVM 文件、3 个 Android 文件，以及共享 UI fixture 的菜单计数接线。
- 文档：ADR、本记录、架构、双语 README、安装/发布说明、CHANGELOG 与索引。
- 配置：Manifest 增加不导出的 Activity；CI 加入 3 个离线 Android 测试类。
  依赖锁、正式包版本、签名和权限不变。

证据位于忽略的 `build/update-check-2026-11-09/`：`final-gate.log`、
`final-offline-android-tests.log`、`live-smoke-result.json`、`live-release-screen.png`、
`ui/updates-ui/` 和 `source-and-artifacts.json`。测试签名副本只作 minified 验证，
不作为可覆盖正式用户安装的发布 APK。没有修改 GitHub Release、标签或发布资产。

## 更新界面修复（2026-11-09）

用户反馈关闭位于长页面底部、Markdown 原文难读、展开后难以收起、信息层次不足。
阅读原 UpdatesScreen、Activity、状态/版本策略、UI 测试和依赖配置后，修改限于更新页展示。
保持检查时机、渠道偏好、版本/下载兼容性和浏览器入口策略；不修改协议、音频或连接服务。

- 页面标题、当前安装和关闭操作固定在顶部，滚动长说明不会移动关闭入口。
- 更新渠道使用分组卡片，结果卡突出可用版本和主下载操作；说明预览独立分组。
- Markdown 使用原生 Markwon 4.6.2，支持标题、强调、有序/无序列表、引用、代码块、
  GFM 表格、任务项和删除线，字体大小和颜色跟随 Compose。默认仅预览六行。
- 展开后正文独立滚动，“收起更新说明”固定可见；收起或返回恢复原摘要浏览位置。
  阅读状态跨页面重建保留，新检查结果会退出旧说明。
- 描述中的链接和图片显示标签/替代文字，保留已有发布详情按钮作为浏览入口。
  无 HTML/图片加载插件或 WebView；阅读本身不新增网络请求。

新组件各自承担结果卡、阅读区或 Markdown 适配职责，没有增加超过 300 行的生产文件。
UI 回归文件接近 300 行时已抽离纯测试 fixture，后续新增 Markdown 交互测试继续放在独立
ReleaseNotesMarkdownTest；不把解析或滚动状态放入 Activity/Controller。

本轮变更分类：生产为 UpdatesScreen、新结果卡/说明组件和双语分组标题；测试为 UI 回归、
独立 Markdown Android 测试与 fixture；文档为本记录、ADR、架构、README、日志和第三方声明；
配置为固定版本的 Markwon 依赖/锁文件与 CI 测试类清单。CommonMark 的 BSD 许可随 APK 附带。
Manifest、权限、正式版本配置和签名配置未因本轮修复改变。

验证：应用 421 项 JVM 通过；API 35 ARM64 模拟器 23 项更新相关 Android 测试通过，
包含固定关闭/收起、原滚动位置、重建/返回、新结果替换、MD 各语法与链接/图片边界。
中英文、浅色与中文 1.8 倍深色截图已检查。quality、Kotlin 编译、Debug/Release Lint、
Debug、minified Release 和 Android 测试 APK 构建通过。Lint 无 Error，保持既有
Debug 36 Warning / 1 Information、Release 25 Warning / 1 Information。
实际 minified Release 的开发签名副本仅安装到模拟器：读取公开 Release、展示正确版本状态、
打开 Markdown 正文、固定关闭/收起、收起回摘要及关闭回主页面均通过，未连接 TS3 或下载远端 APK。
仓库隐私、workflow YAML、双语资源 XML、本轮 Markdown 本地链接和 whitespace 检查通过。

本轮证据：忽略目录 build/update-ui-2026-11-09/ 下的 gate.log、final-checks.log、
final-android-tests.log、jvm-summary.json、lint-summary.json、minified-smoke-result.json、
source-and-artifacts.json 与 final-ui/；截图为离线 fixture，不表示远端已发布该测试版本。
本轮未覆盖安装手机，未中断其正在进行的音频测试；没有发布、提交或合并。
