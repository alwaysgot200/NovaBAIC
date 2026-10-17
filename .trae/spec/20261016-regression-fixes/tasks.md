# 任务清单 — 20261016 回归修复

- [x] T1 AndroidManifest.xml：FloatCaptureActivity 追加 `android:taskAffinity=""`
- [x] T2 ScreenAdvisor.kt：常量 SHARED_REUSE_SIMILARITY=0.45f；prepareTurn/resolveConversation 传内容；新增 resolveSharedConversation；手动追问延续当前会话
- [x] T3 FloatBallService.kt：finishOneShot 加 askJob=null + refresh 改 post
- [x] T4 FloatBallService.kt：ensureSplit 改 dismissMenuNow()；restoreBall 去掉 split==null 条件
- [x] T5 FloatBallService.kt：runOneShot finally 追加 releaseProjection()
- [x] T9 FloatBallService.kt：分屏面板采集前临时摘窗/采集后挂回（防 OCR 污染，spec §4）
- [x] T10 投影就绪竞态修复（spec §5）：ScreenPerceiver.projectionPerception/quickPerceive 加 awaitReady；ScreenAdvisor.adviseOnce 加 awaitProjection；FloatBallService.runOneShot 加 awaitProjection + finally awaitingConsent 跳过 releaseProjection
- [x] T11 联系人误绑定修复（spec §6）：壳标题过滤（resolveContact L1/L2 + isShellTitle 缓存）+ IncomingCue.speakerOf 内容兜底（allowStub=false）+ ContactDirectory.resolve 加 allowStub 参数 + prepareTurn 手动追问保持当前绑定 + isChatWindow 排除自身包名 + speakerOf 单测（含 URL/时间行过滤）
- [x] T6 单测（:app:testDebugUnitTest）+ 编译（:app:assembleDebug）+ 装机
- [x] T7 模拟器复验 spec §4.3 a–f 全场景（含 T9 修复后 DB 断言：同页重复长按不再新开会话）
- [x] T12 连续模式两项修复（场景 g 发现，spec §8）：①授权看门狗竞态：CONSENT_WATCHDOG_MS 120s→600s，慢速人工授权（119s）时 watchdog 先于结果回传触发 abortRecording+清空桥，授权结果被吞、录制永不启动；②共享会话回看：resolveSharedConversation 原来只比最近 1 个会话，A→B→A 切回原场景误开新会话（conv 14/16 分裂）；新增 ContactHistoryGateway.recentScreenBodies 跨会话回看最近 12 条屏幕消息，命中即回原会话（像素 OCR 漏认联系人的回合也借内容相似找回原会话）
- [x] T8 清理 tests/tempImg 证据，向用户汇报
