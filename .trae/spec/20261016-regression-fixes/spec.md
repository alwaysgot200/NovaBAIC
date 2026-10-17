# 回归修复规格：授权劫持前台 / 会话串台 / 球消失 / 发送键卡死（2026-10-16）

## 0. 用户报告（真机回归）

1. 百度新闻页长按进单次参谋 → 面板仍显示「当前联系人：李总」+ 旧聊天对话，
   截屏内容未见。**用户要求：匹配不到联系人/旧会话时新建会话；能匹配才复用历史会话。**
2. 分屏右下角「发送」按钮显示成停止方块；面板弹出时悬浮球消失，关闭面板才恢复。

## 1. 根因（静态分析 + 模拟器复现证实）

| # | 症状 | 根因 | 位置 |
|---|------|------|------|
| 1a | 截到主应用旧聊天（李总） | FloatCaptureActivity 无 taskAffinity，NEW_TASK 落进主应用任务栈（task 46）；授权 finish 后前台=主应用而非 Chrome | AndroidManifest.xml:89-94 |
| 1b | 李总上下文串台 | 非联系人回合恒复用持久化共享会话（ensureSharedConversation 只认 prefs id） | ScreenAdvisor.kt:885/1863 |
| 2a | 发送键卡停止方块 | finishOneShot 在 askJob 协程 finally 内同步 refresh，此刻 isActive=true → isBusy()=true，之后无人再刷新 | FloatBallService.kt:464-473 |
| 2b | 面板开着球消失 | ensureSplit 非录制态 hideAllOverlays 撤球（:657）+ restoreBall 拒绝 split!=null 时恢复（:1384），与 finishOneShot 注释「恢复蓝球（分屏留着展示结果）」矛盾 | FloatBallService.kt:657/1384 |
| 附 | 投影指示灯回合后不灭 | 授权即启投影；a11y 文本/无障碍截图路径不经过投影释放，无人关灯 | runOneShot finally(:453) |

## 2. 修改清单（3 个文件，均纯增量）

### 2.1 AndroidManifest.xml（白名单文件，追加 1 个属性）

FloatCaptureActivity 声明追加 `android:taskAffinity=""`：
NEW_TASK + 空 affinity → 启动到独立任务，finish 后回到用户原前台 App
（Chrome/新闻页）；已有 excludeFromRecents / noHistory / 启动 flags 不动。

### 2.2 ScreenAdvisor.kt — 非联系人会话路由（问题 1b）

- 新增 companion 常量 `SHARED_REUSE_SIMILARITY = 0.45f`。
- `prepareTurn`(:825)：调用改为 `resolveConversation(contact, question)`。
- `resolveConversation`(:885) 签名加 `incoming: String`，三分支：
  1. contact != null → 联系人专属会话（原逻辑不动）；
  2. incoming **不带**屏幕前缀（手动输入行追问，submitTurn 原文入口）→
     延续面板当前会话：`_transcript.value.conversationId > 0` 时
     `subscribeConversation(current)` 并返回；否则 `ensureSharedConversation()`；
  3. incoming **带**屏幕前缀（单次/连续自动屏幕回合，emitScreenTurn:777 与
     FrameDiffEngine:119 均经 autoScreenContent 包前缀）→
     新方法 `resolveSharedConversation(stripScreenLabel(incoming))`。
- 新增 `resolveSharedConversation(screenBody: String): Long`：
  取 prefs 会话 + `historyGateway.latestScreenBody`；
  - 无屏幕消息（空会话/仅手动消息）→ 复用；
  - `FrameQuality.textSimilarity(screenBody, stripScreenLabel(last)) >=
    SHARED_REUSE_SIMILARITY` → 复用（同场景连续滚动/追问）；
  - 否则 `conversationRepository.create` 新会话 + `prefs.setConversationId(新id)`；
    旧会话保留在库（主界面可回看）。
- `ensureSharedConversation`(:1863) 保留，作手动路径兜底。

### 2.3 FloatBallService.kt — 三处小修 + 一处兜底

