# H3 权限和录音准备交付

- F05/F06：首次录音前解释麦克风用途；拒绝可重试，再次拒绝且系统不再提示时引导应用设置；可取消或导入音频。首页及其他录音入口复用平台权限控制器。设置返回会重新检查，准备页不会自行开始录音。
- F07：按 SDK 默认设置和首页单次场景初始化，允许本次更改场景、质量，展示音源和设置摘要。草稿通过 SavedStateHandle 保存，不写回全局设置。
- 开始操作由 ViewModel 和用例执行，串行 prepare/start，忙碌时阻止重复启动和离开。可恢复的失败会保留 SDK 会话，不覆盖待恢复数据。
- Android 13 及以上的通知权限独立说明和请求；用户跳过或拒绝不会阻止录音。不启用后台录音时不请求通知权限。

验证：`bash gradlew :soundly-app:testDebugUnitTest :soundly-app:assembleDebug :soundly-app:lintDebug` 通过；单元测试覆盖草稿恢复、设置优先级、连续点击、防止 prepare 失败后 start、保留可恢复失败。Android 13 模拟器验证首次授权、普通拒绝、系统不再提示后设置入口、授权返回、旋转保留配置、跳过通知仍录音；保存项目的场景为 MEETING。系统设置中的授权通过模拟器 adb 授权后返回验证；尚未进行真机与不同 Android 版本的完整验收。

截图：[权限说明](screenshots/h3-permission.png)、[录音准备](screenshots/h3-prepare.png)。录音中页面及其完整交互属于 H4，预览、增强和保存属于后续阶段。
