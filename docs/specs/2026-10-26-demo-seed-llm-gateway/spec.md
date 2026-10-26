# Spec：演示数据导入 + 前后台 LLM 打通（2026-10-26）

> 上游方案：`NovaBAIC/docs/2026-10-26-测试数据与前后台LLM打通方案.md`（已批准）
> 本 spec 把方案落到「精确文件 / 函数 / 验证口径」，用于直接执行。
> 约束：只改 NovaBAIC 自有代码；上游文件改动需 `[upstream-touch]`；新 .kt 必须带 GPL 头（1–18 行）。

## 1. 目标（可验收）

1. App「连接 AI 服务」界面**不出现**「好参谋云端 AI」卡片、API Key 输入框、官方服务商地址；只出现**模型下拉**，默认 `deepseek-flash`，另一项 `deepseek-pro`。
2. App 保存 Agent 时自动使用：`baseUrl = ${SERVER_ORIGIN}/llm/v1`、`apiKey = 当前绑定卡密`、`model = 下拉选择`。用户无需输入任何 key/地址。
3. 服务端新增 `GET /api/v1/point-card/gateway/config`（登录态）：只回 `{ baseUrl, defaultModel, models[] }`，**不回**上游 key/地址。
4. 服务端 `LlmModel` 增加 `appVisible`（默认 true）：App 下拉只取 `enabled && appVisible && 供应商 enabled` 的模型。
5. 开发库可一键灌入演示数据（账号 `35969378@qq.com / 1qaz2WSX`、租户、2 张卡密并绑定入账、`deepseek-flash/pro` 模型并同步 LiteLLM）；重复导入且未 `-Reset` 必须**报错退出码 2**。
6. 本地演示数据（吴瑞曦/topwayricky、领导画像、升职加薪目标、主动政策、自动范围）可一键导入/重置。
7. 真机（449c98301422 / Xiaomi 23077RABDC / Android 15）完成：免登录注入 → 模型下拉两项 → 经 `/llm/v1` 调 flash/pro 均 200 → 主页三键可用。
8. 「无法连接网络」根因消除：debug 包支持局域网明文 + 构建期 `testOrigin` 覆盖；`net.ps1` 自动探测 LAN 并兜底 `adb reverse`。

## 2. 架构决策

### 2.1 客户端分层（关键约束）

`feature:agents` 只依赖 `core:model / core:data / core:network`，**不能**依赖 `app`（Hilt 装配方向 app → feature）。
因此新增跨层接口放在 `core:data`，由 `app` 模块实现并绑定：

`core/data/.../gateway/AdvisorGateway.kt`
```kotlin
data class GatewayModel(val key: String, val displayName: String, val maxTokens: Int?, val reasoning: Boolean)
data class GatewayConfig(val baseUrl: String, val defaultModel: String, val models: List<GatewayModel>)
interface AdvisorGateway {
    suspend fun config(): GatewayConfig      // 登录态拉服务端清单；失败抛带原因的异常
    suspend fun cardKey(): String?           // 当前绑定卡密（明文，仅在内存/Keystore 解密后短暂存在）
}
```

`app` 实现 `AccountGateway`（`app/.../floatball/account/AccountGateway.kt`）：
- `config()` → `AccountApi.fetchGatewayConfig(token)`；未登录抛 `AccountException(REASON_NOT_LOGGED_IN)`。
- `cardKey()` → `AccountPrefs.loadCardKey()`（新增解密读取方法）。
- Hilt 绑定放在 app 既有的 `AdvisorModule`（`@Binds`，`AdvisorGateway <- AccountGateway`）。

### 2.2 服务端接口与数据

