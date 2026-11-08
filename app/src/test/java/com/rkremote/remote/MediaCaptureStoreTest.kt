package com.rkremote.remote

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaCaptureStoreTest {
    @After
    fun clearStores() {
        CameraFrameStore.clear()
        MicrophoneChunkStore.clear()
    }

    @Test
    fun cameraStorePublishesLatestFrameAndClearsOnStop() {
        val first = byteArrayOf(1, 2, 3)
        val latest = byteArrayOf(4, 5, 6, 7)

        CameraFrameStore.setCapturing(true)
        CameraFrameStore.publish(first)
        CameraFrameStore.publish(latest, "image/webp")
        assertArrayEquals(latest, CameraFrameStore.latestJpeg)
        assertEquals("image/webp", CameraFrameStore.latestMimeType)

        CameraFrameStore.clear()
        assertNull(CameraFrameStore.latestJpeg)
        assertEquals("image/jpeg", CameraFrameStore.latestMimeType)
    }

    @Test
    fun cameraStoreRejectsFramesAfterStopAndAcceptsAfterExplicitRestart() {
        CameraFrameStore.setCapturing(true)
        CameraFrameStore.publish(byteArrayOf(1, 2))
        CameraFrameStore.setCapturing(false)
        CameraFrameStore.publish(byteArrayOf(3, 4))
        assertNull(CameraFrameStore.latestJpeg)

        CameraFrameStore.setCapturing(true)
        val resumed = byteArrayOf(5, 6)
        CameraFrameStore.publish(resumed)
        assertArrayEquals(resumed, CameraFrameStore.latestJpeg)
    }

    @Test
    fun microphoneStoreKeepsOnlyLatestChunkAndConsumesOnce() {
        MicrophoneChunkStore.setCapturing(true)
        MicrophoneChunkStore.publish(byteArrayOf(1, 2))
        val latest = byteArrayOf(3, 4, 5)
        MicrophoneChunkStore.publish(latest)

        assertArrayEquals(latest, MicrophoneChunkStore.take())
        assertNull(MicrophoneChunkStore.take())
    }

    @Test
    fun microphoneStoreClearsPendingAudioWhenStopped() {
        MicrophoneChunkStore.setCapturing(true)
        MicrophoneChunkStore.publish(byteArrayOf(8, 9))

        MicrophoneChunkStore.clear()

        assertNull(MicrophoneChunkStore.take())
    }

    @Test
    fun microphoneStoreRejectsLateChunkAfterStop() {
        MicrophoneChunkStore.setCapturing(true)
        MicrophoneChunkStore.publish(byteArrayOf(1, 2))
        MicrophoneChunkStore.setCapturing(false)

        MicrophoneChunkStore.publish(byteArrayOf(3, 4))

        assertNull(MicrophoneChunkStore.take())
    }
}
