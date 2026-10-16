# P1 风格行为化 + 场景包 实施计划（细化版）

> **📌 状态：已实施完成（2026-10-15，覆盖安装冒烟通过）。本文是当时的实施记录，不再代表待办。**
> **架构事实与数字以总方案现状章节为准：
> [`docs/2026-10-15-智能优化增强的聊天策略方案.md`](../../docs/2026-10-15-智能优化增强的聊天策略方案.md)。**
> **阅读本文时须知（后续 P2 已改变的口径，见文末「实施后勘误」）：场景包已从 2 个扩至 12 个四组；
> 灰度门不再是 P2 开工硬门（总方案决议 #5）；场景选择器已按四组分段。**
>
> 依据：`docs/2026-10-15-智能优化增强的聊天策略方案.md` §6.1–6.4、§7、§8.1、§10.2、§13 P1、§14 评审决议 #4。
> 铁律：零上游模块改动（仅白名单 Manifest/strings.xml 追加）；新代码进 app 模块 floatball 包；GPL 头；不 commit。
> 本文在已批准粗方案基础上细化四块：**资产逐字内容 / UI 交互 / 数据与迁移 / 提示词契约**。

---

## 一、仓库调研结论（已逐文件核实，保留）

1. 风格现状：`contact_profiles.style` 自由文本列；画像页 schema 顶层 `stylePresets` chip + 文本框
   （[AdvisorProfileActivity.kt:407-413](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/AdvisorProfileActivity.kt#L407-L413)）；
   ChatBriefBuilder 以 `【我的语言风格】` 一段注入；`ContactRef.style` 由
   [AdvisorContactDirectory.toRef](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/AdvisorContactDirectory.kt#L89-L100) 带出但**当前无消费方**。
2. schema v1：`options: List<String>` 存中文 label；模型 `PersonaFieldDef`。
3. options 消费四处：PersonaExtractor 萃取提示词、FieldEditorDialog、ChatBriefBuilder 渲染、ProfileMemorySync 双写。
4. DB：advisor.db v3，显式迁移 + PRAGMA 幂等补列；DAO 范式见 [AdvisorDaos.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/data/AdvisorDaos.kt)。
5. 资产加载：`PersonaSchemas` @Singleton+@Volatile 缓存；风格/场景沿用此范式（不入库）。
6. UI：Compose + Baic2Theme；设置页档案区已有入口卡范式（337-387 行两张卡）。
7. P0 已完成：customSystemPrompt 首位为规约总纲，段落顺序 `PREAMBLE / styleWithLength / direction / brief.section / contact`。

### P1 边界（不变）
- 做：两份资产、schema v2 + label→code 惰性迁移、style_cards 表（DB v3→4）、StyleResolver、风格卡编辑 UI、场景默认风格配齐。
- 不做（P2/P3）：阶段机运行态与门控、策略卡/目标、Tactician/Judge、画像 salience 集合裁剪与 staleAfterDays 时效、customNote 的 LLM 编译。
- `fitStages`：资产与 Resolver 完整实现过滤，调用处 P1 传 `stageCode=null`。
- 场景 taboos/stages/retreatSignals：资产一次给全并解析，**P1 不注入提示词**（taboos 红线门属 P2；现有 persona `taboos` textarea 仍随人设段原文渲染，不动）。

---

## 二、资产逐字内容

### 2.1 `assets/advisor/style_playbook.json`（新增，完整）

8 个基调（与旧 stylePresets 八词一一对应，降低老用户认知成本）+ 10 个意图（销售/约会两用）。

```json
{
  "tones": [
    { "code": "pro", "label": "专业严谨",
      "voiceRule": "用陈述句和数据说话，少用语气词与表情；称谓用「您」，措辞克制有依据，不下未经证实的结论" },
    { "code": "direct", "label": "简洁直接",
      "voiceRule": "先给结论再补一句理由；每条回复不超过两句，不寒暄铺垫、不用感叹号堆砌" },
    { "code": "warm", "label": "热情亲切",
      "voiceRule": "语气积极有温度，可适度用语气词；先回应情绪再说事，但不空泛客套、不连环追问" },
    { "code": "consult", "label": "顾问式引导",
      "voiceRule": "用一个关键问题引导对方表达需求，再给选项；不替对方做决定，每条建议附带适用前提" },
    { "code": "buddy", "label": "朋友式轻松",
      "voiceRule": "口语短句、像朋友聊天，可用少量表情；不端着不油腻，正事仍然一句话说清" },
    { "code": "empathetic", "label": "先共情后建议",
      "voiceRule": "先用一句话复述并认可对方的情绪或处境，再给建议；对方情绪未平复前不讲道理、不转话题" },
    { "code": "data", "label": "数据与事实优先",
      "voiceRule": "观点后必跟事实、数字或具体案例；不使用「可能、大概、应该」类无依据措辞，拿不准就说明" },
    { "code": "delicate", "label": "高情商委婉",
      "voiceRule": "拒绝或异议先给共同立场再转折（「理解+不过」）；给对方留台阶，不直接否定人、不戳破" }
  ],
  "intents": [ …见下 10 条… ]
}
```

10 意图逐条（`fitStages` 为 null/缺省=不限阶段；阶段码跨包统一，见 2.2）：

| code | label | do | dont | good | bad | fitStages |
| --- | --- | --- | --- | --- | --- | --- |
| `spark_topic` | 制造话题 | ①每条回复结尾保留一个开放式话钩，不用是非问句收尾 ②话题从对方画像（兴趣/家乡/近期动态）取材 ③一次只推进一个话题，对方接住再深入 | ①连续提问不超过2个，杜绝查户口 ②对方未接住时不连发三条追问 | 「对了，你之前提过喜欢 livehouse——这周末有个乐队来，有关注吗？」 | 「在吗？忙吗？吃饭了吗？」 | weak,rebuild,attract,ice,inquiry |
| `hold_warmth` | 维持聊天热度 | ①先共情或复述情绪，再延展观点 ②自然复用对方此前提过的细节 | ①不只回「哈哈/嗯嗯」收尾 ②对方低谷时不硬转话题 | 「周一就被拉去开了一天会，确实顶。后来那个方案过了吗？」 | 「哈哈」「嗯嗯好的」 | （缺省=全部） |
| `show_value` | 引起关注与兴趣 | ①用具体故事或细节展示生活密度/专业度，不自我标榜 ②观点有信息量或趣味，给对方接话空间 | ①不炫耀、不贬低别人抬自己 ②自述不超过三句 | 「上周帮客户救了个急，半夜改方案改到三点——结果发现就差一行配置。」 | 「我这人吧特别优秀，追我的人可多了」 | attract,probe,inquiry,compare |
| `push_close` | 推进邀约/成交 | ①给具体时间或地点/方案的二选一，不问「要不要」 ②行动前先用一句共同语境铺垫 | ①信号不足时绝不提前使用（阶段门控） ②不在对方犹豫时连续逼两次 | 「那这周六晚饭还是周日下午茶？我把位子先订上。」 | 「那你到底要不要买？给个准话」 | probe,confirm,push,deal |
| `comfort_support` | 共情安抚 | ①先承认情绪合理，再提供一种具体的小帮助或陪伴选项 ②语速放慢、句子变短 | ①不讲大道理、不比较「这算什么」 ②不顺势推销或提要求 | 「被放鸽子确实窝火。要不要吐槽会儿，我听着；不想说就先早点歇。」 | 「这有什么大不了的，别矫情」 | attract,probe,hesitate,after |
| `clarify_need` | 探询真实需求 | ①每次只澄清一个关键变量（预算/时间/偏好/顾虑） ②问题后给出你猜测的两个选项让对方选 | ①不连续盘问、不问能用选项解决的开放大题 | 「你更在意价格还是售后响应？预算大概卡在五千上下还是更高？」 | 「你有什么需求？详细说说」 | rebuild,ice,inquiry,compare |
| `handle_objection` | 化解顾虑 | ①先复述对方顾虑表示听懂，再针对该点给一个事实或案例 ②把选择权交还对方，不硬推 | ①不回避问题、不贬低对方顾虑 ②一次只解一个顾虑 | 「担心实施没人接是吧？我们这边前两周远程陪跑，有问题当天响应。」 | 「你放心，绝对没问题的，我们大公司」 | compare,hesitate,probe |
| `anchor_commit` | 锚定承诺与下一步 | ①把口头共识落成具体时间/动作并复述确认 ②承诺事项同步进待办口径，回复里不改动历史报价 | ①不模糊成「回头再说」 ②不在未确认需求前先承诺交付 | 「那就这么定：本周五前我把报价单发你，下周二电话过细节。」 | 「好的好的，回头联系」 | confirm,push,deal |
| `light_tease` | 轻松调侃 | ①只就事论事自嘲或调侃具体行为，带善意 ②调侃后立刻给一个正向台阶 | ①不拿外貌/年龄/家庭/收入开玩笑 ②对方语气认真时立即收住 | 「可以啊，嘴上说随便，点起菜来比谁都专业。那听你的。」 | 「你这么挑怪不得单身」 | rebuild,attract,probe |
| `graceful_exit` | 体面收尾留白 | ①对方忙碌或冷淡时主动短收，留一个轻话钩下次接 ②语气松弛不追问原因 | ①不发小作文、不质问为什么不回 ②不连续补消息 | 「你先忙，回聊～ 对了那家店我收藏了，改天再说。」 | 「人呢？怎么不回？是不是生气了？」 | weak,rebuild,hesitate,after |

> 意图上限：UI 多选 ≤5 个（防止 do/dont 堆叠稀释）。

### 2.2 `assets/advisor/scenario_packs.json`（新增，完整）

阶段码统一词表（dating 与 sales 各自独立；sales 与现有 intentStage 七概念对齐）：
`weak→rebuild→attract→probe→confirm`；`ice→inquiry→compare→hesitate→push→deal→after`。

```json
{
  "packs": [
    {
      "code": "dating",
      "label": "恋爱/暧昧",
      "relationTypes": ["暧昧对象", "朋友"],
      "defaultTone": "buddy",
      "defaultIntents": ["spark_topic", "hold_warmth", "show_value"],
      "goalTemplates": [
        { "horizon": 3, "text": "和{name}走向稳定、自然的亲密关系；同时我成为{aspiration}的人" },
        { "horizon": 2, "text": "在{deadline}前完成一次自然的线下邀约（吃饭/看展），见面互动不冷场" }
      ],
      "stages": [
        { "code": "weak", "label": "弱联系",
          "advanceSignals": ["主动发起过话题", "连续两次回复不少于2句"],
          "missionHint": "无压力刷存在，单话题轻互动，不邀约" },
        { "code": "rebuild", "label": "重建对话",
          "advanceSignals": ["主动追问一次", "回复带情绪词"],
          "missionHint": "用共同记忆或新话题钩子重启对话" },
        { "code": "attract", "label": "吸引/舒适",
          "advanceSignals": ["主动开启新话题", "私聊延续超过3天"],
          "missionHint": "展示生活密度与情绪稳定，建立舒适感" },
        { "code": "probe", "label": "试探邀约",
          "advanceSignals": ["对邀约暗示给积极回应", "主动提及共同空闲时间"],
          "missionHint": "轻试探线下意愿，不给压力" },
        { "code": "confirm", "label": "敲定见面",
          "advanceSignals": ["确认具体时间或地点"],
          "missionHint": "二选一落定时间地点" }
      ],
      "retreatSignals": ["拒绝", "算了", "呵呵", "已读不回超过24小时"],
      "taboos": ["查户口式连续提问", "深夜连续试探", "提前任/查岗", "弱联系阶段发起邀约"]
    },
    {
      "code": "sales_b2b",
      "label": "B2B销售",
      "relationTypes": ["客户", "潜在客户"],
      "defaultTone": "pro",
      "defaultIntents": ["clarify_need", "handle_objection", "anchor_commit"],
      "goalTemplates": [
        { "horizon": 2, "text": "推动{name}在{deadline}前完成签约/复购，关键障碍是{objection}" }
      ],
      "stages": [
        { "code": "ice", "label": "破冰", "advanceSignals": ["对方回应并表明身份/需求"],
          "missionHint": "建立信任，确认对接人与业务背景" },
        { "code": "inquiry", "label": "询盘", "advanceSignals": ["对方说出具体需求或预算"],
          "missionHint": "澄清需求与决策链，不急于报价" },
        { "code": "compare", "label": "比价", "advanceSignals": ["提到竞品或要求报价"],
          "missionHint": "用差异点与事实回应比较，可给报价" },
        { "code": "hesitate", "label": "犹豫", "advanceSignals": ["提出具体顾虑", "同意进入下一步沟通"],
          "missionHint": "逐点化解顾虑，共情优先" },
        { "code": "push", "label": "促单", "advanceSignals": ["口头认可方案", "同意走流程"],
          "missionHint": "锚定下一步时间与动作，二选一促成" },
        { "code": "deal", "label": "成交", "advanceSignals": ["合同/付款确认"],
          "missionHint": "闭环确认交付与账期，不画蛇添足" },
        { "code": "after", "label": "售后", "advanceSignals": ["续约/增购信号"],
          "missionHint": "兑现承诺，经营复购与转介绍" }
      ],
      "retreatSignals": ["不需要", "已选别家", "以后再说", "预算冻结"],
      "taboos": ["未确认预算前报价", "对已转意向客户继续强调痛点", "凭空改价或食言"]
    }
  ]
}
```

### 2.3 `persona_schema.json` v2（重写，全量字段）

规则：
- 现有全部 key 保留，仅 enum 的 `options` 从字符串数组升级为对象数组；tags 字段保持无 options（自由值不加 `~`，见 §五.3）。
- 新字段属性：`allowCustom`（默认 true，显式 false 的仅 genderHint/ageRange/decisionRole/replyPace/emotionStyle 这类客观项）、`salience`（商机/性格沟通默认 high；院校/家乡等 low）、`impl`（仅 enum 选项）、`implTpl`（text/textarea 可选）。
- 新增 5 字段（§2.4）；删除顶层 `stylePresets`（风格改由 style_cards 承载）。

#### label→code 全量映射表（迁移与萃取归一的唯一字典）

| field | label 顺序 | code 顺序 |
| --- | --- | --- |
| genderHint | 男 / 女 | male / female |
| ageRange | 20以下 / 20-29 / 30-39 / 40-49 / 50+ | under20 / 20s / 30s / 40s / 50plus |
| seniority | 新人 / 执行层 / 主管 / 经理 / 总监 / 老板/决策人 | junior / executor / supervisor / manager / director / boss |
| education（及 my_education） | 高中及以下 / 大专 / 本科 / 硕士 / 博士 | high_school / college / bachelor / master / phd |
| commStyle | 直接干脆 / 委婉客气 / 理性分析 / 热情健谈 / 惜字如金 | direct / polite / analytical / talkative / terse |
| formality | 称您/正式 / 称你/随意 / 看场合 | formal / informal / adaptive |
| humorStyle | 接受玩笑 / 只接受轻松 / 保持严肃 | playful / light / serious |
| relationType | 客户 / 潜在客户 / 同事 / 上级 / 合作方 / 朋友 / 家人 / 暧昧对象 / 其他 | customer / lead / colleague / superior / partner / friend / family / crush / other |
| decisionRole | 决策人 / 影响人 / 执行人 / 把关人 / 未知 | decider / influencer / executor / gatekeeper / unknown |
| intentStage | 破冰 / 询盘 / 比价 / 犹豫 / 促单 / 成交 / 售后 | ice / inquiry / compare / hesitate / push / deal / after |

impl 文案（高 salience 选项，进提示词；其余选项 impl 留空字符串即可）：
- commStyle：direct=「对方要结论不要铺垫：先给答案、一句话说清」；terse=「对方话少是习惯不是冷淡：每次不超过两句、不连环提问填空，她短句回应就换话题」；polite=「对方重分寸：措辞客气、先征询再推进」；analytical=「对方重逻辑：给理由和依据，不打感情牌」；talkative=「可多给信息与话题，但仍先答其所问」。
- decisionRole：decider=「拍板人：对话要给结论与投入产出」；influencer=「影响者：提供便于他内部转述的要点」；executor=「执行人：讲清流程、操作与配合事项」；gatekeeper=「把关人：先解决合规/风险疑虑」。
- intentStage 七项 impl 直接取 2.2 对应 sales 阶段 missionHint。
- emotionStyle（新字段）：expressive=「情绪外露：接住她的情绪浓度回应」；warm_slow=「慢热：不要求即时热情，稳定出现比猛攻有效」；avoidant=「回避型：少逼问、给空间，用轻松低压方式保持联系」。
- replyPace（新字段）：instant=「在线时节奏可快，但仍不连发」；scheduled_active=「按其活跃时段发消息，非活跃时段不期待秒回」；hours_gap=「话题设计成可隔数小时自然续接，不催促」。
- 其余客观字段（gender/age/education/relationType）impl 留空。

### 2.4 新增 5 字段

| owner | key | label | type | allowCustom | options（label→code） | salience |
| --- | --- | --- | --- | --- | --- | --- |
| my | my_aspiration | 我的理想自我 | tags | true | 情绪稳定 calm_emotional / 有边界感 has_boundary / 不讨好 non_pleasing / 幽默有料 witty / 专业可靠 professional / 真诚直接 genuine / 从容松弛 relaxed / 上进有目标 ambitious | normal |
| my | my_relation_goal_default | 默认关系愿景 | enum | true | 长期伴侣 life_partner / 稳定暧昧 stable_casual / 普通朋友 friend / 客户信任 client_trust / 师长印象 mentor_impression | normal |
| their | emotionStyle | 情感表达 | enum | false | 外放 expressive / 慢热 warm_slow / 回避型 avoidant / 未知 unknown | high |
| their | replyPace | 回复节奏 | enum | false | 秒回 instant / 时段性活跃 scheduled_active / 常隔数小时 hours_gap | normal |
| their | loveLanguage | 吃哪种好感方式 | tags | true | 被赞美 praise / 被倾听 listening / 实用帮助 practical_help / 共同体验 shared_experience / 幽默感 humor | high（约会包话题钩子，topicHook=true） |

---

## 三、数据与迁移细化

### 3.1 Room 实体与 DDL

`StyleCardEntity`（并入 [AdvisorEntities.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/data/AdvisorEntities.kt)）：

```kotlin
const val STYLE_SCOPE_MY_DEFAULT = "my_default" // contactId 固定 0
const val STYLE_SCOPE_CONTACT = "contact"

@Entity(
    tableName = "style_cards",
    indices = [Index(value = ["scope", "contactId"], unique = true)],
)
data class StyleCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val scope: String,
    val contactId: Long = 0L,
    val toneCode: String? = null,            // null=未选基调
    val intentCodes: String = "[]",          // JSON 数组，资产 intent code，保存顺序=展示顺序
    val customNote: String? = null,          // 用户手写一句补充（P1 原文生效）
    val compiledCustomJson: String? = null,  // P3 预留，P1 恒为 null
    val confirmed: Boolean = true,           // P1 纯选项+原文即生效
    val updatedAt: Long = 0L,
)
```

`ContactProfileEntity` 追加 `val scenarioCode: String? = null`（尾部追加，默认 null）。

DAO（并入 AdvisorDaos.kt）：

```kotlin
@Dao
interface StyleCardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(card: StyleCardEntity): Long
    @Query("SELECT * FROM style_cards WHERE scope=:scope AND contactId=:contactId")
    suspend fun find(scope: String, contactId: Long): StyleCardEntity?
    @Query("SELECT * FROM style_cards WHERE scope=:scope AND contactId=:contactId")
    fun observe(scope: String, contactId: Long): Flow<StyleCardEntity?>
    @Query("DELETE FROM style_cards WHERE scope='contact' AND contactId=:contactId")
    suspend fun deleteContactCard(contactId: Long)   // 联系人卡"恢复跟随默认"
}
// PersonaFieldDao 追加：
@Query("SELECT * FROM persona_fields") suspend fun listAll(): List<PersonaFieldEntity>
// ContactProfileDao 追加：
@Query("UPDATE contact_profiles SET scenarioCode=:code, updatedAt=:now WHERE id=:id")
suspend fun setScenario(id: Long, code: String?, now: Long)
```

`MIGRATION_3_4`（AdvisorDatabase.kt；version 3→4；实体注册 StyleCardEntity）：

```sql
CREATE TABLE IF NOT EXISTS `style_cards` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `scope` TEXT NOT NULL,
  `contactId` INTEGER NOT NULL DEFAULT 0,
  `toneCode` TEXT,
  `intentCodes` TEXT NOT NULL DEFAULT '[]',
  `customNote` TEXT,
  `compiledCustomJson` TEXT,
  `confirmed` INTEGER NOT NULL DEFAULT 1,
  `updatedAt` INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_style_cards_scope_contactId`
  ON `style_cards` (`scope`, `contactId`);
-- + 复用既有 ensureColumn 范式：
ALTER TABLE contact_profiles ADD COLUMN scenarioCode TEXT;  -- ensureColumn 幂等
```
AdvisorModule 改 `.addMigrations(MIGRATION_2_3, MIGRATION_3_4)`。**SQLiteMigration 内不做 enum 值搬运**。

### 3.2 旧 style 列读写规则（不删列、不双轨写）

- **读（回退链）**：联系人卡 → 全局卡（my_default）→ `profile.style` 旧原文（渲染为单行兜底段）→ 省略。
- **写**：新 UI 一律写 style_cards；联系人详情「保存」按钮改为只存 goal（Repository 新增 `updateGoal`），
  旧 `updateGoalStyle` 仅保留给合并逻辑（[ContactRepository.mergeContacts:480-484](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/advisor/ContactRepository.kt#L480-L484) 不动）。
- `ContactRef.style` 字段保留不动（零侵入调用方），ChatBriefBuilder 不再经它取风格。
- 旧 style 原文不做 tone 自动映射（八个词与 tone label 虽近似，但避免误判；回退段按原文展示即可）。

### 3.3 label→code 惰性迁移（PersonaValueMigrator）

- 触发：`AdvisorProfileActivity.onCreate` 与 `StyleCardActivity.onCreate` 各调一次
  `migrator.runOnce()`；@Singleton 内 Mutex + 进程内 @Volatile 防重入。
- 版本门：SharedPreferences 文件沿用 `"advisor_data"`，key `persona_value_codes_version`，值 1。
- 算法（单 Room 事务）：
  1. `personaFieldDao.listAll()` 全量；schema 中 `options != null` 的字段建 `(key → def)` 映射；
  2. enum：`PersonaValueCodes.migrateSingle(def, value)`；tags（仅 loveLanguage/my_aspiration 有 options）：
     decodeTags 逐元素迁移后重新 encode；无 options 的自由 tags（interests/personality 等）**整行跳过**；
  3. 值发生变化的行 upsert（updatedAt 不变——拷贝原实体只改 value）；
  4. 转换行数打 `Log.d("PersonaMigrator", "converted N/M rows")`；异常 runCatching 不抛、不置版本位（下次再试）。
- 幂等：已是合法 code / `~` 前缀 / 空串 → 原样返回；成功后置版本位。
- 回滚：无破坏性（label 与 code 可逆映射）；卸载重装走 assets 重建；不提供反向迁移。

### 3.4 PersonaValueCodes 纯函数 API（app/src/test 可单测，零 Android 依赖）

```kotlin
internal object PersonaValueCodes {
    const val CUSTOM_PREFIX = "~"
    fun isCode(v: String): Boolean                       // 去前后缀后全匹配 [a-z0-9_]+
    fun customValue(raw: String): String                 // "~" + raw.trim()
    fun storedOption(labelOrCode: String, def): String   // 编辑器存：label/code→code，其余→~raw
    fun displaySingle(def, stored: String): String       // code→label；~x→x；旧label→label；其他原值
    fun displayValue(def, stored: String): String        // tags 逐元素 displaySingle 后顿号拼接；enum 同 single
    fun normalizeExtracted(def, raw: String): String?    // label/code→code；未知：allowCustom=false→null，否则 ~raw
    fun migrateSingle(def, stored: String): String       // 幂等迁移
}
```
判定顺序（避免歧义）：先精确匹配 code → 再精确匹配 label → 再 custom 处理。
萃取侧 tags 归一：按顿号/逗号 split → 逐元素 normalizeExtracted（null 丢弃）→ encodeTags。

---

## 四、UI 交互细化

### 4.1 StyleCardActivity（新增，Compose，@AndroidEntryPoint）

入参：`EXTRA_CONTACT_ID: Long`（默认 0=全局模式；>0=联系人模式）。注入 StyleCardRepository、StyleAssets、
PersonaSchemas（预览不需要）、ContactRepository（取联系人名/场景）。

页面纵向结构（Baic2Theme，PageScaffold 同款滚动列，宽 ≤560dp）：

```
┌ 顶栏：标题「默认说话风格」(全局) /「{name} 的说话风格」(联系人) + 返回
├─[联系人模式才有] 场景区 Surface 卡片
│   「适用场景：恋爱/暧昧 ▾」  副行「切换场景会用其默认风格覆盖当前选择」
│   下拉项：不套用 / 恋爱·暧昧(dating) / B2B销售(sales_b2b)
│   切换时弹 AlertDialog：「将载入该场景的默认基调与意图，覆盖当前未保存的选择」【取消】【载入】
│   载入=本地 setState（不落库，点保存才写）；同时保存后写 contact_profiles.scenarioCode
├─ 基调区 SectionTitle「基调（单选）」
│   FlowRow 8 个 FilterChip：tone.label；单选再点取消；选中高亮
├─ 意图区 SectionTitle「本轮意图（多选，≤5 个）」
│   FlowRow 10 个 FilterChip；选中数=5 时其余置灰（视觉 disable，点已选项可取消）
│   每个已选意图下方/展开卡（按选中顺序）：
│     ┌ intent.label
│     │ ✓ do 各一条（绿点前缀，用 MaterialTheme 颜色不硬编码）
│     │ ✕ dont 各一条（红点）
│     └ ▸ 示例（点击展开 good/bad 两行斜体小字）
├─ 自定义补充 OutlinedTextField（2 行，maxLength 60 硬截断计数）
│   placeholder「例：她加班时别讲大道理，先问吃饭没」
├─ 提示词预览 Surface（surfaceContainer 底）：实时显示 StyleResolver.render 的纯文本（等宽/正文小字）
│   空态：「尚未选择基调或意图，将使用基础参谋风格」
├─ 按钮行：【保存】（主按钮，toast「已保存」后 finish）
│   全局模式：无次按钮；
│   联系人模式：次按钮【恢复跟随默认风格】→ AlertDialog 确认 → deleteContactCard + 清 scenarioCode（可保留？
│     决定：场景与风格卡独立，恢复风格不动 scenarioCode）→ finish
```

交互细节：
- 所有选择只存内存 state，离开页（返回）不保存；保存时一次 upsert（updatedAt=now）。
- 载入场景默认值：tone=pack.defaultTone、intents=pack.defaultIntents（已在资产中）。
- intentCodes 存储顺序 = 用户最后点选顺序（用 LinkedHashSet 维护）。
- 已保存但资产中失效的 code（未来资产改版）：渲染时跳过，UI chip 不勾选，不报错。

### 4.2 AdvisorProfileActivity 改造

**联系人详情 ContactDetailScreen**：
- 删除 407–413 行 style SectionTitle/FlowChips/LabeledField；保存按钮的 `updateGoalStyle(contactId, goal, style)`
  改为 `updateGoal(contactId, goal)`（Repository 新增）。
- 在 goal 字段下新增「说话风格」入口 Surface 卡（仿设置页卡片）：
  主标题 float_style_entry；副行动态文案：
  - 有联系人卡：`{tone.label} · {intent1.label}、{intent2.label}…`（tone 空则只列意图；customNote 非空追加「+自定义」）
  - 无联系人卡有全局卡：「跟随默认风格：{全局摘要}」
  - 都没有：「未设置（使用基础风格）」
  点击 → `Intent(context, StyleCardActivity::class.java).putExtra(EXTRA_CONTACT_ID, contactId)`。
  卡片数据用 StyleCardDao.observe(scope,id) + observe 全局卡（两个 flow combine）。
- 画像字段编辑（FieldEditorDialog）v2：
  - enum：选项 chips 显示 **label**；`allowCustom=true` 时末尾加「✎ 自定义…」chip，
    点击切换为 OutlinedTextField +「✓ 完成」chip（切回 chip 模式，输入非空成为选中值，描边样式）；
    存储：选项→code，自定义→`~原文`。
  - tags：常用项 chips（label，点选切换，选中态）；下方输入框 +「添加」，回车=添加自定义 chip
    （选项 label 点击存 code；自定义存原文，无 options 字段则不加 ~）；已选值：code→label 回显，
    `~x`/自由值→原文，自定义 chip 用 outlined 描边区分；点 chip 的 ✕ 移除。
- FieldRow 值显示改走 `PersonaValueCodes.displayValue(def, entity.value)`（替换 storedValue 直出）。
- onCreate 路径：ProfileNav 外层 LaunchedEffect(Unit) 调 `personaValueMigrator.runOnce()`（注入新增字段）。

**我的人设 MyPersonaScreen**：
- 顶部（分组字段之前）加「默认说话风格」入口卡（同上，固定 contactId=0）；
- my_aspiration / my_relation_goal_default 随新 schema 自动以 FieldRow 出现（无需专门 UI），
  onSave 仍走 setUserField + syncMyPersonaField（双写内容已是 label，见五.4）。

### 4.3 FloatSettingsActivity 入口

档案区「联系人画像」卡（337-361 行）**之前**新增一张「默认说话风格」卡：
`float_settings_style`=「默认说话风格」；hint=float_settings_style_hint=「基调与意图，对所有联系人生效，可按人单独覆盖」；
点击 → StyleCardActivity（不带 extra 即 0）。

### 4.4 Manifest 与 strings（白名单追加）

Manifest：在现有 advisor activity 声明旁追加
`<activity android:name=".floatball.StyleCardActivity" android:exported="false" android:theme="@style/Theme.Baic2"/>`
（theme 与 AdvisorProfileActivity 现有声明对齐，实施时照抄其属性）。

strings.xml 新增键（中文，`%1$s` 格式化）：
`float_style_entry`「说话风格」、`float_settings_style`「默认说话风格」、
`float_settings_style_hint`、`float_style_tone`「基调（单选）」、
`float_style_intents`「本轮意图（最多 5 个）」、`float_style_custom`「一句补充要求（可选）」、
`float_style_custom_hint`、`float_style_preview`「提示词预览」、`float_style_preview_empty`、
`float_style_save`、`float_style_saved`「风格已保存」、`float_style_follow_default`「恢复跟随默认风格」、
`float_style_follow_default_confirm`、`float_style_none`「未设置（使用基础风格）」、
`float_style_following_default`「跟随默认风格：%1$s」、`float_style_custom_badge`「自定义」、
`float_scenario_title`「适用场景」、`float_scenario_none`「不套用场景」、
`float_scenario_overwrite_title`、`float_scenario_overwrite_msg`、
`float_style_example`「示例」。

---

## 五、提示词契约细化

### 5.1 【说话方式】段精确模板（StyleResolver.render 输出）

输入：StyleCardEntity（或旧 style 兜底）、StylePlaybook、stageCode: String?（P1 恒 null）。

有风格卡时：

```text
【说话方式】
基调：{tone.label}。{tone.voiceRule}
{对每个 fitStages 含 stageCode（或 fitStages 为空）的已选意图，按 intentCodes 顺序、资产序兜底}
意图「{intent.label}」：要{do①}；{do②}。不要{dont①}；{dont②}。
{customNote 非空时单独一行}
其他要求：{customNote 原文}
```

- 无效 toneCode/intentCode 静默跳过；过滤后若 tone 与意图与 customNote 全空 → 返回 null。
- `fitStages` 语义：null/缺省=全部阶段适用；非空且 stageCode≠null 且不含 → 该意图不渲染。
- 旧 style 原文兜底（无卡时）：`【说话方式】\n{style原文}`（标题从旧「我的语言风格」改名，正文不动）。

### 5.2 ChatBriefBuilder 改造与最终拼接顺序

`Brief` 数据类调整为：
```kotlin
data class Brief(
    val section: String?,        // 业务目标 + 双方人设 + 话术（不再含风格）；全空=null
    val styleSection: String?,   // StyleResolver 输出；无卡无旧值=null
    val curatedTexts: List<String>,
)
```
build() 内：
- 删除 `SECTION_STYLE` 拼装与 style 变量读取；并行内改读 StyleCardRepository.cardFor(contactId)
  + StyleAssets.playbook，`styleSection = StyleResolver.render(card, playbook, stageCode=null)`
  或旧 profile.style 兜底（注意 profile 来源是 contacts.profile(contactId)）。
- 人设渲染（renderPersona）：每行值改 `PersonaValueCodes.displayValue(def, entity.value)`；
  enum 选中项若有非空 `impl`，行尾追加「（{impl}）」；text/textarea 字段有 `implTpl` 时按
  `implTpl.replace("{v}", 显示值)` 替换后追加；无 impl 不追加。
- collectCurated：去重指纹原料改取**显示文本**（code→label、~x→x），保证与 PromptDedup 指纹语义一致。

ScreenAdvisor `customSystemPrompt` 最终顺序（仅在 listOfNotNull 中插入 brief.styleSection）：

```text
PREAMBLE_SECTION（规约总纲，P0）
styleWithLength（ADVISOR_STYLE_BASE + 长度档，P0）
brief.styleSection（说话方式，新增插入位）
directionSection（即时方向，P0）
brief.section（业务目标/人设/话术）
contactSection（交往脉络，P0）
```
直连 leanSystemPrompt 与 AgentLoop 两通道共用同一 custom，无需额外改动。

### 5.3 画像值存储/显示/萃取/双写四口径

| 环节 | 形态 | 示例 |
| --- | --- | --- |
| DB 存储（enum） | code 或 `~自定义` | `terse` / `~有点高冷` |
| DB 存储（有码表 tags） | JSON 数组，元素 code/`~x` | `["listening","~喜欢手写便签"]` |
| DB 存储（无码表 tags：interests 等） | 原文 JSON 数组，不加 `~` | `["livehouse","徒步"]` |
| UI 显示 | 一律 label/原文 | `惜字如金` / `有点高冷` |
| 萃取落库 | label→code；自造值 allowCustom 时 `~x` 否则丢弃 | 模型回「直接干脆」→`direct` |
| 上游双写（ProfileMemorySync） | 写前 displayValue 成人话 | `我的沟通风格：惜字如金` |

PersonaExtractor 改动点：systemPrompt 目录行仍展示 label（保萃取质量），文案补一句
「可选值必须从给定选项中原样选取；不在选项中的值只有字段允许时才填你的原词」；
parse 过滤链中对 enum/tags 值调 PersonaValueCodes 归一，归一为 null 的草稿丢弃。

ProfileMemorySync 改动点：`syncMyPersonaField` 与 `syncTheirField` 渲染 value 的位置统一过
`PersonaValueCodes.displayValue(def, rawValue)`（tags 内部已处理顿号），其余逻辑不动。

### 5.4 不进 P1 提示词的东西（明确边界）
- scenario_packs 的 stages/advanceSignals/missionHint/taboos/retreatSignals：只解析不注入。
- salience 的「≤6 条相关行」裁剪、staleAfterDays 时效措辞：P3/P2，schema 字段先占位（KDoc 注明）。
- customNote 的 LLM 行为化编译（compiledCustomJson/confirmed 流）：P3；P1 原文一行注入。
- 风格段不参与 PromptDedup 事实指纹（指令性文本）。

---

## 六、文件清单（最终）

**新增**：`assets/advisor/style_playbook.json`、`assets/advisor/scenario_packs.json`、
`advisor/StyleAssets.kt`、`advisor/StyleResolver.kt`、`advisor/StyleCardRepository.kt`、
`advisor/PersonaValueCodes.kt`、`advisor/PersonaValueMigrator.kt`、
`floatball/StyleCardActivity.kt`、
`app/src/test/kotlin/.../floatball/StyleResolverTest.kt`、`PersonaValueCodesTest.kt`。

**修改**：`assets/advisor/persona_schema.json`、`advisor/PersonaSchema.kt`、
`advisor/PersonaExtractor.kt`、`advisor/ChatBriefBuilder.kt`、`advisor/ProfileMemorySync.kt`、
`advisor/ContactRepository.kt`（+updateGoal）、`advisor/data/AdvisorEntities.kt`、
`advisor/data/AdvisorDaos.kt`、`advisor/data/AdvisorDatabase.kt`、`advisor/AdvisorModule.kt`、
`AdvisorProfileActivity.kt`、`FloatSettingsActivity.kt`、
`app/src/main/AndroidManifest.xml`、`app/src/main/res/values/strings.xml`。

## 七、实施顺序（依赖序）

1. persona_schema.json v2 + PersonaSchema.kt 数据类（FieldOption/allowCustom/salience/implTpl/staleAfterDays）
2. PersonaValueCodes + 单测 → 3. PersonaValueMigrator → 4. Extractor/UI 编辑/渲染/双写四处接线
5. style_playbook.json + StyleAssets → 6. scenario_packs.json（同文件加 ScenarioPacks 加载器）
7. DB v4（实体/DAO/迁移/Module）→ 8. StyleCardRepository + StyleResolver
9. ChatBriefBuilder（Brief 三字段、impl 追加、风格回退链）+ ScreenAdvisor 插入 styleSection
10. StyleCardActivity → 11. 两处入口 + Manifest + strings
12. StyleResolverTest（完整渲染/全空/无效码跳过/customNote/fitStages 过滤与 null 不过滤/旧 style 兜底，7 例）
    + PersonaValueCodesTest（code/label/~ 互转、tags 混合、未知值 allowCustom 真假分支、幂等迁移，10 例）

## 八、验证

1. license 门禁（WSL bash 异常时 PowerShell 等价扫描）；
2. `./gradlew :app:assembleDebug`（JDK17）；
3. `./gradlew :app:testDebugUnitTest`（新 17 例 + P0 23 例全绿）；GetDiagnostics 零错误；
4. 模拟器冒烟（**覆盖安装不 clear-data**，验旧库升级）：
   - 冷启动无崩溃；打开画像页后 logcat 见 `PersonaMigrator converted …`，旧 enum 行显示仍为中文 label；
   - 设置页→默认说话风格：选「朋友式轻松」+制造话题/维持热度，预览含 voiceRule 与 do/dont，保存；
   - 联系人详情：风格卡副行显示摘要；进入选 dating 场景→载入默认→保存；
   - 编辑一个 enum 画像字段：选项为中文 label，自定义可输入；保存后 DB 为 code/`~`（dumpsys 不便，
     用再次进入编辑页回显中文验证即可）；
   - 发起一次参谋回合：分屏回答符合所选基调与意图（人工观感），logcat 无 FATAL；
   - 「恢复跟随默认风格」后副行文案正确。
5. 灰度门（交付后人工）：≥7 天 / ≥50 真实回合 do/dont 符合率 ≥85%，达标才启动 P2。
   〔2026-10-16 注：此门后经决议 #5 改判——P2 已不切片全量实施，本门转为合并试用期持续观察项，见文末勘误 2〕

## 九、风险与对策
- 小模型不按 label 选值：提示词约束 + 落库归一兜底，自造值走 `~` 通道不丢。
- 资产不完备：good/bad 锚点 + 灰度门；资产为纯 JSON，补条目不需要发版逻辑改动。
- 显示裸 code：四口径统一走 PersonaValueCodes，单测覆盖；未命中映射回显原值不崩。
- Room 校验失败：严格按 v3 列序追加新列、MIGRATION 与实体定义逐列对照；KSP 缓存损坏按教训 5 清理。
- 场景误覆盖已编辑风格：切换场景只改内存 state 且有确认对话框，保存才落库。

---

## 实施后勘误（2026-10-16 补，以总方案与代码为准）

本节列出 P1 完工后、P2 实施造成的口径变化。**P1 当期交付物（8 基调 10 意图、style_cards、
DB v3→v4、schema v2 + label→code 惰性迁移、StyleCardActivity）与本文一致，未被推翻。**

1. **场景包 2 → 12**：P2 把 `scenario_packs.json` 扩到 12 包、分四组
   （romance/workplace/business/conflict），每包新增 `pathKind`（pursue/maintain/defend）
   与 `group` 元数据；本文 §2.2 的两包 JSON 是 P1 历史内容，完整 12 包清单以总方案 §6.4 与
   资产文件为准。§4.1 风格页场景下拉项（"不套用 / dating / sales_b2b"）已被四组分段选择器取代
   （StyleCardActivity 与新建目标对话框共用）。
2. **灰度门改性质**：本文 §八.5「≥7 天 / ≥50 回合 do/dont 符合率 ≥85% 才启动 P2」原定是
   P2 开工硬门；2026-10-15 评审拍板 **P2 不切片全量实施**（总方案决议 #5），该门转为
   P1/P2 合并试用期的持续观察项，不再阻断过 P2。
3. **DB 版本**：本文的 v3→v4（仅 style_cards + scenarioCode）是正确历史；P2 已继续
   v4→v5（goals/goal_decomps/stage_states/strategy_cards/talk_missions 五表 +
   contact_profiles.activeCardId）。当前版本 = **5**。
4. **fitStages 调用口径**：本文约定「P1 恒传 stageCode=null」；P2 起传真实 stageCode，
   并增加跨包兼容规则（意图从未对本包声明门控时全站点放行，见总方案 §7）。
5. **customNote 行为化编译仍未做**：维持本文边界（compiledCustomJson 恒 null、原文一行注入），
   该确认流随 P3 交付。
