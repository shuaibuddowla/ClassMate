package com.shuaib.classmate.activities

import org.junit.Assert.*
import org.junit.Test

class ClassMateNoticeRowsTest {
    private fun row(key:String, signature:String, layout:Int=1) = ClassMateNoticeRow(key,layout,signature) {}
    @Test fun refreshedCallbacksDoNotRebuildUnchangedCards() {
        assertTrue(ClassMateNoticeRows.DIFF.areContentsTheSame(row("notice:a","same"),row("notice:a","same")))
    }
    @Test fun engagementOrSearchChangesInvalidateOnlyTheirRow() {
        assertTrue(ClassMateNoticeRows.DIFF.areItemsTheSame(row("notice:a","old"),row("notice:a","new")))
        assertFalse(ClassMateNoticeRows.DIFF.areContentsTheSame(row("notice:a","old"),row("notice:a","new")))
    }
    @Test fun paginationAndSummaryCannotReuseNoticeIdentity() {
        assertFalse(ClassMateNoticeRows.DIFF.areItemsTheSame(row("notice:a","same"),row("paging","same")))
        assertFalse(ClassMateNoticeRows.DIFF.areItemsTheSame(row("summary","same",1),row("summary","same",2)))
    }
}
