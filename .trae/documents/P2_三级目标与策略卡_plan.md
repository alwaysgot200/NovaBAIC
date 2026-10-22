# P2 三级目标 + 策略卡 实施计划（细化版 v3）

> **📌 状态：已全量实施完成（2026-10-15；2026-10-16 覆盖安装 v4→v5 全链路冒烟通过，代码待用户最终确认后提交）。**
> **本文档的设计边界（不切片、12 包、L1 不弹分屏、线性阶段机、增强档延后、无 P4）与落地实现一致；**
> **仅有少量文件名与实现偏差，记录于文末「实施后勘误」。架构事实总纲以
> [`docs/2026-10-15-智能优化增强的聊天策略方案.md`](../../docs/2026-10-15-智能优化增强的聊天策略方案.md)
> §4/§6/§13（P2 已完成）为准；§十三 P3 细化已由
> [P3 实施计划](P3_有向图阶段机与全量增强_plan.md) 全量落地（2026-10-16，12 子项全绿），
> 2026-10-16 之后的后续演进见文末「实施后勘误二」。**
>
> 依据：`docs/2026-10-15-智能优化增强的聊天策略方案.md`（下称「总方案」）§4 三级目标、§5 流水线、
> §6.5/§6.6 数据与产物、§7 门控、§8 提示词契约、§9 固化、§10 界面、§11 调度降级、**§13 分期（仅 P0/P1/P2/P3，无 P4）**、
> §14 决议；以及 2026-10-15 需求评审三问：**多场景不同路线、路线分叉/倒退/跳级/动态变化、远中近三级目标**。
> 铁律：上游零侵入（仅 Manifest/strings 白名单纯增量）；新代码进 app 模块 floatball 包（新增 `strategy/` 子包）；
> 每个 .kt 带 GPL-3.0-or-later 头；文案进 strings.xml（`float_*`）；不 commit。
>
> **已拍板边界（2026-10-15）**：
> 1. **P2 一次性全量实施，不切片**：五表 + 两编译器 + 线性阶段机 + Situator/Tactician/Judge + talk_missions
>    + 全部 UI + **12 个场景包一次做齐**；
> 2. **Judge = 规则优先 + 节流轻 LLM**；
> 3. **增强档双调用延后 P3**；P2 只做总方案决议 #3 的「默认档一次调用两段式」；
> 4. **路线生成 = 混合制：模板为骨、LLM 为肉**（§0.1）；
> 5. **目标三级化**：L3 远期愿景 / L2 中期成果 / L1 近期目标 / mission 本轮任务令；
>    **L1 的 AI 建议只在目标 Tab 提示，不弹分屏**（已确认的降噪口径）；
> 6. **分期严格对齐总方案**：路线动态性（有向图/分叉/回退/跳级/reroute）全部归入 **P3 打磨**；
>    更远的自进化设想只作备忘，不虚构 P4 立项（§十三）。

---

## 0. 核心设计立场（先回答评审三问）

### 0.1 多场景路线：模板为骨、LLM 为肉；P2 一次做齐 12 个场景包

| 层 | 谁产出 | 内容 | 为什么 |
| --- | --- | --- | --- |
| **骨**：站点序列、退缩信号、红线、目标模板 | **内置资产包** scenario_packs.json（可审计 JSON） | 12 个关系场景各自的站点链 | 确定性、零 token、不会让模型在恋爱第二站生成「逼婚」节点；措辞有问题改资产即可热修，无需发版 |
| **肉**：每站具体目标描述、出站信号措辞、修炼建议、L1 近期目标 | **PLANNER 按模板生成**，但 stage code 必须在包内，越界整项丢弃 | 因人而异的「这一站对你和小陈意味着什么」 | 纯模板僵化，纯 LLM 不可控；code 白名单是安全闸 |
| **改图**：新增站点/边 | **P3** 才允许 LLM 提议（有向图/重规划），且必须留痕；P2 阶段机是线性链 | 路线动态调整 | 见 §十三 |

包两个新元数据字段（StyleAssets 解析）：
- `pathKind: "pursue" | "maintain" | "defend"`（默认 pursue）：
  **pursue=推进型**（终点是亲密/成交/offer）；**maintain=经营型**（到站后长期保持）；
  **defend=防守型**（终点是止损/切割而非拉近关系，永不注入「推进关系」措辞，红线最前置）；
- `group: "romance" | "workplace" | "business" | "conflict"`：场景选择器按四组分段展示。

12 包全清单（详见 §四；dating/sales_b2b 为 P1 存量，其余 10 个 P2 新增）：

| 组 | code | 场景 | pathKind |
| --- | --- | --- | --- |
| 情感 | `dating` | 恋爱/暧昧 | pursue |
| 情感 | `couple` | 情侣/夫妻长期经营 | maintain |
| 情感 | `family` | 父母/长辈代际沟通 | maintain |
| 职场 | `workplace_superior` | 与上级 | maintain |
| 职场 | `colleague` | 同事平级 | maintain |
| 职场 | `job_hunt` | 求职/面试/谈 offer | pursue |
| 商业 | `sales_b2b` | B2B 销售 | pursue |
| 商业 | `sales_c` | C 端销售（零售/保险/微商） | pursue |
| 商业 | `vendor` | 供应商/乙方管理（我是甲方） | maintain |
| 商业 | `networking` | 贵人/弱关系经营 | maintain |
| 冲突 | `adversarial` | 人际冲突/对立方 | defend |
| 冲突 | `complaint` | 消费投诉/服务维权 | defend |

**明确不做**：群聊多对方场景（当前数据模型是一对一联系人；列入 P3 储备，P2 不做）。

### 0.2 路线动态性：P2 诚实的局限，P3 给正解

- **P2**：阶段是**线性链**——顺序前进（信号阈值+Judge 确认）、手动改站（always allowed、留痕）、
  retreat 时挂降温态势但**不回站**。覆盖常态推进，每项自动动作可审计可撤销。
- **P3**：阶段数组升级为**有向图**四种边 + 路线版本化重规划（reroute），对齐总方案 §13「P3 打磨」，
  细化设计见 §十三（branch 分叉必问、retreat 累计自动回退仅通知、skip 高置信跳站可撤销）。
- 总方案 §14 已有承诺：模型无权当场跳阶段、信号计数 + Judge 回合后确认 + EVENT 留痕可回滚——P2/P3 均遵守。

### 0.3 目标层级：L3→L2→L1→mission 四级链条

```
L3 远期愿景（3个月+，用户手建）   例：和小陈走向稳定关系 / 成为领导倚重的人
 └─ L2 中期成果（1~3个月）         例：月底前完成第三次线下见面 / Q4 前签下基础版
     └─ L1 近期目标（本周级）      例：本轮聊天拿到她对周末演唱会去/不去的明确态度
         └─ mission 本轮任务令（每回合）例：借演唱会话题分享自己经历后反问，不催答复
```

- L1 与 L2 复用同一张 goals 表（horizon=1，parentId 指向 L2），由 PLANNER 编译 L2 路线图时**顺带产出 1~3 个
  当前阶段 L1 建议**，也可手建；
- **L1 建议降噪口径（已确认）**：不弹分屏轻确认条，只在目标 Tab 标「AI 建议」，用户顺手 ✓采纳/忽略；
  active+provisional L1 上限 3 条；provisional L1 7 天未确认惰性清理为 abandoned；L1 完成 P2 靠手动勾选；
- L2 的 provisional 仍走总方案决议 #2 的分屏轻确认（最多 2 次，沉默归档）——两档目标打扰级别不同；
- 无 L1 时 mission 服务于阶段 missionHint，行为等同没有 L1；策略链不开时完全等同 P1。

---

## 一、仓库调研结论（已逐文件核实）

### 1.1 P1 已就位、P2 直接消费

