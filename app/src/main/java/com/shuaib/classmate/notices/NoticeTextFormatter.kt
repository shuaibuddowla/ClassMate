package com.shuaib.classmate.notices

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.shuaib.classmate.utils.ThemeColors
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme

object NoticeTextFormatter {
    private var markwon: Markwon? = null
    private var cachedIsDark: Boolean? = null

    fun init(context: Context) {
        val isDark = ThemeColors.isDark(context)
        if (markwon == null || cachedIsDark != isDark) {
            cachedIsDark = isDark

            val linkColor = ThemeColors.primarySoft(context)
            // Premium background chips for code snippets
            val codeBgColor = if (isDark) "#27272A".toColorInt() else "#F4F4F5".toColorInt()
            val codeTextColor = if (isDark) "#E4E4E7".toColorInt() else "#27272A".toColorInt()
            // Slate/zinc quote vertical bar
            val quoteBarColor = if (isDark) "#52525B".toColorInt() else "#D4D4D8".toColorInt()

            markwon = Markwon.builder(context.applicationContext)
                .usePlugin(object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        builder
                            .linkColor(linkColor)
                            .codeBackgroundColor(codeBgColor)
                            .codeTextColor(codeTextColor)
                            .codeBlockBackgroundColor(codeBgColor)
                            .codeBlockTextColor(codeTextColor)
                            .blockQuoteColor(quoteBarColor)
                            .blockQuoteWidth(8) // subtle vertical line
                            .headingBreakColor(Color.TRANSPARENT)
                            // Restrict heading sizes to look great in compact notice cards
                            .headingTextSizeMultipliers(floatArrayOf(1.25f, 1.18f, 1.12f, 1.08f, 1.05f, 1.0f))
                    }

                    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                        builder.linkResolver(object : LinkResolver {
                            override fun resolve(view: android.view.View, link: String) {
                                // Safe external link handling
                                if (link.startsWith("http://") || link.startsWith("https://")) {
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, link.toUri()).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        view.context.startActivity(intent)
                                    }.onFailure {
                                        Toast.makeText(view.context, "Cannot open link: $link", Toast.LENGTH_SHORT).show()
                                    }
                                } else if (link.startsWith("classmate://")) {
                                    // Safe deep link handling
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, link.toUri()).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        view.context.startActivity(intent)
                                    }
                                }
                            }
                        })
                    }
                })
                .build()
        }
    }

    fun format(context: Context, rawText: String): CharSequence {
        init(context)
        val formatter = markwon ?: return rawText
        return if (rawText.isBlank()) "" else formatter.toMarkdown(rawText)
    }

    /**
     * Strips Markdown formatting characters to produce a clean plain-text string.
     * Useful for search index matching, push notifications, and external sharing.
     */
    fun stripMarkdown(markdown: String): String {
        var text = markdown
        // Remove code blocks
        text = text.replace(Regex("```[a-zA-Z]*\\n([\\s\\S]*?)\\n```"), "$1")
        // Remove inline code
        text = text.replace(Regex("`([^`\\n]+?)`"), "$1")
        // Remove HTML tags
        text = text.replace(Regex("<[^>]*>"), "")
        // Remove links [title](url) -> title
        text = text.replace(Regex("\\[([^\\]]+)\\]\\(([^\\)]+)\\)"), "$1")
        // Remove images ![alt](url) -> empty
        text = text.replace(Regex("!\\[([^\\]]*)\\]\\(([^\\)]+)\\)"), "")
        // Remove headings: # heading -> heading
        text = text.replace(Regex("^(?:#+)\\s+(.+)$", RegexOption.MULTILINE), "$1")
        // Remove blockquotes: > quote -> quote
        text = text.replace(Regex("^>\\s?(.+)$", RegexOption.MULTILINE), "$1")
        // Remove bold and italics
        text = text.replace(Regex("\\*\\*([^\\*]+)\\*\\*"), "$1")
        text = text.replace(Regex("__([^_]+)__"), "$1")
        text = text.replace(Regex("\\*([^\\*]+)\\*"), "$1")
        text = text.replace(Regex("_([^_]+)_"), "$1")
        text = text.replace(Regex("~~([^~]+)~~"), "$1")
        // List bullets
        text = text.replace(Regex("^\\s*[-*\\u2022]\\s+", RegexOption.MULTILINE), "")
        // Numbered lists: 1. item -> item
        text = text.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
        return text.trim()
    }
}
