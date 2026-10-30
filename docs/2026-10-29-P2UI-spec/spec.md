# RPA 重构 P2 阶段 UI 规格（spec）

> 母规格：[2026-10-28-重构RPA动作设计的方案.md](../2026-10-28-重构RPA动作设计的方案.md)（v4，已批准）。
> 本文件只把 p2 剩余工作落成决策完备的执行规格；与母规格冲突时以母规格为准。

## 0. 硬约束（不得违背）

1. `analyze/`、`quickfind/`、`auto/TouchExplorationGate.kt`、`auto/AssistsA11yBridge.kt`、`res/xml/accessibility_service_config.xml` **只可调用不可改**（布局分析工具已完成，只能优化加强，本阶段零改动）。
2. 拾取回填唯一通道 = `RpaPickCoordinator.arm(RpaPickRequest)`（编辑器 arm → 工具直写 store → 重开编辑器定位步骤），p2-4 弹框拾取必须复用。
3. 四项用户决策：①导入三格式自动识别；②条件=结构化+公式 DSL（L2）；③找色/多指/录制=P2 占位禁假入口；④浮窗宽=屏宽×55%，45%–75% 拖拽按比例记忆。
4. 用户可见文案一律进 `strings.xml`（`float_rpa_*` 前缀）；GPL 头必带；不新增 Gradle 模块；不做假入口。

## 1. 现状锚点（已核实）

- `RpaActivity.kt`：`RpaScreen{LIBRARY,EDITOR,VISION}`；`persist/requestRun/exportScript/startPick/openEditor/returnToStepProps` 就绪；`onOpen/onEdit/onCreate/onImport` 过渡走 `openEditor()`。
- `AdvisorPage.kt`：`AdvisorPage(title, onBack, actions, content)` 已带 `actions` 槽；`DeleteConfirmDialog` 共用。
- `RpaComponents.kt`：`RpaCard/RpaSectionTitle/RpaModeBadge/RpaStatusBadge(text, ok)/RpaModeSelector(mode, enabled, onMode)/RpaIssueList` 可复用。
- `RpaScript` 无 `importSource` 字段、无分辨率记录；`RpaCapabilities` 五布尔；`schemaVersion=2`。
- `strings.xml` 已有 69 个 `float_rpa_*` 键，新键只补缺口，不重复。
- `Settings.ACTION_ACCESSIBILITY_SETTINGS` 现成写法：`FloatBallService.kt:3118`。

## 2. p2-2：属性页 + 模式确认门（本次执行）

### 2.1 新文件 `app/src/main/java/com/verlintas/baic2/floatball/rpa/RpaPropertyScreen.kt`

```kotlin
@Composable
internal fun RpaPropertyScreen(
    script: RpaScript,
    capabilities: RpaCapabilities,
    runState: RpaRunController.State,
    onBack: () -> Unit,
    onEdit: () -> Unit,                       // 点「编辑」时宿主先弹 RpaModeGate
    onRun: () -> Unit,
    onStopRun: () -> Unit,
    onShare: (RpaExportFormat) -> Unit,       // = exportScript
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDescriptionChange: (String) -> Unit,    // persist(script.copy(description=…))
    onRefreshCapabilities: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
)
```

布局（§7.2 图10）：

- 骨架 = `AdvisorPage(title = stringResource(R.string.float_rpa_property_title), onBack, actions = { ⋮ 菜单钮 })`。
  - `⋮` DropdownMenu：重命名 / 复制 / 导出（打开格式选择弹窗）/ 删除（`DeleteConfirmDialog`）。重命名复用 `RenameScriptDialog` 模式（把 `RpaLibraryScreen` 内的 `RenameScriptDialog`、`ExportFormatDialog` 可见性 `private`→`internal` 共用，纯可见性放宽，不改行为）。