1. **finishOneShot（问题 2a）**：加 `askJob = null`（对齐 manualJob 收尾口径）；
   `inputHandle?.refresh()` 改 `mainHandler.post { inputHandle?.refresh() }`
   （post 到队列尾=协程完成后执行，isActive 已为 false，图标真正复位）。
2. **ensureSplit（问题 2b）**：`if (mode != Mode.RECORDING) hideAllOverlays()`
   改为 `dismissMenuNow()`（菜单与分屏不共存；球保留可见，可拖动避让面板）。
3. **restoreBall（问题 2b）**：条件去掉 `split == null` →
   `if (ball == null && menuView == null) showBall()`。
4. **runOneShot finally（投影灯兜底）**：`finishOneShot()` 后追加
   `screenAdvisor.releaseProjection()`（runCatching 幂等，:497 已有调用先例；
   NeedsConsent 首轮/拒绝路径均为无投影 no-op）。

无 strings.xml 改动（无新用户可见文案）。

## 3. 影响面与不变量

- 联系人绑定会话路径**完全不动**（含 L1/L2 识别、ensureAdvisorConversation）。
- 去重顺序不变：先路由会话、后 isDuplicateScreen —— 同页重复长按走
  Unchanged 短路，**不会连开新会话**。
- 手动追问延续「面板正在展示的会话」（含联系人会话），语义=续问当前话题。
- 连续模式同一张聊天里滚动：相邻帧相似 ≥0.45 → 停留同一会话；
  切到别的 App → 自动换新会话（符合"看什么聊什么"）。
- 录制态行为不变（红球+掩膜；ensureSplit 录制分支本就不藏球）。
- 截屏洁净度（铁律 §8.1）：球在采集期间本就可见（纯 logo 无文字，不污染
  OCR）；面板文字仍在回合开始后才出现，不变量保持。
- ensureSplit:722 已用 `activeContact.value` 初始化状态条——路由修复后
  新闻页回合 status 即为「未绑定」，无需额外改。

## 4. 追加修复（T9，验证中由 DB 证据发现）

复验取库证实：面板开着时二次长按，投影把面板文字拍进画面 → 内容污染
使路由误判"换场景"（3 次长按产生 2 个新会话）且去重失效——违反铁律
§8.1「截屏前必须清空自身浮层」。修复（仅 FloatBallService.kt）：

- 新增 `splitDetached` 标志 + `detachSplitWindow()`/`reattachSplitWindow()`
  （removeView/addView 往返，视图与输入状态全保留）。
- `startOneShot` 像素分支（needsHide）与 `runOneShot`（preheated==null，
  含授权重跑）采集前 `detachSplitWindow()`。
- `ensureSplit` 顶部：面板存在且已摘窗 → 挂回再返回（TurnStarted/
  Failed/异常路径全覆盖）。
- Unchanged 分支、授权拒绝、consentWatchdog：挂回面板（用户保留旧结果视图）。
- `closeSplit`/`removeAllOverlays`：复位 `splitDetached`。
- NeedsConsent 等待期保持摘窗（授权后重跑的采集依然干净）。

## 5. 追加修复（T10，ANR 排查中由 logcat/trace 证实）

T9 后装机复验触发进程崩溃：`ForegroundServiceDidNotStartInTimeException
(MediaProjectionService)`。完整链路（logcat 证据）：

1. 授权同意 → `onPermissionResult` → `startForegroundService(MediaProjectionService)`
   （系统 15:29:59.742 允许）；
2. `runOneShot` 重跑（`Main.immediate` 同步进入 `adviseOnce`）→
   `quickPerceive` → `projectionPerception` 检查 `ready.value` —— 投影服务仍在
   启动（onStartCommand 未跑）→ 误判 `NeedsProjection` → 发射 `NeedsConsent`；
3. flow 结束 → **T5 的 finally `releaseProjection()` → stopService** —— 服务
   `startForeground` 尚未执行 → 系统 15:29:59.930 "Bringing down service while
   still waiting for start foreground" → 崩溃。

两处缺陷：① 就绪检查与投影服务异步启动存在竞态；② finally 在"等待再次授权"
终止时也不该释放投影（此刻投影服务处于 startForegroundService→startForeground
窗口期，stopService 必崩）。修复（floatball 包内，签名均加默认参数纯增量）：

