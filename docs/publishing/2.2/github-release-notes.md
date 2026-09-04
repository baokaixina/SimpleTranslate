# SimpleTranslate 2.2

SimpleTranslate 2.2 makes the GUI shortcut translate once again instead of continuously, adds support for the Scholar book mod, and lets outgoing slash commands be translated without breaking their syntax.

SimpleTranslate 2.2 让 GUI 快捷键恢复"按一次翻译一次"，新增对 Scholar 书籍模组的适配，并让发出的斜杠命令能在不破坏语法的前提下被翻译。

All 56 builds now share the version number `2.2`. Previous releases were split across 2.1.28, 2.1.29, and 2.1.30 depending on the target.

全部 56 个构建现在统一为版本号 `2.2`。此前各目标分散在 2.1.28、2.1.29 和 2.1.30。

## Fixed / 修复

- The GUI shortcut (`K` by default) translates the current screen once. Previously an accepted translation granted standing permission for further requests, so SHORTCUT mode kept issuing new model requests for as long as the screen stayed open, effectively behaving like AUTO.
- GUI 快捷键（默认 `K`）现在只翻译当前界面一次。此前一次被接受的翻译会成为后续请求的长期许可，导致 SHORTCUT 模式在界面打开期间不断发出新的模型请求，实际表现等同于 AUTO。
- Pressing the GUI shortcut inside a screen no longer leaves in-world HUD translation permanently armed after the screen closes.
- 在界面内按下 GUI 快捷键后，关闭界面不再让世界内 HUD 翻译保持长期开启。
- A translation that would exceed Minecraft's 256-character chat/command limit is no longer sent. The client reports the length instead of transmitting a payload the server rejects.
- 译文若超过 Minecraft 的 256 字符聊天/命令上限则不再发送，客户端会提示长度，而不是发出会被服务端拒绝的内容。

## Added / 新增

### Scholar book support / Scholar 书籍支持

