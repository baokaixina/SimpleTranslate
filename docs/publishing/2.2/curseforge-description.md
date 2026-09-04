# SimpleTranslate 2.2

**SimpleTranslate** is a client-side Minecraft translation mod that translates game text in real time through the model/API configured by the player.

It covers:

- received and outgoing chat, including slash commands;
- item tooltips and dedicated hover tooltips;
- books and signs, including [Scholar](https://www.curseforge.com/minecraft/mc-mods/scholar) books;
- scoreboards, the player list, boss bars, titles, actionbars, entity names, and text display entities;
- whole-screen Minecraft and mod GUIs;
- Component-driven interfaces such as Patchouli, FTB Quests, and Distant Horizons;
- dedicated Wynncraft dialogue/actionbar layout and glyph rendering on Minecraft `>=1.21.4` targets.

## What is new in 2.2

**The GUI shortcut translates once.** Pressing the GUI key (`K` by default) translates the current screen and stops. In 2.1.x an accepted translation left the request permission open, so the shortcut kept issuing new model requests for as long as the screen stayed open — SHORTCUT mode effectively behaved like AUTO. Use AUTO mode if you want continuous translation.

**Scholar book support.** Written books and book-and-quill screens get a translate tab on the outer edge of the book, clear of both pages. On the edit screen the translation is used for rendering only: the page data, the modified flag, and the save/export paths never see it, so closing the screen leaves the text you actually wrote. Typing, adding or removing a page, or opening a different book drops the translation so stale text is never shown against new content. Scholar is an optional dependency — without it installed, nothing loads and behaviour is unchanged.

**Command message translation.** Pressing `Ctrl+Enter` on a slash command now translates only its human-readable parts. Command names, target selectors, coordinates, namespaced ids, JSON/SNBT keys, and translation keys are left intact:

    /say 大家好                                → /say Hello everyone
    /tellraw @s {"text":"你好","color":"gold"}  → {"text":"Hello","color":"gold"}
    /gamemode creative                        → sent unchanged

Vanilla free-text commands (`/say`, `/me`, `/msg`, `/tell`, `/w`, `/whisper`, `/teammsg`, `/tm`) translate their message tail; other commands translate quoted string literals, including ones nested inside SNBT. A new **Server Chat Commands** setting lists the server channel commands to treat as free text — defaulting to `pc gc tc ac r bc broadcast shout global local` — because channel commands differ per server and cannot be detected from the client. Append `:n` to skip leading arguments, as in `party:1` for `/party chat <text>`.

**Over-length protection.** A translation that would exceed Minecraft's 256-character chat/command limit is reported rather than sent, instead of transmitting a payload the server rejects.

**Unified version numbers.** All 56 builds now ship as `2.2`. Previous releases were split across 2.1.28, 2.1.29, and 2.1.30 depending on the target.

## Translation that stays inside Minecraft's structure

Every game-text surface uses a Component JSON array pipeline. Translated semantics are rebound to the current component tree so styles, layout, click events, dynamic numbers, and resource-pack glyphs remain owned by the client. Hidden hover payloads are handled by dedicated tooltip paths instead of being sent incidentally with visible text.

Item tooltips rebuild translations against each frame's live icons, styles, progress values, and spacing. Existing persistent item-tooltip cache entries can be used on the first item-tooltip render.

## Context, profiles, and controls

- Opt in to historical translation context and select its allowed text sources.
- Save reference prompts for global, server, or single-player-world scopes.
- Configure keyboard and mouse chords for the global toggle, chat mode, GUI and tooltip translation, sign actions, and hold-to-show-original controls.
- Manage local translations, terminology, blacklist entries, and request/Token statistics in game.

## Loader and Minecraft coverage

- **Fabric (29):** 1.12.2, 1.16.5, 1.18.2, 1.19.2–1.19.4, 1.20–1.20.6, 1.21–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2.
- **Forge (5):** 1.12.2, 1.16.5, 1.18.2, 1.19.2, 1.20.1.
- **NeoForge (22):** 1.20.1–1.20.6, 1.21–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2.

Fabric 1.12.2 is a narrower Legacy build. Some modern GUI, text-component, and optional-mod compatibility features require newer Minecraft targets. Dedicated Wynncraft support is included only on Minecraft `>=1.21.4` targets. Command message translation is available on every build except the two 1.12.2 ones. Scholar support is included on Minecraft 1.20.1 and `>=1.21` targets.

## Requirements

- Minecraft Java Edition.
- A matching Fabric, Forge, or NeoForge client installation.
- Dependencies listed for the exact file; Fabric builds require the matching Fabric API range.
- Only the Forge 1.12.2 file requires [MixinBooter 9.4 or newer](https://github.com/CleanroomMC/MixinBooter). SimpleTranslate does not bundle it, and no other target requires MixinBooter.
- A compatible translation model/API configured by the player.

SimpleTranslate does not bundle a free translation engine, hosted model, or API quota. Language coverage, translation quality, latency, privacy, and cost depend on the provider selected by the player. The server does not need to install the mod.

Scholar, Wynncraft, Wynntils, Patchouli, FTB Quests, Distant Horizons, Iceberg, and Legendary Tooltips are third-party projects or services. Compatibility descriptions and screenshots demonstrate SimpleTranslate only and do not imply affiliation, endorsement, sponsorship, or official cooperation.

---

# 简单翻译 2.2

SimpleTranslate 是客户端 Minecraft 实时翻译模组，可通过玩家自行配置的模型/API 翻译聊天、物品与悬浮提示、书本、告示牌、HUD、实体名称、文字展示实体，以及 Patchouli、FTB Quests、Distant Horizons 等其他模组的 GUI。

## 2.2 新增

**GUI 快捷键只翻译一次。** 按 GUI 键（默认 `K`）翻译当前界面后即停止。2.1.x 中一次被接受的翻译会让请求许可持续有效，快捷键会在界面打开期间不断发出新的模型请求，SHORTCUT 模式实际表现等同于 AUTO。需要持续翻译请改用 AUTO 模式。

**Scholar 书籍支持。** 成书与书与笔界面在书页外缘获得翻译标签，不遮挡任何一页。编辑界面的译文**仅用于渲染**：页面数据、修改标记和保存/导出路径都不会看到它，关闭界面后书里仍是你写的原文。输入、增删页面或打开另一本书都会丢弃译文，避免旧译文对上新内容。Scholar 为可选依赖，未安装时不加载任何相关代码，行为与此前一致。

**命令消息翻译。** 对斜杠命令按 `Ctrl+Enter`，现在只翻译其中人类可读的部分，命令名、目标选择器、坐标、命名空间 ID、JSON/SNBT 键名和翻译键保持原样：

    /say 大家好                                → /say Hello everyone
    /tellraw @s {"text":"你好","color":"gold"}  → {"text":"Hello","color":"gold"}
    /gamemode creative                        → 原样发出

原版自由文本命令（`/say`、`/me`、`/msg`、`/tell`、`/w`、`/whisper`、`/teammsg`、`/tm`）翻译其消息部分；其他命令翻译引号字符串，包括嵌套在 SNBT 内的。由于服务器频道命令因服而异、客户端无法探测，新增**服务器聊天命令**设置项供你列出按自由文本处理的命令，默认为 `pc gc tc ac r bc broadcast shout global local`；加 `:n` 表示先跳过若干参数，如 `party:1` 对应 `/party chat <文本>`。

**超长保护。** 译文超过 Minecraft 的 256 字符聊天/命令上限时会提示，而不是发出会被服务端拒绝的内容。

**版本号统一。** 全部 56 个构建现在统一为 `2.2`；此前各目标分散在 2.1.28、2.1.29 和 2.1.30。

## 其他说明

Minecraft `>=1.21.4` 目标还包含 Wynncraft 对话与 Actionbar 语义投影、布局/字形覆盖，以及 Wynntils HUD 兼容。Fabric 1.12.2 为功能面较窄的 Legacy 版本。命令消息翻译在除两个 1.12.2 之外的所有构建可用；Scholar 支持包含在 Minecraft 1.20.1 及 `>=1.21` 目标中。

仅 Forge 1.12.2 文件需要安装 [MixinBooter 9.4 或更高版本](https://github.com/CleanroomMC/MixinBooter)。SimpleTranslate 不打包该运行依赖，其他目标不需要 MixinBooter。

模组不内置免费翻译引擎、托管模型或 API 额度；语言范围、质量、速度、隐私和费用取决于玩家选择的服务。服务器无需安装。

Scholar、Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图仅用于展示 SimpleTranslate，不代表隶属、认可、赞助或官方合作。

## Publisher checklist — do not paste as storefront copy

- The repository `LICENSE` and product metadata declare **MIT**. As of the 2.1.28 release notes the live [CurseForge project](https://www.curseforge.com/minecraft/mc-mods/simpletranslate) still displayed **All Rights Reserved**. Verify the project license reads **MIT** before publishing 2.2.
- 仓库 `LICENSE` 与产品元数据声明为 **MIT**。截至 2.1.28 发布说明，线上 [CurseForge 项目](https://www.curseforge.com/minecraft/mc-mods/simpletranslate)仍显示 **All Rights Reserved**。发布 2.2 前请核对项目许可证为 **MIT**。
- Add Scholar as an **optional** dependency on the file/project relations for the 27 builds that carry the integration. Do not mark it required.
- 为携带该集成的 27 个构建把 Scholar 添加为**可选**依赖关系，不要标为必需。
