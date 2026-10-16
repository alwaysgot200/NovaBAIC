# P3 实施计划 · 有向图阶段机、reroute 与全量增强

> 📌 状态：**已实施完成**（2026-10-16 全量落地：12 子项全绿，`:app:testDebugUnitTest`
> 113 测试通过、assembleDebug 通过、build-client.ps1 grant+smoke 通过（扇形菜单+分屏均现）、
> logcat 无 FATAL；总方案已按 §十九 回改）。本文件是 P3 的唯一实施依据（历史记录）。
> 上游事实来源：[总方案](../../docs/2026-10-15-智能优化增强的聊天策略方案.md) §13 P3 清单、
> [P2 plan](P2_三级目标与策略卡_plan.md) §十三「P3 设计备忘录」。
> 本计划与二者冲突时，以**本文件经批准后的版本**为准，并回改总方案对应小节。
>
> **范围口径（用户拍板 2026-10-16）**：严格全量实施总方案 §13 与 P2 plan §十三
> 所列全部 P3 项。**群聊不建多参与者模型——把群当作一个"对方实体"处理**
> （群名=对方名，画像字段描述群整体/关键角色），复用现有一对一绑定与全部链路。
>
> P3 完成后本特性即收口：**不设 P4**。

---

## 一、范围总表（12 项，按依赖序）

| # | 子项 | 核心改动面 | 风险 |
| --- | --- | --- | --- |
| 1 | DB v6 迁移 | stage_states 加 3 列、新 stage_history 表、session_bindings 加 lastIncomingAt | 中（迁移） |
| 2 | 有向图阶段机（advance/branch/retreat/skip 四边 + 私有支线 + locked） | RoadmapPayload 扩展、StagePolicy 重构、MissionJudgeRunner 适配 | 高（主链路） |
| 3 | stage_history 时间线写入与 GoalTab 呈现 | 新 DAO、GoalTab 阶段条升级 | 低 |
| 4 | reroute 路线重规划（触发/PLANNER/stageMapping/轻确认迁移） | GoalPlanner、分屏轻确认条、目标页黄条 | 中高 |
| 5 | 增强档双调用（Tactician 预调用 → 校验 → 注入，失败回退一段式） | ScreenAdvisor、AdvisorPrefs、FloatSettingsActivity | 中高（成本/时延） |
| 6 | customNote 的 LLM 行为化编译 + 确认流 | StyleCardRepository、慢车道、StyleCardActivity、StyleResolver | 中 |
| 7 | 行内纠偏（建议逐条采纳 adoptedIndex + 长按三档即时重生成 + 依据小字） | TranscriptView、FloatBallService、ScreenAdvisor | 中 |
| 8 | L1 Judge 高置信完成**建议**（绝不自动勾） | MissionJudgeRunner、GoalTab | 低 |
| 9 | 画像 salience ≤6 裁剪 + staleAfterDays 时效 | ChatBriefBuilder、persona_schema.json | 低（纯函数） |
| 10 | defend/职场专用意图库 + 24h 时序信号 | style_playbook.json、帧感知来信时间戳、SignalRules | 中 |
| 11 | 群聊场景包（群=单一对方）+ maintain topicBank 扩充 | scenario_packs.json、persona_schema.json、少量文案 | 低 |
| 12 | app 内 eval 断言剧本（S1~S7/红线/越级/reroute） | app/src/test（**不碰上游 :eval 模块**） | 低 |

**明确不做**：多参与者群模型、自动勾选 L1、改动 `:eval` 等上游模块、P4 任何内容。

---

## 二、现状调研结论（代码事实，2026-10-16）