- 场景包 [scenario_packs.json](file:///e:/_android_work/NovaBAIC/app/src/main/assets/advisor/scenario_packs.json)：
  dating（weak/rebuild/attract/probe/confirm）、sales_b2b（ice/inquiry/compare/hesitate/push/deal/after）；
  每站 code/label/advanceSignals/missionHint，包级 retreatSignals/taboos/goalTemplates
  （占位符 `{name}/{aspiration}/{deadline}/{objection}`）；
  [StyleAssets.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/StyleAssets.kt)
  解析为 `ScenarioPack/ScenarioStage/ScenarioGoalTemplate`。
- 风格意图 fitStages：[StyleResolver.render](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/StyleResolver.kt)
  当前按 `stageCode==null || fitStages==null || fitStages.contains(stageCode)` 过滤；
  **P1 恒传 null，P2 传真实 stageCode**——但新 10 包的 stage code 不在现有意图 fitStages 词表内，
  需要跨包兼容规则（§7.5），否则用户选的意图在新场景会被误杀。
- 画像 schema v2：salience/option.topicHook/option.impl/implTpl 已在
  [persona_schema.json](file:///e:/_android_work/NovaBAIC/app/src/main/assets/advisor/persona_schema.json)；
  红线原料：their `taboos`（雷区禁忌）、my `my_taboo`（我不能说的话）。
- DB v4：`contact_profiles.scenarioCode` 已在；MIGRATION_3_4 注释预留「goals/stage_states 等 P2 表走 4→5」。P2 升 **v5**。

### 1.2 主链路挂点（[ScreenAdvisor.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/ScreenAdvisor.kt)）

- `runAdvisorTurn`：T1 并行取数（五类 deferred）→ PromptDedup →
  `customSystemPrompt = listOfNotNull(PREAMBLE, styleWithLength, brief.styleSection, directionSection, brief.section, contactSection)`
  → 两通道（lean `collectLeanOnce` / AgentLoop `collectAdvisorOnce`）。
- 回合后沉淀：`consolidationLock` 内 `maybeRefreshDigest + maybeExtractPersona`；**Judge 挂同一把锁追加其后**。
- 慢车道 LLM 范式：`AuxiliaryTasks.complete(config, systemPrompt, userPrompt, maxTokens, temperature)`
  （[PersonaExtractor.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/PersonaExtractor.kt)）。
- 面板 [FloatUi.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/FloatUi.kt)：
  header/divider/statusBar/transcriptContainer/inputBar；**策略条插在 statusBar 与 transcriptContainer 之间**。
- 详情页 [ContactDetailScreen](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/AdvisorProfileActivity.kt)
  单滚动列、ProfileNav 三屏（LIST/DETAIL/MINE）；P2 在 DETAIL 加「画像/目标」TabRow + Activity extras。
- 设置项范式：[AdvisorPrefs.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/AdvisorPrefs.kt)
  SharedPreferences + StateFlow + setter 校验。

### 1.3 P2 不做（归 P3 或备忘）

有向图阶段机/branch/retreat 边/skip/reroute、增强档双调用、defend/职场专用意图库、L1 自动完成、
salience ≤6 条裁剪、staleAfterDays、customNote 的 LLM 编译（compiledCustomJson 继续恒 null）、
时序信号（已读不回 24h）、eval 断言剧本、群聊包、全局 L3 独立管理 UI（schema 支持 contactId=0，P2 仅做全局默认策略卡）。

---

## 二、包结构与组件总览（军师班子对照）

| 比喻角色 | 文件 | 职责 | 性质 |
| --- | --- | --- | --- |
| 战略参谋长 | `advisor/GoalPlanner.kt` | STRATEGIST（L3→L2 候选+修炼项）、PLANNER（L2→路线图+当前站 L1） | @Inject 慢车道 LLM |
| 情报参谋 | `advisor/CardCompiler.kt` | 策略卡五段确定性拼接 + roleLine 一句话（LLM，失败有兜底） | @Inject 慢车道 |
| 作业调度 | `strategy/StrategyRuntime.kt` | 编译队列：IO 协程+Mutex+inFlight 去重 | @Singleton |
| 数据 | `advisor/GoalRepository.kt`、`advisor/StrategyCardRepository.kt` | 目标/卡片存取与状态机（互斥上限、promptCount、60 条裁剪） | @Inject |
| 取数参谋 | `advisor/StrategyBrief.kt` | T1 串联目标/卡/阶段/态势 → StrategyContext；**Situator 职责落地为本文件 `assess()`（无独立 Situator.kt，见文末勘误）** | @Inject |
| 侦察兵 | （并入 `StrategyBrief.assess`） | retreat 命中、advance 候选、上轮 mission 待裁（零 LLM） | @Inject |
| 临场指挥 | `strategy/Tactician.kt` | 输出协议段文案 + MissionPlan JSON 解析 | 纯函数 |
| 复盘观察员 | `advisor/MissionJudgeRunner.kt`（计划名 Judge.kt，落地改名，见文末勘误） | T6 规则计数 + 下回合节流轻 LLM | @Inject |
| 文书分拣员 | `strategy/PlanStreamFilter.kt` | 流式剥离 `<plan>`（chunk 安全、畸形兜底） | **纯函数+单测** |
| 军法部门 | `strategy/SignalRules.kt`、`StagePolicy.kt`、`StageGate.kt` | 词表信号、阈值跃迁、五道门与段落渲染 | **纯函数+单测** |
| 文案 | `strategy/StrategyPrompt.kt` | 策略段渲染 | 纯函数 |
| 模型 | `strategy/StrategyModels.kt` | JSON 产物与全部状态常量 | 纯 Kotlin |
| UI | `floatball/GoalTab.kt`、`floatball/StrategyCardActivity.kt` | 目标三分区+台账；策略卡审核页 | Compose |

---

## 三、数据层：Room v4 → v5

### 3.1 常量与新实体（追加进 AdvisorEntities.kt）

```kotlin
const val GOAL_DRAFT="draft"; const val GOAL_PROVISIONAL="provisional"; const val GOAL_ACTIVE="active"
const val GOAL_DONE="done"; const val GOAL_ABANDONED="abandoned"; const val GOAL_REVIEW="review"
const val HORIZON_L3=3; const val HORIZON_L2=2; const val HORIZON_L1=1
const val DECOMP_OUTCOMES="outcomes"; const val DECOMP_ROADMAP="roadmap"; const val DECOMP_PRACTICES="practices"
const val CARD_DRAFT="draft"; const val CARD_CONFIRMED="confirmed"; const val CARD_ARCHIVED="archived"
const val MISSION_PENDING="pending"; const val MISSION_ADVANCED="advanced"; const val MISSION_HELD="held"
const val MISSION_REGRESSED="regressed"; const val MISSION_IGNORED="ignored"

@Entity(tableName="goals",
    indices=[Index(value=["contactId","status"]), Index(value=["parentId"]),
             Index(value=["contactId","horizon","status"])])
data class GoalEntity(
    @PrimaryKey(autoGenerate=true) val id: Long=0L,
    val contactId: Long,                  // 0=跨联系人理想自我（P2 UI 仅消费）
    val horizon: Int,                     // 3=L3 远期 / 2=L2 中期 / 1=L1 近期
    val parentId: Long?=null,             // L2→L3；L1→L2
    val scenarioCode: String?=null,
    val title: String,
    val successCriteria: String?=null,
    val deadlineAt: Long?=null,
    val status: String=GOAL_DRAFT,
    val promptCount: Int=0,               // L2 分屏轻确认计数（≤2）；L1 恒 0（只页面提示）
    val priority: Int=5,
    val source: String="user",            // user / ai
    val confirmed: Boolean=false,
    val progressNote: String?=null,
    val failStreak: Int=0,
    val reviewedAt: Long?=null,
    val createdAt: Long=0L,
    val updatedAt: Long=0L,
)
// 其余四表（字段同 v2 设计，此处保留签名要点）：
// goal_decomps(id,goalId,version,kind,payloadJson,confirmed,supersededBy,createdAt)
// stage_states(contactId,scenarioCode,stageCode,enteredAt,advanceHits JSON,evidence) 复合主键
// strategy_cards(id,contactId,version,scenarioCode,payloadJson,status,createdAt)
// talk_missions(id,conversationId,contactId,turnMessageId,stageCode,goalId,
//               mission,actionsJson,forbiddenJson,signalsJson,planBlockJson,
//               result,adoptedIndex(P3预留P2恒null),createdAt)
```

`ContactProfileEntity` 尾部追加 `val activeCardId: Long?=null`。

互斥上限（Repository 强制）：L3 active ≤1（新建前提示归档旧愿景，不删）；L2 active+provisional ≤2；
L1 active+provisional ≤3。

### 3.2 DAO 要点（追加进 AdvisorDaos.kt）

- GoalDao：upsert/find/observe/observeByContact(contactId,horizon)/
  listActive(contactId,horizon)（status IN provisional,active；priority DESC, updatedAt DESC）/
  setStatus/setProgress/incrementPromptCount/listProvisionalL2(contactId)（promptCount<2）/
  countActive(contactId,horizon)/listStaleProvisionalL1(contactId,now)（horizon=1 且 createdAt 超 7 天）。
- GoalDecompDao：upsert/latest(goalId,kind)/observeLatest/supersede(oldId,byId,now)。
- StageStateDao：get/upsert/observe。
- StrategyCardDao：upsert/latest(contactId,status)/observeLatest/archiveOthers(contactId,keepId,now)；
  ContactProfileDao 加 setActiveCard(contactId,id,now)。
- TalkMissionDao：insert/latestPending/settle/observeRecent(contactId,limit)；
  trimContact(contactId,keep=60) 与 trimConversation(conversationId,keep=60)（NOT IN 子查询范式）。

### 3.3 MIGRATION_4_5

五表 CREATE TABLE IF NOT EXISTS（DDL 逐列对齐实体，照 MIGRATION_3_4 范式）+ 索引 IF NOT EXISTS +
`ensureColumn(db,"contact_profiles","activeCardId","activeCardId INTEGER")`；不搬运旧数据；
AdvisorModule 注册 `.addMigrations(…, MIGRATION_4_5)`，version=5。
旧 v3 `contact_profiles.goal/style` 列继续只读回退，不删不双写。

### 3.4 JSON 产物（StrategyModels.kt）

```kotlin
@Serializable data class MissionPlan(
    val mission: String="", val actions: List<String>=emptyList(),
    val forbidden: List<String>=emptyList(), val signalsToWatch: List<String>=emptyList(),
    val successWhen: String="")
@Serializable data class CardItem(val text: String="", val source: String="ai") // ai/user
@Serializable data class StrategyCardPayload(
    val roleLine: String="",
    val selfConstraints: List<CardItem>=emptyList(),
    val theirImplications: List<CardItem>=emptyList(),
    val topicBank: List<CardItem>=emptyList(),
    val taboos: List<CardItem>=emptyList(),
    val cardVersion: Int=1)
@Serializable data class L2Candidate(val title:String="", val successCriteria:String="",
    val deadlineDays: Int?=null, val priority:Int=5)
@Serializable data class L1Candidate(val title:String="", val successCriteria:String="",
    val withinDays: Int=7, val priority:Int=5)
@Serializable data class OutcomesPayload(val l2:List<L2Candidate>=emptyList(), val practices:List<String>=emptyList())
@Serializable data class RoadmapStage(val code:String="", val goal:String="", val exitSignals:List<String>=emptyList())
@Serializable data class RoadmapPayload(
    val stages: List<RoadmapStage>=emptyList(),
    val practices: List<String>=emptyList(),
    val l1: List<L1Candidate>=emptyList())
```

---

## 四、资产：scenario_packs.json 扩至 12 包（纯数据增量）

StyleAssets 的 `ScenarioPack` 增 `pathKind: String?="pursue"`、`group: String?="romance"`；
解析失败的单个包跳过注册（不影响其他包，启动 Log.e 一次）。下表为全部 12 包草案，实施时逐字落入 JSON
（措辞保守、红线宁多勿少；上线后可热修资产）。

### 4.1 情感组

**① dating 恋爱/暧昧（pursue，存量保留）**：weak 弱联系 → rebuild 重建对话 → attract 吸引/舒适 →
probe 试探邀约 → confirm 敲定见面。退缩：拒绝/算了/呵呵/已读不回超过24小时（时长信号 P2 跳过词表，§7.2）。
红线：查户口式连续提问、深夜连续试探、提前任/查岗、弱联系阶段发起邀约。

**② couple 情侣/夫妻经营（maintain，新增）**：
`daily` 日常经营 → `cooling` 矛盾降温 → `repair` 修复重建 → `cocommit` 共同规划（终点站停留型）。
advanceSignals 摘：主动分享日常/回应关心；冲突后愿意复盘；一起讨论具体未来安排。
retreatSignals：随你便、你根本不懂我、又开始了、分房/冷战相关表述。
taboos：翻旧账、冷战试探、拿别人对象比较、在气头上做决定、当众指责对方家庭。
goalTemplates：L3「和{name}保持长期稳定、彼此舒服的亲密关系，同时我成为{aspiration}的人」；
L2「在{deadline}前就{name}在意的那件事达成双方都能接受的相处约定」。

**③ family 父母/长辈代际沟通（maintain，新增）**：
`greet` 日常问候/报平安 → `gap` 观念分歧（催婚/消费/工作）→ `insist` 温和坚持边界 → `compromise` 各退一步。
advanceSignals：主动问你近况而非只说教；愿意听你讲完一次；态度软化/主动转移话题示好。
retreatSignals：你不懂、白养你了、以后别回来了、我都是为你好（终结句式）。
taboos：顶撞式否定、用「你们那套」贴标签、金钱问题赌气失联、在亲戚群里公开顶撞、把伴侣/工作矛盾甩给长辈裁决。
goalTemplates：L3「和{name}保持亲疏有度的关系，既不让步人生选择也不伤亲情」；
L2「在{deadline}前让{name}接受/不再强推我在{name}最在意的那件事上的决定」。

### 4.2 职场组

**④ workplace_superior 与上级（maintain，新增）**：
`settle` 站稳脚跟 → `trust` 建立信任 → `delegated` 被授权挑大梁 → `promoted` 关键项目/晋升（终点停留）。
retreatSignals：对你失望、这事你别管了、我让别人跟、当众批评、已读不回工作请示。
taboos：当众顶撞、揽功推过、未经请示越级汇报、在同事前抱怨薪资/领导、微信群里情绪化回复、没给方案只抛问题。
goalTemplates：L3「在{name}的团队中成为被倚重的人，同时成为{aspiration}的专业者」；
L2「在{deadline}前独立扛下一个被{name}认可的关键交付」。

**⑤ colleague 同事平级（maintain，新增）**：
`ice` 破冰 → `familiar` 熟络 → `mutual` 互助默契 → `ally` 关键盟友（终点停留）。
retreatSignals：甩锅、这不是我的事、你找领导吧、背后议论被知晓、划清界限式回复。
taboos：交浅言深、吐槽共同上级、打听或泄露薪资、把别人帮忙当义务、工作群公开纠错让人下不来台、代人做承诺。
goalTemplates：L2「在{deadline}前和{name}形成稳定协作默契，关键事项上愿意主动配合我」。

**⑥ job_hunt 求职/面试/谈 offer（pursue，新增，短链一次性）**：
`apply` 初筛沟通 → `interview` 面试推进 → `offer` offer/薪资谈判 → `onboard` 入职衔接。
advanceSignals：主动约面试时间/问到岗时间、进入谈薪、发正式 offer、确认入职日期。
retreatSignals：已招到合适的人、岗位冻结、流程暂停、不回复跟进。
taboos：虚报薪资/履历、群发感模板催进度、在面试沟通中贬低前东家、谈薪阶段下最后通牒、未拿到 offer 先离职。
goalTemplates：L2「在{deadline}前拿到{name}所在团队的正式 offer 且薪资不低于预期」；
模板支持无联系人场景（猎头/HR 即联系人）。

### 4.3 商业组

**⑦ sales_b2b B2B 销售（pursue，存量保留）**：ice 破冰 → inquiry 询盘 → compare 比价 → hesitate 犹豫 →
push 促单 → deal 成交 → after 售后复购。红线：未确认预算前报价、对已转意向客户继续强调痛点、凭空改价或食言。

**⑧ sales_c C 端销售/保险/微商（pursue，新增，情绪驱动短链）**：
`ice` 破冰加微 → `need` 需求唤醒 → `trust` 信任建立（晒案例/答疑问）→ `close` 促成 → `repurchase` 复购转介绍。
advanceSignals：主动问价格/效果、讲自身情况、说「再想想」之外的具体疑问、答应小额定金/试用。
retreatSignals：不需要、别发广告了、已买别家、再骚扰拉黑。
taboos：首日刷屏广告、夸大功效/承诺收益、群发话术不称呼、对方婉拒后连续追问、贬低用户现有选择、
未经同意拉群/推名片。
goalTemplates：L2「在{deadline}前促成{name}完成首次购买并愿意保留沟通」。

**⑨ vendor 供应商/乙方管理（maintain，新增，我是甲方）**：
`inquiry` 询价摸底 → `compare` 比价筛选 → `contract` 合同条款 → `delivery` 交付验收 →
`partnership` 长期合作（终点停留）。
advanceSignals：主动给实价/账期方案、接受关键条款、按期交付里程碑、主动提风险和备选。
retreatSignals：推诿延期、「做不了」不给替代、装死不回、偷换材料/范围。
taboos：口头改需求不留痕、压价超底线后又要求加量、验收标准口头化、在对方面前暴露预算上限、
情绪化威胁换供应商。
goalTemplates：L2「在{deadline}前以不超预算的价格锁定{name}的合格交付并写入合同」。

**⑩ networking 贵人/弱关系经营（maintain，新增）**：
`added` 加好友破冰 → `kept` 弱连接保持 → `exchange` 价值交换/帮上忙 → `deep` 深度关系（终点停留）。
advanceSignals：主动点赞评论之外的私聊、接你的话茬、愿意帮忙引荐/解答、主动分享对你有用的信息。
retreatSignals：不通过/通过后零回应、婉拒帮忙、客套疏离（「客气了」「再说」）、朋友圈屏蔽信号。
taboos：加好友立刻有事相求、只在要帮忙时出现、群发祝福/砍一刀、过度攀附套近乎、把引荐当承诺。
goalTemplates：L2「在{deadline}前和{name}建立一次自然的互惠互动（我先提供价值），让对方记得我是谁」。

### 4.4 冲突组

**⑪ adversarial 人际冲突/对立方（defend，新增）**：
`hold` 稳住不激化 → `clarify` 厘清事实固定证据 → `boundary` 划清底线 → `resolve` 谈判/止损/切割（终点停留）。
retreat/激化信号：人身攻击、威胁、造谣扩散、拒绝一切沟通、拉第三方站队。
taboos：情绪对骂、私下妥协不留记录、翻旧账、提前透露底牌与底线数字、在共同社交圈公开冲突细节、威胁式表态。
goalTemplates：L3「在与{name}的冲突中守住利益与体面，不让冲突扩大化」；
L2「在{deadline}前把与{name}的争议落到书面结论或完成干净切割」。

**⑫ complaint 消费投诉/服务维权（defend，新增）**：
`state` 陈述诉求（事实+证据+明确诉求）→ `escalate` 升级施压（客服主管/平台/12315/黑猫）→
`settle` 协商方案 → `close` 收尾确认（退款到账/书面承诺后再撤投诉）。
advanceSignals：客服升级、主动给方案、要求提供账号/凭证（进入处理流程）、确认退款时效。
retreatSignals：以最终解释权拒绝、拖延不回复、要求先撤诉再处理、推诿给第三方。
taboos：辱骂客服个人、未到账先撤投诉、只发情绪不给事实和诉求、接受无书面记录的口头承诺、夸大事实虚构证据。
goalTemplates：L2「在{deadline}前推动{name}就我反映的问题给出可接受的书面解决方案并履行」。

### 4.5 共性约定

- 每包 defaultTone/defaultIntents 复用现有 8 基调 10 意图中的通用项
  （clarify_need/handle_objection/anchor_commit/hold_warmth 等）；defend 类强制叠加包级硬约束文案（StageGate），
  专用意图（hold_line/disengage/fact_anchor/manage_upward 等）列入 P3 意图库；
- goalTemplates 每包至少 1 条 L2；情感/职场/maintain 类配 L3； pursue 短链包（job_hunt）可只配 L2；
- 每包 stages 4~7 个；pathKind=maintain/defend 的终点站为「停留型」，进度条终点站显示完成态徽章而非「下一站」；
- 资产增量约 8~10 KB（压缩后对 APK 体积影响可忽略，不计入 §0 体积估算的代码增量）。

---

## 五、慢车道：GoalPlanner（L3/L2/L1 编译）

### 5.1 触发与作业队列（StrategyRuntime）

@Singleton：`CoroutineScope(SupervisorJob()+Dispatchers.IO)` + 编译 Mutex + inFlight 键集合；
`requestGoalCompile(goalId)` / `requestCardCompile(contactId)`。全部 runCatching，失败只 Log.d
（TAG=`GoalPlanner`/`CardCompiler`），绝不影响回合（降级矩阵 §十）。
幂等：同一目标已有未确认 draft decomp 不重编；重编 version+1，旧 decomp 置 supersededBy 保留审计。

触发点：
1. 目标 Tab 保存手建目标（active/confirmed/source=user）→ enqueueGoalCompile（L3→outcomes；L2→roadmap）；
2. L3 outcomes 成功 → 自动建 ≤2 个 provisional L2（其余候选留 payload，目标页可「采纳」补建）→ 各自编 roadmap；
3. roadmap 成功 → 落 ≤3 个 provisional L1（parentId=L2，仅页面提示）→ 初始化 stage_states 首站 → requestCardCompile；
4. 手动「重新编译」（编辑目标保存 / 卡片审核页按钮）。

### 5.2 新建目标对话框（GoalTab 内 Dialog）

- 层级三选一分段：远期愿景 / 中期目标 / 近期目标；
- 场景选择器：默认 profile.scenarioCode；按 group 四组分段列出 12 包 +「不套用场景」；
- 选定后按 horizon 过滤该包 goalTemplates，chip 点选填入标题（可改）；
- `TemplateFill.render`：`{name}`→联系人显示名；`{aspiration}`→my_aspiration 已确认 label 顿号拼接
（空则「理想中的自己」）；未知占位符（`{deadline}/{objection}`）统一替换「＿＿＿＿」（纯函数+单测）；
- L2/L1：deadline（yyyy-MM-dd 文本框，不引入日期组件）+ 成功判据多行框（可空，planner 补）；
- L1 额外「关联中期目标」下拉（默认该联系人唯一/最近 active L2；无 L2 时禁止建 L1，提示先建 L2）；
- 保存即 §5.1；Toast「已保存，正在后台生成…」。

### 5.3 STRATEGIST（L3→L2 候选+修炼项）

入参：L3 title、双方已确认人设（显示文本）、关系史摘要（contactDirectory.digest 截 600 字）、
my_aspiration、pack（pathKind+group+stages 词表）；`maxTokens=900, temperature=0.4`；
输出 `{"l2":[L2Candidate≤4],"practices":[...]}`；successCriteria 可验证、deadlineDays 14~90、
defend 包 deadlineDays 可短至 7（维权时效）；宽松解析（截首对 `{}`、去围栏）。
落 outcomes decomp v1 + provisional L2（守 ≤2 互斥）。

### 5.4 PLANNER（L2→路线图 + 当前站 L1）

入参：L2 title/successCriteria/deadline、L3 一句、pack.stages（code/label/missionHint/advanceSignals）、
当前 stage_states、pathKind；`maxTokens=700, temperature=0.3`；
输出 `{"stages":[RoadmapStage],"practices":[],"l1":[L1Candidate 1~3]}`；
stage code 越界整项丢弃；缺漏站保留资产 missionHint（UI 以 pack 全量站为骨架，roadmap 覆盖 goal/exitSignals）；
withinDays 1~14。落 roadmap decomp v1 + provisional L1（守 ≤3 互斥，createdAt=now 供 7 天惰性清理）；
stage_states 不存在则插首站。

### 5.5 状态机口径

- 手建=active（亲自输入即确认）；AI 派生=provisional **立即参与回合**（决议 #2）；
- **provisional L2 → 分屏轻确认**（§9.5）：promptCount≥2 无操作→abandoned 回退基线；
  ✓确认→active+confirmed+清零+触发 CardCompiler 重编；
- **provisional L1 → 只在目标 Tab 显示「AI 建议」**（✓采纳/忽略；不弹分屏、不计 promptCount）；
  T1 取数时惰性清理超 7 天未确认者；
- 超限采纳返回 `LIMIT_REACHED`，UI 提示先归档；
- L2 done/abandoned 只由用户操作；failStreak≥3 由 Judge 置 review（黄条不自动改）；
  L2 完成时其下未完成 L1 在目标页提示归档（不自动删）；
- L1 done 由用户勾选（P2；P3 才做 Judge 高置信自动完成建议）。

---

## 六、慢车道：CardCompiler（策略卡）

### 6.1 触发与版本

L2 roadmap 入库后 / 审核页手动重编 / 设置页编全局卡。每次 draft 新行 version+1；
**旧 confirmed 卡持续生效，直到新卡 ✓ 确认**（archiveOthers + profile.activeCardId）。

### 6.2 各段来源（确定性，零 LLM）

| payload 段 | 来源 |
| --- | --- |
| `selfConstraints` | my_aspiration 已确认 label（「成为{label}的人」）+ practices 条目 |
| `theirImplications` | their 已确认字段 impl/implTpl 渲染（salience high→normal，≤8 条）；P2 只进审核卡、不重复注入回合（避免 D5） |
| `topicBank` | topicHook=true 的值 + 自由 tags；**仅 pursue 包生成**；maintain/defend 本段为空 |
| `taboos` | pack.taboos + their taboos 按行 + my my_taboo 按行（去空/去重）；**defend 包的 pack.taboos 排最前，加「冲突期」分组前缀** |

### 6.3 roleLine（唯一 LLM 环节，失败有兜底）

`AuxiliaryTasks.complete(maxTokens=180, temperature=0.4)`；按 pathKind 三套系统提示骨架
（pursue 关系推进 / maintain 长期经营 / defend 冲突止损），统一第二人称、≤60 字、含关系阶段与 1~2 个关键事实、无助手腔。
兜底模板：
- pursue：「你是用户本人，正在和「{name}」聊天；{implication 前两条}。当前按「{stage.label}」节奏推进。」
- maintain：同骨架，尾句「当前重点是把{stage.label}的位置站稳。」
- defend：「你是用户本人，正与「{name}」处理冲突；当前在「{stage.label}」，只谈事实与边界，不激化、不露底牌。」
- 全局卡（contactId=0）：「你是用户本人，以{aspiration}的状态和对方聊天。」

### 6.4 用户改写保留（合并纯函数+单测）

重编时 source=user 的 CardItem 按 text 指纹保留（插到新 ai 列表前部），ai 项全换；
roleLine 用户改过即 user 来源不被覆盖。审核页任何编辑/新增/删除标 user。

---

## 七、快车道纯函数与门控（零 Android，全部 JVM 单测）

### 7.1 PlanStreamFilter（两段式剥离）

标记 `<plan>` / `</plan>`。HEAD 前缀匹配窗口（容忍跨 chunk 的 `<`、`<p`），不匹配立即放行缓冲转 BODY；
PLAN 态 chunk 全部入 hiddenBuffer 不上屏，检索闭标记→截取 plan、剩余转 BODY；
hidden 超 `MAX_PLAN_CHARS=1200` 无闭合→**畸形兜底**：hidden 全部交还可见、plan=null、转 BODY；
`endStream()` 对未闭合同样交还（保证落库消息完整）。
单测 8 例：标准剥离/逐字符切分/开标记前有正文/无标记直通/不闭合超长兜底/CJK/空流/围栏包裹。
解析走 Tactician.parsePlan（去围栏→截首对 `{}`→lenient Json 解码；mission 空或异常→null）。

### 7.2 SignalRules（确定性信号）

- `retreatHits(pack, incomingText)`：pack.retreatSignals 逐条子串匹配；
  **含数字时长的信号（「已读不回超过24小时」等）P2 跳过词表**（本轮无时间戳能力，P3 时序信号补）；
- `advanceCandidates(stage, incomingText, replyText)`：advanceSignals 语义词粗筛（来信/回复命中原词或近义关键字→true），
  仅供 Judge 判断是否值得花一次 LLM；
- defend 包激化信号同走 retreat 通道（§4.4）。单测 4 例。

### 7.3 StagePolicy（P2 线性阈值跃迁）

initial(pack) 首站；bumpHits(json,key) 计数；
maybeAdvance：**不同 signalKey** 命中数 ≥ `max(1, 阶段信号数-1)` → 顺序下一站（pack.stages 顺序，末站返回 null）；
maintain/defend 终点站为停留态（不产生跃迁）；手动改站 always allowed（evidence=「手动设置」）。
单测 5 例：初始化/不足阈值不跳/达标跳下一格/末站停留/跨信号计数区分。
retreat P2 不回站（P3 retreat 边）：只挂降温指令 + failStreak 累计。

### 7.4 StageGate（五道门）

1. 意图门：StyleResolver.render(card,playbook,真实 stageCode)（含 §7.5 跨包兼容）；
2. 动作门：pathKind+stage → 硬约束文案：dating weak/rebuild、sales 两包 early 站禁邀约/报价/逼单；
   job_hunt offer 站前禁下最后通牒；vendor contract 前禁露预算上限；**defend 全包禁拉近关系/禁情绪化措辞**；
   maintain 包禁越界承诺（替人拍板）；映射写本地常量 map（key=`packCode:stageCode`，缺省走 pathKind 默认）；
3. 红线门：redLines(pack,myTaboo,theirTaboos) 聚合去重，永远进【红线】+协议 forbidden；
4. 承诺一致性门：协议固定一句「actions 涉及价格/时间时必须引用既有约定，不得冲突」（配合 P0 到期承诺置顶）；
5. 回退门：retreatHits 非空→【本轮态势】降温段（附命中词；defend 文案为「对方在激化，本轮目标是不升级」）。
单测 4 例：动作门各 pathKind/红线聚合/defend 前置/降温段。

### 7.5 意图门跨包兼容（StyleResolver 小改 + 补单测）

现状过滤会让新 10 包的 stage 把所有带 fitStages 的意图误杀。改为：
`render(card, playbook, stageCode, packStageCodes: Set<String>?=null)`，某意图被过滤当且仅当：
fitStages 非空 **且** packStageCodes 非空 **且** fitStages 与 packStageCodes 有交集（说明该意图对本包声明过门控）
**但**不含当前 stageCode。即：意图从未对本包任何站点声明过门控时，视为通用意图在本包全站点适用。
StrategyBrief 传入当前 pack 的全部 stage code 集合。补 StyleResolverTest 2 例：
新包（colleague）stage 下已选意图不被误杀；dating 包内 weak 站仍正确过滤邀约类意图。

---

## 八、提示词契约（默认档一次调用两段式）

策略激活时 customSystemPrompt 顺序：

```text
PREAMBLE_SECTION（P0 不动）
styleWithLength（P0 不动）
strategySection（新）：
  【你的角色与处境】roleLine
  【当前阶段】{stage.label}：{roadmap.goal 或 missionHint}
  【近期目标】{激活 L1.title}（成功判据：{successCriteria}）   ← 无 L1 整行省略
  【红线（本轮绝对禁止）】…
  【自我约束】…（非空才出）
  【话题素材】…（仅 pursue 包弱互动站）
  【本轮态势】…（仅 retreat 命中）
brief.styleSection（P1，意图按真实阶段+跨包规则过滤）
directionSection（P0）
brief.section（P1；有 active L2 时抑制旧【本次聊天的业务目标】行，避免 D5）
contactSection（P0）
outputProtocol（新）：先输出且只输出一个 <plan>JSON</plan>（MissionPlan 五字段；
  mission 必须服务【近期目标】或当前阶段、受动作门约束、forbidden 与【红线】一致），
  随后空一行直接写回复正文；正文不得复述 JSON
```

---

## 九、快车道接入主链路（ScreenAdvisor）

### 9.1 T1 取数扩展

并行块新增 strategyDeferred（briefDeferred 前置依赖）：
`StrategyBrief.build(contactId, incoming, screenBody): StrategyContext?`
顺序：prefs.strategyMode==OFF 或 contact==null→null；惰性清理 stale L1；读 pack（pathKind/group/stages）+
stage_states + active L2（priority 最高）+ 其下 active L1（priority 最高）→ §9.5 provisional 状态机 →
confirmed 策略卡（activeCardId→联系人最新 confirmed→全局 confirmed；全 null 则无卡）→
Situator.assess；activeGoal/confirmedCard/stage 全 null→null（等同 P1）。
ChatBriefBuilder.build 增参 stageCode、suppressGoal（有 active L2 时抑制旧 goal 行）。

### 9.2 两通道流式剥离 + mission 落库 + 等待提示

collectLeanOnce/collectAdvisorOnce 增参 planFilterEnabled；每回合 new PlanStreamFilter：
- Delta：feed(chunk) 的**可见输出**才进 streaming 与 full；
- **等待提示**：策略激活回合进入流式瞬间发 planning=true，分屏状态栏显示「正在定本轮策略…」
  （float_mission_planning）；最短展示 400ms 防闪烁；filter 向 BODY 放行首个正文字符后清除；
- Done：endStream 兜底后 parsePlan 成功→写 talk_missions（conversationId/contactId/turnMessageId=question.id/
  stageCode/goalId=L1?:L2 id/五字段/createdAt），随后 trim 60；失败不写不报错；
- 落库 assistant 消息只用可见文本；畸形 hidden 在 Done 时补回，气泡完整。

### 9.3 分屏状态流

```kotlin
data class MissionUiState(
    val planning: Boolean=false,
    val missionLine: String?=null,      // 「本轮策略：{mission}｜本轮不做：{forbidden前2条}」
    val expanded: Boolean=false,
    val provisional: ProvisionalPrompt?=null)
data class ProvisionalPrompt(val goalId:Long, val title:String, val remainingShows:Int)
val missionUi: StateFlow<MissionUiState>
fun confirmProvisionalGoal(goalId:Long); fun skipProvisionalGoal(goalId:Long)
fun editProvisionalGoal(contactId:Long); fun toggleMissionExpand()
```

planning/provisional/mission 三态在条上互斥占位（同一时刻最多一个策略 UI）；新回合 prepare 重置。

### 9.4 「本轮策略」只读条

mission 解析成功更新；点击展开 actions/forbidden/signalsToWatch/successWhen 小字；expanded 状态持在 Service（纯展示）。

### 9.5 provisional L2 轻确认（决议 #2）

取数发现需目标且有 provisional L2：promptCount≥2→置 abandoned 本次回退基线；
否则发 ProvisionalPrompt 并 incrementPromptCount（展示即计数）；多条只提示 priority 最高。
✓→active+confirmed+清零+触发 CardCompiler；✎改一下→AdvisorProfileActivity(extras tab=goals) 不增计数；
跳过→+1。问句 float_mission_provisional_q「我理解你这阶段的目标是「%1$s」，对吗？」。
**L1 不进此条**（§5.5）。

### 9.6 Judge 复盘（规则优先 + 节流轻 LLM）

T6（Done 后、consolidationLock 内 persona 萃取之后，零调用）：retreatHits 扫 incoming：
命中→本 mission settle(regressed)+evidence；advanceCandidates=true 仅打标记。

下一回合 T1 内条件性 Judge.evaluate(maxTokens=200,temp=0)：
触发=存在 pending mission 且（距该联系人上次 Judge ≥3 回合 或 advanceCandidates 命中 或 上回合 retreat）。
输入：上轮 mission 五字段+对方最新来信+当前站 advanceSignals；
输出 `{"missionResult","advanceSignals":[原词],"evidence≤40字","progressNote≤60字"}`；
事务：settle；advanceSignals→bumpHits+maybeAdvance（跃迁写新 stage_states+evidence）；
regressed→activeL2.failStreak+1（=3 置 review）；advanced→failStreak=0、progressNote 更新（≤200 字截断）。
失败/withTimeout(12s)：只应用规则结果，语义项下次再试，不阻塞回合。

### 9.7 AdvisorPrefs

strategyMode: Int（OFF=0/SMART=1；ENHANCED=2 常量保留、读到 2 回退 1），默认 1，下一回合生效。

### 9.8 编译调用预算表

| 作业 | 单次（入约/出上限） | 触发 | 活跃联系人月度估算（90 回合/月） |
| --- | --- | --- | --- |
| STRATEGIST | 1.2K / 900 | 每个 L3 一次 | 偶发 2~3 次 |
| PLANNER（含 L1 块） | 1.2K / 700 | 每个 L2 一次 | 偶发 4~6 次 |
| roleLine | 0.6K / 180 | 每卡版本一次 | 偶发 |
| Judge 轻复盘 | 2.2K / 200 | ≥3 回合或有候选信号 | ≤30 次 ≈ 6K 输出 |
| 主回合 plan 前缀 | +400~900 / +150~220 | 每个策略激活回合 | 90 次 ≈ 16K~20K 输出 |

月度增量约 **25K~30K 输出 token**（2/3 是规划前缀；P2 不新增请求数——规划与回复同一次流式调用）。
**不建目标/不绑联系人/关开关 → 两个编译器永不运行，零网络零电量。**

体积/资源影响（实测基线 60.97MB debug 包）：P2 新增代码约 4000 行，APK 压缩体积预计 **+0.8~1.5MB（约 +1.3%~2.5%）**，
release 裁剪后 +0.5~1MB；无新 Gradle 依赖、无新 so；不增 Service/定时器/WakeLock，待机与抓帧功耗与 P1 一致；
稳态内存 +<1MB；存储 5 张小表 + mission 60 条/联系人，典型 +<0.5MB。

---

## 十、降级矩阵

| 故障 | 行为 |
| --- | --- |
| 模式关/无联系人/目标卡阶段全无 | context=null，与 P1 链路完全一致 |
| 新场景包 JSON 解析失败 | 该包不注册其余正常，启动 Log.e 一次 |
| MissionPlan 解析失败 | 全文当普通回复，不写 mission、不报错 |
| `<plan>` 未闭合 | hidden 补还，气泡完整 |
| GoalPlanner/CardCompiler 失败 | 用旧 confirmed 卡或基线；Log.d |
| Judge 失败/超时 | 规则结果照写，语义项跳过 |
| 有 provisional L2 但无卡 | 只用阶段/目标/红线段（roleLine 省略） |
| L1 缺失 | 【近期目标】行省略，mission 服务阶段 missionHint |
| 分屏「改一下」跳转被系统拦 | best-effort startActivity + Toast 引导手动进入画像→目标 |

---

## 十一、UI 细化

### 11.1 AdvisorProfileActivity：详情页 TabRow + extras

详情顶部加「画像 / 目标」TabRow（现有内容整体迁入画像 tab）；
extras：EXTRA_CONTACT_ID:Long、EXTRA_TAB="goals"、EXTRA_GOAL_ID:Long?。

### 11.2 GoalTab.kt（新增）

1. **全局快捷卡**：「我的理想自我 · 默认风格」→ FloatSettingsActivity 策略区（决议 #1）；
2. **远期愿景（L3）** 折叠区：卡片+practices；「+新建远期愿景」；outcomes 未确认时列「AI 建议的中期目标」候选，
   每条【采纳】补建 provisional L2；
3. **中期目标（L2）**：标题/判据/deadline/review 黄条；**阶段进度条**（pack 全站点横条，当前站高亮；
   maintain/defend 终点站为完成态徽章；点站标弹菜单手动改站，evidence=「手动设置」）；
   progressNote+evidence 小字；provisional「观察期」角标；✓确认/✕归档/编辑/完成；达 2 个激活时新建置灰；
4. **近期目标（L1）**：轻量卡（标题+判据+deadline+勾选圈）；provisional 项标「AI 建议」+✓采纳/忽略
   （**不弹分屏**）；7 天未采纳的建议不显示（已惰性归档）；达 3 个置灰；
5. **本回合（只读台账）**：observeRecent(20)，「{相对时间} [结果中文标签] {mission}」；
6. **策略卡入口行**：副标题「草稿待审核 / 已确认 vN / 未生成」→ StrategyCardActivity。

### 11.3 StrategyCardActivity（新增 @AndroidEntryPoint）

入参 contactId（0=全局卡）。roleLine 单行可编辑；四分区（自我约束/对方推论/话题素材/红线）逐行编辑/删除
（ai 项点「改」升 user）+「+加一条」；分区按 pathKind 微调（defend 红线区置顶且默认展开）；
底部【✓确认生效】/【忽略此草稿】/【重新编译】；空态【立即编译】。

### 11.4 分屏策略条（FloatUi 纯 View + Service 接线）

statusBar 与 transcriptContainer 之间插 strategyStrip（默认 GONE），三态互斥：
planning「正在定本轮策略…」；provisional 问句条（✓对 / ✎改一下 / 本次先跳过）；
mission 条（单行省略，点击展开 actions/forbidden/signalsToWatch/successWhen）。

### 11.5 FloatSettingsActivity「我的聊天策略」区

模式分段：关闭 / 智能（默认）/ 增强（置灰「即将推出」）；
「默认策略卡」行（状态副标题→StrategyCardActivity(0)，次按钮【立即编译】）。

### 11.6 场景选择器（两处复用：StyleCardActivity 已有、新建目标对话框新增）

按 group 四组分段（情感关系/职场/商业/冲突维权），组内列包 label；选项全部由资产枚举驱动，
以后新增场景包零代码改动。

### 11.7 Manifest + strings（白名单）

Manifest 追加 StrategyCardActivity（exported=false，照 StyleCardActivity 属性）。
strings 新增 float_goal_*（三档层级/新建/状态/上限/AI建议等）、float_stage_*、
float_strategy_*（模式/卡状态/分区/编译）、float_mission_*（planning/本轮策略/轻确认/五结果/台账）、
float_scenario_group_*（四个组名）。全部中文，格式化 %1$s。

---

## 十二、实施顺序、文件清单与验证

**新增**：`strategy/` 10 文件（§二）；`advisor/GoalRepository.kt`、`StrategyCardRepository.kt`、
`GoalPlanner.kt`、`CardCompiler.kt`、`StrategyBrief.kt`；`floatball/GoalTab.kt`、
`floatball/StrategyCardActivity.kt`；test 下 7 个测试类
（PlanStreamFilter 8 + SignalRules 4 + StagePolicy 5 + StageGate 4 + TacticianParse 5 +
TemplateFill 3 + CardMerge 3 = **32 例**；另 StyleResolver 跨包兼容 +2 例）。

**修改（全 app 自有文件）**：AdvisorEntities/Daos/Database(v5)/AdvisorModule；
scenario_packs.json（+10 包）、StyleAssets（pathKind/group）；StyleResolver（跨包兼容）；
ChatBriefBuilder（stageCode/suppressGoal）；ScreenAdvisor（取数/段落/两通道/missionUi/等待提示/Judge）；
AdvisorPrefs；AdvisorProfileActivity（TabRow+extras）；FloatSettingsActivity；FloatUi；FloatBallService；
AndroidManifest.xml；strings.xml。

**顺序（每步可编译可冒烟）**：
1) 数据层 v5；2) 纯函数+单测（含 StyleResolver 跨包 2 例）；3) Repository；
4) GoalPlanner/CardCompiler/StrategyRuntime；5) 12 包资产 + StyleAssets 解析；
6) StrategyBrief+Situator 取数（先只打日志）；7) 输出协议+两通道剥离+mission 入库+missionUi（含等待提示）；
8) Judge；9) UI（GoalTab→卡片审核页→设置→场景选择器分组→Manifest/strings→分屏条）；10) 全量验证。

