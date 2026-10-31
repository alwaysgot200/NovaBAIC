# Tasks：运行前模式选择门 + Shizuku 无线调试文案修正

依据：`2026-10-30-运行选模式门与Shizuku文案修正-spec.md`（决策 D1-D7）。
完成口径：T1-T4 全部完成 + T5 验证双绿（编译 + license 门禁）。

## T1 文案：无线调试统一口径 + 开发者选项开启方法（D1/D2）

- [x] `values/strings.xml`：改值 `float_shizuku_prereq_wifi_adb`（删 USB/无线辨析、统一「无线调试」、补「设置→关于手机→连续多次单击 Android 版本号（约 7 次）开开发者选项」）；改值 `float_shizuku_prereq_dev`（删辨析句，保留硬前置事实）
- [x] `values-en/strings.xml`：同步两键英文改值
- 验收：两文件无「两个独立开关 / 不需要先开 USB 调试 / separate switch」类辨析表述；含连点版本号说明

## T2 RPA 列表页精简（D3）

- [x] `RpaLibraryScreen.kt`：`RpaCapabilityCard` 删 L598-621 三个修复 OutlinedButton，删 `onGrantProjection/onOpenShizuku/onOpenAccessibilitySettings` 参数；调用点（L270-276）同步
- [x] 保留 ModeReadyRow/CapChip 置灰状态行、刷新钮、`DualReadyGateDialog`（修复入口唯一落点）；不加新说明文字
- 验收：列表页能力卡只有双模式状态 + 能力 chip + 刷新；修复按钮只存在于新建/运行门弹框

## T3 运行前选模式（D4-D7）

- [x] `RpaLibraryScreen.kt`：新增 `RunModeSelectDialog`（候选行按 `script.mode`；未就绪置灰不可选 + 缺项修复入口；确认只对就绪的选中项可用）
- [x] `RpaActivity.kt`：`onStartRun: (String, RpaLine?) -> Unit`（L245/L181）；`requestRun(target, line: RpaLine)` 就绪 issue 按所选线过滤（D7 映射表）后走 ERROR 门；新增 `runModeRequest` 状态统一收口四处运行入口（L665/L894/L979 + 长按菜单经 runAction），DUAL 弹选模式、单模式就绪直跑/未就绪出修复门（D5）
- [x] `RpaEntryPoints.kt`：`startRun(context, scriptId, forceLine: RpaLine?)` + `EXTRA_FORCE_LINE`
- [x] `RpaRunService.kt`：解析 `EXTRA_FORCE_LINE` → `runner.run(script, forceLine)`
- [x] `vendor/RPA/.../RpaEngine.kt`：`RpaRunner.run(..., forceLine: RpaLine? = null)`；`initialLine()` 优先 forceLine（`RpaModePolicy.lineEnabled` + 本线就绪）；`forceLine != null` 禁用全部换线 fallback
- [x] 新增 2 个 `float_*` 文案键（`float_rpa_run_mode_title`、`float_rpa_run_gate_title`，zh+en）
- 验收：VISION-only 脚本只能以无 a11y 线运行且缺项被拦；DUAL 脚本用户选哪条线就锁哪条线（不静默换线）；缺项弹修复入口

## T4 strings 新键与规范自查

- [x] 新键 `float_*` 前缀、无硬编码用户文案；license 头不动（未新增 .kt 文件则无需处理）

## T5 验证

- [x] `.\gradlew.bat :app:assembleDebug --console=plain -q`（cwd=NovaBAIC）通过（含上轮遗留编译确认）
- [x] `wsl bash -c "cd /mnt/d/work/autojs6_Management/NovaBAIC && sed 's/\r$//' tools/check-license-headers.sh | bash"` → `All source files carry the license header.`
