package com.shuaib.classmate.activities

/** Keep the numeric email cohort in the database; present its academic year range. */
internal object ClassMateAcademicSession {
    fun end(value: String): Int {
        val text = value.trim()
        val range = Regex("(\\d{2})-(\\d{2})").matchEntire(text)
        val end = (range?.groupValues?.get(2) ?: text).toIntOrNull()
        require(end != null && end in 0..99 &&
            (range == null || range.groupValues[1].toInt() == (end + 99) % 100)) {
            "Enter an academic session such as 24-25."
        }
        return end
    }
    fun format(value: Any?): String {
        val text = value?.toString()?.trim().orEmpty()
        if (Regex("\\d{2}-\\d{2}").matches(text)) return text
        val end = text.toIntOrNull()?.takeIf { it in 0..99 } ?: return "—"
        return "${((end + 99) % 100).toString().padStart(2, '0')}-${end.toString().padStart(2, '0')}"
    }
}
