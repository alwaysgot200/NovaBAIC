# 好参谋「上下文瘦身」方案

日期：2026-10-14
状态：已批准，实施中
范围：仅 `app` 模块 `floatball` 包；不改动任何上游模块（core/engine、core/data、core/network 零侵入）。

## 1. 问题事实

每次长按/双击参谋，OCR 或无障碍文本（单条上限 6,000 字）都作为普通 USER
消息追加进**同一个持久化「好参谋」会话**；每回合
`getMessages().takeLast(16)` 原样交给 AgentLoop，之后全链路（AgentLoop →
ChatRequest → provider）零裁剪、无压缩。旧屏幕全文随每个回合重放。

真机实测（78 条消息的会话）：

| 回合 | 本次新屏幕 | provider 上报 input tokens |
| --- | --- | --- |
| 兴义长文第 1 次 | 5,988 字 | 7,501 |
| 同一长文第 2 次（完全重复） | 5,988 字 | 11,715 |
| 其后连续 8 回合 | 新屏幕仅 36～220 字 | 稳定 11,700～11,850 |

即 98% 的请求体是过期 OCR；重复主屏/锁屏也被反复发送，模型回答失去焦点。

## 2. 设计原则

**存储/展示的历史 与 发给 LLM 的历史分离。**

- Room 中的完整 OCR 一条不删，转录视图折叠展示照旧（复用【屏幕内容】前缀识别）。
- 发给模型前，在 app 层把历史重组成「瘦身窗口」。
- 不做 Room 迁移、不改 AgentLoop、不改 Provider。

## 3. 具体设计

### 3.1 新增纯函数 `AdvisorHistory.build(messages, screenLabel, digest)`

按 USER 边界把消息划分为「回合」，最后一个回合（当前屏）全文发送，其余：

1. **最近 2 个自动屏幕回合**：屏幕消息截断到 160 字（保留首行 + 省略号），
   AI 参谋回复保留原文（天然很短）。
2. **更早的自动屏幕回合**：不发原文，聚合成**一句占位摘要**——
   合成一对 USER/ASSISTANT 消息：
   - USER：`【历史屏幕】本次之前共自动识别过 N 个屏幕，主要场景：主屏幕×3、
     设置页×4、锁屏×2……原始文本已省略，请聚焦当前屏幕。`
   - ASSISTANT：滚动摘要（3.3）；无摘要时为极简承接句。
   - 场景标签由纯启发式从屏幕文本归类（主屏幕/锁屏/设置页/通知面板/
     聊天通知/网页文章/其他），零额外调用。
3. **用户手动追问**（不带屏幕前缀）：整回合全文保留。
4. **预算封顶**：当前屏之外的历史部分 ≤ 2,400 字符；超出时从最旧的屏幕
   回合开始继续折叠，手动追问受保护。
5. 严格保证 USER 开头、USER/ASSISTANT 交替；消息为 `copy(content=…)`，
   id 等字段不变，`triggerMessageId` 不受影响。

### 3.2 相同画面短路（增强 B）

`submitTurn` 落库前，取最近一条屏幕消息，用现成的
`FrameQuality.textSimilarity`（字符 2-gram Jaccard）比较；≥ 0.92 判为重复：

- 不写库、不调 LLM。
- 连续（差分）模式：静默跳过（差分引擎本有去重，这里兜底）。
- 单次模式：发出新事件 `Event.Unchanged`，服务层开面板 + Toast
  「画面与上次几乎相同，未重复分析」。

### 3.3 滚动摘要（增强 A）

每完成 6 个自动屏幕回合，在后台用上游现成的 `AuxiliaryTasks.complete`
（Hilt 图已提供）对最近约 12 个屏幕回合（屏幕文本各截 200 字 + 参谋回复
全文）生成 ≤150 字的连续背景摘要，存入 `AdvisorPrefs`（不进会话库）。
失败静默保留旧摘要，绝不阻塞主链路；清空会话时摘要一并重置。

