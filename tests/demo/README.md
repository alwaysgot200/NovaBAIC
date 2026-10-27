# 演示数据与真机联调（NovaBAIC/tests/demo）

一条命令灌入「服务端账号/卡密/LLM 模型」与「客户端联系人画像/策略/自动范围」，可重复、可重置。
服务端数据与客户端数据都只认本目录 `manifest.json` 作为唯一事实源。

## 0. 一次性准备

```powershell
cd d:\work\autojs6_Management\NovaBAIC\tests\demo
Copy-Item .env.demo.example .env.demo
# 编辑 .env.demo：填 DEMO_DEEPSEEK_API_KEY（上游 DeepSeek key，不要提交）
# ADB_SERIAL 可选：多设备时用于指定真机；单设备可留空（自动取第一台）
```

前提：dev 容器已启动（`build-server.ps1 dev`）、真机已 `adb devices` 在线、安装的是 **debug 包**。

## 1. 常用命令

```powershell
# 选择连接方式并验证（推荐：局域网；USB 自动化可用 reverse；两者都保留兜底）
.\net.ps1 -Mode Auto
.\net.ps1 -Mode Auto -Install     # 顺带构建并安装 debug 包（注入检测到的 testOrigin）

# 灌服务端（已存在会报错并 exit 2）
.\seed-server.ps1
.\seed-server.ps1 -Reset

# 灌客户端（已存在会报错并 exit 2）
.\seed-client.ps1
.\seed-client.ps1 -Reset

# 全链路：网络 → 导入 → API 登录 → 免登录启动 App → 断言
.\run.ps1
.\run.ps1 -Build            # 含构建安装
.\run.ps1 -Reset            # 先重置再导入

# 只断言
.\assert.ps1 -Origin http://192.168.x.x:8080 -Token <token> -CardKey sk-DEMOCARD-0001

# RPA 冒烟（debug 包；走与画像页「关联会话」相同的 ImChatNavigator.openChat）
# 通道 1：a11y —— 手机设置里开启好参谋的无障碍服务
adb shell am broadcast -a com.verlintas.baic2.DEBUG_DEMO_SEED `
  -n com.verlintas.baic2/.floatball.demo.DemoSeedReceiver `
  --es navPkg com.tencent.mm --es navName 吴瑞曦topwayricky
adb shell am broadcast -a com.verlintas.baic2.DEBUG_DEMO_SEED `
  -n com.verlintas.baic2/.floatball.demo.DemoSeedReceiver `
  --es navPkg com.tencent.wework --es navName 吴瑞曦

# 通道 2：无 a11y —— 关闭无障碍，保留 Shizuku 授权（日志前缀 [v2]，screencap+OCR）
# 结果：result=0 已进入聊天页；result=1 时 data 为 nav_fail:REASON_*（a11y 未开/Shizuku 未授权/联系人未搜到）
# logcat 过滤：adb logcat -s ImChatNavigator:*（openChat 首行 a11y=true/false 即本次通道）
```

二次导入必须失败（防止静默覆盖）：

```powershell
.\seed-server.ps1   # exit 2：演示数据已存在...
.\seed-client.ps1   # exit 2：演示数据已存在...
```

## 2. 灌入了什么

服务端（`prisma/seed-demo.cjs`）：

- 租户 `100099`；账号 `35969378@qq.com / 1qaz2WSX`（绑定本租户自己的 `tenant_admin`，菜单克隆自模板，不跨租户引用 `role_id`）；
- 卡密 `sk-DEMOCARD-0001/0002`（`sk-` 前缀是 LiteLLM 虚拟 key 的硬性要求；各 5000 分 / 30 天，已绑定，钱包 10000 积分）；
- 供应商 `deepseek-demo`（上游 key 取 `DEMO_DEEPSEEK_API_KEY`）；
- 模型 `deepseek-flash`（系数 1.0）、`deepseek-pro`（系数 3.0），并同步进 LiteLLM；
- 隐藏模型 `deepseek-hidden-demo`（enabled=false，用于断言 App 模型卡片列表不出现）。

客户端（`app/src/debug/.../DemoSeedReceiver.kt`，仅 debug 包）：

- 吴瑞曦topwayricky（微信，完整连写显示名，RPA 搜索键就是这个字符串）：领导画像、严格/半夜发指令、长期目标升职加薪、主动维持、handoff；
- 自画像 `demo-me`；主动政策（dailyCap=3、minGapHours=0、静默 23:00–07:00）；自动范围只含吴瑞曦topwayricky；
- 对照联系人：企业微信「吴瑞曦」（同一领导的企微号）、张敏（HR）、`demo 项目群`（群排除）、陌生客户（非白名单）。

重置只删 `demo:v1` 标记行与固定 demo 数据，不碰真实数据。

## 3. 免手输登录

`run.ps1` 用真实登录接口换 token（验证码答案从 Redis 读），再注入：

```powershell
adb shell am start -n com.verlintas.baic2/.MainActivity \
  --es debugToken <access_token> \
  --es debugRefreshToken <refresh_token> \
  --es debugUsername 35969378@qq.com \
  --es debugCardKey sk-DEMOCARD-0001 \
  --es debugModel deepseek-flash
```

调试包才会放行（`AccountApi.TEST_LOGIN_ENABLED` 仅在非 https 测试源为 true），生产包无入口。

## 4. 连接服务器：最稳定方式

