package com.shuaib.classmate.notices

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoticeCacheStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSaveAndReadCacheIsolatedByUserAndBatch() = runBlocking {
        val rootDir = tempFolder.root
        val user1 = "user_abc"
        val user2 = "user_xyz"
        val batch1 = "batch_111"
        val batch2 = "batch_222"

        val payloadU1B1 = """{"feed":[{"id":"notice_1"}],"meta":"batch1"}"""
        val payloadU2B1 = """{"feed":[{"id":"notice_2"}],"meta":"user2_batch1"}"""

        // Save
        NoticeCacheStore.saveCache(rootDir, user1, batch1, payloadU1B1)
        NoticeCacheStore.saveCache(rootDir, user2, batch1, payloadU2B1)

        // Read user 1
        val readU1B1 = NoticeCacheStore.readCache(rootDir, user1, batch1)
        assertNotNull(readU1B1)
        assertEquals(payloadU1B1, readU1B1)

        // Read user 2
        val readU2B1 = NoticeCacheStore.readCache(rootDir, user2, batch1)
        assertNotNull(readU2B1)
        assertEquals(payloadU2B1, readU2B1)

        // Non-existent batch
        val readU1B2 = NoticeCacheStore.readCache(rootDir, user1, batch2)
        assertNull(readU1B2)
    }

    @Test
    fun testClearUserCachePurgesOnlyTargetUser() = runBlocking {
        val rootDir = tempFolder.root
        val user1 = "user_target"
        val user2 = "user_other"
        val batch = "batch_common"

        NoticeCacheStore.saveCache(rootDir, user1, batch, "data_user1")
        NoticeCacheStore.saveCache(rootDir, user2, batch, "data_user2")

        // Clear user 1
        NoticeCacheStore.clearUserCache(rootDir, user1)

        assertNull(NoticeCacheStore.readCache(rootDir, user1, batch))
        assertEquals("data_user2", NoticeCacheStore.readCache(rootDir, user2, batch))
    }
}
