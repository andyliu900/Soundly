# Soundly
🎧 **Soundly** 是一个面向 Android 的轻量级音频处理 SDK，提供音频解码、分析、DSP 处理以及播放能力，帮助开发者快速构建高质量的音频处理应用。

Soundly 的设计目标是：**让复杂的音频处理流程变得简单、清晰、可扩展**。

---

## ✨ Features

- 🎧 **Audio Decode**  
  基于 Android `MediaCodec` 的音频解码能力

- 🧠 **Audio Analyzer**  
  音频特征分析能力，用于识别音频特征信息

- 🎚 **DSP Processing Pipeline**  
  模块化 DSP 处理管线

- 🔊 **PCM Data Processing**  
  完整的 PCM 数据处理流程

- 📀 **WAV Export**  
  支持音频处理结果导出为 WAV 文件

- ▶️ **Audio Player**  
  内置音频播放能力

- 📊 **Processing History**  
  支持音频处理历史记录

---


## 📦 Project Structure
soundly  
├─ soundly-core        # 核心音频处理能力  
├─ soundly-sdk         # 对外 SDK 接口  
└─ sample-app          # 示例应用

---

## 🚀 Getting Started

### 1 添加依赖

如果通过 module 引入：

```gradle
dependencies {
    implementation(project(":soundly-sdk"))
}
```

