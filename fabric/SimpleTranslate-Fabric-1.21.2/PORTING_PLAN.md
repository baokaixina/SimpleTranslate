# SimpleTranslate 多版本 / 多 Loader 移植计划

基线：**Fabric 1.20.4**（当前源码）。
目标矩阵：
- **Fabric**：1.20.1, 1.20.4, 1.20.6, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11
- **NeoForge**：同上（9 个版本）
- **Forge**：仅 1.20.1

本文档仅用作后续移植的**检查清单**与**避坑指南**，不动现有代码。

---

## 0. 分支 / 目录策略

建议按 **每个 MC 版本一个分支 / 独立源码树**，Loader 差异用 Gradle 子模块或内部 `common/` + `fabric/` + `neoforge/` 层拆分。理由：
- 不同 MC 版本的 Mojang mapping 不兼容，`@Shadow` 字段名会对不上
- Mixin target descriptor（方法签名）逐版本漂移
- 勉强用一套源码 + `#if` 预处理器（Architectury / Preprocessor）前期能省事，但碰到 EntityRenderState、TextDisplay.CachedInfo 这类结构级改动后维护成本更高，除非团队已经习惯

**推荐方案**：`common/` 放纯业务逻辑（`translation/`、`cache/`、`config/`、`util/` 中不依赖 MC 类的部分、`HoldOriginalFeature`、`HoldOriginalState` 的骨架），Loader 层实现按键轮询注册入口 + 每个 MC 版本各自维护 `mixin/` 目录。

---

## 1. 通用注意事项（所有版本 / 所有 Loader 都要检查）

### 1.1 Java 版本
- **1.20.1 – 1.20.4**：Java 17
- **1.20.5+（含 1.20.6、1.21.x 全系列）**：Java 21

`build.gradle` 的 `sourceCompatibility` / `targetCompatibility` / `toolchain` 必须匹配，否则 Mixin 运行期可能抛 `UnsupportedClassVersionError`。

### 1.2 Mojang mapping
- 本项目当前用 Yarn（Fabric 默认）。跨 Loader 时很可能要切 **Mojmap（官方映射）**，因为 NeoForge/Forge 用 Mojmap。
- 迁移时把所有 Mixin 的 `@At(target = "L...;...")` 字符串从 intermediary 格式改写为 mojmap 名称。Loom 有 `loom.mappings = ...` 切换开关。

### 1.3 Mixin 稳定性检查（每移植一个版本都要做）
对每个 Mixin 文件用 `javap -p -v` 或 IDE 反编译目标类，逐项核对：
1. 目标类路径（包/类名是否被重构/重命名）
2. `@Shadow` 字段名与类型
3. `@Inject` / `@Redirect` / `@ModifyVariable` 的方法 descriptor
4. lambda 编号（`lambda$xxx$N`）——这是最脆弱的部分
5. 内部类 `$Inner` 是否还存在

### 1.4 按键轮询（HoldOriginalState）
- `com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, keyCode)` 自 1.19 起稳定存在，**各版本直接可用**。
- `mc.getWindow().getWindow()` 在 1.20 – 1.21 系列都保留；若 1.21.10+ 改名需核实。
- **Loader 事件桥接**（见第 3 节）是唯一要改的点。

### 1.5 i18n key 命名空间
`screen.simple_translate.hold_original.*` 已经固定，两套 lang 文件直接复用；后续新增 Feature 请保持同前缀。

---

## 2. 不同 MC 版本的 API 差异清单（按已知风险排序）

### 2.1 高风险 —— 几乎一定要改