- `llm_model` 加列：`appVisible Boolean @default(true) @map("app_visible")`；迁移 `prisma/migrations/<ts>_add_llm_model_app_visible/migration.sql`。
- `PointCardService.gatewayConfig(user)`：
  - 查询 `llmModel.findMany({ where: { enabled: true, appVisible: true, delFlag: '0', provider: { enabled: true, delFlag: '0' } } })`（`include provider` 仅取 enabled）；
  - 排序：`deepseek-flash` 优先，其余按 `modelKey`；
  - 返回 `{ baseUrl: '/llm/v1', defaultModel: 'deepseek-flash'(若存在，否则首个), models: [{ key, displayName, coefficientMilli, maxTokens: null, reasoning: key.contains('pro') || key.contains('reasoner') }] }`；
  - `displayName` 用 `modelKey` 的运营别名（`remark` 为空时映射：`deepseek-flash`→`DeepSeek Flash`，`deepseek-pro`→`DeepSeek Pro`）。
- 控制器：`GET /api/v1/point-card/gateway/config`，`@Api` 文档、Bearer 登录、只读不加 Operlog。

### 2.3 演示数据（服务端）

新增独立脚本 `apps/server/prisma/seed-demo.cjs`（与 `seed-card-orders.cjs` 同风格，直接 Prisma，不写裸 SQL）：
- **幂等判定**：固定邮箱 `35969378@qq.com` 存在 → `exit 2`（打印「演示数据已存在，如需重灌请加 -Reset」）。
- **-Reset**：只删 demo 归属行：固定 `tenantId=100099`、该邮箱 user、卡号 `DEMOPC0001/0002`、供应商 `deepseek-demo` 及其模型、该用户的钱包/流水；再执行导入。
- **导入内容**：
  1. 租户 `100099`（companyName=演示租户，status=0）；
  2. 用户 `35969378@qq.com`（bcrypt 10 轮 `1qaz2WSX`，tenantId=100099，nickName=吴瑞曦的测试号），角色复制开发库 `ruixiwu@163.com` 用户的 `sys_user_role` 行（没有该用户时复制 admin 行）；
  3. 卡批次 `DEMO-PCARD-20261026` + 2 张卡 `DEMOPC0001/0002`（明文 `DEMOCARD-0001/0002`，5000 分/30 天，ACTIVE 已支付）；
  4. 两张卡全部绑定到该用户：钱包 `+10000 积分`，`point_transaction` 每条 `bizType=CARD_ENABLE` 入账（幂等）；
  5. 供应商 `deepseek-demo`（baseUrl `https://api.deepseek.com/v1`，key 取 `DEMO_DEEPSEEK_API_KEY`，缺省回退 `DEEPSEEK_API_KEY`；AES 落库口径同 `seed-card-orders.cjs`）；
  6. 模型 `deepseek-flash → deepseek-chat`（系数 1000，appVisible=true）、`deepseek-pro → deepseek-reasoner`（系数 3000，appVisible=true）、`deepseek-hidden-demo → deepseek-chat`（enabled=false，appVisible=false，用于断言下拉不出现）；
  7. 脚本末尾调用 LiteLLM Admin API `/model/new`（`LITELLM_BASE_URL` + `LITELLM_MASTER_KEY`，容器内默认 `http://autojs6-litellm:4000`）同步三模型；LiteLLM 不可用时只告警，不阻断（目录数据已落库）。
- **不改 seed.ts 基线**：演示数据只属于测试，不进入空库基线。

### 2.4 网络稳定性

- `app/build.gradle.kts`：`server.origin.test` 支持 `-Pbaic.testOrigin` 与 `NOVA_TEST_ORIGIN` 覆盖（优先级：gradle property > 环境变量 > properties 文件）。
- debug 专用 `app/src/debug/res/xml/network_security_config.xml`：`<base-config cleartextTrafficPermitted="true">`（仅 debug 生效；release 继续沿用 main 的 loopback-only 配置）。
- `NovaBAIC/tests/demo/net.ps1`：探测本机 LAN IPv4 → 生成 `-Pbaic.testOrigin=http://<ip>:8080` → 构建/安装 helper `-Install` → `adb reverse tcp:8080 tcp:8080` 兜底 → `curl /api/v1/health/ready` 断言 JSON。
- App「无法连接网络」提示升级：包含当前 `SERVER_ORIGIN`、建议（同一 Wi-Fi / 运行 net.ps1）与「重试」。

### 2.5 客户端向导（只选模型）

