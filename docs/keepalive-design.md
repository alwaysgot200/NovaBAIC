# NovaBAIC「老参谋」保活方案设计（v2）

> 学习吸收 SmsForwarder（E:\_android_work\SmsForwarder）的保活措施，
> 在 `laocanmou` 分支上以「上游零侵入」方式复刻。
> v2 变更：新增**保活强度三档模型**（永久保活 ↔ 耗电量低由用户自选）、
> **设置页完整 UI 规格**（保活体检卡 + 模式卡片 + 授权引导 + 重试滑条）、
> 若干健壮性修正（FGS 启动异常兜底、通知重挂、心跳复活无声音乐）。
> 实现全部位于 `app` 模块 `com.verlintas.baic2.keepalive` 包 + 白名单纯增量追加。

---

## 0. 结论（TL;DR）

1. **不引入 Cactus 依赖**。Cactus 1.1.3-beta13 是 2021 年产物，内部前台服务
   未声明 `foregroundServiceType`，在 targetSdk 34+ 上 `startForeground` 会直接抛
   `MissingForegroundServiceTypeException`；1 像素 Activity 依赖「后台启动
   Activity」这一动作本身——与窗口几个像素无关：Android 10/11 仅剩悬浮窗权限
   豁免可续命，Android 12+（API 31）连豁免也移除，彻底失效。
2. **保活强度交给用户决策**：三档预设「低耗电 / 智能保活（推荐，默认）/
   永久保活」+ 细项自定义，每档明标电池代价（⚡），选「永久保活」需二次确认。
   这是对 SmsForwarder「一堆平铺开关」的 UX 升级：开关还在，但用户先选档位。
3. **零 [upstream-touch]**：不动 `Baic2Application`、`MainActivity`、build 脚本、
   版本目录。触碰范围仅白名单纯追加（Manifest、strings）+ 本分支自有文件小改
   （FloatBallService、ScreenAdvisor、AdvisorTranscript；**FloatUi 零改动**——
   扇形菜单已预置 6 个方位，现有 5 项只用了 5 个）。
4. 老参谋现有 `FloatBallService` 已是 `specialUse` 前台服务 + `START_STICKY`，
   「常驻通知」这一层已经存在；方案补的是**拉活**（开机/更新/心跳/闹钟/崩溃）、
   **授权引导**（通知/电池优化/精确闹钟/厂商自启）、**隐身**（最近任务隐藏）、
   **请求重试**（LLM 流式调用）四层，并全部纳入三档强度之下。

---

## 1. SmsForwarder 保活手段盘点 → 搬运判定

