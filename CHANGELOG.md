# Changelog

## [2.1.29] - 2026-08-13

Compatibility hotfix for Minecraft versions below 1.20.1, plus tighter per-build game-version bounds.

针对 1.20.1 以下版本的兼容性热修，并收紧各构建的游戏版本区间。

### Fixed / 修复

- Fabric 1.16.5–1.19.2 now depend on Fabric API as mod id `fabric`, matching the id those API jars actually declare.
- Fabric 1.16.5–1.19.2 的 Fabric API 依赖改为模组 id `fabric`，与这些 API jar 实际声明的 id 一致。
- Fabric 1.16.5 and 1.18.2 no longer crash on HUD inject: title/subtitle use `Font.drawShadow` (2 calls) and the actionbar uses `Font.draw` (1 call).
- Fabric 1.16.5 / 1.18.2 不再因 HUD 注入崩溃：标题/副标题走 `Font.drawShadow`（2 次），actionbar 走 `Font.draw`（1 次）。
- Fabric 1.16.5 no longer references the missing `ClientTextTooltipAccessor`, and sign text scaling hooks the second `PoseStack.scale` instead of a method that does not exist on 1.16.5.
- Fabric 1.16.5 不再引用不存在的 `ClientTextTooltipAccessor`，告示牌缩放改为钩在第二次 `PoseStack.scale` 上。
- Removed the `space` font provider from 1.16.5 / 1.18.2 CJK fallback (that provider exists only from 1.19.2).
- 从 1.16.5 / 1.18.2 的 CJK 回退字体中移除 `space` provider（该类型从 1.19.2 才存在）。
- Each Fabric/Forge/NeoForge build now declares an exclusive Minecraft version range instead of an open `>=` floor.
- 各 Fabric/Forge/NeoForge 构建改为声明互斥的 Minecraft 版本区间，不再使用开放的 `>=` 下限。
- Forge 1.12.2 now sets `acceptedMinecraftVersions = [1.12.2]`.
- Forge 1.12.2 现在声明 `acceptedMinecraftVersions = [1.12.2]`。

## [2.1.28] - 2026-08-01

This is the cumulative user-facing changelog from the officially released 2.1.2 binaries to 2.1.28. It is not a reconstruction of unpublished intermediate version history.

这是从正式发布的 2.1.2 二进制到 2.1.28 的累计用户可见更新，不代表未公开中间版本的逐版历史。

### Added / 新增

- Added release support for Forge and NeoForge, and expanded Fabric support with Legacy targets for Minecraft 1.12.2, 1.16.5, and 1.18.2.
- 新增 Forge 与 NeoForge 发布支持，并为 Fabric 增加 Minecraft 1.12.2、1.16.5 和 1.18.2 Legacy 目标。
- Added whole-screen GUI translation with shortcut and automatic modes, including Component-driven interfaces from mods such as Patchouli, FTB Quests, and Distant Horizons.
- 新增整屏 GUI 快捷键/自动翻译，并支持 Patchouli、FTB Quests、Distant Horizons 等使用 Minecraft Component 的模组界面。
- Added the complete Wynncraft feature set on Minecraft `>=1.21.4` targets: dialogue and actionbar semantic projection, render plans, layout/glyph overlays, smart dialogue context, and Wynntils HUD integration. Detection is based on exact fonts, glyph anchors, and layout grammar rather than server address.
- 为 Minecraft `>=1.21.4` 目标加入完整 Wynncraft 功能：对话与 Actionbar 语义投影、渲染计划、布局/字形覆盖、智能对话上下文和 Wynntils HUD 集成；识别依据是字体、字形锚点和布局结构，而非服务器地址。
- Added opt-in historical translation context by text source, plus global/server/world reference-prompt profiles.
- 新增按文本来源选择的历史译文上下文，以及全局、服务器和世界范围的参考提示词配置档。
- Added a unified keyboard/mouse chord editor for translation actions and hold-to-show-original controls.
- 新增统一键盘/鼠标组合键编辑器，用于翻译操作和“按住显示原文”。

### Improved / 改进

