# AGENTS.md — 老参谋分支开发铁律

> 本手册约束 `laocanmou`（老参谋）分支上的全部开发工作。
> 上游是 [Verlintas/NovaBAIC](https://github.com/Verlintas/NovaBAIC) 的 `main` 分支；
> 本分支在其之上做**二次扩展开发**，并将**持续合并上游新版本**。
> 一切决策以此为准；与上游文档冲突时，以 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)、
> [`docs/HOW_IT_WORKS.md`](docs/HOW_IT_WORKS.md)、[`docs/adr/`](docs/adr/) 为架构事实来源，以本手册为分支策略事实来源。

---

## 0. 一句话铁律

**上游零侵入，复用优先，新代码进自己的包，合并上游时冲突面最小。**

1. **不改上游模块的文件**。`core/*`、`feature/*`、`device/*`、`tools`、`mcp`、`eval`
   模块里的任何文件，默认一个字都不动。扩展代码全部放在 `app` 模块的独立包里
   （如 `com.verlintas.baic2.floatball`）。
2. **能调用的绝不重写**。动手前先查 §3 能力索引——截图、OCR、LLM、记忆、配置、
   持久化、主题……上游全都有了。重复造轮子 = 给未来合并埋雷。
3. **上游文件的改动必须是"纯增量"**。确需触碰的（目前只有 4 个，见 §4.2）：
   只追加、不重排、不改既有行，diff 保持一眼可读。
4. **每个源文件必须带 GPL-3.0-or-later 头**。从任一现有 `.kt` 文件拷前 19 行。
   `tools/check-license-headers.sh` 是硬门禁。
5. **用户可见文案进 `app/src/main/res/values/strings.xml`**（中文，
   格式化用 `%1$s`），不硬编码在代码里。

---

## 1. 项目与分支定位

- **BetterAIChat2（代号 Nova）**：本地优先的 Android AI 智能体。无云端、无埋点、
  无遥测；API Key 走 Android Keystore 加密；模型输出只是提案，权限与危险等级
  在应用侧强制检查。
- **`laocanmou` 分支的使命**：「老参谋」——常驻悬浮球的一键屏幕参谋。
  用户在任何 App（如微信聊天）里单击悬浮球，应用悄悄完成
  截屏 → OCR → 记忆召回 → LLM 流式分析，然后弹出可拖动分界线的上下分屏浮窗
  给出参考答案。用户零等待感、主应用零打扰。
- **上游原则**：尊重上游功能与架构演进；我们只叠加，不篡改。

---

## 2. 架构速览（模块与依赖方向）

```
:app ──► :feature:* ──► :core:designsystem
  │           │
  │           └──► :core:model
  ├──► :device:impl ──► :device:api
  ├──► :tools ──► :core:engine / :device:api
  ├──► :mcp / :eval
  └──► :core:data / :core:network / :core:engine / :core:runtime
```

| 模块 | 职责 | 纯净性 |
| --- | --- | --- |
| `core:model` | 全部领域模型 + 纯算法（记忆评分/提示词渲染/token 估算） | **纯 Kotlin/JVM** |
| `core:engine` | AgentLoop（React 循环、工具编排、熔断）、AuxiliaryTasks、ConfirmationQueue、`renderSystemPrompt` | **纯 Kotlin/JVM** |
| `core:data` | 唯一数据入口：Room + DataStore + Keystore；全部 Repository | Android |
| `core:network` | ProviderFactory + OpenAI 兼容/Anthropic/Gemini 流式实现 | Android（OkHttp） |
| `core:designsystem` | Baic2Theme、颜色/形状/字体/动效 token | Android（Compose） |
| `core:runtime` | 预留（暂无源码），勿放代码 | — |
| `device:api` | 能力接口：ScreenshotProvider / OcrProvider / ScreenRecorderBridge / 语音 / 无障碍 | Android 接口 |
| `device:impl` | 上述接口的 Android 实现（MediaProjection、ML Kit、Shizuku、RunService…） | Android 实现 |
| `tools` | 内置工具（56 个）+ ToolCatalog/ToolRunner + 定时调度 | — |
| `mcp` | 远程 MCP 工具适配进统一注册表 | — |
| `eval` | 评测：mock 供应商 + 模拟设备 + 判分 | — |
| `feature:*` | 各界面（chat/conversations/tasks/settings/agents/library） | Compose UI |
| `app` | 装配：MainActivity、导航（Baic2App）、DI 桥（AgentRuntimeModule）、**本分支全部扩展** | 入口 |

