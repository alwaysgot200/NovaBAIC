# NovaBAIC「好参谋」保活方案设计（v3）

> 学习吸收 SmsForwarder（E:\_android_work\SmsForwarder）的保活措施，
> 在 `laocanmou` 分支上以「上游零侵入」方式复刻。
> v2 变更：新增**保活强度三档模型**（永久保活 ↔ 耗电量低由用户自选）、
> **设置页完整 UI 规格**（保活体检卡 + 模式卡片 + 授权引导 + 重试滑条）、
> 若干健壮性修正（FGS 启动异常兜底、通知重挂、心跳复活无声音乐）。
> v3 变更（评审后勘误，均经代码与官方文档核实）：
> ① **重写拉活路径**——Android 15 起「有悬浮窗权限即可后台启 FGS」的豁免
> 收窄为「权限 + 此刻存在可见 overlay 窗口」，服务被杀后形成先有鸡先有蛋
> 死局；电池优化白名单与精确闹钟触发本身就是**独立豁免**，据此重排主/备路径；
> ② **保活入口改入好参谋设置页**——扇形菜单现状已是 6 项占满 6 槽位
> （`items.zip(offsets)` 传第 7 项会被静默丢弃），不再新增子球，FloatUi 零改动；
> ③ **重试活信号改为首个文本事件**（`TextDelta`/`ThinkingDelta`），
> `RoundStarted` 瞬时到达不能当超时锚点；④ 文件名勘误
> （`TranscriptView.kt`，不存在 AdvisorTranscript）；⑤ `silence.mp3` 无来源
> （SmsForwarder 仓库不含该资产，它在 Cactus AAR 内），改为自生静音资产；
> ⑥ 体检项计数、低耗电档文案、boot 后全量重排、音乐服务媒体控件等小修。
> 实现全部位于 `app` 模块 `com.verlintas.baic2.keepalive` 包 + 白名单纯增量追加。

---

## 0. 结论（TL;DR）

1. **不引入 Cactus 依赖**。Cactus 1.1.3-beta13 是 2021 年产物，内部前台服务
   未声明 `foregroundServiceType`，在 targetSdk 34+ 上 `startForeground` 会直接抛
   `MissingForegroundServiceTypeException`；1 像素 Activity 依赖「后台启动
   Activity」这一动作本身——与窗口几个像素无关。注意一个平台事实：
   **SYSTEM_ALERT_WINDOW 至今仍是后台启动 Activity 的法定豁免，并未在 Android 12
   被移除**；放弃 1 像素的真正理由是：其目的（前台级优先级）已被 specialUse 常驻
   FGS 合法达成，且透明 1 像素 Activity 是国产 ROM 重点查杀对象，收益为零、
   结怨增加。
2. **保活强度交给用户决策**：三档预设「低耗电 / 智能保活（推荐，默认）/
   永久保活」+ 细项自定义，每档明标电池代价（⚡），选「永久保活」需二次确认。
   这是对 SmsForwarder「一堆平铺开关」的 UX 升级：开关还在，但用户先选档位。
3. **零 [upstream-touch]**：不动 `Baic2Application`、`MainActivity`、build 脚本、
   版本目录。触碰范围仅白名单纯追加（Manifest、strings）+ 本分支自有文件小改
   （FloatBallService、ScreenAdvisor、TranscriptView、FloatSettingsActivity；
   **FloatUi 零改动**——扇形菜单现状已是 6 项占满 6 个 30° 分度槽位，
   保活入口挂进好参谋设置页而不是新增第 7 子球）。
4. 好参谋现有 `FloatBallService` 已是 `specialUse` 前台服务 + `START_STICKY`，
   「常驻通知」这一层已经存在；方案补的是**拉活**（开机/更新/心跳/闹钟/崩溃）、
   **授权引导**（通知/电池优化/精确闹钟/厂商自启）、**隐身**（最近任务隐藏）、
   **请求重试**（LLM 流式调用）四层，并全部纳入三档强度之下。
5. **targetSdk 36 下的拉活真相（v3 核心修正）**：Android 15 起，仅持有
   SYSTEM_ALERT_WINDOW 不再能从后台启 FGS——启动瞬间还必须有**可见的 overlay
   窗口**；而服务被杀时悬浮球窗口已随进程消失，心跳直接拉服务会撞
   `ForegroundServiceStartNotAllowedException`。真正可靠的两条独立豁免是
   **「用户已对本 App 关闭电池优化」**与**「精确闹钟触发」**——因此电池白名单
   从「授权引导之一」提级为智能/永久档的**拉活必要条件**，闹钟链在 A15+ 是比
   Job 心跳更硬的路径（详见 §6.4）。

---

## 1. SmsForwarder 保活手段盘点 → 搬运判定

