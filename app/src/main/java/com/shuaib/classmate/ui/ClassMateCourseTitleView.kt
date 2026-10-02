package com.shuaib.classmate.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView

/** Prefer three readable lines; exceptionally long names grow instead of truncating. */
class ClassMateCourseTitleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {
    private var measuredTitle = ""
    private var measuredWidth = -1
    private var measuredBaseSize = -1f
    private var measuredDirection = -1
    private var measuredTypeface: Typeface? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        val title = text?.toString().orEmpty()
        val baseSize = sp(14.5f)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && availableWidth > 0 &&
            (title != measuredTitle || availableWidth != measuredWidth || baseSize != measuredBaseSize ||
                layoutDirection != measuredDirection || typeface != measuredTypeface)) {
            val probe = TextPaint(paint)
            var chosenSize = sp(12.5f)
            for (step in 0..4) {
                val size = sp(14.5f - step * 0.5f)
                probe.textSize = size
                val layout = StaticLayout.Builder.obtain(title, 0, title.length, probe, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(includeFontPadding)
                    .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
                    .setBreakStrategy(breakStrategy)
                    .setHyphenationFrequency(hyphenationFrequency)
                    .setTextDirection(if (layoutDirection == LAYOUT_DIRECTION_RTL)
                        TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .build()
                if (layout.lineCount <= 3) { chosenSize = size; break }
            }
            if (textSize != chosenSize) super.setTextSize(TypedValue.COMPLEX_UNIT_PX, chosenSize)
            measuredTitle = title; measuredWidth = availableWidth; measuredBaseSize = baseSize
            measuredDirection = layoutDirection; measuredTypeface = typeface
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
