# 「微信 7 步测试夹具」TDD 用例方案 · 双模式参数设计 + 双线真机验证 + 文件交换 v3

- 日期：2026-10-31｜分支：`laocanmou`｜状态：**待批准**（批准前不动代码）
- 文档定位（用户定案 v3）：**本文档就是「微信 7 步测试夹具」这个 TDD 用例的用例文档**——用例怎么定义、双模式参数怎么分别设计、边界怎么提示退出、脚本怎么变成文件上云，全部以这个用例为主线写清，并给出**全部动作类型的标准普适参数设计**（用例是示例，参数框架对所有动作成立）。
- 需求来源（用户原话要点）：
  1. 同一个动作，**双模式下的参数分别设计指定**；某模式前置不具备 → ①新建时该模式不可选，②编辑动作时该模式专有参数**灰色不可用**（例：新建只选 a11y → 无 a11y 专有参数不可用）。
  2. 夹具在双模式下**分别（a11y 线 / 无 a11y 线）跑通真机测试**。
  3. 夹具**就是 TDD 开发模式的用例**：两种模式**设计兼容、独立运行**；**前置条件不具备时的边界处理**（例：机器没有微信 App → 怎样提示并结束退出）；**RPA 脚本导入/导出为文件、可上传云端**，文件语法详细解释、考虑未来升级兼容。
  4.（v3 修订）**所有动作类型的参数设计要标准普适**；测试分级术语不用「JVM」，用「本地测试 / 真机测试」。
- 约束（AGENTS.md / 用户铁律）：UI 只留简洁文案（「a11y（无障碍）」「无 a11y（shizuku）」），技术原理只活在本文档与代码注释；双模铁律 §4.5（a11y 硬前置显式提示、两线独立交付、禁止静默换线）；文案进 `strings.xml`（zh/en）；GPL 头；改动只落 `app/.../floatball/rpa/`、`vendor/RPA/`、测试源集。

---

## 〇、TDD 用例生命周期

```
红：用例先行——夹具 + 断言先落盘（本地测试 / 真机测试 / 边界测试），失败暴露差距
绿：实现 §四 参数框架、§六 边界、§七 文件、§八 云端、§九 UI，逐条转绿
重构：全绿后按标准普适框架清理非夹具动作，全动作回归
```

### 〇.1 测试分级（术语口径：本地测试 / 真机测试）

> 说明：代码是 Kotlin；测试按**运行位置**分两级——本地测试跑在开发机（工具链自带的 JVM 上，故不提），真机测试跑在 Android 设备上。文档与用例命名一律用「本地测试」「真机测试」。

| 级 | 用例 | 断言 |
| --- | --- | --- |
| 本地测试·结构 | `RpaWechatHelloFixtureTest` | 7 步结构、双线参数各自完整（互不依赖）、文件编解码往返 |
| 本地测试·行为 | `RpaRunnerBehaviorTest` | 锁线执行无换线、缺 App/缺参数显式失败并干净结束、边界提示事件 |
| 真机测试·a11y 线 | `RpaWechatHelloDeviceTest -e runLine a11y` | 7 步全 ok、逐步 line=A11Y、无 LineFallback、屏现「你好」 |
| 真机测试·无 a11y 线 | `RpaWechatHelloDeviceTest -e runLine vision` | 7 步全 ok、逐步 line=VISION、无 LineFallback、屏现「你好」 |
| 真机测试·边界 | 同测试 `-e imPackage <未安装包名>` | 提示「应用未安装，运行结束」并干净退出（不挂起、不崩溃） |

### 〇.2 双模式设计兼容、独立运行（用例级规约）

- **独立运行**：夹具每步的 a11y 专有参数自足、无 a11y 专有参数自足——锁 a11y 线只消费节点参数即完成 7 步，锁无 a11y 线只消费视觉/注入参数即完成 7 步，**两线互不引用**（兜底只在本线内部，如 vision 线 OCR→比例兜底；禁止「a11y 线借 vision 坐标」交叉依赖）。
- **设计兼容**：公共参数两线共享；专有参数分线各自演进，某线新增字段不影响另一线（数据/校验/引擎三层按线隔离）。
- **模式兼容**：双模式脚本 = 两套专有参数并存（校验强制双写）；单模式脚本只写本线，另一线允许缺失，导入后切双模式按空侧报缺引导补写。
- **锁线运行**（`forceLine`）是两线独立验证的唯一口径；自动选线（DUAL 优先 a11y、失败显式 `LineFallback`）只用于产品运行，不用于用例验收。

## 一、全动作类型标准普适参数框架（v3 核心）

**任何动作的参数都由四类组成**（标准结构，普适于全部 18 个动作类型）：

| 类 | 内容 | 线别 |
| --- | --- | --- |
| **A. 公共执行参数** | label、enabled、timeoutMs、retries、delayBeforeMs、delayAfterMs、repeat、onFailure、runCondition、note | 两线共享 |
| **B. 动作载荷参数** | 动作要「做什么」的数据（文本、包名、按键、方向、URL、变量表……） | 两线共享 |
| **C. 定位参数**（needsTarget 动作） | 目标「在哪」：a11y 节点选择器组 / 无 a11y 视觉定位组 | **分线各自指定** |
| **D. 分线通道参数** | 「怎么执行」随线变化的部分（输入通道、写入方式、启动完成判定、锚定节点……） | **分线各自指定** |

C 类定位参数标准结构（TAP 族 / WAIT_FOR_TARGET 通用）：

