# 方案：开发环境垃圾清理 + LiteLLM 接入 + 客户端注册修复 + 密码明文 + 测试免登录

日期：2026-10-24。涉及仓库：`d:\work\autojs6_Management`（(1)(2)）、`e:\_android_work\NovaBAIC`（(3)(4)(5)）。
待用户批准后动代码；标注「决策点」处以用户选择为准。

## (1) WSL 旧容器成因 + 启动自动垃圾清理

**为什么有这么多旧容器**（已实查）：
- `autojs6-server` / `autojs6-web-nginx`（Exited 12 天）：生产模拟栈容器。脚本设计是
  `stop`/`dev` 只 `docker stop` 不 `rm`（保秒级重启 + pg 干净落盘）；但 prod-sim 容器
  其实永远不会被 `dev` 复用（`start`/`restart` 一律全删重建），属设计遗留的滞留容器。
- `vigilant_golick` / `great_banach`（Exited 13 天/3 周）：**构建失败残留的匿名容器**
  （镜像 ID 3e0ca1d89a57 / 37e3dcdea119 正是两个悬空镜像）。清理函数
  `Stop-ProjectContainers` 只认 8 个白名单容器名 → 匿名垃圾永远清不掉；
  且 `docker image prune` 不删被容器引用的镜像 → 两个悬空镜像（385MB + 1.15GB）
  也因此回收不掉。

**方案**：`build-server.ps1` 新增 `Cleanup-Garbage`，在 `start / restart / dev / fresh / build`
开头自动执行，并新增 `clean` 命令手动触发：
1. 删除**非白名单的已停止容器**（含匿名构建残留）；白名单复用集
   {pg、redis、dev-server、dev-docs、dev-web、dev-nginx} 停止态保留（dev 秒级重启依赖）。
2. 删除 prod-sim 滞留容器 `autojs6-server` / `autojs6-web-nginx`（反正 start/restart 全删重建）。
3. 容器清完后 `docker image prune -f` 回收悬空镜像（此时不再被容器占用）。
4. 运行中的容器一律不碰；数据卷一律不碰。

- 决策点 A：清理范围——「仅项目相关+匿名残留」（推荐，其他项目手建的停止容器不动）
  vs「所有非白名单停止容器全清」（docker ps -a 最干净，但可能误删其他项目手工容器）。

## (2) dev 栈接入 docker 版 LiteLLM

生产侧已有完整参考（`RefConf/hk/docker-compose.yml` 的 `autojs6-litellm` +
`RefConf/hk/litellm/config.yaml`，STORE_MODEL_IN_DB、独立 `litellm` 库、master key 鉴权、
nginx `/llm/` 剥前缀转发）。本地 dev 栈缺整个环节。

**方案**（与生产同构，复用优先）：
1. `RefConf/dev/docker-compose.local.yml` 新增 `litellm` 服务（dev 与 prod-sim 共用，
   无 profile，同 pg/redis）：镜像 `ghcr.io/berriai/litellm:main-latest`（生产同款 pin 摘要），
   挂载复用 `RefConf/hk/litellm/config.yaml`（静态通用设置，零复制零漂移），
   `DATABASE_URL=...@nest-admin-pg:5432/litellm`、`STORE_MODEL_IN_DB=True`、
   `LITELLM_MASTER_KEY=sk-local-dev-master`（本地固定值），healthcheck 打 `/health/liveliness`。
2. `build-server.ps1` 启动前幂等建库：`docker exec nest-admin-pg` 检测/创建 `litellm` 库
   （prod 的 deploy.sh 幂等建库等价物）。
3. `RefConf/dev/nginx-dev.conf` + `nginx-prod-sim.conf` 各加 `location ^~ /llm/`
   （剥前缀 → `autojs6-litellm:4000`，照抄生产 `autojs6.https.conf` 的写法）。
   **当前 `/llm/` 会被 VitePress 兜底吃掉（假 200），必须补**。
4. dev/prod-sim 的 server 环境变量加 `LITELLM_BASE_URL=http://autojs6-litellm:4000`、
   `LITELLM_MASTER_KEY`（同 litellm 容器值）。
5. `build-server.ps1` 的 logs/status/help 补 litellm 条目；白名单容器加 `autojs6-dev-litellm`。

- 决策点 B：镜像拉取源——WSL 直连 ghcr.io（生产同款，可能慢/被墙）vs 国内可拉的镜像源。

## (3) 客户端注册修复（根因已定位）

**根因**（实测 curl）：测试环境 `SERVER_ORIGIN=http://127.0.0.1:8081` 是 **NestJS 直连口**，
注册页 `${origin}/admin/login/register?...` → **404**（8081 无 SPA）；而 8080（nginx 统一入口）
返回 200。生产 `https://autojs6.80fafa.com` 是单域名同时出 SPA+API+`/llm/`，测试环境 8081 拓扑
与生产不同构，是问题总根源（同理 `gatewayBaseUrl=origin+/llm/v1` 在 8081 也是 404）。

**方案**：测试环境改为与生产同构的**单统一入口**：
1. `server-config.properties`：`server.origin.test=http://127.0.0.1:8080`（nginx 统一入口，
   页面/API/`/llm/` 全通）。
2. 联调时 `adb reverse tcp:8080 tcp:8080`（替代现在的 8081；脚本/文档同步改）。
3. WebView 注册页即通，注册后回登录页登录；无需改注册逻辑本身。

- 决策点 C：确认改 8080 统一入口（推荐，一并解决 /llm/ 与注册）vs 保持 8081、仅注册页
  单独走 8080（要加第二 origin 常量 + 双 reverse，且 /llm/ 仍不通，不推荐）。

## (4) 登录密码框眼睛图标

`LoginScreen.kt` 密码 `OutlinedTextField` 加 `trailingIcon` 眼睛 IconButton：
`VisualTransformation` 在 `PasswordVisualTransformation()` / `VisualTransformation.None` 间切换，
图标 `Icons.Outlined.Visibility / VisibilityOff`；文案 `float_account_password_show/hide` 进
`strings.xml`。小改，直接实施。

## (5) 测试自动化绕过登录（决策点 D）

目的：自动化测试免去手输 admin/admin123+算术验证码。候选：
- **D1（推荐）debug 专用 am start extra 注入会话**：`adb shell am start ...MainActivity
  --es debugToken <access_token> --es debugCardKey <卡密>`，debug 构建读取后直接落
  AccountPrefs 完成登录态；release 构建忽略 extra，无安全影响。自动化脚本先 curl
  `/api/v1/auth/login` 拿 token 再拉起 App。
- **D2 debug 专用 broadcast 接收器**：`adb am broadcast` 注入 token/卡密，App 在后台即完成登录。
- **D3 debug 包登录页放「测试一键登录」按钮**（内置测试账号），点一下过门；无需脚本改造但要手点。
- D1/D2 可组合（token 注入后同一状态机）。

## 验证门槛（提交前）

- `build-server.ps1 dev` 一键起 7 容器（含 litellm）全 healthy；`clean`/启动日志可见垃圾清理回执；
  `/llm/health/liveliness` 经 8080 真实 200（curl 回 JSON，非 VitePress 兜底）；
  `/admin/login/register` 经 8080 200。
- NovaBAIC：license 门禁 + `:app:assembleDebug` + 真机冒烟（注册 WebView 可走通注册表单、
  密码眼睛可切换、免登录注入生效）+ logcat 无 FATAL。
- 不损害既有功能；文案全进 strings.xml；临时截图只进 `tests/tempImg/`。