| 挂点 | 现状 | 文件:行 |
| --- | --- | --- |
| 线性阶段机 | `StagePolicy` 纯函数 object：initial/bumpHits/maybeAdvance（distinct key ≥ 信号数-1）/manualMove/applyAdvance；注释已标注"分叉/回退/跳级归 P3" | `advisor/strategy/StagePolicy.kt:33-125` |
| 跃迁写回 | Judge 规则+LLM 通道合并后调 bumpHits/maybeAdvance，`goals.upsertStage` | `advisor/MissionJudgeRunner.kt:90-132` |
| 阶段状态 | `StageStateEntity(contactId, scenarioCode, stageCode, enteredAt, advanceHits='{}', evidence)`，复合主键 | `advisor/data/AdvisorEntities.kt:314-323` |
| roadmap 结构 | `RoadmapPayload{stages, practices, l1}`；`RoadmapStage{code,goal,exitSignals}`；code 越界整项丢弃 | `advisor/strategy/StrategyModels.kt:96-110` |
| roadmap 编译 | `runPlanner` 调 AuxiliaryTasks(700 tok) → 存 decomp → 落 ≤3 个 provisional L1 → 插首站；decomp 已有版本化 supersededBy 机制 | `advisor/GoalPlanner.kt:153-212` |
| 慢车道 | `StrategyRuntime.requestGoalCompile/requestCardCompile/enqueue` 去重并发 | `advisor/strategy/StrategyRuntime.kt:34-93` |
| 两通道 | `collectLeanOnce`（responder.stream+PlanStreamFilter）、`collectAdvisorOnce`（agentLoop.run）；均无预调用挂点 | `ScreenAdvisor.kt:1310-1428, 1439-1588` |
| 档位 | 读取非 0/1 回退 1；setter 只接受 0/1；设置页增强 chip `enabled=false` | `AdvisorPrefs.kt:169-172,275-276,408-410`；`FloatSettingsActivity.kt:490-528` |
| customNote 编译 | `style_cards.compiledCustomJson` 列 v4 即预留，**全仓零消费**，恒 null | `AdvisorEntities.kt:88`、`AdvisorDatabase.kt:146` |
| 采纳回写 | `talk_missions.adoptedIndex INTEGER` 已存在恒 null；建议回复现为整条气泡+右上角复制按钮，无逐条点击/长按 | `AdvisorEntities.kt:369`、`TranscriptView.kt:137-181` |
| 画像裁剪 | ChatBriefBuilder **零 salience/staleAfterDays 逻辑**（全量渲染）；schema 已有 salience 标注，无 staleAfterDays；PersonaFieldEntity 有 updatedAt | `ChatBriefBuilder.kt`、`assets/advisor/persona_schema.json` |
| 来信时间 | ChatMessage 只有本端 createdAt，**无对方来信时间**；session_bindings 有 cursor/fingerprint 无来信时间列 | `core/model/ChatModels.kt:34-54`（上游，不动） |
| 群/会话绑定 | 会话↔联系人**一对一**绑定（session_bindings 唯一键 conversationId）；自动绑定与手动 rebind 均不区分通知/群；relationType 枚举已含「群聊」 | `ContactRepository.kt:178,264`、`AdvisorContactDirectory.kt:66`、`AdvisorProfileActivity.kt:538` |
| 场景模型 | `ScenarioPack{code,label,relationTypes,stages,pathKind,group}` data class，资产 JSON 驱动 | `advisor/StyleAssets.kt:75-104` |
| 阶段 UI | GoalTab L2 卡内 StageProgressBar：以 pack.stages 全量为骨架顺序渲染 ✓ | `GoalTab.kt:569-613` |
| eval 落点 | `:eval` 是上游独立模块（禁改）；`app/src/test/.../floatball/strategy/` 已有 7 个纯 JVM 测试类（StagePolicyTest/StageGateTest/SignalRulesTest 等），断言剧本放这里零侵入 | `app/src/test/...` |

---

## 三、数据层：DB v5 → v6（MIGRATION_5_6）

`AdvisorDatabase.version = 6`。三处变更，全部幂等（ensureColumn / CREATE TABLE IF NOT EXISTS）：