| # | 机制 | SmsForwarder 实现（源码位置） | 判定 | NovaBAIC 对应 |
|---|------|------------------------------|------|--------------|
| 1 | 开机自启 | `BootCompletedReceiver`：BOOT/LOCKED_BOOT/USER_PRESENT/PACKAGE_RESTARTED，`priority=MAX`、directBootAware；收到后拉起 SplashActivity 间接拉服务；开关 = `PackageManager.setComponentEnabledSetting` | ✅ 可搬（简化） | `KeepAliveBootReceiver` 直接 `startForegroundService(FloatBallService)`；跳过 directBootAware/LOCKED_BOOT |
| 2 | 常驻前台服务 | `ForegroundService`：START_STICKY、IMPORTANCE_HIGH 通知、`App.onCreate` 即拉起 | ✅ 已有 | `FloatBallService` 已是 specialUse FGS + START_STICKY；补 `isRunning` 旗标与退出语义 |
| 3 | Cactus 增强保活 | `com.gyf.cactus:cactus:1.1.3-beta13`：双进程前台服务/JobScheduler/WorkManager/1像素/无声音乐，开关 `enable_cactus` | ❌ 依赖不可搬，✅ 思路可搬 | 框架自实现：JobScheduler 心跳 + AlarmManager 闹钟链（A15+ 拉活主路径，见 §6.4）+ 可选无声音乐 FGS；1 像素放弃（理由见 §2） |
| 4 | 忽略电池优化 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `PowerManager` 检查 + 系统对话框（`KeepAliveUtils.kt`） | ✅ 原样搬 | `keepalive/KeepAliveUtils.kt` |
| 5 | 厂商自启跳转 | `SettingsFragment.startToAutoStartSetting`：`HashMap<MANUFACTURER, 组件列表>`，失败兜底应用详情页 | ✅ 原样搬 | `KeepAliveUtils.autoStartIntent()` + 各品牌提示文案 |
| 6 | 最近任务隐藏 | `appTasks[0].setExcludeFromRecents(true)`（MainActivity 启动时 + 开关切换时） | ✅ 可搬 | `KeepAliveManager.applyRecentsExclusion()`，由服务/设置页驱动，**不碰 MainActivity** |
| 7 | 请求重试机制 | `request_retry_times(0=禁用)/request_delay_time/request_timeout`；XHttp2 线性递增重试 | ✅ 语义可搬，实现重写 | `ScreenAdvisor` 流式管道外层重试封装；「单次超时」语义改为**首个文本事件超时**（`TextDelta`/`ThinkingDelta`，不是瞬时到达的 `RoundStarted`，见 §6.6） |
| 8 | 通知引导 | 「不禁用通知栏，避免APP被杀」提示；POST_NOTIFICATIONS 运行时请求 | ✅ 可搬 | 设置页横幅 + 体检卡 |
| 9 | 通知栏常驻 | `IMPORTANCE_HIGH` + 灯光 + 震动；内容可热更新 | ✅ 可搬（降档） | 保持 `IMPORTANCE_LOW`（本地优先、不打扰） |
| 10 | 崩溃自恢复 | Cactus `setCrashRestartUIEnabled` + 存活统计 | ✅ 思路可搬 | 崩溃时间戳落盘（诊断），靠 START_STICKY + 心跳拉活 |
| 11 | directBootAware / internalOnly | application 级属性 | ❌ 不搬 | minSdk 26 无必要；改动超出白名单字母范围 |

---

## 2. 为什么不引入 Cactus

- **API 34+ 不兼容**：Cactus 的隐藏进程前台服务、通知重建都走无类型的
  `startForeground()`；targetSdk 34 起未声明 `foregroundServiceType` 的 FGS
  启动直接抛异常（本项目 target 36）。
- **1 像素放弃（注意：不是因为「豁免被移除」）**：它是「锁屏瞬间后台启动
  透明 Activity 冒充前台提权」的旧黑科技，与像素数无关。需要纠正一个常见
  误传——SYSTEM_ALERT_WINDOW 至今仍在官方「后台启动 Activity 豁免」清单内，
  Android 12+ 并未移除该豁免；真正让它失去意义的是：① 其目的（锁屏期间
  前台级优先级）已被常驻 specialUse FGS 合法达成；② 透明/1 像素 Activity 是
  国产 ROM 与系统行为检测的重点靶子，复活它只增加被标记概率，零收益。
- **双进程互拉失效**：Android 12+ 对关联进程同步查杀。
- **无声音乐可自实现**：`mediaPlayback` 类型迷你 FGS + `res/raw/silence.mp3`
  循环播放即可，作为「永久保活」档的可选组件。资产**不能从 SmsForwarder 拷贝**
  （其仓库 `res/raw` 无此文件，静音音频打在 Cactus AAR 内部），自行生成
  1–2 秒静音 MP3 入库；并接受其 UX 副作用：新增一条常驻媒体通知、出现在
  锁屏/下拉媒体控件中（见 §6.4.3）。
- **零依赖红利**：心跳全用框架 JobScheduler/AlarmManager，Gradle 一字不改，
  上游合并冲突面为零。

---

## 3. 保活强度模型（核心新增）

用户在「永久保活」与「耗电量低」之间**自己选档**，细项仍可覆盖：

