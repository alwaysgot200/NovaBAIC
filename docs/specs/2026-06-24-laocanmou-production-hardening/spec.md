# 好参谋跨时间对话线程（§6.3–6.8）生产级加固 Spec

日期：2026-06-24
分支：`laocanmou`
范围：对已实施并验收通过的 §6.3–6.8 代码做生产上线前质量加固。**零上游文件改动**，
全部改动落在 `app` 模块 `com.verlintas.baic2.floatball` 包（含 `app/src/main/assets/`）。

---

## 1. 背景与完成度结论（回答「这个需求有 P2？」）

§6（跨时间对话线程时序真实性）按 v3 分期落地，P1–P3 均有实质内容，不是「只有 P1」：

| 期 | §6 内容 | 落点 | 状态 |
| --- | --- | --- | --- |
| P1 | advisor 数据层 + `session_bindings` 的 digest 三列 | `advisor/data/`（AdvisorDatabase/Entities/Daos）、`ContactRepository`、assets | 已实施（迁移有缺陷，见 P1-4） |
| P2 | 会话路由、AdvisorHistory 双档、三路记忆、转折铁律、成交要件网关 | `ContactDirectory`/`AdvisorContactDirectory`、`AdvisorHistory`、`ContactBriefSection`、`ContactHistoryGateway`、`ScreenAdvisor` | 已实施并通过 §6.8 验收（4 处缺陷，本次修复） |
| P3 | 画像萃取 + 确认纪律 + 阶段跃迁 EVENT | `PersonaExtractor`、`ProfileMemorySync`、`persona_schema.json` | 萃取/确认已实施；阶段跃迁当前写成 90 天过期的 FACT，不形成时间线（见 P2-3） |
| P4 | 画像/话术管理 UI | `AdvisorProfileActivity`、`AdvisorPlaybookActivity` | 已实施（不在本次逐行评审范围） |
| P5 | 遗忘等 | `ProfileMemorySync.forgetContact` 已存在 | 后续期 |

本期需求（§6.3–6.8）业务闭环成立：识别 → 按人路由会话 → 双档历史+按人 digest →
三路记忆+转折铁律 → 流式参谋 → 回合后沉淀（digest/画像萃取）→ 画像页确认双写。
§6.8 验收 5 条中 4 条全通过、第 3 条（阶段跃迁 EVENT）此前已标注 P3 不阻塞；
本次 P2-3 用一行资产修正补齐其数据层本质，富文本（old→new+证据）仍留 P3。

## 2. 确认的问题清单与修复决策

### P1-1 共享 digest 串入绑定联系人回合（正确性，必修）

- 位置：`ScreenAdvisor.runAdvisorTurn`（约 L488–497）。
- 现状：`digest = boundDigest ?: prefs.screenDigest()`。联系人刚绑定、自有 digest
  尚为 null 时，**共享会话的全局 digest**（其他屏幕、不相干内容）会被注入该联系人
  的 AdvisorHistory 历史行——与此前已修的 `maybeRefreshDigest` 串味同类。
- 修复：

```kotlin
digest = if (contact != null) boundDigest else prefs.screenDigest(),
```

绑定联系人没有 digest 时返回 null（AdvisorHistory 已能处理 null digestLine）。

### P1-2 「看屏只读」纪律被 upstream() 破坏 + 全局 limit 饿死 + kind 不符契约（正确性，必修）

- 位置：`ScreenAdvisor.buildContactSection`（约 L622）调
  `memoryRepository.upcoming()`。
- 三个问题：
  1. `MemoryRepository.upcoming()` 内部对命中项执行 `touch()`（强化权重），与本回合
     「自动参谋只读、feedback=false」的注释纪律矛盾；被实体过滤掉的待办也被强化。
  2. upstream 先取**全局** limit=3 再按本联系人过滤，记忆库一忙，当前联系人的待办
     可能在过滤前就被截断（饿死）。
  3. upstream 的 upcoming 只收 `PLAN/EVENT`；§6.5 契约要求的是「承诺/约见
     （**AGREEMENT/PLAN**，whenAt 在窗内）」。本 app 画像确认写的是 AGREEMENT，
     导致「待办置顶」在真实数据下基本取不到东西。