```sql
-- ① stage_states 加三列（有向图运行态）
ALTER TABLE stage_states ADD COLUMN roadmapVersion INTEGER;  -- 当前遵循的 roadmap decomp 版本；null=pack 骨架线性链
ALTER TABLE stage_states ADD COLUMN pathTag TEXT;            -- 当前所在支线标记；null=主线
ALTER TABLE stage_states ADD COLUMN locked INTEGER NOT NULL DEFAULT 0;  -- 1=用户手动锁定站，自动边不得移动

-- ② 阶段历史时间线（每联系人×场景滚动保留 50 条，DAO trim）
CREATE TABLE IF NOT EXISTS stage_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  contactId INTEGER NOT NULL,
  scenarioCode TEXT NOT NULL,
  fromCode TEXT,
  toCode TEXT NOT NULL,
  edge TEXT NOT NULL,              -- advance/branch/retreat/skip/manual/reroute/init
  reason TEXT,                     -- 信号短语 / 手动设置 / reroute 映射等
  at INTEGER NOT NULL
);
CREATE INDEX index_stage_history_contact ON stage_history(contactId, scenarioCode, at);

-- ③ 会话加「对方最后来信时间」（24h 时序信号；帧感知层维护）
ALTER TABLE session_bindings ADD COLUMN lastIncomingAt INTEGER;
```

- 实体同步：`StageStateEntity` 加三字段（默认 null/0，旧行自动取默认）；新增 `StageHistoryEntity` + `StageHistoryDao(insert/historyFor/trim)`；`SessionBindingEntity` 加 lastIncomingAt。
- 旧数据兼容：roadmapVersion=null 时一切行为退化为"pack 骨架线性链"（=P2 行为），已在线联系人无感升级。
- 落地文件：`advisor/data/AdvisorDatabase.kt`、`AdvisorEntities.kt`、`AdvisorDaos.kt`（均 floatball 包内）。

---

## 四、有向图阶段机（子项 2）

### 4.1 roadmap 资产扩展（向后兼容，缺省=线性）

`StrategyModels.kt`：

```kotlin
@Serializable data class EdgeDef(
    val from: String, val to: String,
    val kind: String,          // advance / branch / retreat / skip
    val condition: String = "",// 触发条件短语（供 Judge 引用与规则粗筛）
    val needConfirm: Boolean = false, // branch=true；其余 false
    val pathTag: String? = null,      // 进支线时打上
)
@Serializable data class PrivateStageDef(
    val code: String, val label: String, val goal: String = "",
    val exitSignals: List<String> = emptyList(),
    val advanceSignals: List<String> = emptyList(),
    val missionHint: String = "",
    val retreatSignals: List<String> = emptyList(),
    val pathTag: String? = null,
    val expectedDays: Int? = null,    // 预计停留天数（reroute 超时判定用）
)
// RoadmapStage 加 expectedDays: Int? = null
// RoadmapPayload 加：
//   edges: List<EdgeDef> = emptyList()
//   privateStages: List<PrivateStageDef> = emptyList()
```

- **edges 缺省（旧 roadmap/所有现存 12 包）= 隐式顺序 advance 边**，行为与 P2 逐字一致。
- 边校验（GoalPlanner 解析后）：from/to 必须命中 `pack.stages ∪ privateStages`，否则丢弃该边；kind 非法丢弃；同 (from,to,kind) 去重。
- 运行时站点全集 = pack.stages（骨架）+ roadmap.privateStages（随版本走，reroute 后旧支线随旧版失效）。
- PLANNER 提示词升级：允许输出 edges/privateStages/expectedDays；**线性包（现有 12 包）默认仍只给顺序链**，defend 类与 reroute 产物鼓励给 retreat/skip 边；约定 branch 边必须 needConfirm=true。

### 4.2 StagePolicy 重构（保持纯函数 object，便于单测）

- `resolveEdge(state, roadmap, hits, ruleSignals, now): Edge?`：在当前站的出边中按
  advance（阈值沿用 distinct-key ≥ 信号数-1）→ skip（条件命中）→ retreat（规则通道强信号
  且存在 retreat 边）顺序裁决；**locked=1 只放行 manual/reroute**。