### 3.4 单屏上限（增强 C）

`MAX_SCREEN_CHARS` 6,000 → 3,000。参谋结论本就限制 150 字，3,000 字
原文足够覆盖一屏有效信息。

## 4. 改动清单

| 文件 | 改动 |
| --- | --- |
| `floatball/AdvisorHistory.kt` | **新增**：回合分组、场景归类、占位摘要、预算折叠纯函数 |
| `floatball/ScreenAdvisor.kt` | 接入瘦身历史；两阶段 prepare/run；重复短路；摘要刷新；常量调整；新增 `Event.Unchanged` |
| `floatball/AdvisorPrefs.kt` | 摘要文本与已覆盖回合数的持久化 |
| `floatball/FloatBallService.kt` | 处理 `Event.Unchanged`（仅 Toast，不展开面板） |
| `res/values/strings.xml` | 新增 `float_screen_unchanged` 文案 |

## 5. 预期与验证

- 典型回合 input tokens：约 11,800 → 3,000～4,500（系统提示约 2,800 固定底噪）。
- 编译后在模拟器复现「长文重复 + 主屏连点」序列，核对：
  1. 消息库内容完整未删；
  2. 第二次长文、后续小屏的 input tokens 显著下降；
  3. 重复主屏不再触发 LLM，有 Toast；
  4. 回答聚焦当前屏；
  5. logcat 无崩溃。

### 5.1 实测（emulator-5554，API 36，会话已有 80 条历史消息）

| 回合 | 屏幕 | USER 字数 | input tokens（旧链路同场景） |
| --- | --- | --- | --- |
| #79 | 主屏应用抽屉 | 171 | 3,515 |
| #81 | 信息欢迎页 | 99 | 3,341 |
| #83 | 长文报告 A（首屏全文） | 2,078 | **4,503**（旧：7,501） |
| #85 | A 页滚动后的稀疏帧 | 111 | **3,395**（旧：≈11,715） |
| #87 | 长文报告 B（历史含 A 全文） | 1,186 | **4,121**（旧估计 ≈6,000+） |

- 同一画面二次长按：DB 无新消息、无 LLM 调用，Toast「画面与上次几乎相同，未重复分析」。
- #83 完成后后台生成滚动摘要（SharedPreferences `screen_digest`，覆盖 37 个屏幕回合）。
- 回答均聚焦当前屏（B 回合只提炼清水河园区要点，未复述 A 的兴义城市报告）。
- logcat 全流程无 FATAL/AndroidRuntime。
- 已知既有 flake（非本次引入）：`AndroidAccessibilityBridge.captureScreen()` 的
  suspendCancellableCoroutine 无超时，快速滚动后 a11y 文本稀薄、降级截图且模拟器回调
  丢失时回合会永久挂起；后续可在 app 侧 `ScreenPerceiver` 包一层 `withTimeoutOrNull`。

## 6. 跨时间对话线程的时序真实性（对齐 v3 架构的细化实施稿）

> 本节取代早前的线程草图。指纹/画像/会话绑定**不再自造**，统一由 v3
> （[2026-10-14 方案](2026-10-14-好参谋AI辅助自动化聊天功能方案架构设计.md)）
> 的 `contact_profiles / contact_aliases / session_bindings` 承担；本节只补
> v3 未覆盖的四个时序缺口，并全部复用上游记忆。

### 6.0 实施状态（2026-10-14，与 v3 P1 任务并行施工）

**我方已落盘（IDE 诊断 0 问题）：**