规则：**UI 不得直连 DAO**（只走 Repository）；**依赖只能沿箭头方向**；
`core:model` / `core:engine` 不许 import 任何 Android 类。

---

## 3. 能力复用索引（造轮子前必查）

| 我需要… | 用这个 | 位置 |
| --- | --- | --- |
| 截屏（MediaProjection） | `ScreenshotProvider.capture()`；授权 `createPermissionIntent()` + `onPermissionResult()` | `device/api` → impl `projection/AndroidScreenshotProvider` |
| 屏幕文字识别（中/拉丁，离线） | `OcrProvider.recognizeText(bytes)` / `recognize(bytes)`（带包围盒） | `device/api` → impl `ocr/MlKitOcrProvider` |
| 录屏 | `ScreenRecorderBridge.record(durationMs)` | `device/api` |
| 带工具的完整对话循环 | `AgentLoop.run(config, mode, history, …)` | `core/engine/AgentLoop.kt` |
| 无工具小任务（标题/摘要） | `AuxiliaryTasks.complete(config, systemPrompt, userPrompt)` | `core/engine/AuxiliaryTasks.kt` |
| 裸 LLM 流式调用 | `ProviderFactory.create(providerId).stream(ChatRequest)` → `Flow<StreamEvent>` | `core/network/provider/ProviderFactory.kt` |
| 拿用户配置的模型/API Key | `AgentRepository.resolveConfig(agentId)`（null = 默认 agent；Key 自动解密） | `core/data/repository/AgentRepository.kt` |
| 记忆召回（按线索） | `MemoryRepository.recall(MemoryText.terms(text))` | `core/data/repository/MemoryRepository.kt` |
| 核心记忆（用户画像/进行中） | `MemoryRepository.getCore()` | 同上 |
| 记忆写入 | `MemoryRepository.addNote(...)`（含去重/合并/冲突裁决） | 同上 |
| 线索提取 / 归一化 | `MemoryText.terms()` / `normalize()` | `core/model/MemoryText.kt` |
| 记忆渲染进提示词 | `MemoryPrompt.context(core, primed, now)` | `core/model/MemoryPrompt.kt` |
| 系统提示词骨架（模式/策略/时间） | `renderSystemPrompt(mode, custom, planContext, memoryContext)` | `core/engine/AgentLoop.kt` |
| 会话与消息持久化 | `ConversationRepository` | `core/data/repository/` |
| 运行记录（Run） | `RunRepository` + `RunService`（前台服务） | `core/data` + `device/impl/run/` |
| 设备工具（ui_control 等） | `DeviceTool`（`screen_ocr` 等即走上面截屏/OCR 管道） | `tools/DeviceTool.kt` |
| 工具注册与执行 | `ToolCatalog` / `ToolRunner` | `tools/` |
| MCP 刷新 | `McpManager.refresh()` | `mcp/` |
| 偏好设置 | `SettingsRepository`（DataStore Flow） | `core/data/prefs/` |
| 附件导入 | `AttachmentProcessor` | `core/data/attachment/` |
| 应用前后台感知 | `AppVisibility.foreground` | `core/model/AppVisibility.kt` |
| 主题/颜色/动效 | `Baic2Theme(darkTheme, accent)` + token | `core/designsystem` |
| 依赖版本 | 版本目录（加依赖先看这里，不写死版本号） | `gradle/libs.versions.toml` |

