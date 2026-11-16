package com.ideacode.soundly.common.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.ideacode.soundly.databinding.ViewUiStatusBinding

sealed interface UiStatus {
    data object Hidden : UiStatus
    data class Loading(val message: CharSequence? = null) : UiStatus
    data class Error(val message: CharSequence, val retryable: Boolean) : UiStatus
    data class Message(val message: CharSequence) : UiStatus
}

/** A shared loading/error/empty-message surface; callers decide when an action is valid. */
class UiStatusView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val binding: ViewUiStatusBinding
    var onRetry: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        binding = ViewUiStatusBinding.inflate(LayoutInflater.from(context), this)
        binding.statusRetry.setOnClickListener { onRetry?.invoke() }
        render(UiStatus.Hidden)
    }

    fun render(status: UiStatus) {
        isVisible = status !is UiStatus.Hidden
        val message = when (status) {
            UiStatus.Hidden -> null
            is UiStatus.Loading -> status.message
            is UiStatus.Error -> status.message
            is UiStatus.Message -> status.message
        }
        binding.statusProgress.isVisible = status is UiStatus.Loading
        binding.statusMessage.isVisible = !message.isNullOrBlank()
        binding.statusMessage.text = message
        binding.statusRetry.isVisible = status is UiStatus.Error && status.retryable
    }
}