| 版本 | 断裂点 | 涉及 Mixin |
|---|---|---|
| **1.20.1** | 全部使用 `PoseStack` 而非 `GuiGraphics`；`Font.drawShadow(PoseStack, ...)` 等；`drawString(PoseStack, Font, Component, ...)` | `BossHealthOverlayMixin`, `ScoreboardMixin`, `TitleOverlayMixin`（间接渲染路径）、`ChatComponentMixin`（部分） |
| **1.20.6** | `ItemStack.getTooltipLines` 方法改名为 `getTooltipLines(Player, TooltipFlag)` 已存在但开始有 `Item.TooltipContext` 替换路径 | `ItemStackMixin` |
| **1.21.0+** | `Advancement` → `AdvancementHolder` 重构；`AdvancementWidget` 构造改变；`AdvancementToast` 重签 | `AdvancementToastMixin`, `AdvancementTabMixin`, `AdvancementWidgetMixin` |
| **1.21.1+** | `ItemStack.getTooltipLines(Item.TooltipContext, Player, TooltipFlag)` 三参版本；原签名已删 | `ItemStackMixin`（必须改 target） |
| **1.21.5+** | **EntityRenderState 大重构**：`EntityRenderer.render(Entity, ...)` → `EntityRenderer.render(S extends EntityRenderState, ...)`；名牌路径改为 `EntityRenderer.extractRenderState` / `EntityRenderer.renderNameTag(S state, Component displayName, PoseStack, MultiBufferSource, int)` | `EntityRendererMixin` |
| **1.21.5+** | `Display.TextDisplay.cacheDisplay` / `TextRenderState` / `LineSplitter` 均在 entity render state 体系下；内部类/字段可能迁移到 `TextDisplayRenderState` | `TextDisplayMixin` |
| **1.21.8+** | `Gui.displayScoreboardSidebar` 的 lambda 序号变化（这也是当前 1.20.4 构建中已看到的 `lambda$displayScoreboardSidebar$4 does not exists` 警告根因） | `ScoreboardMixin` |

### 2.2 中等风险

| 版本 | 断裂点 | 涉及 Mixin |
|---|---|---|
| 1.20.6 → 1.21 | `SignBlockEntity` / `SignText` 结构微调；`BlockEntityRenderer` 签名在 1.21.5 后与 RenderState 对齐 | `SignTextMixin` |
| 1.20.6 → 1.21 | `BookViewScreen.renderPage` / `PageButton` 小改 | `BookViewScreenMixin`, `BookEditScreenMixin` |
| 1.21.x | `ChatComponent.addMessage` 有 3 参 / 4 参重载漂移；`GuiMessage` record 字段增删（`tag` 字段的 `GuiMessageTag` 类型稳定，但不同版本的构造顺序要核对） | `ChatComponentMixin` |
| 1.21.10+ | `BossHealthOverlay.render` 内部 drawString 调用次序可能重排，`@Redirect ordinal` 需要校准 | `BossHealthOverlayMixin` |

### 2.3 低风险（通常兼容）

- `InputConstants`、`Screen.keyPressed`、`Button.builder`、`CycleButton.onOffBuilder`
- `Component.translatable`、`MutableComponent.withStyle`
- `ClientTickEvents.END_CLIENT_TICK`（仅 Fabric API 层）
- `ModConfig` 的 GSON 持久化（纯业务层）

---

## 3. Loader 层移植要点

### 3.1 Fabric（基线）

无需动。新版本只需 `fabric.mod.json` 中：
- `"depends": { "minecraft": "~1.21.x", "fabric-api": "*", "java": ">=21" }`
- 入口点 `client` 保留 `SimpleTranslateMod`

### 3.2 NeoForge（1.20.6+ 与 1.21.x 全系列）

**事件桥接**：Fabric 的 `ClientTickEvents.END_CLIENT_TICK.register(HoldOriginalState::tick)` 换成：

```java
@EventBusSubscriber(modid = "simple_translate", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class HoldOriginalStateForge {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        HoldOriginalState.tick(Minecraft.getInstance());
    }
}
```

**元数据**：`META-INF/neoforge.mods.toml`，无 `fabric.mod.json`。

**Mixin 配置**：`simple_translate.mixins.json` 位置不变，但 `mods.toml` 中要声明 `mixinConfigs` 字段（NeoForge 1.20.6 开始支持自动加载 `META-INF/neoforge.mods.toml` 中的 `[[mixins]]`）。

**KeyMapping 区别**：NeoForge 的 `KeyMapping` 注册走 `RegisterKeyMappingsEvent`，不是 Fabric 的 `KeyBindingHelper`。但本项目**不使用** KeyMapping 做按住原文（直接轮询），只影响已有的 `ModKeyBindings`（设置热键、模式切换等），移植时记得改注册方式。

**Fabric API 调用**：
- `FabricLoader.getInstance().getConfigDir()` → `FMLPaths.CONFIGDIR.get()`
- `FabricLoader.getInstance().getGameDir()` → `FMLPaths.GAMEDIR.get()`

### 3.3 Forge 1.20.1

**事件总线**：
```java
@Mod.EventBusSubscriber(modid = "simple_translate", value = Dist.CLIENT)
public class HoldOriginalStateForge {
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            HoldOriginalState.tick(Minecraft.getInstance());
        }
    }
}
```

**元数据**：`META-INF/mods.toml`；`@Mod("simple_translate")` 主类。

