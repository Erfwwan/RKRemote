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
        val payload = ByteArray(700_123) { (it * 31).toByte() }
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
            sender.fragment(ByteArray(4 * 1024 * 1024 + 1))
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
}