- 上游不能改。`Baic2Database` 已由上游 Hilt 提供、`NoteDao.getByEntity(pattern,limit)`
  为 public、`ChatMapper.noteToModel()` 所在类为 public `@Singleton @Inject`。
- 修复：新增 app 侧只读薄封装 `contact/ContactMemoryReader.kt`：

```kotlin
@Singleton
class ContactMemoryReader @Inject constructor(
    private val db: Baic2Database,
    private val mapper: ChatMapper,
) {
    /**
     * 按联系人实体只读取窗内承诺/约见（§6.5：AGREEMENT/PLAN）。
     * 纯查询，不做 touch/强化；先按人过滤再截断，避免全局 limit 饿死。
     */
    suspend fun dueFor(
        entities: List<String>,
        now: Long = System.currentTimeMillis(),
        windowMs: Long = 7L * DAY,
        overdueMs: Long = 30L * DAY,
        limit: Int = DUE_LIMIT,
    ): List<Note>
}
```

  实现要点（与 upstream upcoming 保持同构，仅差异处不同）：
  - 实体名 trim/去空/distinct/take(6)；逐个 `MemoryText.entityLikePattern(name)`
    调 `getByEntity(ENTITY_PAGE)`，按 id 合并；
  - `mapper.noteToModel(row)`；
  - 过滤 `kind == AGREEMENT || kind == PLAN`、`whenAt != null` 且
    `whenAt in (now-overdueMs)..(now+windowMs)`；
  - `sortedBy { it.whenAt }.take(limit)`；**不调 touch**。
  - 常量：`DUE_LIMIT=3`、`ENTITY_PAGE` 取与 upstream 一致的分页常量值（实现时核对
    MemoryRepository 中 ENTITY_PAGE 数值）。
- `ScreenAdvisor` 构造注入 `ContactMemoryReader`，`buildContactSection` 改调
  `reader.dueFor(names)`；删除 `memoryRepository.upcoming(...)` 调用。
  `memoryRepository` 仍被 recall/recallByEntity 使用，保留注入。

### P1-3 精确键回查传参错误，精确路径永不命中（正确性，必修）

- 位置：`ContactRepository.resolve`（约 L88–91）。
- 现状：列存复合键 `imPackage:norm`（建 stub L111–119 用 `ContactNames.contactKey()`
  正确写入），回查却传裸 `norm` 给 `findByKey(imPackage, key)`，精确键永不命中，
  实际只靠别名/stub 侥幸归属。
- 修复：循环内改为
  `findByKey(imPackage, ContactNames.contactKey(imPackage, norm))`；
  变体（壳后缀剥离等）仍逐个尝试。编辑前重读该函数确认 import 与变体语义。

### P1-4 MIGRATION_2_3 缺 digest 三列（升级必崩，必修）

- 位置：`advisor/data/AdvisorDatabase.kt` 的 `MIGRATION_2_3`。
- 现状：v3 实体 `session_bindings` 含 `digest`/`digestCoveredTurns`/`digestUpdatedAt`，
  但 2→3 迁移只建画像表；v2 用户升级后任何按 digest 列的 SQL 都会
  `SQLiteException: no such column`。
- 修复：在 MIGRATION_2_3 的 `onMigrate` 中、建表语句之外补三条：

```sql
ALTER TABLE session_bindings ADD COLUMN digest TEXT
ALTER TABLE session_bindings ADD COLUMN digestCoveredTurns INTEGER NOT NULL DEFAULT 0
ALTER TABLE session_bindings ADD COLUMN digestUpdatedAt INTEGER
```

### P1-5 移除破坏性迁移兜底（生产红线，必修）

- 位置：`AdvisorDatabase` builder 的 `fallbackToDestructiveMigration(true)`。
- 现状：版本落差时**静默清空全部联系人画像/绑定**，local-first 红线不可接受；
  1→2、2→3 迁移已齐备。