**Java**：必须 Java 17（Forge 1.20.1 不支持 Java 21）。

**KeyMapping**：`RegisterKeyMappingsEvent` 在 `ForgeEventBus`（MOD bus）。

**ForgeConfigSpec**：可选——本项目用自家 GSON 配置不必切换，但若要集成 Forge 原生 in-game 配置 UI 则需改写 `ModConfig`。

**Mixin 依赖**：`build.gradle` 加 `implementation fg.deobf("org.spongepowered:mixin:0.8.5")` 并配置 `mixin { add sourceSets.main, 'simple_translate.refmap.json' }`。

---

## 4. 每个 Mixin 文件的版本适配矩阵

### 4.1 `ChatComponentMixin`（state-swap 核心）

| 要检查 | 1.20.1 | 1.20.6 | 1.21.1 | 1.21.5 | 1.21.8 | 1.21.10 |
|---|---|---|---|---|---|---|
| `ChatComponent.addMessage` 签名 | `(Component, MessageSignature, GuiMessageTag)` | 同 | 同 | 同 | 同 | 核对 |
| `allMessages` 字段存在 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `rescaleChat()` 方法 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GuiMessage` record 构造 | `(addedTime, content, signature, tag)` 四参稳定 | 同 | 同 | 同 | 同 | 核对 |
| `Style.isEmpty()` 等工具类 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

**风险点**：BUTTON 模式用 `processedMessages` 记 identityHashCode 防再处理，跨版本稳定；但 1.20.1 下 `GuiMessage` 字段顺序可能不同，**必须用 MC 源码确认**。

### 4.2 `TitleOverlayMixin`

| 要检查 | 每个版本必做 |
|---|---|
| `Gui.setTitle(Component)` TAIL 注入点存在 | 确认方法未被 inline |
| `Gui.setSubtitle(Component)` | 同 |
| `Gui.setOverlayMessage(Component, boolean)` | 1.21 后 animateColor 参数保留 |
| `@Shadow protected Component title/subtitle/overlayMessageString` | 1.21.5+ 可能改为 `@Nullable Component` 的方法式 getter，字段仍在 |
| `Gui.clear()` 的存在 | 1.21.x 后可能合入 `removeTabStop` 之类，需确认 |

### 4.3 `BossHealthOverlayMixin`

| 版本 | 可能影响 |
|---|---|
| 1.20.1 | `render(PoseStack, ...)` 非 `GuiGraphics`，**全 6 个 @Redirect target 都要换掉** |
| 1.20.4 – 1.21.4 | 基本稳定 |
| 1.21.5+ | `BossHealthOverlay.render` 内部渲染路径可能走新的 batch draw，`drawString` / `drawCenteredString` 调用被替换为 Font.drawInBatch，**redirect 可能失效** —— 需改用 `@WrapOperation` 或 `@ModifyArg` |

### 4.4 `ScoreboardMixin`（已知问题）

当前 1.20.4 下构建出现：
```
Cannot remap lambda$displayScoreboardSidebar$4 because it does not exists in any of the targets [net/minecraft/client/gui/Gui]
```

**根因**：`Gui.displayScoreboardSidebar` 内部 lambda 序号依赖 javac 编译次序，不同 MC 版本编号不同；此处用 `$4` 在新版本很可能漂移。

**修复方向**（跨版本通用）：
- 改用 `@WrapOperation` 直接在非 lambda 层拦截 `Objective.getDisplayName()` / `drawString`；或
- 用 Mixin `method = { "displayScoreboardSidebar", "m_169552_" }` 列出多个候选；或
- 改为 **Mixin 到 `ClientPacketListener` 更新 objective 时翻译**（数据源翻译），彻底避开渲染 lambda 问题，最稳。

### 4.5 `ItemStackMixin`

| 版本 | 方法签名 |
|---|---|
| 1.20.1 | `getTooltipLines(@Nullable Player, TooltipFlag)` |
| 1.20.4 | 同上 |
| 1.20.6 | 同上（但内部 data component 重构已开始） |
| 1.21.1+ | **`getTooltipLines(Item.TooltipContext, @Nullable Player, TooltipFlag)`**，三参，老签名删除 |
| 1.21.5+ | 核对是否进一步改 |

### 4.6 `AdvancementToastMixin` / `AdvancementTabMixin` / `AdvancementWidgetMixin`

- **1.21 前**：`Advancement` 直接作为主体
- **1.21+**：`AdvancementHolder` 包装 ID + `Advancement`；`AdvancementWidget` 构造参数从 `Advancement` 换成 `AdvancementHolder`；`DisplayInfo` 本身结构基本稳定但通过 holder 拿

### 4.7 `EntityRendererMixin`

- **1.21.5 前**：`EntityRenderer.render(T entity, float yaw, float partialTick, PoseStack, MultiBufferSource, int light)` —— 当前用 `@ModifyVariable` 替换 `Component` 能工作
- **1.21.5+**：`EntityRenderer.render(S state extends EntityRenderState, PoseStack, MultiBufferSource, int)` + 名牌走 `renderNameTag(S, Component, PoseStack, MultiBufferSource, int)` —— **重写为 modify arg on renderNameTag 的 `Component` 形参**，原 ModifyVariable 路径失效

### 4.8 `TextDisplayMixin`

- **1.21.5 前**：`Display.TextDisplay.cacheDisplay(LineSplitter)` 返回 `CachedInfo`，当前实现可 inject HEAD cancel
- **1.21.5+**：文字显示实体的渲染状态可能被抽到 `TextDisplayRenderState`，`cacheDisplay` 可能消失或改名；改为 **在 `TextDisplayRenderer.extractRenderState` 中劫持文本**

### 4.9 `SignTextMixin`

- 1.20.1 – 1.20.6 相对稳定（`SignRenderer.renderSignText` / `getRenderMessages`）
- 1.21+ 核对 `HangingSignRenderer` 是否单独走一套

### 4.10 `BookViewScreenMixin` / `BookEditScreenMixin`

- 每版本核对 `cachedPageComponents` 字段名、`rebuildPageContent` 方法存在性
- 1.21 后 `WrittenBookContent` / `WritableBookContent` 作为 data component 存在，渲染逻辑可能读 component 而非 NBT

### 4.11 `HoverTooltipMixin`

- `Screen.renderComponentHoverEffect(GuiGraphics, Style, int, int)` 1.20.4 签名
- 1.20.1：`renderComponentHoverEffect(PoseStack, Style, int, int)`
- 1.21+ 稳定

---

## 5. 按目标版本的最小移植清单

每个版本都要做的基础步骤：

1. 创建分支 `port/mc<version>-<loader>`
2. 从最接近的已完成版本 fork 源码
3. 升级 `build.gradle`：
   - Loom/NeoGradle/ForgeGradle 版本
   - `minecraft`, `yarn_mappings` 或 `official` mojmap
   - `fabric_loader` / `neoforge_version` / `forge_version`
   - `fabric_api_version` 或移除
   - Java toolchain（17 或 21）
4. 更新元数据文件（`fabric.mod.json` / `neoforge.mods.toml` / `mods.toml`）
5. 逐个 Mixin 按第 4 节矩阵核对目标字节码
6. `gradlew build` → 修复所有 mapping 报错
7. `gradlew runClient` → 进入游戏按第 7 节测试清单逐项过
8. 构建 Release JAR
9. 合并到主分支前运行构建警告扫描（特别是 `Cannot remap lambda` 类警告）

### 5.1 Fabric 1.20.1（优先级：低，API 差异大）
- **最大工作量**：`GuiGraphics` → `PoseStack` 全线改写，影响 9 个 render-gate Mixin 中至少 4 个
- **按住显示原文核心逻辑**（`HoldOriginalState` / `HoldOriginalAware` / `HoldOriginalScreen`）**无需改动**，纯业务
- HoldOriginalScreen 的 `graphics.drawCenteredString` 改成 PoseStack 的同名方法

### 5.2 Fabric 1.20.6（Java 21 切换点）
- 升 Java toolchain 到 21
- `ItemStack.getTooltipLines` 暂时保持 2 参（1.21 才 3 参）
- 其他改动小

### 5.3 Fabric 1.21.1 / 1.21.4
- `ItemStackMixin` 切 3 参 `TooltipContext` 签名
- Advancement 系列 3 个 Mixin 切 `AdvancementHolder`

### 5.4 Fabric 1.21.5（**最危险的跳跃**）
- EntityRenderState 重构：`EntityRendererMixin` 完全重写
- TextDisplay 渲染状态迁移：`TextDisplayMixin` 完全重写
- `ScoreboardMixin` lambda 序号必变
- 建议先单独跑一个 prototype 分支验证 3 个重写方向

### 5.5 Fabric 1.21.8
- 最可能改的是渲染 batch 细节（影响 BossHealthOverlay）
- 核对 Advancement API 是否继续漂移

### 5.6 Fabric 1.21.10 / 1.21.11
- 以 1.21.8 为基础升级
- 逐项跑 runClient 验证，尤其留意 chat signature / session 验证路径是否变

### 5.7 NeoForge 全系列
对每个 MC 版本同步 Fabric 完成后，应用第 3.2 节 Loader 桥接：
- 事件总线换成 `ClientTickEvent.Post`
- 元数据切 `neoforge.mods.toml`
- `FabricLoader` 调用换 FML 等价
- 测试：runClient 启动后打开 Settings GUI 能正常访问

### 5.8 Forge 1.20.1
- **基于 Fabric 1.20.1 的 PoseStack 版本**移植
- Java 17
- 事件总线换 `TickEvent.ClientTickEvent`
- `META-INF/mods.toml` + `@Mod` 主类
- Mixin 通过 `MixinConfigs` 属性在 `mods.toml` 中声明
- 特别留意：Forge 1.20.1 的 `KeyMapping` 注册时机若错会导致 `mc.options.keyMappings` 没有本模组键位；**但本功能不依赖 KeyMapping，只依赖 `InputConstants.isKeyDown`，因此风险较低**

---

## 6. 构建与发布

### 6.1 版本号约定
建议 jar 文件名：`simple_translate-<mc_version>-<loader>-<mod_version>.jar`，例：
- `simple_translate-1.21.4-fabric-1.0.0.jar`
- `simple_translate-1.20.1-forge-1.0.0.jar`

`gradle.properties`：
```
mod_version=1.0.0
minecraft_version=1.21.4
loader_version=...
```

### 6.2 CI 矩阵
若用 GitHub Actions，推荐 matrix 策略：
```yaml
strategy:
  matrix:
    include:
      - mc: "1.20.1", loader: "fabric"
      - mc: "1.20.1", loader: "forge"
      - mc: "1.20.4", loader: "fabric"
      ...
