# Soundly 主流程代码落地里程碑

整理日期：2026-11-15。状态：H0～H7 已落地；H8 自动化联调已实施，真机和人工发布验收待完成。

目标流程：首次使用 → 录音 → 增强 → 保存到设备。

目标模块为仓库实际存在的 **`:soundly-app`**，目录 `soundly-app/`，对应用户所称的 soundly_app；本计划不重命名模块。技术栈：Kotlin、Fragment、XML、ViewBinding、Material 3、Navigation、协程与 Flow。

采用 H0～H8 编号，与已有 Material 功能版 M0～M6 区分。以下文件清单中的“新增”都是拟新增，不表示文件已经存在。

## 1. 范围与当前基础

| 分组 | 本轮覆盖 | 范围说明 |
|---|---|---|
| 主流程页面 | F01、F02、F03-A/F03-B、F04、F07～F09、F15、F18/F19、F22/F23、F24/F25 | 首次与回访首页、场景选择、准备/录音/暂停、原音预览、模式选择/增强、AB 试听、导出设置与成功 |
| 必须随主流程完成的保护 | F05/F06、F10/F11、F12～F14、F20/F21 | 权限与拒绝、后台通知基本功能、放弃确认、中断/空间不足/恢复的必要处理、增强取消/失败；可以先复用 Material 错误组件，不能丢数据或静默失败 |
| 小范围复用 | F16、F26/F27 | 重命名复用；保存后返回既有录音库并验证项目可见，不在本轮全面重做录音库 |
| 保留现状、后续专题 | F17、F28/F29、F30、F31～F37 | 裁剪、项目管理、导入、设置/存储/隐私/帮助/Pro 高保真迁移；已有入口和业务能力保持可用，改公共契约时做针对性回归 |
| 主题 | F38 | 同一 RecordingFragment 深色状态。项目已用 DayNight，主流程公共组件也需可读；主题切换入口及全量页面精修后续安排 |
| 系统能力 | 权限弹窗、通知栏、系统设置、分享面板 | 由 Android 提供；F10 首轮使用 SDK 标准通知，不实现设计稿中的自定义通知波形 |

当前并非从零搭建：

| 现有基础 | 已具备 | 主要差距 |
|---|---|---|
| Gradle 与主题 | ViewBinding 已开启；Fragment、Lifecycle、Navigation、Flow、Material 依赖已存在；主题为 `Theme.Material3.DayNight.NoActionBar` | 设计变量、组件样式、系统栏/insets 与实际高保真布局需要补齐；不把升级依赖作为前置任务 |
| App 依赖管理 | `AppContainer` 手动组装 Provider/Gateway；SDK 由 Application 初始化 | 需增加 ViewModel Factory 与主流程编排；无需为本流程引入 DI 框架 |
| 页面与路由 | `app_navigation.xml` 已有全部主流程 Fragment 和项目/版本/task 参数 | 缺返回栈规则、一次性跳转防重、F25 独立成功视觉状态；保留现有 destination ID |
| 状态与业务 | Recording/Project/Editing/SettingsStorage Gateway 已连 SDK | 多数 Feature 仍在 Fragment 发起业务；只有 FoundationViewModel 有初步示例，不等于主流程已有完整 MVVM |
| 录音/播放 | 已有开始、暂停、保存、原音播放与版本切换入口 | 录音波形目前是进度条；缺真实波形、完整播放器、操作失败处理与状态恢复 |
| 增强/导出 | SDK 任务观察、取消/重试、默认下载导出与分享已封装 | UI 模式映射不一致；终态重复导航风险；导出未暴露改名/进度，成功只显示文本 |

## 2. 里程碑总表

