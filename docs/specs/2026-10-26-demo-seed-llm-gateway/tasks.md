# Tasks：演示数据导入 + 前后台 LLM 打通（已实施）

- [x] T1 服务端 schema：`LlmModel.appVisible` + 迁移 `20261026120000_add_llm_model_app_visible` + DTO/Service 读写
- [x] T2 服务端接口：`GET /api/v1/point-card/gateway/config`（只回模型清单，无 key/上游地址）
- [x] T3 服务端脚本：`prisma/seed-demo.cjs`（导入/`-Reset`/exit 2/同步 LiteLLM 模型与虚拟 key）
- [x] T4 客户端 core：`AdvisorGateway` + `GatewayConfig/GatewayModel`
- [x] T5 客户端 app：`AccountGateway` + `AccountPrefs.loadCardKey()` + Hilt 绑定
- [x] T6 客户端向导：2 步（模型参数 + 提示词）、模型来自服务端卡片、默认 flash；无 Key/服务商/地址（2026-10-26 模型化需求调整，原 3 步/下拉方案作废）
- [x] T7 客户端模型目录：`ModelCatalog` 增 `deepseek-pro`（保留 v4-pro 兼容）
- [x] T8 客户端 debug：`debugCardKey/debugModel` 注入 + `DemoSeedReceiver`（debug 源集）
- [x] T9 网络：`-Pbaic.testOrigin` / `NOVA_TEST_ORIGIN` 覆盖 + debug 明文 network security config
- [x] T10 脚本：`NovaBAIC/tests/demo/*`（net/seed-server/seed-client/reset/assert/run/manifest/README）
- [x] T11 构建：`assembleDebug` 成功并安装真机
- [x] T12 真机验证：二次导入 exit 2 / reset 重跑 / 模型卡片 / flash+pro 200 / App 内聊天回复 OK
- [x] T14 全链路复核（2026-10-26）：`run.ps1 -Reset` exit 0；修复 `redis-cli -a` 密码警告在 Stop 模式下被当成 NativeCommandError 导致 login 步骤中断（改用 `--no-auth-warning`）；修复演示账号跨租户角色引用（改为租户 100099 自建 `tenant_admin` + 95 菜单，`-Reset` 清理角色行）
- [ ] T13 发版：本轮未发版，生产仍停留在上一 tag（需另行推 `v*` 或手动触发 deploy.yml）