- `applyEdge(state, edge, now, reason)`：移动、pathTag 同步、清 advanceHits、写 evidence。
- **retreat 语义变化**：P2 是"不回站只降温"；P3 为——**无 retreat 边时保持 P2 行为**
  （挂降温 + failStreak，不移动）；有 retreat 边且命中时才回站，两种情况都写 history。
- branch 边只产生"待确认跃迁"建议（走分屏轻确认条，见 4.4），不自动移动。
- skip 边自动执行，但在 stage_history 留痕且 GoalTab 提供一键"退回上一站"（manualMove 可达）。
- 终点站：骨架末站且无出边 = 停留态（P2 语义不变）。
- 旧 `maybeAdvance/manualMove/applyAdvance` 保留为薄封装（委托新函数），减少调用方改动面。

### 4.3 MissionJudgeRunner 适配

- 裁决段从 maybeAdvance 改为 resolveEdge；advance/skip/retreat(自动) 直接 applyEdge
  并 append history；branch 产出 pendingBranch 状态（存内存回合态即可，不持久化，
  随策略条同生命周期；用户忽略=丢弃）。
- retreat 无边分支完全沿用 P2（降温指令 + failStreak++）。

### 4.4 分屏轻确认条复用

- P2 的 provisional L2 轻确认条通道扩展为通用"待确认事项"条：类型加 `branch`
  （文案"局势出现新分支：{label}，要切过去吗？[切过去] [先不]"）。
- 确认 → applyEdge + history(edge=branch)；沉默/忽略两次后本次建议不再弹（同 L2 口径）。

---

## 五、stage_history 与 GoalTab 时间线（子项 3）

- 所有移动统一经一个入口写 history：init（首站插入时）、advance、branch、retreat、skip、
  manual、reroute；insert 后 trim 50 条。
- GoalTab 阶段区升级（`StageProgressBar`）：
  - 主链仍以 pack.stages 为骨架；privateStages 以支线样式（虚线节点 + pathTag 色块）
    插在 from 站之后；
  - 当前站加小锁图标（locked=1 时），点锁可解除；长按任意站 = 手动改站（沿用 P2 菜单），
    手动改站自动加锁（防自动边立刻拉走，用户可解锁）；
  - 节点下方折叠"历程"：最近 5 条 history（edge 中文化 + 相对时间 + 原因短句）。

---

## 六、reroute 路线重规划（子项 4）

### 6.1 触发（三选一，全部需用户主动点按钮，绝不静默重规划）

1. active L2 `failStreak ≥ 3`：目标页黄条按钮从"查看"升级为【重新规划路线】；
2. 同站连续 retreat 命中 ×2（history 计数）：分屏策略条出现【路线不对？重规划】；
3. 停留超时：`now - enteredAt > expectedDays×1.5`（expectedDays 缺省 = 14 天兜底），
   GoalTab 路线区小字提示 + 同一按钮。

### 6.2 runReroute(goalId)（GoalPlanner 新增）

- 取当前 roadmap（含版本）+ stage_states + 近 3 条 history + failStreak 与 progressNote +
  最近一轮 mission/incoming 证据，构造 PLANNER reroute 提示词；
- 输出 `RoadmapPayload`（同 schema）**外加** `stageMapping: Map<String,String>`
  （oldCode→newCode；废弃站缺键）——在 RoadmapPayload 加可空字段 `stageMapping`，
  仅 reroute 产物使用；
- 校验后：新版 decomp 落库（旧版 supersededBy 指向新版，不删），**不立即移动阶段**，
  产出一条 pendingReroute（轻确认条："新路线已生成，当前站将调整为 {label}
  [采用新路线] [先不]"）。
- 确认后：按 mapping 迁移 stage_states（无映射→新链首站）、置 roadmapVersion=新版本、
  解锁 locked（reroute 是显式用户意图）、写 history(edge=reroute, reason)；
  旧支线相关在途 L1 不删（P2 口径：不连带删目标），目标页提示其路线已过期。
