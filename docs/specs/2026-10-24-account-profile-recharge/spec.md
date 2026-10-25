# Spec：账号菜单账号名化 + 个人信息页 + 余额不足充值提示

日期：2026-10-24 ｜ 分支：laocanmou ｜ 状态：待批准

## 1. 需求（用户原话）

> 如果用户已经登录了，主页菜单中显示为：账号名；单击则打开这个个人信息页：账号名称，
> 积分余额，到期时间，卡密号码+复制按钮，退出按钮。
> 如果用户单击"退出"，则软件回到最初的登录界面；如果用户的积分不够，则无法再继续使用
> llm，则要及时的提示用户"余额不足，请充值"；"充值"2字是带链接的，点击则进入购买充值的
> webview网页，`https://www.80fafa.com/Goods/3007AFB1DC8B710E`

已确认口径（用户选定）：
1. 「到期时间」= **积分到期时间**（`pointsExpireAt`）。
2. 余额不足判定 = **按网关报错特征匹配**（不做钱包复核请求）。

## 2. 功能拆解

### F1 主页菜单账号名化
- 已登录：主页左上角 Dock 菜单的账号入口显示**账号名**；未登录显示「登录」。
- 单击 → 打开个人信息页（现有 `AccountActivity`）。

### F2 个人信息页
信息行（自上而下）：
1. 账号名称
2. 积分余额（保留「刷新」按钮，功能最大化）
3. 到期时间（积分到期时间；空值显示「永不过期」）
4. 卡密号码 + 复制按钮（复制到剪贴板 + Toast「已复制」）
5. 退出按钮

保留「启用卡密」表单卡（既有功能，不得损害）。点「退出」→ `logout()` 后
`AccountActivity` finish，底下的 `MainActivity` 因 `session == null` 命中登录门
→ 显示**登录界面**（回到最初登录界面，机制已存在，无需新码）。

### F3 余额不足提示 + 充值链接
- LLM 回合失败且失败报文命中「网关额度/鉴权拒绝」特征 → 状态原因归类为 `no_points`。
- 分屏状态行显示「**余额不足，请充值**」，其中「充值」为可点击链接
  （下划线 + LINK_COLOR），点击打开**购买充值 WebView 页**（固定 URL，见 §5）。
- 可见性保障已存在：`FloatBallService` 对 `Event.Failed` 走 `ensureSplit()` 自动拉起分屏。

## 3. 修改清单（精确到文件/函数）

### 3.1 [AccountManager.kt](file:///e:\_android_work\NovaBAIC\app\src\main\java\com\verlintas\baic2\floatball\account\AccountManager.kt)（floatball 私有，可改）
- 新增 `fun cardKey(): String? = prefs.loadCardKey()`（KDoc：个人信息页「卡密号码」展示与复制）。

### 3.2 [AccountApi.kt](file:///e:\_android_work\NovaBAIC\app\src\main\java\com\verlintas\baic2\floatball\account\AccountApi.kt)（floatball 私有，可改）
- companion 新增：`const val RECHARGE_URL = "https://www.80fafa.com/Goods/3007AFB1DC8B710E"`
  （KDoc：充值购买页，余额不足提示「充值」入口，商业化入口收口）。

### 3.3 新建 `app/src/main/java/com/verlintas/baic2/floatball/account/RechargeActivity.kt`
- `@AndroidEntryPoint` 不必须（无注入）；`ComponentActivity`：
  - `attachBaseContext` 走 `AppLocaleStore`（与 `AccountActivity` 同构）；
  - `enableEdgeToEdge` + Baic2Theme（themeMode/accent 自 `SettingsRepository` 注入，
    与 AccountActivity 同构）+ 状态栏图标色 SideEffect；
  - 内容 = `AdvisorPage(title = float_recharge_title, onBack = { finish() })` +
    `AndroidView(WebView)`：`javaScriptEnabled`、`domStorageEnabled`、`WebViewClient()`、
    `loadUrl(AccountApi.RECHARGE_URL)`（镜像 `LoginScreen.RegisterWebViewScreen` 写法，
    含 `@SuppressLint("SetJavaScriptEnabled")`）。
- GPL-3.0-or-later 头 19 行（从任意现有 .kt 拷贝）。

