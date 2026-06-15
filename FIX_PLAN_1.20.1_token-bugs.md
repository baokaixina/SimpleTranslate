# 修复计划：1.20.1 Fabric token 消耗类 bug

**日期**: 2026-06-10
**目标版本**: `fabric/SimpleTranslate-Fabric-1.20.1`（修复验证后再按 multiversion-support 流程移植到其他版本）
**审计来源**: audit-performance-thread-safety 技能审计报告（见对话记录）

---

## 修复 1：失败请求无限重试 → 指数退避（CRITICAL）

**问题**: `DirectFormattedTranslationPipeline.requestAsync` 失败后固定 6 秒冷却
（`FAILURE_RETRY_MS=6000`），渲染路径每 6 秒重新发起付费请求，无失败上限、无负缓存。

**改动文件**: `util/TranslationLane.java`

- 把 `failureUntil: Map<String, Long>` 改为 `Map<String, FailureState>`，
  `FailureState { int failures; long retryAt; }`。
- `fail(task, baseDelayMs)` 内部计算递增延迟：`delay = baseDelayMs << (failures-1)`，
  上限 `MAX_BACKOFF_MS = 600_000`（10 分钟）。
  即 6s → 12s → 24s → … → 10min 封顶。
- `finish()` 同时清除失败状态（成功即复位）。
- 内存保护：失败表超过 8192 条时整表清空（同时修复原 `failureUntil` 无限增长问题）。
- 失败状态在 `clear()`（语言/设置变更、`resetTranslationRuntime`）时复位，行为不变。

**调用方不改**：`DirectFormattedTranslationPipeline` 等仍传 6000ms 作为基础延迟。

## 修复 2：部分映射结果缓存死循环（HIGH）

**问题**: `TranslationManager.translateMapping` 写缓存只要求结果非空（:86），
但 `sanitizeMappingResult` 可能丢弃部分 slot；读取时 `matchesMapping` 要求
slot 数完全一致 → 缓存被删 → 重新付费请求 → 又缓存部分结果 → 死循环。

**改动文件**: `translation/TranslationManager.java`

- 写缓存条件增加 `matchesMapping(mapping, result)`：只缓存完整、可通过读取校验的结果。
- 部分结果仍返回给调用方一次性使用，但不再入缓存。

## 修复 3：计分板/Boss栏动态数字缓存碎片化（HIGH）

**问题**: 计分板行（如 `Time: 12:34`、`Kills: 17`）数值每变一次生成全新缓存键
→ 全新 LLM 请求。actionbar 已有 `@@N@@` 模板机制（TitleOverlayMixin），
但 scoreboard / bossbar 没有。

**改动文件**:
- 新增 `util/NumberTemplate.java`：
  - `capture(Component)`：用 `ComponentSegmentHelper.extractSegments` 遍历段落，
    把数字 token（`\d+(?:[.,:]\d+)*%?`）替换为 `@@0@@`、`@@1@@`…，
    返回归一化 Component + 数值列表（保留每段 Style）。
  - `restore(Component translated)`：把翻译结果中的 `@@i@@` 替换回当前实际数值。
  - 同时提供 String 版本 `captureText / restoreText`。
- `util/ScoreboardTranslationHelper.java`：`translateComponent` / `translateString`
  改为「先 capture → 用归一化文本查缓存/请求 → 命中后 restore 实际数值」。
  仅当翻译成功（`translated=true`）时 restore，否则返回原始组件（带真实数字）。
- `mixin/BossHealthOverlayMixin.java`：`simple_translate$translateComponent` /
  `translateString` 同样包一层 NumberTemplate。
- `translation/DeepSeekTranslationService.buildSystemPrompt`：在通用段落追加一句，
  声明 `@@<number>@@` 为客户端冻结的数字占位符，必须原样保留且各出现一次。

**效果**: `Time: 12:34` → 模板 `Time: @@0@@` 只翻译/缓存一次，之后纯本地替换。

## 修复 4：空翻译术语污染 system prompt（MEDIUM）

**问题**: 自动检测术语以空翻译 `""` 入库；`collectTermHints` 不过滤空值，
prompt 中出现 `- "Term" -> ""`，误导模型输出空串（→ 不缓存 → 触发修复 1 的重试）。

**改动文件**: `translation/TranslationManager.java`

- 两个 `collectTermHints` 重载均增加 `!entry.getValue().isBlank()` 过滤。

## 修复 5：队列匿名 key 竞态（线程安全）

**问题**: `TranslationRequestQueue.normalizeKey` 在持锁前执行 `++nextSequence`，
两个匿名请求可能拿到同一 key 被错误合并（拿到对方的翻译结果）。

**改动文件**: `translation/TranslationRequestQueue.java`

- 匿名 key 改用独立的 `AtomicLong ANONYMOUS_SEQUENCE`，不再触碰受锁保护的 `nextSequence`。

---

## 本次不做（记录为后续工作）

- 物品 tooltip 上下文哈希碎片化（需重新设计 tooltip 上下文键，影响面大）。
- 按 surface 裁剪巨型 system prompt（设计级改动，需回归测试翻译质量）。
- `estimateMaxTokens` 字符≈token 估算（与 thinking 模式交互，需单独验证）。
- 渲染路径每帧 Template+SHA-256 的 memo 优化（性能类，与 token 无关）。
- 其他版本（1.19.x–1.21.x）移植：1.20.1 验证后按 multiversion-support 技能执行。

## 验证

1. `./gradlew compileJava` 编译通过。
2. 代码走查：失败路径退避序列、缓存写入条件、占位符 capture/restore 往返一致性。
3. （建议人工）进入带动态计分板的服务器观察日志：同一行数值变化不再产生新请求；
   `Translation queue enqueued` 频率显著下降；失败重试间隔递增。
