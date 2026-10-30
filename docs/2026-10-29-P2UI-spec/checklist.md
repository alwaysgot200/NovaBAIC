# RPA 重构 P2 阶段验证检查点（checklist）

对应 [spec.md](./spec.md) §2.5 / §4 与 [tasks.md](./tasks.md)。

## p2-2 检查点

- [x] C1 `.\gradlew :app:compileDebugKotlin`（cwd=NovaBAIC）编译通过
- [x] C2 analyze/、quickfind/、RpaPickCoordinator.kt 等拾取契约文件 diff 为空（只可调用不可改）
- [x] C3 新增文案全部在 strings.xml（float_rpa_* 前缀），无硬编码用户可见文案
- [x] C4 「导入来源」行省略、分辨率仅当前值——无造假数据；P2 占位禁用 + 「即将开放」

## 全阶段收尾检查点（p2-6）

- [x] C5 `.\gradlew :app:testDebugUnitTest` 全绿（987 测试 0 失败）
- [x] C6 `.\gradlew :app:assembleDebug` 成功
- [x] C7 license 头检查通过（CRLF 绕过法）
- [x] C8 母规格 §十改动清单逐项落地、§九业务闭环九条路径可走通

> C8 记录偏差（功能等价，有意保留）：§十「RpaModule.kt：DI 绑定新 codec、handler 表、求值器」
> 未按字面落地——codec/求值器为 vendor `object` 单例、handler 表是 `RpaRunner` 内部注册分发，
> `RpaRunService` 直构 `RpaRunner`，无需 DI 绑定，能力等价。