- 拒绝：新版 decomp 留存但不激活（confirmed=0），状态机继续跑旧版。

---

## 七、增强档双调用（子项 5）

- `AdvisorPrefs.setStrategyMode` 开放 STRATEGY_ENHANCED=2（读取端校验不变）；
  FloatSettingsActivity 增强 chip 解锁，文案注明"每回合多一次快速分析，响应略慢、更克制"。
- ScreenAdvisor 仅在**增强档 + 智能通道回合**插入预调用（轻通道 lean 不插，控成本）：
  1. 主调用**之前**先 `AuxiliaryTasks.complete(maxTokens≈300, temp=0.2)` 跑 Tactician
     预分析：输入当前 stage/card/L1/红线/对方最新来信，输出严格 JSON
     `{"hardActions":[],"donts":[],"mustAnswer":[],"toneGuard":""}`；
  2. 用与 PlanStreamFilter 同包的确定性校验器校验（字段/长度/非法指令），不过则**整段丢弃**；
  3. 通过则作为"本轮战术硬约束"块拼进主调用 system prompt（物理拼装顺序位于策略卡之后、
     红线块之前，总方案 §8.1 同步回改）；
  4. 预调用异常/超时（预算 8s）/解析失败 → 静默回退一段式，missionUi/日志记 reason
     （`REASON_ENHANCED_FALLBACK`），**读 2 最终行为退化为档 1**（P2 承诺口径不变）。
- 两次调用严格串行，在 Advisor 协程队列内；新增 token 成本只发生在用户手选增强档时。

---

## 八、customNote LLM 编译确认流（子项 6）

- StyleCardRepository 保存风格卡：纯选项组合 → confirmed=1 即时生效（P2 不变）；
  customNote 非空 → 入慢车道 StrategyRuntime 新队列 `requestStyleCompile(cardId)`。
- 编译：AuxiliaryTasks 小调用（≤250 tok）把一句手写补充解释成
  `{"do":[],"dont":[]}`（每条 ≤20 字，≤3+3），失败留 reason 不阻塞其余链路。
- 写回 compiledCustomJson，confirmed=0（draft）；重新编辑 customNote → 旧编译作废重编。
- StyleCardActivity：draft 卡顶部确认条【这样理解对吗：…（do/dont 预览）】
  [理解正确，采用] [重新理解] [放弃这句补充]；采用 → confirmed=1；放弃 → 清 customNote 与编译。
- StyleResolver.render：仅 confirmed 的编译结果渲染进风格块（do 进"要这样做"、
  dont 进"避免"）；draft 不进任何 prompt。

---

## 九、行内纠偏与依据可解释（子项 7）

- **逐条采纳**：TranscriptView 解析参谋气泡内编号建议（2~3 条），每条末尾加小"✓采纳"；
  点击 → 回写本会话最新 pending `talk_missions.adoptedIndex`（DAO 新增 adopt 方法）+
  复制该条正文（沿用现有复制能力）；无 pending mission 时仅复制。
- **长按三档**：长按任意建议条弹【太直接了/太软了/换个方向】；
  - 反馈映射成修正指令（"更含蓄、先共情再表达"等固定中文模板，不新造 LLM 自由文本）；
  - ScreenAdvisor 在轻通道**即时重生成一次**该回合建议（带修正指令 + 原上下文），
    结果以新气泡追加（不覆盖原气泡，保留对照）；原 mission settle=ignored，
    重生成内容挂新 mission 行（adoptedIndex 归零待选）；
  - 每回合长按重生成限 1 次（防无限烧钱），按钮置灰提示。
- **依据小字 + 记错了**：ChatBriefBuilder 组装时同时产出本回合引用的记忆/画像依据短句
  （≤3 个关键词，随回合态带到 UI）；气泡下方显示"依据：X、Y · 记错了"。
  点"记错了"→ 弹一行输入（默认带原关键词）→ `MemoryRepository.addNote` 走既有冲突裁决，
  不做记忆直删（本地优先、可追溯）。