### 3.4 [AndroidManifest.xml](file:///e:\_android_work\NovaBAIC\app\src\main\AndroidManifest.xml)（白名单，纯增量）
- 在 `AccountActivity` 声明（L109-113）后追加：
  ```xml
  <activity
      android:name=".floatball.account.RechargeActivity"
      android:exported="false"
      android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"
      android:label="@string/float_recharge_title" />
  ```

### 3.5 [AccountScreen.kt](file:///e:\_android_work\NovaBAIC\app\src\main\java\com\verlintas\baic2\floatball\account\AccountScreen.kt)（floatball 私有，可改）
`AccountScreen` 重构信息区（保持卡片式 + AdvisorPage 骨架不变）：
- 卡片「账号信息」（`float_account_section_profile`）内 4 行：
  - 账号名称：`float_account_name_row`（`session?.username`）；
  - 积分余额：`float_account_points_balance` + 右侧「刷新」（现有 `refreshWallet()` 保留）；
  - 到期时间：`wallet?.pointsExpireAt` 空 → `float_account_expire_row_never`，
    否则 `float_account_expire_row` + `formatIsoTime`；
  - 卡密号码：`accountManager.cardKey()`（新调用）空 → `float_account_card_key_none`，
    否则 `float_account_card_key_row` + 复制按钮（`OutlinedButton`，
    `ClipboardManager.setPrimaryClip(ClipData.newPlainText("card_key", key))` +
    Toast `float_copy_done`）。
- 「启用卡密」卡（卡号/卡密输入 + 启用按钮 + 成功/错误文案）**原样保留**。
- 「退出」按钮保留 `logout(); onClose()`。
- 删除原「账号卡」的会话有效期行（`float_account_expire` 展示位）——用户指定的信息页
  字段清单中没有它，「到期时间」已确认为积分到期时间。
- 依赖新增：`android.content.ClipData`/`ClipboardManager`、`android.widget.Toast`。
- 状态：`val savedCardKey = remember { mutableStateOf(accountManager.cardKey()) }`，
  启用卡密成功后同步刷新为新卡密。

### 3.6 [Baic2App.kt](file:///e:\_android_work\NovaBAIC\app\src\main\java\com\verlintas\baic2\Baic2App.kt)（**上游文件，[upstream-touch]，diff 最小化**）
- `Baic2App(...)`、`DockAnchor(...)`、`DockPanel(...)` 三个签名**末尾**追加
  `accountName: String? = null` 并逐层透传（默认值保证其他调用点零改动）。
- `DockPanel` 内 `DockActionItem`（L540-544）label 改为
  `accountName ?: stringResource(R.string.float_nav_login)`（仅此一行变化）。

### 3.7 [MainActivity.kt](file:///e:\_android_work\NovaBAIC\app\src\main\java\com\verlintas\baic2\MainActivity.kt)（白名单，纯增量）
- `Baic2App(...)` 调用（L187-194）追加实参：
  `accountName = session?.username?.takeIf { it.isNotBlank() }`。

### 3.8 [ScreenAdvisor.kt](file:///e:\_android_work\NovaBAIC\app\src\main\java\com\verlintas\baic2\floatball\ScreenAdvisor.kt)（floatball 私有，可改）
- companion 新增 `const val REASON_NO_POINTS = "no_points"` 与
  `private fun noPointsReason(message: String?): String?`：
  小写化后包含任一特征 → `REASON_NO_POINTS`，否则 null。
  特征词：`8007`、`积分不足`、`余额不足`、`请充值`、`请换卡`、`insufficient`、`budget`、
  `quota`、`exceeded`、`401`、`403`、`unauthorized`、`forbidden`、`api key`、`api_key`、
  `authentication`。
- 4 处汇聚点包一层 `noPointsReason(x) ?: x`：
  - L2713 `Chunk.Failure -> failureReason = noPointsReason(chunk.reason) ?: chunk.reason`
  - L2742 `ZeroOutputFailure(noPointsReason(error.message) ?: error.message ?: REASON_NETWORK)`
  - L2898 `AgentEvent.Failed -> failureReason = noPointsReason(event.error.message) ?: event.error.message`
  - L2926 同 L2742。
- 不改构造签名、不动其余管道。