**调 LLM 的三档选择**：要工具/多轮 → `AgentLoop`；一次性小任务 → `AuxiliaryTasks`；
自定义流式 UI（如老参谋分屏）→ `ProviderFactory` 直接拿 Flow。其余情况禁止手搓 HTTP。

---

## 4. 本分支扩展规则（老参谋）

### 4.1 现有结构（`app/src/main/java/com/verlintas/baic2/floatball/`）

| 文件 | 职责 | 铁律 |
| --- | --- | --- |
| `ScreenAdvisor.kt` | 大脑：会话管道（AgentLoop/ConversationRepository/记忆）+ LLM 串行队列 + 回合完成事件流 | **零 UI、零窗口依赖**；只编排上游能力 |
| `ScreenPerceiver.kt` | 感知阶梯：a11y 文本 → a11y 截图 → 投影，含帧质检与重试 | 只产数据（Text/Image/NeedsProjection/Failed） |
| `FrameDiffEngine.kt` | 连续参谋差分循环：运动检测→静默→掩膜 OCR→去重→入队 | 节奏读 `AdvisorPrefs`，运行中可改 |
| `FrameQuality.kt` | 灰度签名/空白帧检测/矩形掩膜/文本相似度（纯函数） | 无状态，调用方选后台线程 |
| `AdvisorHistory.kt` | 发 LLM 前的瘦身历史组装（当前屏全文+近 2 屏缩写+旧屏一句占位/滚动摘要，纯函数） | 只改发送副本，落库原文不动 |
| `FloatUi.kt` | 全部浮层视图工厂（三态球/扇形菜单/分屏面板） | **纯视觉，无业务状态**；手势由服务接线 |
| `TranscriptView.kt` | 分屏转录渲染（观察 `ScreenAdvisor.transcript`，无自有状态） | 与主界面同一会话数据源 |
| `FloatBallService.kt` | 瘦服务：状态机（Idle/OneShot/Recording）、手势路由、窗口增删、事件渲染 | 不写业务管道；投影经 Advisor 门面 |
| `AdvisorPrefs.kt` | 老参谋专属设置 + 会话 ID（SharedPreferences，floatball 私有） | 不碰上游 SettingsRepository |
| `FloatCaptureActivity.kt` + `FloatCaptureBridge.kt` | MediaProjection 授权中转（系统要求 Activity 发起） | 结果回传后立即 `clear()`，防泄漏 |
| `FloatSettingsActivity.kt` | 设置页（抓帧节奏/时长上限/权限直达/会话管理） | 只管老参谋专属项 |
| `OverlayPermissionScreen.kt` | 悬浮窗权限引导页（Compose） | 独立文件，不内嵌进 MainActivity |

新增老参谋能力时延续该分层：**编排进 Advisor/Perceiver、视觉进 FloatUi、接线进 Service**。
错误传递用 reason 字符串（`REASON_*` 常量），展示层负责映射成用户文案。

### 4.2 允许触碰的上游文件（白名单，改动保持纯增量）

| 文件 | 允许的改动 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 追加 `uses-permission`、`service`/`activity` 声明 |
| `app/src/main/java/.../MainActivity.kt` | 悬浮窗权限门 + 冷启动自动隐藏 + 2 个 extra 常量；新逻辑一律挪到 floatball 包，不在此文件堆代码 |
| `app/src/main/res/values/strings.xml` | 追加 `float_*` 前缀的键；品牌名（`app_name` 等）以本分支口径改值；可用同名键覆盖上游库资源（device:impl/feature:settings，app 资源合并优先级更高，勿改库文件） |
| `gradle/wrapper/gradle-wrapper.properties` | 镜像与超时（本机网络所需） |

