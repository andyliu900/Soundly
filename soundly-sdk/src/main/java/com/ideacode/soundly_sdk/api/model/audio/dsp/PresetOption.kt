package com.ideacode.soundly_sdk.api.model.audio.dsp

enum class PresetOption(val title: String) {

    RAW("原始音频"),
    DEFAULT("默认增强"),
    AI("AI 智能增强"),

    /*********  常规优化  *********/
    VOICE_STABLE("人声稳定"),
    SPEECH_FOCUS("清晰优先"),
    NATURAL_SMOOTH("自然顺滑"),


    /*********  特定场景优化  *********/
    PODCAST("播客/访谈场景"),
    MEETING("会议/通话场景"),
    MUSIC("音乐/嘈杂场景"),

}