---

## 十、L1 Judge 完成建议（子项 8）

- Judge prompt/JSON 增字段：传入 active L1（≤3）标题与成功判据，输出
  `completedL1: [{id, confidence: 0-1, evidence}]`；
- **只有高置信（confidence ≥ 0.8 且规则通道同向）**才生成建议；
- GoalTab 对应 L1 卡顶部建议条【看起来已达成：{title}？】[标记完成] [还没]；
  确认才走 `completeGoal`；"还没"→ 该 L1 本生命周期不再提示（记本地抑制，加字段或内存态，
  优先内存态+progressNote，不为它加列）。绝不自动勾选。

---

## 十一、画像 salience 裁剪与 staleAfterDays（子项 9，纯函数优先）

- persona_schema.json：为字段增加可选 `staleAfterDays`（int；缺省/null=长期有效）。
  首批标注：年龄/城市/职业等约 10 个易变字段给 180/365；价值观类不标。
- ChatBriefBuilder 注入规则（纯函数 `selectPersonaFields(fields, stage, limit=6)`，单测覆盖）：
  1. 当前站 advanceSignals/retreatSignals 命中相关字段优先（关键词弱关联表写在资产侧）；
  2. 其次 salience=normal > low；updatedAt 新者优先；
  3. 总数 ≤6 条；被裁掉的不进 prompt（不是折叠成概括——避免漏关键禁忌：
     **their taboos/my taboos 与红线相关字段永远不裁**，优先级最高）；
  4. 超 staleAfterDays 的字段保留但措辞变"（早期信息，可能已变化，请先验证）"，不删除。
- 画像编辑/确认流程不变。

---

## 十二、defend/职场意图库 + 24h 时序信号（子项 10）

### 12.1 意图库扩充（style_playbook.json 纯增量）

新增意图码（带 do/dont/templates/fitStages/pathKind 门控，沿用 P1 schema）：
`hold_line`（守住边界不攻击）、`disengage`（体面抽身/结束话题）、`fact_anchor`
（事实固定、留证口吻）、`manage_upward`（向上管理：给选择题不给问答题）、
`credit_share`（功劳让渡/团队口径）、`de_escalate`（降温降级措辞）。
意图门已支持 pathKind 过滤（P2），retreat 类意图只在 defend 包与 retreat 边命中后上浮。

### 12.2 24h 时序信号

- 写入点：帧感知/会话扫描确认"对方新来信"时（IncomingCue 文本指纹变化的现有判定处）
  更新 `session_bindings.lastIncomingAt`（仅在文本确实变化时，防 OCR 抖动反复刷）。
- 消费点：StrategyBrief 取数时带出 `hoursSinceIncoming`；SignalRules/Judge 增加中性信号
  `silence_24h`（距上次来信 ≥24h）。**语义中性**：不直接催促回复，由策略卡/场景决定
  动作——pursue 可建议轻触达，defend/maintain 可建议继续等；missionHint 文案区分。
- 无来信时间（旧会话从未更新）= 无此信号，不臆测。

---

## 十三、群聊场景包与话题库扩充（子项 11）

- scenario_packs.json 新增 `group_general`（群=单一对方实体）：
  - group 新增第 5 组 `"social"`；relationTypes=["group"]；pathKind 默认 maintain；
  - 站点骨架示例：破冰露面 → 稳定在场 → 关键人互信 → 口碑沉淀；信号/红线资产围绕
    "群是公共场合"设计（禁私发内容、避免公开纠正人、@礼仪）。
- persona_schema.json：their 侧加群实体适用字段（群类型 enum：工作/兴趣/家长/邻里；
  群规模 enum；关键角色 text 多行），owner=their，单聊联系人不显示这些字段
  （编辑器按 relationType=group 显隐，已有显隐机制）。
