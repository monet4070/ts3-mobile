# ADR-0021: 1.0.0 stable release acceptance / 稳定版发布验收

Date: 2026-11-04. Status: accepted for this release.

## Decision

The maintainer explicitly requested public stable publication of 1.0.0 and
synchronized Chinese/English documentation after receiving the signed candidate.
Proceed with versionCode 13, the existing public certificate, the reviewed PR
and the unchanged, verified signed APK. Retain the evidence boundaries below;
publication does not turn an unperformed check into a pass.

This release decision adjusts the pre-publication physical-device requirements
in the [checklist](../release/1.0.0/CHECKLIST.md) for this release. Final-APK
physical Bluetooth/hot-plug and real-user-data upgrade checks, detailed duration
records and a second Android generation remain follow-up work. They are disclosed
in both release bodies and [compatibility](../COMPATIBILITY.md), rather than
reported as completed. No universal guarantee against transient speaker output
is made: Android/HAL can reroute already buffered PCM before a route callback.

## Evidence

- Frozen build source: `eb7e87d82c5b9aadaa46ddd2258b7de7f475b109`; its CI passed.
- Signed APK SHA-256:
  `bfa68b26eecbd30dad267574df7b30f84e761426d154396adb764babf674475a`.
- Certificate SHA-256:
  `e239102412f17ddd1909bd323b3825831b153fec4602ec00abb359da7d8be592`;
  matches public 0.11.1-m10. Its historical Android Debug subject is unchanged.
- 551 JVM, 129 app Android, 32 default audio Android tests; 48 Opus compatibility
  combinations and 7 Python tests passed. Format/compile/Lint/build gates passed.
- Exact signed-APK identity/INI, Opus 1.6.1 ART and cold-launch probes passed.
- API 35 / 16 KiB emulator update from public code 12 to code 13 retained the UID,
  synthetic private files/preferences and Keystore ciphertext. No real user data
  was accessed; this does not establish physical-device data retention.
- Maintainer-confirmed Xiaomi 14 / Android 16 hands-on feature tests used the
  November 3 isolated QA APK. It predates the new Bluetooth protection and has a
  separate application ID/certificate. Preserve the confirmation at that scope.

The original key was supplied and used without being copied into the repository
or release assets. Long-term custody, access control and offline backup remain
the maintainer's responsibility; no unobserved backup operation is claimed.

## Source and publication binding

Merge through PR #5, retain the built commit in history and tag the merged
release commit. Publication changes only Markdown documentation; compare all
434 recorded source/configuration inputs before tagging. Keep `sourceCommit`
and `sourceTree` in `BUILDINFO.json` as the actual build inputs, and separately
record `releaseCommit`, `releaseTag`, the documentation-only diff and input
verification. Do not relabel an old binary as a rebuild from a new commit.

The 302-line English README was assessed before editing: only its release-status
paragraph changes; detailed operation and release evidence already have dedicated
guides. No further decomposition is needed for this publication. Production code,
tests, Gradle configuration, permissions and storage formats are unchanged.

## 中文

维护者在收到已签名候选包后，明确要求现在公开 1.0.0 稳定版并同步双语资料。
本次按该指示发布 versionCode 13，沿用旧公开证书与已验证 APK，并通过现有 PR/CI 合入。
本决定调整本次发布前的设备验收范围，不把未执行项目改写为通过。

已保留小米 14 / Android 16 对昨日 QA 包的实测确认。最终包的新蓝牙逻辑、热插拔、
真实用户数据升级及第二 Android 代际设备仍待复核；细分通话时长不补造记录。
自动化与签名包模拟器结果只证明相应的软件及升级场景。系统可能在回调前外放已缓冲音频，
不承诺零瞬时外放。以上边界随中英文发布说明公开。

构建源码提交与最终发布提交分别记录，434 个源码/配置输入必须相同；后续仅文档变更
不触发重新构建或重签。密钥、mapping 和私人诊断不进入 Git 或公开发布资产。
