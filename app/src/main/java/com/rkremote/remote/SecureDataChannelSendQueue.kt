package com.rkremote.remote

import java.util.ArrayDeque

/**
 * Bounded FIFO for complete logical messages waiting for a WebRTC DataChannel.
 *
 * Frames are released as soon as libwebrtc accepts them. A message is never
 * interleaved with another message, preserving the RKD1 reassembly contract
 * and the sequence order assigned by SecureInternetPeer.
 */
internal class SecureDataChannelSendQueue(
    private val maxQueuedBytes: Long = DEFAULT_MAX_QUEUED_BYTES,
    private val maxQueuedMessages: Int = DEFAULT_MAX_QUEUED_MESSAGES,
    private val maxBufferedAmount: Long = DEFAULT_MAX_BUFFERED_AMOUNT
) {
    private data class PendingMessage(
        val frames: ArrayDeque<ByteArray>
    )

    private val messages = ArrayDeque<PendingMessage>()
    private var queuedBytes = 0L

    init {
        require(maxQueuedBytes > 0L)
        require(maxQueuedMessages > 0)
        require(maxBufferedAmount > 0L)
    }

    @Synchronized
    fun enqueue(frames: List<ByteArray>): Boolean {
        if (frames.isEmpty() || messages.size >= maxQueuedMessages) return false
        val size = frames.sumOf { it.size.toLong() }
        if (size <= 0L || size > maxQueuedBytes || queuedBytes > maxQueuedBytes - size) return false
        val pending = PendingMessage(ArrayDeque(frames))
        messages.addLast(pending)
        queuedBytes += size
        return true
    }

    /**
     * Sends only while both the application queue and native DataChannel
     * remain below their watermarks. False from sendFrame means backpressure;
     * the unsent frame remains queued and will be retried on the low-buffer callback.
     */
    @Synchronized
    fun drain(
        bufferedAmount: () -> Long,
        channelOpen: () -> Boolean,
        sendFrame: (ByteArray) -> Boolean
    ): Int {
        var sent = 0
        while (messages.isNotEmpty() && channelOpen()) {
            val pending = messages.peekFirst() ?: break
            val frame = pending.frames.peekFirst()
            if (frame == null) {
                messages.removeFirst()
                continue
            }
            val buffered = bufferedAmount()
            if (buffered < 0L || frame.size.toLong() > maxBufferedAmount ||
                buffered > maxBufferedAmount - frame.size
            ) break
            if (!sendFrame(frame)) break

            pending.frames.removeFirst()
            queuedBytes -= frame.size.toLong()
            sent++
            if (pending.frames.isEmpty()) messages.removeFirst()
        }
        return sent
    }

    @Synchronized
    fun queuedBytes(): Long = queuedBytes

    @Synchronized
    fun queuedMessages(): Int = messages.size

    @Synchronized
    fun clear() {
        messages.clear()
        queuedBytes = 0L
    }

    companion object {
        const val DEFAULT_MAX_QUEUED_BYTES = 256L * 1024L
        const val DEFAULT_MAX_QUEUED_MESSAGES = 32
        const val DEFAULT_MAX_BUFFERED_AMOUNT = 256L * 1024L
        const val BUFFERED_AMOUNT_LOW_THRESHOLD = 64L * 1024L
    }
}
