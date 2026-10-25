# Soundly SDK 2.0.0 验收结论

验收日期：2026-10-25。发布坐标：`com.ideacode:soundly-sdk:2.0.0`。

## 自动化门禁

| 验收项 | 结果 | 证据或边界 |
|---|---|---|
| JVM 单元测试 | 通过 | 49/49；固定 JSON、Markdown、JUnit XML 位于 `baseline/reports/release-2.0.0-2026-10-25/` |
| Android 仪器测试 | 通过 | API 33 模拟器：45 通过、0 失败、2 个 opt-in 长音频用例跳过 |
| Room Migration | 通过 | `2→4`、`3→4` 在 API 33 模拟器及 OPPO PHY110 API 36 实体机均通过；v1 使用声明的 destructive fallback |
| 30 分钟/2 小时流式音频 | 通过 | 两个 opt-in 用例另行执行，帧数、WAV、进度和 PSS 门槛均通过；详见 `LONG_AUDIO_REPORT.md` |
| 后台 2 小时时长边界 | 通过（模拟） | 虚拟 elapsed clock 验证 Long 时长、暂停冻结及前台通知 start/refresh/stop；不是实体机连续录制 |
| 来电/音频焦点/输入设备 | 通过（状态机） | PHONE_CALL、AUDIO_FOCUS_LOST、INPUT_DEVICE_DISCONNECTED、MICROPHONE_UNAVAILABLE 均验证类型化中断和冻结 |
| 存储耗尽 | 通过（故障注入） | 录音中低空间进入 INSUFFICIENT_STORAGE，并持久化恢复进度 |
| 进程终止恢复 | 通过（重建模拟） | 新 manager 读取已持久化 session/marker 并恢复输出；不是操作系统真实杀进程 |
| 取消与重试 | 通过 | 取消清理临时文件；retryable 失败生成新 task 与新 variant |
| 裁剪/多版本/AB 数据 | 通过 | 非破坏裁剪、lineage、边界 marker、多增强版本独立持久化通过 |
| 导出质量策略 | 通过 | PRESERVE_SOURCE、PCM16、AUTOMATIC、冲突策略、MediaStore、分享 URI 均覆盖 |
| Release/R8 | 通过 | SDK release AAR 与 sample-app minified release 均构建成功；稳定 API 未出现访问级别泄漏 |
| AAR/Maven 集成 | 通过 | 发布到本地 Maven；sample-app 通过外部坐标而非 project dependency 完成 R8 Release APK |
| Lint | 通过 | `:soundly-sdk:lintDebug` 成功，无阻断项 |
| 公共 API 兼容检查 | 通过 | release AAR 的 129 个受支持公开字节码类型已冻结到 `api/api-2.0.0.txt` 并 exact diff 通过 |
| API 文档 | 完成 | `API_SURFACE.md` 包含依赖、权限、初始化、录音、恢复、增强、AB、裁剪、导出、生命周期和 1.x 迁移 |
| ProGuard consumer rules | 完成 | 仅保留稳定 API、增强模型、数据库实现和前台服务；移除整个 Room/coroutines 的过宽 keep |

## 实体机结果

OPPO PHY110、Android API 36 已通过 21 个短批次用例：Migration 2 个，以及编辑、增强、导出、分享、MediaStore、存储/隐私 19 个。完整单进程套件运行到后台录音区域时，OPPO Hans/OFreezer 将无前台界面的 instrumentation 进程冻结；第二批启动阶段也再次被冻结。未修改用户设备电池白名单，因此剩余实体机用例不能标为已通过。

## 发布前必须人工执行

以下场景依赖真实系统事件或长时间设备行为，自动故障注入不能替代。全部完成前，`2.0.0` 应视为 release candidate，而不是最终生产签核：

- 在至少一台 Google/AOSP 与一台主流国产 ROM 上真实后台录音 30 分钟和 2 小时，锁屏后检查通知、文件可播放、时长、波形、标记、温升和耗电。
- 真实运营商来电、VoIP 通话、其他 App 抢占麦克风、蓝牙/有线输入拔出，逐项确认中断提示及可恢复性。
- 使用受控测试设备制造真实低存储/写满，确认不产生零字节正式项目、恢复文件可用、清理后可重试。
- 录音中使用系统方式杀进程并重新进入 App，确认恢复入口、marker、时长和文件尾部均正确；同时验证用户 force-stop 后的产品提示。
- 用 44.1/48 kHz、单声道和立体声真实内容执行 30 分钟/2 小时增强，记录峰值 PSS、处理时长、输出音质和分块边界是否有爆音。
- 在 OPPO 上为测试构建临时允许后台运行后重跑完整套件；验收结束后恢复原电池策略。

## 复现命令

```bash
bash ./gradlew :soundly-sdk:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 bash ./gradlew :soundly-sdk:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5554 bash ./gradlew :soundly-sdk:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ideacode.soundly_sdk.editing.StreamingLongAudioStressTest \
  -Pandroid.testInstrumentationRunnerArguments.soundlyLongAudio=true
bash ./gradlew :soundly-sdk:assembleRelease :soundly-sdk:lintDebug
bash ./gradlew :soundly-sdk:publishReleasePublicationToMyRepoRepository
bash ./gradlew :sample-app:assembleRelease \
  -PsoundlyUsePublishedSdk=true -PsoundlySdkVersion=2.0.0
python3 soundly-sdk/baseline/scripts/check_public_api.py \
  --project . --baseline soundly-sdk/api/api-2.0.0.txt
```