```
每个组合用独立的 `gradlew build`，产物 upload 为 artifact。

### 6.3 已知遗留警告
**`ScoreboardMixin.lambda$displayScoreboardSidebar$4`**：1.20.4 构建警告，其他版本大概率也要改写。见 4.4 节修复方向。

---

## 7. 每个版本移植后必跑的测试清单

按 plan 原验收清单，最小化为：

1. **构建**：`gradlew build` 零 ERROR（WARNING 最好也为 0，至少 lambda 警告要清）
2. **启动**：`gradlew runClient` 无 MixinApplyError
3. **基础翻译回归**：
   - API Key 填入 → 聊天收英文 → 显示译文
   - 物品 tooltip → 悬停显示译文
4. **HoldOriginal 总开关**：默认关闭，按键无效
5. **HoldOriginal 录键 GUI**：
   - 每个 Feature 能录入键位
   - ESC 取消
   - 清除按钮置回 "无"
6. **GUI 冲突验证**（关键）：
   - 打开背包，按住绑定键 → tooltip 显示原文
   - 打开聊天，按住绑定键 → 聊天消息显示原文
7. **State-swap 边界**：
   - AUTO 模式收一批消息后，按住 CHAT 键 → 全部瞬间回英文；松开 → 全部回中文
   - 触发 Title 命令 `/title @s title {"text":"Hello"}` → 按 TITLE 键切换
8. **BUTTON 模式 + Hold 组合**（冷门）：
   - 手动点按钮变成译文，再按住键 → 变回带按钮的原文；松开 → 回译文

---

## 8. 不在本文档范围内的事

- **鼠标按钮绑定**（`InputConstants.Type.MOUSE`）：所有版本都可以后续加，API 一致
- **复合快捷键（Ctrl+H）**：需扩展 `HoldOriginalState` 存储 modifier 组合
- **键位冲突检测**：当前允许两 Feature 绑同键
- **Architectury / Preprocessor 多版本合一**：如果团队维护成本可接受，后续可重构；本计划按"每版本独立分支"假设

---

## 附：常用定位命令

确定 mapping 后的类/方法名时：
```bash
# 反编译已下载的 MC jar
./gradlew genSources      # Fabric/Forge
./gradlew sourcesJar      # 通用

# 查找 Mixin target 方法签名
javap -p -s net.minecraft.client.gui.Gui | grep -i scoreboard

# 找 lambda 编号（不同 MC 版本差异来源）
javap -p net.minecraft.client.gui.Gui | grep lambda
```