| # | 机制 | SmsForwarder 实现（源码位置） | 判定 | NovaBAIC 对应 |
|---|------|------------------------------|------|--------------|
| 1 | 开机自启 | `BootCompletedReceiver`：BOOT/LOCKED_BOOT/USER_PRESENT/PACKAGE_RESTARTED，`priority=MAX`、directBootAware；收到后拉起 SplashActivity 间接拉服务；开关 = `PackageManager.setComponentEnabledSetting` | ✅ 可搬（简化） | `KeepAliveBootReceiver` 直接 `startForegroundService(FloatBallService)`；跳过 directBootAware/LOCKED_BOOT |
| 2 | 常驻前台服务 | `ForegroundService`：START_STICKY、IMPORTANCE_HIGH 通知、`App.onCreate` 即拉起 | ✅ 已有 | `FloatBallService` 已是 specialUse FGS + START_STICKY；补 `isRunning` 旗标与退出语义 |
| 3 | Cactus 增强保活 | `com.gyf.cactus:cactus:1.1.3-beta13`：双进程前台服务/JobScheduler/WorkManager/1像素/无声音乐，开关 `enable_cactus` | ❌ 依赖不可搬，✅ 思路可搬 | 框架自实现：JobScheduler 心跳 + AlarmManager 兜底 + 可选无声音乐 FGS；1 像素放弃 |
| 4 | 忽略电池优化 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `PowerManager` 检查 + 系统对话框（`KeepAliveUtils.kt`） | ✅ 原样搬 | `keepalive/KeepAliveUtils.kt` |
| 5 | 厂商自启跳转 | `SettingsFragment.startToAutoStartSetting`：`HashMap<MANUFACTURER, 组件列表>`，失败兜底应用详情页 | ✅ 原样搬 | `KeepAliveUtils.autoStartIntent()` + 各品牌提示文案 |
| 6 | 最近任务隐藏 | `appTasks[0].setExcludeFromRecents(true)`（MainActivity 启动时 + 开关切换时） | ✅ 可搬 | `KeepAliveManager.applyRecentsExclusion()`，由服务/设置页驱动，**不碰 MainActivity** |
| 7 | 请求重试机制 | `request_retry_times(0=禁用)/request_delay_time/request_timeout`；XHttp2 线性递增重试 | ✅ 语义可搬，实现重写 | `ScreenAdvisor` 流式管道外层重试封装；「单次超时」语义改为**首 token 超时** |
| 8 | 通知引导 | 「不禁用通知栏，避免APP被杀」提示；POST_NOTIFICATIONS 运行时请求 | ✅ 可搬 | 设置页横幅 + 体检卡 |
| 9 | 通知栏常驻 | `IMPORTANCE_HIGH` + 灯光 + 震动；内容可热更新 | ✅ 可搬（降档） | 保持 `IMPORTANCE_LOW`（本地优先、不打扰） |
| 10 | 崩溃自恢复 | Cactus `setCrashRestartUIEnabled` + 存活统计 | ✅ 思路可搬 | 崩溃时间戳落盘（诊断），靠 START_STICKY + 心跳拉活 |
| 11 | directBootAware / internalOnly | application 级属性 | ❌ 不搬 | minSdk 26 无必要；改动超出白名单字母范围 |

---

## 2. 为什么不引入 Cactus

- **API 34+ 不兼容**：Cactus 的隐藏进程前台服务、通知重建都走无类型的
  `startForeground()`；targetSdk 34 起未声明 `foregroundServiceType` 的 FGS
  启动直接抛异常（本项目 target 36）。
- **1 像素已死**：它是「锁屏瞬间后台启动透明 Activity 冒充前台提权」的旧黑科技，
  与像素数无关——≤Android 9 随便启动，10/11 靠悬浮窗豁免续命，12+ 一律拦截。
  其目的（锁屏期间前台级优先级）已被常驻前台服务合法达成。
- **双进程互拉失效**：Android 12+ 对关联进程同步查杀。
- **无声音乐可自实现**：`mediaPlayback` 类型迷你 FGS + `res/raw/silence.mp3`
  循环播放即可，作为「永久保活」档的可选组件。
- **零依赖红利**：心跳全用框架 JobScheduler/AlarmManager，Gradle 一字不改，
  上游合并冲突面为零。

---

## 3. 保活强度模型（核心新增）

用户在「永久保活」与「耗电量低」之间**自己选档**，细项仍可覆盖：

| | 🌙 低耗电 | ⚖️ 智能保活（推荐·默认） | 🛡️ 永久保活 |
|---|---|---|---|
| 定位 | 最省电：悬浮球随用随在，被杀不自动复活 | 每 15 分钟自动检查拉活，耗电几乎无感 | 最强存活：双保险心跳 + 可选无声音乐 |
| 电池标注 | ⚡ 低 | ⚡⚡ 适中 | ⚡⚡⚡ 明显增加 |
| 常驻前台服务 + 通知 | ✅ | ✅ | ✅ |
| 开机自启 | ✅（零持续耗电） | ✅ | ✅ |
| JobScheduler 心跳（15 min，persisted） | — | ✅ | ✅ |
| 精确闹钟兜底 | — | — | ✅（需精确闹钟权限） |
| 崩溃自恢复 + onTaskRemoved 加固 | — | — | ✅ |
| 无声音乐 | — | — | 子开关（默认关，⚡⚡⚡） |
| 最近任务隐藏 | 用户可选（与档位无关） | 同左 | 同左 |
| 授权引导 | 常显 | 常显 | 常显 |

