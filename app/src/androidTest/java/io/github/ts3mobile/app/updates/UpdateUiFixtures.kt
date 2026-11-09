package io.github.ts3mobile.app.updates

internal val longUpdateNotes =
    """
    # 更新说明

    ## 音频与连接
    - **蓝牙播放**：改进路由提示与恢复。
    - 移动网络诊断支持 `JSON` 文件导出。

    > 测试版仍需验证实际弱网环境。

    ## 安装
    1. 保留原应用和身份。
    2. 下载后覆盖安装。

    ```text
    versionCode = 15
    ```

    | 渠道 | 状态 |
    | --- | --- |
    | 正式版 | 可用 |
    | 测试版 | 可选 |

    - [x] 诊断导出
    - [ ] 长时弱网验收

    """.trimIndent() + "\n\n" + "详细改进说明与验证范围。\n\n".repeat(60)

internal fun updateUiRelease() =
    UpdateRelease(
        "Fixture update", "1.1.0-rc.1", 15, true, "2026-11-09T01:00:00Z",
        "$RELEASES_PAGE_URL/tag/v1.1.0-rc.1", "$RELEASES_PAGE_URL/download/v1.1.0-rc.1/app.apk",
        "Improved update discovery", 26, setOf("b".repeat(64)), "a".repeat(64),
    )
