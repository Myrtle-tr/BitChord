package com.music.bitchord.wearos.lyrics

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.ambient.AmbientLifecycleObserver

@Immutable
data class AmbientState(
    val isAmbient: Boolean = false,
    /** Shift content by a few pixels over time to avoid OLED burn-in. */
    val burnInProtectionRequired: Boolean = false,
    /** Low-bit displays: pure black/white only, no gray. */
    val deviceHasLowBitAmbient: Boolean = false,
)

/**
 * Tracks ambient (always-on) mode for the hosting activity.
 * Requires `<uses-permission android:name="android.permission.WAKE_LOCK" />` in the wearos manifest.
 */
@Composable
fun rememberAmbientState(): AmbientState {
    val activity = LocalContext.current.findComponentActivity()
    var state by remember { mutableStateOf(AmbientState()) }

    if (activity != null) {
        DisposableEffect(activity) {
            val callback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    state = AmbientState(
                        isAmbient = true,
                        burnInProtectionRequired = ambientDetails.burnInProtectionRequired,
                        deviceHasLowBitAmbient = ambientDetails.deviceHasLowBitAmbient,
                    )
                }

                override fun onExitAmbient() {
                    state = AmbientState()
                }
            }
            val observer = AmbientLifecycleObserver(activity, callback)
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }
    return state
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}