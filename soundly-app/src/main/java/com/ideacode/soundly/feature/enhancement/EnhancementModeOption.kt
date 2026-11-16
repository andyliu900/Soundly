package com.ideacode.soundly.feature.enhancement

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.ideacode.soundly.R
import com.ideacode.soundly.domain.mainflow.EnhancementMode

/** Create a native checkable radio button styled as one accessible option card. */
fun createEnhancementModeOption(context: Context, mode: EnhancementMode): AppCompatRadioButton =
    AppCompatRadioButton(context).apply {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        id = when (mode) {
            EnhancementMode.AUTO -> R.id.setup_mode_auto
            EnhancementMode.CLEAR_SPEECH -> R.id.setup_mode_speech
            EnhancementMode.NOISE_REDUCTION -> R.id.setup_mode_noise
            EnhancementMode.NATURAL -> R.id.setup_mode_natural
        }
        buttonDrawable = null
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(62)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundResource(R.drawable.bg_setup_option)
        setTextColor(ContextCompat.getColor(context, R.color.preview_text))
        textSize = 15f
        compoundDrawablePadding = dp(12)
        val title = context.getString(EnhancementUi.title(mode))
        val description = context.getString(when (mode) {
            EnhancementMode.AUTO -> R.string.setup_auto_detail
            EnhancementMode.CLEAR_SPEECH -> R.string.setup_speech_detail
            EnhancementMode.NOISE_REDUCTION -> R.string.setup_noise_detail
            EnhancementMode.NATURAL -> R.string.setup_natural_detail
        })
        text = SpannableString("$title\n$description").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(.80f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.preview_secondary)), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val iconId = when (mode) {
            EnhancementMode.AUTO -> R.drawable.ic_soundly_wave
            EnhancementMode.CLEAR_SPEECH -> R.drawable.ic_people
            EnhancementMode.NOISE_REDUCTION -> R.drawable.ic_setup_noise
            EnhancementMode.NATURAL -> R.drawable.ic_setup_leaf
        }
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(context, R.color.preview_background)) }
        val icon = DrawableCompat.wrap(requireNotNull(ContextCompat.getDrawable(context, iconId))).mutate()
        DrawableCompat.setTint(icon, ContextCompat.getColor(context, R.color.preview_primary))
        val badge = LayerDrawable(arrayOf(circle, icon)).apply {
            setLayerSize(0, dp(40), dp(40))
            setLayerSize(1, dp(22), dp(22))
            setLayerGravity(0, Gravity.CENTER)
            setLayerGravity(1, Gravity.CENTER)
            setBounds(0, 0, dp(40), dp(40))
        }
        val check = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_setup_check)).mutate().apply { setBounds(0, 0, dp(20), dp(20)) }
        setCompoundDrawablesRelative(badge, null, check, null)
        layoutParams = RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
