package com.snapbrain.haptics

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short, crisp vibrations. Uses composed primitives on phones with a linear motor (Xiaomi 14T has one)
 * and falls back to the predefined effects elsewhere.
 */
class HapticsKit(context: Context) {

    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    @Suppress("WrongConstant") // vararg of Composition.PRIMITIVE_* constants
    private fun supports(vararg primitives: Int): Boolean =
        vibrator?.areAllPrimitivesSupported(*primitives) == true

    /** Correct answer: one satisfying click. */
    fun success() = play(
        primary = {
            if (supports(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 60)
                    .compose()
            } else null
        },
        fallback = VibrationEffect.EFFECT_CLICK,
    )

    /** Wrong answer: two short thuds. */
    fun failure() = play(
        primary = {
            if (supports(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f, 90)
                    .compose()
            } else null
        },
        fallback = VibrationEffect.EFFECT_DOUBLE_CLICK,
    )

    /** Swipe crossing the answer threshold, button taps. */
    fun tick() = play(
        primary = {
            if (supports(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f).compose()
            } else null
        },
        fallback = VibrationEffect.EFFECT_TICK,
    )

    /** Hook screen: a firm "wake up" knock. */
    fun knock() = play(
        primary = {
            if (supports(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.7f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    .compose()
            } else null
        },
        fallback = VibrationEffect.EFFECT_HEAVY_CLICK,
    )

    private fun play(primary: () -> VibrationEffect?, fallback: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(primary() ?: VibrationEffect.createPredefined(fallback)) }
    }
}
