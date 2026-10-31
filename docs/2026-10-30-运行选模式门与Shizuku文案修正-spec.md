# Spec：运行前模式选择门 + Shizuku 无线调试文案修正（2026-10-30）

状态：**待批准**。批准后按 `2026-10-30-运行选模式门与Shizuku文案修正-tasks.md` 执行。

## 0. 用户需求（原文锚定）

> (1) 你说："不需要「先开 USB 调试、再开无线调试」。两者是开发者选项里两个独立开关"
> =====》》我们的 shizuku 用户并不懂开发，所以我们这里统一采用"无线调试"吧，
> 且要在文字中说明用户如果打开"开发者选项"（多次单击 andfroid 的版本号）
>
> (2) 你说："并给出对应修复按钮（去开启无障碍 / 打开 Shizuku 授权页 / 授权录屏）"；
> ====>>>只要保证"新建" 之前去打开 2 种模式相应设置即可。不用在 RPA 脚本列表页中写很多的说明文字。
> 另外，在单击按钮运行时，应该先让用户选择可用的运行模式。
> 比如某个脚本在设计时选择仅支持"无 a11y"模式，则在运行时，只能以"无 a11y" 的模式运行，
> 且要求前置条件具备。

## 1. 已核实事实（代码调研结论）

| 事实 | 位置 |
| --- | --- |
| 运行管线唯一入口 `requestRun(target)`：persist → `RpaValidator.validate` + `RpaReadiness.check` → ERROR 即弹问题列表，否则 `onStartRun(saved.id)` | `app/.../rpa/RpaActivity.kt` L340-357 |
| `onStartRun: (String) -> Unit` 接线到 `RpaEntryPoints.startRun(this, scriptId)` | `RpaActivity.kt` L181、L245 |
| `RpaEntryPoints.startRun` 只发 `EXTRA_SCRIPT_ID`；`RpaRunService.startRun(scriptId)` → `RpaRunner.run(script)`，无线路参数 | `RpaEntryPoints.kt` L72-77；`RpaRunService.kt` L88-146、L343 |
| 引擎 `RpaRunner` 的 `initialLine()`（`vendor/RPA/.../RpaEngine.kt` L756-761）：`A11Y→A11Y.takeIf{a11yReady}`；`VISION→VISION.takeIf{visionReady}`；`DUAL→a11y 优先、vision 兜底`；换线 fallback 仅 `script.mode==DUAL` 生效（L526/674/722/778/797） | `RpaEngine.kt` |
| `RpaModePolicy`（`RpaCatalog.kt` L272-282）：`lineEnabled(mode, line)` 可判定「某模式是否允许某线」 | `vendor/RPA/.../RpaCatalog.kt` |
| 就绪口径（用户已定案）：a11y 模式 = `capabilities.a11yReady`；无 a11y 模式 = `shizukuReady && ocrReady && projectionReady`（`visionModeReady()`） | `RpaLibraryScreen.kt` L533-541 |
| `RpaCapabilityCard`（L543-623）含 3 个修复 OutlinedButton（L598-621：去开启无障碍/打开 Shizuku 授权页/授权录屏）——用户要求从列表页删除 | `RpaLibraryScreen.kt` |
| 新建前置门 `DualReadyGateDialog`（L681-758）已有缺项报因 + 3 个直达修复入口——保留为唯一修复入口 | `RpaLibraryScreen.kt` |
| Shizuku 前置文案：`float_shizuku_prereq_wifi_adb`（`values/strings.xml` L150）含「USB 调试与无线调试是两个独立开关，不需要先开 USB 调试」辨析长文；`float_shizuku_prereq_dev`（L149）含「它与普通 USB 调试是两个独立开关」句；values-en L886-887 对应 | `app/src/main/res/values[-en]/strings.xml` |
| 运行按钮三处：库页行内「运行」（L359 经 `runAction`）、长按菜单「运行」（L402-405）、属性页（`RpaActivity.kt` L979）、浮窗面板（L665） | 两文件 |
| `RpaRunController.State.Running` 已带 `line: RpaLine?`，换线事件 `LineFallback` 已有 | `RpaRunController.kt` / `RpaRunEvent` |
| vendor/RPA（`com.verlintas.rpa`）是本分支自有模块，可改；AGENTS.md §4.2 白名单仅约束上游文件 | `AGENTS.md` |

## 2. 设计决策

