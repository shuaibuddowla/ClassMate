package com.shuaib.classmate.activities

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnLayout
import com.shuaib.classmate.R
import com.shuaib.classmate.utils.ThemeColors
import java.util.regex.Pattern
import kotlin.math.hypot

internal object ClassMateNoticeText {
    const val MAX_PREVIEW_LINES = 5
    const val SUFFIX_MORE = "… see more"
    const val SUFFIX_LESS = "  see less"

    val BOLD_PATTERN: Pattern = Pattern.compile("(?<=(?:^|[\\s(\\[{\"'`]))(?:\\*\\*|\\*)([^\\s\\*](?:[^\\*\n]*?[^\\s\\*])?)(?:\\*\\*|\\*)(?=(?:$|[\\s)\\]}'\",.!?`]))")
    val ITALIC_PATTERN: Pattern = Pattern.compile("(?<=(?:^|[\\s(\\[{\"'`]))_([^\\s_](?:[^_\n]*?[^\\s_])?)_(?=(?:$|[\\s)\\]}'\",.!?`]))")
    val STRIKE_PATTERN: Pattern = Pattern.compile("(?<=(?:^|[\\s(\\[{\"'`]))(?:~~|~)([^\\s~](?:[^~\n]*?[^\\s~])?)(?:~~|~)(?=(?:$|[\\s)\\]}'\",.!?`]))")
    val MONO_PATTERN: Pattern = Pattern.compile("(?<=(?:^|[\\s(\\[{\"'`]))(?:```|`)([^\\s`\n](?:[^`\n]*?[^\\s`\n])?)(?:```|`)(?=(?:$|[\\s)\\]}'\",.!?`]))")
    val BULLET_LINE_PATTERN: Pattern = Pattern.compile("(?m)^(\\s*)(?:[-*])\\s+")

    val WEB_URL_PATTERN: Pattern = Pattern.compile(
        "(?:https?://|www\\.)[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|" +
            "\\b[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*\\." +
            "(?:com|org|net|edu|gov|mil|app|dev|io|co|me|bd|ai|tech|xyz|info|biz|tv|cc|live|online|site|page|link|store|cloud|space|uk|us|ca|de|in|eu|au|fr|jp|gg|so|gl|ly|to|mobi|pro|name|asia|int|arpa)" +
            "(?::[0-9]{1,5})?(?:/[a-zA-Z0-9+&@#/%?=~_|!:,.;-]*[a-zA-Z0-9+&@#/%=~_|-]|(?!/))",
        Pattern.CASE_INSENSITIVE
    )

    fun isExpandable(body: String): Boolean {
        return body.count { it == '\n' } >= MAX_PREVIEW_LINES || body.length > 280
    }

    fun normalizeUrl(rawUrl: String): String {
        return if (!rawUrl.startsWith("http://", ignoreCase = true) &&
            !rawUrl.startsWith("https://", ignoreCase = true)
        ) {
            "https://$rawUrl"
        } else {
            rawUrl
        }
    }

    fun openExternalUrl(context: Context, rawUrl: String) {
        try {
            val normalized = normalizeUrl(rawUrl)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open link: $rawUrl", Toast.LENGTH_SHORT).show()
        }
    }