不在白名单里的上游文件要改？先问三个问题：
① 能否在 `app` 模块用组合/包装/新文件解决？② 真是上游 bug？考虑报上游而非本地修；
③ 都不行——改，但提交信息必须带 `[upstream-touch]` 标记，diff 最小化，
合并上游时优先复核这些提交。

### 4.3 不要做

- ❌ 不新增 Gradle 模块（会改 `settings.gradle.kts`，制造合并冲突；扩展放 app 独立包足够）
- ❌ 不直接 new `OkHttpClient`/`Json`/`SimpleDateFormat` 等——从 Hilt 图注入
- ❌ 不在悬浮窗 Service 里跑重活——管道进 Advisor 的协程（`Dispatchers.Default`/`IO`），
  主线程只做视图更新
- ❌ 不绕过 `ScreenAdvisor` 直接调 provider/ocr（服务层保持薄）
- ❌ 不把 OCR/截屏结果落盘或发遥测——本地优先红线
- ❌ **临时截图/中间产物一律放 `tests/tempImg/`（唯一合法位置），严禁放进 `dev/`
  或其他源码目录**。包括但不限于：adb 截屏、冒烟/验收证据图、uiautomator dump
  中转的 png。`tests/tempImg/` 已在 `.gitignore`（不进版本库），任务结束后随手
  清理；需要长期保留的证据图也放该目录，文档引用统一写 `tests/tempImg/xxx.png`。
  `dev/` 只放可复用的开发脚本（如 mock server），不放任何二进制产物

### 4.4 全屏界面一致性（铁律）

「老参谋」的**所有全屏界面**（设置/画像/风格卡/策略卡/自我人设库/手册等，
叠加小窗口如分屏浮层、扇形菜单除外）必须与主框架的界面、布局、风格保持
一致，达到商业软件级的观感底线：

1. **统一骨架**：全屏页面一律复用 `app/.../floatball/AdvisorPage.kt` 的
   `AdvisorPage`——第一行永远是与状态栏相接的统一顶栏：**返回箭头
   （`Icons.Outlined.ArrowBack`）+ 页面标题**（titleLarge 加粗，超长省略）。
   顶栏避让公式必须与主界面左上角导航下拉框（Baic2App 的 `DockAnchor`）
   同构：`windowInsetsPadding(WindowInsets.statusBars) + padding(Baic2Spacing.md)`，
   顶栏内容紧贴系统状态栏正下方——不要用 M3 `TopAppBar`（64dp 高度会让
   标题悬在状态栏下方过远，观感是「两张皮」）。
   insets 由 Scaffold 统一下发（内容收 `PaddingValues` 自行消费），禁止各页
   自绘标题行、禁止内容画进刘海/状态栏、禁止底部被导航条遮挡。
2. **视觉对齐主框架内页**（参考 `feature/library` 的 `LibraryPageScaffold`）；
   主题一律走 `Baic2Theme` 与 token，不硬编码颜色。
3. 新增全屏页面从第一行代码就用该骨架，不留「回头统一」的债。

### 4.5 无障碍双模设计铁律

设计任何涉及屏幕感知或自动化操作的功能时，**必须先回答**：
「无障碍（a11y）开关关闭时，这条路径还能走通吗？」

1. **双模自检**：感知面优先考虑 a11y 关闭时的降级路径
   （录屏抽帧 + OCR + 找图）；执行面优先考虑 a11y 之外的注入通道
   （Shizuku shell `input tap`/剪贴板粘贴）；能降级的必须设计降级，
   不许把 a11y 当作想当然的既存条件。
2. **硬前置必须显式提示**：若某能力在物理上只有 a11y 一条通道
   （如节点动作、`dispatchGesture` 手势、`ACTION_SET_TEXT`），
   必须在方案文档里显式标注「a11y 硬前置」，并在实现层**及时、主动地
   提示用户**（前置就绪检查不满足即禁用开关并展示缺项与操作指引；
   运行中 a11y 断连即降级+通知）——**严禁静默失效**（功能点了没反应、
   或悄悄走空）。