| 线 | 标准字段 |
| --- | --- |
| a11y 专有 | 选择器组 `selectors[]`（text / textMatch / viewId / description / className / clickableOnly / editableOnly / enabledOnly，条间 OR、条内 AND）+ 多命中选取 `pick`（FIRST/TOP_MOST/BOTTOM_MOST/LARGEST/NEAREST）+ 节点动作 `action`（CLICK/LONG_CLICK/READ）+ 节点快照兜底 `snapshot` + 备注 |
| 无 a11y 专有 | 主定位 `primary` + 兜底 `fallback`：`kind`（TEMPLATE/OCR_TEXT/RATIO）三选一；模板：templateId + threshold(0.5–0.99) + grayscale + region(ROI 四值可越界) + matchIndex + quickSearch + embedMode；OCR：ocrText + ocrMatch + region；比例：ratioX/ratioY（可越界 -0.2–1.2）+ offsetX/Y；统一 `pick` + 备注 |

D 类分线通道参数标准字段（按动作取用）：

| 字段 | 线 | 适用动作 | 语义 |
| --- | --- | --- | --- |
| `readySelector`（节点选择器） | a11y | LAUNCH_APP / OPEN_CHAT | 启动完成判定：等待节点出现 |
| `readyText`（文本） | 无 a11y | LAUNCH_APP / OPEN_CHAT | 启动完成判定：等待屏幕出现文本（OCR） |
| `inputSelector`（editable 节点） | a11y | INPUT_TEXT | 目标输入框（空=当前焦点） |
| `appendMode`（bool） | a11y | INPUT_TEXT | 写入方式：替换（SET_TEXT）/ 追加 |
| `inputChannel`（枚举） | 无 a11y | INPUT_TEXT | CLIPBOARD_PASTE（剪贴板粘贴）/ SHELL_INPUT（shell input text） |
| `clearBeforeInput`（bool + 键序） | 无 a11y | INPUT_TEXT | 清空原内容（全选+删除） |
| `focusPoint`（比例点） | 无 a11y | INPUT_TEXT | 输入前先点一下焦点（选填） |
| `anchorSelector`（节点） | a11y | SWIPE / SCROLL | 以节点为锚点滑动（选填） |
| `startPoint/endPoint`（比例点） | 无 a11y | SWIPE / SCROLL | 起止点（缺省按方向+距离居中换算） |
| `readScope`（节点选择器） | a11y | ASSERT_TEXT / SET_VARIABLE(UI_READ) | 读屏来源范围（空=全屏） |
| `ocrRegion`（ROI） | 无 a11y | ASSERT_TEXT / SET_VARIABLE(UI_READ) | OCR 读取区域 |

### 1.1 全动作类型 × 参数矩阵（标准普适定案）

图例：●=本线专有按标准字段给；○=无本线专有参数（仅 A+B）；—=本线不适用（P2 占位）。

| # | 动作 | B 载荷（共享） | C 定位 | D a11y 专有 | D 无 a11y 专有 | 用例涉及 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | LAUNCH_APP | packageName、restart、closeOnly | — | ● readySelector | ● readyText | 步骤1 |
| 2 | OPEN_CHAT | imPackage、contactName | — | ● readySelector | ● readyText | — |
| 3 | WAIT | waitMs | — | ○ | ○ | — |
| 4 | WAIT_FOR_TARGET | — | ● C 标准双组 | ○ | ○ | — |
| 5 | TAP | holdMs | ● C 标准双组 | ● pick/action/snapshot | ● primary/fallback 全组 | 步骤2/4/5/7 |
| 6 | LONG_PRESS | holdMs | ● C 标准双组 | 同 TAP | 同 TAP | — |
| 7 | INPUT_TEXT | text | — | ● inputSelector、appendMode | ● inputChannel、clearBeforeInput、focusPoint | 步骤3/6 |
| 8 | PRESS_KEY | key（back/home/recents/notifications/enter） | — | ○（无障碍全局动作） | ○（input keyevent） | — |
| 9 | SWIPE | direction、distanceRatio、durationMs | — | ● anchorSelector | ● startPoint/endPoint | — |
| 10 | SCROLL | direction、distanceRatio | — | ● anchorSelector（scrollable 节点） | ● startPoint/endPoint | — |
| 11 | ASSERT_TEXT | expect{text,match,timeoutMs} | — | ● readScope | ● ocrRegion | 测试断言用 |
| 12 | COMMENT | 文本 | — | ○ | ○ | — |
| 13 | OPEN_LINK | url（scheme 白名单） | — | ○（startActivity 线路无关） | ○ | — |
| 14 | SET_VARIABLE | assignments[]（变量名/类型/来源/值） | — | ● readScope（UI_READ 时） | ● ocrRegion（UI_READ 时） | 变量预置可选 |
| 15 | CONTROL_FLOW | flowKind、gotoStepId、runCondition | — | ○ | ○ | — |
| 16 | COMPOSITE | customActionId、customArgValues | — | ○ | ○ | — |
| 17 | TAP_COLOR（P2） | 颜色值、容差 | ● C（region） | —（无 a11y 概念） | ● 全部（颜色匹配参数） | — |
| 18 | GESTURE_MULTI（P2） | 手势路径 | — | — | ● 多指路径坐标 | — |

实现口径：C/D 类字段全部按线存入 `RpaTarget` + `RpaStep.lineParams`（§五）；**P2 动作（17/18）维持占位置灰**，参数表先定标准以保证未来实现不破坏文件兼容（§七.5）。

## 二、「微信 7 步测试夹具」用例规格（TDD 用例正文）