- **D1（文案·无线调试统一口径）**：`float_shizuku_prereq_wifi_adb` 删去 USB/无线「两个独立开关」辨析，主语统一「无线调试」；补开发者选项开启方法——「设置 → 关于手机（关于本机），连续多次单击『Android 版本号』（约 7 次）」。保留既有关键事实：无线调试跑在 WLAN、Wi-Fi 断会被系统关回、先连稳定 Wi-Fi、启动成功后 Shizuku 常驻到重启。
- **D2（文案·`float_shizuku_prereq_dev`）**：**保留该前置项**（小米/红米等机型的「USB 调试（安全设置）」是自动点击命令的硬前置，删掉会违反 AGENTS.md §4.5「硬前置必须显式提示」），但同样删去「两个独立开关」辨析句，改为平实表述：「部分机型（小米/红米等）还需打开「USB 调试（安全设置）」，否则自动点击等命令会权限不足」。若用户要求彻底不提 USB 字样，批准时一并推翻此决策即可。
- **D3（列表页精简）**：`RpaCapabilityCard` 删 3 个修复按钮及其参数（`onGrantProjection/onOpenShizuku/onOpenAccessibilitySettings` 从该卡片签名移除），保留双模式状态行（ModeReadyRow + CapChip 置灰口径）与「刷新」。缺项修复入口只留在「新建」前置门 `DualReadyGateDialog`。不加任何新说明文字。
- **D4（运行选模式·落点）**：模式选择弹框放在 `RpaApp`（RpaActivity.kt）层统一收口（新增状态 `runModeRequest: RpaScript?`），库页行内运行、长按菜单运行、属性页运行、浮窗面板运行**全部**先经它，再带选定线路调 `requestRun(target, line)`。
- **D5（弹框出现规则）**：`script.mode == DUAL` → 每次运行都弹「选择运行模式」（a11y / 无 a11y 单选；未就绪项置灰不可选并给缺项 + 修复入口）；`A11Y`/`VISION` 单模式脚本 → 模式唯一无需选择：该模式就绪则直接运行，未就绪则弹「前置条件未就绪」修复门（确认钮禁用）。（若用户要求单模式也每次弹框确认，批准时指出即可。）
- **D6（运行模式必须真正约束执行线）**：用户所选模式不允许引擎偷偷换线。实现：`forceLine: RpaLine?` 全链路透传——`RpaActivity.onStartRun: (String, RpaLine?) -> Unit` → `RpaEntryPoints.startRun(context, scriptId, forceLine)` → Intent `EXTRA_FORCE_LINE` → `RpaRunService` → `RpaRunner.run(script, forceLine)`；`initialLine()` 优先返回 `forceLine.takeIf { RpaModePolicy.lineEnabled(script.mode, it) && 本线就绪 }`；**`forceLine != null` 时禁用换线 fallback**（失败显式报因，不静默切另一条线，符合 §4.5 禁令）。`forceLine == null` 时保持现行为不变。
- **D7（前置校验按所选线路）**：`requestRun(target, line)` 的就绪校验按**所选线**收口——`RpaValidator.validate`（行无关，全保留）+ `RpaReadiness.check` 结果按 issue 归属过滤：走 a11y 线丢弃 `ready_vision_*`/`ready_dual_vision_missing`；走 vision 线丢弃 `ready_a11y_missing`/`ready_dual_a11y_missing`；`ready_template_missing`/`ready_no_line` 两线都保留。ERROR 非空仍只弹问题列表不启动。就绪口径与弹框一致：a11y 线 = `a11yReady`；vision 线 = `shizukuReady && ocrReady && projectionReady`。

## 3. 变更清单（文件级）

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `app/src/main/res/values/strings.xml` | 改值：`float_shizuku_prereq_wifi_adb`（L150，按 D1）、`float_shizuku_prereq_dev`（L149，按 D2）；追加 `float_rpa_run_mode_title`、`float_rpa_run_gate_title`（`float_*` 前缀，符合 §4.2 纯增量口径） |
| 2 | `app/src/main/res/values-en/strings.xml` | 同步 #1 的英文（L886-887 改值 + 2 新键） |
| 3 | `app/.../rpa/RpaLibraryScreen.kt` | ① `RpaCapabilityCard` 删 L598-621 三个修复按钮与三个回调参数（调用点 L270-276 同步）；② 新增 `RunModeSelectDialog`（internal，见 §4）供 RpaApp 复用；`DualReadyGateDialog` 与状态 chip 区不动 |
| 4 | `app/.../rpa/RpaActivity.kt` | ① `onStartRun: (String) -> Unit` → `(String, RpaLine?) -> Unit`（L245、L181 接线）；② `requestRun(target, line: RpaLine)`：按 D7 过滤就绪 issue，`onStartRun(saved.id, line)`；③ 新增 `runModeRequest` 状态 + 运行模式/前置门弹框接线，L665/L894/L979 三处 `onRun` 改为 `runModeRequest = target`（`runAction` 的「运行中→停止 / 忙→toast」逻辑保留在库页） |
| 5 | `app/.../rpa/RpaEntryPoints.kt` | `startRun(context, scriptId, forceLine: RpaLine?)`，Intent 追加 `EXTRA_FORCE_LINE`（值 `"A11Y"`/`"VISION"`） |
| 6 | `app/.../rpa/RpaRunService.kt` | 读 `EXTRA_FORCE_LINE` 映射回 `RpaLine?`，传 `runner.run(script, forceLine)`；常量 `EXTRA_FORCE_LINE` |
| 7 | `vendor/RPA/.../engine/RpaEngine.kt` | `RpaRunner.run(...)` 增 `forceLine: RpaLine? = null`；`initialLine()` 优先 forceLine（经 `RpaModePolicy.lineEnabled` + 本线就绪校验）；`forceLine != null` 时全部换线 fallback（L526/674/722/778/797）不再触发 |

