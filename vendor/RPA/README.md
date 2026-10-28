# RPA 自动化模块（vendor/RPA）

> 一个可拖拽编排、同时支持 **a11y** 与 **无 a11y（Shizuku + 抽帧 + OCR + OpenCV）**
> 两条执行线路的手机 RPA 引擎与配置界面。
>
> - 模块位置：`NovaBAIC/vendor/RPA`（纯 Kotlin，不依赖 Android API，可单测）
> - Android 适配与 Compose 界面：`app/src/main/java/com/verlintas/baic2/floatball/rpa`
> - 发行版新建脚本：`com.verlintas.rpa.model.RpaScriptFactory.starter(...)`（空白骨架）
> - 微信 7 步流程：`app/src/test/.../rpa/RpaWechatHelloFixture.kt`（**仅 test，不发行**）
> - 入口：好参谋设置页「RPA 自动化」；悬浮球「更多 → RPA 工具 → RPA 设计」；
>   画像「聊天会话」行的「RPA」按钮（只预填 IM 包名，用于新建脚本）

---

## 0. 一句话概括

**先选模式（a11y / 无 a11y / 双模式），再拖入动作，每个需要定位的动作分别（或同时）配置
「a11y 选择器」与「视觉定位」；执行时按 JSON 数组顺序跑，双模式在线路失败时显式换线并写日志，
绝不静默切换。**

---

## 1. 设计目标与硬约束

| 目标 | 落地方式 |
|---|---|
| 独立模块 | `vendor/RPA` 只放模型 / 校验 / 存储 / 解释器 / 空白脚本工厂；Android 能力通过 `RpaDevice` 端口注入 |
| 不新增 Gradle 模块 | `app/build.gradle.kts` 把 `vendor/RPA/src/main/java` 同时挂到 `java.srcDir` 与 `kotlin.srcDir` |
| 复用 Assists | `AssistsCore.getRootNodeTree()/getAllNodes()`、`AssistsService`、`AssistsA11yBridge` 全部复用，不改上游 |
| 双模可运行 | 每个依赖定位的动作都有 `a11y` + `vision` 两份定位表达式；三模式约束由校验器强制 |
| 可拖拽配置 | 动作库长按拖入 + 步骤行手柄拖动排序 + 点击追加；顺序即 JSON 数组顺序 |
| 可交互定位 | a11y：布局分析器；无 a11y：以图找图 / OCR / 比例坐标配置页 |
| 失败可解释 | 稳定的 issue code / 运行事件（含 `LineFallback`），UI 统一本地化展示 |

### 1.1 三模式是「前置决定」，不是运行期选项

新建脚本时必须先选模式（默认 `DUAL`），模式写入 `RpaScript.mode`。它决定：

- 属性面板哪一张定位卡可用（不可用的一侧整卡置灰、入口按钮禁用）；
- 校验器要求补齐哪一侧的定位（`DUAL` 要求两侧同时可执行）；
- 运行时允许使用哪条线路，以及是否允许「定位/执行失败后换线」。

运行期不存在「用户临时开一个 a11y 试试」的隐式行为；模式是脚本契约的一部分。

### 1.2 双模执行面铁律

| 能力 | A11Y 线路 | VISION 线路 |
|---|---|---|
| 定位 | `AssistsCore.getAllNodes()` + `RpaSelector` | 抽帧 + `RpaVisionLocator`（OpenCV 模板 / OCR / 比例） |
| 点击 | `AssistsA11yBridge.tap` | `ShizukuInjector.tap` |
| 长按 | `AssistsA11yBridge.longPress` | `ShizukuInjector.longPress` |
| 滑动 | `AssistsA11yBridge.swipe` | `ShizukuInjector.swipe` |
| 输入 | `AssistsA11yBridge.typeText`（SET_TEXT） | `ShizukuInjector.type` / 清空 + PASTE |
| 按键 / 前台 | 无障碍全局动作 / 根节点包名 | `input keyevent` / `dumpsys activity` |
| 帧源 | 不用于执行（仅布局分析器配置时可用） | Shizuku screencap / MediaProjection |

`RpaAndroidDevice` 的每个方法都显式带 `line: RpaLine`，实现内部**不得**自行换线；
换线只能由 `RpaRunner` 在 `DUAL` 模式下发起，并写入 `RpaRunEvent.LineFallback`。

---

## 2. 目录与分层

### 2.1 vendor（纯 Kotlin）

```
vendor/RPA/src/main/java/com/verlintas/rpa/
├── model/
│   ├── RpaModels.kt      # 脚本 / 步骤 / 目标 / 选择器 / 视觉定位 / 运行事件
│   ├── RpaCatalog.kt     # 动作目录、三模式矩阵、静态校验、运行就绪门
│   ├── RpaTextMatch.kt   # EXACT / CONTAINS / PREFIX / REGEX 共用匹配
│   └── RpaScriptFactory.kt # 发行版新建脚本空白骨架（无 Demo 数据）
├── store/
│   └── RpaScriptStore.kt # JSON 文档存储 + PNG 模板 + 原子写 + 坏文档汇报
└── engine/
    └── RpaEngine.kt      # RpaDevice 端口 + RpaRunner 解释器
```

### 2.2 app（Android / Compose）