- 修复：删除该调用。编辑时若 `:app` 已启用 BuildConfig，则改为
  `if (BuildConfig.DEBUG) fallbackToDestructiveMigration(true)`（开发友好、release
  严格）；若未启用 buildConfig 特性则直接删除，不为此开特性。

### P2-1 回合后沉淀任务并发可重复消耗 LLM（健壮性，修）

- 位置：`ScreenAdvisor` 回合完成处 launch 的 `maybeRefreshDigest` 与
  `personaExtractor.extractIfDue`。
- 现状：每回合各 launch 一次；LLM 慢 + 连续回合时，两个同类沉淀任务可并发重复跑
  （digest 重复生成、萃取双调用）。
- 修复：新增 `private val consolidationLock = Mutex()`；回合后块改为
  `if (consolidationLock.tryLock()) { try { digest; extract } finally { unlock() } }`
  ——上一回合沉淀未完成则本回合跳过。安全性：下次回合重新评估门槛；两类沉淀的素材
  都是从全量回合重算（digest 用 `completedScreenTurns` 全量、萃取用 since 之后全部
  消息），跳过一次不丢信息。

### P2-2 ContactBriefSection 600 字预算被转折铁律突破（契约一致性，修）

- 位置：`brief/ContactBriefSection.kt`。
- 现状：`appendLine` 受 SECTION_MAX=600 约束，但 `TURNING_RULE` 与段头在预算外
  `append`，整段可超约 120 字。
- 修复：行预算基线计入段头与 `TURNING_RULE.length`（即行可用预算 =
  SECTION_MAX − 段头长 − TURNING_RULE.length），保证整段不超 600；
  铁律仍始终保留。编辑时重读 render 全文后最小改动。

### P2-3 intentStage 双写类型 fact→event（§6.6 数据层补齐，修）

- 位置：`app/src/main/assets/advisor/persona_schema.json` L33。
- 现状：`intentStage` 的 `"memory": "fact"` → 确认时双写成 90 天过期 FACT，
  addNote 合并/裁决会以新压旧，**阶段变迁时间线消失**，与 §6.6「EVENT 序列即时间线」
  相悖。
- 修复：改为 `"memory": "event"`。`ProfileMemorySync` 已支持 MEMORY_EVENT
  （importance=4、whenAt=当天）；每次确认不同阶段产生不同内容的 EVENT，时间线成立。
  富文本「旧→新+依据」仍属 P3，不动。

### P3-1 过时 KDoc/注释（清理，修）

- `contact/ContactDirectory.kt` L49–53：删除对已不存在的 `EmptyContactDirectory`
  的描述，改为描述当前唯一实现 `AdvisorContactDirectory` 经 Hilt 绑定。
- `advisor/AdvisorDirectoryModule.kt` L29–32：去掉「P3 起……替换
  EmptyContactDirectory」措辞，改为现状陈述（advisor 包实现已生效）。

### P3-2 重复造轮子：两处 stripScreenLabel 合一（清理，修）

- 现状：`ContactHistoryGateway`（companion 外私有正则函数，每行消息新建 Regex）与
  `ScreenAdvisor.stripScreenLabel`（约 L460）各有一份近似实现。
- 修复：在 `ContactHistoryGateway.kt` 提升为 `internal fun stripScreenLabel(text)`
  顶层函数 + companion 持有编译一次的 `Regex("^【[^】]*】\\s*")`；
  `ScreenAdvisor` 删除私有实现并 import 共用函数。编辑时核对两者语义一致
  （ScreenAdvisor 版本若有额外规则则保留超集）。
- Gateway 内调用点改顶层函数；行为不变。

## 3. 明确不修（已核实为可接受/设计如此，报告中说明）

- `runAdvisorTurn` 方法偏长：可后续抽 ContactTurnPlanner，本次不重构（无功能风险，
  强改易引入回归）。
