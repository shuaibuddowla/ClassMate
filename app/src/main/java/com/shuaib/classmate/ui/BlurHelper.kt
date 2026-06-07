/*
 * com/shuaib/classmate/ui/BlurHelper.kt
 *
 * Utility for applying RenderEffect backdrop blur on Android 12+ (API 31).
 * Falls back gracefully on older devices — existing semi-transparent drawables
 * provide the visual depth.
 */
package com.shuaib.classmate.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

object BlurHelper {

    /**
     * Apply a backdrop blur to [view] using RenderEffect on API 31+.
     * The blur is non-destructive: it only modifies the view's render effect,
     * leaving its content and background drawable intact.
     *
     * @param view        Target view to blur.
     * @param radiusX     Horizontal blur radius in pixels (default 20px ≈ soft frosted glass).
     * @param radiusY     Vertical blur radius in pixels (default 20px).
     */
    fun applyBackdropBlur(view: View, radiusX: Float = 20f, radiusY: Float = 20f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(radiusX, radiusY, Shader.TileMode.CLAMP)
            )
        }
        // On API < 31: semi-transparent drawables on the view provide the glass illusion.
    }

    /**
     * Remove any RenderEffect blur previously applied via [applyBackdropBlur].
     */
    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }

    /**
     * Apply a lighter blur suitable for inline cards (not the full nav bar).
     * Radius is smaller to avoid overwhelming card content.
     */
    fun applyCardBlur(view: View) = applyBackdropBlur(view, radiusX = 12f, radiusY = 12f)
}
