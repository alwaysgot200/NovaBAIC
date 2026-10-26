# 注册页体验改造 + 产品角色预置化方案

- 日期：2026-10-25
- 状态：**已实施并验证**（2026-10-25 真机注册→登录闭环 + DB 克隆校验 + PC 回归全过）
- 依据：`docs/2026-10-24-商业化方案-注册充值与LiteLLM统一网关.md` v2.3（§3.1-3.3 产品角色映射、§5.2 注册链路、§5.3 RBAC 落位、§9.3 C1 seed/基线门禁）
- 触发反馈（用户原话要点）：
  1. 注册页输入框「变成了从右向左显示」（RTL 异常）；
  2. 「header 区为灰白，body 为黑色」，整体排版不美观；
  3. 注册与 PC 端有区别：注册后角色应为产品角色（好参谋），且这些角色应是**预置好的种子数据**。

---

## 1. 现状与根因（调研结论，只读核实）

### 1.1 注册角色分流断在前端（核心功能差距）

服务端**已经**支持产品分流（`apps/server/src/module/main/auth.controller.ts` L233-297）：

- `productId=haocanmou/autojs6` → `ProductRoleService.provisionProductTenant`（只绑产品角色，**不绑** tenant_admin/employee，**跳过激活**，`activationRequired: false`，返回「注册成功，账号已开通，可直接登录使用」）；
- 缺省/`qunkong` → 现状链路（tenant_admin + 激活邮件）。

但前端 `apps/web/src/views/_builtin/login/modules/register.vue` 的表单 model 与 `handleSubmit` 提交体**均不含 productId**——App 带着 `?productId=haocanmou` 打开注册页，参数从未传给后端，注册实际走了现状链路：

- 角色 = tenant_admin（不是 haocanmou）；
- 被激活邮件流程卡住（好参谋 App 用户「注册→回登录页即用」被打断）；
- 成功页 UI 写死「注册成功，请激活账号…48 小时内激活」，与产品注册「注册即用」口径冲突。

### 1.2 产品角色是注册时动态创建（与「预置种子数据」诉求有差距）

`product-role.service.ts` 的 `ensureProductRole` 在注册/开通时按 `tenantId+roleKey` **动态 create** `sys_role` 行并写 `sys_role_menu`。产品角色是「租户内运行时行」（与 tenant_admin/employee 同构，每个租户一行）。用户要求：角色定义与菜单授权应为**预置好的种子数据**，注册时只做分配，不凭空造角色。

### 1.3 注册页排版/主题问题（截图证据 `tests/tempImg/smoke24-register.png`）

- **两张皮**：App 顶栏（AdvisorPage）浅色灰白，web 页面 body 黑色。根因：web 主题 `themeScheme: 'auto'` 跟随系统 `prefers-color-scheme`（`store/modules/theme/index.ts` L34-39），手机深色模式下登录/注册页整体变黑。
- **移动端排版**：登录/注册页是桌面布局塞进手机 WebView——表单容器 `h-630px` 固定高、必填红星用「可见/不可见星号」占位技巧与输入框错位、联系电话 placeholder 被截断、验证码行拥挤、web 页头（logo+主题/语言切换）与 App 顶栏功能重复。
- **输入倒序**（用户确认现象）：敲入 `abcd` 显示 `dcba`——字符逐个插到行首，是**插入点异常**，不是布局镜像。已排除嫌疑：App 侧 WebView 配置干净（`LoginScreen.kt` L399-404 仅 JS/DOM Storage）；web 源码全局无 `direction: rtl`/`bidi-override` 样式。候选根因：
  1. **IME × WebView 组合**：特定输入法（小米/搜狗键盘）经 `InputConnection.commitText` 每次插到位置 0；
  2. **页面输入处理**：NInput/v-model 重渲染把光标复位到行首（每次按键后 next char 前插）；
  3. 设备开发者选项「强制 RTL」或隐性 `direction` 异常。

  **实施第一步真机诊断定根因**（诊断阶梯见 2.1.d），按结论对因修复。

---

## 2. 改造方案

### 2.1 前端注册页（autojs6_Management `apps/web`）