| 组别 | 阶段 | 界面 / 稿件 | 核心功能与交付 | 本阶段完成标志 |
|---|---|---|---|---|
| A 规则与基础 | H0 规格和技术契约（已完成） | 全主流程 | 明确模式映射、单次录音配置、导出格式、版本选择、弹层动作、任务作用域、路由与状态表；落地关键业务规则及 Gateway 适配 | [H0 契约](MAIN_FLOW_H0_CONTRACT.md)记录主流程输入、状态和去向；关键规则有单元测试 |
| A 规则与基础 | H1 UI 公共层与状态框架（已完成） | 全主流程公共组件，F38 主题基础 | Material 3 样式、ViewBinding 生命周期、StateFlow/UiAction/UiEffect、ViewModel Factory、错误映射、insets、播放器/波形组件接口 | [H1 交付记录](MAIN_FLOW_H1_DELIVERY.md)含浅/深色、小屏、大字体及 loading/error 验证；业务页分后续阶段接入 |
| B 首次使用与录音入口 | H2 启动、引导和首页 | F01/F02/F03-A/F03-B/F04 | SDK 启动路由、引导只出现一次、最近录音、单次场景、回访与通知回应用路由 | 首装到首页及回访首页均正确；系统返回不回引导；已有录音会刷新 |
| B 首次使用与录音入口 | H3 权限和录音准备 | F05/F06/F07 | 麦克风授权、普通/永久拒绝、设置返回重查；通知授权独立；读取默认并应用单次场景/音质 | 允许后只启动一次；拒绝可退出或走现有导入；本次配置与 SDK 会话一致 |
| C 录音与原音 | H4 录音、保护与本地保存 | F08/F09/F10/F11/F12/F13/F14/F38 | 真实波形/时长/标记、暂停/继续、后台通知、放弃确认、保存中/失败、中断与恢复最低闭环 | 可录制带标记音频并保存；返回/旋转/前后台不重复录制，异常不静默丢数据 |
| C 录音与原音 | H5 原音预览与增强入口 | F15/F16 | 本地项目/原始版本加载、播放/暂停/定位、静态波形、重命名、一键增强/调整方式双入口 | 预览对象确为新保存原音；两种增强入口传入同一正确来源版本 |
| D 增强与结果 | H6 模式、进度、结果与 AB | F18/F19/F20/F21/F22/F23 | 模式映射、一次提交、真实进度、取消/重试、结果播放、AB 同位置、创建另一个版本 | 原音保留、增强版本落库；取消/失败有闭环；终态仅处理一次，AB 与导出版本可追踪 |
| E 导出与验收 | H7 保存到设备与成功页（已完成） | F24/F25；复用 F27 | 本次文件名/格式/位置、导出进度/错误、防重入、成功展示实际结果、返回库；保留系统分享 | [H7 交付记录](MAIN_FLOW_H7_DELIVERY.md)记录实现、验证与待验收事项 |
| E 导出与验收 | H8 主流程联调与发布门禁（验收中） | 上述全部 | 导航回归、状态机关键测试、真机录音/通知/导出、视觉与适配验证 | [H8 验收记录](MAIN_FLOW_H8_ACCEPTANCE.md)记录自动化结果及待完成的真机和人工验收 |

执行依赖：**H0 → H1 → H2 → H3 → H4 → H5 → H6 → H7 → H8**。设计资源和 H7 格式规则可提前整理；每个阶段从首个页面起就接真实数据，H8 不承担此前未做的全部联调。

## 3. 各阶段具体落地与代码影响

本节资源路径默认位于 `soundly-app/src/main/res/`，Kotlin 包路径默认位于 `soundly-app/src/main/java/com/ideacode/soundly/`。

### A 组：规则与基础

| 阶段 | 具体实施项 | 预计修改的现有代码 | 拟新增内容 |
|---|---|---|---|
| H0 | 确定最小完整路径、错误状态、页面共用关系；现有功能与视觉冲突逐条记录，先做契约补齐再做视觉替换 | 补充 M0 主流程增量契约；F07/F18 接入配置与模式映射；扩展播放定位、显式导出 Gateway | [主流程 H0 契约](MAIN_FLOW_H0_CONTRACT.md)、状态/路由表、模式映射表、导出能力表及对应策略单元测试 |
| H1 | 补主题、色值、字阶、间距、按钮/卡片/弹层/提示；主流程页统一 insets；统一时长、容量显示 | `MainActivity.kt`、`res/layout/activity_main.xml`、`values/themes.xml`、`values-night/themes.xml`、两套 `colors.xml`、`strings.xml` | `values/styles.xml`、`values/dimens.xml`；必要 drawable/vector 资产；`common/ui/` 下播放器/波形等可复用 View 或 XML include |
| H1 | 确定状态流和事件约定；ViewModel 不依赖 Fragment/View，Fragment 仅渲染及平台交互；绑定随 view 生命周期清理 | `app/AppContainer.kt`、`common/error/AppErrorMessageMapper.kt`；复用 `FoundationViewModel` 的 factory 方式，但不把它扩成所有页面的巨大 ViewModel | 显式 ViewModel Factory；`domain/` 下必要主流程 UseCase；`platform/` 权限/分享控制器；各阶段按需增加 UiState/UiAction/UiEffect |