### 3.9 [TranscriptView.kt](file:///e:\_android_work\NovaBAIC\app\src\main\java\com\verlintas\baic2\floatball\TranscriptView.kt)（floatball 私有，可改）
- `renderStatus` 的 reason 分支新增 `"no_points" -> noPointsText()`。
- 新增 `private fun noPointsText(): CharSequence`：`float_ask_no_points`（「余额不足，请充值」）
  中定位 `float_recharge_link`（「充值」）附加 `ClickableSpan`（样式复用 `LINK_COLOR` +
  下划线），onClick → `context.startActivity(Intent(context, RechargeActivity::class.java)
  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))`；找不到「充值」子串时回退纯文本。
- `renderStatus` 末尾：文本为 Spanned 时 `holder.text.movementMethod =
  LinkMovementMethod.getInstance()`（LinkMovementMethod/ClickableSpan/TextPaint 均已 import）。
- 新增 import：`android.content.Intent`、`com.verlintas.baic2.floatball.account.RechargeActivity`。

### 3.10 [strings.xml](file:///e:\_android_work\NovaBAIC\app\src\main\res\values\strings.xml)（白名单，纯增量 `float_*`）
| 键 | 值 |
| --- | --- |
| `float_account_section_profile` | 账号信息 |
| `float_account_name_row` | 账号名称：%1$s |
| `float_account_expire_row` | 到期时间：%1$s |
| `float_account_expire_row_never` | 到期时间：永不过期 |
| `float_account_card_key_row` | 卡密号码：%1$s |
| `float_account_card_key_none` | 未启用卡密 |
| `float_account_card_copy` | 复制 |
| `float_ask_no_points` | 余额不足，请充值 |
| `float_recharge_link` | 充值 |
| `float_recharge_title` | 购买充值 |

复用现有键：`float_account_points_balance`、`float_account_points_refresh`、
`float_account_section_card`、`float_account_logout`、`float_copy_done`、`float_nav_login`。

## 4. 不做的事（范围红线）

- 不查钱包复核余额（用户选定纯报错匹配）。
- 不动服务端；不加新 Gradle 模块；不碰 `core:*`。
- 不改主界面聊天（feature:chat）里的错误展示（上游文件）。
- 个人信息页不加「充值」按钮（需求只给提示行链接；如需后续再加）。

## 5. 配置与环境

- 充值 URL 固定：`https://www.80fafa.com/Goods/3007AFB1DC8B710E`（不随 serverEnv 变化）。
- 构建：`./gradlew :app:assembleDebug`；真机验证包沿用
  `-Pbaic.serverEnv=test`（PowerShell 必须加引号）+ release 签名工作流（build-client.ps1）。

## 6. 验证步骤（提交前门槛）

1. **license 门禁**：`wsl bash -c "cd /mnt/e/_android_work/NovaBAIC && sed 's/\r$//' tools/check-license-headers.sh | bash"`（新文件 RechargeActivity.kt 带 GPL 头）。
2. **编译**：`./gradlew :app:assembleDebug`（必要时真机包 `"-Pbaic.serverEnv=test"`）。
3. **真机冒烟**（设备 449c98301422，勿 pm clear；截图走 screencap /sdcard + pull，落 `tests/tempImg/`）：
   - [ ] 已登录时主页 Dock 菜单显示**账号名**；点它进入个人信息页
   - [ ] 未登录/退出后菜单显示「登录」
   - [ ] 个人信息页 4 项信息齐全：账号名称/积分余额/到期时间/卡密号码+复制按钮
   - [ ] 复制按钮 → Toast「已复制」，剪贴板内容 = 卡密
   - [ ] 「启用卡密」表单仍可用（功能不回退）
   - [ ] 点「退出」→ 回到登录界面
   - [ ] 余额不足：测试服务器把卡积分扣光/吊销 key 后提问 → 分屏状态行
        「余额不足，请充值」→ 点「充值」打开购买充值 WebView 页
4. **logcat**：`adb logcat -d | grep -E "FATAL|AndroidRuntime"` 必须为空。

## 7. 风险与备注

- **[upstream-touch]**：Baic2App.kt 属上游文件，提交信息须带 `[upstream-touch]`；
  采用「签名末尾加带默认值参数」策略，合并上游时冲突面≈1 行。
- 网关报错特征匹配存在误判可能（如某工具错误文本含 "401"）；特征词偏保守，
  仅 LLM 失败链路（AdvisorResponder/AgentLoop 错误）会进入该分类，影响面可控。
- 服务端 8007 流程：余额不足 → 吊销该卡 LiteLLM key，App 侧只能看到网关
  auth/budget 拒绝报文——这正是特征匹配覆盖 `401/api key/budget` 等词的原因。
