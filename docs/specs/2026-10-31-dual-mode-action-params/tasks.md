# 任务清单 · 「微信 7 步测试夹具」TDD 用例方案 v3

> 前置：本清单**须用户批准 spec.md 后才可开工**。TDD 节奏：每任务先补测试（红）再实现（绿）。术语：本地测试（开发机）/ 真机测试（设备）。

## T0 用例先行（红）
- [x] `RpaWechatHelloFixtureTest`：双线自足断言（a11y 侧 / vision 侧各自完整、互不依赖，spec §〇.2）。
- [x] `RpaWechatHelloDeviceTest`：`-e runLine a11y|vision|auto` 锁线 + 无 LineFallback 断言 + `-e imPackage` 边界 B1（未安装包名 → 提示并干净退出）。
- [x] `RpaRunnerBehaviorTest` 边界用例落盘（先红）：B1 缺 App、B2/B3 前置拦截、target_timeout / step_timeout / assert_failed 提示与干净退出。

## T1 数据模型（vendor/RPA）
- [x] `RpaModels.kt`：`RpaInputChannel`、`RpaA11yLineParams`、`RpaVisionLineParams`、`RpaLineParams`，`RpaStep.lineParams`（默认值兼容旧 JSON，schemaVersion 不变）。
- [x] `RpaCatalog.kt`：`RpaValidator` 分线校验增量；`RpaReadiness` 增 `app_missing` / `screen_locked` 预检（B1/B5）。
- [x] 单测：`RpaCodecTest`（lineParams 往返 + 旧 JSON 兼容 + schemaVersion 过新拒绝导入）、`RpaValidatorTest`。

## T2 引擎按线消费（全动作标准框架，spec §一/§五）
- [x] `RpaEngine.kt`：INPUT_TEXT / LAUNCH_APP / OPEN_CHAT / SWIPE / SCROLL / ASSERT_TEXT / SET_VARIABLE 按线取 `lineParams`（缺省=现行为）；禁止跨线取值；运行中 `app_missing` 兜底。
- [x] `RpaRunnerBehaviorTest` 边界用例转绿（T0 全部）。

## T3 UI：新建弹框模式置灰
- [x] `RpaModeSelector` 支持 `disabledModes`（置灰 + 简洁原因）；CreateScriptDialog 三芯片恒显示 + 修复入口；strings.xml（zh/en）。

## T4 UI：动作编辑四类分段 + 置灰
- [x] `RpaStepDialog.kt`：StepForm 拆 common/a11yPart/visionPart；按「公共 / a11y（无障碍） / 无 a11y（shizuku）」分组渲染（C/D 类字段入组）。
- [x] 置灰 R1（模式不含→只读灰）+ R2（前置不具备→灰 +「暂不可用」+ 修复入口，就绪自动解灰）。
- [x] 点击三兄弟两侧分写修正；INPUT_TEXT / LAUNCH_APP / SWIPE / SCROLL / ASSERT_TEXT 的 D 类字段入表单。
- [x] strings.xml（zh/en）字段名与徽标文案。

## T5 夹具更新
- [x] `RpaWechatHelloFixture.kt`：7 步补 lineParams（spec §2.1），T0 断言转绿。

## T6 构建与本地测试全绿
- [x] `gradlew :app:testDebugUnitTest :vendor:RPA:test` 全绿；`gradlew :app:assembleDebug` 通过。

## T7 真机双模式分别跑通（192.168.10.2:41155）
- [ ] 前置检查：无模拟器占用；`adb connect 192.168.10.2:41155`；微信登录、亮屏解锁、悬浮窗权限。
- [ ] a11y 线：runLine=a11y 全绿（line=A11Y、无回退、屏现「你好」）。
- [ ] 无 a11y 线：runLine=vision 全绿（line=VISION、无回退、屏现「你好」）。
- [ ] 边界 B1：未安装包名 → 「应用未安装，运行结束」+ 3 秒内干净退出。

## T8 文件与云端（spec §七/§八）
- [ ] 夹具导出 `.rpa` → 重导入：双线参数 + 模板往返零丢失（本地测试 + 真机 UI 各一遍）。
- [ ] 文件契约对齐 spec §7.4/§7.5（manifest/sha256/示例文件一致性抽查）。
- [ ] 云通道：若既有云接口可用 → 上传/下载/再导入全链；不可用 → 记录并只验本地文件往返（接口对接列后续批次）。

## T9 UI 闭环抽查（截图存 tests/tempImg/）
- [ ] 新建弹框置灰变化（开关无障碍 / Shizuku）。
- [ ] 动作编辑：a11y 单模式脚本「无 a11y」组灰色（用户例）；双模式两组独立可编辑；前置暂缺组灰 + 恢复解灰。
- [ ] 运行模式门锁线运行一遍（双模式脚本分别锁 a11y / 无 a11y）。