```
app/src/main/java/com/verlintas/baic2/floatball/rpa/
├── RpaActivity.kt         # 全屏容器：LIBRARY / EDITOR / VISION 状态机
├── RpaLibraryScreen.kt    # 脚本库、能力卡、新建/复制/删除（无 Demo 入口）
├── RpaEditorScreen.kt     # 双 Tab：动作流程（拖拽）/ 动作属性（参数 + 定位 + 变量 + 运行）
├── RpaVisionScreen.kt     # 以图找图 / OCR / 比例坐标交互配置（无 a11y）
├── RpaComponents.kt       # 文案映射、卡片、模式徽标、帧画布、选择器摘要
├── RpaAndroidDevice.kt    # RpaDevice 的 Android 实现（双线路严格分流）
├── RpaTemplateMatcher.kt  # OpenCV 多尺度 / 灰度 / 掩膜模板匹配 + 缓存
├── analyze/               # 悬浮球「布局分析」覆盖层（全局工具，不在编辑器内跑）
└── RpaModule.kt           # Hilt：RpaScriptStore（filesDir/rpa）
```

### 2.3 依赖方向

```
Compose UI ──> vendor/RPA model + engine + store
     │
     └──> RpaAndroidDevice ──> Assists / Shizuku / FrameSource / ScreenCapture / OCR / OpenCV
```

vendor 不反向依赖 app；`RpaRunner` 只认 `RpaDevice` 接口，换平台只需重写实现。

---

## 3. 数据结构与存储方案

### 3.1 文件布局

```
filesDir/rpa/
├── scripts/
│   ├── script_xxx.json          # 一个脚本 = 一个 JSON 文档
│   └── script_yyy.json
└── templates/
    ├── script_xxx/
    │   ├── tpl_aaa.png          # 该脚本的以图找图模板
    │   └── tpl_bbb.png
    └── script_yyy/
        └── tpl_ccc.png
```

- **JSON 是唯一事实源**：动作顺序就是 `steps` 数组顺序，拖拽只改数组，不存额外 sort 字段。
- **模板按 `scriptId` 分区**：删除脚本时 `deleteRecursively()` 只清自己的模板目录。
- **文件名白名单化**：`safeSegment()` 只保留字母数字与 `._-`，防导入文档路径穿越。
- **原子写**：先写 `<name>.tmp`，再 `Files.move(ATOMIC_MOVE + REPLACE_EXISTING)`；
  文件系统不支持原子移动时退回普通覆盖，失败一律清理临时文件。
- **坏文档可见**：`listReport()` 把解析失败的 JSON 文件名交给 UI 提示，不静默丢弃。
- **复制**：`duplicate()` 生成新脚本 id / 新步骤 id，并复制模板目录；JSON 落盘失败会
  回收新模板目录，不留「有模板没有脚本」的孤儿数据。
- **版本**：`schemaVersion` 当前为 `1`，读取时 `ignoreUnknownKeys=true`，新增可选字段可向前兼容。

### 3.2 运行安全与预算

- **总预算**：`RpaRunner(totalTimeoutMs)` 默认 `RpaLimits.DEFAULT_RUN_TIMEOUT_MS`；
  `WAIT` / 重试 / `delayBefore` / `delayAfter` 都受预算约束，耗尽统一以
  `run_timeout` 收口（事件与日志同因）。
- **前台守卫 fail-closed**：脚本声明 `appPackage` 时，点击 / 输入 / 断言前必须确认前台
  包名；探针返回 `null` 直接失败，绝不在未知前台执行。
- **就绪门**：运行前 `RpaReadiness` 按模式只校验本线路所需能力；A11Y 模式不查 vision 模板。
- **模板缓存**：LRU 16，淘汰即 `release()` native `Mat`；模板覆盖 / 删除后
  `RpaAndroidDevice.invalidateTemplate()` 立即失效。

### 3.3 脚本 JSON 示例（节选）

```json
{
  "schemaVersion": 1,
  "id": "script_m6x9a1_427",
  "name": "微信发送你好",
  "mode": "DUAL",
  "appPackage": "com.tencent.mm",
  "description": "打开微信，进入指定联系人聊天页，发送「你好」并断言已上屏。",
  "variables": {
    "imPackage": "com.tencent.mm",
    "contactName": "文件传输助手"
  },
  "steps": [
    {
      "id": "step_m6x9a1_512",
      "action": "LAUNCH_APP",
      "label": "打开微信",
      "enabled": true,
      "params": { "packageName": "com.tencent.mm" },
      "timeoutMs": 15000,
      "retries": 0,
      "delayBeforeMs": 0,
      "delayAfterMs": 0,
      "onFailure": "STOP",
      "note": ""
    },
    {
      "id": "step_m6x9a1_513",
      "action": "OPEN_CHAT",
      "label": "打开联系人聊天",
      "enabled": true,
      "params": {
        "imPackage": "${imPackage}",
        "contactName": "${contactName}"
      },
      "timeoutMs": 25000,
      "retries": 0,
      "delayBeforeMs": 0,
      "delayAfterMs": 0,
      "onFailure": "STOP",
      "note": ""
    },
    {
      "id": "step_m6x9a1_516",
      "action": "TAP",
      "label": "点击发送",
      "enabled": true,
      "params": {},
      "target": {
        "a11y": {
          "selectors": [
            { "text": "发送", "textMatch": "EXACT", "enabledOnly": true },
            { "description": "发送", "textMatch": "EXACT", "enabledOnly": true }
          ],
          "pick": "BOTTOM_MOST",
          "action": "CLICK",
          "note": "发送键文本 / 描述均为「发送」"
        },
        "vision": {
          "primary": {
            "kind": "OCR_TEXT",
            "ocrText": "发送",
            "ocrMatch": "EXACT",
            "threshold": 0.8,
            "grayscale": true,
            "region": { "left": 0.5, "top": 0.55, "right": 1.0, "bottom": 1.0 },
            "pick": "BOTTOM_MOST",
            "offsetX": 0,
            "offsetY": 0
          },
          "fallback": {
            "kind": "RATIO",
            "ratioX": 0.92,
            "ratioY": 0.88,
            "pick": "FIRST"
          },
          "note": "底部右侧 OCR「发送」，失败用比例兜底"
        }
      },
      "timeoutMs": 6000,
      "retries": 0,
      "delayBeforeMs": 0,
      "delayAfterMs": 0,
      "onFailure": "STOP",
      "note": ""
    }
  ],
  "createdAt": 1761523200000,
  "updatedAt": 1761523200000
}
```

