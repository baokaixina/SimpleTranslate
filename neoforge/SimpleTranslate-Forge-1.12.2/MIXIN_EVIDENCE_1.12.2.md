# SimpleTranslate Forge 1.12.2 Mixin evidence

Exact build target: Forge `1.12.2-14.23.5.2860`, snapshot mappings
`20171003-1.12`, Java 8 (Temurin 8u492), Gradle 4.9 + ForgeGradle 3 +
MixinGradle 0.7.38 (offline build). Runtime client: Forge `14.23.5.2795` +
OptiFine E3 + MixinBooter `9.4` + FTBLib `5.4.7.2` + FTBQuests `1202.9.0.15`
+ ItemFilters `1.0.4.2` at `<designated-test-client>\forge\1.12.2forge`
(version `1.12.2-Forge_14.23.5.2795-OptiFine_E3`).

Target descriptors were inspected with `javap -p -s`/`javap -c` against the
exact build artifact
(`forge-1.12.2-14.23.5.2860_mapped_snapshot_20171003-1.12.jar`) and, for
Forge-added members, additionally against the exact runtime universal jar
(`forge-1.12.2-14.23.5.2795.jar`). Compiled handler descriptors were
inspected with `javap -p` on `build/classes/java/main`; SRG refmap entries
were read from `build/tmp/compileJava/compileJava-refmap.json`.

## Runtime evidence sessions (2026-07-27)

All interaction rows below were closed in live singleplayer sessions in the
world `Space Expedition to EPIC 204` (cheats on), player `STXTester`, using a
local mock Component-JSON endpoint (`http://127.0.0.1:8917/chat/completions`)
that returns a `[译]`-prefixed copy of every requested component. Evidence
artifacts:

- Console logs: `runclient-flegacy-A3-20260727-061020.out.log`,
  `runclient-flegacy-B2-20260727-063248.out.log`,
  `runclient-flegacy-B3-20260727-064116.out.log`,
  `runclient-flegacy-FINAL-20260727-065109.out.log` (project root).
- Persistent cache written by the sessions:
  `<client>\config\simple_translate-stx2.json` — surfaces recorded:
  advancement(2), advancement.widget.title(1),
  advancement.widget.description(2), book(2), bossbar(1), chat.button(1),
  chat.context.batch(3), entity_name(2), ftb_gui(19), gui.button(7),
  hover_text(1), hud_actionbar(3), hud_subtitle(1), hud_title(1),
  item_tooltip(8), player_tab(1), scoreboard(1), scoreboard.line(2),
  sign(16). (`chat.outgoing` intentionally never caches; its evidence is the
  visible resent message.)
- Final accepted jar: `simple_translate-1.12.2-forge-2.1.28.jar`, built clean
  2026-07-27 06:50:29, SHA-256
  `504D85DDF746C5DE32961AD1CC950231283251F24D98CCB6EA606AACB07A0CDE`,
  deployed hash-identical and re-verified in-world (FINAL session 06:51,
  world entry, no crash reports, cache reload confirmed).

## FTB TextField follow-up (2026-07-28)

The prior FINAL session contained an actual optional-Mixin failure that the
older summary did not call out: MixinBooter 9.4 tried to remap the JVM array
owner emitted by `String[]#clone()` and rejected
`INVOKEVIRTUAL [Ljava/lang/String;::clone()Ljava/lang/Object;` while loading
the exact `FTBLib-5.4.7.2.jar` `TextField` class. This meant the FTB text-field
compatibility path was not applied on a real installed dependency.

The exact target remains `TextField.text:[Ljava/lang/String;` and
`draw(Lcom/feed_the_beast/ftblib/lib/gui/Theme;IIII)V`, both re-checked with
`javap -p -s -c` against that test-client JAR. The handler now clones through
`java/util/Arrays.copyOf([Ljava/lang/Object;I)[Ljava/lang/Object;` plus the
compiler's checked `String[]` cast, so the transformed method contains no
array-owner invocation. The handler descriptor is unchanged:
`(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`.

Clean ForgeGradle build passed on 2026-07-28. Its deployable JAR SHA-256 is
`9FE1C9F3F56E081D47BA8420712402CA837A2FB0048199EED0C542E1CD7A4117`.
It was deployed to the designated exact Forge `14.23.5.2795` + Java 8 client
with FTBLib, FTB Quests, Item Filters, MixinBooter, and OptiFine installed.
The client reached the main menu and entered `Space Expedition to EPIC 204`
as `CodexTester` (entity id 82); no new crash report, `Mixin apply`,
`InvalidMixin`, or `FtbTextFieldMixin` failure appeared in `latest.log`.

## Fixes required by the runtime sessions

The world sessions falsified five "startup verified" assumptions; each fix
below was re-derived from exact-jar bytecode and re-verified live:

1. **HUD title/subtitle/actionbar** — `GuiIngame#renderGameOverlay(F)V` is
   overridden by `GuiIngameForge` without a super call, so the old injection
   never ran on a production Forge client. Retargeted to Forge-named
   `GuiIngameForge#renderTitle(IIF)V` + `#renderRecordOverlay(IIF)V`
   (`remap = false`; both verified in 2860 and 2795 jars); field access moved
   to new `GuiIngameAccessor` (`overlayMessage:field_73838_g`,
   `displayedTitle:field_175201_x`, `displayedSubTitle:field_175200_y`, all
   `Ljava/lang/String;` per refmap).
2. **Advancement widget crash** — `GuiAdvancement#description` is a
   fixed-size list; the restore path's `clear()/addAll()` threw
   `UnsupportedOperationException` (crash-2026-07-27_06.06.04). Restore now
   swaps per-index with `set()`.
3. **Outgoing chat crash** — Mixin rejects doubly-nested anonymous classes;
   first real send with `outgoingChatEnabled=true` crashed
   (`...GuiChatOutgoingMixin$1$1`, crash-2026-07-27_06.30.55). Handler body
   moved to plain class `chat/OutgoingChatTranslator`; commands (`/...`) are
   never translated.
4. **Book page** — `GuiScreenBook#pageGetCurrent()` is invoked only from
   edit-mode paths (2860 bytecode: `keyTypedInBook`,
   `pageInsertIntoCurrent`), so the old RETURN injection never fired for
   signed books. Replaced with a `@Redirect` of the single
   `NBTTagList#getStringTagAt(I)Ljava/lang/String;` call in
   `drawScreen(IIF)V` (refmap: `func_150307_f` / `func_73863_a`).
5. **B/K/V keys dead in GUIs + FTB mixins never registered** —
   1.12.2 `Minecraft#runTickKeyboard` only ticks KeyBindings when no screen
   is open; added a `GuiScreenEvent.KeyboardInputEvent.Post` handler for
   bookmark (B), whole-frame GUI (K), and manual tooltip (V). The optional
   FTB mixins were registered from the `IEarlyMixinLoader` (coremod stage,
   before mod jars join the LaunchClassLoader), so their config plugin probe
   always failed and `GuiWrapper` was never transformed in any earlier run;
   they now load through `SimpleTranslateLateMixinLoader`
   (`ILateMixinLoader`, split configs `simple_translate.late.ftb.mixins.json`
   and `simple_translate.late.tips.mixins.json`, `required=false`, mixins stay
   `@Pseudo` for graceful absence). FTB button
   discovery switched to duck typing (`setTitle(String)` declared only on
   `Button` subclasses in FTBLib-5.4.7.2 per `javap`), because FTB builds
   its buttons as anonymous subclasses that defeat name-suffix matching.

Also verified this session: the settings screen preserves a full-length
endpoint on save (512-char field limits; earlier truncation at 32 chars came
from a pre-fix jar) and feature toggles persist immediately.