| 文件 | 内容 |
| --- | --- |
| `floatball/AdvisorHistory.kt`（改） | `bound` 绑定档：近 4 屏×400 字、预算 3200、绑定档折叠措辞（旧屏报价/承诺有效，最新态度以当前屏为准） |
| `floatball/contact/ContactDirectory.kt`（新） | 消费端契约：resolve/ensureAdvisorConversation/conversationIds/digest/saveDigest |
| `floatball/contact/ContactDirectoryModule.kt`（新） | `EmptyContactDirectory` 默认 Hilt 绑定——P1 落地前行为与现状一致 |
| `floatball/contact/ContactHistoryGateway.kt`（新） | 注入上游 public `MessageDao`，成交信号词门控 + 12 词 LIKE 检索，≤3 条摘录 |
| `floatball/brief/ContactBriefSection.kt`（新） | 纯函数：交往脉络（MM-dd 日期+kind 标签）、到期承诺置顶、成交要件、转折铁律 |
| `floatball/ScreenPerceiver.kt`（改） | 透传 `foregroundPackage()` / `windowTitle()`（各包 runCatching） |
| `floatball/ScreenAdvisor.kt`（改） | 联系人解析→会话路由→bound 历史→三路记忆（recallByEntity/upcoming/recall）→独立 brief 段→customSystemPrompt；digest 按联系人/共享双存储；`DIGEST_SYSTEM_BOUND` 强制「截至 MM-dd，阶段=X」 |

**接缝（P1 完成后对接，预期冲突点只有一个）：**

1. P1 的 ContactRepository 就绪后，**删除** `ContactDirectoryModule.kt`
   （含 EmptyContactDirectory），在对方 Hilt 模块 `@Binds` 真实实现；
   这是故意做成编译期可见的单点冲突。
2. `session_bindings` 实体请追加 digest 三列（advisor.db 未发布，零迁移成本）：
   `digest: String? = null`、`digestTurns: Int = 0`、`digestUpdatedAt: Long? = null`。
3. 然后整包编译 + §6.8 冒烟；P3 画像确认流再补 intentStage 跃迁的 EVENT 双写。

**端到端实测结论（2026-10-14 傍晚，emulator-5554 / API 36，Chrome 测试壳模拟 IM 页）：**

- 接缝 1/2 已接通：删除 EmptyContactDirectory 默认绑定，改由 P1 的
  `AdvisorContactDirectory` 实现；digest 三列以
  `digest/digestCoveredTurns/digestUpdatedAt` 落 `session_bindings`。
- **Day3 转折闭环通过**：王总 conv2 第 9 屏（同意走合同、按 40 万、培训写进合同）
  回答稳定闭环（消息 id=94 `usageIn=4094`、id=104 `4362`、id=108 `4678`）：
  「按 40 万含两次现场培训」「签约付一半、验收付尾款」「只确认数量/付款/交付/
  签约主体，不再谈价」。
- **记忆脉络铁证**：向 notes 注入一条不出现在任何屏幕消息里的独特 PREFERENCE
  （「本地化私有部署、数据不出内网」，entities=王总），当回合回答主动写出
  「另加本地化私有部署、数据不出内网条款」——`recallByEntity` 路径生效。
- **绑定档 digest 已自动落库**：满 6 回合后 `session_bindings(conv=2,contact=3)`
  的 digest =「截至 10-15，阶段=成交。谈定企业版 40 万（原报 42 万，竞品 35 万），
  含两次现场培训……付款拟签约付一半、验收付尾款……」，`digestCoveredTurns=9`。
  施工中修掉两个串味 bug：① 绑定联系人首摘要误继承共享会话的 coveredTurns
  （实测被 37 压制导致 `total-37<6` 永不刷新），改为无 stored digest 从 0 计数；
  ② `previousDigest` 会把共享会话摘要串进绑定档，改为绑定档只读自己的 stored。
- **A/B 隔离通过**：李总 conv3 回合（id=116 `usageIn=3813`）只含 28 万/不含运维/
  上线培训/固定对接人（来自李总专属 PREFERENCE 记忆），**零** 40 万/42 万/35 万/
  两次现场培训/王总字样；conv2 无新增消息，面板状态条显示「当前联系人：李总」。