规则：
- **默认档 = 智能保活**（悬浮球是产品核心，杀不活功能就废了；而心跳每刻钟
  毫秒级唤醒，真实耗电可忽略——「推荐」名副其实）。
- 选「永久保活」弹**二次确认**：明写「会明显增加耗电，主要面向深度清理激进的
  国产 ROM」。
- 用户手动改任何细项 → 档位显示「自定义」，模式卡片高亮离开预设。
- `KeepAliveManager.applyMode()` 统一应用/撤销：排程或取消 job、设置或撤销
  闹钟链、启停无声音乐服务。

各细项的电池代价标注（UI 逐项展示）：

| 措施 | 代价 | 说明 |
|---|---|---|
| 常驻前台服务 | ⚡ 极低 | 本来就是产品形态 |
| 开机自启 | ⚡ 无 | 一次性广播，无持续耗电 |
| JobScheduler 心跳 | ⚡ 极低 | 每小时 4 次毫秒级唤醒 |
| 精确闹钟兜底 | ⚡⚡ 低 | 闹钟链 + Doze 下限流（9 分钟/次） |
| 无声音乐 | ⚡⚡⚡ 明显 | 音频通路常开 + 进程完全唤醒 |
| 最近任务隐藏 | ⚡ 无 | 纯窗口标志 |
| 电池优化豁免 | 授权类 | 不直接耗电，解除系统后台限制 |

---

## 4. 总体架构与文件清单

```
┌─ 决策层：KeepAliveSettingsActivity（三档强度 + 细项 + 体检卡）─┐
│                                                               │
├─ 授权引导层 ─────────────────────────────────────────────────┤
│ 通知权限 / 悬浮窗 / 电池优化 / 精确闹钟 / 厂商自启跳转             │
├─ 常驻层（已有，小加固）───────────────────────────────────────┤
│ FloatBallService：FGS + START_STICKY + isRunning + 退出旗标    │
├─ 拉活层（新增 keepalive 包）──────────────────────────────────┤
│ BootReceiver(BOOT/UPGRADE/QUICKBOOT) → HeartbeatJob(JobScheduler)
│   ← 兜底 ← AlarmReceiver(精确闹钟链) ；MusicService(可选)       │
├─ 隐身与重试层 ────────────────────────────────────────────────┤
│ applyRecentsExclusion ；ScreenAdvisor 流式重试(首token超时)     │
└──────────────────────────────────────────────────────────────┘
```

### 新增文件（全部 GPL-3.0 头 + Hilt）

| 文件（`app/src/main/java/com/verlintas/baic2/keepalive/`） | 职责 |
|---|---|
| `KeepAlivePrefs.kt` | SharedPreferences（`floatball_keepalive`）封装；开关、强度档位、重试参数的唯一存储 |
| `KeepAliveManager.kt` | 门面单例：`applyMode()` 应用/撤销全部机制；`onServiceCreated/Destroyed/onUserExit`；`applyRecentsExclusion`；心跳排程；崩溃时间戳 |
| `KeepAliveBootReceiver.kt` | BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT_POWERON → 检查档位 + 悬浮窗权限 → 拉服务；FGS 启动异常时兜底排程心跳 |
| `KeepAliveHeartbeatJob.kt` | JobService：检查「期望运行 + 未运行 + 有悬浮窗权限」→ 拉服务；永久档顺带复活无声音乐 |
| `KeepAliveAlarmReceiver.kt` | 精确闹钟兜底：重排 job、拉活服务、设下一个闹钟 |
| `KeepAliveMusicService.kt` | 可选无声音乐：`mediaPlayback` FGS 循环 `res/raw/silence.mp3` |
| `KeepAliveUtils.kt` | 电池优化检查/申请、精确闹钟检查/跳转、厂商自启跳转表、**保活体检（health check）计算** |
| `KeepAliveSettingsActivity.kt` | Compose 设置页（规格见 §7） |
| `app/src/main/res/raw/silence.mp3` | 从 SmsForwarder 拷贝的无声音频资产（纯新增文件） |

