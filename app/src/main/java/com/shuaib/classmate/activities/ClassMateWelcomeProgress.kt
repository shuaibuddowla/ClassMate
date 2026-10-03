package com.shuaib.classmate.activities

/** Persisted completion takes precedence over an older activity snapshot. */
internal object ClassMateWelcomeProgress {
    fun afterSignIn(newAccount: Boolean, profileComplete: Boolean, notificationsEnabled: Boolean): Int = when {
        newAccount -> 1
        !profileComplete -> 2
        !notificationsEnabled -> 3
        else -> 0
    }

    fun resume(step: Int, profileComplete: Boolean, notificationsEnabled: Boolean): Int = when {
        step in 1..2 && profileComplete -> if (notificationsEnabled) 0 else 3
        step == 3 && notificationsEnabled -> 0
        else -> step
    }

    fun restore(persisted: Int?, saved: Int): Int =
        (persisted ?: saved).takeIf { it in 1..3 } ?: 0

    fun canMove(current: Int, expected: Int): Boolean =
        current in 1..3 && current == expected
}