夹具代码：[RpaWechatHelloFixture.kt](../../../app/src/sharedTest/java/com/verlintas/baic2/floatball/rpa/RpaWechatHelloFixture.kt)。流程：启动微信 → 搜索图标 → 输入联系人 → 选中联系人 → 点击输入框 → 输入「你好」→ 点击发送。断言不占第 8 步：测试代码在脚本结束后检查「你好」在屏。

### 2.1 逐步规格（双模式两套参数分别指定）

**步骤1 启动微信（LAUNCH_APP）**｜公共：应用 `${imPackage}`=com.tencent.mm、运行等待 5s、超时 15s｜a11y：readySelector= text 含「微信」节点（选填；2026-10-31 真机取证：微信 8.x 树里带文本的节点均 clickable=false、可点击的是外层容器，点击按坐标派发天然命中容器，故与拾取回填同语义只设 enabledOnly 不设 clickableOnly）｜无 a11y：readyText=「微信」（选填）。启动语义=**应用首页**（2026-10-31 真机定案）：纯 `FLAG_ACTIVITY_NEW_TASK` 对已有任务是「带到前台即完」，微信停在聊天页时 launcher intent 被顶页无效投递，readySelector/readyText 所等的首页「微信」文本永不出现 → `launch_timeout`；故 launchApp 启动 intent 带 `FLAG_ACTIVITY_CLEAR_TOP` 清掉入口之上的页面，启动完成判定与任务残留状态无关。投递通道（同日续案）：部分系统会静默丢弃后台进程的 `startActivity`（HyperOS「后台弹出界面」MIUIOP 10021 实测拦截：调用正常返回但界面不动，误报 `launch_timeout`），故 shell 通道（Shizuku，shell 身份）优先投递 `am start -f NEW_TASK|CLEAR_TOP`，无 shell 通道回退 `startActivity`；2026-10-31 真机以「微信停在聊天页」最坏态验证通过。

**步骤2 点击搜索图标（TAP）**｜公共：按下 100ms｜a11y：selectors=[desc 含「搜索」, viewId=search]、pick=FIRST、action=CLICK｜无 a11y：primary=RATIO(80.6%, 5.8%)（真机 1080×2460 校准值，微信 8.0.79 右上搜索放大镜 x≈870）。

**步骤3 输入联系人（INPUT_TEXT）**｜公共：text=`${contactName}`、超时 5s｜a11y：inputSelector=空（沿用焦点）、appendMode=替换（SET_TEXT）｜无 a11y：inputChannel=CLIPBOARD_PASTE、clearBeforeInput=是（全选+删除）。

**步骤4 点击联系人（TAP）**｜a11y：selectors=[text 精确 `${contactName}`（EXACT，className=TextView，enabledOnly）]、pick=FIRST（2026-10-31 真机取证：FTS 结果页搜索框 EditText 的 text 恰为刚输入的完整联系人名，CONTAINS 会先命中搜索框点空；「包含:」聊天记录行 text 也含全名；结果行名节点是 `com.tencent.mm:id/odf` TextView 且 text 恰为完整显示名，EXACT+TextView 唯一锁定）｜无 a11y：primary=OCR_TEXT(`${contactName}`，区域 12%–75%，pick=TOP_MOST)、fallback=RATIO(50%, 28%)（本线内兜底）。

**步骤5 点击聊天输入框（TAP）**｜a11y：selectors=[editableOnly]、pick=BOTTOM_MOST｜无 a11y：primary=RATIO(50%, 94%)（输入栏 y≈2280–2400 真机校准）。

**步骤6 输入「你好」（INPUT_TEXT）**｜同步骤3，text=你好。

**步骤7 点击发送（TAP）**｜a11y：selectors=[text 含「发送」, desc 含「发送」]、pick=BOTTOM_MOST｜无 a11y：primary=OCR_TEXT(「发送」，区域 50%–100%)、fallback=RATIO(87.1%, 55.2%)（键盘弹起后校准）。

### 2.2 用例验收断言

1. 锁 a11y 线 / 锁无 a11y 线**各**全 7 步 ok；2. 逐步事件 `line=` 全为所锁线路、**零 `LineFallback`**；3. 结束后聊天页屏文本出现「你好」；4. 边界变体（`imPackage` 传未安装包名）按 §六 B1 提示并干净退出。

## 三、现状基线与差距

| 层 | 现状 | 差距 |
| --- | --- | --- |
| 数据模型 | `RpaTarget{a11y, vision}` 已分线（[RpaModels.kt](../../../vendor/RPA/src/main/java/com/verlintas/rpa/model/RpaModels.kt)） | D 类分线通道参数（INPUT_TEXT/LAUNCH_APP/SWIPE 等）无存储 |
| 动作编辑 | [RpaStepDialog.kt](../../../app/src/main/java/com/verlintas/baic2/floatball/rpa/RpaStepDialog.kt) 单份混合表单 | 无「公共 / a11y / 无 a11y」分区、无置灰、无 D 类字段 |
| 新建脚本 | `CreateScriptDialog` 按就绪**隐藏**模式项 | 应「显示但置灰+原因」 |
| 引擎 | `forceLine` 锁线、按线分流执行已有 | D 类通道参数未消费；app_missing 预检/兜底不足 |
| 文件 | JSON/XML codec + `.rpa` 包已有（[RpaScriptCodec.kt](../../../vendor/RPA/src/main/java/com/verlintas/rpa/store/RpaScriptCodec.kt)、[RpaScriptPackage.kt](../../../vendor/RPA/src/main/java/com/verlintas/rpa/store/RpaScriptPackage.kt)） | 语法未成文规范（§七补齐）；云端流程未定义（§八） |
| 真机测试 | 自动选线跑夹具 | 未双线锁线；无边界用例 |