- 头部 Row：64dp 圆形 Surface（primaryContainer，脚本名首字做装饰图标）+ Column{名称（titleLarge 加粗）+「%1$d 个动作 · %2$.1f KB」用 `float_rpa_script_steps` + `float_rpa_script_kb`/`float_rpa_script_meta` 口径}。KB = `script.steps` 序列化字节数口径与 `RpaLibraryScreen` 一致（`store.exportBytes(JSON).size / 1024` 不可取——属性页不拿 store；改用 `RpaJsonCodec.encode(script).size`，vendor 可达）。
- 区块一「脚本描述」（`float_rpa_script_description`）：正文显示 `script.description`（空则灰色提示文案）+ 右上「编辑」小 TextButton → AlertDialog 单行输入 → `onDescriptionChange`。
- 区块二「运行环境」（`float_rpa_property_env`）：`RpaModeBadge(script.mode)` + 模式提示（`rpaModeHint`）；四就绪徽标 `RpaStatusBadge`：无障碍/Shizuku/录屏/OCR（`float_rpa_cap_*`）；视觉就绪行复用 `float_rpa_cap_vision_ready/missing` 口径；授权钮：`onOpenShizuku`、`onGrantProjection`、`onRefreshCapabilities`（与 `RpaCapabilityCard` 同款，不复制实现，调用共享件）。
- 区块三「基本信息」（`float_rpa_property_basic`）：
  - 动作数量：`float_rpa_script_steps`。
  - 分辨率：**仅当前值**——`LocalConfiguration` + `LocalDensity` 取实际 px（`screenWidthDp→px` 同 DisplayMetrics 口径），文案 `float_rpa_property_resolution_value="%1$d×%2$d"`；「推荐」无数据源，整行不放推荐值（不造数据）。
  - 创建时间 / 更新时间：`yyyy-MM-dd HH:mm`（`java.text.SimpleDateFormat`，Locale.getDefault）。
  - 数据版本：`schemaVersion`。
  - **「导入来源」省略**：模型无字段、store 不记录，按「不做假入口」原则不渲染该行（有意偏差，与母规格 §7.2 差一行，理由记录在案）。
- 「脚本市场（上传/售卖）· 云备份」：两行禁用占位（`float_rpa_property_market`/`float_rpa_property_cloud_backup` + 尾缀「即将开放」chip），整行 `enabled=false`，不响应点击（禁用占位 ≠ 假入口，符合母规格）。
- 底部固定操作条（Column 底部 surface + divider，内容区 `verticalScroll` 让出高度）：
  - 左段三个图标钮：分享（`Icons.Outlined.Share`，`material-icons-core` 有 Share；点击→`ExportFormatDialog`→`onShare(format)`）、云备份（禁用）、上传市场（禁用）。
  - 右段：`编辑`（OutlinedButton，`onEdit`）+ `运行`（FilledButton；`runState` 运行中→文案 `float_rpa_stop`/`onStopRun`）。

### 2.2 新文件 `app/src/main/java/com/verlintas/baic2/floatball/rpa/RpaModeGate.kt`

```kotlin
@Composable
internal fun RpaModeGateDialog(
    script: RpaScript,
    capabilities: RpaCapabilities,
    onDismiss: () -> Unit,
    onConfirm: (RpaMode) -> Unit,
    onRefreshCapabilities: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onGrantProjection: () -> Unit,
    onOpenShizuku: () -> Unit,
)
```

布局（§7.4）：

- `AlertDialog`（复用 `RpaLibraryScreen` 弹窗观感）：标题 `float_rpa_gate_title`「编辑前确认：运行模式」。
- 正文：`RpaModeSelector(mode, enabled = true, onMode)`（本地 remember 态，确认才回传）；就绪状态行（四 `RpaStatusBadge` + `float_rpa_gate_refresh`「刷新检测」TextButton → `onRefreshCapabilities`）。
- **A11Y 硬前置**：选中 `RpaMode.A11Y` 且 `!capabilities.a11yReady` → 红色警告行 `float_rpa_gate_a11y_required` + `[去开启无障碍]` 钮（`onOpenAccessibilitySettings` = `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`，仿 `FloatBallService.kt:3118`；宿主 RpaApp 已有 `ON_RESUME` 自动复检 `refreshCapabilities`，返回即更新徽标）。此时**「进入编辑」禁用**。
- 双模/视觉缺 Shizuku/录屏/OCR：黄色警告行 `float_rpa_gate_warn_vision`「仅影响运行，不影响编辑」，不阻断「进入编辑」。
- 按钮行：`[取消]（float_cancel）[进入编辑]（float_rpa_gate_enter）`；`onConfirm(选中模式)` → 宿主 `persist(script.copy(mode))` 后进编辑。

### 2.3 `RpaActivity.kt` 过渡接线（p2-6 再统一到浮窗）