### 3.3 核心字段语义

| 结构 | 字段 | 说明 |
|---|---|---|
| `RpaScript` | `mode` | `A11Y` / `VISION` / `DUAL`，新建时决定，之后仍可在属性页改（会立刻收紧密码/校验） |
| | `appPackage` | 目标 App 包名；非空时执行器做前台守卫（见 §9.4） |
| | `variables` | `${key}` 轻量变量，跨联系人复用脚本 |
| | `steps` | 有序动作数组，唯一执行顺序来源 |
| `RpaStep` | `action` | 12 个动作原语之一 |
| | `params` | 动作参数（按动作取用；未用字段为 null） |
| | `target` | 需要定位的动作才使用；含 `a11y` / `vision` 两侧 |
| | `expect` | `ASSERT_TEXT` 的期望文本 / 匹配模式 / 超时 |
| | `timeoutMs` | 定位轮询 / 断言等待上限 |
| | `retries` | 失败重试次数（整步重跑，重试间隔 400ms） |
| | `delayBeforeMs` | 执行前固定延迟 |
| | `delayAfterMs` | 动作最终成功后等待；失败 / 重试 / CONTINUE 等待期都不执行 |
| | `onFailure` | `STOP`（默认）或 `CONTINUE` |
| `RpaTarget` | `a11y` | `RpaA11ySpec`：选择器数组（OR）+ `pick` + 节点动作 + 快照 |
| | `vision` | `RpaVisionSpec`：主定位 + 可选兜底定位 |
| `RpaA11ySpec` | `selectors` | 多条选择器之间 **OR**；单条内非空条件 **AND** |
| | `pick` | `FIRST / TOP_MOST / BOTTOM_MOST / LARGEST / NEAREST` |
| | `snapshot` | 布局分析器留下的节点快照；选择器全 miss 时用于坐标兜底 |
| `RpaVisionSpec` | `primary` | 主定位，三选一：`TEMPLATE / OCR_TEXT / RATIO` |
| | `fallback` | 同一线路内的兜底定位（区别于跨线路 `LineFallback`） |
| `RpaSelector` | `text/textMatch` | 文本条件 + 匹配口径 |
| | `viewId/description/className` | resource-id / contentDescription / 类名 |
| | `clickableOnly/editableOnly/enabledOnly` | 属性过滤；默认只找 enabled 节点 |
| `RpaVisionLocator` | `templateId` | 指向 `templates/<scriptId>/<templateId>.png` |
| | `threshold/grayscale/region` | 模板匹配阈值、灰度、归一化 ROI |
| | `ocrText/ocrMatch/pick` | OCR 文本条件与多命中策略 |
| | `ratioX/ratioY` | 归一化坐标（0..1，按当前帧尺寸换算） |
| | `offsetX/offsetY` | 像素级偏移，便于点偏一点（如避开图标边缘） |

### 3.4 变量解析

- 语法：`${name}`，可出现在动作参数（`packageName / imPackage / contactName / text / key / expect.text`）与定位表达式（a11y 选择器的 `text / viewId / description / className`、视觉 OCR 的 `ocrText`）。
- 运行时由 `resolveVariables(value, script.variables)` 替换；定位表达式在 `RpaRunner` 调 `locate` 前统一展开（`RpaTarget.resolve(variables)`），未定义变量保留原样。
- 校验器把未声明的变量报为 **WARNING**（`variable_undefined`），不阻止运行，但 UI 会提示；变量声明面板同时收集动作参数与定位表达式里的引用。
- 典型用法：`variables.contactName` 来自「聊天会话」行，实现同一脚本跨联系人复用。

---

## 4. 动作表达描述（Action DSL）

### 4.1 表达公式

```
Step = Action + Params + Target? + Expect? + Timeout + Retries + DelayBefore + FailurePolicy
```

- **Action** 是原语，不做「万能脚本代码」；
- **Target** 只描述「怎么找到元素」，不描述「怎么点」；点击/长按由 Action 决定；
- 所有定位表达式都可选双线路，但受到 `mode` 约束；
- 参数与定位分离，便于拖拽时先落动作、再到属性页补定位。

### 4.2 动作目录（12 个原语）

| Action | 分类 | 参数 | 需要 Target | a11y 侧 | vision 侧 |
|---|---|---|---|---|---|
| `LAUNCH_APP` | APP | `packageName` | 否 | 仅启动 + 等前台 | 同左 |
| `OPEN_CHAT` | APP | `imPackage` + `contactName` | 否 | `ImChatNavigator.openChat` | `openChatByVision` |
| `WAIT` | FLOW | `waitMs` | 否 | 固定等待 | 固定等待 |
| `WAIT_FOR_TARGET` | FLOW | — | 是 | 轮询节点出现 | 轮询视觉命中 |
| `TAP` | TARGET | — | 是 | 节点 click 或坐标 tap | Shizuku tap |
| `LONG_PRESS` | TARGET | — | 是 | 节点 longClick 或长按坐标 | Shizuku longPress |
| `INPUT_TEXT` | INPUT | `text` + `clearFirst` | 否 | SET_TEXT | `input text` / 清空 + PASTE |
| `PRESS_KEY` | INPUT | `key` | 否 | 无障碍全局动作 | `input keyevent` |
| `SWIPE` | GESTURE | `direction` + `distanceRatio` + `durationMs` | 否 | 手势 | Shizuku `input swipe` |
| `SCROLL` | GESTURE | 同 `SWIPE` | 否 | 手势 | Shizuku `input swipe` |
| `ASSERT_TEXT` | ASSERT | `expect.text/match/timeoutMs` | 否 | 节点文本 | OCR 文本行 |
| `COMMENT` | FLOW | — | 否 | 空操作（仅编辑器可读） | 空操作 |