**验证**：
1. license 等效扫描 + `:app:assembleDebug` + GetDiagnostics 零错误；
2. `:app:testDebugUnitTest`：新增 34 例 + 现有 41 例全绿；
3. **覆盖安装冒烟（v4→v5 不 clear-data）**：
   - 旧画像/风格/会话完好，Room 无崩溃；策略默认智能；场景选择器四组 12 包齐；
   - 李总（sales_b2b）：新建 L2→roadmap+L1 候选生成、首站破冰、卡片草稿；手动推进到询盘；
     卡片逐句改+重编 user 句保留+✓确认；参谋回合气泡无 JSON 残留、「正在定本轮策略…」闪过后出正文、
     本轮策略行可展开、ice/inquiry 无报价逼单；
   - 新联系人绑 **adversarial**：阶段链 hold→…、卡片红线含冲突期条目、roleLine 为防守口径、回复无拉近关系措辞；
   - 新联系人绑 **workplace_superior**（maintain 代表）：终点站 promoted 显示停留徽章；意图门未误杀已选意图（日志核 styleSection）；
   - dating：建 L3→provisional L2 分屏纸条 ✓/跳过各一次（第三次不再出现→abandoned）；L1 建议仅目标页出现、可勾选完成；
   - retreat（来信「不需要」）：下回合【本轮态势】降温；
   - 模式关：提示词回归 P1 形态；`FATAL|AndroidRuntime` 为空。

