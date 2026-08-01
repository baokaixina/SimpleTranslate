# 简单翻译（SimpleTranslate）2.1.28

SimpleTranslate 是一个**客户端 Minecraft 实时翻译模组**。它通过玩家自行配置的语言模型/API 翻译聊天、物品提示、悬浮提示、书本、告示牌、HUD、实体名称、文字展示实体和其他模组 GUI。

服务器无需安装本模组。模组不限定英译中，只要所配置的模型/API 支持，就可以使用其他源语言和目标语言组合。

## 重要说明

- **本模组不内置免费翻译引擎、在线模型或 API 额度。** 用户需要自行选择兼容服务，并遵守该服务的隐私、计费和数据处理政策。
- 推荐直接在游戏内设置页配置 API 地址、密钥、模型和语言方向。当前主配置文件位于 Minecraft 实例的 `config/simple_translate/simple_translate-client.json`，不是旧介绍中出现过的历史路径。
- 不要公开配置文件、API Key、请求日志或包含凭据的截图。
- Fabric、Forge、NeoForge 文件不能混用；请下载与 Minecraft 版本和加载器完全对应的 JAR。

## 主要功能

- 翻译接收和发送的聊天消息，并提供 AUTO 与手动模式。
- 翻译物品 Tooltip、聊天/书本悬浮提示、书本页面和告示牌。
- 翻译计分板、玩家列表、Boss 栏、标题、Actionbar、实体名称和文字展示实体。
- 使用快捷键（默认 `K`）或自动模式翻译当前整屏 GUI。
- 翻译使用 Minecraft Component 的其他模组界面，例如 Patchouli 手册、FTB Quests 和 Distant Horizons。
- 在 Minecraft `>=1.21.4` 目标提供 Wynncraft 对话与 Actionbar 语义投影、专用布局/字形覆盖和 Wynntils HUD 兼容。
- 可按来源选择是否向 API 提供历史译文，并为全局、服务器或单人世界保存参考提示词。
- 支持键盘和鼠标组合键，可配置全局开关、聊天模式、GUI、Tooltip、告示牌操作和“按住显示原文”。
- 提供本地持久缓存、缓存管理、术语、黑名单和请求/Token 统计。

## 结构保留

所有游戏文本表面统一使用 Component JSON 数组管线。译文会重新绑定到当前组件树，从而保留样式、布局、点击事件、动态数字和资源包图标。普通可见文本中的隐藏悬浮内容不会被顺带发送，而由专用 Tooltip 路径处理。

物品 Tooltip 会根据当前帧实际存在的图标、样式、进度值和间距重新绑定译文；已有持久缓存可在物品 Tooltip 首次显示时命中。

## 支持版本

| 加载器 | Minecraft 版本 | 文件数 |
| --- | --- | ---: |
| Fabric | 1.12.2、1.16.5、1.18.2、1.19.2–1.19.4、1.20–1.20.6、1.21–1.21.11、26.1、26.1.1、26.1.2、26.2 | 29 |
| Forge | 1.12.2、1.16.5、1.18.2、1.19.2、1.20.1 | 5 |
| NeoForge | 1.20.1–1.20.6、1.21–1.21.11、26.1、26.1.1、26.1.2、26.2 | 22 |

Fabric 1.12.2 是功能面较窄的 Legacy 版本。部分现代 GUI、文本组件和可选模组兼容功能只存在于较新的 Minecraft 目标。Wynncraft 专用功能仅随 Minecraft `>=1.21.4` 目标提供。

## 安装

1. 安装与游戏版本对应的 Fabric、Forge 或 NeoForge。
2. 按具体下载文件的依赖说明安装前置；Fabric 版本需要匹配范围的 Fabric API。
   仅 Forge 1.12.2 文件还需要安装 [MixinBooter 9.4 或更高版本](https://github.com/CleanroomMC/MixinBooter)。SimpleTranslate 不打包该运行依赖，其他目标不需要 MixinBooter。
3. 把对应 JAR 放入客户端实例的 `mods` 目录。
4. 进入游戏设置翻译 API 和语言方向。Fabric 用户可选装 Mod Menu 作为设置入口。

## 升级兼容

- 持久化命名空间继续为 `stx2`，新缓存继续使用 `component_json_v1`。
- 兼容的旧 `json.<surface>` Component 缓存可在满足条件时惰性迁移；更旧的 wire/style 缓存保持停用。
- 旧单键快捷键会迁移为组合键；语言默认值只处理已知的 `en -> auto` 与 `zh -> zh_cn`。
- 升级不会无条件清空用户缓存。

## 第三方说明

Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图仅用于展示 SimpleTranslate，不代表这些项目与 SimpleTranslate 存在隶属、认可、赞助或官方合作。
