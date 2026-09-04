# SimpleTranslate 2.2.1 Short Descriptions

## Release summary / 更新摘要

### English

Fixes item-tooltip translation on NeoForge 1.21.6-1.21.11 and 26.1-26.2. NeoForge routes tooltips through its own render overload that the mod did not wrap, so the dedicated item-tooltip path never ran and never reported an error. Fabric, Forge, and NeoForge 1.20.1-1.21.5 were not affected; they carry 2.2 unchanged.

### 中文

修复 NeoForge 1.21.6–1.21.11 与 26.1–26.2 的物品 Tooltip 翻译。NeoForge 把 Tooltip 渲染改道到自己的重载，而模组没有包裹它，导致物品 Tooltip 专用路径从未执行，也不会报错。Fabric、Forge 以及 NeoForge 1.20.1–1.21.5 不受影响，内容与 2.2 相同。