### 既有文件改动

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` ⚠️白名单 | 追加 2 权限 + receiver/service/activity 声明（见 §5） |
| `res/values/strings.xml` ⚠️白名单 | 追加 `float_keepalive_*` 键 + `float_ask_retrying` |
| `floatball/FloatBallService.kt` | onCreate 调 `KeepAliveManager.onServiceCreated`（内含体检里的 recents/心跳一致性）；子菜单加第 6 项「保活」；`exitApp` 置退出旗标；`onTaskRemoved` 加固；`onStartCommand` 支持「重挂通知」动作（通知权限刚授予后刷新可见性） |
| `floatball/ScreenAdvisor.kt` | 新增 `Status.Retrying(attempt, total)`；`runAdvisorTurn` 套重试封装（两阶段收集，见 §6.6） |
| `floatball/AdvisorTranscript.kt` | `StatusLine` 加 `Retrying` 分支（渲染「第 n/N 次重试…」） |
| `floatball/FloatUi.kt` | **零改动**（扇形菜单已有 6 方位，第 6 子球直接放入） |

**不改**：`Baic2Application`、`MainActivity`、`FloatUi`、`app/build.gradle.kts`、
`gradle/libs.versions.toml`、任何上游模块。

---

## 5. Manifest 追加清单（纯增量）

```xml
<!-- 权限（SCHEDULE_EXACT_ALARM 上游已声明，无需新增） -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

<!-- 开机/更新自启（受保护广播，exported 必须 true） -->
<receiver
    android:name=".keepalive.KeepAliveBootReceiver"
    android:enabled="true"
    android:exported="true"
    tools:ignore="IntentFilterExportedReceiver">
    <intent-filter android:priority="999">
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>

<!-- 心跳兜底闹钟 -->
<receiver
    android:name=".keepalive.KeepAliveAlarmReceiver"
    android:enabled="true"
    android:exported="false" />

<!-- 心跳 JobService（周期下限 15 分钟，系统重排） -->
<service
    android:name=".keepalive.KeepAliveHeartbeatJob"
    android:exported="false"
    android:permission="android.permission.BIND_JOB_SERVICE" />

<!-- 可选无声音乐：仅用户前台开启（mediaPlayback 在 Android 15 的
     BOOT_COMPLETED 受限类型清单内，绝不从开机路径启动） -->
<service
    android:name=".keepalive.KeepAliveMusicService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />

<!-- 保活设置页 -->
<activity
    android:name=".keepalive.KeepAliveSettingsActivity"
    android:exported="false"
    android:label="@string/float_keepalive_title"
    android:theme="@style/Theme.BAIC2" />