- **未绑定回归通过**：桌面长按曾被懒建档成「主屏幕」stub（与「未绑定=现行为」
  相悖），已在 `ScreenAdvisor` 加聊天窗口闸门：HOME Intent 动态发现各启动器 +
  固定系统壳名单（`NON_CHAT_PACKAGES`：systemui/settings/incallui/phone/
  授权器），命中即不解析联系人、回落共享 conv1。复测主屏回合落 conv1
  （id=119/120），未新建档案/会话，状态条「未绑定联系人，点此管理画像」。
- **同画面二次触发短路通过**：同页立即第二次长按仅弹 Toast，conv 无新消息；
  全程 logcat 无 FATAL/AndroidRuntime（仅有 monkey 自身的良性启动行）。
- **已知 P1 侧缺陷（不影响本稿验收，建议 P1 修）**：`ContactRepository.resolve`
  调 `findByKey(imPackage, norm)`，但列存的是复合键 `imPackage:norm`，精确键
  实际永不命中，现网归属全部依赖建 stub 时写入的自别名（alias=规范化标题）。
  真实微信场景标题=对方昵称=自别名，功能无感；但键回查形同虚设，建议调用点改传
  `ContactNames.contactKey(imPackage, norm)`。
- **环境备注**：install -r / 冷启动后 ART JIT 风暴曾引发跨进程 ANR
  （systemui、phone 与本 App 同报 startup timeout，主线程 trace 为 Compose
  文本布局突发占满 CPU 后自恢复，非死锁、非本次代码崩溃）；`cmd package compile
  -m speed -f` 且等系统 load 回落即恢复。

### 6.1 验收场景（端到端走查）

同一客户跨 3 天跟进：Day1-2 各屏拒绝、嫌贵；Day3 当前屏出现「那行，你给我走
合同吧」。要求参谋当回合：① 知道在跟谁、之前谈到哪款/多少钱/承诺过什么；
② 识别拒绝→成交的转折，立即转闭环（型号/数量/付款/交付），不继续逼单；
③ 这些事实跨天存活，且不串到其他客户的回合。

### 6.2 v3 已覆盖与四个缺口

| 能力 | v3 落点 | 本稿增补 |
| --- | --- | --- |
| 联系人身份/别名/合并 | §4、§11.1 | 直接复用，**不另建指纹** |
| 业务目标/双方人设/话术 | contact_profiles、persona_schema、ChatBriefBuilder（§6.2） | 复用 |
| 一人一会话 | session_bindings（PK=conversationId）**隐含但无路由代码** | **缺口 1：会话路由** |
| 历史缓存 | §6.3 游标+压缩，未区分绑定/未绑定 | **缺口 2：AdvisorHistory 绑定档折叠策略 + 按联系人 digest** |
| 跨天事实 | 画像结构化字段 + §6.5 双写纪律 | **缺口 3：转折当回合的记忆取数与转折铁律**（现稿仅 terms recall，精度/时效不足） |
| 阶段变迁 | intentStage 字段（萃取在回合后、每 5 轮、需确认） | **缺口 4：阶段跃迁作为带日期的 EVENT 写入**；成交要件历史检索 |

### 6.3 缺口 1：会话路由（P2 前置，先于 ChatBriefBuilder）

现状 `ScreenAdvisor.ensureConversation()` 只有一个全局会话（prefs.conversationId）。
改为按联系人路由，**不新增表**：

```kotlin
// ScreenAdvisor
private suspend fun resolveConversation(contactId: Long?): Long {
    if (contactId == null) return ensureSharedConversation()        // 未绑定：现行行为
    val binding = contactRepository.bindingFor(contactId)          // session_bindings
    val id = binding?.conversationId?.takeIf { conversationRepository.get(it) != null }
        ?: conversationRepository.create(
               agentId = profile.agentId, mode = AppMode.CHAT,
               title = profile.displayName,                         // 主界面会话列表可辨
           ).also { contactRepository.attachConversation(contactId, it) }
    resubscribe(id)                                                // 抽取现有 observeJob 逻辑
    return id
}
```