3. **RPA 执行面双模架构（2026-10-21 定版，此后所有 RPA 功能一律照此自检）**：
   a11y 关闭不是「功能受限」而是**完整的第二套执行面**，两种模式都必须可交付：
   - **a11y 开**：无障碍节点读写 / 手势通道（毫秒级、零弹框）——首选。
   - **a11y 关**：`Shizuku`（shell 注入 `input tap`/`appops`）+ **静默 MediaProjection**
     （`appops set <pkg> PROJECT_MEDIA allow` 后授权框静默通过，HyperOS 真机实测，
     见 `docs/2026-10-21-投影免弹窗授权方案.md`）+ OCR + 以图找图。
   感知（抓帧/OCR/找图）与执行（注入/粘贴）都要能独立走通，且失败必须报因（§4.5.2）。
4. 参考架构：三模执行面与就绪校验门
   见 `docs/2026-10-19-自动聊天托管执行面方案.md` §17.2/§20。

---

## 5. 代码规范

- **License 头**：每个 `.kt`/`.kts`/`.py` 文件头部（模板见任意现有文件）；
  `tools/check-license-headers.sh` 校验。
- **DI**：Hilt + KSP。构造注入 `@Inject constructor(...)`；接口绑定写
  `@Module @InstallIn(SingletonComponent::class)`，放在实现方模块。
  Service 用 `@AndroidEntryPoint` + 字段注入。
- **Kotlin**：官方风格（`kotlin.code.style=official`），4 空格缩进，
  遵循所在文件既有写法；公共 API 写 KDoc（说明约束而非复述实现）。
- **UI**：Compose 走 `Baic2Theme` 与 token（不硬编码颜色）；
  悬浮窗等系统窗口场景允许纯 View（集中在 `FloatUi`）。
- **协程**：`StateFlow` 驱动状态（React 不轮询）；冷流用 `flow {}`；
  取消要能传播（`askJob?.cancel()` 必须真正中断 OkHttp 流）。
- **错误**：Result/密封事件携带原因字符串，UI 层映射文案；不许吞异常。
- **资源**：字符串进 `strings.xml`；可格式化参数用位置标记（`%1$s`、`%1$d`）。

---

## 6. 构建与验证（本机环境）

| 项 | 值 |
| --- | --- |
| JDK | 17（`D:\programfiles\Java\jdk-17`） |
| Android SDK | `D:/programfiles/Android/SDK`（platform 35/36，NDK 23.1/26.1） |
| Gradle | wrapper 9.6.1（发行包走腾讯镜像，见 wrapper properties） |
| 模拟器 | `Galaxy_Intel_API_36`（720×1280 @320dpi） |
| 应用 | `com.verlintas.baic2`，debug APK 在 `app/build/outputs/apk/debug/` |

```bash
./gradlew :app:assembleDebug          # 编译
tools/check-license-headers.sh        # license 门禁（bash）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 悬浮窗权限（首次）
adb shell appops set com.verlintas.baic2 SYSTEM_ALERT_WINDOW allow

# 冒烟链路（老参谋）
adb shell am force-stop com.verlintas.baic2
adb shell monkey -p com.verlintas.baic2 -c android.intent.category.LAUNCHER 1
#   → 冷启动只出悬浮球；单击=屏幕问答（每次弹系统投影授权，拍完即关投影）
#   → 分屏面板出现【屏幕内容】+流式回答；拖把手调高度；长按=子菜单
adb logcat -d | grep -E "FATAL|AndroidRuntime"   # 必须为空
```

提交前最低门槛：**license 检查过 + 编译过 + 冒烟链路过 + logcat 无崩溃**。
涉及 `core:model`/`core:engine` 的（本分支不应有）还须过单测：
`./gradlew :core:model:test :core:engine:test`。

### 6.1 Android 版本适配事实（用户侧口径见 README「设备与系统要求」）