| Surface | Exact target | Handler / field evidence | Runtime status |
| --- | --- | --- | --- |
| Item tooltip | `GuiScreen#getItemToolTip(ItemStack):List` | `(ItemStack, CallbackInfoReturnable):void` | VERIFIED 2026-07-27: creative-inventory hover shows every line `[译]`-prefixed incl. styled lore (screenshots a33/a34); cache `item_tooltip` ×8 |
| Tooltip glow | `GuiScreen#drawHoveringText(List,II)V` (vanilla, func_146283_a) + Forge-added `drawHoveringText(List,II,FontRenderer)V` (`remap=false`; javap 2860+2795) | shared `@Unique` glow painter; `fontRenderer:FontRenderer` shadow | VERIFIED: item path only reaches the Forge 4-arg overload — vanilla-only hook proven dead for items live; after fix, blue glow visible around item tooltip (b10) and chat hover tooltip (a37) |
| Hover text | `GuiScreen#handleComponentHover(ITextComponent,int,int):void` | redirect `HoverEvent#getValue():ITextComponent` | VERIFIED: chat hover shows `[译] Geheimer Hinweis`; hoverEvent survives translation of the visible line (a37/a38); cache `hover_text` |
| Chat AUTO/BUTTON | `GuiNewChat#printChatMessage(ITextComponent):void` | `(ITextComponent,CallbackInfo):void` | VERIFIED: button mode attaches `[翻译]` to every incoming line (a15/a36); AUTO mode replaces lines via deletion-ID path (`[译] <STXTester> ...`, b02); cache `chat.context.batch` ×3 |
| Chat button click | `GuiScreen#handleComponentClick(ITextComponent):boolean` | `(ITextComponent,CallbackInfoReturnable):void` | VERIFIED: clicking `[翻译]` swaps the line to the translation + `[原文]` button (a38); cache `chat.button` |
| Outgoing chat | `GuiChat#keyTyped(char,int):void`, redirect of unique `GuiChat#sendChatMessage(String):void` | redirect delegates to `OutgoingChatTranslator.send` (post-fix) | VERIFIED: sent text arrives as `[译] hello outgoing translation test` (b02); pre-fix crash reproduced and eliminated; commands pass through untranslated |
| HUD title/subtitle/actionbar | `GuiIngameForge#renderTitle(IIF)V`, `#renderRecordOverlay(IIF)V` (`remap=false`) + `GuiIngameAccessor` fields | handlers `(int,int,float,CallbackInfo):void`; SRG fields per refmap | VERIFIED: `/title` shows `[译] Grosser Titel` + `[译] Unter Titel` (a32); actionbar `[译] Aktions Leiste` (a31); bookmark overlay also translated; cache `hud_title`/`hud_subtitle`/`hud_actionbar` |
| Scoreboard | `GuiIngame#renderScoreboard(ScoreObjective,ScaledResolution):void` | title swap + exact `drawString` ordinal 0 / width ordinal 1 redirects | VERIFIED: sidebar `[译] Testziel` / `[译] Punkte` (a16); persists across sessions from stx2 cache (f01) |
| Boss bar | `GuiBossOverlay#renderBossHealth():void` | `mapBossInfos:Map<UUID,BossInfoClient>` | VERIFIED: summoned wither shows `[译] 凋灵` over the boss bar (a50); cache `bossbar` |
| Tab list (historical, superseded) | `GuiPlayerTabOverlay#getPlayerName(NetworkPlayerInfo):String` | return callback | The 2026-07-27 build translated player names. This was later removed to match the baseline safety rule; the current implementation translates only tab header/footer components, documented below. |
| Advancement toast | `AdvancementToast#draw(GuiToast,long):IToast.Visibility` | `advancement:Advancement`; `DisplayInfo` accessors | VERIFIED: granting story/mine_stone pops toast `[译] 石器时代` (a22); cache `advancement` ×2. Note: root advancements never toast (vanilla behaviour) |
| Advancement widget | `GuiAdvancement#drawHover(int,int,float,int,int):void` | `title:String`, `description:List<String>` (fixed-size; per-index restore post-fix) | VERIFIED: hover shows `[译] 获得升级` + translated description, no crash after fix (a35); pre-fix crash captured (crash-2026-07-27_06.06.04); cache `advancement.widget.*` |
| Entity name | `RenderLivingBase#renderName(EntityLivingBase,double,double,double):void` | redirect `EntityLivingBase#getDisplayName():ITextComponent` | VERIFIED: summoned pig renders name tag `[译] Rosa Schweinchen` (a20); cache `entity_name` ×2 |
| Sign | `TileEntitySignRenderer#render(TileEntitySign,double,double,double,float,int,float):void` | full target args + callback | VERIFIED: the map's own signs cached 16 `sign` entries (e.g. "Sleep Quarter" → `[译]`-wrapped) during world rendering |
| Book page | redirect `NBTTagList#getStringTagAt(I)String` inside `GuiScreenBook#drawScreen(IIF)V` (post-fix; old pageGetCurrent hook proven edit-only) | redirect handler + `bookIsUnsigned:boolean` | VERIFIED: page 1 `[译] Guten Tag liebes Buch`, page 2 `[译] Seite zwei Inhalt` after cache-invalidating page flip (b06/b07); cache `book` ×2 |
| Book bookmark | `GuiScreenBook#currPage:int`, `cachedPage:int` accessors `()I`/`(I)V` + screen-key handler (B) | accessor get/set + `setCachedPage(-1)` re-parse | VERIFIED: B toggles bookmark; "bookmark restored" actionbar overlay observed (b07) and restore re-parses the page through the translated cache; pre-fix the B key was dead in GUIs (KeyBinding tick gap) |
| Whole-frame GUI (K) | `GuiScreenEvent.DrawScreenEvent.Pre/Post` + screen-key handler (K) | `GuiTranslationController.beginFrame/endFrame/renderStatus` | VERIFIED: pause menu buttons all `[译]`-prefixed with `ST GUI: ON` status badge (b09); cache `gui.button` ×7 |
| Settings screen | `SimpleTranslateScreen` + scrollable detail pages (U key in-world) | 512-char endpoint/key fields; immediate persistence; fixed Return-to-settings bar | VERIFIED: service page exposes API detection; editing the model writes through immediately and the detail Return returns to the sectioned root rather than gameplay (09.35/09.36) |
| FTB wrapper | `GuiWrapper#func_73863_a(IIF)V` (@Pseudo, remap=false), registered via `SimpleTranslateLateMixinLoader` (post-fix) | pseudo callbacks `(int,int,float,CallbackInfo):void`; duck-typed Button discovery (`setTitle(String)` javap-verified on FTBLib-5.4.7.2 `Button`; absent on `Widget`) | VERIFIED: My Team GUI renders `[译] 设置 / 组员 / 盟友 / 管理者 / 敌人 / 退出组` (b12); team-settings config GUI headers translated (b13); cache `ftb_gui` ×19. Pre-fix the early-loader registration provably never transformed GuiWrapper |
| FTB text | `TextField#draw(Theme,int,int,int,int):void`; `text:String[]` (@Pseudo, remap=false, same late config) | pseudo callbacks `(CallbackInfo):void`, shadow `[Ljava/lang/String;` — descriptors javap-exact vs FTBLib-5.4.7.2 | PARTIAL: config now provably registers late and sibling wrapper mixin injects+executes; `TextField#draw` itself was not exercised because the test world ships no quest pack (empty FTB Quests tree has no TextField) and scripted quest creation via the chapter dialog was unreliable under automation. Static bytecode evidence retained |

