# 任务清单 — 好参谋 §6 生产级加固（已全部完成）

执行原则：每次 Edit 前先 Read 目标文件最新内容；上游文件零改动；新文件带 GPL-3.0
头（前 19 行）；每完成一组即编译/诊断。

## T1 — 正确性修复（P1）

- [x] T1.1 `ScreenAdvisor.runAdvisorTurn`：绑定联系人 digest 不再回退共享 digest
  （spec P1-1）。
- [x] T1.2 新增 `contact/ContactMemoryReader.kt`（只读 dueFor：AGREEMENT/PLAN、
  按人 LIKE 合并、窗内过滤、不 touch；GPL 头）；`ScreenAdvisor` 注入并替换
  `upcoming()` 调用（spec P1-2）。
- [x] T1.3 `ContactRepository.resolve`：`findByKey` 传
  `ContactNames.contactKey(imPackage, norm)`（spec P1-3）。
- [x] T1.4 `MIGRATION_2_3` 幂等补 digest 三列（PRAGMA table_info 判列，
  兼容带列/不带列两种 v2 开发库）（spec P1-4）。
- [x] T1.5 删除 `fallbackToDestructiveMigration(true)`（spec P1-5）。

## T2 — 健壮性与契约（P2）

- [x] T2.1 `ScreenAdvisor` 增加 consolidationLock（Mutex.tryLock 包住回合后
  digest+萃取块）（spec P2-1）。
- [x] T2.2 `ContactBriefSection.render`：行预算扣除段头+TURNING_RULE，整段 ≤600
  （spec P2-2）。
- [x] T2.3 `persona_schema.json`：intentStage 的 memory 改 "event"（spec P2-3）。

## T3 — 清理（P3）

- [x] T3.1 修 `ContactDirectory.kt`、`AdvisorDirectoryModule.kt` 过时 KDoc
  （EmptyContactDirectory / 「P3 起」）。
- [x] T3.2 核查结论：两处 stripScreenLabel 语义不同（精确资源前缀 vs 通用方括号
  清理），**不合并**；仅把 Gateway 的两个每行新建 Regex 提为 companion 常量。

## T4 — 垃圾文件清理

- [x] T4.1 删除 48 个一次性产物（16 个一次性脚本、13 个 ANR/trace/xml 转储、
  5 个旧库快照、48 个非引用截图中的 48 项）；保留 sync.py、dbdev/ 夹具、
  site/ 测试页、12 张被设计文档引用的证据图。

## T5 — 验证

- [x] T5.1 license 门禁通过（All source files carry the license header.）。
- [x] T5.2 `:app:assembleDebug` BUILD SUCCESSFUL（并清除改动文件内 1 个
  不必要 !! 告警）。
- [x] T5.3 GetDiagnostics：8 个改动 Kotlin 文件全部 0 诊断。
- [x] T5.4 设备冒烟（emulator-5554，已装新 APK/AOT 编译/授权）：
  王总 Day3 页长按照参谋回合成功（成交口径 40 万/培训/合同，无 FATAL）；
  桌面长按走去重短路无面板；DB 核查无「主屏幕」stub、conv1/2/3 计数与
  digest（王总 covered=9）符合预期；测试后已停 http server、移除 adb reverse、
  清理设备与本地冒烟截图、恢复设备授权。
- [x] T5.5 加固记录已追加到 `docs/advisor-context-slim-design.md` §6.8；
  最终审查报告在会话中交付。