- Strengthened Component semantic projection and rebinding for dynamic numbers, private-use/resource-pack glyphs, styles, layout, and click/hover-event ownership.
- 强化 Component 语义投影与重绑定，更稳定地保留动态数字、私用区/资源包字形、样式、布局和点击/悬浮事件归属。
- Item tooltips now rebuild translated semantics against the current frame's original icons, styles, progress values, and spacing. Existing persistent item-tooltip cache entries can be used on the first render.
- 物品 Tooltip 现在会基于当前帧的原始图标、样式、进度值和间距重建译文；已有持久化物品 Tooltip 缓存可在首次渲染时直接命中。
- Improved whole-HUD-frame handling for scoreboards and overlays, and cumulative capture of newly visible rows in scrolling GUIs.
- 改进计分板和覆盖层的整帧 HUD 处理，并可在滚动 GUI 中累计捕获新出现的行。
- Improved compatibility with FTB Quests and the Iceberg tooltip gather path used by Legendary Tooltips.
- 改进 FTB Quests，以及 Legendary Tooltips 所使用的 Iceberg Tooltip 收集路径兼容性。
- Added on-demand CJK fallback while preserving custom-font and private-use glyph ownership.
- 增强按需 CJK 字形回退，同时保留自定义字体与私用区字形归属。
- Reorganized settings into focused pages for GUI translation, display compatibility, shortcuts, text context, reference profiles, and translation speed.
- 将设置重组为 GUI 翻译、显示兼容、快捷键、文本上下文、参考配置档和翻译速度等独立页面。

### Fixed / 修复

- AUTO chat classification now normalizes full-width Latin text before detection.
- AUTO 聊天分类现在会在检测前归一化全角 Latin 文本。
- System-menu lines whose content after a colon contains only symbols are preserved as one complete original line.
- 冒号后仅包含符号的系统菜单行会完整保留为一条原文。
- Restricted player-prefix recognition to plausible prefixes while retaining the existing skips for short coordination messages such as `gg`, `lfg`, and `r1`.
- 限制玩家前缀识别范围，同时继续跳过 `gg`、`lfg`、`r1` 等简短协作消息。
- Improved stability for resource-pack icons and live progress values during translated rendering.
- 提升资源包图标和动态进度值在译文渲染时的稳定性。

### Compatibility and migration / 兼容与迁移

- Only the Forge 1.12.2 build requires [MixinBooter 9.4 or newer](https://github.com/CleanroomMC/MixinBooter) at runtime. SimpleTranslate does not bundle it; no other target requires MixinBooter.
- 仅 Forge 1.12.2 构建需要在运行时安装 [MixinBooter 9.4 或更高版本](https://github.com/CleanroomMC/MixinBooter)。SimpleTranslate 不打包该依赖，其他目标不需要 MixinBooter。
- The persistence namespace remains `stx2`; new entries continue to use `component_json_v1`.
- 持久化命名空间继续为 `stx2`，新条目继续使用 `component_json_v1`。
- Compatible legacy `json.<surface>` Component cache entries may migrate lazily. Older wire/style generations remain inactive and are not broadly deleted.
- 兼容的旧 `json.<surface>` Component 缓存可惰性迁移；更旧的 wire/style 代际保持停用，也不会被广泛删除。
- Legacy single-key bindings migrate to chord settings. Known language defaults migrate from `en` to `auto` and from `zh` to `zh_cn`.
- 旧单键设置会迁移为组合键；已知语言默认值会从 `en` 迁移到 `auto`、从 `zh` 迁移到 `zh_cn`。
- User caches are not unconditionally cleared during upgrade; invalid or structurally incompatible individual entries may still be discarded.
- 升级不会无条件清空用户缓存；无效或结构不兼容的单条记录仍可能被正常淘汰。

### Notes / 说明

- The outgoing Ctrl+Enter language-direction and failed-send safeguards were already part of the official 2.1.2 Fabric release and are intentionally not presented as new in 2.1.28.
- 发送前 Ctrl+Enter 语言方向和失败时禁止误发原文已属于正式 2.1.2 Fabric 发布，因此没有重复列为 2.1.28 新功能。
- Component JSON, the `stx2` namespace, and cache sharing predate this cumulative release and are not described as newly introduced features.
- Component JSON、`stx2` 命名空间和缓存共享早已存在，不作为本次新功能宣传。
- Wynncraft, Wynntils, Patchouli, FTB Quests, Distant Horizons, Iceberg, and Legendary Tooltips are third-party projects or services. Compatibility descriptions and screenshots do not imply affiliation, endorsement, sponsorship, or official cooperation.
- Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图不代表隶属、认可、赞助或官方合作。