| | 🌙 低耗电 | ⚖️ 智能保活（推荐·默认） | 🛡️ 永久保活 |
|---|---|---|---|
| 定位 | 最省电：悬浮球随用随在；仅靠系统 `START_STICKY` 托管重建，不做主动巡检 | 每 15 分钟自动检查拉活，耗电几乎无感（**需完成电池优化白名单**，否则 A15+ 拉活可能失效） | 最强存活：Job 心跳 + 精确闹钟双保险（A15+ 闹钟是硬豁免），可选无声音乐 |
| 电池标注 | ⚡ 低 | ⚡⚡ 适中 | ⚡⚡⚡ 明显增加 |
| 常驻前台服务 + 通知 | ✅ | ✅ | ✅ |
| 开机自启 | ✅（零持续耗电） | ✅ | ✅ |
| JobScheduler 心跳（15 min，persisted） | — | ✅ | ✅ |
| 精确闹钟链（A15+ 拉活硬豁免） | — | — | ✅（需精确闹钟权限） |
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
- **电池优化白名单是智能/永久档「主动拉活」的成立前提**（A15+ 的独立 FGS
  启动豁免，见 §6.4）：未完成时模式卡片下方出警示行「未加入电池优化白名单，
  系统查杀后可能无法自动拉回」，但不阻断选档——低耗电档无此要求。
- `KeepAliveManager.applyMode()` 统一应用/撤销：排程或取消 job、设置或撤销
  闹钟链、启停无声音乐服务；**boot 接收器拉起服务后必须执行一次完整
  `applyMode()`**（闹钟不跨重启存活，job 虽 persisted 也重排一次以求一致）。

各细项的电池代价标注（UI 逐项展示）：

| 措施 | 代价 | 说明 |
|---|---|---|
| 常驻前台服务 | ⚡ 极低 | 本来就是产品形态 |
| 开机自启 | ⚡ 无 | 一次性广播，无持续耗电 |
| JobScheduler 心跳 | ⚡ 极低 | 每小时 4 次毫秒级唤醒 |
| 精确闹钟链 | ⚡⚡ 低 | A15+ 拉活硬豁免；Doze 下限流 9 分钟/次，15 分钟周期不受影响 |
| 无声音乐 | ⚡⚡⚡ 明显 | 音频通路常开 + 进程完全唤醒 |
| 最近任务隐藏 | ⚡ 无 | 纯窗口标志 |
| 电池优化豁免 | 授权类 | 不直接耗电；解除系统后台限制，**同时是 A15+ 后台启动 FGS 的独立豁免**（智能/永久档主动拉活的前提） |

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
│   ← A15+硬路径 ← AlarmReceiver(精确闹钟链) ；MusicService(可选)  │
│ 拉活豁免：电池白名单 / 闹钟触发 / 可见 overlay（降级占位窗）     │
├─ 隐身与重试层 ────────────────────────────────────────────────┤
│ applyRecentsExclusion ；ScreenAdvisor 流式重试(首文本事件超时)   │
└──────────────────────────────────────────────────────────────┘
```

### 新增文件（全部 GPL-3.0 头 + Hilt）

| 文件（`app/src/main/java/com/verlintas/baic2/keepalive/`） | 职责 |
|---|---|
| `KeepAlivePrefs.kt` | SharedPreferences（`floatball_keepalive`）封装；开关、强度档位、重试参数的唯一存储 |
| `KeepAliveManager.kt` | 门面单例：`applyMode()` 应用/撤销全部机制；`onServiceCreated/Destroyed/onUserExit`；`applyRecentsExclusion`；心跳排程；崩溃时间戳 |
| `KeepAliveBootReceiver.kt` | BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT_POWERON → 检查档位 + 悬浮窗权限 → 拉服务 → 执行完整 `applyMode()`（重排 job+闹钟链）；FGS 启动异常时兜底排程心跳。BOOT/MY_PACKAGE_REPLACED 属系统豁免广播可启 specialUse；QUICKBOOT 为厂商广播**不享豁免**，仅尝试不承诺 |
| `KeepAliveHeartbeatJob.kt` | JobService：检查「期望运行 + 未运行 + 有悬浮窗权限」→ 拉服务（白名单豁免；无白名单时走「先挂可见 overlay 再启 FGS」降级，见 §6.4.1）；永久档顺带复活无声音乐 |
| `KeepAliveAlarmReceiver.kt` | 精确闹钟接收器（A15+ 拉活主路径）：重排 job、拉活服务/音乐、设下一个闹钟；闹钟触发本身是 FGS 启动独立豁免 |
| `KeepAliveMusicService.kt` | 可选无声音乐：`mediaPlayback` FGS 循环 `res/raw/silence.mp3`；只允许设置页前台开启或闹钟/白名单路径复活，**绝不从 BOOT 启动** |
| `KeepAliveUtils.kt` | 电池优化检查/申请、精确闹钟检查/跳转、厂商自启跳转表、**保活体检（health check）计算** |
| `KeepAliveSettingsActivity.kt` | Compose 设置页（规格见 §7）；**入口 = `FloatSettingsActivity` 首行「保活措施」**，不进扇形菜单 |
| `app/src/main/res/raw/silence.mp3` | **自行生成**的 1–2 秒静音 MP3（SmsForwarder 仓库无此资产；纯新增二进制文件，须注明来源/许可） |

### 既有文件改动

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` ⚠️白名单 | 追加 3 权限（BOOT_COMPLETED / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS / FOREGROUND_SERVICE_MEDIA_PLAYBACK；SCHEDULE_EXACT_ALARM 上游已有）+ receiver/service/activity 声明（见 §5） |
| `res/values/strings.xml` ⚠️白名单 | 追加 `float_keepalive_*` 键 + `float_ask_retrying` |
| `floatball/FloatBallService.kt` | onCreate 调 `KeepAliveManager.onServiceCreated`（内含体检里的 recents/心跳一致性）；`exitApp` 置退出旗标；`onTaskRemoved` 加固；`onStartCommand` 支持「重挂通知」动作（通知权限刚授予后刷新可见性）。**扇形菜单不动** |
| `floatball/ScreenAdvisor.kt` | 新增 `Status.Retrying(attempt, total)`；`runAdvisorTurn` 套重试封装（两阶段收集，见 §6.6） |
| `floatball/TranscriptView.kt` | 状态渲染 `when(state.status)` 加 `Retrying` 分支（渲染「第 n/N 次重试…」；穷举 when，编译强制补齐） |
| `floatball/FloatSettingsActivity.kt` | 本分支自有文件：权限区下方追加一行「保活措施」→ 启动 `KeepAliveSettingsActivity`（约 10 行，不嵌入其页面逻辑） |
| `floatball/FloatUi.kt` | **零改动**（扇形菜单 6 槽位已被现有 6 项占满，且 150°/30° 几何是定版规格） |

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
  `startForegroundService(FloatBallService)` → onCreate 5 秒内 `startForeground`
  + showBall（现状已满足）→ 通知 + 悬浮球回归。
