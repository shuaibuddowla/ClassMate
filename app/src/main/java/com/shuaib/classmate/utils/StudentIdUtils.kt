package com.shuaib.classmate.utils

import android.text.InputFilter
import android.text.Spanned
import java.util.Locale

object StudentIdUtils {
    private val Pattern = Regex("^[A-Z]{2,4}[0-9]{5}$")
    val lengthFilter: InputFilter = InputFilter.LengthFilter(9)

    /**
     * Input filters for the student ID field.
     * Does NOT force uppercase while typing - lets the user type naturally.
     * Normalization (uppercase + trim) happens on form submit via normalize().
     */
    val inputFilters: Array<InputFilter> = arrayOf(
        InputFilter { source: CharSequence, _: Int, _: Int, _: Spanned, _: Int, _: Int ->
            // Only allow letters and digits - no spaces or special chars
            val filtered = source.filter { it.isLetterOrDigit() }
            if (filtered.length == source.length) null else filtered
        },
        lengthFilter
    )

    fun normalize(value: String): String {
        return value.trim()
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .take(9)
    }

    fun isValid(value: String): Boolean = Pattern.matches(normalize(value))
}