---

## 十三、P3 打磨（严格对齐总方案 §13，含本方案承诺的动态路线）

> 总方案只定义到 P3；以下 A 组是本方案为评审问 2 在 P3 内的细化，B 组是总方案既定 P3 项，C 组是 P2 欠账。
> **不设 P4**；更远的想法仅作 §十四 备忘，不立项、不承诺。

### 13.1 A 组：阶段有向图（路线动态性正解）

RoadmapPayload 升级（roadmap decomp 新版本形态，与旧版共存、确认后懒迁移）：

```json
"edges": [
  {"from":"attract","to":"probe","type":"advance","signals":["对邀约暗示给积极回应","主动提及共同空闲时间"],"threshold":2},
  {"from":"attract","to":"friendzone","type":"branch","signals":["明确表示只当朋友"],"auto":false},
  {"from":"probe","to":"attract","type":"retreat","signals":["拒绝","算了"],"threshold":2,"auto":true},
  {"from":"inquiry","to":"hesitate","type":"skip","signals":["直接给出明确顾虑并要求方案"],"auto":"confirm"}
]
```

- `advance`：P2 阈值逻辑泛化，自动；
- `branch` 分叉：**永不自动**，分屏轻确认「对方反馈偏向『朋友区』，切到长线朋友路线吗？」→确认后支线站入图
  （privateStages 随该 roadmap 存储）；