参数类型由 `RpaParamKind` 描述（TEXT / MULTILINE / PACKAGE / DURATION_MS / NUMBER /
RATIO / ENUM / BOOLEAN），属性面板据此渲染输入控件，避免每种动作写一套 UI。

### 4.3 与行业工具的对应

| 本模块 | 自动精灵 | UiPath / Power Automate | AutoJs6 |
|---|---|---|---|
| `Step` | 步骤 / 动作块 | Activity / Action | 一行脚本或 `click()` 调用 |
| `RpaA11ySpec.selectors` | 找图/找字/控件条件 | Selector（`<wnd ctrl>` 等） | `text("...").findOne()` 选择器链 |
| `RpaSelector.pick` | 第 N 个结果 | Selector 的 `idx` | `find()` 结果集索引 |
| `RpaVisionLocator.TEMPLATE` | 找图 | Image Automation | `images.findImage()` |
| `RpaVisionLocator.OCR_TEXT` | 找字 | OCR / Document Understanding | `ocr` 插件 / `paddle` |
| `RpaVisionLocator.RATIO` | 坐标点击 | Anchor + offset | `click(x, y)` |
| `onFailure=CONTINUE` | 忽略错误继续 | ContinueOnError | `try/catch` + 继续 |
| `LineFallback` | （无） | （无） | （无，需手写 fallback） |

---

## 5. 可交互配置的动作属性 UI

### 5.1 页面与导航

```
脚本库（LIBRARY）
  ├─ 运行能力卡（a11y / Shizuku / OCR / 录屏 + 「授权录屏」「打开 Shizuku 授权页」）
  ├─ 新建脚本（必须先选模式；发行版由 RpaScriptFactory.starter 生成空白骨架）
  └─ 打开脚本 → 编辑器（EDITOR）
                 ├─ Tab 1：动作流程（拖拽编排 + 运行面板）
                 ├─ Tab 2：动作属性
                 │     ├─ 布局分析器（INSPECTOR）→ 回填 RpaA11ySpec
                 │     └─ 以图找图（VISION）   → 回填 RpaVisionSpec
                 └─ 返回时自动保存
```

`RpaActivity` 统一处理返回栈：编辑器返回即落盘；分析器 / 视觉页返回编辑器，不丢当前步骤。

### 5.2 动作属性面板的分区

从上到下（`RpaEditorScreen.PropertiesPane`）：

1. **脚本设置**：名称、目标包名、描述、模式选择器。
   - 模式切换立即反映到下方定位卡的置灰与校验结果；
2. **步骤基础**：标签、启用开关、超时、重试、执行前延迟、失败策略、备注；
3. **动作参数**：按 `RpaCatalog.specOf(action).params` 动态渲染；
   - `ENUM` → FilterChip；`BOOLEAN` → Switch；`DURATION_MS` / `NUMBER` / `RATIO` → 数字输入；
   - 必填项为空时在卡片内红字提示，并在运行面板汇总；
4. **目标定位**（仅 `needsTarget()` 的动作显示）：
   - **a11y 卡**：已生成的选择器摘要、多命中策略、清除；
     - `mode=VISION` 时整卡降透明度，入口按钮禁用，并显示「当前模式不使用 a11y」；
   - **视觉卡**：主定位类型 + 兜底定位 + 清除；
     - `mode=A11Y` 时同样禁用；
   - 入口按钮分别调用 `onOpenInspector(stepId)` / `onOpenVision(stepId)`；
5. **变量**：声明 / 编辑 `${key}`，并展示脚本中引用但未声明的 key；
6. **能力快照**：a11y / Shizuku / OCR 三个状态徽标。

### 5.3 三模式下的可用 / 必填矩阵

| 模式 | a11y 卡 | vision 卡 | 需要定位的步骤必须满足 | 运行时线路 |
|---|---|---|---|---|
| `A11Y` | 可用 | 禁用（置灰） | 至少一条非空 `RpaSelector`（或 snapshot） | 只走 a11y；无 fallback |
| `VISION` | 禁用（置灰） | 可用 | `primary` 已配置 | 只走 vision；无 fallback |
| `DUAL` | 可用 | 可用 | **两侧都配置** | 先 a11y，失败显式切 vision |

校验器 `RpaValidator` 对应 code：

- `mode_a11y_missing_a11y_target`
- `mode_vision_missing_vision_target`
- `mode_dual_missing_a11y_target`
- `mode_dual_missing_vision_target`

### 5.4 交互约定

- 所有「不可用」状态用 `alpha(0.55f)` + 禁用按钮 + 一句原因文案，而不是直接隐藏（用户需要知道为什么）；
- 所有枚举选择用 `FilterChip`，一眼看到全集；
- 长文本（输入内容、正则）用多行输入；
- 校验/就绪问题在运行面板聚合展示，带步骤序号与稳定 code；
- 运行中锁定编排与属性编辑（`enabled = !running`），避免边跑边改造成语义漂移。

---

## 6. 拖拽式动作配置 UI

### 6.1 动作库