- **拉起后必须执行一次完整 `KeepAliveManager.applyMode()`**：精确闹钟不跨
  重启存活，job 虽 `persisted` 也重排一次以与当前档位对齐（v3 补：v2 只写
  「拉服务」，重启后永久档闹钟链实际丢失）。
- **广播的豁免资格要区分**：`BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED` 在
  官方豁免广播清单内，接收期间启 FGS 合法，且 Android 15 的 BOOT 受限类型
  清单（dataSync/camera/mediaPlayback/mediaProjection/microphone）不含
  `specialUse`；`QUICKBOOT_POWERON` 是**厂商自定义广播、不在豁免清单**，
  Android 12+ 从它启 FGS 可能直接被拒——保留注册（部分老 ROM 比标准 BOOT
  更早送达），但对它不做效果承诺，失败走同一兜底。
- **开关双轨**（对齐 SmsForwarder）：`PackageManager.setComponentEnabledSetting`
  禁用/启用 receiver 本体（关闭后系统根本不派发）+ prefs 镜像值供 UI。
- **健壮性**：`startForegroundService` 包 runCatching——若系统拒绝后台启 FGS
  （`ForegroundServiceStartNotAllowedException`），兜底为「立即排程心跳 job
  （+ 永久档闹钟）」，让拉活延后到满足豁免的时刻，而不是静默失败。
- 打开开关时展示品牌自启提示并跳厂商自启页（同 SmsForwarder `checkWithReboot`）。

### 6.2 悬浮按钮保活 / 服务常驻

- `FloatBallService.isRunning` 静态旗标（onCreate/onDestroy 维护）。
- **退出旗标** `serviceDesired`：`exitApp()` 置 false（心跳不许复活明确退出）；
  `onServiceCreated`、开机接收器、主界面启动置 true。显式 `stopSelf` 后
  START_STICKY 本就不重启，心跳双保险。
- **永久档专属**：`onTaskRemoved`（划掉主任务时保持服务与浮层一致）、
  `installCrashGuard()`（崩溃时间戳落盘，诊断用；进程恢复靠 sticky + 心跳）。

### 6.3 忽略电池优化（v3 提级：主动拉活的必要条件）

- **为什么它不再只是「授权引导之一」**：官方「后台启动 FGS 限制豁免」清单中，
  「The user turns off battery optimizations for your app」是一条**与 overlay
  可见性无关的独立豁免**。即：用户完成白名单后，Job 心跳/任意后台时刻
  `startForegroundService` 都合法，§6.4 的先有鸡先有蛋问题直接消失。
  智能/永久档体检中它是硬待办（未完成不阻断选档，但模式卡显示警示）。