```

不采纳 `installLocation="internalOnly"`、`directBootAware`（见 §1 #11）。

---

## 6. 逐项设计

### 6.1 开机启动

- receiver 收到 BOOT / MY_PACKAGE_REPLACED / QUICKBOOT_POWERON：
  `档位存在（恒存在）&& serviceDesired && canDrawOverlays` →
  `startForegroundService(FloatBallService)` → onCreate → 通知 + 悬浮球回归。
- **开关双轨**（对齐 SmsForwarder）：`PackageManager.setComponentEnabledSetting`
  禁用/启用 receiver 本体（关闭后系统根本不派发）+ prefs 镜像值供 UI。
- **健壮性（v2 新增）**：`startForegroundService` 包 runCatching——若系统在
  某些 ROM/版本上拒绝后台启 FGS（`ForegroundServiceStartNotAllowedException`），
  兜底为「立即排程心跳 job + 闹钟」，让拉活延后到允许的时刻，而不是静默失败。
- 打开开关时展示品牌自启提示并跳厂商自启页（同 SmsForwarder `checkWithReboot`）。
- Android 15 BOOT 受限 FGS 类型清单不含 `specialUse`，该路径合法。

### 6.2 悬浮按钮保活 / 服务常驻

- `FloatBallService.isRunning` 静态旗标（onCreate/onDestroy 维护）。
- **退出旗标** `serviceDesired`：`exitApp()` 置 false（心跳不许复活明确退出）；
  `onServiceCreated`、开机接收器、主界面启动置 true。显式 `stopSelf` 后
  START_STICKY 本就不重启，心跳双保险。
- **永久档专属**：`onTaskRemoved`（划掉主任务时保持服务与浮层一致）、
  `installCrashGuard()`（崩溃时间戳落盘，诊断用；进程恢复靠 sticky + 心跳）。

### 6.3 忽略电池优化

- `PowerManager.isIgnoringBatteryOptimizations` 实时查询，UI 不落盘。
- 开 → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI；
  关 → `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 列表页。
- 厂商自启跳转表搬 `SettingsFragment.startToAutoStartSetting`：
  `HashMap<MANUFACTURER.lowercase(), List<组件>>`，含 `/` 用
  `ComponentName.unflattenFromString`，否则 `getLaunchIntentForPackage`；
  全失败 → toast + 打开应用详情页。
- 合规注：Play 对该权限有限制；本应用 GitHub/APK 直装不受影响。

### 6.4 增强保活（心跳 / 闹钟 / 无声音乐）

1. **JobScheduler 心跳**（智能档起生效）：
   `JobInfo.Builder(JOB_ID, KeepAliveHeartbeatJob).setPeriodic(15min).setPersisted(true)`。
   执行体：`serviceDesired && canDrawOverlays && !isRunning` → 拉服务；
   永久档且无声音乐开 → 顺带检查/复活 `KeepAliveMusicService`。
   Android 12+ 后台启 FGS 的豁免条件「已授予 SYSTEM_ALERT_WINDOW」恰是
   老参谋必要权限，该路径 12–15 全可用。
