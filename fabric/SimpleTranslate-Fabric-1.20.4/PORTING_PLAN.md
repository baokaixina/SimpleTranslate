# SimpleTranslate 多版本同步与移植计划

## 基线

唯一产品基线：`fabric/SimpleTranslate-Fabric-1.20.1`。

当前审计事实：

- Minecraft 1.20.1，Mojmap，Java 17
- Fabric Loader 0.16.14，Fabric API 0.83.0+1.20.1
- 128 个 Java 文件，23 个 GUI 类，16 个启用的客户端 Mixin
- 中英文语言文件各 406 个键且键集合一致
- 所有游戏文本统一使用 Minecraft Component JSON 数组请求/响应；不再存在编号 wire、标签恢复、样式猜位或纯文本降级
- 普通 Component JSON 请求会先剥离隐藏 `hoverEvent` 内容，返回后再原样贴回原组件；聊天悬浮/物品悬浮文本只由专门的 hover/tooltip 路径翻译，不能作为可见聊天/HUD 翻译的副作用提前翻译
- 自动聊天只保留 Component JSON collect-window 调度；旧 PendingEntry 聊天批队列、逻辑块检测器和纯文本 mapping wrapper 已删除
- `stx2` 仅保留为持久化命名空间；新缓存格式为 `component_json_v1`，旧 `json.<surface>` 缓存按命中惰性迁移
- 正式翻译和模型诊断均使用 `HttpClient.sendAsync`；请求队列跟踪真实 HTTP future，保留去重、优先级、分 lane、取消与重试语义
- `TitleOverlayMixin` 当前 68 行；动态 actionbar 数值模板和技术性 HUD 判断由功能辅助类负责
- 经典分区式设置主页、可滚动即时保存子页面、固定返回栏
- 客户端与服务端入口，支持可选共享缓存
- OCR 功能及其源码、配置、按键、缓存通道、Mixin、语言和测试代码已全部删除

源码和基线验证脚本高于本文档及本地技能；发现冲突时先校正文档和技能，再开始目标版本同步。

## 同步原则

1. 一次只处理一个目标，完成检查、构建、运行和日志审计后再进入下一个。
2. 不进行 Git 分支、暂存、提交或推送操作，除非用户在当前回合明确要求。
3. 不引入 `common/` 多模块重构；继续维护每个版本的独立工程。
4. 基线负责产品行为，目标工程只保留 Minecraft/Loader API 适配。
5. 不把旧 OCR 代码、旧 Tab Hub、体验预设或旧缓存协议当作目标版本特性保留。
6. 同步后必须比较基线与目标的产品文件清单；目标独有的业务 Java、Mixin、资源、语言/配置入口和脚本，除非能说明是版本或 Loader 适配，否则一律删除。
7. 不以“能够编译”作为保留旧代码的理由；旧类即使未注册，也可能通过反射、Mixin 配置、资源或缓存继续生效。
8. 用户运行时缓存不得无差别删除。缓存语义变化时优先通过协议、键签名或质量校验自动失效；专用测试客户端可在备份后清空。
9. 所有游戏文本表面和 `manager.raw` 都必须经同一个 Component JSON 数组管线；不得恢复旧编号文档、标签恢复、样式投影或纯文本翻译路径。
10. JSON 响应只校验顶层数组数量与每项能否解析成 Minecraft `Component`。不添加结构锁，也不在客户端猜测或修复模型返回的颜色、层级、事件和文本结构。
11. JSON 非法、数量不符、组件解析失败或请求失败时保留原文并进入正常冷却，不允许回退旧协议。聊天块、告示牌组和书页仍按本地位置原子映射，不得错位或发布半块结果。
12. 新缓存必须使用原始 surface 和 `component_json_v1` 格式标识。允许旧 `json.<surface>` 缓存惰性迁移；旧 wire/样式缓存保持未激活状态，不批量删除用户文件。

基线行为包括翻译/恢复、缓存、配置、经典设置 GUI、语言资源、聊天、提示框、书本、告示牌、HUD、标题、BossBar、计分板、成就、实体名、文本显示、按住显示原文、术语、黑名单、缓存导入导出、提示框发光、请求调度和共享缓存。

目标适配包括 Gradle、依赖版本、Java 版本、Loader 元数据、入口、事件、按键注册、网络 API、兼容包装和全部 Mixin 描述符。

## 目标顺序

基线之外共有 41 个目标：32 个现有工程和 9 个待创建 NeoForge 工程。

1. Fabric 新版本：1.20.4、1.20.5、1.20.6、1.21、1.21.1、1.21.2、1.21.3、1.21.4、1.21.5、1.21.6、1.21.7、1.21.8、1.21.9、1.21.10、1.21.11。
2. Fabric 回填：1.20、1.20.2、1.20.3、1.19.4、1.19.3、1.19.2。
3. Forge：1.20.1、1.19.2。
4. 现有 NeoForge：1.20.1、1.20.4、1.20.6、1.21.1、1.21.4、1.21.5、1.21.8、1.21.10、1.21.11。
5. NeoForge 回填：1.20.2、1.20.3、1.20.5、1.21、1.21.2、1.21.3、1.21.6、1.21.7、1.21.9。

用户要求确认节点时必须停在该目标完成后，不自动进入下一版本。

## API 断层

- 1.19.2/1.19.3：无 Text Display 实体，GUI/提示框接口较旧。
- 1.20.5+：Java 21、数据组件和网络 Payload API 变化。
- 1.21+：成就 Holder、物品提示参数和书本数据组件变化。
- 1.21.5+：EntityRenderState 与 Text Display 渲染链路重构。
- 1.21.8+：GUI 类延迟加载，必须入世验证；鼠标/键盘事件签名继续变化。
- Forge/NeoForge：入口、事件总线、配置路径、按键和共享缓存网络注册必须按 Loader 重写，服务端入口不得链接客户端类。

## 每个目标的实施流程

1. 记录 Minecraft、Loader、Java、依赖、Mixin 和测试客户端绑定。
2. 清理目标中的 OCR、已从基线删除的旧业务源码/资源/Mixin/脚本，以及旧生成字节码。
3. 同步基线业务源码、GUI、语言、资源、逻辑检查和翻译夹具。
4. 恢复并校正目标版本的 Loader/API 适配，并逐项说明目标独有文件的必要性。
5. 对照目标 Minecraft 源码逐个审计启用 Mixin。
6. 运行逻辑检查、翻译夹具和干净构建。
7. 部署非 sources JAR，最小化后台入世，检查日志和崩溃报告。
8. 比较基线/目标产品文件清单，确认无未说明的额外业务文件，并扫描成品 JAR 中是否残留已删除类。
9. 扫描源码、资源、脚本和成品 JAR，OCR 命中必须为零。
10. 记录构建产物、验证结果、已清理内容和剩余风险，等待下一目标授权。

## 验证命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-logic-checks.ps1 -ProjectDir .
powershell -ExecutionPolicy Bypass -File .\scripts\run-translation-fixtures.ps1 -ProjectDir .
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-21"
.\gradlew.bat clean build --no-daemon -x test
powershell -ExecutionPolicy Bypass -File .\scripts\run-client-check.ps1 -ProjectDir . -EnterWorld -WindowStyle Minimized
```

脚本不能替代真实 API 质量、LAN 多客户端共享缓存和全部视觉交互验收；这些风险必须单独报告。