Notes:

- The mock endpoint proves the full mixin → engine → HTTP → Component-JSON
  acceptance → cache → render loop per surface; it does not prove live
  provider quality (out of scope for this port evidence).
- `sendCommandFeedback` is disabled by the test map, so command feedback does
  not appear in chat logs; visual state changes and the stx2 cache are the
  authoritative session evidence.
- Interference note: this machine runs sibling automation that hunts
  Minecraft windows by title; all sessions above ran with a retitled window
  (`STX-FLEGACY-EVIDENCE`), pid-bound input, and an independent launcher
  (local PCL libraries/assets, no shared temp infrastructure).

## 2026-07-28 clean rebuild follow-up

- Rebuilt from an emptied Gradle output directory with Java 11 for
  ForgeGradle 3 and Java 8 bytecode target; `clean build --no-daemon` passed.
  Deployable JAR SHA-256:
  `F50A4BCD76D56044D0D69E79EE99C467A419ABADCA5B05F4FBB6B8302ECD9A41`.
- Deployed that JAR to the exact Forge `14.23.5.2795` + Java 8 test client.
  The client reached `Space Expedition to EPIC 204` at 07:41:42 as
  CodexTester (entity id 82), with FTB Library, FTB Quests, Item Filters,
  MixinBooter and OptiFine present. The current `latest.log` has no
  SimpleTranslate Mixin failure or crash.

## 2026-07-28 baseline-style settings and request-policy rebuild

- Replaced the superseded `SimpleTranslateConfigScreen` tab hub with the
  reachable `SimpleTranslateScreen` root menu and `BaseSimpleTranslateScreen`
  / `ScrollableSettingsScreen` implementation. `ForgeClientEvents` and
  `ForgeConfigGuiFactory` now construct only this new root; clean packaged
  output contains no `SimpleTranslateConfigScreen` class.
- The 1.12.2 settings tree now follows the baseline information architecture:
  General, Translation access, Translation surfaces, Translation guidance,
  Operation, and Advanced. It has independent scrollable pages for service,
  language, chat, books, tooltips, GUI/FTB, HUD, world text, profile, terms,
  blacklist, local context, cache, shortcuts, glow, request scheduling, and
  token usage. All controls persist immediately. The service page also sends
  a small, normal Component-JSON request to detect the saved endpoint/key/model
  availability without caching game text.
- Scope-local context now has real policy gates for chat, item/hover tooltip,
  books, signs, HUD, advancement, entity name, GUI, and FTB surfaces. Its
  policy is part of the cache identity and disabled scopes neither provide nor
  receive local examples. The new session token monitor records provider
  `usage` fields from accepted Component-JSON responses only.
- Language parity after the rebuild: `en_us.lang` and `zh_cn.lang` both have
  212 matching keys; no key exists only in one language. Residue scan over
  source/resources and the clean JAR found no old tab-hub class, OCR,
  `PendingEntry`, or `LogicalBlock` symbol.
- `clean build --no-daemon` passed with Java 11. Deployable JAR SHA-256:
  `1DAEF7513FCF10EC32425F5801B7A1DB51C32FC5A4C1485DB89260C4AE0093DD`.
  It was hash-matched into the exact Forge `14.23.5.2795` + Java 8 client.
  That client reached main-menu readiness and entered `Space Expedition to
  EPIC 204` as CodexTester (entity id 82) at 09:23:51. `latest.log` had no
  SimpleTranslate/Mixin fatal or error entry and no new crash report was
  created; the older crash reports in the instance predate this run.

## 2026-07-28 subsequent GUI safety follow-up

- The whole-frame GUI controller now translates inside the original 1.12.2
  widget rectangles: labels wider than a button are trimmed at draw time,
  while originals are still restored after the frame. Its status badge is
  localized and remains outside the captured controls. The exact client
  entered the test world and exercised an inventory screen with K enabled:
  the localized green status badge rendered and the narrow arrow controls
  remained inside their fixed boxes (screenshot `09.34.15`).
- A later service-page layout-only rebuild makes the immediate-save status
  visible above the fields. The exact-client test configuration proved that
  editing the model wrote `mock-component-jsonX` immediately and a subsequent
  backspace immediately restored `mock-component-json` in
  `simple_translate.properties`. The deployable JAR SHA-256 for this latest
  layout build is `349E5368AD9E8FD310BD1EE811754975948A491F30CF1216AD4E042C1F1AA872`.
  It reached exact-client main-menu readiness. Per user direction, no further
  automatic client/world launch is performed after this point; final manual
  acceptance remains with the user.