SDK：`minSdk 26 / targetSdk 36 / compileSdk 37`。写保活/悬浮窗相关代码时必须按行判断，
不允许假设「大家都在最新版」：

| 版本 | 关键约束 | 本分支对策 |
| --- | --- | --- |
| 8.0–11（26–30） | 前台服务/后台启动最宽松 | 直启 FGS |
| 12（31+） | 后台启动受限；精确闹钟是 `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` 运行时能力 | `KeepAliveUtils` 检查 canScheduleExactAlarms 并引导设置 |
| 13（33+） | 通知运行时权限 | 设置页内联 `POST_NOTIFICATIONS` 请求 |
| 14（34+） | FGS 类型强制声明与按类型启动；MediaProjection 一授权一投影 | Manifest 声明 `foregroundServiceType`；截屏拍完即关（§8 教训 2） |
| 15+（35+） | 仅有 SAW 不再允许后台启 FGS，需「此刻存在可见 overlay」 | `KeepAliveManager.reviveServiceIfNeeded()` 三级降级：电池白名单/闹钟豁免直启 → 先挂占位 overlay 再启 → 放弃 |

其余红线：BOOT 黑名单含 `mediaPlayback`，`KeepAliveMusicService` 绝不从开机广播启动；
电池优化白名单与精确闹钟是两个独立豁免，不可互相假设；force-stop 后任何保活都失效，
UI 文案不得承诺「永久存活」。国产 ROM 自启跳转表在 `KeepAliveUtils.AUTO_START_TABLE`。

---

## 7. Git 与上游合并协议

- **分支**：开发永远在 `laocanmou`；不直接动本地 `main`（它是上游的镜像）。
- **提交信息**：Conventional Commits（`feat:`/`fix:`/`docs:`/`refactor:`…），
  中文描述，与上游历史风格一致；触碰白名单外上游文件加 `[upstream-touch]`。
- **合并节奏**：上游发版后尽快合并，攒得越多越难合。

```bash
git fetch origin main            # 或上游 remote
git checkout laocanmou
git merge origin/main
# 预期冲突点（都是追加式，好解）：
#   app/src/main/AndroidManifest.xml —— 各自的声明块共存
#   app/src/main/res/values/strings.xml —— 键级冲突，两边都保留
#   MainActivity.kt —— 只会撞我们的小块 gate 逻辑
#   gradle-wrapper.properties —— 保留镜像行
git mergetool / 手工解决
./gradlew :app:assembleDebug && 冒烟链路   # 合并后必跑
```

- 合并后如上游改了 `docs/ARCHITECTURE.md`/ADR/版本目录，**同步更新本手册 §2/§3**。
- 上游有 DB 迁移（CHANGELOG 会标 `DB vNN`）时，重点回归会话/记忆功能。

### 7.1 版本号铁律（tag 全驱动，2026-10-17 修订）

**tag 是版本号唯一事实来源：release CI 从 tag 派生 `versionName`/`versionCode`
注入构建，仓库 `app/build.gradle.kts` 里的值只是本地开发占位，发版不再需要
"先升版本号"提交，也就不存在"忘升版本号被 CI 拒"这回事。**

1. **发版 = 一步**：在 laocanmou 最新提交上打 tag 并推送（tag 必须指向 laocanmou
   tip，否则 CI 拒绝）：
   ```bash
   git push origin laocanmou && git tag v0.3.2 && git push origin v0.3.2
   ```
2. **派生公式**（CI 内执行；本地发版构建可用 `-Pbaic.versionName/-Pbaic.versionCode`
   覆盖）：`versionName` = tag 去 `v` 前缀（预发布 tag 保留后缀，如 `0.3.2-rc1`）；
   `versionCode` = 主版本×10000 + 次版本×100 + 补丁号（v0.3.2 → 302）。
   次版本/补丁号 ≤ 99 由 CI 强制（超出会破坏公式单调性）。