- `ContactHistoryGateway.dealFacts` 12 个串行 LIKE：均按会话 id 集合预过滤、
  ROWS_PER_KEYWORD=10、6 行即停，单机本地库规模下可接受；不过度设计。
- 拼接预检与 prepareTurn 各调一次 resolveContact：第二次取数更新鲜（用户可能切窗），
  属有意保留。
- 手动追问回合不纳入字符预算：设计要求整回合保护。
- 会话创建与 bind 跨两个 Room 库无事务：调用方 runCatching，且每次「先查后建」，
  失败可自愈；可接受。
- LLM prompt 片段（中文）留在代码 companion：属于提示词内容而非用户可见 UI 文案，
  与既有 `ADVISOR_STYLE` 模式一致；不挪 strings.xml。

## 4. 垃圾文件清理（tests/tempImg，已 gitignore）

- 保留：`sync.py`（推库工具）、`dbdev/`（回归夹具库）、`site/`（复测页面）、
  `v15*.png`–`v20*.png`（设计文档引用的证据图）。
- 删除一次性脚本/转储：`q*.py`、`mutate*.py`、`chk*.py`、`dump*.py`、`push.py`、
  `seed.py`、`schema.py`、`u.xml`、`anr_*.txt`、`trace*.txt`，以及执行时确认无用的
  其他一次性中间产物（删除前先列目录核对）。
- 不动 `dev/`（仅 3 个可复用脚本：build-gazetteer.py、两个 mock server）。

## 5. 受影响文件清单

| 文件 | 改动 |
| --- | --- |
| `app/.../floatball/ScreenAdvisor.kt` | P1-1 digest、P1-2 换 reader、P2-1 锁、P3-2 共用函数 |
| `app/.../floatball/contact/ContactMemoryReader.kt` | 新增（带 GPL-3.0 头，前 19 行仿同包文件） |
| `app/.../floatball/advisor/ContactRepository.kt` | P1-3 复合键 |
| `app/.../floatball/advisor/data/AdvisorDatabase.kt` | P1-4 迁移补列、P1-5 去破坏性兜底 |
| `app/.../floatball/brief/ContactBriefSection.kt` | P2-2 预算 |
| `app/src/main/assets/advisor/persona_schema.json` | P2-3 fact→event |
| `app/.../floatball/contact/ContactDirectory.kt` | P3-1 KDoc |
| `app/.../floatball/advisor/AdvisorDirectoryModule.kt` | P3-1 KDoc |
| `app/.../floatball/contact/ContactHistoryGateway.kt` | P3-2 共用函数+Regex 提顶 |
| `tests/tempImg/` 一次性产物 | 删除（见 §4） |

## 6. 验证步骤（提交前门槛）

1. License 门禁（WSL，CRLF 规避）：
   `C:\Windows\System32\bash.exe -lc "cd /mnt/e/_android_work/NovaBAIC && tr -d '\r' < tools/check-license-headers.sh | bash"`
2. 编译：`.\gradlew :app:assembleDebug`（PowerShell 7，JDK17）。
3. IDE 诊断：所有改动文件 0 error/warning（GetDiagnostics）。
4. 设备冒烟（emulator-5554，如在线）：
   install -r → monkey 启动（保活进程）→ 进程存活时授予 a11y + SAW；
   重启测试站 `python -m http.server 8765 --directory tests\tempImg\site` +
   `adb reverse tcp:8765 tcp:8765`；
   a. Chrome 打开王总 Day3 页 → 单击球 → 面板出现、状态栏显示绑定「王总」、
      流式回答成交导向且含 40 万自洽信息；
   b. 回桌面 → 单击球 → 走共享会话（不新建「主屏幕」stub）；
   c. `adb logcat -d | grep -E "FATAL|AndroidRuntime"` 为空；
   d. 查 advisor.db：新回合后 session_bindings digest 行为正常（如可操作）。
5. 输出审查报告（完成度/业务闭环/性能/边界/解耦/问题-修复对照/mermaid 流程图）。
