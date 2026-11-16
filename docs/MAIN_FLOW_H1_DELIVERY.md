# 主流程 H1 公共 UI 与状态基础交付

日期：2026-11-15。H1 为主题、组件和状态基础；F02～F25 业务页面仍按 H2～H7 逐步改造，播放器及波形视图当前展示在调试组件页，尚未连接录音、预览或增强结果的 SDK 数据。

## 已实现

| 内容 | 文件与使用方式 |
|---|---|
| Material 3 浅色/深色语义色 | `res/values/colors.xml`、`res/values-night/colors.xml`；`themes.xml` 通过同名资源自动切换。F01 继续使用设计稿的浅色启动背景 |
| 文本、间距和组件样式 | `res/values/dimens.xml`、`styles.xml`：标题/正文/次要文字、主次按钮、卡片、输入框、确认弹窗和底部弹层主题 |
| Edge-to-edge 与 IME | `MainActivity` 在 Navigation 容器统一处理系统栏、刘海、导航栏和键盘 insets；F01 保持全屏。滚动视图放在安全区域容器内，避免内容滚入状态栏 |
| 状态与动作 | `common/state/UiStateModel.kt` 提供 `UiState`、`UiAction`、`UiEffect`、`StateActionViewModel` 和显式 ViewModel Factory；F01 是实际使用示例 |
| 一次性事件 | `UiEffectQueue` 用 StateFlow 保留 ViewModel 生命周期内未处理事件；界面在 RESUMED 且目标正确时处理并确认。它不负责进程死亡持久化，进程重启需从事实来源恢复状态 |
| 信息与错误提示 | `common/ui/UiStatusView.kt` 提供隐藏、加载、信息、可重试/不可重试错误；`AppErrorMessageMapper` 使用本地化安全文案 |
| 波形 | `common/ui/audio/WaveformView.kt` 输入外部归一化样本并绘制进度；输入限量降采样，异常值归零。具体 SDK 实时/静态波形的订阅留给各页面 ViewModel |
| 播放器 | `AudioPlayerView` 根据外部 `AudioPlayerUiState` 展示播放/暂停、进度和时长；用户触发 toggle/seek 回调，程序渲染不会触发 seek；不持有 SDK 或音频业务 |
| 时长与容量 | `DisplayFormat` 统一 MM:SS / HH:MM:SS、长时长及按 locale 格式化的文件容量。录音页默认显示小时 |
| Debug 预览 | `src/debug/.../UiGalleryActivity` 展示组件、浅/深色、加载/错误、确认弹窗和底部弹层；使用示例波形，仅 Debug 构建包含该页面 |

## 预览方式

安装 `soundly-app/build/outputs/apk/debug/soundly-app-debug.apk` 后，可在已连接设备或模拟器执行：

```shell
adb shell am start -n com.ideacode.soundly/.debug.UiGalleryActivity
```

多台设备同时连接时加 `adb -s <设备序列号>`。该页面只供 Debug 构建检查 UI，Release 合并 Manifest 不包含该 Activity。

模拟器截图：[浅色](screenshots/h1-components-light.png) · [深色](screenshots/h1-components-dark.png)。截图为组件预览，并非 F02～F25 的最终高保真页面。

## 验证记录

- `:soundly-app:testDebugUnitTest`：通过；包括事件队列在重订阅时保留、处理确认、并发投递，以及时长/容量边界。
- `:soundly-app:assembleDebug`、`:soundly-app:assembleDebugAndroidTest`、`:soundly-app:lintDebug`、`:soundly-app:assembleRelease`：通过；Release 合并 Manifest 未包含 Debug 预览 Activity。
- Android 13 模拟器（Pixel 8 Pro API 33）：组件浅/深色、错误→重试→加载、系统栏、360dp 宽/1.3 倍字体、滚动和键盘避让经人工检查；F01 可进入现有首次引导页。
- 模拟器 AndroidJUnitRunner：`UiGalleryInstrumentedTest` 3 项通过，覆盖加载/错误及 Activity 重建、程序渲染不触发 seek、确认弹窗和底部弹层开关。
- 连接的 PHY110 真机：曾安装调试构建并检查浅色组件预览；设备随后回到桌面，后续深色/小屏/键盘检查使用模拟器。真机上的安装包为测试过程中的构建，最新包以当前 Debug APK 为准。

## 后续接入

H2 接入首次引导/首页；H3 接入权限和录音准备；H4 将真实录音状态和实时波形接入 UI；H5/H6 将播放器和静态波形连接 SDK；H7 接入导出状态。完整录音、增强、保存闭环及不同 Android 版本的设备验收属于后续阶段，不能由 H1 组件预览代替。