- `retreat` 倒退：退缩信号累计阈值后**自动回一站/进修复支线**，仅分屏一行通知，可一键撤销；
- `skip` 跳级：需 Judge 对信号组合高置信+语义确认，自动跳站+evidence，进度条标「已越过 X 站」可撤销；
- DB v6：stage_states 加 roadmapVersion/pathTag/locked；新表 stage_history（最近 50 条：
  fromStage/toStage/edgeType/evidence/at），GoalTab 阶段条加时间线；
- 手动锁定的站（locked）不被任何自动边移动。

### 13.2 A 组：路线重规划 reroute

触发：failStreak≥3（P2 黄条在 P3 变【换条路线】按钮）、连续 retreat×2、阶段停留超 L2 剩余时长 50% 无 advance。
PLANNER reroute 入参加「失败原因+stage_history+画像变化」，输出新版本 roadmap **必须带
`stageMapping:{旧code:新code|null}`**；轻确认后迁移 stage_states（无映射站回退首站并提示），
旧 roadmap 置 supersededBy 保留审计。

### 13.3 B 组：总方案既定 P3 项

1. 自定义风格的 LLM 行为化编译确认流（启用 style_cards.compiledCustomJson）；
2. 行内纠偏与记忆依据可解释（talk_missions.adoptedIndex 启用）；
3. 画像 staleAfterDays 时效措辞；
4. eval 断言剧本进 `:eval`（总方案 S1~S7 + 新场景越级拦截/红线违规/plan 合法率/reroute 合理性）。

