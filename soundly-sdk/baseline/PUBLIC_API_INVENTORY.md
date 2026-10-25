# Soundly SDK 公开 API 清单

## 口径

本清单同时保留两种口径：

1. **面向宿主 App 的设计 API**：Kotlin 源码中计划由 `Soundly` / `SoundlySdk` 暴露的能力。
2. **AAR 实际可见字节码**：`classes.jar` 中 `javap -public` 可见的类型。Kotlin `internal` 会编译为 JVM `public`，因此此集合大于设计 API。

完整字节码签名位于固定报告中的 `api-git-head-legacy.txt` 和 `api-current-worktree.txt`。旧版 AAR 有 54 个顶层类条目，当前 AAR 有 151 个顶层类条目。

## 初始化与统一门面

| 类型 | 关键 API | 当前定位 |
|---|---|---|
| `Soundly` | `initialize(context, config): SoundlyResult<Unit>`、`isInitialized()`、`sdk()` | SDK 2.0 主入口 |
| `SoundlyConfig` | 调试日志等初始化配置 | 稳定配置模型 |
| `SoundlySdk` | 统一暴露下表 13 项能力 | SDK 2.0 能力门面 |
| `Soundly.init(context)` | 无返回值、失败抛异常 | 旧版兼容，已废弃 |
| `Soundly.container()` | 直接暴露 `SdkContainer` | 旧版兼容，已废弃；工作项 3 尚需收紧 |
| `SdkAudioProcessor()` | 直接构造处理器 | 旧版兼容，已废弃 |

## `SoundlySdk` 能力

| 属性 | 接口 | 主要公开操作 |
|---|---|---|
| `processor` | `AudioProcessingService` | `process(request, onProgress)` |
| `player` | `AudioPlayer` | 旧播放器兼容能力 |
| `history` | `AudioHistoryRepository` | 旧历史记录兼容能力 |
| `projects` | `AudioProjectRepository` | 观察、搜索、读取、保存、重命名、版本/标记维护、删除项目 |
| `importer` | `AudioImporter` | `importAudio(request)` |
| `metadata` | `AudioMetadataReader` | `read(uri)` |
| `recorder` | `RecordingManager` | 准备、开始、暂停、继续、标记、完成、放弃、发现/恢复录音 |
| `playback` | `AudioPlaybackController` | 准备、播放、暂停、停止、拖动、切源保留位置、释放 |
| `trimmer` | `AudioTrimmer` | 裁剪预览、非破坏性应用裁剪 |
| `exporter` | `AudioExporter` | 导出、按默认设置导出、分享、创建 SAF 文档 Intent |
| `storage` | `StorageManager` | 空间快照、清缓存、删除全部本地数据 |
| `enhancer` | `AudioEnhancer` | 按项目/源版本/预设生成增强版本 |
| `settings` | `SoundlySettingsRepository` | Flow 观察、读取、更新、重置设置 |

## 核心领域与请求/结果模型

| 分组 | 公开类型 |
|---|---|
| 统一结果/错误 | `SoundlyResult<T>`、`SoundlyError`、`SoundlySdkException` |
| 项目 | `AudioProject`、`AudioVariant`、`AudioMarker`、`ProjectStatus`、`VariantType`、`AudioSourceType`、`AudioFormat` |
| 音源/处理 | `AudioSourceRef`、`PresetOption`、`ProcessingRequest`、`ProcessingProgress`、`ProcessingResult`、`ProcessingStage` |
| 录音 | `RecordingConfig`、`RecordingQuality`、`RecordingScene`、`RecordingState`、`RecordingSession`、`RecordingResult`、`RecoverableRecording`、`RecordingInterruptionReason`、`AudioMeter`、`WaveformFrame`、`MarkerResult` |
| 播放 | `PlaybackState` |
| 导入 | `AudioImportRequest`、`AudioImportResult`、`AudioMetadata` |
| 增强 | `EnhancementResult` |
| 裁剪 | `TrimRange`、`TrimPreview`、`TrimResult`、`TrimmedMarker` |
| 导出/分享 | `ExportRequest`、`ExportResult`、`ExportProgress`、`ExportDestination`、`ExportStage`、`ExportQuality`、`FileConflictStrategy`、`ShareRequest`、`ShareResult` |
| 存储 | `StorageSnapshot` |
| 设置 | `SoundlySettings`、`RecordingNamingStrategy`、`SoundlySettingsRepository` |

## Git HEAD 旧 SDK 的宿主入口

旧 SDK 没有统一的 `SoundlySdk` 能力门面，宿主主要依赖以下入口：

| 入口 | 行为/问题 |
|---|---|
| `Soundly.init(context)` | 使用 `lateinit` 初始化容器，没有类型化结果 |
| `Soundly.container()` | 公开整个依赖容器及内部实现 |
| `SdkAudioProcessor.process(uri, preset, outputFileName, callback)` | 返回 `Unit`，输出结果只能从回调读取 |
| `SdkAudioPlayer` / `AudioPlayer` | 旧播放器接口 |
| `AudioHistoryRepository` / `RoomAudioHistoryRepository` | 旧历史记录接口 |
| `PresetOption`、`ProcessingStage`、`ProcessingProcess` | 旧处理输入与状态模型 |
| `AudioHistory`、`AudioVariant` | 旧历史领域模型 |

## 已冻结的 API 风险

| 风险 | 基线结论 | 后续归属 |
|---|---|---|
| `SdkContainer` 与 `Soundly.container()` 仍可从宿主调用 | 未完成真正隐藏 | 工作项 3 |
| 多个 `core.*`、数据库实现、Android 实现类在 AAR 字节码中公开 | AAR 实际表面远大于设计表面 | 工作项 3 / 20 |
| 当前仍保留旧 `player`、`history` | 为兼容旧宿主而存在 | 版本迁移策略 |
| `ExportQuality` 当前只有占位策略 | 接口存在但质量策略未闭环 | 工作项 17 |
| 处理链仍可见旧 `ProcessingProcess` | 兼容回调仍存在 | 工作项 5 / 13 |

此清单冻结“现在是什么”，不把尚未完成的封装描述成已完成。