`ActionLibrary` 按分类分组展示 12 个动作：

- **点击**：追加到列表末尾，并选中该步骤跳到属性页；
- **长按并拖动**：进入拖拽状态，跟随手指显示浮动卡片。

### 6.2 插入位置计算

`FlowPane` 记录每个步骤行在根容器中的纵向中心 `anchors[stepId]`：

```kotlin
fun targetIndex(y: Float): Int =
    anchors.count { (_, center) -> center < y }
```

- 拖拽位置实时换算「插入到第 N 步之前」，底部浮条展示「插入到第 N 步」；
- 松手时：
  - 来自动作库：`list.add(insert, defaultStep(action))`；
  - 来自步骤行：先 `removeAt(from)`，再按 `insert > from ? insert - 1 : insert` 修正索引后插入；
- `insert` 一律 `coerceIn(0, steps.size)`，空列表也能落到 0。

### 6.3 步骤排序

- 步骤行左侧 `≡` 手柄长按拖动，使用行锚点重新计算目标索引；
- 同时保留「上移 / 下移」按钮作为可达性兜底（无法长按、误触、小屏时仍可排序）；
- 排序后选中步骤保持不变（按 id 跟踪，不按 index）。

### 6.4 手势状态机

```kotlin
var drag by remember { mutableStateOf<DragPayload?>(null) }

beginDrag(source, action, stepId, label, position)  // 记录起点与初始插入位置
updateDrag(delta)                                   // 更新浮动卡片位置与插入索引
drop()                                              // 落库：追加或移动
drag = null                                         // 取消
```

- `DragPayload.source ∈ { LIBRARY, STEP }`，两种来源共用一套落点逻辑；
- 拖拽浮层使用窗口坐标减根容器 `topLeft`，避免 LazyColumn 滚动造成偏移；
- 列表 `itemsIndexed(key = step.id)` 保证拖动/删除时行身份稳定，不会串行。

---

## 7. Android 界面布局分析器（a11y，悬浮球覆盖层）

> 目标：达到 AutoJs6 / AutoJs「布局工具」的效果——看到截图 + 节点框，点一下就能生成选择器。
> 2026-10-28 起分析器**只在目标 App 前台**从悬浮球「更多 → 布局分析」进入
> （`app/.../rpa/analyze/`，`TYPE_ACCESSIBILITY_OVERLAY`），编辑器内不再内置分析器：
> 在编辑器里抓帧抓树必然截到编辑器自身，入口改为「提示 + 在目标应用拾取」。

### 7.1 数据来源

| 数据 | 来源 | 说明 |
|---|---|---|
| 截图 | `RpaAnalyzeCapture`（`FrameSource.grabFrameWithFreshness()`） | 仅用于配置展示，不参与 VISION 执行 |
| 节点树 | `AssistsCore.getRootNodeTree()` | 复用 Assists 的 a11y 树 |
| 可编辑性 | `AccessibilityNodeInfo.isEditable` | 通过 `getAllNodes()` 关联补全 |

### 7.2 交互流程

1. 打开覆盖层自动抓帧 + 取树；失败时区分「没有 a11y」与「抓帧失败」；
2. 画面叠加节点框：
   - 选中节点 → 高亮色；
   - 可编辑节点 → 可编辑色；
   - 可点击节点 → 可点击色；
   - 其他 → 普通描边；
   - 最多绘制 300 个框，避免大页面卡顿；
3. 点击画面 → `RpaNodeIndex.hitTest(x, y)` 命中**面积最小**的节点（最具体元素）；
4. 节点列表按层级缩进，可按 `ALL / 可点击 / 可编辑 / 有文本` 过滤；
5. 选中节点后展示：text、description、viewId、className、clickable、editable、scrollable、
   visibleToUser、bounds；
6. 勾选要纳入选择器的条件（文本 / 描述 / id / 类名 / 可点击 / 可编辑），选择匹配口径与
   `pick` 策略，实时预览选择器摘要；
7. 「应用到当前步骤」→ 写 `cacheDir/rpa/pick/request.json`，经 `RpaPickCoordinator`
   回填编辑器当前步骤，并附带 `RpaNodeSnapshot`。

### 7.3 生成的定位表达式

- 单条 `RpaSelector`（AND 条件）+ 一个 `pick`；
- `snapshot` 用于：属性页展示、选择器全 miss 时的坐标兜底；
- 若用户只勾选了「可点击」等属性条件，也能成立（选择器非空即可）；
- 校验器对空选择器报 `a11y_selector_empty`，对「只有快照」报 `a11y_snapshot_only`（WARNING）。

### 7.4 与 AutoJs6 的差异

- 不提供 JS 注入 / 控件层级实时刷新循环，采用「手动刷新」快照，避免持续占用 a11y；
- 选择器生成后是**声明式数据**，不是脚本代码，便于跨设备复用与校验；
- 节点树来自 Assists，不重新实现无障碍服务。

---

## 8. 以图找图交互配置（VISION）

> 目标：在一个页面里完成「截帧 → 框选 → 保存模板 → 调阈值 → 即时测试 → 应用」，
> 并同时支持 OCR 文本定位与比例坐标兜底。

### 8.1 同源帧原则

配置页使用 `RpaAndroidDevice.visionConfigFrame()`：

- 只走 Shizuku screencap / MediaProjection；
- 即使当前开着 a11y，也不借用 a11y 截图；
- 保证「配置时看到的画面」与「运行时 vision 线路看到的画面」同源，模板不会因截图通道不同而失配。

### 8.2 交互流程

