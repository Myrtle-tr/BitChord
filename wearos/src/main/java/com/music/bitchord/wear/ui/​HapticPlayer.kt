package com.music.bitchord.wearos.lyrics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Predefined, system-tuned effects so they feel right on the Galaxy Watch haptic motor. */
class HapticPlayer(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Very light tick: moving to the next lyric line. */
    fun tick() = play(VibrationEffect.EFFECT_TICK)

    /** Slightly firmer click: entering a chorus, or a successful swipe gesture. */
    fun click() = play(VibrationEffect.EFFECT_CLICK)

    private fun play(effectId: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createPredefined(effectId))
    }
}