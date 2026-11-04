# 首个稳定版发布资料 / First stable release pack

目标：**TS3 Mobile 1.0.0**，Git tag `v1.0.0`，首个稳定版。
状态：**2026-11-04 已进入候选发布准备；源码版本 1.0.0 / 13，尚未公开。**

GitHub 当前最新公开版本仍是 `v0.11.1-m10` / versionCode 12。候选源码已切换为
`1.0.0` / versionCode 13。维护者确认已在小米 14 上完成剩余实测；昨天的隔离 QA 包
已定位，签名与确切产物边界见[本轮发布准备记录](1.0.0/PREPARATION_2026-11-04.md)。

## 文件入口

| 文件 | 用途 |
| --- | --- |
| [中文 Release 正文](1.0.0/RELEASE_NOTES_ZH.md) | 发布清单完成后可用于 GitHub Release；顶部准备状态说明在真正发布时移除 |
| [English release body](1.0.0/RELEASE_NOTES.md) | 同一版本的英文说明，可并入同一 GitHub Release |
| [发布清单与验收表](1.0.0/CHECKLIST.md) | 记录源码、版本、签名、自动化、实测、资产和公开步骤 |
| [打包与上线手册](1.0.0/PUBLISHING.md) | 版本冻结、签名、校验、资产清单、草稿与正式发布命令 |
| [异机签名交接](1.0.0/SIGNING_HANDOFF.md) | 在持有旧公开密钥的电脑核对候选、签名并绑定最终证据 |
| [安装升级说明](../INSTALLATION.md) | 下载校验、签名兼容、旁装包与身份保留 |
| [中英文用户资料](../USER_GUIDE_ZH.md) | 日常操作，另有英文指南和常见问题 |
| [兼容性与验证范围](../COMPATIBILITY.md) | 当前证据和待验收能力，避免把构建成功等同稳定验收 |

## 对外信息约定

- 版本标题：`TS3 Mobile 1.0.0 · 首个稳定版`；英文可用 `First stable release`。
- 候选 versionName 为 `1.0.0`、versionCode 为 `13`。已核对 GitHub 公开包最高为 12；本地私发包若有更高值，
  versionCode 必须高于需要升级的旧包；若 13 已使用则继续递增。
- APK：`ts3-mobile-1.0.0-release.apk`；校验表：`SHA256SUMS.txt`；构建信息：`BUILDINFO.json`。
- 正式应用 ID：`io.github.ts3mobile.app`；不分发 `uidebug`/F1a 旁装包作为正式更新。
- 支持说明：Android 8.0+ 可安装，当前实测范围单独披露，不宣称所有设备均验证。
- 不写“已经上线”“已全部验收”“零问题”，也不预填未生成的哈希、证书或发布日期。

较早的 [M11 清单](M11_RELEASE_CHECKLIST.md) 与持续开发计划保留作历史背景，
当前稳定版工作以本资料和其中明确沿用的验收标准为入口。未执行的旧验收项仍是未执行。

## English

This pack prepares `v1.0.0` as the first stable release; it does not publish it.
The public latest release remains `0.11.1-m10` (code 12). Candidate source metadata
is now `1.0.0` / code 13. The maintainer confirmed remaining hands-on checks on a
Xiaomi 14; the identified November 3 QA APK and signing/artifact boundaries are
recorded in the [preparation log](1.0.0/PREPARATION_2026-11-04.md).
Use the bilingual release bodies after the checklist and exact signed-APK
acceptance are complete. Do not publish checksums, certificate claims or test
passes until they come from the actual release artifact.
