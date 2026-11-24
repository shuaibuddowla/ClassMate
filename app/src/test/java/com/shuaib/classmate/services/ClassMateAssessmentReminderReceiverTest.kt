package com.shuaib.classmate.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassMateAssessmentReminderReceiverTest {

    @Test
    fun testNotificationTagFormatting() {
        val tag24 = ClassMateAssessmentReminderReceiver.notificationTag("asmt_xyz", "24h")
        val tag2 = ClassMateAssessmentReminderReceiver.notificationTag("asmt_xyz", "2h")

        assertEquals("asmt_asmt_xyz_24h", tag24)
        assertEquals("asmt_asmt_xyz_2h", tag2)
        assertNotEquals(tag24, tag2)
    }

    @Test
    fun testRequestCodeDeterministicAndPositive() {
        val code1 = ClassMateAssessmentReminderReceiver.requestCode("asmt_123", "24h")
        val code2 = ClassMateAssessmentReminderReceiver.requestCode("asmt_123", "24h")
        val code3 = ClassMateAssessmentReminderReceiver.requestCode("asmt_123", "2h")

        assertEquals(code1, code2)
        assertNotEquals(code1, code3)
        assertTrue("Request code must be positive 31-bit integer", code1 >= 0)
        assertTrue("Request code must be positive 31-bit integer", code3 >= 0)
    }
}
