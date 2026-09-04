# TS3 Mobile Architecture / TS3 Mobile 架构说明

This bilingual index points to the English and Chinese architecture documents. Both share the
same diagrams (regenerated from `.mmd` under [`docs/images/`](images/)); the prose in each is a
parallel translation, not a fork — keep them in sync when you edit either.

本双语索引指向英文与中文架构文档。两者共用相同的图示（由
[`docs/images/`](images/) 下的 `.mmd` 重新生成）；两份文字是平行翻译而非分叉，修改任一版本时
请同步另一版本。

- English / 英文: [`ARCHITECTURE_EN.md`](ARCHITECTURE_EN.md)
- 中文 / Chinese: [`ARCHITECTURE_ZH.md`](ARCHITECTURE_ZH.md)
- Top-level architecture note (canonical) / 顶层架构说明（权威）: [`../ARCHITECTURE.md`](../ARCHITECTURE.md)

## Diagrams / 图示

| Diagram / 图示 | Source / 源文件 |
| --- | --- |
| Overall architecture / 总体架构 | [`images/01_overall_architecture.mmd`](images/01_overall_architecture.mmd) |
| Protocol connection sequence / 协议连接时序 | [`images/02_protocol_sequence.mmd`](images/02_protocol_sequence.mmd) |
| Audio pipeline / 音频管线 | [`images/03_audio_pipeline.mmd`](images/03_audio_pipeline.mmd) |
| Lifecycle and security / 生命周期与安全 | [`images/04_lifecycle_security.mmd`](images/04_lifecycle_security.mmd) |
| UI data flow (MVI) / UI 数据流 | [`images/05_ui_mvi.mmd`](images/05_ui_mvi.mmd) |

To regenerate the images see the "Regenerating diagrams" section of either language document.
重新生成图片请见任一语言文档中的"重新生成图示 / Regenerating diagrams"小节。