不改：`RpaModels.kt`、`RpaCatalog.kt`（`RpaModePolicy`/`RpaReadiness` 现有 API 够用）、`RpaRunController.kt`、`DualReadyGateDialog`、ShizukuGrantScreen（前置区已含无线调试条目）。

## 4. `RunModeSelectDialog` 交互规格

- 标题：`float_rpa_run_mode_title`（选择运行模式）。
- 候选行 = 脚本 `mode` 决定：`DUAL` → [a11y 模式, 无 a11y 模式]；`A11Y` → [a11y 模式]；`VISION` → [无 a11y 模式]。行内：RadioButton + 模式名（复用 `float_rpa_mode_line_a11y`/`float_rpa_mode_line_vision`）+ 就绪/未就绪 chip（复用 `float_rpa_mode_status_ready/missing`）。
- 就绪口径同 D7：a11y = `a11yReady`；无 a11y = `shizukuReady && ocrReady && projectionReady`。未就绪行**置灰不可选**（与能力卡同一置灰口径）。
- 未就绪缺项在弹框内给修复入口（与 `DualReadyGateDialog` 同款，复用 `float_rpa_gate_go_a11y` / `float_rpa_open_shizuku` / `float_rpa_grant_projection` 与三个回调）：a11y 缺→去开启无障碍；无 a11y 缺→打开 Shizuku 授权页 / 授权录屏。
- 确认「运行」（复用 `float_rpa_run`）：仅在已选中且就绪的模式可用 → `onConfirm(mode → RpaLine)`；取消（`float_cancel`）。
- 单模式脚本（D5）：同一弹框渲染单行；唯一模式就绪则不出弹框直接跑，未就绪出弹框（确认禁用、只留修复入口），标题用 `float_rpa_run_gate_title`。

## 5. 新增文案（zh / en）

| 键 | 中文 | English |
| --- | --- | --- |
| `float_rpa_run_mode_title` | 选择运行模式 | Choose run mode |
| `float_rpa_run_gate_title` | 运行前置条件未就绪 | Run prerequisites not ready |

`float_shizuku_prereq_wifi_adb` 新值（D1）：
「开发者选项里打开「无线调试」。先打开开发者选项：进入「设置 → 关于手机（关于本机）」，连续多次单击「Android 版本号」（约 7 次）即会出现开发者选项。没有电脑时，Shizuku「通过无线调试启动」是唯一的启动口，无线调试不开就起不来。注意无线调试跑在 WLAN 上：先连稳定的 Wi-Fi 再开，Wi-Fi 一断系统会自动把它关回去；启动成功后 Shizuku 常驻到手机重启，之后关掉无线调试不影响使用。」

`float_shizuku_prereq_dev` 新值（D2）：
「开发者选项里打开「USB 调试（安全设置）」（部分机型如小米/红米需要它）——不开它，自动点击等命令会权限不足。」

## 6. 验证

1. 编译：`.\gradlew.bat :app:assembleDebug --console=plain -q`（cwd=`d:\work\autojs6_Management\NovaBAIC`；含 vendor/RPA 模块；顺带确认上轮 `openAccessibilitySettings`/`float_cancel` 修复后的编译通过）。
2. license 门禁（脚本 CRLF 中毒，必须去 CR 后经 WSL bash）：
   `wsl bash -c "cd /mnt/d/work/autojs6_Management/NovaBAIC && sed 's/\r$//' tools/check-license-headers.sh | bash"` → 期望 `All source files carry the license header.`
3. 自查：strings.xml 新键 `float_*` 前缀、无硬编码文案；`RpaCapabilityCard` 无修复按钮；DUAL 运行必弹选模式；VISION-only 脚本只能以无 a11y 线运行且缺项被拦（代码级走查：`initialLine()` 强线 + fallback 禁用）。

## 7. 风险与回退

- 引擎加 `forceLine` 是带默认值的纯增量参数，不传即现行为，对既有运行零影响。
- 文案删辨析句只影响展示，不动 ShizukuGrantScreen 结构。
- 若批准时推翻 D2（彻底不提 USB 字样）或 D5（单模式也每次弹框），改动集中在 strings 与弹框出现条件，成本极小。
