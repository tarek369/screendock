package com.cressu.screendock.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf

/**
 * Bridge between [MainActivity] (owns the PiP window) and Compose.
 * [mediaScreenShowing] is written by the UI; [isInPip] by the activity.
 */
class PipState(val supported: Boolean, private val enter: () -> Unit) {
    var isInPip by mutableStateOf(false)
    @Volatile var mediaScreenShowing: Boolean = false
    fun enterPip() = enter()
}

val LocalPip = compositionLocalOf<PipState?> { null }