- `PowerManager.isIgnoringBatteryOptimizations` 实时查询，UI 不落盘。
- 开 → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI；
  关 → `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 列表页。
- 厂商自启跳转表搬 `SettingsFragment.startToAutoStartSetting`：
  `HashMap<MANUFACTURER.lowercase(), List<组件>>`，含 `/` 用
  `ComponentName.unflattenFromString`，否则 `getLaunchIntentForPackage`；
  全失败 → toast + 打开应用详情页。本项目已有 `QUERY_ALL_PACKAGES`，
  跳转表所需的包可见性不受 Android 11+ 限制。
- 合规注：Play 对该权限有限制；本应用 GitHub/APK 直装不受影响。

### 6.4 增强保活（心跳 / 闹钟 / 无声音乐）

#### 6.4.0 先讲平台约束（targetSdk 36，v3 重写）

心跳/闹钟能不能拉活，取决于触发那一刻 App 是否落在
[后台启 FGS 豁免清单](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
内。与本方案相关的四条：

| 豁免 | 适用触发点 | 版本要点 |
|---|---|---|
| BOOT_COMPLETED / MY_PACKAGE_REPLACED 接收期 | 开机接收器 | A15 起仅限制 5 类 FGS 类型，specialUse 不在内 |
| **用户已对本 App 关闭电池优化** | **任意时刻（含 Job 心跳）** | 全版本，与 overlay 是否可见无关 |
| **精确闹钟触发** | **AlarmReceiver 执行期** | A12+ 起需 exact alarm 权限 |
| 持有 SYSTEM_ALERT_WINDOW | 任意时刻 | **A15 收窄：启动瞬间还必须有可见 overlay 窗口** |

关键推论：服务被系统杀掉时，悬浮球窗口随进程一起消失，此时若既没有电池
白名单、也不是闹钟触发，Job 心跳直接 `startForegroundService` 必抛
`ForegroundServiceStartNotAllowedException`——v2「SAW 在 12–15 全可用」的
判断错误。因此拉活策略按以下优先级实现。

#### 6.4.1 JobScheduler 心跳（智能档起生效）

- 排程：`JobInfo.Builder(JOB_ID, KeepAliveHeartbeatJob).setPeriodic(15min)
  .setPersisted(true)`（周期下限 15 分钟，系统可合批延后；A16 起 Job 受
  待机桶运行时配额约束，毫秒级巡检远在配额内）。
- 执行体判定 `serviceDesired && canDrawOverlays && !isRunning` 后，
  **按序尝试三条拉起路径**：
  1. `PowerManager.isIgnoringBatteryOptimizations(self) == true` →
     直接 `startForegroundService(FloatBallService)`（独立豁免，最干净）；
  2. API ≥ 35 且无白名单 → **overlay 先行降级**：通过 `WindowManager` 先
     addView 一个占位悬浮球（TYPE_APPLICATION_OVERLAY；加窗口本身不需要
     FGS，有 SAW 即可）。占位窗必须**非零尺寸且非全透明**（约 8dp、alpha
     0.15；0 像素或 INVISIBLE/GONE 不满足「visible window」判定），
     经 `View.isAttachedToWindow` + 一帧绘制确认后再
     `startForegroundService`；占位窗打固定 tag，由服务 onCreate 在
     `showBall()` 前摘除，Job 侧另设 10 秒兜底摘除（同进程，WindowManager
     句柄可直接移除）。该路径有「先挂窗后启服务」的渲染时序，故只作降级
     不作默认；
  3. 全部失败 → runCatching 落日志并依赖下一拍/闹钟链，不崩溃不刷屏。
- 永久档且无声音乐开：同一次心跳里顺带检查/复活 `KeepAliveMusicService`
  （mediaPlayback 非 while-in-use 类型，白名单/overlay 豁免同样覆盖它）。

#### 6.4.2 AlarmManager 精确闹钟链（永久档；A15+ 最硬路径）

- `setExactAndAllowWhileIdle(+15min)` 链：触发时重排 job → 拉活服务/音乐 →
  设下一个闹钟。**闹钟触发本身是独立豁免**，不依赖白名单也不依赖可见
  overlay——因此在用户拒绝电池白名单的机器上，永久档仍能靠闹钟链拉活，
  它在 A15+ 的实际可靠性高于 Job 心跳。
- API 31+ 先查 `AlarmManager.canScheduleExactAlarms()`：false 时降级
  `setWindow(15min, 2min)`（不享豁免，仅尽力），并在体检卡提示授权
  （跳 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`）。
- Doze 下 exact alarm 限流 9 分钟/次，15 分钟周期在阈值之上，不受影响。
- 闹钟不跨重启：boot 路径必须重设（见 §6.1 的 `applyMode()`）。

#### 6.4.3 无声音乐（永久档子开关，默认关）

- `mediaPlayback` FGS + MediaPlayer 循环 1 秒静音 WAV（`res/raw/silence.wav`，
  仓库自生资产）；**只允许从设置页
  前台启动**，或由 6.4.1/6.4.2 路径复活；绝不在 BOOT 接收器启动
  （mediaPlayback 在 A15 BOOT 受限类型清单内，启动即抛异常）。
