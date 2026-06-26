# SimpleTranslate 2.0

**语言 / Language:** [中文说明](#中文说明) | [English](#english)

---

## 中文说明

SimpleTranslate / 简单翻译 是一个客户端 Minecraft 实时翻译模组，适合游玩外语地图、RPG 服务器、冒险内容、任务书、物品 lore、告示牌和各种 UI 文本。

**SimpleTranslate 2.0 支持在你配置的翻译模型/API 能力范围内进行任意语言互转。**  
它不局限于英文 -> 中文。你可以用于英文、中文、日文、韩文、混合多语言文本，或任何模型支持的源语言/目标语言组合。

> 当前公开的 2.0 下载：**仅提供 Fabric 版本**。

[下载 SimpleTranslate 2.0](https://github.com/baokaixina/SimpleTranslate/releases/tag/v2.0)

### 2.0 有什么不同

SimpleTranslate 2.0 将翻译系统重构为以 Minecraft **Component JSON** 为核心。

旧式做法通常会把游戏文本压平成普通字符串，翻译后再猜颜色和位置；2.0 会把 Minecraft 文本组件交给模型，并接收翻译后的 Component JSON。这有助于保留：

- 颜色和格式；
- 换行；
- 物品 lore 结构；
- 悬浮提示结构；
- 告示牌布局；
- 书本页顺序；
- 聊天格式；
- HUD、标题和 ActionBar 文本；
- 成就名称和描述。

目标很简单：让 Minecraft 文本被翻译后，仍然像 Minecraft 原生文本。

### 主要功能

#### 聊天翻译

翻译服务器消息、RPG 菜单文本、旁白消息、任务提示和带格式的聊天内容。

聊天悬浮文本由专门的悬浮翻译路径处理，不会因为翻译可见聊天而意外翻译隐藏 hover 内容。

![聊天翻译前](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-chat-before.png)

![聊天翻译后](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-chat-after.png)

#### 物品提示翻译

翻译物品名称、属性、lore、NBT 相关文本和复杂彩色提示。

适合 RPG 服务器、自定义物品、冒险地图和包含大量物品描述的整合包。

#### 告示牌翻译

支持翻译单个告示牌或一组告示牌。适合冒险地图、剧情墙、服务器规则牌、谜题提示和地图说明。

![告示牌翻译前](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-sign-before.png)

![告示牌翻译后](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-sign-after.png)

#### 书本翻译

翻译成书和书页内容，同时保持清晰的阅读顺序。

![书本翻译前](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-book-before.png)

![书本翻译后](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-book-after.png)

#### 成就翻译

翻译成就名称和描述。

![成就翻译前](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-advancement-before.png)

![成就翻译后](https://github.com/baokaixina/SimpleTranslate/releases/download/v2.0/demo-advancement-after.png)

#### 更多游戏文本

SimpleTranslate 还可以翻译：

- Boss 栏；
- 计分板；
- 标题和副标题；
- ActionBar；
- 实体名称；
- 文本展示实体；
- 物品悬浮提示；
- 书本/聊天悬浮提示；
- 模组支持的其他 Minecraft UI 文本表面。

### 任意语言互转

模组本身不会把你锁定到某一种语言对。

你可以在设置中配置目标语言，并使用任何支持目标语言的翻译提供商、API 或模型。

示例：

- 英文 -> 中文；
- 中文 -> 英文；
- 日文 -> 中文；
- 韩文 -> 中文；
- 混合多语言文本 -> 中文；
- 任意支持的源语言 -> 任意支持的目标语言。

翻译质量取决于你配置的模型/API。

### 缓存、Token 统计和显示原文

SimpleTranslate 提供适合长期游玩的工具：

- 持久化翻译缓存；
- 共享缓存支持；
- Token 和请求统计；
- 可配置的翻译提供商；
- 按住临时显示原文。

按住显示原文适合核对源文本、对比译文或截图展示。

### 支持的 Fabric 版本

SimpleTranslate 2.0 当前提供以下 Fabric 版本：

- 1.19.2、1.19.3、1.19.4
- 1.20、1.20.1、1.20.2、1.20.3、1.20.4、1.20.5、1.20.6
- 1.21、1.21.1、1.21.2、1.21.3、1.21.4、1.21.5、1.21.6、1.21.7、1.21.8、1.21.9、1.21.10、1.21.11
- 26.1、26.1.1、26.1.2、26.2

请从 [2.0 发布页](https://github.com/baokaixina/SimpleTranslate/releases/tag/v2.0) 下载与你 Minecraft 版本匹配的 jar。

### 需求

- Minecraft Java Edition
- Fabric Loader
- Fabric API
- 客户端安装
- Mod Menu 可选，但推荐安装，方便打开设置
- 已配置的翻译提供商/API/模型

### 说明

- SimpleTranslate 是客户端辅助模组，服务器无需安装。
- 翻译速度、质量和 Token 消耗取决于你配置的提供商、模型和网络环境。
- 如果希望结构保留效果更好，建议使用能稳定遵循 JSON/component 指令的模型。

### 链接

- [GitHub Releases](https://github.com/baokaixina/SimpleTranslate/releases)
- [MC百科页面](https://www.mcmod.cn/class/23154.html)

---

## English

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