2. **AlarmManager 兜底**（永久档专属）：`setExactAndAllowWhileIdle(+15min)` 链；
   触发时重排 job + 拉活 + 设下一个闹钟。API 31+ 先查
   `canScheduleExactAlarms()`，false 降级 `setWindow`，并在体检卡提示授权
   （跳 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`）。Doze 下 exact 限流
   9 分钟/次，15 分钟周期不受影响。
3. **无声音乐**（永久档子开关，默认关）：`mediaPlayback` FGS + MediaPlayer
   循环，从设置页前台启动/停止；心跳负责复活。打开时明示 ⚡⚡⚡ 耗电。

### 6.5 在最近任务列表中隐藏

- `activityManager.appTasks.forEach { it.setExcludeFromRecents(enabled) }`
  （runCatching 包裹）。应用时机：设置页切换时；`FloatBallService.onCreate`；
  `showMainUi()` 的 Intent 按需加 `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`。
- 提示语点明副作用：主界面从最近任务消失后，回入口 = 悬浮球菜单「主界面」。

### 6.6 请求重试机制

参数（默认值参考 SmsForwarder 截图与流式场景修正）：

| 键 | 含义 | 范围 | 默认 |
|---|---|---|---|
| `retry_times` | 最多重试次数，**0 = 禁用** | 0–10 | 3 |
| `retry_delay_sec` | 递增间隔基数（秒），第 n 次重试前等 `delay + n*delay` | 1–60 | 5 |
| `retry_timeout_sec` | **首 token 超时**（秒） | 5–120 | 15 |

- **作用域**：`ScreenAdvisor.runAdvisorTurn`（本分支唯一 LLM 调用点）。
  主界面聊天重试属上游职责，不碰。
- **两阶段收集**（v2 明确实现形态）——Flow 只能收集一次，用 channel 化解决
  「首 token 限时 + 后续不限时」：
  ```kotlin
  coroutineScope {
      val events = agentLoop.run(...).produceIn(this)
      val first = withTimeoutOrNull(timeoutMs) { events.receive() }
          ?: throw RetryableException("first_token_timeout")
      handle(first)                       // 首个事件（可能是 TextDelta/AssistantMessage）
      for (event in events) handle(event) // 之后不再设总超时
  }
  ```
- **重试判定**：零输出失败（首 token 超时 / 连接失败 / `AgentEvent.Failed`）
  → 按递增间隔重试（5、10、15…）；**已有部分流式输出后失败 → 不重试**
  （防内容重复），保存部分文本并报错。次数=0 走现有单次路径，零开销。
- 新增 `Status.Retrying(attempt, total)`，`AdvisorTranscript` 渲染
  「第 n/N 次重试…」。

### 6.7 通知保底与「不禁用通知栏」

- 通道保持 `IMPORTANCE_LOW` + ongoing（保活锚点但不骚扰；SmsForwarder 的
  HIGH+震动是转发器场景）。
- **通知权限缺口（13+ 现状第一个真实问题）**：`POST_NOTIFICATIONS` 从未被
  运行时申请过 → 通知不可见、用户易误判应用已死。设置页体检卡 + 横幅内联申请
  （ActivityResultContracts），授予后发 `ACTION_REPOST_NOTIFICATION` 给服务
  重挂前台通知。
- 引导文案：设置页副标题 + 底部说明「请勿在系统设置中禁用应用通知」。

---

## 7. 设置页 UI 规格（KeepAliveSettingsActivity）

Compose + Material3 + `Baic2Theme`（颜色全部走 colorScheme token，不硬编码）。
入口：悬浮球长按子菜单第 6 子球「保活」（FloatUi 零改动，6 方位正好占满）。

### 7.1 页面结构（自上而下）

```
┌ TopAppBar：保活措施                      ⚡ 当前档位徽标
│ 副标题：悬浮球常驻的关键——完成授权引导，并在省电与存活之间选一档
│
│ ┌─ 保活体检卡（顶部汇总）─────────────────────┐
│ │ ●●●●○○  保活状态：一般（4/6 项就绪）          │
│ │ 待完成：通知权限 · 电池优化白名单               │
│ │                    [一键去开启 → 逐项跳转]     │
│ └───────────────────────────────────────────┘
│ （缺通知权限时另出醒目横幅；缺悬浮窗权限时同理——理论上不可能）
│
│ 保活强度 —— 在「耗电量低」与「永久保活」之间自选
│ ┌───────────────────────────────────────────┐
│ │ ○ 🌙 低耗电 · 系统托管                 ⚡   │
│ │    最省电。悬浮球随用随在，被杀不自动复活      │
│ ├───────────────────────────────────────────┤
│ │ ● ⚖️ 智能保活 · 推荐                  ⚡⚡  │
│ │    每 15 分钟自动检查拉活，耗电几乎无感        │
│ ├───────────────────────────────────────────┤
│ │ ○ 🛡️ 永久保活 · 最强存活             ⚡⚡⚡ │
│ │    心跳+精确闹钟双保险，可选无声音乐；          │
│ │    耗电明显增加                              │
│ └───────────────────────────────────────────┘
│ [仅永久档] ○ 可选组件：后台播放无声音乐   ⚡⚡⚡
│ （选永久档 → AlertDialog 二次确认耗电代价）
│ （细项手动改动 → 模式区顶部显示「已自定义」角标）
│
│ 授权引导（一次性；绿勾=已就绪，灰圈=待办，点击即跳）
│  ✓/○ 开机自启            ⚡无    副文案=当前品牌跳转提示（vivo手机：权限管理 → 自启动…）
│  ✓/○ 忽略电池优化        授权类  副文案=「手动管理：允许自启动、关联启动、后台运行」
│  ✓/○ 精确闹钟（永久档）   授权类  跳 ACTION_REQUEST_SCHEDULE_EXACT_ALARM
│  ✓/○ 最近任务隐藏        ⚡无    开关本体；副文案=「主界面入口仍在悬浮球菜单」
│
│ 请求重试机制（次数=0 禁用，逐次递增）
│  最多重试         [────●──────]  3 次
│  递增间隔         [──●────────]  5 秒
│  单次超时（首条）  [──●────────]  15 秒
│  小字：超时=等多久没出第一个字才重试；流式回答总时长不设限
│
│ ┌─ 底部诚实说明卡 ────────────────────────────┐
│ │ · 「强行停止 / 一键清理」任何保活都无法防御    │
│ │ · 各厂商深度清理力度不同，完成授权引导可       │
│ │   显著提升存活率；请勿禁用本应用的通知        │
│ └───────────────────────────────────────────┘
```

### 7.2 组件与交互细节

- **模式卡片**：竖排 3 张可选 `Card`（360dp 宽下横向 3 列太挤），选中态 =
  `tonalElevation` + 边框高亮 + RadioButton；右上角 ⚡ 徽标（文字资源，不用
  emoji 硬编码）。切换即时生效 + SnackBar 反馈；「永久保活」先确认再生效。
- **体检卡**：`KeepAliveUtils.healthChecks()` 返回
  `List<Check(name, ready, action)>`——通知权限（33+）、悬浮窗、电池优化、
  开机自启组件、精确闹钟（仅永久档计入）。「一键去开启」按待办顺序逐项跳转；
  `onResume` 重算（用户从系统页回来立即刷新）。
- **授权行**：M3 `ListItem` + leading 状态图标（✓ Check / ○ RadioButton 未选
  样式）+ trailing「去开启」TextButton 或 Switch（最近任务隐藏用 Switch 本体）。
  状态一律实时查询系统，不落盘（真相在系统）。
- **重试滑条**：M3 `Slider` + 行尾实时数值；`steps` 按范围取整；改完即存
  （防抖 300ms）。
- **返回即生效**：全部设置即时落盘，无「保存」按钮。

### 7.3 strings 键（全部 `float_keepalive_*` + 复用 `float_ask_*` 风格）

`float_keepalive_title / _subtitle`、`_health_ok / _health_fair / _health_weak /
_health_ready_n`、`_mode_low / _mode_low_desc / _mode_smart / _mode_smart_desc /
_mode_permanent / _mode_permanent_desc / _mode_custom_badge`、
`_battery_low / _battery_mid / _battery_high`、
`_confirm_permanent_title / _confirm_permanent_body / _confirm_ok`、
`_auth_boot / _auth_battery / _auth_exact_alarm / _auth_recents`、
`_go_enable`、`_music_toggle`、`_retry_times_label / _retry_delay_label /
_retry_timeout_label / _retry_hint`、`float_ask_retrying`（`第 %1$d/%2$d 次重试…`）、
`_footer_force_stop / _footer_vendor`，以及各品牌自启提示
`_auto_start_vivo / huawei / honor / xiaomi / redmi / oppo / oneplus / meizu /
samsung / letv / zte / unknown`（文案搬 SmsForwarder，格式化用位置标记）。

---

## 8. 开关键一览（`floatball_keepalive` SharedPreferences）

| 键 | 类型/默认 | 说明 |
|---|---|---|
| `mode` | Int/1（智能） | 0 低耗电、1 智能、2 永久、3 自定义 |
| `enable_boot` | Bool/true | 开机自启（+receiver 组件启用双轨） |
| `enable_exclude_recents` | Bool/false | 最近任务隐藏 |
| `enable_silence_music` | Bool/false | 无声音乐（仅永久档可见） |
| `retry_times` | Int/3 | 0 = 禁用 |
| `retry_delay_sec` | Int/5 | 递增间隔基数 |
| `retry_timeout_sec` | Int/15 | 首 token 超时 |
| `service_desired` | Bool/true | 退出旗标 |
| `last_crash_at` | Long/0 | 崩溃诊断 |

（电池优化、通知权限、精确闹钟一律实时查系统，不落盘。）

---

## 9. 铁律合规自检（AGENTS.md）

- ✅ 上游零侵入：仅白名单纯追加（Manifest、strings `float_keepalive_*`）；
- ✅ 无新 Gradle 模块、无新依赖；不动 build 脚本与版本目录；
- ✅ 新代码全部在 `com.verlintas.baic2.keepalive`；
- ✅ 复用优先：FloatUi 零改动即挂第 6 子球；LLM 不手搓 HTTP（只在
  AgentLoop 之外包重试）；权限判断走系统 API；
- ✅ 每文件 GPL-3.0 头；`tools/check-license-headers.sh` 过闸；
- ✅ 文案全进 strings.xml（位置标记格式化）；
- ✅ Service/Receiver 毫秒级轻活；主线程不做位图；
- ✅ 提交拆分：`feat(floatball): 保活设置页（强度三档+体检+授权引导）` →
  `feat(floatball): 心跳拉活、开机自启与无声音乐` →
  `feat(floatball): 屏幕问答流式重试`。

---

## 10. 验证方案

```bash
./gradlew :app:assembleDebug && tools/check-license-headers.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.verlintas.baic2 SYSTEM_ALERT_WINDOW allow