1. 抓帧；失败时提示需要 Shizuku 或录屏授权；
2. 在帧画布上拖拽框选 ROI（归一化 `RpaRect`），实时显示裁剪预览；
3. 「保存为模板」→ PNG 写入 `templates/<scriptId>/tpl_xxx.png`，并自动把主定位切到 `TEMPLATE`；
4. 模板参数：
   - 阈值滑杆 `0.50..0.99`（默认 `0.80`）；
   - 灰度开关（默认开）；
   - 区域（ROI）开关：可复用当前框选，也可清除；
   - `pick` 多命中策略；
   - `offsetX / offsetY` 像素偏移；
5. OCR 定位：输入文本 + 匹配口径 + `pick`，点「识别」展示当前帧 OCR 行，便于确认文案；
6. 比例定位：点画面直接取 `ratioX/ratioY`（进入「取点」模式），或手动输入；
7. 兜底定位：可再配置一条 `fallback`（模板 / OCR / 比例任意组合）；
8. 「测试」：对当前帧跑一次模板匹配，画面上画出命中框并显示分数 / 坐标；
9. 「应用」→ `onApply(RpaVisionSpec)` 回填编辑器当前步骤。

### 8.3 定位器执行顺序

```
vision.primary 命中 → 采用
vision.primary 未命中 → vision.fallback 命中 → 采用
两者都未命中 → 该线路定位失败
```

跨线路 fallback（`DUAL` 下 a11y ↔ vision）由 `RpaRunner` 负责，与这里同线路兜底是两层独立机制。

### 8.4 配置页与执行器的一致性

- ROI 统一使用归一化坐标，执行时按当前帧尺寸换算，不依赖分辨率；
- 模板匹配使用 `RpaTemplateMatcher`，配置页的即时测试与执行器共用同一实现；
- OCR 使用 `OcrProvider.recognize`，配置页展示的行即执行器断言/定位所用数据源；
- 比例坐标按当前帧宽高换算，旋转/分辨率变化仍可用。

---

## 9. 执行引擎与报因

### 9.1 运行就绪门（先于任何动作）

`RpaReadiness.check(script, caps, templateExists)`：

| 模式 | 条件 | 缺失 code |
|---|---|---|
| `A11Y` | `caps.a11yReady` | `ready_a11y_missing`（ERROR） |
| `VISION` | `caps.visionReady`（Shizuku 注入 + 抽帧） | `ready_vision_missing`（ERROR） |
| | `caps.shizukuReady`（点击 / 输入 / 滑动注入） | `ready_vision_injection_missing`（ERROR） |
| | `caps.ocrReady` | `ready_ocr_missing`（ERROR） |
| `DUAL` | 至少一条**可执行**线路（a11y 或 Shizuku 视觉） | `ready_no_line`（ERROR） |
| | 缺 a11y | `ready_dual_a11y_missing`（WARNING） |
| | 缺 vision（含仅录屏、无 Shizuku） | `ready_dual_vision_missing`（WARNING） |
| 任意 | 模板文件存在 | `ready_template_missing`（ERROR） |

> 录屏（MediaProjection）只提供抽帧，不能注入点击 / 输入。仅有录屏时仍可用「以图找图」页采集模板与验证 OCR，但 `VISION` / `DUAL` 的运行就绪门不会放行。
>
> 能力卡的两个入口：`!projectionReady && !shizukuReady` 时显示「授权录屏」；`!shizukuReady` 时显示「打开 Shizuku 授权页」（复用 `ShizukuGrantActivity` 的安装 / 授权 / appops 引导），返回后点「刷新」复查就绪状态。

存在 ERROR 时：`ScriptStarted(line=null)` → 逐条 `Log(not_ready ...)` → `ScriptFinished(false, readiness_failed)`，
**不会执行任何动作**。

### 9.2 单步执行流水线

```
delayBeforeMs
  → StepStarted
  → guardForeground（目标 App 守卫）
  → 按 action 执行
       ├─ 需要定位：locateWithFallback
       └─ 执行 / 输入 / 按键 / 断言（DUAL 失败可换线）
  → retries（整步重跑，400ms 间隔）
  → StepFinished(ok, detail)
  → 成功且 delayAfterMs>0 → delay(delayAfterMs)
  → 失败且 onFailure=STOP → ScriptFinished(false, index)
```

### 9.3 双模式换线规则

| 触发点 | 行为 |
|---|---|
| `TAP / LONG_PRESS / WAIT_FOR_TARGET` 定位 miss | 记录 `LineFallback(reason="locate_miss" / "wait_target_miss")`，切到另一线路重试定位 |
| `OPEN_CHAT / PRESS_KEY / SWIPE` 执行失败 | `onLine` 记录 `LineFallback` 后换线执行 |
| `INPUT_TEXT` 失败 | 记录 `LineFallback`，换线并强制 `clearFirst=true` 再输入 |
| `ASSERT_TEXT` 未命中 | 记录 `LineFallback(reason="assert_miss")` 后换线取文本 |
| 换线成功 | 本次运行**粘性**使用新线路（后续步骤不再切回） |
| 换线也失败 | 该步失败，按 `onFailure` 停止或继续 |

### 9.4 前台守卫

脚本 `appPackage` 非空时，除 `LAUNCH_APP / OPEN_CHAT / WAIT / COMMENT` 外，执行前检查前台包名：

- 不匹配 → `foreground_mismatch: expected=... actual=...`，该步失败；
- 防止自动化误点进自家 App 或通知栏（2026-10-21 真机事故教训）。

### 9.5 运行事件（UI 日志口径）