H0 执行方案（具体能力与页面状态以 [H0 契约](MAIN_FLOW_H0_CONTRACT.md) 为准）：

| 事项 | 建议实现方案 | 不明确会影响 |
|---|---|---|
| 场景/音质 | 全局设置读取为初始值；首页场景作为本次草稿；F07 调整优先级最高；不反写全局默认；场景名到 SDK 枚举显式映射 | H2/H3 的参数与配置合并 |
| 增强模式 | 四种 UI 模式映射到确定 preset/策略；“自动”不凭字面绑定；模式名称、说明和推荐理由分别管理 | H5/H6，尤其一键增强 |
| 导出 | 首轮固定 `Download/Soundly`，位置只读；文件名可编辑；可用格式按源版本能力过滤，默认参考既有设置；不支持时展示可用替代选项 | H7 及 EditingGateway |
| 试听与保存对象 | 结果页默认选增强；AB 切换后“保存与分享”跟随当前选中版本；F24 必须显式显示当前版本 | H6/H7，避免误导出原音 |
| F11 动作 | 建议先保留现有“返回/保存/放弃”业务能力，补三动作视觉；若改两按钮，先同步基线 | H4 与系统返回 |
| 分享 | 保留现有入口；按源版本格式分享；返回应用不把“已打开分享面板”记为“发送成功” | H7，不与导出格式混淆 |
| 进程终止 | 录音走 SDK 可恢复会话；增强 task 仅当前 SDK 进程内可观察，进程终止后提示任务不可恢复并回原音；导出先核对真实结果再决定重试 | H4/H6/H7 状态恢复 |
| 必需补稿 | 保存中/失败、恢复中/失败、模式映射、F24 等待/错误、F11 动作、F15 更多菜单；可用统一组件状态说明交付 | 对应模块定稿与验收 |

### B 组：首次使用与录音入口

| 阶段 | 界面与功能细项 | 预计修改的现有文件 | 拟新增单元与验收重点 |
|---|---|---|---|
| H2 | F01：观察 SDK 初始化结果；错误可重试；处理已有活动录音/残留会话；F02：完成引导持久化；移除启动/引导历史栈 | `feature/startup/StartupFragment.kt`、`feature/onboarding/OnboardingFragment.kt`、对应 `fragment_startup.xml` / `fragment_onboarding.xml`；`app/AppPreferences.kt`、必要时 `SoundlyApplication.kt` | `StartupViewModel` / `OnboardingViewModel`；首次/非首次、失败重试及活动会话路由测试 |
| H2 | F03/F04 共用首页，真实最近项目卡片；F03-B 选择单次场景；保留录音库、设置、导入既有入口；底部导航与主流程全屏页显隐统一 | `feature/home/HomeFragment.kt`、`layout/fragment_home.xml`；`MainActivity.kt`、`activity_main.xml`、`navigation/app_navigation.xml` | `HomeViewModel`、`SceneSelectionBottomSheet`、最近项目 Adapter/XML、底部导航 menu；保存后首页刷新，顶部最近项仍可打开既有详情 |
| H3 | F05：用户点击录音时解释并请求；F06：普通拒绝可重试，永久拒绝引导系统设置；不要仅凭 `shouldShowRequestPermissionRationale=false` 判定永久拒绝，要结合申请历史；设置返回重查但不擅自开始录制 | `feature/recording/PrepareRecordingFragment.kt` 中权限逻辑迁到页面外平台层；必要时 `AppPreferences.kt` 记录申请历史；核查合并后的 Manifest，不重复声明 SDK 已有权限/服务 | `PermissionBottomSheet`、`MicrophonePermissionController`、必要申请结果模型/XML；麦克风与 Android 13+ 通知授权分别处理 |
| H3 | F07：按默认＋单次覆盖构造 RecordingConfig；展示场景、只读音源、音质；Start 一次点击链路串行 prepare/start，配置与 pending 状态跨重建保存 | `PrepareRecordingFragment.kt`、`layout/fragment_prepare_recording.xml`；复用 `RecordingGateway.prepare(config)` 与 `SettingsStorageGateway` | `PrepareRecordingViewModel`、`StartRecordingUseCase`、单次 `RecordingDraft`；prepare/start 失败可处理，快速点击不生成两个 session |