### 13.4 C 组：P2 有意欠账

增强档双调用（Tactician 独立预调用+规则校验+再喂 Responder，设置开放「增强」）；
defend/职场专用意图库（hold_line/disengage/fact_anchor/manage_upward）；
L1 Judge 高置信自动完成建议；salience ≤6 条集合裁剪；时序信号（已读不回 24h，需消息时间戳）；
群聊多对方场景包；maintain 包的职场话题素材源（topicBank 扩充）。

---

## 十四、远期备忘（仅记录想法，不是立项、无任何承诺）

以下来自评审讨论，超出总方案 P0~P3 范围，仅在此留痕，等 P3 验收数据（越级动作率/红线违规率/reroute 合理性）
说话后再决定是否立项：LLM 提议资产外自定义路线（人审私有图）；同一 L2 多路线假设并行与裁决；
Situator/Tactician/Judge 思考链在分屏可见、可对单步纠偏；目标漂移检测（mission 长期偏离 L3 时提醒重写愿景）；
本地策略效果统计反哺提示词（数据不出端）。**本文不赋予它们任何排期或分期编号。**

---

## 十五、风险与对策

- **小模型不按协议输出 plan**：HEAD 前缀窗口直通+未闭合 1200 字兜底，两种失败都退回普通回复；解析截首对 `{}`。
- **首字延迟增加**：规划块 ~150~220 token 在正文前生成；planning 提示（最短 400ms）消解白等感；红线/阶段为确定性预注入不额外调用；Judge 严格节流。
- **阶段误判**：模型无权当场跳阶段；词表只计数、跨信号阈值、语义确认在下回合 Judge；UI 永远可手动改站留痕；P3 自动边全部可撤销。
- **新 12 包措辞不当**：首版保守（红线宁多勿少、defend 建议书面渠道）；纯资产可热修；冒烟覆盖 pursue/maintain/defend 三类代表。
- **意图门误杀**：§7.5 跨包兼容规则 + 2 个单测；新包站点对未声明门控的意图一律放行。
- **provisional 打扰**：L2 最多 2 次分屏提示沉默归档；**L1 零分屏打扰**（只目标页）；条上三态互斥。
- **编译并发/重复烧 token**：Mutex+inFlight 去重+未确认草稿不重编；仅手改目标才重编 roadmap。
- **策略卡与人设重复（D5）**：theirImplications 只审核不注入；taboos/roleLine 为新增信息；有 L2 时抑制旧 goal 行。
- **Room v5 偏差**：DDL 逐列对照、IF NOT EXISTS/ensureColumn 幂等；KSP 缓存损坏按教训 5 删 generated/ksp。
- **存量 v3 goal/style 列**：只读回退不动；新目标确认后旧 goal 行被抑制但永不删除（P-6）。