- 绑定链路零改动（群会话 bind 到一个 ContactProfileEntity 即"群实体"）；
  场景选择器（GoalTab SCENARIO_GROUPS 五组）允许 group 联系人选到 social 组。
- 文案：群实体下分屏/目标页少量"对方"→"群里/大家"的措辞，用 string 资源按
  relationType 切换（新增 `float_*` 键，不改上游）。
- maintain 包 topicBank 扩充：12 包中 maintain 路径的可聊话题池各 +6~10 条
  （资产 JSON，无代码）。

---

## 十四、eval 断言剧本（子项 12，全部 app/src/test）

不碰上游 `:eval` 模块。在 `app/src/test/java/com/verlintas/baic2/floatball/strategy/`
新增纯 JVM 测试（无 Android 依赖，构造假数据驱动纯函数）：

1. `StageGraphTest`：线性缺省等价 P2；四边裁决优先级；locked 拦截；私有支线可达/
   旧版支线失效；retreat 有边回站、无边只降温。
2. `RerouteTest`：stageMapping 迁移（有映射/无映射落首站/版本指针/拒绝不激活）。
3. `SignalRulesTest` 扩充：silence_24h 三态（无时间/不足24h/超过24h）。
4. `PersonaSelectTest`：≤6 裁剪、禁忌永不裁、时效措辞、相关度优先。
5. 既有 S1~S7 行为断言以 StagePolicyTest/StageGateTest/PlanStreamFilterTest/
   TacticianParseTest 形式补齐缺口（越级拦截、红线违规拦截、plan 合法率、
   增强档预调用 JSON 校验器的合法/非法用例）。

---

## 十五、实施步骤（依赖序）

1. **DB v6**：实体/DAO/迁移 + 既有测试回归（无行为变化）。
2. **有向图内核**：StrategyModels 扩展 → StagePolicy 重构 → MissionJudgeRunner 适配
   → StageGraphTest 等单测先行（此步结束内部行为对线性包完全不变）。
3. **history + GoalTab 时间线/锁/支线 UI**。
4. **reroute**：runReroute + 触发入口 + 轻确认迁移 + RerouteTest。
5. **三个低风险增强**：salience/staleAfterDays、L1 完成建议、意图库+topicBank+群包资产。
6. **customNote 编译流**（慢车道 + 确认 UI）。
7. **增强档双调用**（Prefs/设置页/预调用/回退）。
8. **行内纠偏 + 依据小字**（TranscriptView/Service/ScreenAdvisor，UI 交互最密，放最后）。
9. **24h 时序信号**（写入点 + 消费）。
10. 全量回归：单测 + license + assembleDebug + build-client.ps1 冒烟 + logcat 无崩溃。

每步独立可编译、可提交；任一步冒烟失败不进入下一步。

---

## 十六、验证

- `./gradlew :app:assembleDebug` 通过；`tools/check-license-headers.sh` 通过；
  `./gradlew :app:testDebugUnitTest`（新增纯 JVM 测试全绿）。
- 冒烟（模拟器 Galaxy_Intel_API_36，沿用 build-client.ps1 grant/smoke）：
  1. **升级链**：预装 P2 版本造 stage/goals 数据 → 覆盖安装 → v5→v6 迁移成功，
     旧联系人阶段显示与行为同 P2（roadmapVersion=null 退化）；
  2. 线性 12 包：信号计数跃迁、手动改站、retreat 降温不回站，与 P2 一致；
  3. 构造带 edges 的 roadmap（调试入口或 reroute 产物）验证 advance/branch 确认/
     skip 自动+留痕/retreat 回站/locked 拦截/支线节点显示；
  4. reroute：failStreak≥3 出按钮 → 生成新路线 → 确认后站迁移/history 记录/
     拒绝则继续旧版；
  5. 增强档：开档后观察 logcat 两次调用时序与注入；断网/预调用超时时回退一段式可见 reason；
  6. customNote：写补充→编译→draft 确认条→采用后进 prompt；放弃后清空；
  7. 分屏：建议逐条采纳回写 adoptedIndex（查库）；长按三档重生成一次后置灰；
  8. L1 高置信完成仅出建议条，不自动完成；
  9. 画像：构造 10+ 字段验证 ≤6 且禁忌必在；超期字段带时效措辞；
  10. 群会话：绑定群实体→选 social 组包→全链路（画像/目标/分屏）无"私聊"错文案；
  11. logcat 无 FATAL/AndroidRuntime。