权限说明可从首页调用；录音库等既有入口进入 F07 时仍需校验权限。保留 `prepareRecordingFragment` destination，只调整入口前置检查和参数，不复制两套授权逻辑。

### C 组：录音与原音

| 阶段 | 界面与功能细项 | 预计修改的现有文件 | 拟新增单元与验收重点 |
|---|---|---|---|
| H4 | F08/F09 共用录音页，订阅 SDK state/waveform/markers；暂停冻结显示，继续后续录；标记先自动编号，不把设计中的自定义标签当已有能力 | `feature/recording/RecordingFragment.kt`、`layout/fragment_recording.xml`、主题/文案；`RecordingGateway.kt` 现有 Flow 可复用 | `RecordingViewModel`、录音页 Renderer、实时 WaveformView；真实数据驱动，波形用有界缓存/适度刷新，不每帧重建整页状态 |
| H4 | F10 使用 SDK 通知暂停/继续/完成；返回录音页；F11 顶部返回/系统返回统一确认，切后台继续录音；完成后 Finalizing 禁重复点击，成功带真实项目/版本 ID 到 F15 | `RecordingFragment.kt`、`MainActivity.kt` / `StartupFragment.kt`、`app_navigation.xml`；核对通知点击路径，不另建录音服务 | `DiscardRecordingDialog`、保存中/错误 UI；用操作锁和 session 标识防重，避免返回已结束录音页 |
| H4 | F12/F13 最小原因文案、重试/保存与既有存储入口；F14 恢复/失败/逐条处理，放弃失败不能先跳首页 | `feature/startup/RecoveryFragment.kt`、`layout/fragment_recovery.xml`、`RecordingFragment.kt`、`common/error/` | `RecoveryViewModel`；核实残留会话丢弃接口的真实语义，不能把丢弃当前 session 当丢弃任意历史 session；能力不足另列 SDK 事项 |
| H5 | F15 加载原音项目/版本、静态波形、标记列表，播放/暂停/seek/完成/错误；F16 名称校验和真实持久化 | `feature/preview/AudioPreviewFragment.kt`、`layout/fragment_audio_preview.xml`、`data/sdk/ProjectGateway.kt` | `AudioPreviewViewModel`、`RenameProjectDialog` / 对应 XML；ProjectGateway 补 seekTo 的公开 SDK 委托，复用播放状态与波形加载；不存在的显式版本报错，不静默用原音替代 |
| H5 | 一键增强使用冻结的推荐配置直接创建任务到 F19；调整方式进入 F18；更多菜单保留原音直接导出/裁剪既有入口 | `AudioPreviewFragment.kt`、相关 XML、`app_navigation.xml`、`AppContainer.kt`、`EditingGateway.kt` 调用层 | 共用 `StartEnhancementUseCase`；项目 ID、来源版本 ID、模式只传一次，不在页面重建时重新创建任务 |

F12～F14 本轮要求保护闭环和可辨识状态；与主流程无关的存储管理视觉精修留到后续。SDK 若确实缺少必要公开动作，列为窄范围依赖修复，不在 App 复制音频或文件逻辑。

### D 组：增强与结果

| 阶段 | 界面与功能细项 | 预计修改的现有文件 | 拟新增单元与验收重点 |
|---|---|---|---|
| H6 | F18 四种 UI 模式展示、当前选择、来源预览、提交态；由同一映射器提供 preset | `feature/enhancement/EnhancementSetupFragment.kt`、`layout/fragment_enhancement_setup.xml` | `EnhancementSetupViewModel`、`EnhancementModeMapper` / UI 模式模型；原有五项 spinner 不直接换成四个未经映射的按钮 |
| H6 | F19 显示真实阶段与百分比；F20 继续/确认取消及取消中；F21 按 retryable 展示重试、更换模式、返回原音；未知 task/任务丢失有明确状态 | `EnhancementProgressFragment.kt`、`fragment_enhancement_progress.xml`；复用 EditingGateway observe/cancel/retry | `EnhancementProgressViewModel`、`CancelEnhancementDialog`；重试后替换 activeTaskId，旧终态不反复导航/弹窗；系统返回也遵循取消策略 |
| H6 | F22/F23 同页两状态，加载真实版本和静态波形；播放暂停/进度；AB 保持时间及播放状态，取消/加载失败不假装已切换；效果说明只显示有依据信息 | `EnhancementResultFragment.kt`、`layout/fragment_enhancement_result.xml`、`ProjectGateway.kt`、`app_navigation.xml` | `EnhancementResultViewModel`、共用播放器；从导航保留 `originalVariantId` 参数名，语义明确为“本次输入版本”，避免与整个项目最初原音混淆 |
| H6 | 创建另一个版本回 F18；保存传当前 selectedVariantId；成功已落库，无需等导出才算保存项目 | 同上；复用 `observeProject` 与 EditingGateway | 版本存在校验，处理 `first` 找不到版本的崩溃风险；原音/增强波形与元数据跟随选择；不覆盖原始文件 |

