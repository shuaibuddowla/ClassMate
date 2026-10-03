package com.shuaib.classmate.activities

/** Persisted completion takes precedence over an older activity snapshot. */
internal object ClassMateWelcomeProgress {
    fun restore(persisted: Int?, saved: Int): Int =
        (persisted ?: saved).takeIf { it in 1..3 } ?: 0

    fun canMove(current: Int, expected: Int): Boolean =
        current in 1..3 && current == expected
}
