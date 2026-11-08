package com.rkremote.remote

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PttAudioQueueTest {

    @Before
    fun resetQueue() {
        PttAudioQueue.setEnabled(false)
        PttAudioQueue.clear()
    }

    @After
    fun disableQueue() {
        PttAudioQueue.setEnabled(false)
        PttAudioQueue.clear()
    }

    @Test
    fun rejectsAudioUntilTargetOwnerEnablesSpeaker() {
        assertFalse(PttAudioQueue.isEnabled())
        assertFalse(PttAudioQueue.offer(byteArrayOf(1, 0)))
        assertNull(PttAudioQueue.poll(0))
    }

    @Test
    fun rejectsEmptyOversizedAndMisalignedPcmChunks() {
        PttAudioQueue.setEnabled(true)

        assertFalse(PttAudioQueue.offer(byteArrayOf()))
        assertFalse(PttAudioQueue.offer(byteArrayOf(1)))
        assertFalse(PttAudioQueue.offer(ByteArray(PttAudioQueue.MAX_CHUNK_BYTES + 2)))
        assertTrue(PttAudioQueue.offer(byteArrayOf(1, 0)))
    }

    @Test
    fun dropsOldestChunkWhenQueueIsFullToBoundLatency() {
        PttAudioQueue.setEnabled(true)
        repeat(PttAudioQueue.MAX_QUEUED_CHUNKS_FOR_STATUS) { index ->
            assertTrue(PttAudioQueue.offer(byteArrayOf(index.toByte(), 0)))
        }

        assertTrue(PttAudioQueue.offer(byteArrayOf(99, 0)))
        assertArrayEquals(byteArrayOf(1, 0), PttAudioQueue.poll(0))
        repeat(PttAudioQueue.MAX_QUEUED_CHUNKS_FOR_STATUS - 2) {
            assertTrue(PttAudioQueue.poll(0) != null)
        }
        assertArrayEquals(byteArrayOf(99, 0), PttAudioQueue.poll(0))
        assertNull(PttAudioQueue.poll(0))
    }

    @Test
    fun disablingSpeakerImmediatelyClearsQueuedAudio() {
        PttAudioQueue.setEnabled(true)
        assertTrue(PttAudioQueue.offer(byteArrayOf(1, 0)))

        PttAudioQueue.setEnabled(false)

        assertFalse(PttAudioQueue.isEnabled())
        assertNull(PttAudioQueue.poll(0))
        assertFalse(PttAudioQueue.offer(byteArrayOf(2, 0)))

        PttAudioQueue.setEnabled(true)
        assertNull(PttAudioQueue.poll(0))
    }
}
