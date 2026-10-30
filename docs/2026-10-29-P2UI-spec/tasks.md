# RPA 重构 P2 阶段任务清单（tasks）

依据 [spec.md](./spec.md) 与母规格 [2026-10-28-重构RPA动作设计的方案.md](../2026-10-28-重构RPA动作设计的方案.md)。
已完成（p1 全部、p2-1）不再列出细节，仅保留勾选记录。

## 已完成

- [x] p1-1 `RpaModels.kt`：新枚举/RpaStepCondition/RpaGlobalSetting/RpaCustomAction/repeat/schemaVersion=2
- [x] p1-2 `RpaCatalog.kt`：动作 spec/defaultStep/校验/RpaLimits
- [x] p1-3 `RpaConditionDsl.kt`：L2 公式解析求值
- [x] p1-4 `RpaEngine.kt`：handler 注册表 + PC 循环 + 条件/跳转/repeat
- [x] p1-5 store：JSON/XML codec + .rpa 包 + 导入导出
- [x] p1-6 `RpaDevice` 端口与 `RpaAndroidDevice` 对接
- [x] p1-7 单测 + assembleDebug + license 检查
- [x] p2-1 `RpaLibraryScreen` 行式改版 + 行内运行 + 长按菜单 + 导入入口

## p2-2（进行中）：属性页 + 模式确认门

- [x] 2a. `RpaLibraryScreen.kt`：`RenameScriptDialog`、`ExportFormatDialog` 可见性 `private`→`internal`（纯放宽，不改行为）
- [x] 2b. 新建 `RpaPropertyScreen.kt`（spec §2.1：AdvisorPage 骨架 + ⋮ 菜单 + 头部 + 三区块 + 即将开放占位 + 底部操作条）
- [x] 2c. 新建 `RpaModeGate.kt`（spec §2.2：模式单选 + 就绪徽标 + A11Y 硬前置禁用「进入编辑」+ 去开启无障碍）
- [x] 2d. `RpaActivity.kt`：`RpaScreen.PROPERTY` + `openProperty` + onOpen/onEdit/onCreate/onImport 接线 + BackHandler + 模式门→openEditor 过渡（spec §2.3）
- [x] 2e. `strings.xml`：新增 spec §2.4 全部 `float_rpa_*` 键
- [x] 2f. 验证：`.\gradlew :app:compileDebugKotlin` 通过；analyze/quickfind/Pick 契约文件 diff 为空

## p2-3：`RpaActionPanel.kt` 浮窗（§7.5）

- [x] 3a. 新建浮窗 ComposeView 覆盖层（55% 屏宽、45–75% 拖拽记忆、高≤65%、墨板观感）
- [x] 3b. 标题栏拖动 + 贴边吸附 + 收起 44dp 圆球；齿轮=RpaGlobalSettingsDialog、✕=退出
- [x] 3c. 步骤行 36dp（序号徽/动作名/×N/选中态/长按菜单/拖拽排序）+ `+添加动作` 虚线行
- [x] 3d. 底栏 `▶运行|录制(P2置灰)|•••`；••• 菜单七项（保存/远程编辑置灰/清空动作/缩小面板/开启日志/查看变量+导出/导入）
- [x] 3e. `RpaChromeGate.hide(REASON_EDIT)` 互斥接入（REASON_EDIT 常量已在 RpaChromeGate 纯追加定义）
- [x] 3f. 验证：compileDebugKotlin + 浮窗与悬浮球互斥

## p2-4：`RpaStepDialog.kt` + `RpaGlobalSettingsDialog.kt`（§7.6）

- [x] 4a. 字段矩阵按动作类型裁剪（打开应用/点击/点击图片/点击文字/设置变量/控制运行/打开链接/复合动作）
- [x] 4b. 单位块 秒/毫秒；坐标/图片位置「...」钮 → `RpaPickCoordinator.arm(RpaPickRequest)`（唯一拾取通道）
- [x] 4c. 跳转选择器：stepId 锚定 + 相对步数糖 + 实时预览
- [x] 4d. 运行条件子弹框（未设置/变量比较/出现图片内嵌模板/出现文字/上步成功/上步失败）
- [x] 4e. `RpaGlobalSettingsDialog`（图12）落 `RpaScript.global`（默认等待/重复/失败即停/全局监听P2置灰/手势变形P2置灰）
- [x] 4f. 验证：compileDebugKotlin + 拾取契约文件 diff 为空

## p2-5：ChromeGate + VisionScreen 扩展

- [x] 5a. `RpaChromeGate.kt` 纯追加 `REASON_EDIT` 常量（p2-3 已加，本次核对存在，零改动）
- [x] 5b. `RpaVisionScreen.kt` 扩展：ROI 越界%四值可拖框/相似度 50–99%/匹配第几/☐快速搜图/埋图模式默认值占位（模型字段已就绪）
- [x] 5c. strings 补缺 + 验证 compileDebugKotlin

## p2-6：导航统一 + 构建门禁

- [x] 6a. `RpaActivity` 导航统一 LIBRARY→PROPERTY→门→浮窗；导入后自动进属性页（§九）
- [x] 6b. 删除被跳转步骤三选项弹窗（一并清除跳转/跳转悬空标红/取消）
- [x] 6c. 门禁复跑：`.\gradlew :app:testDebugUnitTest` + `.\gradlew :app:assembleDebug` + license 检查（CRLF 绕过法：去 \r 后 wsl bash 执行）

## p3-1：退场

- [x] 7a. 删除 `RpaEditorScreen.kt`，清理引用
- [x] 7b. 手册 §六同步更新

## 验收口径

- 母规格 §十改动清单逐项落地、§七 UI 规范逐屏对照、§十二验证全绿。
- 布局分析工具（analyze/quickfind/）与 RpaPickCoordinator 契约文件 diff 为空。
- P2 占位一律禁用 + 「即将开放」，无假入口。
