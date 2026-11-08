package com.rkremote.remote

/**
 * Latest-only H.264 publication point. No unbounded queue: a slow network
 * reader skips directly to the newest access unit on its next iteration.
 */
object ScreenVideoStore {
    data class Config(val width: Int, val height: Int, val csd0: ByteArray, val csd1: ByteArray)
    data class Frame(val sequence: Long, val ptsUs: Long, val flags: Int, val bytes: ByteArray)

    @Volatile private var config: Config? = null
    @Volatile private var latest: Frame? = null
    private var sequence = 0L

    @Synchronized fun configure(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray) {
        require(width in 2..480 && height in 2..480)
        require(csd0.size <= MAX_CODEC_CONFIG && csd1.size <= MAX_CODEC_CONFIG)
        config = Config(width, height, csd0.copyOf(), csd1.copyOf())
        latest = null
        (this as java.lang.Object).notifyAll()
    }

    @Synchronized fun publish(ptsUs: Long, flags: Int, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_ACCESS_UNIT) return
        sequence += 1
        latest = Frame(sequence, ptsUs, flags, bytes.copyOf())
        (this as java.lang.Object).notifyAll()
    }

    @Synchronized fun awaitConfig(timeoutMs: Long): Config? {
        val end = System.currentTimeMillis() + timeoutMs
        while (config == null && System.currentTimeMillis() < end) {
            (this as java.lang.Object).wait((end - System.currentTimeMillis()).coerceAtLeast(1))
        }
        return config
    }

    @Synchronized fun awaitNext(afterSequence: Long, timeoutMs: Long): Frame? {
        val end = System.currentTimeMillis() + timeoutMs
        while ((latest == null || latest!!.sequence <= afterSequence) &&
            config != null && System.currentTimeMillis() < end) {
            (this as java.lang.Object).wait((end - System.currentTimeMillis()).coerceAtLeast(1))
        }
        return latest?.takeIf { it.sequence > afterSequence }
    }

    @Synchronized fun clear() {
        config = null
        latest = null
        (this as java.lang.Object).notifyAll()
    }

    private const val MAX_CODEC_CONFIG = 64 * 1024
    const val MAX_ACCESS_UNIT = 2 * 1024 * 1024
}
