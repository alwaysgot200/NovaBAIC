# Tasks：「关联会话」RPA 双模化执行清单

> 对应 spec.md；批准后按序执行，每项完成即勾。

## T1 模式 1（a11y 开）复测 ×3 轮 [完成：R1 主列表 24s / R2 冷启动 31s（⑤ back 自愈）/ R3 主列表 25s，均 entered=true + ChattingUI + 截图 r1-r3_mode1.png]
- [x] R1：微信停在聊天页初始态 → 导航链触发 → logcat ①-⑧ 全绿 + ChattingUI + 截图
- [x] R2：微信 force-stop（冷启动态）→ 同上验证
- [x] R3：微信停在主列表 → 同上验证
- 结论：三轮全过 = 模式 1 稳定（冷启动场景 ⑤ back 循环把微信退后台两次但 relaunch 自愈）

## T2 实现模式 2（改 ImChatNavigator.kt + strings.xml）[完成]
- [x] 构造参数追加 frameSource/shizukuGate/imageMatcher；companion 追加 REASON_SHIZUKU_OFF/REASON_PROJECTION_UNAVAILABLE
- [x] openChat 入口三分支（a11y 开→既有；a11y 关+Shizuku→模式 2；都无→REASON_SHIZUKU_OFF）
- [x] ensureProjection()（isEnable→appops grant→request(false)→验证）
- [x] openChatByVision()：③拉起→⑤找图/坐标 tap 搜索→⑥-0 tap 输入框→⑥-1 paste→⑦坐标 tap→⑧投影帧 OCR
- [x] chatPageVisibleByOcr 帧来源参数化（含 height 帧高兜底）+ bitmapToPng
- [x] toastResult 新原因码映射 + strings.xml 两条文案（float_im_nav_shizuku_off/projection_fail）
- [x] `./gradlew :app:assembleDebug` 编译过 + license 门禁过（tr -d '\r' 管道方案）+ 装机成功

## T3 模式 2（a11y 关）反复测试 ×3 轮 [完成]
- [x] R1：冷通道（装机后首触发）——微信停在目标聊天页初始态 → ④' 直达 + ⑧-ocr lines=4（screencap 清晰帧生效），全链 4.6s
- [x] R2：微信主列表初始态全链——⑤ 找图 poll=1 命中新模板（score 0.916）+ ⑦-0 rendered=true + ⑦ tap 结果条目 + ⑧ entered=true，全链 12.3s，ChattingUI + 截图确认（修复项全部生效）
- [x] R3：微信 force-stop 冷启动 + 通道热复用——首测暴露冷启动误匹配（⑤ 假命中点空 → FTS 未开而链路走空；⑧ 类名门对 LauncherUI 内嵌 fragment 聊天页假阴性）→ 修复三件（⑤ tap 后 FTS 验证+归位重试 / ⑦-0 结果区过滤搜索框 / ⑧ 双宿主门放宽）→ 重测全绿：真图标 poll=1 命中 + ⑦-0 过滤生效 + ⑧ entered=true lines=4，全链 15.8s（含冷启动 5s），ChattingUI + 截图确认
- [x] 每轮：[v2] 日志全绿 + ChattingUI + 截图肉眼确认

## T4 混合回归 [完成]
- [x] 模式 2 测完后开 a11y → 同导航链触发模式 1 → 全绿（25.1s：⑤ back 自愈归位 → ratio 兜底 → ⑦ hop=1 → ⑧-ocr nameHit=true lines=4 entered=true + ChattingUI）
- [x] logcat 确认门面分支按 a11y 状态切换（openChat a11y=true 走模式 1 无 [v2] 日志；此前三轮 a11y=false 均走 [v2] vision mode）

## T5 收尾 [完成]
- [x] 诊断日志审查（⑧ v2_debug_frame.png 转储块已删；步骤编号日志保留）
- [x] 全程 logcat 无 FATAL/AndroidRuntime（含三轮模式 2 + T4 + 最终冒烟）
- [x] tests/tempImg/ 清理（删除 364 个中间产物，保留 8 张最终证据图：r1-r3_mode1 / r2_end / r3_end+r3_now / r3b_end / t4_end）
- [x] git commit（0fa5945 feat: 关联会话 RPA 模式 2 补齐——Shizuku screencap 清晰帧三件套 + 双模真机验证全绿）
