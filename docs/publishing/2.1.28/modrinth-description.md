# SimpleTranslate 2.1.28 / 简单翻译 2.1.28

SimpleTranslate is a **client-side real-time Minecraft translation mod** for chat, item and hover tooltips, books, signs, HUD text, entity names, text displays, and other mods' GUIs. It uses the language model/API configured by the player and is not limited to one language pair.

SimpleTranslate 是一个**客户端 Minecraft 实时翻译模组**，覆盖聊天、物品与悬浮提示、书本、告示牌、HUD、实体名称、文字展示实体，以及其他模组的 GUI。模组使用玩家自行配置的语言模型/API，不限定单一语言方向。

## Features / 功能

- Received and outgoing chat translation with AUTO and manual modes.
- Item tooltips and dedicated hover-tooltip translation paths.
- Books, signs, scoreboards, player list, boss bars, titles, actionbars, entity names, and text displays.
- Whole-screen GUI translation by shortcut (`K` by default) or automatic mode.
- Other mods' Component-driven interfaces, including Patchouli guides, FTB Quests, and Distant Horizons.
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

Fabric 1.12.2 is a narrower Legacy build. Some modern GUI and compatibility features require newer Minecraft targets. Dedicated Wynncraft support is included only on Minecraft `>=1.21.4` targets.

## Installation / 安装

1. Install the matching Fabric, Forge, or NeoForge loader.
2. Install dependencies listed for the exact download; Fabric builds require the matching Fabric API range.
   Only the Forge 1.12.2 download also requires [MixinBooter 9.4 or newer](https://github.com/CleanroomMC/MixinBooter). SimpleTranslate does not bundle it, and no other target requires MixinBooter.
3. Put the matching SimpleTranslate JAR in the client instance's `mods` directory.
4. Configure the API URL, key, model, and language direction in game.

The Minecraft server does not need to install SimpleTranslate.

Wynncraft, Wynntils, Patchouli, FTB Quests, Distant Horizons, Iceberg, and Legendary Tooltips are third-party projects or services. Compatibility descriptions and screenshots demonstrate SimpleTranslate only and do not imply affiliation, endorsement, sponsorship, or official cooperation.

仅 Forge 1.12.2 下载文件还需要安装 [MixinBooter 9.4 或更高版本](https://github.com/CleanroomMC/MixinBooter)。SimpleTranslate 不打包该运行依赖，其他目标不需要 MixinBooter。

Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图仅用于展示 SimpleTranslate，不代表隶属、认可、赞助或官方合作。
