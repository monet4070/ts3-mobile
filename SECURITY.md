# Security Policy

## Supported versions

The first stable release is 1.0.0, initially published on 2026-11-04 and reissued
on 2026-11-05 as versionCode 14 after withdrawal of code 13. Fixes target the latest
stable patch and the current development branch on `main`; old milestone APKs are
not maintained separately. There is no long-term support
promise for superseded patches or third-party re-signed builds.

## Reporting a vulnerability

Do not disclose a suspected vulnerability in a public issue, discussion, pull
request, or chat log. Use GitHub's **Report a vulnerability** form in the
repository Security tab to send the maintainers a private report:
[private security report](https://github.com/monet4070/ts3-mobile/security/advisories/new).

Include the affected revision, Android version, reproduction steps, impact,
and any proof of concept that can be shared safely. Remove real server
passwords, identity material, addresses, and participant data from the report.

The maintainers will acknowledge a report when capacity allows, assess whether
it affects the current code, and coordinate disclosure after a fix is available.
This volunteer project cannot promise a response or remediation deadline.

## Scope notes

Reports about TeamSpeak servers, the official TeamSpeak clients, Android, ts3j,
libopus, or RNNoise should be sent to the corresponding upstream project unless
the issue is caused by TS3 Mobile's integration.

## 中文说明

1.0.0 首个稳定版于 2026-11-04 首发，code 13 撤回后于 2026-11-05 以 code 14 重发。
安全修复面向最新稳定补丁和 `main` 开发分支，
不单独维护旧里程碑 APK，不承诺长期维护被替代的补丁或第三方重签包。

请通过上方 GitHub 私密安全报告提交版本、Android 版本、复现方法、影响与可安全分享的概念验证。
不要在公开 Issue、PR 或聊天日志中披露漏洞与真实密码、服务器、身份、用户信息。
维护者会根据人力评估和协调修复，没有固定响应或修复时限。