- 证据图一律放 `tests/tempImg/`（AGENTS.md §4.3）。

---

## 十七、风险与对策

| 风险 | 对策 |
| --- | --- |
| 有向图重构误伤 P2 线性行为 | edges 缺省逐字走旧逻辑；StagePolicy 旧函数保留薄封装；StageGraphTest 含"线性等价 P2"用例 |
| reroute 错站/乱站 | 永不静默迁移：必须轻确认；mapping 无键落首站而非乱猜；旧版 decomp 不删可回溯 |
| 双调用增加时延与费用 | 仅增强档、仅智能通道、串行、8s 超时、300 tok 上限；失败静默回退档 1；设置页文案明示成本 |
| retreat 回站引发"阶段倒退"观感 | 仅当 roadmap 显式给 retreat 边才回站，且 history 留痕、UI 标"策略性回撤"；无边保持只降温 |
| 长按重生成被滥用烧钱 | 每回合限 1 次；固定中文反馈模板不开放自由文本注入 |
| 24h 信号变成"催回复"焦虑 | 信号中性、动作由场景/pathKind 决定；defend/maintain 默认建议继续等待 |
| 群聊文案穿帮（"对方"→群） | 集中 string 资源按 relationType 切换；冒烟第 10 条专项走查全部 float_* 文案 |
| 画像裁剪误丢禁忌 | taboos/红线字段硬豁免，裁剪排序在其之后；单测固化 |
| KSP 增量缓存损坏（老毛病） | 报 *_GeneratedInjector 找不到时删 app/build/generated/ksp 重建 |

---

## 十八、文件清单（全部在 app 模块 floatball 包内，上游零侵入）

**修改**：`advisor/data/{AdvisorDatabase,AdvisorEntities,AdvisorDaos}.kt`、
`advisor/strategy/{StrategyModels,StagePolicy,StrategyRuntime}.kt`、
`advisor/{MissionJudgeRunner,GoalPlanner,GoalRepository,StyleCardRepository,StyleResolver,ChatBriefBuilder,ContactRepository,SignalRules,StrategyBrief}.kt`、
`ScreenAdvisor.kt`、`AdvisorPrefs.kt`、`FrameDiffEngine.kt`（或 IncomingCue 调用处）、
`GoalTab.kt`、`FloatSettingsActivity.kt`、`StyleCardActivity.kt`、`TranscriptView.kt`、
`FloatUi.kt`、`FloatBallService.kt`；
`app/src/main/assets/advisor/{persona_schema.json,style_playbook.json,scenario_packs.json}`；
`app/src/main/res/values/strings.xml`（白名单，仅追加 float_* 键）。

**新增**：`advisor/strategy/StageGraph.kt`（如 resolveEdge 独立成文件）、
`app/src/test/.../strategy/{StageGraphTest,RerouteTest,PersonaSelectTest}.kt` 等；
均带 GPL-3.0-or-later 头。

**不改**：core/feature/device/tools/mcp/eval 任何文件；Manifest 无需变更
（无新服务/权限/Activity）。

---

## 十九、与现有文档的回改约定

批准并实施完成后，回改 [总方案](../../docs/2026-10-15-智能优化增强的聊天策略方案.md)：
头部状态 P3 ✅、§5 四边语义、§6.5 改 v6、§8.1 增强档拼装顺序、§9 Judge 完成建议、
§13 全绿并收口（声明无 P4）；P1/P2 plan 不动（历史记录）。
