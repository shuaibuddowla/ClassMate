package com.shuaib.classmate.notices

import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import com.shuaib.classmate.R
import java.util.regex.Pattern

object WhatsAppEditorHelper {

    // Regex for inline WhatsApp formatting
    private val BOLD_REGEX = Pattern.compile("(?<=(?:^|[\\s(\\[{\"'`]))(?:\\*\\*|\\*)([^\\s\\*](?:[^\\*\n]*?[^\\s\\*])?)(?:\\*\\*|\\*)(?=(?:$|[\\s)\\]}'\",.!?`]))")
    private val ITALIC_REGEX = Pattern.compile("(?<=(?:^|[\\s(\\[{\"'`]))_([^\\s_](?:[^_\n]*?[^\\s_])?)_(?=(?:$|[\\s)\\]}'\",.!?`]))")
    private val STRIKE_REGEX = Pattern.compile("(?<=(?:^|[\\s(\\[{\"'`]))(?:~~|~)([^\\s~](?:[^~\n]*?[^\\s~])?)(?:~~|~)(?=(?:$|[\\s)\\]}'\",.!?`]))")
    private val MONO_REGEX = Pattern.compile("(?<=(?:^|[\\s(\\[{\"'`]))(?:```|`)([^\\s`\n](?:[^`\n]*?[^\\s`\n])?)(?:```|`)(?=(?:$|[\\s)\\]}'\",.!?`]))")

    private val NUMBERED_LIST_REGEX = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
    private val BULLET_LIST_REGEX = Regex("""^(\s*)([•\-\*])\s+(.*)$""")

    fun attach(editText: EditText) {
        val watcher = object : TextWatcher {
            private var isFormatting = false
            private var newlineInsertedAt = -1

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isFormatting || s == null) return
                // Check if user inserted a newline
                if (count == 1 && before == 0 && s.length > start && s[start] == '\n') {
                    newlineInsertedAt = start
                } else {
                    newlineInsertedAt = -1
                }
            }