---

## 十六、实施后勘误（2026-10-16 补，冒烟验收后的与计划出入）

总体：§0 六条拍板边界、12 包清单、五表 v5、四级链、三档上限、两段式协议、降级矩阵**均按计划落地**；
出入仅以下几条，不影响设计结论：

1. **文件名调整**：
   - 计划中的 `strategy/Situator.kt` **未独立建文件**——态势评估（retreat 命中/advance 候选/
     上轮 mission 待裁）落地为 `advisor/StrategyBrief.kt` 的 `assess()`；
   - 计划中的 `strategy/Judge.kt` 落地为 **`advisor/MissionJudgeRunner.kt`**（仍在 `advisor/` 包，
     规则优先 + 下一回合节流轻 LLM 的口径不变）；
   - `strategy/` 子包实际 10 个文件：StrategyRuntime、Tactician、PlanStreamFilter、SignalRules、
     StagePolicy、StageGate、TemplateFill、CardPayloads、StrategyPrompt、StrategyModels。
2. **TemplateFill 正则转义（冒烟实测修复）**：占位符正则在 Android ICU 环境下结尾大括号必须
   显式转义——`Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")`；裸 `}` 在 OpenJDK 单测中合法、
   在设备上直接抛 `PatternSyntaxException` 崩溃。已记入总方案 §6.5 教训与项目记忆。