| # | 改动 | 说明 |
| --- | --- | --- |
| a | **productId 接线** | `register.vue` 读取 URL query `productId`，并入 `fetchAuthRegisterCompany` 提交体；缺失/非法**不传**（服务端回退现状链路，兼容现有网页注册）。App 端 `AccountApi.registerUrl()` 已带 `productId=haocanmou`，无需改参数名 |
| b | **成功页按 `activationRequired` 分流** | 产品注册（`activationRequired=false`）：「注册成功，账号已开通」+「去登录」按钮回登录页；现状链路维持现有激活邮件提示 UI |
| c | **主题统一（强制浅色，App 场景）** | App 打开的 URL 追加 `theme=light`；登录/注册页读取该参数时锁定 `themeScheme='light'` 并隐藏主题切换按钮。PC 无参数行为**完全不变**（仍可切暗色） |
| d | **输入倒序修复（先诊断后对因）** | 诊断阶梯（真机，证据图进 `tests/tempImg/`）：① 同一 WebView 载极简页 `<input>`（data: URL）敲 `abcd`，区分容器/IME 层 vs 页面层；② 手机浏览器直接开注册页对比；③ 换输入法（小米键盘/其他）对比；④ `evaluateJavascript` 取 input computed `direction`/`unicode-bidi`。对因修复（按序兜底）：a) CSS 层——表单容器与全部 `NInput` 显式 `dir="ltr"` + `direction: ltr; unicode-bidi: isolate; text-align: left`；b) 页面层——修输入处理（光标复位/重渲染问题）；c) 容器层——App WebView 兜底（注入强制 LTR 样式 / 换输入法引导）；d) 若 IME 特定且无解，向用户报因并给操作指引（禁静默失效） |
| e | **移动端排版美化** | 卡片式表单容器（用户偏好卡片风格）、必填 `*` 与输入框对齐规范（去掉不可见星号技巧，改标准必填标记排布）、缩短 placeholder 修复截断、验证码行改两栏合理比例、主按钮全宽、间距统一；桌面端不回退 |
| f | 小项 | App 场景隐藏 web 页头的主题/语言切换（与 App 顶栏重复）；productId 场景副标题/文案按产品口径微调（如「注册好参谋账号」） |

### 2.2 产品角色预置化（种子数据）

**推荐形态：「基线种子模板 + 注册时克隆到租户」**（决策点 D2，见 §4）：

1. **预置模板（种子数据）**：`sys_role` 新增 3 行产品角色模板——固定主键段 `9001/9002/9003`，挂平台租户 `000000`，`roleKey = haocanmou / autojs6 / qunkong`，`PROTECTED_ROLE_KEYS` 已含这三个 key（租户侧不可改名/改菜单/删除）；同时写 `sys_role_menu` 模板授权（菜单集合与 `product-catalog.constants.ts` 现有集合一一对应：haocanmou=订单管理树、autojs6=+市场论坛树、qunkong=白名单全集）。
2. **落位=数据迁移，不是 seed.ts**：`seed.ts` 仅空库执行，存量库（含 dev/生产）拿不到；且 `check-db-baseline` 门禁规定「迁移与 seed 持有的固定 id 零重叠，一条基线行只能有一个归属」。故模板行以**数据迁移 SQL** 持有（`prisma/migrations` 新增，稳定主键维护惯例），migrate deploy 后新老库一致预置。
3. **注册/开通只克隆不造角色**：`ensureProductRole` 改为「查模板（忽略租户过滤）→ 在目标租户克隆角色行 + 复制 `sys_role_menu`」；模板缺失 → 明确抛错 fail-fast（配置门禁），不再凭空 create；幂等语义保留（已存在租户内行则复用并补齐授权）。
4. **基线门禁扩展**：`check-db-baseline.cjs` 新增校验——`sys_role` 固定 id 迁移/seed 零重叠 + 3 个产品角色模板存在且菜单集合与常量一致。
5. **不动**：`admin / tenant_admin / employee` 内置角色、租户白名单 `TENANT_ALLOWED_MENU_IDS`、菜单体系。

**备选形态 D2-B（不推荐）**：产品角色直接用全局种子行、注册时仅绑 `sysUserRole` 不克隆。优点是角色行即种子行；缺点是角色属平台租户，租户侧角色列表/用户管理的角色名解析等按 `tenantId` 过滤的既有查询需逐处放开租户过滤，回归面大。

### 2.3 激活口径（对齐商业化方案 §5.2）

产品注册（带 productId）**注册即用**——服务端已实现（跳过激活邮件、`activationRequired: false`），前端把成功页/UI 文案对齐即可；现状链路（无 productId）维持激活邮件流程不变。

---

## 3. 影响面与不动清单

| 仓库 | 改动文件 | 不动 |
| --- | --- | --- |
| autojs6_Management | `apps/web/.../login/modules/register.vue`、登录页主题参数处理（theme store/shared 小改）、`prisma/migrations/*`（新增数据迁移）、`product-role.service.ts`、`scripts/check-db-baseline.cjs` | 菜单体系、白名单、内置角色、现有网页注册行为 |
| NovaBAIC（App） | `AccountApi.registerUrl()` 追加 `theme=light`（1 行）+ `LoginScreen.kt` WebView 设置兜底（如 RTL 复现指向 WebView 层） | 上游模块零触碰 |

---

## 4. 决策点

