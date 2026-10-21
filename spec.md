# Spec：「关联会话」微信直达 RPA 双模化——模式 2 补齐 + 双模反复测试

> 对应用户指令：（1）a11y 开时直接操作微信对象；（2）a11y 关时靠
> 「Shizuku + MediaProjection + 抽帧 OCR + 以图找图」执行 RPA——全部完全跑通。

## 1. 背景与目标

「关联会话」= 从联系人画像页点击微信链接，直达该联系人的微信聊天页。
当前只有模式 1（a11y 开，c05d08d 已实现并单次验证）；模式 2 在
[ImChatNavigator.kt](app/src/main/java/com/verlintas/baic2/floatball/auto/ImChatNavigator.kt#L150-L153)
直接短路返回 `REASON_A11Y_OFF`——**完全未实现**，违反 AGENTS §4.5 双模铁律。

目标：
- 模式 1 复测 ≥3 轮（不同微信初始态：聊天页/主列表/后台），确认既有实现稳定
- 模式 2 补齐实现，复测 ≥3 轮（冷投影建立 + 热复用 + 不同初始态）
- 混合回归：同一次装机下模式 2 → 开 a11y → 模式 1 切换正确

## 2. 已确认的事实（研究结论）

| 事实 | 来源 |
| --- | --- |
| 调用点：`AdvisorProfileActivity : ComponentActivity()` L783 `scope.launch { openChat(...) }` | 画像页前台时触发，topActivity 条件成立 |
| `MPManager.request(autoAllow=false, timeOut=5000): Boolean`——suspend，走 topActivity 的 launcher，appops allow 时系统授权静默通过（HyperOS 实测 c9b636c），超时返回 false | vendor 只读 |
| `MPManager.isEnable`（imageReader 存在）、`takeScreenshot2Bitmap()` 常驻投影帧 | vendor 只读 |
| `FrameSource.grabFrame(): Result<Bitmap>`——a11y 截图失败自动落投影（a11y 关时即纯投影路径） | 既有，不改 |
| `ShizukuInjector`：`tap(x,y)`/`paste()`/`clearInput()`/`foregroundPackage(): String?`（dumpsys 解析）/`available` | 既有，不改 |
| `ShizukuGate.projectionSilentGranted()/grantProjectionSilent()`——appops PROJECT_MEDIA | 既有，不改 |
| `ImageMatcher.find("search")`——多尺度找图，search.png 模板已入库 | 既有，不改 |
| ⑥-1 clearInput/paste 本来就走 Shizuku（模式 1 已混合使用） | 既有 |
| `chatPageVisibleByOcr` 名字三层匹配（精确/CJK 段/前缀 2/3）已真机验证 | 既有，仅换帧来源 |
| 坐标比例：搜索图标 (0.825, 0.074)、FTS 输入框 (0.46, 0.074)、结果条目 y=0.197h——真机实测 | 既有常量 |

## 3. 设计

### 3.1 门面分支（openChat 入口）

```
a11y 开                        → 既有模式 1 流程（零行为变更）
a11y 关 && shizuku.available   → 模式 2：openChatByVision(imPackage, name)
a11y 关 && !shizuku.available  → launchBestEffort + Fail(REASON_SHIZUKU_OFF)
                                 （两套执行面都不可用，显式报因 §4.5.2）
```

### 3.2 模式 2 前置：投影会话建立序列 `ensureProjection()`

```
1. MPManager.isEnable == true                    → true（热复用）
2. !ShizukuGate.projectionSilentGranted()        → grantProjectionSilent()（appops set）
3. MPManager.request(autoAllow=false)（画像页前台发起，appops allow 静默通过，≤5s）
4. 仍 !isEnable                                  → false → Fail(REASON_PROJECTION_UNAVAILABLE)
```

投影不主动释放：MPManager 常驻投影是既有基础设施（与 WebRTC/找图共用），
导航器不自作主张释放；后续触发直接热复用。

### 3.3 每步执行面/感知面替换表

| 步骤 | 模式 1（a11y，既有） | 模式 2（新增） |
| --- | --- | --- |
| ① 前置门 | bridge.connected | shizuku.available + ensureProjection() |
| ② 前台探测 | bridge.foregroundPackage | shizuku.foregroundPackage() |
| ②½ 剪贴板 priming | context 写入（不变） | 同左（与 a11y 无关） |
| ③ 拉起 IM + 等前台 | packageManager + 轮询 | 同左，轮询换 shizuku.foregroundPackage() |
| ④/④½/⑤½ 标题快捷/列表直达 | windowTitle/findText | **跳过**（无 a11y 树；每次走搜索全链是预期行为） |
| ⑤ 搜索图标 tap | 节点优先 + 坐标兜底（a11y tap） | **ImageMatcher.find("search") 以图找图优先** + 坐标比例兜底（shizuku.tap） |
| ⑥-0 FTS 输入框 tap | 坐标（a11y tap） | 坐标（shizuku.tap） |
| ⑥-1 clearInput + paste | Shizuku（既有） | 同左 |
| ⑦ 结果条目 tap | findText 优先 + 坐标兜底 | 坐标比例兜底 (w/2, 0.197h)（唯一通道，单跳） |
| ⑧ 聊天页验证 | a11y 截图 + OCR | **FrameSource.grabFrame()（投影抽帧）+ OCR**（三层名字匹配逻辑复用） |

模式 2 不移植的分支：back 循环/⑤½ 列表直达/⑦ 多跳循环——分别为搜索页
三态恢复、a11y 树直达、IME 候选词上屏设计，a11y 关时无从谈起或不需要
（paste 直接上屏）。转场等待用固定 delay（POST_TAP/POST_INPUT 既有值）。

屏幕尺寸来源：`context.resources.displayMetrics`（application context，
真实屏幕 1080×2460，与 a11y 无关）。

### 3.4 代码变更清单

1. **[ImChatNavigator.kt](app/src/main/java/com/verlintas/baic2/floatball/auto/ImChatNavigator.kt)**（唯一主改动，纯增量）：
   - 构造函数追加 `frameSource: FrameSource`、`shizukuGate: ShizukuGate`（@Singleton 已在 Hilt 图）
   - companion 追加：`REASON_SHIZUKU_OFF = "im_nav_shizuku_off"`、
     `REASON_PROJECTION_UNAVAILABLE = "im_nav_projection_unavailable"`
   - `openChat` 入口改造：上述 3.1 分支（既有 a11y 开流程整体不动）
   - 新增 `ensureProjection(): Boolean`（3.2 序列）
   - 新增 `openChatByVision(imPackage, name): NavResult`（③→⑤→⑥→⑦→⑧，
     每步 `Log.i(TAG, "[v2] ③ …")` 同款编号日志，失败即报因返回）
   - `chatPageVisibleByOcr(name)` → `chatPageVisibleByOcr(name, frame: Bitmap)`：
     帧由调用方给（模式 1 传 bridge 截图，模式 2 传 FrameSource 帧），
     OCR 三层匹配逻辑不动
   - `toastResult` when 映射追加两个新原因码
2. **[strings.xml](app/src/main/res/values/strings.xml)**（白名单纯增量，`float_` 前缀）：
   - `float_im_nav_shizuku_off`：双执行面均不可用，指引「开无障碍或配 Shizuku」
   - `float_im_nav_projection_fail`：投影建立失败，指引重试/查 Shizuku 授权

### 3.5 不改的东西

- 模式 1 全部既有流程（反复测试的目标之一就是确认零回归）
- FrameSource / ShizukuInjector / ShizukuGate / ImageMatcher / MPManager（只调用）
- FloatCaptureActivity / FloatCaptureBridge（老参谋一次性投影链路，与常驻投影无关）
- 上游模块任何文件

## 4. 验证计划（反复测试）

### 4.1 模式 1（a11y 开）×3 轮
1. 前置：竖屏锁定、a11y 开（进程活着时 `settings put`）、装 c05d08d APK（已装）
2. 导航链：抽屉(121,184)→设置(341,688)→老参谋(368,1925)→滚 4 次→联系人画像
   →吴瑞曦卡(508,742)→详情页微信链接(330,1600)
3. 每轮验证：
   - logcat `ImChatNavigator` ①-⑧ 日志全绿（entered=true）
   - `dumpsys window | mCurrentFocus` = `com.tencent.mm/...ChattingUI`
   - 截图（tests/tempImg/）肉眼确认聊天页顶部为联系人名
4. 轮间重置微信初始态：R2 微信 force-stop（后台冷启）、R3 微信停在主列表——
   覆盖 ③ 冷启动/④ 主列表/⑤ 搜索页三态分支

### 4.2 模式 2（a11y 关）×3 轮
1. 前置：`settings put secure enabled_accessibility_services ''` 关 a11y
   （进程活着时执行）、Shizuku 运行中、编译装机新 APK
2. 同样导航链触发（自家 App 导航不依赖 a11y）
3. 每轮验证：
   - logcat `[v2]` 日志全绿：投影建立→③拉起→⑤找图/坐标 tap→⑥-0 tap 输入框
     →⑥-1 clearInput/paste→⑦ 坐标 tap→⑧ OCR entered=true
   - mCurrentFocus = ChattingUI + 截图确认
4. 轮间变化：R1 冷投影建立（装机后首次）、R2 热复用（isEnable 直接 true）、
   R3 微信 force-stop（冷启动 + 投影热复用）
5. 反向验证：R0 先测 a11y 关 + 不动 Shizuku 的场景不可行（Shizuku 常驻），
   改为代码走查确认 REASON_SHIZUKU_OFF 分支文案正确

### 4.3 混合回归
- 模式 2 测完后开 a11y → 同导航链触发模式 1 → 全绿
- 确认门面分支随 a11y 状态正确切换（logcat `openChat … a11y=true/false`）

### 4.4 全程红线
- `adb logcat -d | grep -E "FATAL|AndroidRuntime"` 为空
- license 门禁 `tools/check-license-headers.sh` 过
- 临时截图只进 tests/tempImg/，结束清理

## 5. 风险与边界

| 风险 | 处置 |
| --- | --- |
| 个别 ROM appops allow 仍弹投影授权框 | request 5s 超时 false → REASON_PROJECTION_UNAVAILABLE 显式报因（§4.5.2 不静默），弹窗留用户处理 |
| dumpsys 前台探测每次 ~50-100ms shell | 轮询间隔 300ms 已覆盖，可接受 |
| 投影首帧黑帧/未就绪 | FrameSource 既有 acquireLatestImage；⑧ 轮询重试天然覆盖 |
| 模式 2 无标题快捷路径，已在聊天页也走全链 | 预期行为：⑧ OCR 保证落点正确；多花 ~5s 不算缺陷 |
| paste 未上屏（剪贴板异常） | ⑦ 坐标 tap 后 ⑧ OCR 验证失败 → REASON_CONTACT_NOT_FOUND 报因 |
| MPManager.request 期间授权 Activity 盖屏 | 授权完成自动返回画像页再继续 RPA；超时报因即止，不再 tap |