## 四、UI：新建置灰 + 编辑分组置灰

### 4.1 新建脚本（CreateScriptDialog）

- 三枚模式芯片**恒显示**（a11y（无障碍）/ 无 a11y（shizuku）/ 双模式）；前置不具备 → **置灰不可选** + 一行简洁原因 + 修复按钮：a11y 不具备→「无障碍未开启」+「去开启」；无 a11y 不具备→「Shizuku 未就绪」+「打开 Shizuku」；双模式需两线都就绪。
- `RpaModeSelector` 增加 `disabledModes: Map<RpaMode, String>` 参数（置灰 + 原因）。

### 4.2 动作编辑弹框（RpaStepDialog）

- 字段区按 §一 四类渲染：A/B 类在**公共段**（无组头）→ 组头「a11y（无障碍）」（C 的 a11y 组 + D 的 a11y 字段）→ 组头「无 a11y（shizuku）」（C 的 vision 组 + D 的 vision 字段）。`StepForm` 拆 `common + a11yPart + visionPart`，`build()` 分别写入 `params` / `target.a11y` / `target.vision` / `lineParams`。
- **置灰规则（两条）**：
  - **R1 模式不含**：`script.mode` 不含该线 → 该线专有段整组灰色只读（值保留）。→ 满足用户例：新建只选 a11y，无 a11y 专有参数不可用。
  - **R2 前置不具备**：模式含该线但设备前置当前不具备（a11y=无障碍+桥接；无 a11y=Shizuku）→ 整组灰 + 组头「暂不可用」徽标 + 修复入口；`readinessChanges` 就绪即自动解灰。
- 双模式两组独立编辑、允许取值不同；DUAL 双写校验维持（缺侧报 `mode_dual_missing_*` 禁「确定」）。点击三兄弟两侧分写修正（坐标补 a11y 选择器、文字拆两侧、图片补 a11y 节点选择器）。
- 文案简洁铁律：组头只有「a11y（无障碍）」「无 a11y（shizuku）」徽标，原理不进 UI。strings.xml（zh/en）新增置灰原因、组头、D 类字段名、边界提示文案。

## 五、数据模型与引擎改动（vendor/RPA）

```kotlin
@Serializable data class RpaLineParams(
    val a11y: RpaA11yLineParams = RpaA11yLineParams(),
    val vision: RpaVisionLineParams = RpaVisionLineParams(),
)
@Serializable data class RpaA11yLineParams(
    val readySelector: RpaSelector? = null,    // LAUNCH_APP / OPEN_CHAT
    val inputSelector: RpaSelector? = null,    // INPUT_TEXT
    val appendMode: Boolean = false,
    val anchorSelector: RpaSelector? = null,   // SWIPE / SCROLL
    val readScope: RpaSelector? = null,        // ASSERT_TEXT / SET_VARIABLE(UI_READ)
)
@Serializable data class RpaVisionLineParams(
    val readyText: String? = null,
    val inputChannel: RpaInputChannel = RpaInputChannel.CLIPBOARD_PASTE,
    val clearBeforeInput: Boolean = true,
    val focusPoint: RpaRect? = null,
    val startPoint: RpaRect? = null, val endPoint: RpaRect? = null,   // SWIPE / SCROLL
    val ocrRegion: RpaRect? = null,
)
@Serializable enum class RpaInputChannel { CLIPBOARD_PASTE, SHELL_INPUT }
```

`RpaStep` 增 `lineParams: RpaLineParams = RpaLineParams()`（全默认可省略，旧 JSON 零迁移，schemaVersion 保持 2）。

- **校验**（`RpaValidator` 增量）：inputSelector 非法、inputChannel 枚举、锚点/起止点区间（沿用 RpaLimits）；DUAL 双写约束不动。
- **引擎**：INPUT_TEXT / LAUNCH_APP / SWIPE / SCROLL / ASSERT_TEXT / SET_VARIABLE 按当前线取 `lineParams` 对应侧执行（a11y：节点/SET_TEXT/无障碍手势；无 a11y：注入通道/OCR/比例）；缺省值=现行为，旧脚本零回归；**禁止跨线取值**。

## 六、运行前置与边界处理（细化到动作/步骤 + 提示界面 + 退出状态）

### 6.1 边界三要素（统一口径）

① **运行事件报因**：`ScriptFinished(ok=false, detail=<稳定错误码>)` + 面向用户的 `Log` 事件（代码稳定、UI 本地化）；
② **提示界面**：运行面板日志行（红字报因）+ 通知栏一句话（`float_rpa_run_failed_*`）；开始运行被拦截的场景用弹框（含直达修复按钮）。**全部简洁文案，无技术术语**；
③ **干净退出**：停止轮询、释放截屏/注入资源、运行态复位、浮球回常态、**回到脚本页**、3 秒内可再次运行（真机用例断言此状态）。

### 6.2 边界矩阵（运行前拦截类）