- 联系人识别用 v3 的 L1（`AccessibilityBridge.windowTitle()` + title_rules 规范化）
  + L4（binding 回查）；**识别失败按未绑定走共享会话**，不阻断现行功能。
- 合并（v3 §4.3）迁移 session_bindings 后，旧 conversationId 自然挂到目标档案，
  历史"跟着人走"；参谋侧零搬运。
- 一个 contactId 可有多个 conversationId（合并带来），取数按集合（见 6.7）。

### 6.4 缺口 2：AdvisorHistory 分两档 + digest 入库

**绑定联系人会话（同一会话内全部是同一线程）**——折叠放宽、时间信息保留：

| 层 | 现行（未绑定/共享） | 绑定档 |
| --- | --- | --- |
| 当前屏 | 全文（≤3000） | 同 |
| 最近屏幕回合 | 2 个，缩写 160 字 | **4 个，缩写 400 字**（保报价/异议/承诺原话） |
| 更早 | 一对场景占位 + 全局 digest | 场景计数 + **该联系人 digest（带日期与阶段，见下）** |
| 更早预算 | PRIOR_BUDGET_CHARS 2400 | **3200**（同一线程无串档风险，预算可让渡） |
| 手动追问 | 整回合保护 | 同 |

`AdvisorHistory.build(...)` 增加参数 `bound: Boolean` 与 `digestLine: String?`；
两档常量集中在 companion，不拆文件。

**digest 存储搬家**：现存 AdvisorPrefs 的全局 screen_digest 保留给共享会话；
联系人 digest 落到 v3 `session_bindings` 新增三列（P1 建表时一次到位，零迁移）：

```sql
ALTER TABLE session_bindings ADD COLUMN digest TEXT;
ALTER TABLE session_bindings ADD COLUMN digestTurns INTEGER NOT NULL DEFAULT 0;
ALTER TABLE session_bindings ADD COLUMN digestUpdatedAt INTEGER;
```

**digest 内容契约（治"旧摘要帮倒忙"）**：滚动摘要必须带日期与阶段，禁止无时态
结论。prompt 约定输出形如：
`截至 10-12，阶段=犹豫；嫌 50 万报价高、提过竞品 X；我方承诺过含一年运维。`
——阶段与日期由 ChatBriefBuilder 从画像 intentStage 与记忆另取（6.5），digest 只
负责叙事；清会话/合并时按 v3 生命周期处理。

### 6.5 缺口 3：转折当回合的记忆取数（全部走现成上游 API，零上游改动）

每轮绑定联系人的参谋，在 AgentLoop 之前（ScreenAdvisor 协程内）：

```kotlin
// 1) 按人直取（比 v3 §6.5 的 terms recall 更精确，天然防串档）
val entityNotes = memoryRepository.recallByEntity(
    entities = listOf(profile.displayName) + aliases.map { it.alias },
    limit = 6, feedback = false,           // 自动参谋轮次不做强化反馈
)
// 2) 前瞻记忆：承诺/约见（AGREEMENT/PLAN，whenAt 在窗内）
val due = memoryRepository.upcoming(now = now, windowMs = 7 * DAY)
    .filter { note -> (note.entities intersect nameSet).isNotEmpty() }
// 3) 通用经验仍走线索召回（谈判心得、我方偏好），不含人名也可
val general = memoryRepository.recall(
    terms = MemoryText.terms(profile.goal.orEmpty() + " " + increment.takeLast(400)),
    limit = 3, feedback = false,
)
```

ChatBriefBuilder 在 custom 段（v3 §6.2 模板）插入独立区块，**与 memoryContext
分轨渲染**——memoryContext 照常只放 core+general；按人事实进下面区块：