### E 组：导出与验收

| 阶段 | 界面与功能细项 | 预计修改的现有文件 | 拟新增单元与验收重点 |
|---|---|---|---|
| H7 | F24 显示所选版本、文件名、支持格式及固定位置；使用默认设置作为初值，文件名编辑不改项目名；后缀随实际格式锁定 | `feature/export/ExportFragment.kt`、`layout/fragment_export.xml`、`EditingGateway.kt`、`SettingsStorageGateway.kt` 调用处 | `ExportViewModel`、`ExportAudioUseCase`、本次导出参数模型；扩展 App Gateway 接收 displayName/format/destination 和真实进度，内部调用 SDK 已有 `export(request,onProgress)` |
| H7 | 等待/写入进度/失败/重试/重名自动编号；导出中不多次提交，不同时发起分享；F25 成功视觉与实际 URI/名称/格式；返回库只保留一次有效入口 | `ExportFragment.kt`、`app_navigation.xml` 的返回栈处理；现有 `LibraryFragment.kt` / Adapter 仅按需要接入刷新/返回定位 | `layout_export_success.xml` 作为同一 Fragment 子布局/状态，不新增必需 destination；保存结果丢失时查真实文件，不在重建后自动重导 |
| H7 | 系统分享复用现有能力，准备中/无接收方/错误处理；保存成功后的分享明确分享所选源版本还是实际导出文件 | `ExportFragment.kt`、`EditingGateway.kt`、平台控制器 | 首轮建议按现有 SDK 分享源版本并明确标注，尤其转为 WAV 导出后不可暗示分享同一个 WAV；若要求分享实际导出文件则另扩展 URI 分享契约 |
| H8 | 关键状态机/业务编排单测、Navigation/生命周期验证、完整真机操作、文件可播放及元数据检查、与既有旁支功能回归 | 各阶段相关生产代码仅修验证问题；`src/test/`、`src/androidTest/`；更新验收文档 | 主流程测试与真实验收记录，内容见第 7 节；不为纯 XML 颜色或简单映射写镜像测试 |

## 4. 推荐状态、协程与生命周期约定

| 层级 | 约定 | 解决的问题 |
|---|---|---|
| Fragment / XML | 通过 ViewBinding 绑定视图、转发 UiAction；在 `viewLifecycleOwner.repeatOnLifecycle(STARTED)` 收集；onDestroyView 清理持有的 binding/adapter 回调 | 避免销毁视图仍更新、重复注册监听和内存泄漏 |
| ViewModel | 每个业务页面/共用状态页面有自身 UiState；SDK Flow 转成 StateFlow；UI action 发起业务编排，必要时委托 UseCase；禁止持有 View/Activity/Binding | 从 Fragment 移除业务与可重入状态，支持重建和独立测试 |
| UiState 与 UiEffect | 加载/错误/当前版本/已完成结果是可恢复状态；导航/打开系统 UI 是一次性效果。效果需目标校验/消费确认，不能仅用 replay=0 SharedFlow 或 StateFlow 直接 navigate 便宣称防重 | 避免回前台重放成功导航，也避免后台完成时丢跳转 |
| SavedStateHandle | 存项目/版本/task ID、选择、输入草稿及必要已处理标识；不存 Activity、File/大波形或假定已持久化的任务对象 | 重建可恢复参数，但不把已失效 SDK 任务当作仍运行 |
| 录音 | SDK recorder/前台服务持有录音，不由 UI 的 STARTED/STOPPED 控制；收集停止不应等于录音停止 | 后台、旋转不终止真实录音 |
| 增强 | 创建/观察任务分离；导航图或正确页面作用域持有 task ID；同进程页面重建只重新观察。SDK 仅保证当前进程任务，进程重启做失效/结果核查 | 不重复增强、不出现无限等待；不在本轮引入自动后台续跑承诺 |
| 导出/保存 | 业务调用不放在 repeatOnLifecycle 中；由适当 ViewModel/流程作用域持有并发起一次。返回策略明确为执行中阻止导航或显式提示，不私自取消长操作 | 避免重收集就重导出，以及 View 销毁中断调用后结果不明 |
| 高频波形 | 使用真实 waveform Flow、有界采样缓冲与 View 刷新；时间/字节来源是 SDK；不由 UI 计时器推算录音成功数据 | 控制内存和绘制负载，避免假状态 |
| 平台能力 | 权限、打开设置、分享由 Fragment/Platform Controller 执行，结果转 UiAction；SDK Gateway 负责音频业务适配 | 不把 Android UI 对象塞进 ViewModel；复用现有 Intent 返回边界时在平台层消费 |