# 设置页与模式切换
adb shell am start -n com.verlintas.baic2/.keepalive.KeepAliveSettingsActivity
# 体检卡逐项核对：dumpsys 输出与绿勾一致
adb shell dumpsys deviceidle whitelist | grep verlintas   # 电池优化
adb shell dumpsys jobscheduler | grep -A2 KeepAlive        # 智能档起有 job
adb shell dumpsys alarm | grep verlintas                   # 永久档起有闹钟
adb shell dumpsys activity services com.verlintas.baic2    # FGS / 无声音乐

# 开机自启
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.verlintas.baic2
# 或 adb reboot 后轮询 dumpsys window 确认悬浮球窗口（历史教训#4）

# 拉活：后台化后 adb shell am kill（force-stop 除外，那是无解场景）
# 最近任务隐藏：dumpsys activity recents 核对任务被排除
# 重试：次数调小 + 断网触发 Failed，分屏面板应显示「第 n/N 次重试…」
adb logcat -d | grep -E "FATAL|AndroidRuntime"   # 必须为空
```

---

## 11. 风险与边界（诚实声明）

1. **force-stop 无法防御**——所有保活（含 SmsForwarder）都防不了用户强杀。
2. **厂商差异**：MIUI/EMUI 深度清理可能连 JobScheduler 一起禁——这正是三档
   模型与授权引导存在的意义：把可控部分交给用户。
3. **BOOT 启 FGS 的类型限制**：mediaPlayback 等在 Android 15 受限清单内；
   BOOT 只拉 specialUse 的悬浮球服务，无声音乐仅前台开启，规避之。
   另加 `ForegroundServiceStartNotAllowedException` 兜底（转心跳），
   对未知 ROM 行为不静默失败。
4. **通知权限（13+）是保活可见性前提**：体检卡把它做成第一待办。
5. **无声音乐耗电**：默认关 + ⚡⚡⚡ 标注 + 永久档二次确认，三重提示。
6. **重试的部分输出语义**：有部分文本不重试（防重复），只重试零输出失败——
   与 XHttp2 整请求重试不同，是流式场景的正确适配。