- 打开时明示三重代价：⚡⚡⚡ 耗电（音频通路常开 + 进程完全不入 Doze）、
  **新增一条常驻媒体通知**、**出现在锁屏/下拉媒体控件中**（用户会看到一个
  「不发声的播放器」，文案必须预告）。通道独立（`keepalive_music`，
  IMPORTANCE_LOW），与悬浮球通知分开统计。

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
| `retry_delay_sec` | 递增间隔基数（秒）；第 n 次重试前等待 `delay × n`（n 从 1 起，即 5/10/15…） | 1–60 | 5 |
| `retry_timeout_sec` | **首个文本事件超时**（秒），见下方活信号定义 | 5–120 | 15 |

- **作用域**：`ScreenAdvisor.runAdvisorTurn`（本分支唯一 LLM 调用点）。
  主界面聊天重试属上游职责，不碰。用户消息在 `submitTurn` 只追加一次，
  重试 = 用相同 history 重新 `agentLoop.run`，不重复写库。
- **活信号定义（v3 修正，关键）**：`AgentEvent` 流中 `RoundStarted` 在回合
  开始时几乎瞬时到达，`ToolCallStarted` 等也不代表有回答；**「首个文本
  事件」= `TextDelta` / `ThinkingDelta` / `AssistantMessage` 三者之一**。
  推理模型会先吐 `ThinkingDelta`，计入活信号可避免「思考 20 秒被误判超时」。
- **两阶段收集**——Flow 只能收集一次，用 channel 化解决
  「首文本限时 + 后续不限时」：
  ```kotlin
  // attempt = 1..(retryTimes + 1)；每 attempt 重建一次上游 flow
  val events = agentLoop.run(config, mode, prompt, history, ...).produceIn(scope)
  var alive = false
  withTimeoutOrNull(timeoutMs) {
      for (e in events) {
          if (e is TextDelta || e is ThinkingDelta || e is AssistantMessage) { alive = true; break }
          handleNonText(e)              // RoundStarted 等照常处理，不刷新超时锚点语义
      }
  } ?: throw RetryableException("first_text_timeout") // 超时且未见到任何文本
  for (event in events) handle(event)   // 活信号之后不再设总超时，回答多长都等完
  ```
  注意：超时后必须取消该 collect 协程并丢弃整条 flow（防止迟到的 assistant
  落库造成重试后内容重复）。
- **重试判定**：零输出失败（首文本超时 / 连接失败 / collect 抛网络类异常 /
  零文本后的 `AgentEvent.Failed`）→ 先置 `Status.Retrying(n, total)`，
  `delay(delaySec * 1000L * n)`（可取消，`turnMutex` 内不占线程）后重跑；
  **已见文本事件后失败 → 绝不重试**（防内容重复），沿用现有 catch 把
  partial 落库并报错。次数=0 走现有单次路径，零开销。
- **最坏耗时与连续模式预算**：默认参数下最坏 ≈ 4×15s 超时 + (5+10+15)s
  等待 ≈ **90 秒**。单次参谋可接受；**连续（录制）模式强制覆盖为
  times=1、timeout=8s**（或后续做成独立设置项），避免一个坏网络回合堵死
  差分串行队列近两分钟；等待期间到达的新帧按现有 FrameDiffEngine 节奏
  排队/丢弃。
