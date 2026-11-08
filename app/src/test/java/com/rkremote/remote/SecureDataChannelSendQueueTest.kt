package com.rkremote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureDataChannelSendQueueTest {
    @Test
    fun preservesMessageAndFrameOrderAcrossBackpressure() {
        val queue = SecureDataChannelSendQueue(
            maxQueuedBytes = 10_000,
            maxQueuedMessages = 4,
            maxBufferedAmount = 100
        )
        val sent = mutableListOf<String>()
        assertTrue(queue.enqueue(listOf("a1".toByteArray(), "a2".toByteArray())))
        assertTrue(queue.enqueue(listOf("b1".toByteArray())))

        var buffered = 99L
        assertEquals(0, queue.drain({ buffered }, { true }) {
            sent += it.decodeToString()
            true
        })
        assertEquals(6L, queue.queuedBytes())

        buffered = 0L
        assertEquals(3, queue.drain({ buffered }, { true }) {
            sent += it.decodeToString()
            true
        })
        assertEquals(listOf("a1", "a2", "b1"), sent)
        assertEquals(0L, queue.queuedBytes())
        assertEquals(0, queue.queuedMessages())
    }

    @Test
    fun boundedQueueRejectsOverflowWithoutDroppingEarlierMessages() {
        val queue = SecureDataChannelSendQueue(
            maxQueuedBytes = 5,
            maxQueuedMessages = 2,
            maxBufferedAmount = 10
        )
        assertTrue(queue.enqueue(listOf(byteArrayOf(1, 2, 3))))
        assertFalse(queue.enqueue(listOf(byteArrayOf(4, 5, 6))))
        assertEquals(3L, queue.queuedBytes())
        assertEquals(1, queue.queuedMessages())
    }

    @Test
    fun pausesWhenChannelIsNotOpenAndCanDrainAfterReconnect() {
        val queue = SecureDataChannelSendQueue()
        val sent = mutableListOf<Byte>()
        assertTrue(queue.enqueue(listOf(byteArrayOf(1), byteArrayOf(2))))
        assertEquals(0, queue.drain({ 0L }, { false }) {
            sent += it[0]
            true
        })
        assertEquals(2L, queue.queuedBytes())

        assertEquals(2, queue.drain({ 0L }, { true }) {
            sent += it[0]
            true
        })
        assertEquals(listOf(1.toByte(), 2.toByte()), sent)
        assertEquals(0L, queue.queuedBytes())
    }

    @Test
    fun retainsAFrameWhenNativeSendReportsBackpressure() {
        val queue = SecureDataChannelSendQueue()
        assertTrue(queue.enqueue(listOf(byteArrayOf(9))))
        assertEquals(0, queue.drain({ 0L }, { true }) { false })
        assertEquals(1L, queue.queuedBytes())
        assertEquals(1, queue.drain({ 0L }, { true }) { true })
        assertEquals(0L, queue.queuedBytes())
    }

    @Test
    fun clearReleasesAllQueuedFrames() {
        val queue = SecureDataChannelSendQueue()
        assertTrue(queue.enqueue(listOf(ByteArray(128), ByteArray(256))))
        queue.clear()
        assertEquals(0L, queue.queuedBytes())
        assertEquals(0, queue.queuedMessages())
    }
}