3. **合并上游后**：不再需要手工对齐版本号——派生值从 302 起步，恒大于上游手动
   维护的 versionCode（上游 0.3.0 才 24），安装层覆盖升级天然只增不减。唯一要留意
   的冲突裁决口径：`build.gradle.kts` 版本行若撞上游改动，保留我们的
   `providers.gradleProperty` 写法即可。
4. **打错的 tag**：删远端重打即可触发重跑：
   `git push origin :refs/tags/vX.Y.Z`，然后在 laocanmou tip 上重新
   `git push origin vX.Y.Z`。
5. 发版前看一眼最新 tag 仍是好习惯：`git describe --tags --abbrev=0`。

---

## 8. 历史教训（已踩过的坑，别再踩）

1. **截屏前必须清空自身浮层**——否则 OCR 把自己的面板文字识别进去
   （老参谋面板只在 `Captured` 事件后才显示）。
2. **MediaProjection 授权必须由 Activity 发起**——Service 里发起直接失败，
   所以有 FloatCaptureActivity 中转；Android 14+ 一授权一投影。老参谋采用
   **拍完即关**策略（`ScreenAdvisor` 截屏成功后立即 `release()`）：状态栏
   指示灯即刻熄灭、零驻留功耗，代价是每次单击都要重新授权。
3. **主线程不做位图解码**——`BitmapFactory.decodeByteArray` 看似无害，
   720p+ 截图足以掉帧；一律 `Dispatchers.Default`。
4. **测试脚本要等窗口真正就绪**——模拟器冷启动慢，ADB 脸盲坐标点击前先轮询
   `dumpsys window` 确认悬浮球窗口存在。
5. **KSP 增量缓存会在大幅重写后损坏**（Windows 尤甚）——报
   `*_GeneratedInjector.java 找不到`时，删 `app/build/generated/ksp` 重建即可。
6. **模拟器 a11y 授权的三个坑**（老参谋测试必读）：`install -r` 与 `force-stop`
   都会把 `enabled_accessibility_services` 清空；且 App 进程不存在时
   `settings put` 也会被清。正确顺序 = **先启动 App，进程活着时 put**。
   `build-client.ps1 grant/smoke` 已内置此逻辑，勿手搓。
7. **adb 注入模拟不了真双击**（每次 input 命令抖动 ≥300ms 系统双击窗口）——
   录制模式的自动化测试走 `.\build-client.ps1 record`（service action，
   与双击同一状态机入口），`record-stop` 停止。
8. **PowerShell 的 `>` 重定向会写坏二进制**（adb exec-out screencap | png
   被 CRLF 破坏）——截图一律 `screencap /sdcard + adb pull`（smoke 命令已内置）。
9. **临时截图只能进 `tests/tempImg/`**（见 §4.3 禁令）。`build-client.ps1` 的
   截图函数就落在这里并被 `.gitignore` 忽略；手动 adb pull、证据图、像素分析
   中转 png 一律同址。曾因随手把一批 evidence-*.png 放进 `dev/` 污染开发脚本
   目录，已全部迁移——别再犯。
10. **Windows 构建诡异失败的终极排查顺序**（2026-10-21 实战教训）：构建中途
   文件锁（如 `dirty-sources.txt` 被占用）之后，可能出现「compileDebugKotlin
   报成功/UP-TO-DATE/FROM-CACHE 但产物目录为空，javac 找不到连
   `Baic2Application` 在内的全部自有类」的假象。层层清缓存都无效时，根因是
   **全局构建缓存**（本机 `GRADLE_USER_HOME=D:\programfiles\Android\gradlepro`，
   非默认 `~/.gradle`）缓存了残缺产物。修复顺序：`gradlew --stop` → 删项目
   `.gradle` 与各模块 `build` → **删 `D:\programfiles\Android\gradlepro\caches\build-cache-1`**
   → 冷重建。只清项目级缓存解决不了 FROM-CACHE 毒条目。