- 新增 `Status.Retrying(attempt, total)`，[TranscriptView.kt](file:///e:/_android_work/NovaBAIC/app/src/main/java/com/verlintas/baic2/floatball/TranscriptView.kt)
  的状态 `when` 加分支渲染「第 n/N 次重试…」。

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
入口（v3 修正）：扇形菜单「设置」子球（单击主球弹出，不是长按）→
`FloatSettingsActivity` 权限区下方新增一行「保活措施」→ 本页。
**不新增扇形子球**：菜单现状已是 6 项（问答/主界面/设置/隐藏/关于/退出）
占满 150° 内 6 个 30° 槽位，FloatUi 的 `items.zip(offsets)` 传第 7 项会被
静默丢弃。

### 7.1 页面结构（自上而下）

```
┌ TopAppBar：保活措施                      ⚡ 当前档位徽标
│ 副标题：悬浮球常驻的关键——完成授权引导，并在省电与存活之间选一档
│
│ ┌─ 保活体检卡（顶部汇总）─────────────────────┐
│ │ ●●●●○  保活状态：一般（4/5 项就绪，智能档）    │
│ │ 待完成：通知权限 · 电池优化白名单               │
│ │                    [一键去开启 → 逐项跳转]     │
│ └───────────────────────────────────────────┘
│ （缺通知权限时另出醒目横幅；缺悬浮窗权限时同理——理论上不可能。
│   分母随档位变化，计数定义见 §7.2；智能/永久档缺电池白名单时
│   模式卡下方常驻警示行）
│
│ 保活强度 —— 在「耗电量低」与「永久保活」之间自选
│ ┌───────────────────────────────────────────┐
│ │ ○ 🌙 低耗电 · 系统托管                 ⚡   │
│ │    最省电。仅靠系统托管重建，不做主动巡检      │
│ ├───────────────────────────────────────────┤
│ │ ● ⚖️ 智能保活 · 推荐                  ⚡⚡  │
│ │    每 15 分钟自动检查拉活，耗电几乎无感        │
│ ├───────────────────────────────────────────┤
│ │ ○ 🛡️ 永久保活 · 最强存活             ⚡⚡⚡ │
│ │    心跳+精确闹钟双保险，可选无声音乐；          │
│ │    耗电明显增加                              │
│ └───────────────────────────────────────────┘
│ [仅永久档] ○ 可选组件：后台播放无声音乐   ⚡⚡⚡
│    副文案：会出现常驻媒体通知与锁屏媒体控件，且明显耗电
│ （选永久档 → AlertDialog 二次确认耗电代价）
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
│  单次超时（首文本）[──●────────]  15 秒
│  小字：超时=等多久没出第一个字（含推理模型的思考过程）才重试；
│        开始回答后流式总时长不设限
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
  `List<Check(name, ready, action)>`，**检查项与分母随档位变化**（消除 v2
  「4/6 却只有 5 项」的计数矛盾）：

  | # | 检查项 | 低耗电 | 智能 | 永久 | 判定来源（全部实时查询，不落盘） |
  |---|---|---|---|---|---|
  | C1 | 通知权限（13+） | ✅计入 | ✅计入 | ✅计入 | `NotificationManagerCompat.areNotificationsEnabled()` |
  | C2 | 悬浮窗权限 | ✅计入 | ✅计入 | ✅计入 | `Settings.canDrawOverlays()` |
  | C3 | 开机自启组件 | ✅计入 | ✅计入 | ✅计入 | receiver 组件 `COMPONENT_ENABLED_STATE` |
  | C4 | 电池优化白名单 | ⚪仅建议 | ✅计入 | ✅计入 | `PowerManager.isIgnoringBatteryOptimizations()` |
  | C5 | 心跳调度活跃 | — | ✅计入 | ✅计入 | `JobScheduler.getPendingJob(JOB_ID) != null` |
  | C6 | 精确闹钟可调度 | — | — | ✅计入 | API31+ `AlarmManager.canScheduleExactAlarms()` |

  即分母：低耗电 **3**、智能 **5**、永久 **6**；C4 在低耗电档不卡健康度，
  但仍以普通授权行展示。「一键去开启」按待办顺序逐项跳转；`onResume`
  重算（用户从系统页回来立即刷新）。
- **授权行**：M3 `ListItem` + leading 状态图标（✓ Check / ○ RadioButton 未选
  样式）+ trailing「去开启」TextButton 或 Switch（最近任务隐藏用 Switch 本体）。
  状态一律实时查询系统，不落盘（真相在系统）。
- **重试滑条**：M3 `Slider` + 行尾实时数值；`steps` 按范围取整；改完即存
  （防抖 300ms）。
- **返回即生效**：全部设置即时落盘，无「保存」按钮。

### 7.3 strings 键（全部 `float_keepalive_*` + 复用 `float_ask_*` 风格）

`float_keepalive_title / _subtitle`、`_health_ok / _health_fair / _health_weak /
_health_ready_n`、`_mode_low / _mode_low_desc / _mode_smart / _mode_smart_desc /
_mode_permanent / _mode_permanent_desc`、
`_battery_low / _battery_mid / _battery_high`、
`_confirm_permanent_title / _confirm_permanent_body / _confirm_ok`、
`_auth_boot / _auth_battery / _auth_exact_alarm / _auth_recents`、
`_go_enable`、`_music_toggle / _music_media_note`（媒体通知/锁屏控件预告）、
`_whitelist_warning`（智能/永久档未加白名单的模式卡警示行）、
`_retry_times_label / _retry_delay_label /
_retry_timeout_label / _retry_hint`、`float_ask_retrying`（`第 %1$d/%2$d 次重试…`）、
`_footer_force_stop / _footer_vendor`，以及各品牌自启提示
`_auto_start_vivo / huawei / honor / xiaomi / redmi / oppo / oneplus / meizu /
samsung / letv / zte / unknown`（文案搬 SmsForwarder，格式化用位置标记）。

---

## 8. 开关键一览（`floatball_keepalive` SharedPreferences）

| 键 | 类型/默认 | 说明 |
|---|---|---|
| `mode` | Int/1（智能） | 0 低耗电、1 智能、2 永久 |
| `enable_boot` | Bool/true | 开机自启（+receiver 组件启用双轨） |
| `enable_exclude_recents` | Bool/false | 最近任务隐藏 |
| `enable_silence_music` | Bool/false | 无声音乐（仅永久档可见） |
| `retry_times` | Int/3 | 0 = 禁用 |
| `retry_delay_sec` | Int/5 | 递增间隔基数 |
| `retry_timeout_sec` | Int/15 | 首个文本事件超时（含思考过程） |
| `service_desired` | Bool/true | 退出旗标 |

（电池优化、通知权限、精确闹钟一律实时查系统，不落盘。连续/录制模式不另设
键：重试预算在代码内强制收窄为 times=1、timeout=8s，见 §6.6。）

---

## 9. 铁律合规自检（AGENTS.md）

- ✅ 上游零侵入：仅白名单纯追加（Manifest、strings `float_keepalive_*`）；
- ✅ 无新 Gradle 模块、无新依赖；不动 build 脚本与版本目录；
- ✅ 新代码全部在 `com.verlintas.baic2.keepalive`；
- ✅ 复用优先：FloatUi 零改动（6 槽位已满，入口挂好参谋设置页）；LLM 不
  手搓 HTTP（只在 AgentLoop 之外包重试）；权限判断走系统 API；
- ✅ 每文件 GPL-3.0 头；`tools/check-license-headers.sh` 过闸；
  自生 `silence.mp3` 属二进制，在文件头注释/PR 说明注明生成方式与来源；
- ✅ 文案全进 strings.xml（位置标记格式化）；
- ✅ Service/Receiver 毫秒级轻活；主线程不做位图；
- ✅ 提交拆分：`feat(floatball): 保活设置页（强度三档+体检+授权引导，
  含 FloatSettingsActivity 入口行）` →
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

# 开机自启（先打开 A15 BOOT 限制开关再测，模拟器 API 35/36 必测）
adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS com.verlintas.baic2
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.verlintas.baic2
# 或 adb reboot 后轮询 dumpsys window 确认悬浮球窗口（历史教训#4）
# 重启后除悬浮球外，还要核对 job 已重排 + 永久档闹钟链已重设（§6.1）

# 拉活矩阵（force-stop 除外，那是无解场景）——必须在 API 36 模拟器逐格验证：
# 格1 有电池白名单：am kill 后等心跳（≤15min，或 adb shell cmd jobscheduler
#       run-job 触发），服务应直接拉回
# 格2 无白名单 + 无 overlay：同上触发，核对走了「占位 overlay 先行」降级，
#       logcat 应见占位窗 add/remove，无 ForegroundServiceStartNotAllowedException
adb shell cmd jobscheduler run -f com.verlintas.baic2 <心跳JOB_ID>
# 格3 永久档 + 撤销 exact alarm 权限：体检卡 C6 应变灰，跳授权页可恢复
# 格4 QUICKBOOT_POWERON 广播：允许失败，但不得崩溃（兜底排程日志可见）
adb shell am broadcast -a android.intent.action.QUICKBOOT_POWERON -p com.verlintas.baic2
# 最近任务隐藏：dumpsys activity recents 核对任务被排除
# 重试：次数调小 + 断网触发 Failed，分屏面板应显示「第 n/N 次重试…」；
#       另测推理模型长思考（>timeout）不被误判（ThinkingDelta 算活信号）；
#       录制模式下重试预算应为 times=1/timeout=8s
adb logcat -d | grep -E "FATAL|AndroidRuntime"   # 必须为空
```

> 注：`cmd jobscheduler run-job` 的 job id 以实现时常量为准；测试前用
> `dumpsys jobscheduler` 查到实际编号。

---

## 11. 风险与边界（诚实声明）

1. **force-stop 无法防御**——所有保活（含 SmsForwarder）都防不了用户强杀。
2. **厂商差异**：MIUI/EMUI 深度清理可能连 JobScheduler 一起禁——这正是三档
   模型与授权引导存在的意义：把可控部分交给用户。
3. **A15+ 后台启 FGS 的两道收紧**（v3 重估，均有对策但必须如实告知）：
   ① BOOT 接收器黑名单含 mediaPlayback——BOOT 只拉 specialUse 的悬浮球
   服务，无声音乐仅前台开启或由闹钟/白名单路径复活；② 仅持 SAW 不再够，
   启动瞬间须有可见 overlay——心跳拉活按「电池白名单 → 占位 overlay 先行
   → 放弃等下一拍/闹钟」三级降级，对未知 ROM 行为不静默失败（落日志）。
4. **通知权限（13+）是保活可见性前提**：体检卡把它做成第一待办。
5. **无声音乐耗电与媒体控件困扰**：默认关 + ⚡⚡⚡ 标注 + 永久档二次确认 +
   设置行预告「常驻媒体通知/锁屏媒体控件」，四重提示。
6. **重试的部分输出语义**：有部分文本（含思考过程）不重试（防重复），只
   重试零文本失败——与 XHttp2 整请求重试不同，是流式场景的正确适配；
   活信号锚定 `TextDelta/ThinkingDelta/AssistantMessage`，避免把
   `RoundStarted` 误当首字、把长思考误判超时。
7. **占位 overlay 降级的体验毛刺**：无电池白名单的 A15+ 机器上，心跳拉活
   瞬间可能闪现一个极淡的 8dp 光点（正式球在服务 onCreate 后立即替换）。
   这是平台规则下的必要妥协；白点持续超过 1 秒视为缺陷（兜底摘除会报警）。