`AgentWizardViewModel.kt`：
- 删除 `AgentPreset` 列表与 key 前缀识别；`WizardStep` 收敛为 `MODEL / TUNING / PROMPT`。
- 注入 `AdvisorGateway`；`startForNew/startForEdit` 调 `gateway.config()`：
  - 成功：`models` 填充、默认 `deepseek-flash`（不存在取 `defaultModel`，再退首个）；
  - 失败：`gatewayError` 显示原因（未登录/网络/服务端），并提示「绑定卡密」。
- 保存：`baseUrl = config.baseUrl`（相对路径由 `AccountApi` 侧拼成绝对地址，见下）、`provider = OPENAI_COMPATIBLE`、`apiKey = gateway.cardKey() ?: 编辑态已有 key`。
- 为兼容 `Agent.baseUrl` 必须是绝对地址：`AdvisorGateway.config()` 返回的 `baseUrl` 由 app 实现拼成 `${SERVER_ORIGIN}/llm/v1`（服务端契约面仍回 `/llm/v1`）。

`AgentWizardScreen.kt`：
- `ProviderStep` 改为「选择模型」：卡片列表（label + key），无 key 输入、无 baseUrl 输入、无服务商列表。
- `ConnectionStep` 删除；模型选择与连接状态合并到第一步；底部步骤指示器 3 段。
- 失败提示 `wizard_gateway_error` 带端点与重试按钮。

`ModelCatalog.kt`：新增 `deepseek-pro`（label `DeepSeek Pro`，1M 上下文，maxTokens 64k，reasoning=true）；`deepseek-v4-pro` 保留为兼容别名（`entryFor` 已支持前缀匹配）。

### 2.6 本地演示数据（客户端，debug-only）

新增 `app/src/debug/java/.../floatball/demo/DemoSeedReceiver.kt`（仅 debug 源集，release 不含）：
- Action `com.verlintas.baic2.DEBUG_DEMO_SEED`；extras：`path`（manifest JSON 文件）、`reset`（bool）。
- 通过 Hilt `@AndroidEntryPoint` 注入 `AdvisorDatabase`、`ContactRepository`、`AdvisorPrefs`、`AutoSelectionStore`。
- 导入（幂等）：先按 alias `demo:v1` 查已存在联系人；存在且未 reset → 广播 `RESULT_DEMO_EXISTS`（脚本 exit 2）。
- 写入：
  - 吴瑞曦topwayricky（微信 `com.tencent.mm`，完整连写显示名）：`resolve(createStub=true, bypassTombstone=true)` → `recordChannel` → `updateGoal`（长期目标升职加薪）→ `setContactType(person)` → `setEngagement(proactive)` → `setPrivacyPolicy(handoff)` → `setStageIntent("争取本季度晋升")` → `recordAliasHit("demo:v1")`；
  - `PersonaField`/`style`：用 `ContactRepository.updateStyle`（若存在）写「工作要求严格，喜欢半夜发工作指令」；
  - `selfPersonaDao.upsert(SelfPersonaEntity(tag="demo-me", note="业务骨干/产品经理，长期目标=升职加薪"))`；
  - `proactivePolicyDao.upsert(ProactivePolicyEntity(contactId, enabled=1, dailyCap=3, minGapHours=0, quietStart="23:00", quietEnd="07:00", ...))`；
  - `advisorPrefs.addAutoWhitelistContact(id)` + `autoSelectionStore.save(Selection(targets=[Target(id,"com.tencent.mm","吴瑞曦topwayricky","吴瑞曦topwayricky")]))`；
  - 对照联系人：企业微信「吴瑞曦」（同一领导的企微号，非白名单）、张敏（HR，非白名单）、1 个群 `demo 项目群`（isGroup=true）、1 个陌生联系人；同样打 `demo:v1` alias。