| 事件 | 用途 |
|---|---|
| `ScriptStarted(script, line?)` | 显示脚本名与起始线路；`line=null` 表示就绪失败 |
| `StepStarted(index, step, line?)` | 当前步骤与线路 |
| `StepFinished(index, step, ok, detail)` | 成功/失败报因 |
| `LineFallback(index, step, from, to, reason)` | 双模换线，必须显式展示 |
| `Log(message)` | 重试、就绪失败明细、继续执行等 |
| `ScriptFinished(ok, failedStepIndex?, detail)` | 收尾 |

### 9.6 常见失败码

| code / detail | 含义 | 处理 |
|---|---|---|
| `target_not_found` | 定位未命中且无可用兜底 | 用布局分析器 / 以图找图补定位，或检查前台 |
| `target_timeout` | `WAIT_FOR_TARGET` 超时 | 增大超时 / 检查页面 |
| `no_active_line` | 模式与能力不匹配 | 检查模式与能力卡 |
| `param_*_missing` | 动作必填参数为空 | 属性页补齐 |
| `foreground_mismatch` | 前台不是目标 App | 回到目标 App 或调整脚本 |
| `readiness_failed` | 就绪门未过 | 按日志逐条修复能力/模板 |
| `open_chat_failed` | 联系人会话打开失败 | a11y 用节点导航；vision 用视觉导航 |
| `a11y_not_ready` | 无障碍服务未连接 | 打开 Assists 无障碍 |

---

## 10. 微信「你好」测试夹具（仅 test）

### 10.1 定位

- 夹具代码：`app/src/sharedTest/java/com/verlintas/baic2/floatball/rpa/RpaWechatHelloFixture.kt`
  （同时挂给 `test` 与 `androidTest` 源集）
- JVM 单测：`app/src/test/java/com/verlintas/baic2/floatball/rpa/RpaWechatHelloFixtureTest.kt`
- 真机仪器测试：`app/src/androidTest/java/com/verlintas/baic2/floatball/rpa/RpaWechatHelloDeviceTest.kt`
- **不进入发行版**：没有 Demo 卡片、生成按钮、菜单、示例资源或联系人预填；
  发行版新建脚本只调用 `RpaScriptFactory.starter()`。
- 画像 / 联系对话页的「RPA」按钮只预填 `EXTRA_IM_PACKAGE`，不生成脚本。

### 10.2 七步流程

| # | Action | 参数 | a11y 定位 | vision 定位 |
|---|---|---|---|---|
| 1 | `LAUNCH_APP` | `com.tencent.mm`，`delayAfterMs=5000` | — | — |
| 2 | `TAP` 搜索图标 | — | `description` 含「搜索」或 `viewId` 含 `search` | `RATIO(0.88, 0.075)` |
| 3 | `INPUT_TEXT` | `${contactName}`，`clearFirst=true` | — | — |
| 4 | `TAP` 联系人 | — | `text` 含 `${contactName}` + `clickableOnly` | OCR `${contactName}` + 区域；比例兜底 |
| 5 | `TAP` 输入框 | — | `editableOnly` + `BOTTOM_MOST` | 比例 `(0.50, 0.88)` |
| 6 | `INPUT_TEXT` | `你好`，`clearFirst=true` | — | — |
| 7 | `TAP` 发送 | — | 文本/描述「发送」+ `BOTTOM_MOST` | OCR「发送」+ 比例兜底 |

严格 7 步，不含第 8 步断言；脚本是否发送成功由测试用例断言引擎结果。

### 10.3 跑法

```powershell
# JVM：结构与 FakeRpaDevice 执行
.\gradlew.bat :app:testDebugUnitTest --tests "*RpaWechatHelloFixtureTest*"

# 真机：7 步实跑（微信已登录、已解锁、无障碍或 Shizuku 就绪）
adb -s <serial> shell am instrument -w `
  -e class com.verlintas.baic2.floatball.rpa.RpaWechatHelloDeviceTest `
  -e contactName "吴瑞曦topwayricky" `
  com.verlintas.baic2.test/androidx.test.runner.AndroidJUnitRunner