| 方式 | 稳定性 | 适用 |
| --- | --- | --- |
| **局域网 IP（推荐日常）** | 高 | 手机与 PC 同一 Wi-Fi；`net.ps1` 自动探测并写入构建；需防火墙放行 8080、路由器给 PC 固定 DHCP |
| adb reverse（推荐自动化） | 中 | USB/无线 adb 在线；`net.ps1` 每次连接后重设 `adb reverse tcp:8080 tcp:8080` |
| 公网 HTTPS 测试域 | 最高 | 异地/无 adb；与生产同构，无需 debug 明文白名单 |

结论：**日常真机优先局域网 IP；本机 Windows Docker Desktop 的 8080 只发布到 127.0.0.1，`net.ps1 -Mode Auto` 探测失败会自动回退 adb reverse（当前最稳）；有公网条件后迁移 HTTPS 测试域。**
debug 包已允许明文 HTTP（`app/src/debug/res/xml/network_security_config.xml`），release 仍只放行 loopback。

## 5. 故障排查

- `健康检查失败`：确认 `build-server.ps1 dev` 在跑；`curl http://<ip>:8080/api/v1/health/ready`；Windows 防火墙放行 8080；手机与 PC 同一 Wi-Fi（访客网络隔离会失败）。
- `run-as` 失败：安装的是 release 包，换 debug 包。
- `result=2`：演示数据已存在，用 `.\reset.ps1`。
- LiteLLM 调用 401：卡密未在网关注册；重跑 `.\seed-server.ps1 -Reset` 或检查 `LITELLM_MASTER_KEY`。
- LiteLLM 调用 402：卡积分用完，换 `sk-DEMOCARD-0002`。

## 6. 最近一次实测（2026-10-26）

- `.\run.ps1 -Reset` 全绿：网络→服务端导入→客户端导入→API 登录→注入启动 App→断言全部通过；
- 紧接着 `.\run.ps1` 返回 exit 2（演示数据已存在，未静默覆盖）；
- 断言结果：`gateway/config` 仅返回 `deepseek-flash/deepseek-pro`（无 key/上游地址），两个模型经卡密直调均成功；
- 客户端 `advisor.db` 复核：5 条联系人画像、1 条 `demo-me` 自画像、吴瑞曦主动政策（dailyCap=3、minGapHours=0、静默 23:00–07:00）；
- 服务端角色复核：`role_tenant=100099`、`role_key=tenant_admin`、菜单 95 项；`getRouters` 返回 200 且包含 `/market/ai-usage`。
- 2026-10-26 晚（联系人修正 `吴瑞曦topwayricky` + 企微 `吴瑞曦`）复验：存活画像 5 条、`proactive_policies.contactId=6`、旧档 `chat_channels`/政策行已清空、自动范围仅 `吴瑞曦topwayricky`；RPA 冒烟 **a11y 与无 a11y 两种通道 × 微信/企微 均 result 0**（微信 `ChattingUI`、企微 `MessageListActivity`，⑧ `entered/nameHit=true`）。
- 无 a11y 企微首轮失败（`im_nav_launch_timeout`）已修复：视觉模式按 IM 判别主列表/搜索页/会话页（企微 `WwMainActivity`/`GlobalSearchActivityV2`/`MessageListActivity`），back 不再盲退越过 App，结果条目改用 OCR 命中行中心点击。

## 7. 自动档复测（2026-10-27）

前置：debug 包、已跑 `seed-client.ps1`、微信已登录、Shizuku/无障碍按当前通道就绪。
`--ez auto` 走与主页「自动」键相同的 `ACTION_AUTO_FROM_MAIN`；`AutoSelectionStore` 的选择集来自 demo 数据（吴瑞曦topwayricky × 微信）。

```powershell
# 快速档启动：工作 1 分钟 / 停歇 1 分钟 / 发送前等 1~3 秒
adb shell am broadcast -a com.verlintas.baic2.DEBUG_DEMO_SEED `
  -n com.verlintas.baic2/.floatball.demo.DemoSeedReceiver `
  --es pace "1-3" --es executor auto --ei work 60 --ei rest 60 `
  --ez auto true

# 模拟一条来信（走正式后台回合管线；结果码 0 仅表示已受理）
adb shell am broadcast -a com.verlintas.baic2.DEBUG_DEMO_SEED `
  -n com.verlintas.baic2/.floatball.demo.DemoSeedReceiver `
  --es say "吴总：自动炒股 v1 的回测数据发我看看" `
  --es sayContact 吴瑞曦topwayricky

# 停止
adb shell am broadcast -a com.verlintas.baic2.DEBUG_DEMO_SEED `
  -n com.verlintas.baic2/.floatball.demo.DemoSeedReceiver --ez stopAuto true

# 分段耗时/发送出口
adb logcat -d -s AutoTiming:* | Select-String "auto_timing:"
adb logcat -d | Select-String "out_sent|out_degraded|send_button_not_found|wrong_conversation"
```

参数口径：

- `--es pace "min-max"`：秒；快速 `1-3`、标准 `3-10`、拟人 `8-90`、从容 `30-180`（默认拟人）。
- `--ei work / --ei rest`：秒；合法档 0/60/300/600/900/1200，`rest=0` 不停歇，默认 60/60。
- `--es executor a11y|shizuku|auto`：发送通道偏好，默认 `auto`。
- `--es say` 会写入 USER 消息并触发 LLM 分析/成稿；回合可能十几秒，操作后看台账与 logcat，不看广播返回。

启动/稳定性验收：三入口 2s 内红球+脉冲、30s 内进入 AUTO、失败有状态/日志；完整矩阵见
`docs/2026-10-27-自动档稳定性与回复节奏诊断方案.md` §7。

