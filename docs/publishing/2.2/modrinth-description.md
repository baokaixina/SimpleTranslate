# SimpleTranslate 2.2 / 简单翻译 2.2

SimpleTranslate is a **client-side real-time Minecraft translation mod** for chat, item and hover tooltips, books, signs, HUD text, entity names, text displays, and other mods' GUIs. It uses the language model/API configured by the player and is not limited to one language pair.

SimpleTranslate 是一个**客户端 Minecraft 实时翻译模组**，覆盖聊天、物品与悬浮提示、书本、告示牌、HUD、实体名称、文字展示实体，以及其他模组的 GUI。模组使用玩家自行配置的语言模型/API，不限定单一语言方向。

## New in 2.2 / 2.2 新增

- **The GUI shortcut translates once.** Pressing `K` translates the current screen and stops. In 2.1.x an accepted translation kept the request permission open, so the shortcut kept spending tokens for as long as the screen was open.
- **GUI 快捷键只翻译一次。** 按 `K` 翻译当前界面后即停止。2.1.x 中一次被接受的翻译会让请求许可持续有效，快捷键会在界面打开期间持续消耗 Token。
- **Scholar book support.** Written books and book-and-quill screens from Scholar get a translate tab on the outer edge of the book. On the edit screen the translation is display-only, so the saved book keeps the text you wrote.
- **Scholar 书籍支持。** Scholar 的成书与书与笔界面在书页外缘获得翻译标签；编辑界面的译文仅用于显示，保存的书里仍是你写的原文。
- **Command messages can be translated.** `Ctrl+Enter` on a slash command translates only its readable text and leaves command names, selectors, coordinates, ids and JSON keys untouched. A configurable list covers server channel commands such as `/pc` and `/gc`.
- **命令消息可翻译。** 对斜杠命令按 `Ctrl+Enter` 只翻译其中可读文本，命令名、选择器、坐标、ID 和 JSON 键名保持不变；可配置列表覆盖 `/pc`、`/gc` 等服务器频道命令。
- **Over-length protection.** A translation longer than Minecraft's 256-character limit is reported instead of being sent.
- **超长保护。** 译文超过 Minecraft 的 256 字符上限时会提示，而不是发送出去。

## Features / 功能

- Received and outgoing chat translation with AUTO and manual modes.
- Outgoing slash commands, with syntax preserved and a configurable server chat-command list.
- Item tooltips and dedicated hover-tooltip translation paths.
- Books, signs, scoreboards, player list, boss bars, titles, actionbars, entity names, and text displays.
- Whole-screen GUI translation by shortcut (`K` by default) or automatic mode.
- Other mods' Component-driven interfaces, including Patchouli guides, FTB Quests, Distant Horizons, and Scholar books.
- Dedicated Wynncraft dialogue/actionbar projection and glyph/layout rendering on Minecraft `>=1.21.4`, including Wynntils HUD compatibility.
- Opt-in historical translation context and global/server/world reference-prompt profiles.
- Configurable keyboard and mouse chords, including hold-to-show-original controls.
- Persistent local cache, cache management, request/Token statistics, terminology, and blacklist tools.

## Structure and privacy / 结构与隐私

All game-text surfaces use a Component JSON array pipeline. Visible semantics are rebound to the current Minecraft component structure to preserve style, layout, click events, dynamic values, and custom-font icons. Hidden hover payloads in ordinary visible text are not sent as a side effect; dedicated hover paths translate tooltip content.

Historical translation context is opt-in and configurable by text source. SimpleTranslate does not bundle a free engine, hosted model, or API quota. Review the privacy, billing, and data-processing policies of the provider you configure.

## Supported versions / 支持版本

| Loader | Minecraft versions |
| --- | --- |
| Fabric | 1.12.2, 1.16.5, 1.18.2, 1.19.2–1.19.4, 1.20–1.20.6, 1.21–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 |
| Forge | 1.12.2, 1.16.5, 1.18.2, 1.19.2, 1.20.1 |
| NeoForge | 1.20.1–1.20.6, 1.21–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 |

Fabric 1.12.2 is a narrower Legacy build. Some modern GUI and compatibility features require newer Minecraft targets. Dedicated Wynncraft support is included only on Minecraft `>=1.21.4` targets. Command message translation is available on every build except the two 1.12.2 ones. Scholar support is included on Minecraft 1.20.1 and `>=1.21` targets.

Fabric 1.12.2 是功能面较窄的 Legacy 版本，部分现代 GUI 与兼容功能需要更新的 Minecraft 目标。Wynncraft 专用支持仅限 Minecraft `>=1.21.4`。命令消息翻译在除两个 1.12.2 之外的所有构建可用。Scholar 支持包含在 Minecraft 1.20.1 及 `>=1.21` 目标中。

## Installation / 安装

1. Install the matching Fabric, Forge, or NeoForge loader.
2. Install dependencies listed for the exact download; Fabric builds require the matching Fabric API range.
   Only the Forge 1.12.2 download also requires [MixinBooter 9.4 or newer](https://github.com/CleanroomMC/MixinBooter). SimpleTranslate does not bundle it, and no other target requires MixinBooter.
3. Put the matching SimpleTranslate JAR in the client instance's `mods` directory.
4. Configure the API URL, key, model, and language direction in game.

The Minecraft server does not need to install SimpleTranslate. [Scholar](https://modrinth.com/mod/scholar) is optional; SimpleTranslate works exactly as before without it.

Minecraft 服务端无需安装 SimpleTranslate。[Scholar](https://modrinth.com/mod/scholar) 为可选，未安装时 SimpleTranslate 行为与此前完全一致。

Scholar, Wynncraft, Wynntils, Patchouli, FTB Quests, Distant Horizons, Iceberg, and Legendary Tooltips are third-party projects or services. Compatibility descriptions and screenshots demonstrate SimpleTranslate only and do not imply affiliation, endorsement, sponsorship, or official cooperation.

Scholar、Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图仅用于展示 SimpleTranslate，不代表隶属、认可、赞助或官方合作。