    fun createSafeUrlSpan(view: View, rawUrl: String): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) {
                openExternalUrl(widget.context, rawUrl)
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.color = view.context.getColor(R.color.cm_primary)
                ds.isUnderlineText = true
            }
        }
    }

    fun parseMarkup(context: Context, rawText: String, isForNotification: Boolean = false): SpannableStringBuilder {
        if (rawText.isBlank()) return SpannableStringBuilder("")

        // 1. Normalize bullet prefixes at start of lines (- or * followed by space)
        val normalizedText = BULLET_LINE_PATTERN.matcher(rawText).replaceAll("$1• ")
        val builder = SpannableStringBuilder(normalizedText)

        // 2. Monospace: ```code``` or `code`
        if (isForNotification) {
            applyInlineMarkup(builder, MONO_PATTERN) {
                listOf(
                    TypefaceSpan("monospace"),
                    RelativeSizeSpan(0.92f)
                )
            }
        } else {
            val isDark = ThemeColors.isDark(context)
            val monoBgColor = if (isDark) 0xFF27272A.toInt() else 0xFFF4F4F5.toInt()
            val monoTextColor = if (isDark) 0xFFE4E4E7.toInt() else 0xFF27272A.toInt()

            applyInlineMarkup(builder, MONO_PATTERN) {
                listOf(
                    TypefaceSpan("monospace"),
                    BackgroundColorSpan(monoBgColor),
                    ForegroundColorSpan(monoTextColor),
                    RelativeSizeSpan(0.92f)
                )
            }
        }

        // 3. Bold: *bold* or **bold**
        applyInlineMarkup(builder, BOLD_PATTERN) {
            listOf(StyleSpan(Typeface.BOLD))
        }

        // 4. Italic: _italic_
        applyInlineMarkup(builder, ITALIC_PATTERN) {
            listOf(StyleSpan(Typeface.ITALIC))
        }

        // 5. Strikethrough: ~strike~ or ~~strike~~
        applyInlineMarkup(builder, STRIKE_PATTERN) {
            listOf(StrikethroughSpan())
        }

        return builder
    }

    private fun applyInlineMarkup(
        builder: SpannableStringBuilder,
        pattern: Pattern,
        createSpans: () -> List<Any>
    ) {
        var offset = 0
        val matcher = pattern.matcher(builder)
        while (offset < builder.length && matcher.find(offset)) {
            val matchStart = matcher.start()
            val matchEnd = matcher.end()
            val content = matcher.group(1) ?: ""
            builder.replace(matchStart, matchEnd, content)
            val contentEnd = matchStart + content.length
            for (span in createSpans()) {
                builder.setSpan(span, matchStart, contentEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            offset = contentEnd
            matcher.reset(builder)
        }
    }

    fun styled(view: TextView, text: String, query: String): SpannableStringBuilder {
        val result = parseMarkup(view.context, text)
        val textStr = result.toString()
        val matcher = WEB_URL_PATTERN.matcher(textStr)
        val linkColor = view.context.getColor(R.color.cm_primary)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val rawUrl = matcher.group()
            result.setSpan(
                createSafeUrlSpan(view, rawUrl),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            result.setSpan(
                ForegroundColorSpan(linkColor),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            result.setSpan(
                UnderlineSpan(),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (query.isNotBlank()) {
            var from = 0
            while (from < textStr.length) {
                val found = textStr.indexOf(query, from, ignoreCase = true)
                if (found < 0) break
                result.setSpan(
                    BackgroundColorSpan(view.context.getColor(R.color.cm_search_highlight)),
                    found,
                    found + query.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                result.setSpan(
                    ForegroundColorSpan(view.context.getColor(R.color.cm_search_highlight_text)),
                    found,
                    found + query.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                from = found + query.length
            }
        }
        return result
    }

    fun createLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        includePad: Boolean = false,
        spacingAdd: Float = 0f,
        spacingMult: Float = 1f
    ): Layout {
        val safeWidth = width.coerceAtLeast(1)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setIncludePad(includePad)
                .setLineSpacing(spacingAdd, spacingMult)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text, paint, safeWidth,
                Layout.Alignment.ALIGN_NORMAL,
                spacingMult, spacingAdd,
                includePad
            )
        }
    }

    fun measureLineCount(view: TextView, text: CharSequence, width: Int): Int {
        if (width <= 0 || text.isEmpty()) return 0
        return createLayout(
            text, view.paint, width,
            view.includeFontPadding, view.lineSpacingExtra, view.lineSpacingMultiplier
        ).lineCount
    }

    fun findSpanAt(widget: TextView, buffer: Spannable, touchX: Float, touchY: Float): ClickableSpan? {
        val layout = widget.layout ?: return null
        if (layout.lineCount == 0 || layout.height <= 0) return null
        val x = touchX.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = touchY.toInt() - widget.totalPaddingTop + widget.scrollY
        if (y < 0) return null
        val line = layout.getLineForVertical(y.coerceAtMost(layout.height - 1))
        val lineLeft = layout.getLineLeft(line)
        val lineRight = layout.getLineRight(line)
        if (x < lineLeft - 24 || x > lineRight + 24) return null
        val clampedX = x.toFloat().coerceIn(lineLeft, lineRight)
        val off = layout.getOffsetForHorizontal(line, clampedX)
        val spans = buffer.getSpans(0, buffer.length, ClickableSpan::class.java)
        return spans.firstOrNull {
            val start = buffer.getSpanStart(it)
            val end = buffer.getSpanEnd(it)
            off in start until end || (off == end && off > start)
        } ?: spans.firstOrNull {
            val start = buffer.getSpanStart(it)
            val end = buffer.getSpanEnd(it)
            (off - 1) in start until end
        }
    }

    class SelectableLinkMovementMethod : ArrowKeyMovementMethod() {
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L

        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val action = event.actionMasked
            val slop = (ViewConfiguration.get(widget.context).scaledTouchSlop * 2).toFloat()

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dist = hypot(event.x - downX, event.y - downY)
                    val elapsed = System.currentTimeMillis() - downTime
                    if (dist <= slop && elapsed < 800L) {
                        val link = findSpanAt(widget, buffer, event.x, event.y)
                            ?: findSpanAt(widget, buffer, downX, downY)
                        if (link != null) {
                            Selection.removeSelection(buffer)
                            link.onClick(widget)
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                }
            }

            return super.onTouchEvent(widget, buffer, event)
        }
    }

    fun newMovementMethod(): ArrowKeyMovementMethod = SelectableLinkMovementMethod()

    fun bind(
        view: TextView,
        body: String,
        query: String,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit
    ) {
        view.setTextIsSelectable(true)
        view.movementMethod = newMovementMethod()
        view.setLinkTextColor(view.context.getColor(R.color.cm_primary))
        view.ellipsize = null

        if (isExpanded) {
            view.maxLines = Int.MAX_VALUE
            val formatExpanded = { width: Int ->
                val full = styled(view, body, query)
                val lineCount = if (width > 50) measureLineCount(view, full, width) else (body.count { it == '\n' } + 1)
                if (lineCount > MAX_PREVIEW_LINES) {
                    val result = SpannableStringBuilder(full).append(SUFFIX_LESS)
                    val toggleSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) = onToggleExpand()
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = view.context.getColor(R.color.cm_primary)
                            ds.isUnderlineText = false
                            ds.isFakeBoldText = true
                        }
                    }
                    result.setSpan(
                        toggleSpan,
                        result.length - SUFFIX_LESS.length,
                        result.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    view.text = result
                } else {
                    view.text = full
                }
            }

            val tag = Any()
            view.tag = tag

            val currentWidth = (view.width - view.totalPaddingLeft - view.totalPaddingRight)
            if (currentWidth > 50) {
                formatExpanded(currentWidth)
            } else {
                view.text = styled(view, body, query)
            }
            view.doOnLayout { v ->
                if (view.tag !== tag) return@doOnLayout
                val measuredWidth = (v.width - (v as TextView).totalPaddingLeft - v.totalPaddingRight)
                if (measuredWidth > 50) {
                    formatExpanded(measuredWidth)
                }
            }
            return
        }

        // Collapsed mode: at most 6 lines
        view.maxLines = MAX_PREVIEW_LINES

        val formatCollapsed = { width: Int ->
            val fullStyled = styled(view, body, query)
            val lineCount = measureLineCount(view, fullStyled, width)
            if (lineCount > MAX_PREVIEW_LINES) {
                val layout = createLayout(
                    fullStyled, view.paint, width,
                    view.includeFontPadding, view.lineSpacingExtra, view.lineSpacingMultiplier
                )
                val targetLine = (MAX_PREVIEW_LINES - 1).coerceAtMost(layout.lineCount - 1)
                val lineEnd = layout.getLineEnd(targetLine).coerceAtMost(fullStyled.length)

                fun makeCandidate(cutoff: Int): SpannableStringBuilder {
                    var safeCutoff = cutoff.coerceIn(0, fullStyled.length)
                    if (safeCutoff > 0 && Character.isHighSurrogate(fullStyled[safeCutoff - 1])) safeCutoff--
                    val textStr = fullStyled.toString()
                    val matcher = WEB_URL_PATTERN.matcher(textStr)
                    while (matcher.find()) {
                        if (safeCutoff in (matcher.start() + 1)..matcher.end()) {
                            safeCutoff = matcher.start()
                            break
                        }
                    }
                    while (safeCutoff > 0 && textStr[safeCutoff - 1].isWhitespace()) {
                        safeCutoff--
                    }
                    val candidate = SpannableStringBuilder(fullStyled.subSequence(0, safeCutoff)).append(SUFFIX_MORE)
                    val toggleSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) = onToggleExpand()
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = view.context.getColor(R.color.cm_primary)
                            ds.isUnderlineText = false
                            ds.isFakeBoldText = true
                        }
                    }
                    candidate.setSpan(
                        toggleSpan,
                        candidate.length - SUFFIX_MORE.length,
                        candidate.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    return candidate
                }

                // Reserve a safe 24px safety buffer so "… see more" never wraps onto line 7
                val safeWidth = (width - 24).coerceAtLeast(1)
                var low = 0
                var high = lineEnd
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    val candidate = makeCandidate(mid)
                    val candidateLines = measureLineCount(view, candidate, safeWidth)
                    if (candidateLines <= MAX_PREVIEW_LINES) {
                        low = mid
                    } else {
                        high = mid - 1
                    }
                }
                view.text = makeCandidate(low)
            } else {
                view.text = fullStyled
            }
        }

        val tag = Any()
        view.tag = tag

        val currentWidth = (view.width - view.totalPaddingLeft - view.totalPaddingRight)
        if (currentWidth > 50) {
            formatCollapsed(currentWidth)
        } else {
            view.text = styled(view, body, query)
        }
        view.doOnLayout { v ->
            if (view.tag !== tag) return@doOnLayout
            val measuredWidth = (v.width - (v as TextView).totalPaddingLeft - v.totalPaddingRight)
            if (measuredWidth > 50) {
                formatCollapsed(measuredWidth)
            }
        }
    }
}