## 2026-07-29 build-only core synchronization follow-up

- Replaced the former target-only dot cache identity with the baseline
  `stx2:<surface>:...:fmt=component_json_v1:lang=...` shape.  A validated hit
  from either old target key or the baseline `json.<surface>` key migrates only
  that one entry; unrelated legacy records remain inactive.
- The 1.12.2 scheduler now retains the baseline behaviour needed by this
  runtime: classified request lanes, protected tooltip/hover/chat/HUD work,
  HTTP 408/425/429/5xx and I/O retries (three bounded attempts), and temporary
  global throttling after HTTP 429.  `item_tooltip` and `hover_text` aliases
  now classify into their proper lanes instead of silently falling into the
  generic queue.
- Component-cache writes are coalesced for 750 ms and saved through a sibling
  temporary file plus atomic move. Item-tooltip lines are submitted as one
  Component-JSON array and only become visible after the entire result is
  validated and cached; they are no longer independent requests that lose
  tooltip context.
- Exact Forge 1.12.2 source and bytecode were inspected for
  `FMLNetworkEvent$ClientDisconnectionFromServerEvent`
  `(Lnet/minecraft/network/NetworkManager;)V`; Forge posts it on
  `MinecraftForge.EVENT_BUS`. The client handler now flushes delayed cache
  writes, resets world-bound request/chat/book/GUI/token state, and permits a
  fresh shared-cache session on the next connection. Shared-import provenance
  is persisted, so imported current Component-JSON entries do not upload back
  after a client restart; only validated current-format local entries upload.
- Offline `clean build --no-daemon` with Java 11 passed on 2026-07-29. The
  deployable JAR SHA-256 is
  `A2E2D52204C6CD3E73DC6BFBA8807618B3965C72DADED218C7CD74F0A66384A8`.
  Static source/JAR residue checks found no `SimpleTranslateConfigScreen`,
  `PendingEntry`, `LogicalBlock`, or OCR path. Per the user's explicit
  instruction, no client was launched for this follow-up; runtime verification
  of this build remains manual/pending.

## 2026-07-29 book control and DeepSeek endpoint correction

- The legacy B-key page-position marker was not the baseline book feature and
  has been removed. `GuiScreenBookMixin` now draws the visible left-edge `T`
  bookmark used by the baseline. It starts translation for the signed book,
  turns green while translations are active, and toggles back to the original
  text on a second click. The hover labels are localized in both language
  files.
- Exact target evidence: mapped Forge 2860 `GuiScreenBook` has one
  `NBTTagList#getStringTagAt(I)String` invocation in `drawScreen(IIF)V`, and
  a cancellable `mouseClicked(III)V`. The exact test-client 2795 Minecraft
  JAR (`bmj`) also has the matching NBT list (`z:Lge;`), signed-book boolean
  (`i:Z`), page index (`y:I`), cached page (`C:I`), and one visible-page NBT
  read. Compiled handler descriptors are `(IIF, CallbackInfo)V` and
  `(III, CallbackInfo)V`; the generated refmap maps both target methods.
- The service configuration now recognizes a bare
  `https://api.deepseek.com` value as the documented DeepSeek base URL and
  persists the complete legacy POST endpoint
  `https://api.deepseek.com/chat/completions` on load or edit. Other provider
  URLs are left unchanged.
- Offline `clean build --no-daemon` with Java 11 passed. Deployable SHA-256:
  `14B140CEAD3B306116CE7601537540655472DF7AD12A6F392D042DC6BBB1E332`.
  It was hash-matched into the user-designated Forge 14.23.5.2795 test
  instance. No client was started per the user's instruction; the new visual
  control and real API request remain for manual runtime verification.

## 2026-07-29 complete baseline port, build-only handoff

This section supersedes earlier product counts and handler descriptions where
they differ. The user explicitly prohibited further client launches, so the
checks here are source, exact-artifact bytecode, compiled-handler, fixture and
clean-build checks only. Runtime acceptance of this final build remains with
the user.

### Product inventory

- Canonical baseline inspected: `fabric/SimpleTranslate-Fabric-1.21.11`, 165
  Java product files and 485 keys per language at this audit.
- Forge 1.12.2 product: 127 Java product files and 468 matching keys per
  language. Baseline roles that need several modern screen/component helpers
  are folded into legacy controllers instead of retaining dead facade classes.
- The 21 omitted language keys are exactly the Text Display surface (not
  present in Minecraft 1.12.2), its modern display/font-compatibility controls,
  and the Wynn HUD surface. Wynn is forbidden below Minecraft 1.21.4 by the
  project version rule. Four target-only language keys label the legacy integer
  buttons used by the tooltip-glow editor.
- Source/resources and compiled-product residue scans contain zero Wynn or
  Text Display symbols. Every current `ModConfig` value has a production
  consumer; every product Java class is reachable from another product class,
  Forge entry point, or Mixin configuration.
- Current mixin inventory is 25 exact vanilla/Forge client mixins plus four
  optional late mixins (three FTBLib, one Tips). Every configured mixin has both
  a source file and compiled class.

