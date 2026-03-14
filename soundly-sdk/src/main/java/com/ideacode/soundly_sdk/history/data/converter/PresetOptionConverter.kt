package com.ideacode.soundly_sdk.history.data.converter

import androidx.room.TypeConverter
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption

class PresetOptionConverter {

    @TypeConverter
    fun fromPreset(option: PresetOption): String = option.name

    @TypeConverter
    fun toPreset(value: String): PresetOption = PresetOption.valueOf(value)

}