- Written books and book-and-quill screens from [Scholar](https://modrinth.com/mod/scholar) can be translated. A translate tab sits on the outer edge of the book, clear of both pages.
- 可翻译 [Scholar](https://modrinth.com/mod/scholar) 的成书与书与笔界面。翻译标签位于书页外缘，不遮挡任何一页。
- On the edit screen the translation is swapped in for rendering only. The page data, the modified flag, and the save/export paths never see it, so closing the screen leaves the text you actually wrote.
- 编辑界面的译文仅用于渲染，不进入页面数据、修改标记和保存/导出路径；关闭界面后书里仍是你写的原文。
- Typing, adding or removing a page, or switching to a different book drops the translation session so stale text is never shown against new content.
- 输入、增删页面或切换到另一本书都会丢弃翻译会话，避免旧译文对上新内容。
- The integration is reflection-only and gated by a mixin config plugin. Scholar is an optional dependency; without it installed nothing loads and nothing changes.
- 该集成完全基于反射，并由 mixin 配置插件按需启用。Scholar 是可选依赖，未安装时不加载任何相关代码，行为与此前一致。

### Command message translation / 命令消息翻译

- `Ctrl+Enter` on a slash command translates only the human-readable parts. Command names, target selectors, coordinates, namespaced ids, JSON/SNBT keys, and translation keys are left byte-for-byte intact.
- 对斜杠命令按 `Ctrl+Enter` 只翻译其中人类可读的部分；命令名、目标选择器、坐标、命名空间 ID、JSON/SNBT 键名和翻译键逐字节保持原样。
- Vanilla free-text commands (`/say`, `/me`, `/msg`, `/tell`, `/w`, `/whisper`, `/teammsg`, `/tm`) translate their message tail. Other commands translate quoted string literals, including ones nested inside SNBT.
- 原版自由文本命令（`/say`、`/me`、`/msg`、`/tell`、`/w`、`/whisper`、`/teammsg`、`/tm`）翻译其消息部分；其他命令翻译引号字符串，包括嵌套在 SNBT 内的。
- A new **Server Chat Commands** setting lists the server channel commands to treat as free text, defaulting to `pc gc tc ac r bc broadcast shout global local`. Append `:n` to skip leading arguments, as in `party:1` for `/party chat <text>`.
- 新增**服务器聊天命令**设置项，列出按自由文本处理的服务器频道命令，默认 `pc gc tc ac r bc broadcast shout global local`；加 `:n` 表示先跳过若干参数，如 `party:1` 对应 `/party chat <文本>`。
- A command with nothing translatable in it is sent unchanged.
- 命令中没有可翻译内容时原样发出。

### Book screens in whole-screen translation / 书籍界面纳入整屏翻译

- Vanilla and Scholar book screens now participate in whole-screen GUI translation, so buttons and labels around the pages are translated. Page bodies stay owned by the book's own translation control, which avoids duplicate requests for the same text.
- 原版和 Scholar 的书籍界面现在参与整屏 GUI 翻译，书页周围的按钮和标签会被翻译；正文仍由书籍自身的翻译控件负责，避免同一段文本被重复请求。

## Coverage / 覆盖范围

| Feature | Builds |
| --- | --- |
| GUI one-shot fix, over-length guard | All 56 / 全部 56 个 |
| Command message translation | 54 (all except Fabric 1.12.2 and Forge 1.12.2) |
| Scholar book support | 27 (Fabric and NeoForge 1.20.1, 1.21, 1.21.1, 1.21.4, 1.21.5, 1.21.8–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2; plus Forge 1.20.1) |

The 1.12.2 builds have no outgoing-chat feature, so command translation does not apply there.

1.12.2 构建没有发送前翻译功能，因此命令翻译不适用。

## Supported releases / 发布范围

| Loader | Minecraft versions | Builds |
| --- | --- | ---: |
| Fabric | 1.12.2, 1.16.5, 1.18.2, 1.19.2–1.19.4, 1.20–1.20.6, 1.21–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 | 29 |
| Forge | 1.12.2, 1.16.5, 1.18.2, 1.19.2, 1.20.1 | 5 |
| NeoForge | 1.20.1–1.20.6, 1.21–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 | 22 |

Fabric 1.12.2 is a narrower Legacy build. Dedicated Wynncraft support is included only on Minecraft `>=1.21.4` targets.

Fabric 1.12.2 是功能面较窄的 Legacy 版本；Wynncraft 专用支持仅随 Minecraft `>=1.21.4` 目标提供。

Only the Forge 1.12.2 build requires [MixinBooter 9.4 or newer](https://github.com/CleanroomMC/MixinBooter) at runtime. SimpleTranslate does not bundle it, and no other target requires MixinBooter.

仅 Forge 1.12.2 构建需要在运行时安装 [MixinBooter 9.4 或更高版本](https://github.com/CleanroomMC/MixinBooter)。SimpleTranslate 不打包该依赖，其他目标不需要 MixinBooter。

## Upgrade notes / 升级说明

- No configuration or cache migration is required from 2.1.28/2.1.29/2.1.30. Persistence stays under `stx2` and cache entries stay `component_json_v1`.
- 从 2.1.28/2.1.29/2.1.30 升级无需迁移配置或缓存；持久化仍在 `stx2`，缓存条目仍为 `component_json_v1`。
- If you relied on the GUI shortcut behaving like continuous translation, switch GUI translation mode to AUTO. The shortcut is one-shot by design and by documentation.
- 如果你此前依赖 GUI 快捷键的持续翻译表现，请把 GUI 翻译模式改为 AUTO；快捷键的设计与文档定义都是一次性。
- Scholar's edit screen binds `Ctrl+K` to the obfuscated formatting code. If you want the GUI shortcut to work while editing a book, bind it to a chord that does not collide, such as `Alt+K`.
- Scholar 的编辑界面把 `Ctrl+K` 绑定为乱码格式符。若希望在编辑书籍时使用 GUI 快捷键，请改绑为不冲突的组合键，例如 `Alt+K`。

See [`CHANGELOG.md`](../../../CHANGELOG.md) for the full history.

Scholar, Wynncraft, Wynntils, Patchouli, FTB Quests, Distant Horizons, Iceberg, and Legendary Tooltips are third-party projects or services. Compatibility descriptions and screenshots demonstrate SimpleTranslate only and do not imply affiliation, endorsement, sponsorship, or official cooperation.

Scholar、Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图仅用于展示 SimpleTranslate，不代表隶属、认可、赞助或官方合作。
