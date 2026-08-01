# SimpleTranslate 2.1.28

SimpleTranslate 2.1.28 is a cumulative release built on the officially published 2.1.2 binaries. It expands loader and Minecraft-version coverage while adding whole-screen GUI translation, dedicated Wynncraft rendering, translation context profiles, configurable keyboard/mouse chords, and substantial rendering compatibility improvements.

SimpleTranslate 2.1.28 是基于正式 2.1.2 二进制的累计更新，扩展了加载器和 Minecraft 版本覆盖，并加入整屏 GUI 翻译、Wynncraft 专用渲染、翻译上下文配置档、键盘/鼠标组合键，以及大量渲染兼容改进。

## Highlights / 重点变化

- Translate the current GUI with a shortcut (`K` by default) or automatic mode, including Component-driven interfaces such as Patchouli, FTB Quests, and Distant Horizons.
- 可用快捷键（默认 `K`）或自动模式翻译当前 GUI，包括 Patchouli、FTB Quests、Distant Horizons 等 Component 驱动的模组界面。
- Minecraft `>=1.21.4` targets include dedicated Wynncraft dialogue/actionbar semantic projection, layout/glyph overlays, smart context, and Wynntils HUD integration.
- Minecraft `>=1.21.4` 目标包含 Wynncraft 对话/Actionbar 语义投影、布局/字形覆盖、智能上下文和 Wynntils HUD 集成。
- Choose which historical translations may be supplied to the API, and save reference prompts per global, server, or single-player-world scope.
- 可选择哪些历史译文能够提供给 API，并按全局、服务器或单人世界保存参考提示词。
- Configure keyboard and mouse chords for GUI, Tooltip, chat, signs, the global toggle, and hold-to-show-original controls.
- 可为 GUI、Tooltip、聊天、告示牌、全局开关和“按住显示原文”配置键盘/鼠标组合键。
- Improved Component semantic rebinding, CJK fallback, scrolling GUI capture, whole-HUD frames, FTB Quests, and the Iceberg path used by Legendary Tooltips.
- 改进 Component 语义重绑定、CJK 回退、滚动 GUI 捕获、整帧 HUD、FTB Quests，以及 Legendary Tooltips 使用的 Iceberg 路径。
- Item tooltips rebind translations to current-frame icons, styles, progress values, and spacing; existing persistent item-tooltip entries can be used on the first render.
- 物品 Tooltip 会把译文重绑定到当前帧图标、样式、进度值和间距；已有持久缓存可在物品 Tooltip 首次渲染时命中。

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

- Persistence remains under `stx2`, and new cache entries remain `component_json_v1`.
- Compatible old `json.<surface>` Component entries may migrate lazily; older wire/style generations stay inactive.
- Legacy single-key bindings migrate to chords. Language migration is limited to the known `en -> auto` and `zh -> zh_cn` defaults.
- Upgrading does not unconditionally delete user caches.

See [`CHANGELOG.md`](../../../CHANGELOG.md) for the full cumulative list.

The outgoing Ctrl+Enter direction and failed-send safeguards were already included in the official 2.1.2 Fabric release and are not presented as new here. Component JSON, `stx2`, and cache sharing also predate this release.

Wynncraft, Wynntils, Patchouli, FTB Quests, Distant Horizons, Iceberg, and Legendary Tooltips are third-party projects or services. Compatibility descriptions and screenshots demonstrate SimpleTranslate only and do not imply affiliation, endorsement, sponsorship, or official cooperation.

Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图仅用于展示 SimpleTranslate，不代表隶属、认可、赞助或官方合作。
