package com.shuaib.classmate.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassMateWelcomeProgressTest {
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