- **ScreenPerceiver.kt**：`projectionPerception(awaitReady: Boolean = false)` —— 
  授权后重跑传 true，跳过 `ready.value` 早期退出，直接 `capture()`（内部已自带
  5 秒就绪等待与失败语义）；`quickPerceive` 透传同名参数。
- **ScreenAdvisor.kt**：`adviseOnce(preheated, awaitProjection: Boolean = false)`
  透传。
- **FloatBallService.kt**：`runOneShot` 加 `awaitProjection: Boolean = false`；
  授权同意的重跑 `runOneShot(null, CONSENT_SETTLE_MS, awaitProjection = true)`；
  finally 加 `awaitingConsent` 本地标志——NeedsConsent 分支置 true，为 true 时
  **跳过** releaseProjection（首轮无投影 no-op；重跑竞态窗口不再触发 stopService）。

附带结论（不改代码）：15:16 的 "executing service FloatBallService" ANR 与
01:39 旧构建的 Baic2AccessibilityService ANR 同签名（冷启动 27 个 dex + 页错误
风暴 + 模拟器高负载下服务超时），属环境性问题，非本分支回归。

## 6. 追加修复（T11，问题 1 的真正根因：联系人误绑定）

T2 修复的是**路由**，但复验 DB 取证发现识别本身就错了：15:43 新闻页回合
写进了李总会话（conv 3，today_msgs 22→24），面板显示「当前联系人：李总」。
证据链（advisor.db）：

1. alias 表 15:43:49 新闻页回合写入 rawAlias=「Chrome」——Chrome 的 a11y
   窗口标题是**常量「Chrome」**（=应用标签，与页面内容无关）；
2. 精确键 `com.android.chrome:chrome` 命中 profile 4（displayName=李总，
   历史上由壳标题建档后经用户改名）→ **所有 Chrome 页面回合全部绑到李总**；
3. 同源污染：懒建 Stub（createStub=true）让「老参谋」「联系人画像」「数字健康」
   等应用壳标题成为联系人档案（profile 6/7/8）。

设计 §11.1 的 L1 假设「窗口标题=聊天对象名」仅对真实 IM（微信）成立。

### 修复（4 文件，均 floatball 包内）

- **ScreenAdvisor.kt**：
  - `resolveContact(titleFallback, screenBody)`——L1 windowTitle 与 L2 标题带
    均先过**壳标题过滤** `isShellTitle(pkg, title)`（normalize 后与应用标签相等
    → 视为无标题；应用标签用 ConcurrentHashMap 缓存）；
  - 新增内容级兜底：`IncomingCue.speakerOf(screenBody)` 提取聊天结构发言人
    → `resolve(pkg, speaker, allowStub=false)` **只认存量键/别名，绝不新建**
    （mock 聊天页「李总：…」×3 +「我：…」→ findByAlias 命中李总档案，
    保住 tests/tempImg/site 模拟聊天测试链路）；
  - `prepareTurn`：自动屏幕回合才传 screenBody；**手动追问不重新识别**
    （保持当前 activeContact，避免面板会话中途状态条翻「未绑定」）；
  - `tryMultiScreenStitch`：resolveContact 传入 preheated 作 screenBody；
  - `isChatWindow`：自身包名（`context.packageName`）排除——主应用不是 IM。
- **IncomingCue.kt**：新增纯函数 `speakerOf(body)`（有「我」发言、其余发言人
  唯一且 ≥2 行才返回该名；文章/新闻无对话结构返回 null）+ JVM 单测。
- **ContactDirectory.kt**（接口）：`resolve` 加 `allowStub: Boolean = true`
  默认参数（纯增量）；**AdvisorContactDirectory.kt** 透传。
- **数据不动**（铁律：保留历史数据）：profile 4 经壳过滤后 L1 不可达，
  经「李总」别名仍可被内容兜底命中；conv 3 中误入的 2 条新闻消息保留。

## 7. 验证（提交前门槛）