            override fun afterTextChanged(editable: Editable?) {
                if (isFormatting || editable == null) return
                isFormatting = true
                try {
                    // 1. Handle smart list continuation on newline
                    if (newlineInsertedAt >= 0 && newlineInsertedAt < editable.length) {
                        val pos = newlineInsertedAt
                        newlineInsertedAt = -1
                        handleSmartEnter(editText, editable, pos)
                    }

                    // 2. Apply live visual formatting spans
                    applyLiveFormatting(editText, editable)
                } catch (_: Exception) {
                } finally {
                    isFormatting = false
                }
            }
        }

        editText.addTextChangedListener(watcher)
        setupSelectionActionMode(editText)
    }

    private fun handleSmartEnter(editText: EditText, editable: Editable, newlinePos: Int) {
        // newlinePos is the index of '\n' that was just inserted
        val text = editable.toString()
        // Find line start before this newline
        val lineStart = text.lastIndexOf('\n', (newlinePos - 1).coerceAtLeast(0)).let {
            if (it < 0) 0 else it + 1
        }
        val prevLine = text.substring(lineStart, newlinePos)

        // Check Numbered List (e.g., "1. " or "1. Some task")
        val numMatch = NUMBERED_LIST_REGEX.matchEntire(prevLine)
        if (numMatch != null) {
            val indent = numMatch.groupValues[1]
            val num = numMatch.groupValues[2].toIntOrNull() ?: 1
            val content = numMatch.groupValues[3].trim()

            if (content.isEmpty()) {
                // Empty item (e.g. user pressed enter on "2. "): cancel list!
                editable.delete(lineStart, newlinePos + 1)
                editText.setSelection(lineStart.coerceAtMost(editable.length))
            } else {
                // Continue list: insert next number
                val nextPrefix = "$indent${num + 1}. "
                val insertPos = newlinePos + 1
                editable.insert(insertPos, nextPrefix)
                editText.setSelection((insertPos + nextPrefix.length).coerceAtMost(editable.length))
            }
            return
        }

        // Check Bullet List (e.g., "• " or "- " or "* ")
        val bulletMatch = BULLET_LIST_REGEX.matchEntire(prevLine)
        if (bulletMatch != null) {
            val indent = bulletMatch.groupValues[1]
            val content = bulletMatch.groupValues[3].trim()

            if (content.isEmpty()) {
                // Empty bullet: cancel list!
                editable.delete(lineStart, newlinePos + 1)
                editText.setSelection(lineStart.coerceAtMost(editable.length))
            } else {
                // Continue bullet list
                val nextPrefix = "$indent• "
                val insertPos = newlinePos + 1
                editable.insert(insertPos, nextPrefix)
                editText.setSelection((insertPos + nextPrefix.length).coerceAtMost(editable.length))
            }
            return
        }
    }

    private fun applyLiveFormatting(editText: EditText, editable: Editable) {
        // Remove previous formatting spans created by this helper
        val oldSpans = editable.getSpans(0, editable.length, CharacterStyle::class.java)
        for (span in oldSpans) {
            if (span is StyleSpan || span is StrikethroughSpan || span is TypefaceSpan || span is ForegroundColorSpan) {
                editable.removeSpan(span)
            }
        }

        val text = editable.toString()
        val dimColor = editText.context.getColor(R.color.cm_text_muted)

        // Monospace
        val monoMatcher = MONO_REGEX.matcher(text)
        while (monoMatcher.find()) {
            val start = monoMatcher.start()
            val end = monoMatcher.end()
            val cStart = monoMatcher.start(1)
            val cEnd = monoMatcher.end(1)
            editable.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(dimColor), start, cStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(dimColor), cEnd, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Bold
        val boldMatcher = BOLD_REGEX.matcher(text)
        while (boldMatcher.find()) {
            val start = boldMatcher.start()
            val end = boldMatcher.end()
            val cStart = boldMatcher.start(1)
            val cEnd = boldMatcher.end(1)
            editable.setSpan(StyleSpan(Typeface.BOLD), cStart, cEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(dimColor), start, cStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(dimColor), cEnd, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Italic
        val italicMatcher = ITALIC_REGEX.matcher(text)
        while (italicMatcher.find()) {
            val start = italicMatcher.start()
            val end = italicMatcher.end()
            val cStart = italicMatcher.start(1)
            val cEnd = italicMatcher.end(1)
            editable.setSpan(StyleSpan(Typeface.ITALIC), cStart, cEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(dimColor), start, cStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(dimColor), cEnd, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Strikethrough
        val strikeMatcher = STRIKE_REGEX.matcher(text)
        while (strikeMatcher.find()) {
            val start = strikeMatcher.start()
            val end = strikeMatcher.end()
            val cStart = strikeMatcher.start(1)
            val cEnd = strikeMatcher.end(1)
            editable.setSpan(StrikethroughSpan(), cStart, cEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(dimColor), start, cStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(ForegroundColorSpan(dimColor), cEnd, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun setupSelectionActionMode(editText: EditText) {
        val originalCallback = editText.customSelectionActionModeCallback
        editText.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                originalCallback?.onCreateActionMode(mode, menu)
                menu.add(Menu.NONE, 2001, Menu.NONE, "Bold")
                menu.add(Menu.NONE, 2002, Menu.NONE, "Italic")
                menu.add(Menu.NONE, 2003, Menu.NONE, "Strike")
                menu.add(Menu.NONE, 2004, Menu.NONE, "Mono")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return originalCallback?.onPrepareActionMode(mode, menu) ?: false
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val selStart = editText.selectionStart
                val selEnd = editText.selectionEnd
                if (selStart in 0 until selEnd) {
                    val wrap = when (item.itemId) {
                        2001 -> Pair("*", "*")
                        2002 -> Pair("_", "_")
                        2003 -> Pair("~", "~")
                        2004 -> Pair("`", "`")
                        else -> null
                    }
                    if (wrap != null) {
                        val editable = editText.text
                        if (editable != null) {
                            val selected = editable.subSequence(selStart, selEnd).toString()
                            editable.replace(selStart, selEnd, "${wrap.first}$selected${wrap.second}")
                            editText.setSelection(selStart + 1, selEnd + 1)
                        }
                        mode.finish()
                        return true
                    }
                }
                return originalCallback?.onActionItemClicked(mode, item) ?: false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                originalCallback?.onDestroyActionMode(mode)
            }
        }
    }
}
