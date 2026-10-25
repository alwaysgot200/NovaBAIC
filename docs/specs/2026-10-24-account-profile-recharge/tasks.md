# Tasks：账号菜单账号名化 + 个人信息页 + 余额不足充值提示

依据 [spec.md](./spec.md)。批准后按序执行；每步完成即勾选。

## T1 常量与读口
- [x] `AccountManager.kt` 新增 `fun cardKey(): String? = prefs.loadCardKey()`
- [x] `AccountApi.kt` companion 新增 `RECHARGE_URL`（充值购买页固定 URL）

## T2 充值 WebView 页
- [x] 新建 `floatball/account/RechargeActivity.kt`（GPL 头 + AppLocaleStore + Baic2Theme +
      AdvisorPage + WebView 加载 `AccountApi.RECHARGE_URL`）
- [x] `AndroidManifest.xml` 追加 `RechargeActivity` 声明（AccountActivity 之后，纯增量）

## T3 个人信息页重排
- [x] `AccountScreen.kt`：「账号信息」卡 = 账号名称/积分余额+刷新/到期时间/卡密号码+复制
- [x] 保留「启用卡密」表单与「退出」按钮；启用成功后卡密号码行同步刷新
- [x] 删除原账号卡会话有效期行（字段清单已确认不含）

## T4 主页菜单账号名化（[upstream-touch]）
- [x] `Baic2App.kt`：`Baic2App`/`DockAnchor`/`DockPanel` 签名末尾加
      `accountName: String? = null` 并透传；DockActionItem label =
      `accountName ?: stringResource(R.string.float_nav_login)`
- [x] `MainActivity.kt`：`Baic2App(...)` 传 `accountName = session?.username?.takeIf { it.isNotBlank() }`

## T5 余额不足提示 + 充值链接
- [x] `ScreenAdvisor.kt`：companion 加 `REASON_NO_POINTS` + `noPointsReason()`，
      包 L2713/L2742/L2898/L2926 四处汇聚点
- [x] `TranscriptView.kt`：`renderStatus` 加 `"no_points"` 分支 → 「余额不足，请充值」
      （「充值」ClickableSpan → RechargeActivity，FLAG_ACTIVITY_NEW_TASK）
- [x] `strings.xml` 追加 10 个 `float_*` 键（见 spec §3.10）

## T6 验证（提交前门槛）
- [ ] license 门禁（wsl + sed 去 CRLF）
- [ ] `./gradlew :app:assembleDebug` 编译通过
- [ ] 真机冒烟：菜单账号名→个人信息页 4 项信息→复制 Toast→启用卡密可用→退出回登录页
- [ ] 余额不足链路：扣光积分/吊销 key 后提问 → 「余额不足，请充值」→ 点「充值」开 WebView
- [ ] logcat 无 FATAL/AndroidRuntime；证据图落 `tests/tempImg/`（勿 pm clear）