```

JVM 用例 1 校验结构 / 双线路 / 校验器；用例 2 用 `FakeRpaDevice` 跑完整脚本，
断言 7 次 `StepStarted`、7 次 `StepFinished(ok=true)`、最终 `ScriptFinished(ok=true)`。
真机用例断言脚本成功后聊天页出现「你好」；微信未安装 / 未解锁 / 两条线路都不可用时
`assume` 跳过（模拟器无微信即为跳过路径，已实测）。

### 10.4 预期行为（夹具）

- a11y 可用：全程 `line=A11Y`；
- 仅 vision 可用：`DUAL` 起始线路为 `VISION`（`initialLine()` 先看 a11y，再 vision）；
- a11y 某步定位失败但 vision 可用：日志出现 `LineFallback(A11Y→VISION, locate_miss)`，之后粘性走 vision；
- 真机上若发送键无文本/描述，用「以图找图」框选发送按钮并保存模板后重跑。

---

## 11. 验收清单

### 11.1 数据与存储

- [ ] 新建脚本后 `filesDir/rpa/scripts/<id>.json` 存在，字段完整、`steps` 顺序与 UI 一致；
- [ ] 拖动步骤后重新打开脚本，顺序保持；
- [ ] 复制脚本：新 id、新步骤 id、模板目录被复制；
- [ ] 删除脚本：JSON 与模板目录同时删除；
- [ ] 损坏 JSON 出现在「坏脚本」提示里，不崩溃、不静默消失；
- [ ] 复制失败（磁盘不可写）时列表不出现半成品脚本、模板目录无孤儿；
- [ ] 模板保存后 `templates/<scriptId>/<templateId>.png` 存在，重启 App 仍可匹配。

### 11.2 三模式

- [ ] `A11Y` 模式：视觉卡置灰、入口禁用；校验不要求 vision；
- [ ] `VISION` 模式：a11y 卡置灰、入口禁用；校验不要求 a11y；
- [ ] `DUAL` 模式：两侧都未配置时运行按钮禁用，并给出对应 code；
- [ ] 运行中切换模式/编辑被锁定；
- [ ] 就绪门不通过时不执行任何动作，日志逐条报 code。

### 11.3 布局分析器

- [ ] 画面点击命中最小节点；
- [ ] 过滤「可点击 / 可编辑 / 有文本」生效；
- [ ] 应用当前节点的选择器能在运行中命中同一元素；
- [ ] 无 a11y 时给出明确提示而不是空白页；
- [ ] 快照 bounds 与画面位置一致（含状态栏偏移）。

### 11.4 以图找图

- [ ] 框选 ROI 后模板预览正确；
- [ ] 阈值调低可命中、调高可过滤；
- [ ] 即时测试的命中框与执行器命中位置一致；
- [ ] OCR 文本定位能在当前聊天页找到「发送」；
- [ ] 比例兜底在模板/OCR 失败时生效；
- [ ] 同一脚本多个模板互不干扰。

### 11.5 双模执行

- [ ] 关闭 a11y 后 `DUAL` 脚本自动从 vision 起步；
- [ ] a11y 失败时日志明确出现 `LineFallback`，不是静默切换；
- [ ] 两条线路都失败时按 `onFailure` 停止/继续；
- [ ] 前台守卫能阻止在错误 App 内点击/输入；
- [ ] 重试次数、超时、`delayBeforeMs`、`delayAfterMs` 均按配置生效；
- [ ] `delayAfterMs` 只在最终成功后等待，失败 / 重试期间不等待；
- [ ] `WAIT` / 重试超过总预算时以 `run_timeout` 结束，最后事件与日志同因；
- [ ] 前台探针取不到包名（如系统限制）时本步失败，而不是放行执行。

---

## 12. 扩展路线

| 方向 | 建议实现 |
|---|---|
| 条件分支 / 循环 | 新增 `IF / ELSE / REPEAT` 容器步骤；保持 `steps` 树形嵌套，仍用 JSON 存储 |
| 子流程 | `CALL_SCRIPT(scriptId)`；运行时传入变量覆盖 |
| 数据驱动 | `variables` 升级为 `List<Map<String,String>>` 数据集，按行跑脚本 |
| 视觉能力 | 增加 `FEATURE_MATCH`（ORB）/ `COLOR` 定位；模板缩略图管理页 |
| 断言增强 | 节点属性断言（存在/文本/checked）、图像区域断言 |
| 录制回放 | 监听 a11y 事件生成草稿步骤，再人工补双线路 |
| 脚本市场 | JSON 文档可直接备份；补模板打包（zip）、导入与签名校验 |

---

## 13. 已知限制

1. **微信 a11y 树不稳定**：部分版本输入框/发送键不暴露文本或 `editable`，必须用布局分析器
   重新生成，或改用 vision 模板；这是 7 步夹具默认同时写两套定位的原因。
2. **无 a11y 的输入依赖 Shizuku**：录屏只能抽帧，不能注入点击 / 输入；没有 Shizuku 时
   `VISION` / `DUAL` 的就绪门会报 `ready_vision_missing` / `ready_vision_injection_missing`
   并拒绝运行。输入法候选、剪贴板 PASTE 行为因机型而异，失败会显式报 `input_failed`。
3. **模板跨分辨率**：模板匹配支持多尺度，但极端分辨率/比例变化仍可能失配；建议在目标设备上
   重新框选，或使用 OCR / 比例兜底。
4. **OCR 延迟**：抽帧 + OCR 比 a11y 节点查询慢，`WAIT_FOR_TARGET` 的超时需适当放宽。
5. **`RpaA11ySpec.snapshot` 是坐标兜底**：跨分辨率不可靠，只作为选择器全 miss 时的最后手段；
   校验器会给出 WARNING。
6. **脚本暂不支持并行 / 条件分支**：当前解释器是严格顺序执行，复杂流程需要拆成多个脚本。

---

## 14. 快速索引

| 想改什么 | 看哪里 |
|---|---|
| 动作种类 / 参数 / 校验 / 就绪门 | `vendor/RPA/.../model/RpaCatalog.kt` |
| 数据结构 / 事件 / 变量 | `vendor/RPA/.../model/RpaModels.kt` |
| 存储 / 模板 / 复制 / 坏文档 | `vendor/RPA/.../store/RpaScriptStore.kt` |
| 执行顺序 / 换线 / 前台守卫 | `vendor/RPA/.../engine/RpaEngine.kt` |
| 新建脚本骨架 | `vendor/RPA/.../model/RpaScriptFactory.kt` |
| 微信 7 步夹具（test/androidTest） | `app/src/sharedTest/.../rpa/RpaWechatHelloFixture.kt` |
| 真机 7 步仪器测试 | `app/src/androidTest/.../rpa/RpaWechatHelloDeviceTest.kt` |
| 双线路 Android 实现 | `app/.../rpa/RpaAndroidDevice.kt` |
| 拖拽编排 / 属性面板 | `app/.../rpa/RpaEditorScreen.kt` |
| 布局分析覆盖层 | `app/.../rpa/analyze/RpaAnalyzeOverlayController.kt` |
| 以图找图 | `app/.../rpa/RpaVisionScreen.kt` |
| 模板匹配 | `app/.../rpa/RpaTemplateMatcher.kt` |
| 入口 / 页面状态机 | `app/.../rpa/RpaActivity.kt` |
