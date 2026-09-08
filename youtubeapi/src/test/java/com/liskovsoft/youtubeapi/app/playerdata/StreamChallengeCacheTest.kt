package com.liskovsoft.youtubeapi.app.playerdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamChallengeCacheTest {
    private val playerUrlA = "https://www.youtube.com/s/player/e937390a/player_ias.vflset/en_US/base.js"
    private val playerUrlB = "https://www.youtube.com/s/player/f1234567/player_ias.vflset/en_US/base.js"

    @Before
    fun setUp() {
        StreamChallengeCache.clear()
    }

    @Test
    fun testBasicPutAndGet() {
        StreamChallengeCache.putNSig(playerUrlA, "raw_n_token", "decoded_n_token")
        StreamChallengeCache.putSig(playerUrlA, "raw_s_token", "decoded_s_token")

        assertEquals("decoded_n_token", StreamChallengeCache.getNSig(playerUrlA, "raw_n_token"))
        assertEquals("decoded_s_token", StreamChallengeCache.getSig(playerUrlA, "raw_s_token"))

        assertNull(StreamChallengeCache.getNSig(playerUrlA, "non_existent"))
        assertNull(StreamChallengeCache.getSig(playerUrlA, "non_existent"))
    }

    @Test
    fun testPlayerUrlIsolation() {
        // Same raw token, but different player versions should not collide
        StreamChallengeCache.putNSig(playerUrlA, "same_token", "decoded_version_A")
        StreamChallengeCache.putNSig(playerUrlB, "same_token", "decoded_version_B")

        assertEquals("decoded_version_A", StreamChallengeCache.getNSig(playerUrlA, "same_token"))
        assertEquals("decoded_version_B", StreamChallengeCache.getNSig(playerUrlB, "same_token"))
    }

    @Test
    fun testLruEviction() {
        // Fill cache past MAX_N_CACHE_SIZE (512)
        for (i in 0..520) {
            StreamChallengeCache.putNSig(playerUrlA, "token_$i", "decoded_$i")
        }

        // Cache size should not exceed 512
        val (nSize, _) = StreamChallengeCache.size()
        assertEquals(512, nSize)

        // The earliest entries (e.g. token_0 ... token_8) should have been evicted
        assertNull(StreamChallengeCache.getNSig(playerUrlA, "token_0"))
        assertNull(StreamChallengeCache.getNSig(playerUrlA, "token_8"))

        // The latest entries should be present
        assertNotNull(StreamChallengeCache.getNSig(playerUrlA, "token_520"))
        assertNotNull(StreamChallengeCache.getNSig(playerUrlA, "token_519"))
    }

    @Test
    fun testConcurrentAccess() {
        val threads = 8
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)

        for (t in 0 until threads) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val token = "concurrent_${t}_$i"
                        StreamChallengeCache.putNSig(playerUrlA, token, "val_$i")
                        val retrieved = StreamChallengeCache.getNSig(playerUrlA, token)
                        assertEquals("val_$i", retrieved)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        assertEquals(true, completed)
    }
}
