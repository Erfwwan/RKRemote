package com.rkremote.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SecureDataChannelFramerTest {
    @Test
    fun reassemblesLargePayloadEvenWhenFragmentsArriveOutOfOrder() {
        val sender = SecureDataChannelFramer()
        val receiver = SecureDataChannelFramer()
        val payload = ByteArray(60_123) { (it * 31).toByte() }
        val frames = sender.fragment(payload)
        assertTrue(frames.size > 1)

        var result: ByteArray? = null
        frames.reversed().forEach { frame ->
            val next = receiver.accept(frame)
            if (next != null) result = next
        }
        assertArrayEquals(payload, result)
    }

    @Test
    fun rejectsDuplicateFragmentAndOversizedMessage() {
        val sender = SecureDataChannelFramer()
        val receiver = SecureDataChannelFramer()
        val frames = sender.fragment(ByteArray(30_000) { 7 })
        assertNull(receiver.accept(frames.first()))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.accept(frames.first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            sender.fragment(ByteArray(RemoteMessage.MAX_MESSAGE_BYTES + 1))
        }
    }

    @Test
    fun rejectsShortNonFinalAndOversizedFinalFragments() {
        val sender = SecureDataChannelFramer()
        val payload = ByteArray(30_000) { (it * 7).toByte() }
        val frames = sender.fragment(payload)
        assertTrue(frames.size > 1)

        val shortFirst = frames.first().copyOf(frames.first().size - 1)
        assertThrows(IllegalArgumentException::class.java) {
            SecureDataChannelFramer().accept(shortFirst)
        }

        val last = frames.last()
        val oversizedLast = last.copyOf(last.size + 1)
        oversizedLast[oversizedLast.lastIndex] = 1
        assertThrows(IllegalArgumentException::class.java) {
            SecureDataChannelFramer().accept(oversizedLast)
        }
    }

    @Test
    fun rejectsCorruptedHeader() {
        val sender = SecureDataChannelFramer()
        val receiver = SecureDataChannelFramer()
        val frame = sender.fragment("payload".toByteArray()).single()
        ByteBuffer.wrap(frame).putInt(0, 0)
        assertThrows(IllegalArgumentException::class.java) { receiver.accept(frame) }
    }

    @Test
    fun expiresIncompleteMessagesAndReleasesInFlightSlots() {
        val sender = SecureDataChannelFramer()
        val firstPayload = ByteArray(30_000) { 3 }
        val secondPayload = ByteArray(30_000) { 9 }
        val firstFrames = sender.fragment(firstPayload)
        val secondFrames = sender.fragment(secondPayload)
        val receiver = SecureDataChannelFramer(
            maxInFlightMessages = 1,
            maxAgeMillis = 100L
        )

        assertNull(receiver.accept(firstFrames.first(), nowMillis = 1_000L))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.accept(secondFrames.first(), nowMillis = 1_050L)
        }

        assertNull(receiver.accept(secondFrames.first(), nowMillis = 1_101L))
        var result: ByteArray? = null
        secondFrames.drop(1).forEachIndexed { index, frame ->
            result = receiver.accept(frame, nowMillis = 1_102L + index)
        }
        assertArrayEquals(secondPayload, result)
    }

    @Test
    fun clearReleasesAllIncompleteMessageSlots() {
        val sender = SecureDataChannelFramer()
        val first = sender.fragment(ByteArray(30_000) { 1 })
        val secondPayload = ByteArray(30_000) { 2 }
        val second = sender.fragment(secondPayload)
        val receiver = SecureDataChannelFramer(maxInFlightMessages = 1)

        assertNull(receiver.accept(first.first()))
        receiver.clear()
        assertNull(receiver.accept(second.first()))
        var result: ByteArray? = null
        second.drop(1).forEach { frame -> result = receiver.accept(frame) }
        assertArrayEquals(secondPayload, result)
    }

}