### Exact changed targets and compiled handlers

- `FontRenderer#renderString(String,float,float,int,boolean):int`
  `(Ljava/lang/String;FFIZ)I` and
  `#getStringWidth(String):int` `(Ljava/lang/String;)I` were inspected in the
  mapped Forge 2860 artifact. The exact runtime 2795+OptiFine jar class `bip`
  has matching obfuscated descriptors. Compiled `@ModifyVariable` handlers
  are both `(Ljava/lang/String;)Ljava/lang/String;`.
- `GuiPlayerTabOverlay#renderPlayerlist(int,Scoreboard,ScoreObjective):void`
  `(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V`
  has exact `header` and `footer` fields of
  `Lnet/minecraft/util/text/ITextComponent;`. Runtime class `bjq` has the
  equivalent `(ILbhk;Lbhg;)V`. Compiled HEAD/RETURN handlers are
  `(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;CallbackInfo)V`.
  Player names are deliberately left unchanged.
- `GuiChat#keyTyped(char,int):void` is `(CI)V`, with protected
  `inputField:Lnet/minecraft/client/gui/GuiTextField;`; runtime class `bkn`
  has `(CI)V` and field type `Lbje;`. The compiled handler is
  `(CILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V`, and
  the accessor returns `Lnet/minecraft/client/gui/GuiTextField;`. Only
  Ctrl+Enter / Ctrl+numpad-Enter is consumed.
- The book target remains `GuiScreenBook#drawScreen(IIF)V` with exactly one
  visible-page `NBTTagList#getStringTagAt(I)String` invocation; its compiled
  redirect is `(Lnet/minecraft/nbt/NBTTagList;I)Ljava/lang/String;`. Bookmark
  draw/click handlers remain `(IIF,CallbackInfo)V` and
  `(III,CallbackInfo)V`. The final bookmark now has baseline inactive,
  translating and translated colors, bounded offsets, translating text, and
  hold-original behavior.
- Official optional dependency inspected: CurseForge Tips file 2675002,
  `Tips-1.12.2-1.0.7.jar`, SHA-256
  `7E6275E26212654F512A987819116C71F996AB2D713DBC7A8E3EC216F57FCB87`.
  Its exact hook is static `net.darkhax.tips.TipsAPI#renderTip()V`, which makes
  one title draw and one split-body draw through `FontRenderer`. The compiled
  optional HEAD/RETURN handlers are static `(CallbackInfo)V`; the mixin is
  `@Pseudo`, `remap=false`, and registered by the late MixinBooter config.
- Tooltip glow is painted only from the Forge-added
  `drawHoveringText(List,II,FontRenderer)V` descriptor because exact 1.12.2
  bytecode shows the vanilla List overload delegates to it; retaining both
  painters would draw the glow twice. Dedicated item/component-tooltip scope
  handlers use `renderToolTip(ItemStack,II)V` and
  `handleComponentHover(ITextComponent,II)V`. Sign and entity
  changes retain exact target descriptors
  `(TileEntitySign,DDDFIF)V` and `(EntityLivingBase,DDD)V`; their compiled
  handlers match those target arguments exactly.

### Build-only logic fixtures

`validatePortLogic` starts only a loopback HTTP fixture (never Minecraft) and
checks all six provider request formats: DeepSeek Chat, OpenAI-compatible Chat,
OpenAI Responses, Anthropic Messages, Gemini generateContent, and local
Ollama. It also checks bare DeepSeek URL completion, bearer/query/x-api-key
authentication placement, top-level Component-array requests, hidden hover
payload stripping and reattachment, and rejection of a response whose array
count differs from the request. The fixture prints
`PORT_LOGIC_VALIDATION_OK` on success.

Final command `clean validatePortLogic build --offline --no-daemon` completed
all 13 tasks successfully. The reobfuscated product JAR contains 255 class
entries, uses Java 8 class-file major version 52, is 545,761 bytes, and has
SHA-256
`68F64668B9B3710ADA4E66D2E06910554C4E683697CA7E9A156AE55C9A53121E`.
The non-sources JAR was copied hash-identically to the designated exact-client
`mods` directory. No client or dedicated server was started.

## 2026-07-30 exact-target repair evidence

This section supersedes the earlier changed-target inventory. Exact artifacts:

- Designated Forge 2795 + OptiFine E3 combined client JAR SHA-256:
  `8ADA07DA5EE77DAD3527BD7278FBD05EE1FC8A597813B216A871A2D7D64CC64F`.
- Official Forge `1.12.2-14.23.5.2795` universal JAR SHA-256:
  `E6233E7A5E57182CD729E992B7098ED0CA6C2C5058781DB55BDD27BF032C2C7D`.
- Exact FTBLib `5.4.7.2` JAR SHA-256:
  `623FC574F0227FEC6CC9E26C3879A605F9B4499C0078FF085291901A5495A6AC`.