1. `./gradlew :app:testDebugUnitTest`（113 既有单测全绿 + speakerOf 新测）。
2. `./gradlew :app:assembleDebug` + 装机。
3. 模拟器复验（对照上次复现路径逐条断言）：
   - a. Chrome 打开 news.baidu.com → 长按球 →（a11y 开，文本路径）→
     状态条「未绑定」、**不进李总会话**（DB 断言 conv 3 无新增消息）、
     新屏内容落新/复用共享会话；
   - b. Chrome 打开模拟聊天页（tests/tempImg/site/li.html）→ 长按球 →
     状态条「当前联系人：李总」、回合落 conv 3（T11 内容兜底回归）；
   - c. 授权路径（关 a11y → 长按 → 投影授权）→ 无
     ForegroundServiceDidNotStartInTimeException 崩溃（T10 回归）；
   - d. 面板开启期间球可见（可拖动）；回合完成后发送键为发送图标（T3/T4 回归）；
   - e. 同页再长按 → 「画面与上次几乎相同」toast，球复位正常；
   - f. 主应用内长按 → 不新建联系人档案（T11 自包排除）；
   - g. 连续模式短跑：同一页面多帧同会话，切页面换新会话；
   - h. logcat 无 FATAL/ANR；投影指示灯回合结束即灭。
4. 证据图只进 `tests/tempImg/`，验证后清理。

## 8. 追加修复（T12，场景 g 复验中发现的两处连续模式缺陷）

### 8.1 授权看门狗竞态（录制永不启动）

**现象**：`record` → 授权对话框 → 用户确认「共享屏幕」后录制不启动
（球回蓝、无投影、无回合），授权结果被静默吞掉。

**根因**：`CONSENT_WATCHDOG_MS=120s`，慢速人工授权（实测 119s：三步
点击选范围）与看门狗竞态——watchdog 先触发 `abortRecording()` +
`FloatCaptureBridge.clear()`，随后到达的授权结果因桥已清空被丢弃；
即使结果先到，`postDelayed` 回调里 `mode != RECORDING` 检查也会因
watchdog 抢先复位而走 `releaseProjection()`。

**修复**：`CONSENT_WATCHDOG_MS 120s → 600s`。watchdog 只防「中转
Activity 结果永不回来」的进程级孤儿，而 ActivityResult 机制保证对话框
确认/取消必回调；放宽不改变兜底语义。

### 8.2 共享会话回看（A→B→A 误开新会话）

**现象**：连续模式 李总页→地址栏→李总页，同一聊天页的两轮回合分裂到
conv 14/16 两个会话（"同页多帧同会话"断言失败）。

**根因**：`resolveSharedConversation` 只与「当前偏好会话」的最后一条
屏幕消息比对相似度；偏好会话是 B（地址栏页）时，切回 A 的回合与 B
不相似 → 直接新建。像素 OCR 漏认联系人（"李总"→"季总"错字）叠加放大
了该路径的触发频率。

**修复**（ScreenAdvisor + ContactHistoryGateway，均 floatball 包）：
新增 `recentScreenBodies(labelPrefix, limit)` 跨会话回看（主键倒序
有界扫描最近 12 条自动屏幕消息）；偏好会话不相似时按新→旧逐条比对，
命中即回到原场景会话并更新偏好。副作用为正：OCR 漏认联系人的回合也
借内容相似找回原会话。

### 8.3 场景 g/h 复验结论（2026-10-16 17:2x，T12 装机后）

- g 主链路：授权（>120s 慢速人工操作）→ 红球 + toast「连续参谋已开启」
  + 状态栏录屏指示；record-stop 后球回蓝、指示灭（t12_g2/g3.png）；
- g 会话粘性：A（李总页 msg 200/201）→ B（aidu.de，与 conv 17 内容
  重复被去重拦截）→ A'（李总页，与 conv 16 msg 200 重复被去重拦截），
  同页同会话、切页换会话均达成；msg 200 落 conv 16 为回看命中铁证
  （修复前必新建会话）；
- h：logcat 无 FATAL/ANR。

### 8.4 已知限制（不修，记录在案）

连续模式像素 OCR 路径的错字（"李总"→"季总"）会拉低联系人识别率；
宁漏绑不误绑（用户刚经历过误绑定回归），漏绑回合由 §8.2 的内容相似
回看兜底回原会话。
