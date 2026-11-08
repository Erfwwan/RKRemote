package com.rkremote.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SecureRelayFrameCodecTest {
    @Test
    fun compressedPayloadRoundTripsAcrossRelayFrames() {
        val pattern = "RKRemote encrypted relay ".toByteArray()
        val source = ByteArray(48 * 1024) { pattern[it % pattern.size] }
        val sender = SecureRelayFrameCodec()
        val receiver = SecureRelayFrameCodec()

        val frames = sender.encode(source)
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.size <= 12 * 1024 - 17 })

        var decoded: ByteArray? = null
        frames.forEach { frame ->
            receiver.accept(frame)?.let { decoded = it }
        }
        assertArrayEquals(source, decoded)
    }

    @Test
    fun incompressiblePayloadIsBoundedAndRoundTrips() {
        val source = ByteArray(64 * 1024).also(SecureRandom()::nextBytes)
        val sender = SecureRelayFrameCodec()
        val receiver = SecureRelayFrameCodec()

        val frames = sender.encode(source)
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.size <= 12 * 1024 - 17 })

        var decoded: ByteArray? = null
        frames.forEach { frame ->
            receiver.accept(frame)?.let { decoded = it }
        }
        assertArrayEquals(source, decoded)
    }
}