```
【交往脉络】（均为历史时点事实，供参考）
- 10-11 · 阶段：比价｜对方原话："50 万太贵，X 家才 42"
- 10-12 · 承诺：我方答应报价含一年免费运维（10-15 前给修订方案）
- 10-14 前待办 · 10-15 前发修订方案
【转折铁律】
脉络只代表当时。当前屏出现与脉络矛盾的信号（尤其拒绝→意向/成交信号）时，
一律以当前屏为准并立即顺应：停止说服与逼单，转入型号、数量、付款方式、
交付时间的闭环确认；脉络中的报价/承诺仍有效，成交回复须与之自洽。
```

渲染规则：entityNotes 按 `whenAt` 倒序、每条一行「MM-dd · kind 中文 · 内容
（≤80 字）」；due 置顶为待办；空则整段省略（无占位垃圾）。转折铁律仅绑定联系人
时注入，未绑定沿用现行 ADVISOR_STYLE。

时序保证：Day3 当回合即使 digest 仍旧（回合后才刷新），模型同时拿到**当前屏
全文**（永远权威）+ 带日期的旧脉络 + 转折铁律，弱模型也不会对已同意的客户逼单。

### 6.6 缺口 4：阶段跃迁写入 + 成交要件历史检索

**写入（唯一时机 = 用户在画像页 ✓ 确认，遵守 v3 §6.5 纪律）**：当确认的
intentStage 与旧 confirmed 值不同，除 persona_fields 更新外，双写一条带日期事件：

```kotlin
memoryRepository.addNote(
    kind = NoteKind.EVENT, source = NoteSource.USER, importance = 4,
    content = "${name} 意向阶段：$old → $new。依据：${field.evidence.take(80)}",
    whenAt = now,
    entities = listOf(name) + aliases,
    conversationId = currentConversationId,
)
```

旧阶段事实**不删不改**——EVENT 序列即时间线；FACT（预算/顾虑）按 v3 带
`expiresAt=+90d` 自然失效；承诺/约见写 `AGREEMENT/PLAN` + `whenAt`，供
`upcoming()` 自动浮现。全部经 addNote 的去重/合并/冲突裁决，重复确认不产生垃圾。

**成交要件网关**（当前屏出现成交/型号/价格信号且视口内缺要件时）：app 层新增
薄封装 `ContactHistoryGateway`（floatball 包，非 UI），调 v3 已核实的 public
`MessageDao.searchMessagesIn(conversationIds, pattern, from, to, limit)`，
范围 = 该 contactId 下**全部** session_bindings 会话（合并后多个也覆盖）：

- 检索词固定小集合：`报价|价格|多少钱|合同|账期|发票|型号|数量|交付|优惠`；
- 命中 ≤3 条、每条 ≤120 字、标注日期，作为「更早聊天中的成交要件」附在脉络段后；
- 零命中则不出兜底问句（多屏上滑拼接 v3 §6.6 已能覆盖近两屏，再缺才在建议末尾
  提示用户划出更早记录）。

### 6.7 文件清单与分期映射

| 文件（均在 `app/.../floatball/`，带 GPL 头） | 改动 | v3 期 |
| --- | --- | --- |
| `contact/ContactResolver.kt`（新） | L1 标题+规范化+L4 回查（v3 §11.1 的最小子集） | P2 提前 |
| `contact/ContactHistoryGateway.kt`（新） | searchMessagesIn 按人取成交要件 | P2 |
| `brief/ChatBriefBuilder.kt`（v3 新） | 交往脉络段 + 转折铁律 + 待办置顶 | P2 |
| `AdvisorHistory.kt`（已存在） | bound 档常量/分支 + digestLine 参数 | P2 |
| `ScreenAdvisor.kt`（已存在） | 会话路由；recallByEntity/upcoming/recall 三路取数；digest 按会话存储 | P2 |
| AdvisorDatabase `session_bindings`（v3 P1） | +digest/digestTurns/digestUpdatedAt 三列 | P1 |
| 画像确认流（v3 P3/P4） | intentStage 变化时 addNote(EVENT, whenAt) | P3 |
| strings.xml | float_* 文案（转折提示/待办标签等） | P2 |