| # | 边界 | 检测时机 | 错误码 | 提示（简洁文案） | 退出行为 |
| --- | --- | --- | --- | --- | --- |
| B1 | **目标应用未安装（用户例：无微信）** | 运行前预检（`RpaReadiness`：appPackage 可解析）+ LAUNCH_APP 运行中兜底 | `app_missing` | 弹框「应用未安装，无法运行」；已开始则「应用未安装，运行结束」 | 不开始 / 立即结束，干净退出 |
| B2 | a11y 前置不具备 | 运行前就绪门 | `ready_a11y_missing` | 「无障碍未开启，无法运行」+「去开启」 | 拦截不运行 |
| B3 | 无 a11y 前置不具备 | 运行前就绪门 | `ready_vision_injection_missing` | 「Shizuku 未就绪，无法运行」+「打开 Shizuku」 | 拦截不运行 |
| B4 | 双模式两线都缺 | 运行前就绪门 | `ready_no_line` | 「两条执行线路都不可用」 | 拦截不运行 |
| B5 | 屏幕未点亮 / 锁屏 | 运行前预检 | `screen_locked` | 「请先点亮并解锁屏幕」 | 拦截不运行 |
| B9 | 变量未定义 / 参数缺失 | 静态校验 | `variable_undefined` 等 | 编辑器报错列表 | 阻「确定」/ 阻运行 |
| B10 | 双模式缺一线参数 | 静态校验 | `mode_dual_missing_*` | 「双模式需补齐两条线路参数」 | 阻「确定」/ 阻运行 |
| B12 | 导入文件损坏/校验不符/版本过新 | 导入时 | `package_*` 系列 | 「文件已损坏或不完整」「文件版本较新，请升级 App」 | 拒绝导入，不落库 |

### 6.3 边界矩阵（运行中逐步失败类——按动作细化）

统一退出行为 = 6.1③ 干净退出 + 日志逐步行（线别 + 原因）；`onFailure=CONTINUE` 的步骤只记行不中止。

| 动作 | 失败场景 | 错误码 | 提示（运行面板 + 通知栏） |
| --- | --- | --- | --- |
| LAUNCH_APP / OPEN_CHAT | 应用未安装 | `app_missing` | 「应用未安装，运行结束」 |
| LAUNCH_APP / OPEN_CHAT | 启动后完成判定超时（readySelector/readyText 未出现） | `launch_timeout` | 「第 N 步启动超时，运行结束」 |
| TAP / LONG_PRESS / WAIT_FOR_TARGET | 节点选择器全 miss（a11y 线） | `target_timeout` | 「第 N 步未找到目标，运行结束」 |
| TAP / LONG_PRESS / WAIT_FOR_TARGET | OCR/找图 miss、兜底也 miss（无 a11y 线） | `target_timeout` | 同上 |
| TAP | 点击注入失败（桥接断 / Shizuku 断） | `inject_failed` | 「第 N 步操作失败，运行结束」 |
| INPUT_TEXT | 无可编辑焦点且未给 inputSelector/focusPoint | `input_focus_missing` | 「第 N 步未找到输入框，运行结束」 |
| INPUT_TEXT | 注入/粘贴失败 | `input_failed` | 「第 N 步输入失败，运行结束」 |
| PRESS_KEY / SWIPE / SCROLL | 注入失败 | `inject_failed` | 「第 N 步操作失败，运行结束」 |
| ASSERT_TEXT | 断言文本未出现（超时） | `assert_failed` | 「第 N 步断言未通过，运行结束」 |
| SET_VARIABLE | UI_READ 读不到内容 | `ui_read_empty` | 「第 N 步未读到内容，运行结束」 |
| CONTROL_FLOW | 跳转越界 / 死循环（双保险） | `jump_limit_exceeded` | 「控制运行异常，运行结束」 |
| 任意步骤 | 超时 / 重试耗尽 | `step_timeout` | 「第 N 步超时，运行结束」 |
| COMPOSITE | 引用缺失 / 展开超深 | `custom_action_*` | 「复合动作异常，运行结束」 |

- 本地行为测试逐条覆盖上表关键行（B1、target_timeout、step_timeout、assert_failed）；真机用例至少实测 B1 + target_timeout（如临时改错选择器验证提示与退出）。
- 提示界面样式规范：运行面板=时间戳 + 红色报因行；通知栏=单条一句话（步骤号 + 原因 + 「运行结束」）；拦截类=AlertDialog + 一个直达修复按钮 +「取消」。不弹连续多个弹窗。

## 七、导入 / 导出文件语法详解（规范事实源）

### 7.1 载体总览

| 载体 | 扩展名 | 用途 | 云端 |
| --- | --- | --- | --- |
| JSON | `.json` | **规范语法**（内部事实源、字段语义以此为准） | 可上传（无模板的单文件） |
| XML | `.xml` | 等价映射（JSON 树 ↔ XML，互转零丢失） | 同上 |
| **`.rpa` 包** | `.rpa` | **推荐云端交付物**：脚本 + 模板图 + 清单，自包含 | 云备份 / 脚本市场统一载体 |

### 7.2 JSON 逐字段语法

UTF-8、prettyPrint、`encodeDefaults=true`、`ignoreUnknownKeys=true`。顶层（`RpaScript`）：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `schemaVersion` | int | 结构版本，当前 2（§7.5） |
| `id` | string | 稳定 id（`script_<base36时间>_<序>_<盐>`） |
| `name` / `description` | string | 名称 / 描述 |
| `mode` | enum | `A11Y` \| `VISION` \| `DUAL` |
| `appPackage` | string | 目标应用包名 |
| `variables` | map<string,string> | `${name}` 变量表 |
| `steps` | RpaStep[] | 步骤序列（顺序执行，CONTROL_FLOW 可跳转） |
| `createdAt` / `updatedAt` | long | 毫秒时间戳 |
| `global` | object | `defaultWaitSec`(可空)、`repeat`(0=一遍)、`stopOnFailure`、`globalListener`(P2)、`gestureTransform`(P2) |
| `customActions` | object[] | 复合动作（`id/name/params[]/steps[]`，展开深度 ≤3） |

