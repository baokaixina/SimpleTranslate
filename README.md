# SimpleTranslate 2.0

**Language:** English | [简体中文](README.zh-CN.md)

SimpleTranslate is a client-side Minecraft translation mod for players who play foreign-language maps, RPG servers, adventure content, quest books, item lore, signs, and UI prompts.

**SimpleTranslate 2.0 can translate between all languages supported by your configured translation model/API.**  
It is not limited to English -> Chinese. You can use it for English, Chinese, Japanese, Korean, mixed multilingual text, or any other source/target language combination your model supports.

> Current public 2.0 download: **Fabric builds only**.

[Download SimpleTranslate 2.0](https://github.com/baokaixina/SimpleTranslate/releases/tag/v2.0)

## What Makes 2.0 Different

SimpleTranslate 2.0 rebuilds the translation system around Minecraft **Component JSON**.

Instead of flattening game text into plain strings and guessing colors or positions later, 2.0 sends Minecraft text components to the model and receives translated Component JSON back. This helps preserve:

- colors and formatting,
- line breaks,
- item lore structure,
- hover tooltip structure,
- sign layout,
- book page order,
- chat formatting,
- HUD/title/actionbar text,
- advancement names and descriptions.

The goal is simple: translate Minecraft text while keeping it looking like Minecraft.

## Main Features

### Chat Translation

Translate server messages, RPG menu text, narrator messages, quest prompts, and formatted chat content.

Chat hover text is handled by a dedicated hover translation path, so hidden hover text is not translated accidentally as a side effect of translating visible chat.

![Chat before](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-chat-before.png)

![Chat after](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-chat-after.png)

### Item Tooltip Translation

Translate item names, attributes, lore, NBT-related text, and complex colored tooltips.

Useful for RPG servers, custom items, adventure maps, and modpacks with long item descriptions.

### Sign Translation

Translate single signs or groups of signs. This is useful for adventure maps, story walls, server rule boards, puzzle hints, and map instructions.

![Sign before](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-sign-before.png)

![Sign after](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-sign-after.png)

### Book Translation

Translate written books and book pages while keeping the reading order clear.

![Book before](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-book-before.png)

![Book after](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-book-after.png)

### Advancement Translation

Translate advancement names and descriptions.

![Advancement before](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-advancement-before.png)

![Advancement after](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-advancement-after.png)

### More Supported Game Text

SimpleTranslate can also translate:

- boss bars,
- scoreboards,
- titles and subtitles,
- actionbar text,
- entity names,
- text display entities,
- item hover tooltips,
- book/chat hover tooltips,
- other Minecraft UI text surfaces supported by the mod.

## Any-Language Translation

The mod itself does not lock you to one language pair.

You can configure the target language in the settings and use any translation provider/model that supports your desired languages.

Examples:

- English -> Chinese
- Chinese -> English
- Japanese -> Chinese
- Korean -> Chinese
- Mixed multilingual text -> Chinese
- Any supported source language -> any supported target language

Translation quality depends on the model/API you configure.

## Cache, Token Statistics, and Original Text

SimpleTranslate includes tools for long-term gameplay:

- persistent translation cache,
- shared cache support,
- token/request statistics,
- configurable translation providers,
- temporary hold-to-show-original behavior.

The hold-to-show-original feature is useful when checking the source text, comparing translations, or taking screenshots.

## Supported Fabric Versions

SimpleTranslate 2.0 currently provides Fabric jars for:

- 1.19.2, 1.19.3, 1.19.4
- 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.20.6
- 1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
- 26.1, 26.1.1, 26.1.2, 26.2

Download the matching jar from the [2.0 release page](https://github.com/baokaixina/SimpleTranslate/releases/tag/v2.0).

## Requirements

- Minecraft Java Edition
- Fabric Loader
- Fabric API
- Client-side installation
- Mod Menu is optional but recommended for opening settings easily
- A configured translation provider/API/model

## Notes

- SimpleTranslate is a client-side helper mod. Servers do not need to install it.
- Translation speed, quality, and token usage depend on your configured provider/model and network environment.
- For best structure preservation, use a model that follows JSON/component instructions reliably.

## Links

- [GitHub Releases](https://github.com/baokaixina/SimpleTranslate/releases)
- [MC百科 Page](https://www.mcmod.cn/class/23154.html)