零上游文件改动；不新增 Gradle 模块；OCR/记忆内容不落盘不发遥测，红线不变。

### 6.8 验收用例（实施后冒烟）

1. 造同一联系人 3 天会话（Day1 拒绝长文、Day2 嫌贵、Day3 同意），Day3 回合：
   建议为闭环确认而非继续说服；引用的报价/承诺与历史一致。
2. A、B 两客户各有不同报价：B 的回合脉络/要件中不出现 A 的任何数字。
3. Day3 后画像页确认 犹豫→促单：Note 表出现带 whenAt 的 EVENT；次日进入该聊天，
   脉络按日期倒序展示，承诺到期日由 upcoming 置顶。
4. 未绑定联系人：行为与当前版本完全一致（共享会话 + 现行折叠 + 全局 digest）。
5. 同画面二次触发短路、token 预算（绑定档历史 PRIOR ≤3200 + 脉络段 ≤600 字）
   不退化；logcat 无崩溃。

**实测结果（2026-10-14，证据图存 `tests/tempImg/v15~v20-*.png`，库快照同目录 `dbdev/`，均不入库）：**

1. **通过**。Day3 同意屏回合（conv2 id=94/104/108，`usageIn=4094/4362/4678`）
   全部为闭环口径（40 万+两次培训、分期、只确认成交要件、不再谈价），报价与
   Day1-2 历史一致；独特记忆点（私有部署）亦被当回合引用。
2. **通过**。李总 conv3 id=116（`usageIn=3813`）脉络与回答中无王总任何数字
   （40 万/42 万/35 万/两次现场培训均 0 命中）；两客户会话、记忆、binding 各自独立。
3. **部分通过 / P3 不阻塞**。脉络按日期倒序、到期承诺由 upcoming 置顶的取数路径
   已随 ①② 验证；但画像页确认「犹豫→促单」的 intentStage EVENT 双写属 P3，
   本期未实施，Note 表 EVENT 跃迁留待 P3 验收。
4. **通过**。桌面/系统壳新增聊天窗口闸门（§6.0 实测结论），主屏长按回落共享
   conv1（id=119/120），折叠与 digest 走现行共享档，未产生任何档案/会话/binding。
5. **通过**。同画面二次长按仅 Toast 短路、无新消息；绑定档 token 实测
   4094/4362/4678（PRIOR 历史 ≤3200 + 脉络段 ≤600 + 屏幕/系统提示）；
   全部轮次 logcat 无 FATAL。

**生产级加固（2026-06-24，spec：`docs/specs/2026-06-24-laocanmou-production-hardening/`）：**

- 修复 5 处正确性问题：绑定联系人回合不再回退共享 digest（防串味）；到期承诺
  改由 app 侧只读 `ContactMemoryReader` 按人取 AGREEMENT/PLAN（上游 upcoming()
  会 touch 反写权重且全局 limit=3 会饿死非头部联系人）；`findByKey` 改传
  `包名:规范化名`复合键（原裸 norm 精确路径永不命中）；MIGRATION_2_3 幂等补
  digest 三列（PRAGMA 判列，兼容两种开发期 v2 库形态）；移除
  fallbackToDestructiveMigration（漏迁移宁崩溃不清用户画像）。
- 健壮性/契约：回合后 digest+萃取加 Mutex 防慢 LLM 重复消耗；脉络段预算预扣
  转折铁律（整段硬 ≤600）；intentStage 双写 fact→event，确认即留带日期 EVENT
  时间线（「旧→新+依据」富文本仍属 P3）。
- 冒烟复测：编译/license/IDE 诊断 0 问题；王总 Day3 页回合正常成交口径
  （a11y 文本路径按测试壳「Chrome」标题归李总属夹具已知现象，生产 IM 窗口标题
  即人名）；桌面长按无新回合（去重短路）、contact_profiles 无「主屏幕」stub；
  conv2 王总 digest（covered=9）与各会话计数符合预期；logcat 无 FATAL。