- Exact Tips `1.12.2-1.0.7` JAR SHA-256:
  `7E6275E26212654F512A987819116C71F996AB2D713DBC7A8E3EC216F57FCB87`.

The official Forge 2795 `ClassPatchManager` CLIENT patch was applied in
memory to the designated combined client's `GuiScreen` class before target
inspection. The patched class has the Forge
`drawHoveringText(Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V`
overload. `renderToolTip(ItemStack,II)V` and the vanilla List overload each
invoke it exactly once; the Forge overload invokes `GuiUtils.drawHoveringText`
exactly once. Therefore the current single glow painter on the Forge overload
is exact and avoids a double render.

| Changed Mixin | Exact target / invocation evidence | Compiled handler descriptor |
| --- | --- | --- |
| `GuiNewChatMixin` | designated combined client `bjb#a(Lhh;I)V`; mapped target `GuiNewChat#printChatMessageWithOptionalDeletion(ITextComponent,I)V`. Exact `bjb#a(Lhh;)V` invokes the lower overload once with deletion ID `0`; direct callers may invoke the lower overload with a nonzero replacement ID. Exact public `bjb#c(I)V` / mapped `GuiNewChat#deleteChatLine(I)V` removes that ID from both drawn and stored chat lists. | output `(ITextComponent,I,CallbackInfo)V`; deletion `(I,CallbackInfo)V` |
| `GuiTextFieldTranslationMixin` | `GuiTextField#drawTextBox()V`, HEAD + RETURN | both `(CallbackInfo)V` |
| `GuiScreenTooltipGlowMixin` | `renderToolTip(ItemStack,II)V`, `handleComponentHover(ITextComponent,II)V`, and Forge `drawHoveringText(List,II,FontRenderer)V` | target-argument callbacks for the two scopes; glow `(List,I,I,FontRenderer,CallbackInfo)V` |
| `GuiScreenHoverTextMixin` | `GuiScreen#handleComponentHover(ITextComponent,II)V` contains three `HoverEvent#getValue()ITextComponent` calls, one per action branch | `(HoverEvent)ITextComponent`; action is filtered inside the redirect |
| `GuiScreenBookMixin` | `GuiScreenBook#drawScreen(IIF)V` contains exactly one `NBTTagList#getStringTagAt(I)String`; `keyTypedInBook(CI)V` verified for edit snapshots | redirect `(NBTTagList,I)String`; draw/click/edit callbacks match target arguments |
| `GuiIngameHudMixin` | exact Forge 2795 `GuiIngameForge#renderTitle(IIF)V` and `#renderRecordOverlay(IIF)V`; production overlay calls each once | all four `(IIF,CallbackInfo)V` |
| `GuiIngameScoreboardMixin` | `GuiIngame#renderScoreboard(ScoreObjective,ScaledResolution)V` contains exactly two `ScorePlayerTeam#formatPlayerName(Team,String)String` calls; ordinal 0 is measurement, ordinal 1 drawing | HEAD/RETURN `(ScoreObjective,ScaledResolution,CallbackInfo)V`; both redirects `(Team,String)String` |
| `RenderPlayerScoreboardMixin` | `RenderPlayer#renderEntityName(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;D)V` contains exactly one `ScoreObjective#getDisplayName()String` | `(ScoreObjective)String` |
| `RenderLivingBaseMixin` | `RenderLivingBase#renderName(EntityLivingBase,DDD)V` contains exactly one `EntityLivingBase#getDisplayName()ITextComponent` | `(EntityLivingBase)ITextComponent` |
| `RenderEntityNameMixin` | `Render#renderName(Entity,DDD)V` contains exactly one `Entity#getDisplayName()ITextComponent` | `(Entity)ITextComponent` |
| `FtbTextBoxMixin` | exact FTBLib `TextBox#setFocused(Z)V`, `#onClosed()V`, and `#draw(Theme,IIII)V` | `(Z,CallbackInfo)V`, `(CallbackInfo)V`, and both draw callbacks `(CallbackInfo)V` |
| `TipsOverlayMixin` | exact static `TipsAPI#renderTip()V` | both static `(CallbackInfo)V` |

Forge 2795 also proves the event-bus split behind the chat/runtime repair:
`FMLCommonHandler#onPostClientTick()` posts `ClientTickEvent(END)` to
`FMLCommonHandler#bus()`, while GUI/render and `FMLNetworkEvent` are posted to
`MinecraftForge.EVENT_BUS`. The client bridge is consequently registered on
both exact buses. Shared-cache client/server Tick and player lifecycle handlers
are registered on the FML bus; `onPostServerTick()` and
`firePlayerLoggedOut()` exact bytecode post there.

## 2026-08-01 baseline parity completion and runtime acceptance

This section supersedes the 2026-07-29 build-only handoff for the current
product and runtime result.

### Ported baseline contracts

- The request queue now owns the real asynchronous HTTP future. It provides
  surface deduplication, priority lanes, age boosting, protected eviction,
  bounded retry, scoped cancellation, error-status TTL, and shutdown. The HTTP
  future disconnects its active `HttpURLConnection` and interrupts its worker
  when cancelled; diagnostics no longer use an unrelated common-pool future.
