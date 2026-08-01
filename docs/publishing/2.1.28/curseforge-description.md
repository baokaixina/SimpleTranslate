# SimpleTranslate 2.1.28

**SimpleTranslate** is a client-side Minecraft translation mod that translates game text in real time through the model/API configured by the player.

It covers:

- received and outgoing chat;
- item tooltips and dedicated hover tooltips;
- books and signs;
- scoreboards, the player list, boss bars, titles, actionbars, entity names, and text display entities;
- whole-screen Minecraft and mod GUIs;
- Component-driven interfaces such as Patchouli, FTB Quests, and Distant Horizons;
- dedicated Wynncraft dialogue/actionbar layout and glyph rendering on Minecraft `>=1.21.4` targets.

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

Fabric 1.12.2 is a narrower Legacy build. Some modern GUI, text-component, and optional-mod compatibility features require newer Minecraft targets. Dedicated Wynncraft support is included only on Minecraft `>=1.21.4` targets.

## Requirements

- Minecraft Java Edition.
- A matching Fabric, Forge, or NeoForge client installation.
- Dependencies listed for the exact file; Fabric builds require the matching Fabric API range.
- Only the Forge 1.12.2 file requires [MixinBooter 9.4 or newer](https://github.com/CleanroomMC/MixinBooter). SimpleTranslate does not bundle it, and no other target requires MixinBooter.
- A compatible translation model/API configured by the player.

SimpleTranslate does not bundle a free translation engine, hosted model, or API quota. Language coverage, translation quality, latency, privacy, and cost depend on the provider selected by the player. The server does not need to install the mod.

Wynncraft, Wynntils, Patchouli, FTB Quests, Distant Horizons, Iceberg, and Legendary Tooltips are third-party projects or services. Compatibility descriptions and screenshots demonstrate SimpleTranslate only and do not imply affiliation, endorsement, sponsorship, or official cooperation.

---

# 简单翻译 2.1.28

SimpleTranslate 是客户端 Minecraft 实时翻译模组，可通过玩家自行配置的模型/API 翻译聊天、物品与悬浮提示、书本、告示牌、HUD、实体名称、文字展示实体，以及 Patchouli、FTB Quests、Distant Horizons 等其他模组的 GUI。

Minecraft `>=1.21.4` 目标还包含 Wynncraft 对话与 Actionbar 语义投影、布局/字形覆盖，以及 Wynntils HUD 兼容。Fabric 1.12.2 为功能面较窄的 Legacy 版本。

仅 Forge 1.12.2 文件需要安装 [MixinBooter 9.4 或更高版本](https://github.com/CleanroomMC/MixinBooter)。SimpleTranslate 不打包该运行依赖，其他目标不需要 MixinBooter。

模组不内置免费翻译引擎、托管模型或 API 额度；语言范围、质量、速度、隐私和费用取决于玩家选择的服务。服务器无需安装。

Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图仅用于展示 SimpleTranslate，不代表隶属、认可、赞助或官方合作。

## Publisher checklist — do not paste as storefront copy

As checked on 2026-08-01, the live [CurseForge project](https://www.curseforge.com/minecraft/mc-mods/simpletranslate) displays **All Rights Reserved**, while the source tree and product metadata declare **MIT**. Before publishing 2.1.28, change or verify the CurseForge project license as **MIT**.

截至 2026-08-01，线上 [CurseForge 项目](https://www.curseforge.com/minecraft/mc-mods/simpletranslate) 显示为 **All Rights Reserved**，而源码与产品元数据声明为 **MIT**。发布 2.1.28 前，请把 CurseForge 项目许可证改为或核对为 **MIT**。
