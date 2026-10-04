package com.shuaib.classmate.activities

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.TextPaint
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import com.shuaib.classmate.R

internal object ClassMateNoticeText {
    fun styled(view: TextView, text: String, query: String): SpannableStringBuilder {
        val result = SpannableStringBuilder(text)
        Linkify.addLinks(result, Linkify.WEB_URLS)
        if (query.isNotBlank()) {
            var from = 0
            while (from < text.length) {
                val found = text.indexOf(query, from, ignoreCase = true)
                if (found < 0) break
                result.setSpan(BackgroundColorSpan(view.context.getColor(R.color.cm_search_highlight)), found, found + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(android.text.style.ForegroundColorSpan(view.context.getColor(R.color.cm_search_highlight_text)), found, found + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                from = found + query.length
            }
        }
        return result
    }
    fun bind(view: TextView, body: String, query: String, open: () -> Unit) {
        view.movementMethod = LinkMovementMethod.getInstance()
        view.setLinkTextColor(view.context.getColor(R.color.cm_primary))
        view.maxLines = 3
        // A card shows three lines. Avoid laying out/linkifying thousands of hidden
        // characters; the full notice remains available through See more.
        var limit=minOf(body.length,512)
        if(limit<body.length && limit>0 && Character.isHighSurrogate(body[limit-1])) limit--
        val previewBody=body.take(limit)
        view.text = styled(view, previewBody, query)
        val binding=Any()
        view.tag = binding
        view.post {
            if (view.tag !== binding) return@post
            val layout = view.layout ?: return@post
            val last = minOf(2, layout.lineCount - 1)
            if (last < 0) return@post
            if (body.length>previewBody.length || layout.lineCount > 3 || layout.getEllipsisCount(last) > 0) {
                val suffix = "… See more"
                var low = 0
                var high = layout.getLineEnd(last).coerceAtMost(previewBody.length)
                fun fits(end: Int): Boolean = android.text.StaticLayout.Builder.obtain(
                    previewBody.take(end).trimEnd() + suffix, 0, (previewBody.take(end).trimEnd() + suffix).length,
                    view.paint, layout.width).setIncludePad(view.includeFontPadding)
                    .setLineSpacing(view.lineSpacingExtra, view.lineSpacingMultiplier).build().lineCount <= 3
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (fits(mid)) low = mid else high = mid - 1
                }
                var end = low
                if (end > 0 && Character.isHighSurrogate(body[end - 1])) end--
                val prefix = body.take(end).trimEnd()
                val full = styled(view, previewBody, query)
                val result = SpannableStringBuilder(full, 0, prefix.length)
                result.getSpans(0, result.length, android.text.style.URLSpan::class.java).forEach { link ->
                    if (full.getSpanEnd(link) > prefix.length || (body.length>previewBody.length && full.getSpanEnd(link)==previewBody.length)) result.removeSpan(link)
                }
                result.append(suffix)
                val preview = result.toString()
                result.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = open()
                    override fun updateDrawState(ds: TextPaint) { ds.color = view.context.getColor(R.color.cm_primary); ds.isUnderlineText = false; ds.isFakeBoldText = true }
                }, preview.length - 8, preview.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                view.text = result
            }
        }
    }
}