- Whole-frame GUI translation now recognizes formatting/control/private-use
  protected runs and replays layout programs with their source cursor advances.
  Compatible translated text is scaled only when necessary, with a `0.75`
  minimum scale, while incompatible layout programs retain the original text.
  Normal non-layout strings keep the legacy widget-width behavior.
- Sign mode changes cancel queued and running `sign.auto` / `sign.manual`
  requests before resetting state. The settings root renders the localized
  recent API-error status. The baseline validation scripts and Forge logo
  resource are present in the product tree and packaged JAR.
- The Component JSON wire contract remains unchanged: requests and responses
  are top-level Component arrays, hidden hover payloads are stripped and
  reattached, invalid/count-mismatched responses keep originals, and no plain
  text or retired wire-protocol fallback was introduced.

### Exact target and compiled-handler evidence

- Mapped Forge 2860 `FontRenderer#renderString(String,float,float,int,boolean)`
  has descriptor `(Ljava/lang/String;FFIZ)I`, one call to
  `renderStringAtPos`, and locals `this`, `String`, `float`, `float`, `int`,
  `boolean`. The designated Forge 2795 + OptiFine E3 runtime class `bip` has
  the same descriptor and local layout. The current cancellable HEAD handler
  is `(Ljava/lang/String;FFIZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V`;
  the width handler remains `(Ljava/lang/String;)Ljava/lang/String;` for exact
  `getStringWidth(String):int` `(Ljava/lang/String;)I`.
- The exact render/boss/scoreboard targets remain those recorded in the
  2026-07-30 table. Their current compiled handlers are:
  `Render#renderName` redirect
  `(Entity)ITextComponent`, `RenderLivingBase#renderName` redirect
  `(EntityLivingBase)ITextComponent`, `GuiBossOverlay#renderBossHealth` HEAD
  and RETURN `(CallbackInfo)V`, and `GuiIngame#renderScoreboard` HEAD/RETURN
  `(ScoreObjective,ScaledResolution,CallbackInfo)V` with both row redirects
  `(Team,String)String`.
- The first exact-client launch exposed Mixin 0.8's package boundary:
  injected render bytecode referenced `RenderEntityNameMixin$EntityMemo`, and
  Mixin rejected direct loading from its configured package with
  `IllegalClassLoadError`. All four Mixin-package nested helpers were removed.
  Entity and boss memos now use ordinary product type
  `core.ComponentTranslationMemo`; scoreboard restoration uses two typed
  `ThreadLocal` values. The clean compiled tree and packaged JAR contain zero
  `$` class entries below `com/yourname/simpletranslate/mixin/`.

### Product inventory and verification

- Current product inventory is 130 main Java files, 27 GUI files, 4 validation
  Java files, and 5 scripts. The Mixin inventory is 25 primary client entries,
  3 late FTBLib entries, and 1 late Tips entry. Both language files contain
  468 matching keys. The documented 1.12.2 exclusions remain Text Display,
  modern font-model controls, and Wynn-only surfaces; Wynn must not ship below
  Minecraft 1.21.4.
- Java 11 offline `clean build --no-daemon -x test`, `validatePortLogic`, and
  all 13 translation fixtures passed. Queue validation proves that scoped
  cancellation reaches the active transport future and that the queue resumes;
  GUI layout validation covers protected-run compatibility, mismatch rejection,
  normal-format classification, and source-advance guards.
- The reobfuscated non-sources JAR contains 333 entries and 296 class entries,
  uses Java 8 class-file major version 52, is 637,725 bytes, and has SHA-256
  `B59F02A2B305E1C3910AA536E53805FC474A5C531CD2ACBD3E0156A6E43E3327`.
  Required Mixin configs/refmap, logo, layout renderer, protected-run parser,
  memo support, and request queue are packaged. Source and JAR residue scans
  found no retired config screen, `PendingEntry`, `LogicalBlock`, OCR, Wynn,
  Text Display, `CompletableFuture.supplyAsync`, or legacy translation callable.
- The same JAR hash was deployed into the isolated designated client using
  Minecraft 1.12.2, Forge 14.23.5.2795, OptiFine E3, MixinBooter 9.4, FTBLib
  5.4.7.2, and FTB Quests 1202.9.0.15. The client loaded all 9 mods, started
  the integrated server, and `CodexTester` entered the existing save at
  12:29:46. The rotated exact-session log
  `logs/debug-1.log.gz` has SHA-256
  `E050C794A78FEFF263D5FE2F5501737E9C0F50CDC40FE467B2D05EEBB772E3EC`
  and contains no ERROR/FATAL severity line, Mixin/class-loading/injection
  failure, or crash signature. No new crash report was created.
- Per the user's acceptance instruction, runtime validation ends at successful
  existing-save entry plus clean logs; no separate key-by-key or GUI visual
  feature check is required for this handoff. No dedicated server or new world
  was used.