步骤（`RpaStep`）字段表：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `id` | string | 步骤 id（跳转锚点） |
| `action` | enum | 见 §7.3 枚举表 |
| `label` / `note` | string | 步骤名 / 备注 |
| `enabled` | bool | false=跳过 |
| `params` | object | B 类载荷（见 §7.3 按动作字段） |
| `target` | object\|null | C 类定位：`a11y` + `vision` 双组（见下） |
| `lineParams` | object | D 类分线通道参数（§五；全默认可省略） |
| `expect` | object\|null | ASSERT_TEXT：`{text, match, timeoutMs}` |
| `timeoutMs` / `retries` / `delayBeforeMs` / `delayAfterMs` | long/int | 超时（=「等待目标出现」的步骤预算，定位按步轮询不单发，2026-10-31 定案——覆盖 IM 搜索结果异步渲染竞态）/ 失败重试 / 前后延迟 |
| `repeat` | int | 总执行次数（≥1） |
| `onFailure` | enum | `STOP` \| `CONTINUE` |
| `runCondition` | object | 运行条件 sealed（见 §7.3） |

`target.a11y`：`selectors[]`（单条：`text/textMatch/viewId/description/className/clickableOnly/editableOnly/enabledOnly`）、`pick`、`action`、`snapshot`（节点快照：`packageName/className/text/description/viewId/hintText/bounds{left,top,right,bottom}/clickable/editable/scrollable/focused`）、`note`。
`target.vision`：`primary`/`fallback`（可空），单个定位器：`kind`、`templateId`、`ocrText`、`ocrMatch`、`threshold`、`grayscale`、`region{left,top,right,bottom}`（归一化可越界）、`ratioX/ratioY`、`offsetX/offsetY`、`matchIndex`（1 起，可空）、`quickSearch`、`embedMode`、`pick`、`note`。
`runCondition`（判别字段 `type`）：`none` \| `variable{lhs,op,rhs}`（op=EQ/NE/GT/GE/LT/LE）\| `image_visible{templateId}` \| `text_visible{text,match}` \| `prev_ok` \| `prev_fail` \| `expr{expression}`（L2 公式）。

**变量语法**：`${name}`，名称字符集 `A-Z a-z 0-9 _ . -`；可出现在 params 文本、expect、runCondition 载荷、a11y 选择器文本、vision `ocrText`；运行时按 `variables` + SET_VARIABLE 声明展开，未定义保留原样并警告。

### 7.3 枚举取值表（全量）

| 枚举 | 取值 |
| --- | --- |
| `action` | LAUNCH_APP, OPEN_CHAT, WAIT, WAIT_FOR_TARGET, TAP, LONG_PRESS, INPUT_TEXT, PRESS_KEY, SWIPE, SCROLL, ASSERT_TEXT, COMMENT, OPEN_LINK, SET_VARIABLE, CONTROL_FLOW, COMPOSITE, TAP_COLOR(P2), GESTURE_MULTI(P2) |
| `mode` | A11Y, VISION, DUAL |
| `textMatch` / `ocrMatch` / `match` | EXACT, CONTAINS, PREFIX, REGEX |
| `pick` | FIRST, TOP_MOST, BOTTOM_MOST, LARGEST, NEAREST |
| `target.a11y.action` | CLICK, LONG_CLICK, READ |
| `target.vision.*.kind` | TEMPLATE, OCR_TEXT, RATIO |
| `params.key`（PRESS_KEY） | back, home, recents, notifications, enter |
| `params.direction`（SWIPE/SCROLL） | LEFT, RIGHT, UP, DOWN |
| `params.flowKind`（CONTROL_FLOW） | GOTO, STOP |
| `params.assignments[].type` | NUMBER, STRING |
| `params.assignments[].source` | LITERAL, UI_READ, VARIABLE_REF, INCREMENT |
| `onFailure` | STOP, CONTINUE |
| `runCondition.type` | none, variable, image_visible, text_visible, prev_ok, prev_fail, expr |
| `lineParams.vision.inputChannel` | CLIPBOARD_PASTE, SHELL_INPUT |

### 7.4 完整示例：「微信 7 步测试夹具」`script.json`（节选 3 步 + 结构全量示意）

