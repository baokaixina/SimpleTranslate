# SimpleTranslate 2.2.1

Item-tooltip translation fix for ten NeoForge builds. No other change; every other build is 2.2 with a new version number.

十个 NeoForge 构建的物品 Tooltip 翻译修复。除此之外没有其他改动，其余构建即 2.2，仅版本号更新。

## Fixed / 修复

Item tooltips are translated again on **NeoForge 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2 and 26.2**.

**NeoForge 1.21.6、1.21.7、1.21.8、1.21.9、1.21.10、1.21.11、26.1、26.1.1、26.1.2 和 26.2** 的物品 Tooltip 重新可以翻译。

NeoForge adds its own tooltip render overload that also takes the `ItemStack`, so it can fire `RenderTooltipEvent`. The deferred tooltip runnable calls that overload, and it renders inline instead of delegating to the vanilla method. SimpleTranslate only wrapped the vanilla signature — which still exists on NeoForge, so nothing failed loudly: no injection error, no log line, no crash. The dedicated item-tooltip frame was simply never opened.

NeoForge 额外添加了一个带 `ItemStack` 的 Tooltip 渲染重载以触发 `RenderTooltipEvent`。延迟渲染调用的正是该重载，且它直接渲染，不会转调原版方法。SimpleTranslate 此前只包裹了原版签名 —— 而原版方法在 NeoForge 上依然存在，所以不会有任何显式失败：没有注入错误、没有日志、不崩溃，物品 Tooltip 专用帧只是从未打开。

Whole-screen GUI translation (`K`) covers the same text through an independent screen frame, so item text still appeared translated that way. Only the dedicated hover/shortcut path was affected.

整屏 GUI 翻译（`K`）通过独立的屏幕帧覆盖同一段文本，因此物品文字用这种方式仍会显示译文；受影响的只有悬停/快捷键这条专用路径。

## Not affected / 不受影响

- Every Fabric build and every Forge build.
- NeoForge 1.20.1–1.21.5: NeoForge patches `renderTooltipInternal` in place there rather than adding an overload, so the wrapped method is the one that runs.

- 全部 Fabric 与 Forge 构建。
- NeoForge 1.20.1–1.21.5：这些版本 NeoForge 是就地修改 `renderTooltipInternal`，没有新增重载，被包裹的正是实际执行的方法。

## Upgrade / 升级

Drop-in replacement for 2.2. No configuration or cache migration.

可直接替换 2.2，无需迁移配置或缓存。

Scholar, Wynncraft, Wynntils, Patchouli, FTB Quests, Distant Horizons, Iceberg, and Legendary Tooltips are third-party projects or services. Compatibility descriptions and screenshots demonstrate SimpleTranslate only and do not imply affiliation, endorsement, sponsorship, or official cooperation.

Scholar、Wynncraft、Wynntils、Patchouli、FTB Quests、Distant Horizons、Iceberg 和 Legendary Tooltips 均为第三方项目或服务。兼容性描述与截图仅用于展示 SimpleTranslate，不代表隶属、认可、赞助或官方合作。
