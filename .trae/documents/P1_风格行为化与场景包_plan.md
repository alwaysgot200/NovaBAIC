# P1 风格行为化 + 场景包 实施计划

> 依据：`docs/2026-10-15-智能优化增强的聊天策略方案.md` §6.1–6.4、§7、§8.1、§10.2、§13 P1、§14 评审决议 #4。
> 铁律：零上游模块改动（仅白名单 Manifest/strings.xml 追加）；新代码进 app 模块 floatball 包；GPL 头；不 commit。

## 一、仓库调研结论（已逐文件核实）

### 现状
1. **风格现状**：联系人风格是 `contact_profiles.style` 一列自由文本；画像页用 schema 顶层
   `stylePresets`（8 个中文词）chip + 文本框编辑（[AdvisorProfileActivity.kt:407-413](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/AdvisorProfileActivity.kt#L407-L413)）；
   提示词侧 [ChatBriefBuilder.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/ChatBriefBuilder.kt) 以 `【我的语言风格】\n{style原文}` 一段注入，无 do/dont 行为约束。
2. **画像 schema v1**：[persona_schema.json](file:///e:/_android_work/NovaBAIC/app/src/main/assets/advisor/persona_schema.json)
   的 `options` 是 `List<String>`（中文 label），存储直接存 label；tags 存 label 的 JSON 数组。
   模型定义在 [PersonaSchema.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/PersonaSchema.kt)（`PersonaFieldDef.options: List<String>?`）。
3. **消费 options 的四处**（v2 改造面全集）：
   - 萃取提示词 [PersonaExtractor.kt:104](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/PersonaExtractor.kt#L104)：`可选值：label/label`，模型回填 label，parse 不做归一；
   - 编辑 UI `FieldEditorDialog`：enum chip 直接存 label；tags 走文本框；
   - 提示词渲染 `ChatBriefBuilder.renderPersona` → `PersonaValues.renderLine(label,type,value)`（无 schema 上下文）；
   - 上游双写 [ProfileMemorySync.kt:92-105](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/ProfileMemorySync.kt#L92-L105)：`我的{label}：{value}`，value 原样。
4. **DB**：advisor.db 当前 v3（[AdvisorDatabase.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/data/AdvisorDatabase.kt)），
   迁移走显式 `MIGRATION_2_3` + PRAGMA 幂等补列模式；Room 装配在 [AdvisorModule.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/AdvisorModule.kt)。
5. **资产加载范式**：[PersonaSchemas](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/PersonaSchema.kt#L106-L123)
   是 @Singleton + @Volatile 缓存读 assets JSON；话术另有 seeder 入库范式（本次不入库，风格/场景资产直接读 JSON，与 persona_schema 同范式）。
6. **UI 范式**：画像页/设置页均为 Compose + Baic2Theme；[FloatSettingsActivity.kt:337-361](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/FloatSettingsActivity.kt#L337-L361)
   已有"档案管理"入口卡可仿照新增入口；Activity 均 @AndroidEntryPoint。
7. P0 已完成：`customSystemPrompt` 段落表首位是规约总纲；brief.section 作为其中一段拼接；
   风格段升级后自然落在总纲之后，无需动 ScreenAdvisor 主结构。

### P1 边界（与方案终态的分期裁剪）
- **做**：两份新资产、schema v2 + 存量惰性迁移、style_cards 表（v3→v4）、StyleResolver 查表渲染、
  风格卡编辑 UI（全局 + 联系人）、场景选择与默认风格配齐。
- **不做（P2/P3）**：阶段机运行态与门控、策略卡/目标体系、Tactician/Situator/Judge、
  画像 salience 集合裁剪与 staleAfterDays 时效渲染、自定义补充的 LLM 行为化编译确认流（P1 customNote 原文直接注入，字段与表结构预留）。
- **fitStages 处理**：资产与 Resolver 完整实现阶段过滤（纯函数 + 单测），但 P1 调用处传
  `stageCode = null`（不过滤，全部选中意图渲染）；P2 接阶段状态后零改动启用。
- **DB 版本偏离说明**：方案 §6.5 设想 3→4 一次建完全部新表；因评审决议 #4 要求 P1/P2 不合批、
  P1 要独立灰度，P1 只建 `style_cards` + `contact_profiles.scenarioCode`；goals 等四表留给
  P2 的 4→5 迁移，避免灰度版本携带未使用表结构。
- **场景包 P1 消费深度**：仅"选择场景 + 一键预填默认 tone/intents + 存 scenarioCode"；
  stages/retreatSignals/taboos/goalTemplates 只随资产加载、不参与回合（P2 消费）。

## 二、改动文件清单

### 新增
| 文件 | 职责 |
| --- | --- |
| `app/src/main/assets/advisor/style_playbook.json` | 8 基调 + 10 意图（do/dont/good/bad/fitStages） |
| `app/src/main/assets/advisor/scenario_packs.json` | dating / sales_b2b 两包（阶段机/模板资产一次给全） |
| `floatball/advisor/StyleAssets.kt` | `StylePlaybook/StyleTone/StyleIntent`、`ScenarioPack/…` 数据类 + 两个 assets 加载器（@Singleton，缓存） |
| `floatball/advisor/StyleResolver.kt` | **纯函数 object**：风格卡 →【说话方式】提示词段；code↔label 解析、fitStages 过滤、customNote 拼接 |
| `floatball/advisor/StyleCardRepository.kt` | style_cards 读写（联系人卡/全局卡）、场景默认值预填 |
| `floatball/advisor/PersonaValueCodes.kt` | **纯函数**：code 合法性、`~` 前缀 custom、stored→显示 label、萃取 label→code 归一、迁移映射 |
| `floatball/advisor/PersonaValueMigrator.kt` | 一次性幂等批处理：persona_fields 的 enum/tags label→code（SharedPreferences 版本门） |
| `floatball/advisor/data/StyleCardEntity.kt`（并入 AdvisorEntities.kt 亦可） | StyleCardEntity + StyleCardDao（并入 AdvisorDaos.kt） |
| `floatball/StyleCardActivity.kt` | 风格卡编辑页（Compose）：基调单选/意图多选+do/dont 预览/自定义一句/实时渲染预览/场景选择 |
| `app/src/test/.../floatball/StyleResolverTest.kt` | JVM 单测 |
| `app/src/test/.../floatball/PersonaValueCodesTest.kt` | JVM 单测 |

### 修改
| 文件 | 改动 |
| --- | --- |
| `assets/advisor/persona_schema.json` | 升 v2：options 改对象（code/label/impl/topicHook）；字段加 allowCustom/salience/implTpl；新增 5 字段（my_aspiration、my_relation_goal_default、emotionStyle、replyPace、loveLanguage）；移除顶层 stylePresets |
| `advisor/PersonaSchema.kt` | `FieldOption` 数据类；`PersonaFieldDef.options: List<FieldOption>?`、新增 allowCustom/salience/implTpl/staleAfterDays；`optionByCode/optionByLabel` 辅助 |
| `advisor/PersonaExtractor.kt` | 目录可选值展示 label；parse 后 value 经 `PersonaValueCodes` 归一（label→code，未知→`~原文`） |
| `advisor/ChatBriefBuilder.kt` | 风格段改走 StyleCardRepository+StyleResolver（联系人卡→全局卡→v3 style 列回退→省略）；画像值 code→label 显示；salience=high 选项追加 impl 短句；curatedTexts 取 label 文本（去重指纹原料语义不变） |
| `advisor/ProfileMemorySync.kt` | 双写上游前 code→label（含 tags 逐元素解析），保证写入记忆的是人类可读文本 |
| `advisor/data/AdvisorEntities.kt` | `ContactProfileEntity` 加 `scenarioCode: String?`；新增 StyleCardEntity |
| `advisor/data/AdvisorDaos.kt` | 新增 StyleCardDao；persona 全量行查询（迁移用，若已有则复用） |
| `advisor/data/AdvisorDatabase.kt` | version 3→4，注册实体 + `MIGRATION_3_4` |
| `advisor/AdvisorModule.kt` | addMigrations(MIGRATION_2_3, MIGRATION_3_4) |
| `AdvisorProfileActivity.kt` | 联系人详情：移除 stylePresets chip/文本框，改"说话风格"入口行（摘要 tone+intents label）；画像 enum/tags 编辑改 v2 控件（label chip 存 code + "✎ 自定义…"）；onCreate 触发一次 Migrator；我方人设页加入口 |
| `FloatSettingsActivity.kt` | 档案区新增"默认说话风格"入口卡 → StyleCardActivity（全局模式） |
| `app/src/main/AndroidManifest.xml` | 追加 StyleCardActivity 声明（白名单） |
| `app/src/main/res/values/strings.xml` | 追加 `float_style_*`、`float_scenario_*` 键（白名单） |

## 三、实施步骤（依赖序）

1. **schema v2 资产与模型**：重写 persona_schema.json（全量字段保持 key 不变，只升级 options 形态与追加字段）；
   更新 PersonaSchema.kt 数据类（`ignoreUnknownKeys` 已开，旧缓存无影响）。
2. **PersonaValueCodes 纯函数 + 单测先行**：
   - `CODE_PATTERN = [a-z0-9_]+`；custom 前缀 `~`；
   - `displayValue(def, raw)`：code→option.label；`~x`→x；旧 label→label（兼容未迁移值）；原样兜底；
   - `normalizeExtracted(def, raw)`：等于某 label/code → code；否则 `~raw`（custom 仅在 allowCustom≠false 时，否则丢弃归 null）；
   - tags 逐元素同规则；迁移映射 `migrateStored(def, raw)` 幂等（code/`~` 不动，label→code，未知→`~label`）。
3. **PersonaValueMigrator**：prefs 门（`persona_value_codes_version=1`）→ 一次事务扫全量 persona_fields，
   仅 enum/tags 转换；转换行数写日志；失败静默保留原值（渲染侧本来就兼容旧 label）。
4. **接消费方**：Extractor parse 归一；FieldEditorDialog v2 控件（enum 单选 label chip + 自定义输入切换；
   tags 常用项 chip + 输入回车，自定义值描边 chip）；ChatBriefBuilder/ProfileMemorySync 显示与双写改 label 解析；
   画像页 LaunchedEffect 触发 Migrator。
5. **style_playbook.json**：8 tones（pro/buddy/warm/consult/direct/empathetic/data/delicate 等，
   voiceRule 一条）+ 10 intents（spark_topic/hold_warmth/show_value/push_close/comfort/
   clarify_need/handle_objection/anchor_commit/light_tease/graceful_exit 等，每个含 do 2–3、dont 1–2、
   good/bad 各一、fitStages 可空）。StyleAssets.kt 加载器 + 解析。
6. **scenario_packs.json**：dating 按方案示例补全 defaultTone/defaultIntents；
   sales_b2b 补全同名键（defaultTone=pro、intents=clarify_need/handle_objection/anchor_commit 等）；
   sales 阶段码与现有 intentStage 七词对齐。StyleAssets 内 ScenarioPacks 加载器。
7. **DB v4**：StyleCardEntity（id/scope/contactId/toneCode/intentCodes(JSON)/customNote/
   compiledCustomJson 预留空/confirmed=1/updatedAt；唯一索引 scope+contactId）；
   MIGRATION_3_4（CREATE TABLE style_cards + ensureColumn scenarioCode）；DAO（upsert/find/observe）。
8. **StyleCardRepository + StyleResolver**：
   - 选择链 `cardFor(contactId)`：contact 卡 → my_default 卡 → null；
   - `render(card, playbook, stageCode=null)`：标题【说话方式】；tone.voiceRule；
     选中 intents 按资产顺序输出「意图 label：要…/不要…」（无效 code 跳过）；
     customNote 非空追加"其他要求：{原文}"；全空返回 null；
   - 场景预填：applyScenario(contactId, packCode)：卡不存在或用户确认重置时写 default 组合 + profile.scenarioCode。
9. **ChatBriefBuilder 接线**：风格段从 profile.style 切换为 StyleResolver 输出；
   三级回退（联系人卡/全局卡/v3 style 原文）；Brief.section 内标题改【说话方式】。
10. **StyleCardActivity UI**：两种模式（全局/联系人，intent extra 传 contactId，0=全局）；
    顶部联系人模式含场景下拉（选场景弹"预填默认风格"确认）；基调单选；意图多选 chip +
    选中即展开 do/dont（good/bad 折叠"示例"）；customNote 单行输入框；底部"提示词预览"只读卡片
    （StyleResolver 实时输出）；保存 toast。
11. **入口接线**：FloatSettingsActivity 入口卡；AdvisorProfileActivity 联系人详情入口行 +
    MyPersonaScreen 顶部入口；Manifest 注册；strings 追加。
12. **单测**：StyleResolverTest（6–8 例：完整渲染/空卡/无效意图码跳过/customNote/
    fitStages 给定阶段时过滤与 null 不过滤/全局回退由 Repository 测试不涉及 Android 故只测纯函数）；
    PersonaValueCodesTest（code/label/~ /tags 混合/未知 label/幂等，8–10 例）。

## 四、依赖与注意事项

- 序列化：新 JSON 全部 kotlinx.serialization + `ignoreUnknownKeys = true`；资产中不含注释尾逗号。
- Room：新增实体同步进 @Database entities；若 KSP 报 `*_GeneratedInjector` 找不到，按教训 5 删
  `app/build/generated/ksp` 重建。
- `contact_profiles.style` 旧列**只读保留**：合并逻辑（mergeContacts 取 style）不动；
  新链路只在风格卡缺失时回退读它；UI 不再写该列。
- v3 旧用户零丢失：enum/tags 旧 label 有三道保险——Migrator 批处理、渲染侧 label 兼容、萃取归一；
  映射失败的值以 `~原文`（custom）原样存活。
- 风格段不进 P0 的事实指纹去重链（指令性文本，不与事实互斥）。
- 字符串全部进 strings.xml（float_ 前缀）；Compose 走 Baic2Theme token，不硬编码颜色。
- 自定义补充 P1 不调 LLM（compiledCustomJson 始终 null、confirmed=1 原文生效），P3 再做编译确认流。
- 不新增 Gradle 模块；不 new OkHttp/Json（Json 用各文件既有的 `Json { ignoreUnknownKeys=true }` 范式）。

## 五、验证

1. `bash tools/check-license-headers.sh`（若 WSL bash 仍受 CRLF 影响，用等价 PowerShell 扫描兜底）。
2. `./gradlew :app:assembleDebug`（JAVA_HOME=D:\programfiles\Java\jdk-17）。
3. `./gradlew :app:testDebugUnitTest`：新单测全绿 + 既有 P0 单测不回归。
4. GetDiagnostics 零错误。
5. 模拟器冒烟（pwsh 7 跑 build-client 或手动）：
   - 升级安装覆盖旧库（不 clear-data），冷启动无崩溃；
   - 画像页打开后检查 persona_fields 旧 enum 值已变 code（或仍以 label 正确显示，二者皆可接受）；
   - 设置页 → 默认风格卡：选基调+2 意图，预览含 voiceRule/do/dont，保存；
   - 联系人详情 → 风格入口：选 dating 场景默认值预填；
   - 发起一次参谋回合，logcat/分屏回答行为符合所选风格；
   - `logcat | grep FATAL` 为空。
6. 灰度门（交付后人工，非本次实施门槛）：≥7 天 / ≥50 回合，do/dont 符合率 ≥85%，达标才启动 P2。

## 六、风险与对策

- **小模型不按 code 萃取**：萃取提示词仍给中文 label（保萃取质量），落库前代码归一为 code；
  模型自造值走 `~` custom 通道，不丢不堵。
- **风格资产条目不完备导致空泛**：10 个意图覆盖销售/约会两用；good/bad 示例给模型锚点；
  灰度门不达标只补 JSON 资产即可（资产热更新走 seeder 版本式思路，后续可仿 playbooks 版本号）。
- **v2 schema 漏改消费方导致显示 code**：PersonaValueCodes 单测 + 四处消费点逐一接线；
  渲染侧 label 未命中时回显原值（不出 `direct` 这类裸码的最坏情况也只是兜底原文，不崩）。
- **Room 迁移失败**：ensureColumn 幂等范式；MIGRATION 只 CREATE/ALTER，不做数据搬运
  （enum 迁移在 Room 外惰性执行）。
- **P1/P2 资产字段前向兼容**：scenario_packs 中 stages/模板等 P2 字段现在就进 JSON，
  数据类用 ignoreUnknownKeys 或直接建全字段（建全字段更利于 P2 零资产迁移）。