3. **L1 采纳计数排除自身（冒烟实测修复）**：采纳某条 provisional L1 候选（先建后计数）时，
   上限判断必须排除该条自身，否则 active+provisional 已满 3 条时新候选永远无法采纳。
   GoalDao 新增 `countActiveExcept(contactId, horizon, exceptId)`。
4. **§3.1 goals 实体列序**：落地 DDL 中 `failStreak` 位于 `progressNote` 之前（与本文字段签名
   等价，仅物理列序不同）；以 `AdvisorDatabase.kt` 的 `MIGRATION_4_5` 为唯一准绳。
5. **阶段事件留痕口径澄清**：P2 未向上游记忆双写阶段 EVENT 草稿（本文 §9.6 也只要求写
   stage_states）；跃迁永久留痕在 Advisor 库 `stage_states`（事件型语义、不套 90 天 FACT TTL），
   逐跳 `stage_history` 时间线仍按 §13.1 归 P3（DB v6）。总方案 §9 已据此对齐。
6. **P3 已全量落地**〔2026-10-22 修订〕：§十三（有向图/reroute/P3 既有项/P2 欠账）已由
   [P3 实施计划](P3_有向图阶段机与全量增强_plan.md) 全量实施完成（2026-10-16，12 子项全绿：
   113 测试通过 + 冒烟通过）；§十四远期备忘仍为备忘（未立项）。总方案 §13 已收口、
   声明无 P4。

---

## 十七、实施后勘误二：后续演进（2026-10-22 补）

本文落地的五表 / 12 包 / 四级链 / 两段式协议口径不变；以下为 2026-10-16 之后与本文
设计面有交叉的演进，均已实施、以当前代码为准：

1. **详情页 Tab 2 → 4**（2026-10-21 术语铁律，AGENTS §1.5）：本文 §11.1 的「详情顶部加
   画像/目标 TabRow」为 P2 时点快照；现役详情页为「画像 / 目标 / 参谋会话 / 聊天会话」
   四 Tab——参谋会话 Tab（AdvisorSessionsTab.kt：上游 conversations/messages 按
   session_bindings 归属本联系人渲染，卡片化 + 解除关联/彻底删除双语义弹窗）与
   聊天会话 Tab（ChatChannelsTab.kt：v13 chat_channels 聊天会话行，卡片化 + 删除 +
   ImChatNavigator 直达），两者为 2026-10-21/22 术语铁律与列表增删体系交付物。
2. **术语口径按铁律重述**（AGENTS §1.5）：「会话」一词禁止裸用——**参谋会话**（上游
   conversations/messages + session_bindings 归属关系）/ **聊天会话**（chat_channels 行：
   只记「哪个 APP 的哪个聊天对象」，内容不存储）/ **联系人**（contact_profiles 档案）。
   本文及 P1/P3 中的「会话绑定」类措辞按此口径理解。
3. **绑定与删除面新增**（2026-10-22 列表增删体系）：ChatChannelDao.delete(id)、
   SessionBindingDao.deleteByConversation 与 ContactRepository.deleteChannel /
   unbindConversation；删除当前活跃参谋会话时 ScreenAdvisor.clearConversationAsync +
   conversationId 置 0 自愈重建。上游 conversations/messages 永不触碰。
4. **DB v5 → v13**：P3 升 v6（stage_states roadmapVersion/pathTag/locked 三列、
   stage_history 表、session_bindings.lastIncomingAt）；其后 v7~v13 为后续新特性演进
   （导演 director 四表+补列、素材库 personal_materials、UltraAdvisor v2.2
   self_personas/topic_plans + contact_profiles 七列、隐私闸门 privacyPolicy、聊天策略
   裁量层 talkerStrategyJson/stageIntent、chat_channels 表），与本文五表零冲突、纯增量。
5. **strategy/ 子包与 StyleResolver 演进**：P3 新增 StageGraph.kt（有向图内核，
   autoMove/offers/applyEdge）等文件（文件清单以目录为准）；StyleResolver.render
   增 packStageCodes（本文 §7.5 跨包兼容已落地）与 pathKind（defend 意图门）两参；
   增强档预调用组件为 advisor/TacticalAdvisor.kt（preAnalyze → TacticalBrief，
   失败回退 REASON_ENHANCED_FALLBACK，读档 2 行为退化不变）。
6. **场景与测试规模**：场景包现役 13 个五组（P3 增 group_general / social）；本文 §十二的
   34 例测试已壮大至 app/src/test floatball 相关 58 个测试文件（strategy/ 13 +
   director/ 13 + auto/ 7 + experts/ 6 + advisor/contact/brief 等，含 P3 的
   StageGraphTest / RerouteRulesTest / PersonaSelectTest）。