- 重置：`contactAliasDao.listByAlias("demo:v1")` → 逐个 `contactProfileDao.setDeleted` + 删除其 `chat_channels` 行 + `proactivePolicyDao.delete(contactId)`（政策表以 contactId 为主键，不随档案软删消失）；`selfPersonaDao` 删除 tag `demo-me`；`autoSelectionStore.clear()`；`advisorPrefs.removeAutoWhitelistContact`。
- RPA 冒烟（debug-only 附加入口）：extras `navPkg`/`navName` → 直接调用 `ImChatNavigator.openChat`（与画像页「关联会话」同一链路）；成功 result=0，失败 result=1 且 data=`nav_fail:REASON_*`。用于自动化验证模糊匹配与「最靠前会话」选择，不写任何数据。
- RPA 双通道验收口径：①a11y（节点通道，模糊 token + 最靠上节点）；②无 a11y（Shizuku+OCR，日志前缀 `[v2]`）。视觉模式按 IM 用 `ImPages(mainMarkers/searchMarkers/chatMarkers)` 判别页面（微信 `LauncherUI/FTSMainUI/ChattingUI`，企微 `WwMainActivity/GlobalSearchActivityV2/MessageListActivity`）；`backToMainList` 每轮校验前台包名，退到主列表即停、已离开则 `relaunchByShell`；结果条目用 OCR 命中行中心点击（固定比例兜底）。

`app/src/main/java/.../MainActivity.kt`（`[upstream-touch]` 一行）：`accountManager.debugInjectFromIntent(this, intent)` 扩展读取 `debugCardKey`/`debugModel`。

`AccountManager.kt`：
- `debugInjectFromIntent` 解析 `debugCardKey` → 启动内部协程 `applyGateway(cardKey, model)`（默认 `deepseek-flash`）；
- `applyGateway(cardKey, model = "deepseek-flash")` 写 `agent.copy(baseUrl = api.gatewayBaseUrl(), model = model)` 并落卡密；
- `AccountPrefs` 新增 `loadCardKey()`。

### 2.7 脚本目录（NovaBAIC/tests/demo）

```
tests/demo/
├── README.md            # 使用方法/前置/故障排查/最稳定连接结论
├── manifest.json        # 服务端+客户端演示数据唯一事实源
├── .env.demo.example   # DEMO_SEED_TOKEN / DEMO_DEEPSEEK_API_KEY / NOVA_TEST_ORIGIN
├── net.ps1              # 探测 LAN、设置构建参数、adb reverse 兜底、健康检查
├── seed-server.ps1      # 调 docker exec node prisma/seed-demo.cjs，已存在 exit 2
├── seed-client.ps1      # adb push manifest + am broadcast 导入（已存在 exit 2）
├── reset.ps1            # 服务端 -Reset + 客户端 reset 广播
├── assert.ps1           # 账号/卡密/模型接口/LLM 网关/App UI 断言
└── run.ps1              # net → seed-server → seed-client → 免登录启动 → assert
```

失败语义：`$ErrorActionPreference='Stop'`；服务端脚本 exit 2 → `seed-server.ps1` 原样 exit 2；任何步骤失败立即终止。

## 3. 验证（真机 449c98301422）

1. `.\tests\demo\run.ps1` 全链路（首次导入）。
2. 第二次运行 `seed-server.ps1` / `seed-client.ps1` 必须 exit 2。
3. `.\tests\demo\reset.ps1` 后重跑成功。
4. `adb shell am start ... --es debugToken ... --es debugCardKey DEMOCARD-0001 --es debugModel deepseek-flash` 启动。
5. App AI 设置：无 key/无地址/无「好参谋云端 AI」，下拉仅 `DeepSeek Flash`/`DeepSeek Pro`。
6. `curl -H "Authorization: Bearer DEMOCARD-0001" http://<host>:8080/llm/v1/chat/completions` 对 `deepseek-flash`、`deepseek-pro` 均 200。
7. 主页三键（单次/连续/自动）可点，自动弹层正常。
8. 服务端 `gateway/config`：App 未登录 401；登录后只含两模型、无 key/上游地址。

## 4. 非目标

- 不做历史数据迁移；不改生产数据。
- 不改 LiteLLM 部署/代理拓扑；不改 32 种设备 WS 协议。
- 不把演示数据写进 `seed.ts` 基线。