不建立覆盖所有功能的大型共享 ViewModel；共享限于录音草稿、增强任务或导出等确需跨页保存的流程状态。

## 5. Gateway 与现有契约的改动清单

| 接口 / 文件 | 当前可直接复用 | 预计必要改动 | 是否预计修改 SDK |
|---|---|---|---|
| `RecordingGateway.kt` | state、waveform、markers、prepare(config)、start/pause/resume/stopAndSave/recover | 首轮优先不改签名；若 F07 要显示未暴露的会话配置/恢复标识，评估只读适配；核实历史残留丢弃能力 | 正常录音无需；历史恢复操作缺公开能力时另列修复 |
| `ProjectGateway.kt` | observeProject(s)、loadWaveform、播放状态、prepare/play/pause、AB 切换、rename | 补 `seekTo(positionMs)` 委托 SDK；显式区分无效版本，不用错误版本回退 | SDK 已有 seekTo，通常无需改 SDK |
| `EditingGateway.kt` | start/observe/cancel/retry enhancement、默认导出、prepareShare | 新增带显式参数和进度的导出路径，保留旧 exportToDownloads 等调用兼容；必要时结果加入实际导出元数据；任务参数在调用层保留 | SDK 已有 export/onProgress，通常无需改 SDK |
| `SettingsStorageGateway.kt` | observeSettings、读取持久化默认值 | 本轮通常无需改接口，使用现有 Flow 取初值；本次覆盖不写全局 | 无 |
| `SoundlySdkProvider.kt` / 错误适配 | initialize/get、AppResult 转换 | 失败统一展示；必要时保留可安全用于错误 UI 的错误类别；不把底层异常/路径直接展示 | 优先仅 App 适配 |
| `AppContainer.kt` | 手动依赖组装 | 加主流程 UseCase/Mapper/Factory；现有 Gateway 不为架构整洁而整体拆分重命名 | 无 |
| `app_navigation.xml` / `AppDestination.kt` | 既有 destinations 和 ID 参数 | 补单次配置参数、popUpTo/单次导航/返回策略及命名文档；AppDestination 按实际消费需要对齐，避免建立第二套路由事实源 | 无 |

M0 曾规定高保真迁移不能变更业务契约。本计划识别到单次配置、seek 和显式导出属于既有功能版的能力补齐，实施时应先在 H0 记录这些窄范围增量，再迁移相应 UI；本次规划不自动修改原基线。

## 6. 页面复用与跨页参数

