package com.shuaib.classmate.fcm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassMatePushPolicyTest {
    @Test fun studentReceivesOwnBatchButNotAnotherBatch() {
        assertTrue(ClassMatePushPolicy.addressedToCurrentUser("prod","student","cse22",false,"prod","student","active","student","cse22"))
        assertFalse(ClassMatePushPolicy.addressedToCurrentUser("prod","student","cse23",false,"prod","student","active","student","cse22"))
    }
    @Test fun globalReleaseDoesNotBypassAccountOrEnvironmentIsolation() {
        assertTrue(ClassMatePushPolicy.addressedToCurrentUser("prod","student",null,true,"prod","student","active","student","cse22"))
        assertFalse(ClassMatePushPolicy.addressedToCurrentUser("staging","student",null,true,"prod","student","active","student","cse22"))
        assertFalse(ClassMatePushPolicy.addressedToCurrentUser("prod","previous-account",null,true,"prod","student","active","student","cse22"))
        assertFalse(ClassMatePushPolicy.addressedToCurrentUser("prod","student",null,true,"prod","student","pending","student","cse22"))
    }
}
