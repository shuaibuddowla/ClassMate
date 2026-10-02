package com.shuaib.classmate.activities

import android.content.Context
import android.widget.*
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.shuaib.classmate.R

internal class ClassMateFormUi(val context: Context) {
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }
    val scroll = ScrollView(context).apply { addView(panel) }
    fun label(value: String) = TextView(context).apply {
        text = value; textSize = 13f; setTextColor(context.getColor(R.color.cm_text_secondary))
        setPadding(0, dp(12), 0, dp(6)); panel.addView(this)
    }
    fun field(label: String, multiline: Boolean = false): TextInputEditText {
        val box = TextInputLayout(context).apply {
            hint = label; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        val input = TextInputEditText(box.context).apply {
            setTextColor(context.getColor(R.color.cm_text_primary))
            if (multiline) { minLines = 4; maxLines = 8; gravity = android.view.Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else { setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        }
        box.addView(input); panel.addView(box); return input
    }
    fun choice(title: String, items: List<String>): Spinner {
        label(title)
        return Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            minimumHeight = dp(48); panel.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun status() = label("").apply { visibility = android.view.View.GONE; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
}