- `RpaScreen` 增加 `PROPERTY`。
- 新增局部 `openProperty(target)`：`script = target; screen = PROPERTY`。
- `onOpen`、`onEdit`、`onCreate`（创建成功后）、`onImport`（入库后，§九）→ `openProperty(...)`。
- PROPERTY 分支：`RpaPropertyScreen(...)`；`onEdit` → 弹 `RpaModeGateDialog`；`onConfirm(mode)` → `persist(script.copy(mode))` 后 `openEditor()`（**过渡**：p2-3 浮窗就绪后改为开 `RpaActionPanel`）；`onShare(format)` → `exportScript`；`onRun` → `requestRun`。
- BackHandler：PROPERTY → `persist` 后回 LIBRARY（保存失败留在 PROPERTY，与 `leaveEditor` 同口径）。
- 拾取回跳（`initialScriptId`）暂仍走 `openEditor`（p2-6 改指 PROPERTY→浮窗）。

### 2.4 `strings.xml` 新增键（全部 `float_rpa_*`，中文）

`float_rpa_property_title`（本地脚本详情）、`float_rpa_property_env`（运行环境）、`float_rpa_property_basic`（基本信息）、`float_rpa_property_resolution_value`（%1$d×%2$d）、`float_rpa_property_created`、`float_rpa_property_updated`、`float_rpa_property_schema`（数据版本）、`float_rpa_property_market`（脚本市场（上传 / 售卖））、`float_rpa_property_cloud_backup`（云备份）、`float_rpa_property_coming_soon`（即将开放）、`float_rpa_property_share`（分享）、`float_rpa_property_upload_market`（上传市场）、`float_rpa_description_hint`（未填写描述）、`float_rpa_description_edit`（编辑描述）、`float_rpa_gate_title`、`float_rpa_gate_ready`（就绪状态）、`float_rpa_gate_a11y_required`、`float_rpa_gate_go_a11y`（去开启无障碍）、`float_rpa_gate_refresh`（刷新检测）、`float_rpa_gate_enter`（进入编辑）、`float_rpa_gate_warn_vision`。

### 2.5 验证（p2-2）

1. `.\gradlew :app:compileDebugKotlin`（cwd=`d:\work\autojs6_Management\NovaBAIC`）编译通过。
2. 不触碰 analyze/、quickfind/、Pick 契约文件（diff 零涉及）。

## 3. 后续阶段概要（详细规格见母规格 §7.5–7.8 / §十）

- **p2-3 `RpaActionPanel.kt` 浮窗**（§7.5）：宽=屏宽×55%，45%–75% 拖边缘按比例记忆（`SettingsRepository` 或 prefs）；高≤屏高 65%；墨板 #303030 系、圆角 16dp、elevation 8dp；标题栏整条拖动+贴边吸附+收起 44dp 圆球；步骤行 36dp（序号徽+动作名+×N、长按=步骤菜单、拖拽排序）；`+添加动作` 虚线行；底栏 `▶运行|录制(P2置灰)|•••`；••• 菜单（保存/远程编辑置灰/清空/缩小/日志/变量/导入）；与悬浮球互斥 `RpaChromeGate.hide(REASON_EDIT)`。
- **p2-4 `RpaStepDialog.kt` + `RpaGlobalSettingsDialog.kt`**（§7.6）：字段矩阵按动作裁剪；单位块秒/毫秒；坐标「...」钮 → `RpaPickCoordinator.arm(RpaPickRequest)`（唯一拾取通道）；跳转选择器 stepId 锚定+相对步数糖+实时预览；运行条件子弹框（未设置/变量比较/出现图片内嵌模板/出现文字/上步成功/上步失败）；全局设置弹窗（图12）落 `RpaScript.global`。
- **p2-5**：`RpaChromeGate` 纯追加 `REASON_EDIT`；`RpaVisionScreen` 扩展（ROI 越界%四值/相似度 50–99%/匹配第几/☐快速搜图/埋图模式占位，模型字段已就绪）。
- **p2-6**：`RpaActivity` 导航统一 LIBRARY→PROPERTY→门→浮窗；导入后自动进属性页（§九）；删除被跳转步骤三选项弹窗；门禁复跑 `.\gradlew :app:testDebugUnitTest` + `.\gradlew :app:assembleDebug` + license 检查（CRLF 绕过法）。
- **p3-1**：删 `RpaEditorScreen.kt` + 手册 §六同步。

## 4. 验收口径

- 母规格 §十改动清单逐项落地；§七 UI 规范逐屏对照；§十二验证全绿。
- 布局分析工具与拾取契约文件 diff 为空。
- 无假入口：P2 占位一律禁用 + 「即将开放」。