```json
{
  "schemaVersion": 2,
  "id": "script_mock_0001",
  "name": "微信 7 步测试夹具",
  "mode": "DUAL",
  "appPackage": "com.tencent.mm",
  "description": "启动微信→搜索图标→输入联系人→选中联系人→点击输入框→输入「你好」→点击发送。",
  "variables": { "imPackage": "com.tencent.mm", "contactName": "吴瑞曦topwayricky" },
  "createdAt": 1793400000000, "updatedAt": 1793400000000,
  "global": { "defaultWaitSec": null, "repeat": 0, "stopOnFailure": true,
              "globalListener": null, "gestureTransform": null },
  "customActions": [],
  "steps": [
    {
      "id": "step_0001", "action": "LAUNCH_APP", "label": "启动微信", "enabled": true,
      "params": { "packageName": "${imPackage}", "restart": false, "closeOnly": false },
      "target": null,
      "lineParams": {
        "a11y":   { "readySelector": { "text": "微信", "textMatch": "CONTAINS", "enabledOnly": true } },
        "vision": { "readyText": "微信" }
      },
      "expect": null,
      "timeoutMs": 15000, "retries": 0, "delayBeforeMs": 0, "delayAfterMs": 5000,
      "onFailure": "STOP", "note": "", "repeat": 1,
      "runCondition": { "type": "none" }
    },
    {
      "id": "step_0002", "action": "TAP", "label": "点击搜索图标", "enabled": true,
      "params": { "holdMs": 100 },
      "target": {
        "a11y": {
          "selectors": [
            { "text": null, "description": "搜索", "textMatch": "CONTAINS",
              "clickableOnly": false, "editableOnly": false, "enabledOnly": true },
            { "text": null, "viewId": "search", "textMatch": "CONTAINS",
              "clickableOnly": false, "editableOnly": false, "enabledOnly": true }
          ],
          "pick": "FIRST", "action": "CLICK", "snapshot": null,
          "note": "微信右上角搜索入口"
        },
        "vision": {
          "primary": { "kind": "RATIO", "ratioX": 0.806, "ratioY": 0.058,
                        "threshold": 0.8, "grayscale": true, "region": null,
                        "templateId": null, "ocrText": null, "ocrMatch": "CONTAINS",
                        "matchIndex": null, "quickSearch": false, "embedMode": "DEFAULT",
                        "offsetX": 0, "offsetY": 0, "pick": "FIRST" },
          "fallback": null,
          "note": "右上角搜索图标（真机 1080×2460 校准）"
        }
      },
      "lineParams": { "a11y": {}, "vision": {} },
      "expect": null,
      "timeoutMs": 5000, "retries": 0, "delayBeforeMs": 0, "delayAfterMs": 0,
      "onFailure": "STOP", "note": "", "repeat": 1,
      "runCondition": { "type": "none" }
    },
    {
      "id": "step_0003", "action": "INPUT_TEXT", "label": "输入联系人", "enabled": true,
      "params": { "text": "${contactName}", "clearFirst": true },
      "target": null,
      "lineParams": {
        "a11y":   { "inputSelector": null, "appendMode": false },
        "vision": { "inputChannel": "CLIPBOARD_PASTE", "clearBeforeInput": true, "focusPoint": null }
      },
      "expect": null,
      "timeoutMs": 5000, "retries": 0, "delayBeforeMs": 0, "delayAfterMs": 0,
      "onFailure": "STOP", "note": "", "repeat": 1,
      "runCondition": { "type": "none" }
    }
    // …步骤4-7 结构同上（TAP×3 + INPUT_TEXT×1），完整文件由 RpaJsonCodec 导出即得
  ]
}
```

### 7.5 `.rpa` 包语法与完整性

ZIP 条目：`manifest.json` + `script.json`（导入同时认 `script.xml`）+ `templates/<templateId>.png`（模板池，定位与条件共享）。

```json
{ "format": "rpa-package", "schemaVersion": 2, "exportedAt": 1793400000000,
  "name": "微信 7 步测试夹具", "sha256": "<script.json 的 sha256>", "scriptCode": false }
```

- `sha256` 不符 = 被篡改，拒绝导入；模板条目名经 `safeSegment` 防路径穿越；缺模板不阻断导入（运行前 `ready_template_missing` 按线报因）。
- `scriptCode=true` 表示含 P2 占位能力，导入时标「需升级」徽标（运行到该步显式 `unsupported_action`）。
- XML 版本：`<rpa-script format="json-tree" schemaVersion="2">` 逐字段等价树；解析禁 DTD/外实体（XXE 防御）。

### 7.6 升级兼容策略（未来兼容性规约）

1. **加字段不升版本**：新字段一律带默认值（如本次 `lineParams`）——旧文件读入=旧语义；新文件被旧版读入靠 `ignoreUnknownKeys` 不崩（未知字段会丢，故有规则 3/6）。
2. **破坏性变更必升 `schemaVersion`**（改名/改类型/改语义/删字段）：升 3 时同步写迁移器，导入先按版本分派迁移再解析；迁移用旧版样本文件做回归。迁移示例：
   ```jsonc
   // v2 → v3（示例：假设 params.text 改名 payload.text）
   // 迁移器：if (v==2) { step.payload.text = step.params.text; step.schemaVersion = 3 }
   ```
3. **高版本文件导入低版本 App**：`schemaVersion > 当前` → 报「文件版本较新，请升级 App 后导入」，拒绝导入（不半解析、不丢字段落库）。
4. **枚举只增不改**：新动作/新枚举只追加；旧版遇未知枚举 → 校验报因（`unsupported_action` 同族），不猜测执行。
5. **P2 参数先定标准**（§一 17/18）：未来实现点击颜色/多指手势时字段名已在此冻结，保证文件向前可读。
6. **模板 id 稳定**：随机 id 而非序号，包内文件名即 id，跨版本不漂移。
7. **云端存文件字节**：不转换、不重排；版本问题在导入端按 1–6 处理。

## 八、云端上传设计（文件即交付物）

### 8.1 两个云端去处（对齐图10 属性页口径）

| 去处 | 语义 | 载体 |
| --- | --- | --- |
| **云备份** | 账户私有存档（同名多版本，可恢复） | `.rpa` |
| **脚本市场** | 公开/售卖分享（上传后经审核上架） | `.rpa` |

### 8.2 流程

```
导出（本地）                云端                      导入（任意设备）
脚本 ──pack──▶ .rpa ──sha256 自校验──▶ 上传（multipart：文件 + manifest 摘要）
                                        │ 云端存对象字节 + 元数据
                                        │（name / schemaVersion / exportedAt / sha256 / 大小）
                                        ▼
                              版本列表（同名多版本，不覆盖）
                                        │ 下载（校验 sha256）
        脚本 ◀──unpack+兼容检查◀── .rpa ◀──┘
```

