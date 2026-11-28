# Support / 获取支持

[English](#english) · [中文使用指南](docs/USER_GUIDE_ZH.md) · [User Guide](docs/USER_GUIDE.md) · [常见问题](docs/TROUBLESHOOTING_ZH.md) · [Troubleshooting](docs/TROUBLESHOOTING.md)

## 报告问题

1. **先确认版本与常见问题**：核对当前安装的 TS3 Mobile 版本（见[发布资料索引](docs/release/README.md)），如涉及安装、签名或覆盖升级，先阅读[安装与升级说明](docs/INSTALLATION.md)。
2. **搜索已有 Issue**：在 [GitHub Issues](https://github.com/monet4070/ts3-mobile/issues) 检索同类问题；未找到时，缺陷请提交 **Bug report**，新建议请提交 **Feature request**。
3. **提供必要上下文**：
   - 应用版本／`versionCode`（或源码提交）、下载来源与构建类型（正式包／Debug 包）
   - 手机型号、Android 版本与厂商系统（如 HyperOS / OneUI）
   - 音频路由（耳机、蓝牙、外放）、前后台状态、复现步骤、期望结果与实际结果
4. **附带脱敏诊断**：
   - 在 **更多选项 → 设置 → 诊断** 中可复制脱敏报告或保存 JSON 文件；遇到断音或切网问题可先按[离线音频诊断步骤](docs/USER_GUIDE_ZH.md#离线音频诊断)点击 **标记刚才断音** 后再导出。
   - 反馈频道文件或聊天附件问题时，请注明是在**读取列表**、**下载**还是**保存**阶段失败以及有无权限提示，不要附带真实文件或聊天正文。
   - 反馈浮窗 PTT 问题时，请注明悬浮窗权限、浮窗样式（标准／极简）、麦克风模式以及触发方式（手动／通知召回／退后台自动显示）。
5. **分享前务必脱敏**：检查日志、截图与剪贴板，删除真实服务器地址、密码、身份、昵称与其他成员信息；切勿上传 `.ts3mbak` / `.ini` 身份文件、Keystore、签名私钥或原始网络抓包。可用 `voice.example.com` 和示例昵称代替。

> **安全漏洞请勿公开提交 Issue。** 请使用 [GitHub 私密安全报告](https://github.com/monet4070/ts3-mobile/security/advisories/new)，完整流程见[安全政策](SECURITY.md#中文说明)。

本项目由社区维护，没有付费客服或固定响应时限。Android 厂商省电策略、网络环境与服务器权限均会影响实际表现；已验证覆盖与已知限制见[兼容性与验证范围](docs/COMPATIBILITY.md)。应用不会自动上传诊断，也不会通过仓库 Star 操作发送会话数据。

---

## English

[简体中文](#报告问题) · [User Guide](docs/USER_GUIDE.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) · [Install & Upgrade](docs/INSTALLATION.md#english)

1. **Check your version and docs first:** Confirm your installed version against the [Releases Index](docs/release/README.md). For install, signing, or upgrade conflicts, read [Installation & Upgrades](docs/INSTALLATION.md#english) before taking action.
2. **Search existing issues:** Check [GitHub Issues](https://github.com/monet4070/ts3-mobile/issues) first, then open a **Bug report** for defects or a **Feature request** for proposals.
3. **Include helpful context:**
   - App version / `versionCode` (or source commit), download source, and build type (Release vs Debug)
   - Device model, Android version, and vendor ROM
   - Audio route (headset, Bluetooth, speaker), foreground/background state, exact steps, expected vs actual results
4. **Attach sanitized diagnostics:**
   - Use **More options → Settings → Diagnostics** to copy a redacted report or save JSON. For audio dropouts or network handoff issues, follow [Offline audio diagnostics](docs/USER_GUIDE.md#offline-audio-diagnostics) to mark interruptions before exporting.
   - For channel file or chat attachment issues, note whether listing, downloading, or saving failed and whether a permission error appeared—never attach real files or chat contents.
   - For floating PTT issues, include overlay permission status, style (Standard/Minimal), mic mode, and trigger path (manual, notification recall, or auto-show).
5. **Sanitize before posting:** Review logs, screenshots, and clipboard text to remove real server endpoints, passwords, identities, nicknames, and participant data. Never upload identity files, keystores, private keys, or raw packet captures.

> **Do not report security vulnerabilities in public issues.** Use the [private security report form](https://github.com/monet4070/ts3-mobile/security/advisories/new) following [SECURITY.md](SECURITY.md).

This community-maintained project has no support SLA. Device coverage and open release gates are recorded in [Compatibility & Validation](docs/COMPATIBILITY.md#english). The app never uploads diagnostics automatically.
