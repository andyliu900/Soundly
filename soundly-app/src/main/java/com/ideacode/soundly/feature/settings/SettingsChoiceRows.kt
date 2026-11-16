package com.ideacode.soundly.feature.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.content.ContextCompat
import com.ideacode.soundly.R

/** Reusable single-choice rows; RadioGroup owns accessibility and selection semantics. */
internal object SettingsChoiceRows {
    fun populate(group: RadioGroup, choices: List<Pair<String, String>>) {
        group.removeAllViews()
        val context = group.context
        val density = context.resources.displayMetrics.density
        choices.forEach { (title, description) ->
            val label = SpannableString("$title\n$description").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, title.length, 0)
                setSpan(RelativeSizeSpan(.82f), title.length + 1, length, 0)
                setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.preview_secondary)), title.length + 1, length, 0)
            }
            val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(context, R.color.library_icon_bg)) }
            val icon = ContextCompat.getDrawable(context, R.drawable.ic_soundly_wave)!!.mutate().apply { setTint(ContextCompat.getColor(context, R.color.preview_primary)) }
            val badge = LayerDrawable(arrayOf(circle, InsetDrawable(icon, (11 * density).toInt()))).apply { setBounds(0, 0, (48 * density).toInt(), (48 * density).toInt()) }
            group.addView(AppCompatRadioButton(context).apply {
                id = View.generateViewId()
                layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                text = label; textSize = 16f; setTextColor(ContextCompat.getColor(context, R.color.preview_text))
                minHeight = (72 * density).toInt(); gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL; textDirection = View.TEXT_DIRECTION_LOCALE
                setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
                setButtonDrawable(R.drawable.settings_radio_inset); buttonTintList = null
                setCompoundDrawables(badge, null, null, null); compoundDrawablePadding = (14 * density).toInt()
                setBackgroundResource(R.drawable.bg_settings_choice)
            })
        }
    }
    fun selectedIndex(group: RadioGroup): Int = group.indexOfChild(group.findViewById(group.checkedRadioButtonId))
    fun select(group: RadioGroup, index: Int) { group.getChildAt(index.coerceIn(0, group.childCount - 1))?.let { group.check(it.id) } }
}