| 场景 | 推荐实现单元 | 关键输入 / 输出 |
|---|---|---|
| F03-A/F04 | 同一 HomeFragment 的空/非空状态 | 项目列表 Flow；输出本次场景草稿或既有 projectId |
| F03-B、F05/F06 | 场景 BottomSheet；权限 BottomSheet 的解释/拒绝状态 | 当前场景或权限申请状态；结果返回调用页，不产生新的录音 session |
| F07 | PrepareRecordingFragment | 草稿＋默认设置；成功后按当前 SDK session 进入录音 |
| F08/F09/F12/F13/F38 | 同一 RecordingFragment 的状态/主题 | SDK 真实会话状态；保存成功输出 projectId＋originalVariantId |
| F10/F11/F14 | SDK 通知；放弃 Dialog；既有 RecoveryFragment | 作用对象必须是正确活动/可恢复 session，不能以页面存在推断正在录音 |
| F15/F16 | AudioPreviewFragment＋Rename Dialog | projectId＋variantId；一键增强输出 taskId；调整方式输出来源版本 ID |
| F18/F19/F20/F21 | SetupFragment；ProgressFragment 的 running/error 状态＋取消 Dialog | projectId＋sourceVariantId＋preset → taskId；retry 返回新 taskId |
| F22/F23 | 同一 EnhancementResultFragment 的 AB 选择状态 | projectId＋originalVariantId（本次输入）＋enhancedVariantId；导出输出 selectedVariantId |
| F24/F25 | 同一 ExportFragment 的 configure/exporting/success/error 状态 | projectId＋variantId＋本次参数；成功持有真实 ExportResult |

导航仅传轻量 ID 和配置值；项目详情、播放位置和版本数据从 Gateway/流程状态重新获取，不传整段音频或大型对象。

返回栈重点：启动/引导完成移除旧页；录音完成不可回到可再次操作的旧会话；增强结果不可退回会重复弹终态的任务页；导出成功返回库后不能返回执行中的导出页。现有旁支目的地保留，只清理当前流程产生的相关条目。

## 7. 分阶段验收与最终门禁

| 阶段 | 必需验证 | 通过标准 |
|---|---|---|
| H1/H2 | 生命周期与公共样式；首次/回访；最近录音刷新；SDK 初始化失败 | 无重建后重复导航、无引导反复出现；UI 不被系统栏/键盘遮挡 |
| H3/H4 | 权限允许/普通拒绝/系统设置；准备失败；开始防重；暂停/继续；标记；完成/放弃；中断/恢复 | 同一次操作只有一会话和一次保存；通知/旋转不丢录音；失败不假跳成功 |
| H5/H6 | 正确版本加载；播放器和 AB；一键/选模式；取消；可/不可重试失败；未知 task；终态重放 | 原音保持可用；一次任务一次输出/跳转；进度和模式与 SDK 一致；后台完成能正确回显 |
| H7 | 文件名/格式校验；不支持转换；空间不足；重复点击；重名；实际输出；成功页重建与分享 | 文件名、实际容器/格式、内容可播放，版本无串用；不因旋转自动多导出；无错误“分享成功” |
| H8 | 新装完整闭环、回访闭环、进程重建/终止、浅/深色、小屏/大字体、既有导入/裁剪/库/设置回归 | 正常链路与关键保护均有记录；改 Gateway 不破坏原调用方 |

自动化应覆盖行为风险：UseCase/状态机/并发防重/导航终态消费/版本选择/格式约束等关键逻辑，使用 Fake Gateway 和协程测试工具；不为每张静态 XML 写一套实现镜像测试。

代码实施后的门禁建议沿用：`:soundly-app:testDebugUnitTest`、`:soundly-app:assembleDebug`、`:soundly-app:lintDebug`、`git diff --check`。单阶段运行与改动相关检查，H8 汇总完整门禁；本次规划未执行这些命令，也不复用旧报告的通过结果当作本次结果。

设备验证至少覆盖 API 26+ 基础能力、API 33+ 通知权限及 API 35+ edge-to-edge/insets 行为。真实录制与导出文件可播放是主流程完成条件；SDK 侧已有自动化不能替代 App 端操作验证。

## 8. 每阶段交付方式

每完成 H1～H7 中一个阶段，交付：对应可运行页面/状态、变更文件、已验证路径、截图或录屏、实际测试结果、下一阶段依赖和已知问题。涉及尚未补图的子状态，记录使用的公共组件方案。

H0 先把主流程相关规则整理到可直接编码的程度；H1 建好复用基础；H2～H7 按用户操作顺序逐段跑通；H8 记录整条主流程的设备验收。主流程验收后再安排裁剪/导入、完整录音库和设置等独立里程碑。

参考：[用户操作流程与设计稿索引](../ProjectUI/用户操作流程与设计稿索引.md)、[UI 完备性评审报告](../ProjectUI/UI设计稿完备性与实现准备评审报告.md)、[原 M0 功能基线](M0_FUNCTIONAL_UI_BASELINE.md)、[M6 集成验收记录](M6_INTEGRATION_ACCEPTANCE.md)。
