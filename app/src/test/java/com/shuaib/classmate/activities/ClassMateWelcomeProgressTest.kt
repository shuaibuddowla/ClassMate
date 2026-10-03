package com.shuaib.classmate.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassMateWelcomeProgressTest {
    @Test fun newAccountStartsWithAccountDetection() {
        assertEquals(1,ClassMateWelcomeProgress.afterSignIn(true,false,false))
    }

    @Test fun completedExistingAccountOnlyNeedsDisabledNotificationPrompt() {
        assertEquals(0,ClassMateWelcomeProgress.afterSignIn(false,true,true))
        assertEquals(3,ClassMateWelcomeProgress.afterSignIn(false,true,false))
    }

    @Test fun incompleteExistingAccountResumesProfileWithoutAccountDetection() {
        assertEquals(2,ClassMateWelcomeProgress.afterSignIn(false,false,true))
        assertEquals(2,ClassMateWelcomeProgress.afterSignIn(false,false,false))
    }

    @Test fun serverCompletionOverridesOldProfileStepIncludingOnAnotherDevice() {
        assertEquals(0,ClassMateWelcomeProgress.resume(2,true,true))
        assertEquals(3,ClassMateWelcomeProgress.resume(2,true,false))
        assertEquals(3,ClassMateWelcomeProgress.resume(1,true,false))
    }

    @Test fun ordinaryResumeDoesNotCreateNewNotificationPrompt() {
        assertEquals(0,ClassMateWelcomeProgress.resume(0,true,false))
        assertEquals(0,ClassMateWelcomeProgress.resume(3,true,true))
        assertEquals(2,ClassMateWelcomeProgress.resume(2,false,false))
    }

    @Test fun savedProfileDoesNotRepeatAfterActivityRecreation() {
        assertEquals(3,ClassMateWelcomeProgress.restore(3,2))
        assertFalse(ClassMateWelcomeProgress.canMove(3,2))
        assertEquals(0,ClassMateWelcomeProgress.restore(0,2))
    }

    @Test fun completedFlowDoesNotRestartFromStalePermissionSnapshot() {
        assertEquals(0,ClassMateWelcomeProgress.restore(0,3))
    }

    @Test fun recreationResumesCurrentStepWithoutRepeatingEarlierSteps() {
        assertEquals(2,ClassMateWelcomeProgress.restore(2,1))
        assertEquals(3,ClassMateWelcomeProgress.restore(3,2))
        assertEquals(2,ClassMateWelcomeProgress.restore(null,2))
    }

    @Test fun staleOrDuplicateCallbacksCannotAdvanceTheFlow() {
        assertTrue(ClassMateWelcomeProgress.canMove(1,1))
        assertFalse(ClassMateWelcomeProgress.canMove(2,1))
        assertFalse(ClassMateWelcomeProgress.canMove(0,3))
    }
}