- **上传**：客户端导出 `.rpa` 后立即自校验 sha256；上传携带 manifest 摘要（名称/版本/时间/大小上限校验、PNG 魔数校验）；失败可重试，重复上传同 sha256 幂等去重。
- **下载/恢复**：按脚本名列出版本（时间倒序），下载后 `unpack` 强校验 sha256 + §7.6 兼容检查（版本过新拒绝并提示升级）；导入即重新入库（id 冲突时复制为新 id 并提示）。
- **分享/市场**：同 `.rpa` 文件；`scriptCode=true` 的包市场侧标「需升级」。
- **安全**：sha256 防篡改；模板路径安全；大小上限（脚本 JSON ≤ 2MB，模板 PNG ≤ 2MB/张、≤ 50 张）；云端不解析脚本内容（不执行、不转换）。
- **本期实现边界**：客户端导出/导入/自校验 + 上传下载走**既有账户云接口**（后端未就绪的字段先定文件契约，接口对接为后续批次；本地文件往返不阻塞本期验收）。

## 九、真机双模式执行序列（用例验收）

### 9.1 环境与前置

Redmi 23077RABDC（Android 15，1080×2460），**无线调试 `adb connect 192.168.10.2:41155`**（用户指定）；测试前确认无模拟器占用；微信 8.0.79 已登录、联系人 `吴瑞曦topwayricky`、屏幕点亮解锁、悬浮窗权限。a11y 线前置：无障碍服务 + Assists 桥接；无 a11y 线前置：Shizuku 就绪。

### 9.2 测试通道改造（「红」先落）

`RpaWechatHelloDeviceTest` 增参数：`-e runLine a11y|vision|auto`（→ `forceLine`）、`-e imPackage <包名>`（边界 B1）。断言：① 7 步全 ok；② 无 `LineFallback` 且逐步 `line=` 全为所锁线路；③ 屏现「你好」；④（B1）`app_missing` 提示 + 3 秒内干净退出。

### 9.3 执行序列

1. a11y 线：`adb -s 192.168.10.2:41155 shell am instrument -w -e class ...RpaWechatHelloDeviceTest -e runLine a11y -e contactName 吴瑞曦topwayricky ...` → 全绿（line=A11Y）。
2. 无 a11y 线：同命令 `-e runLine vision` → 全绿（line=VISION）。
3. 边界 B1：`-e runLine auto -e imPackage com.example.notinstalled` → 提示并干净退出。
4. UI 闭环抽查（截图存 `tests/tempImg/`）：新建弹框置灰变化（开关无障碍/Shizuku）；a11y 单模式脚本「无 a11y」组灰色（用户例）；双模式两组独立可编辑；锁线运行一遍；导出 `.rpa` → 重导入往返一致。
5. 失败归因：逐步 line/reason 日志，按选择器漂移 / 注入失败 / OCR miss 分类；坐标漂移按夹具注释口径校准比例值。

## 十、改动清单 / 验收

| 文件 | 改动 |
| --- | --- |
| `vendor/RPA/.../model/RpaModels.kt` | `RpaLineParams`/`RpaA11yLineParams`/`RpaVisionLineParams`/`RpaInputChannel`、`RpaStep.lineParams` |
| `vendor/RPA/.../model/RpaCatalog.kt` | `RpaValidator` 分线校验；`RpaReadiness` + `app_missing`/`screen_locked` 预检 |
| `vendor/RPA/.../engine/RpaEngine.kt` | 全动作 D 类参数按线消费（§五）+ 运行中 `app_missing` 兜底 |
| `vendor/RPA/.../store/*` | 结构不动；补语法样例单测、高版本拒绝导入用例 |
| `app/.../rpa/RpaStepDialog.kt` | 四类分段渲染 + R1/R2 置灰 + 三兄弟分写修正 + D 类字段 |
| `app/.../rpa/RpaComponents.kt` / `RpaLibraryScreen.kt` | 模式芯片置灰+原因；CreateScriptDialog 全量+置灰+修复入口 |
| `app/src/sharedTest/.../RpaWechatHelloFixture.kt` | 7 步补 lineParams（§2.1） |
| `app/src/androidTest/.../RpaWechatHelloDeviceTest.kt` | runLine 锁线 + 无回退断言 + imPackage 边界 |
| `res/values/strings.xml` + `values-en` | §四/§六 文案 |
| 单测 | RpaCodecTest（往返/兼容/拒新）、RpaValidatorTest、RpaRunnerBehaviorTest（边界）、RpaWechatHelloFixtureTest（双线自足） |

验收：① 本地测试全绿 + `assembleDebug` 通过；② 真机双线锁线各全绿（7 步、无回退、屏现「你好」）+ B1 全绿；③ UI 置灰逐条截图；④ `.rpa` 导出→（云通道可用则上传下载）→导入往返零丢失；⑤ 旧脚本零回归。

## 十一、显式标注（AGENTS.md §4.5）

- **a11y 硬前置**：a11y 线编辑与执行显式要求无障碍+桥接就绪，缺则置灰+报因+直达修复，绝不静默降级。
- 两线参数、两线测试独立交付（§〇.2）；锁线禁止换线，回退必须显式（`LineFallback`），测试断言验证。
- 所有边界「提示并干净结束退出」（§六），禁止静默失败、禁止挂起。
