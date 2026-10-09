package com.shuaib.classmate.activities

import org.junit.Assert.*
import org.junit.Test

class ClassMateNoticeTextTest {

    @Test
    fun isExpandableDetectsLongNoticesAndMultilineNotices() {
        // Short notice: <350 chars and <6 newlines
        assertFalse(ClassMateNoticeText.isExpandable("Hello students! Tomorrow is a regular class day."))

        // Long notice with no newlines (> 350 chars)
        val longNotice = "A".repeat(400)
        assertTrue(ClassMateNoticeText.isExpandable(longNotice))

        // Notice with multiple newlines (>= 6)
        val multiLineNotice = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6\nLine 7"
        assertTrue(ClassMateNoticeText.isExpandable(multiLineNotice))
    }

    @Test
    fun constantsMatchRequiredBehavior() {
        assertEquals(6, ClassMateNoticeText.MAX_PREVIEW_LINES)
        assertEquals("… see more", ClassMateNoticeText.SUFFIX_MORE)
        assertEquals("  see less", ClassMateNoticeText.SUFFIX_LESS)
    }

    @Test
    fun urlPatternMatchesHttpHttpsAndWww() {
        val text = "Visit https://classmate.app and http://test.org and www.google.com for info"
        val matcher = ClassMateNoticeText.WEB_URL_PATTERN.matcher(text)
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            matches.add(matcher.group())
        }
        assertEquals(listOf("https://classmate.app", "http://test.org", "www.google.com"), matches)
    }

    @Test
    fun urlPatternExcludesTrailingPunctuation() {
        val text = "Check https://classmate.app. Also (www.google.com), and http://test.org/path!"
        val matcher = ClassMateNoticeText.WEB_URL_PATTERN.matcher(text)
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            matches.add(matcher.group())
        }
        assertEquals(listOf("https://classmate.app", "www.google.com", "http://test.org/path"), matches)
    }

    @Test
    fun normalizeUrlHandlesSchemesProperly() {
        assertEquals("https://www.google.com", ClassMateNoticeText.normalizeUrl("www.google.com"))
        assertEquals("http://test.org", ClassMateNoticeText.normalizeUrl("http://test.org"))
        assertEquals("https://classmate.app", ClassMateNoticeText.normalizeUrl("https://classmate.app"))
        assertEquals("https://classmate.vercel.app", ClassMateNoticeText.normalizeUrl("classmate.vercel.app"))
        assertEquals("https://google.com", ClassMateNoticeText.normalizeUrl("google.com"))
    }

    @Test
    fun urlPatternMatchesNakedDomainsLikeClassmateVercelApp() {
        val text = "Check classmate.vercel.app for notices, and visit drive.google.com/file/123."
        val matcher = ClassMateNoticeText.WEB_URL_PATTERN.matcher(text)
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            matches.add(matcher.group())
        }
        assertEquals(listOf("classmate.vercel.app", "drive.google.com/file/123"), matches)
    }

    @Test
    fun urlPatternDoesNotMatchRegularAbbreviations() {
        val text = "Dr. Smith said e.g. this is regular text and not a link."
        val matcher = ClassMateNoticeText.WEB_URL_PATTERN.matcher(text)
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            matches.add(matcher.group())
        }
        assertTrue(matches.isEmpty())
    }
}