| # | 问题 | 裁决（用户确认） |
| --- | --- | --- |
| D1 | 注册页主题口径 | **已确认：App 场景强制浅色统一**（与 App 白色顶栏一套商业浅色观感），PC 不受影响 |
| D2 | 产品角色预置形态 | **已确认：种子模板 + 注册克隆**（§2.2） |
| D3 | 输入倒序根因（敲 `abcd` 显示 `dcba`，用户确认现象） | 现象=插入点异常；根因按 §2.1.d 诊断阶梯真机定位（IME×WebView / 页面输入处理 / RTL 强制），对因修复 |

---

## 5. 验证计划

1. `pnpm typecheck && pnpm lint` + `build-server.ps1 dev` 全绿（含 db-baseline 门禁）。
2. DB 验证：`sys_role` 存在 3 行模板（9001/9002/9003）+ `sys_role_menu` 模板授权；注册 haocanmou 后新租户出现克隆角色行、`sysUserRole` 绑定克隆角色、**无** tenant_admin 绑定。
3. 真机冒烟（截图进 `tests/tempImg/`）：
   - App 打开注册页：浅色统一、排版美观；
   - 输入校验：敲 `abcd` 显示 `abcd`（顺序正确，逐字段验证）；
   - 填表注册（productId=haocanmou）→ 成功页「注册成功，账号已开通」→ 回登录页立即登录成功；
   - 登录后菜单 = 订单管理（haocanmou 角色菜单），无系统管理/群控管理泄漏。
4. PC 端回归：不带 productId 网页注册行为不变（激活邮件流程）。
5. 冒烟链路 + logcat 无崩溃（App 侧如有改动）。

---

## 6. 2026-10-25 二次修订：App 注册页改原生实现（替代 WebView）

### 6.1 触发反馈（真机截图）

1. 顶栏下两行文案（「注册后回到本登录页即可」「帮您发群控」）属于冗余，要求去掉并整体美化；
2. WebView 输入框字符**从行首倒插**（邮箱 `qq.com@87396953`、密码同理），昵称输入框偶发正常——`register.vue` 的 `dir=ltr` CSS 兜底无效；
3. 软键盘覆盖下方输入框导致无法输入/删除，且该 WebView 页面**不允许系统截图**；用户明确要求「不要用 WebView，实现同样的功能效果（要有验证码）」。

### 6.2 决策与口径（用户确认）

| # | 决策 | 说明 |
| --- | --- | --- |
| D4 | **App 注册页改原生 Compose**，删除 WebView 注册容器 | 倒插根因在 WebView × 输入法层，客户端无法可靠修复；原生输入框天然无此问题 |
| D5 | 注册**保留图形验证码** | 直接用 `/api/v1/auth/code`，与登录页同一渲染/刷新管线；服务端 `sys.account.captchaEnabled=true` |
| D6 | 登录**保留图形验证码** | 服务端 `@Captcha()` 全局强制（`sys.account.captchaEnabled=true`），客户端去掉会直接登录失败；用户确认保留 |
| D7 | 注册表单**精简四字段** | 邮箱账号、密码、确认密码、图形验证码；昵称/企业名称/联系电话由服务端按邮箱前缀生成默认值（不改服务端 DTO 默认规则） |

### 6.3 实现落位（NovaBAIC）

| 文件 | 改动 |
| --- | --- |
| `floatball/account/AccountApi.kt` | 新增 `register()`（`POST /api/v1/auth/register-company`，`productId=haocanmou`，只传 email/password/confirmPassword/code/uuid）；删除 `registerUrl()` |
| `floatball/account/AccountManager.kt` | 新增 `register()` 门面；删除 `registerUrl()` |
| `floatball/account/LoginScreen.kt` | 删除 `RegisterWebViewScreen`；新增 `RegisterScreen`（卡片表单 + 逐字段校验 + 服务端错误原文展示 + 验证码失败自动换图）、`RegisterSuccessCard`（注册即用成功页）、`CaptchaBox`（点击换图）；注册成功回登录页并**预填邮箱**；登录/注册内容区统一 `imePadding()`（配合 Manifest 已存在的 `adjustResize`），软键盘弹出时表单上移、输入框不被遮挡 |
| `res/values/strings.xml` | 新增注册页文案键；删除 `float_account_register_hint` |

### 6.4 不动与验收

- **不动**：`apps/web` PC 网页注册页（`register.vue`）与 productId 接线（PC 端行为不变）；服务端注册/登录安全策略；WebView 仅保留给登录/注册页的**验证码 SVG 图片渲染**（纯展示、禁焦点，无输入场景）。
- **验收**：`assembleDebug` 通过 + license 头检查通过；真机/模拟器：注册页无冗余文案、输入顺序正确（敲 `abcd` 显示 `abcd`）、键盘弹出输入框可见可编辑、点验证码图换图、注册成功→成功页→「立即登录」回登录页且邮箱已预填→登录成功；logcat 无崩溃。

