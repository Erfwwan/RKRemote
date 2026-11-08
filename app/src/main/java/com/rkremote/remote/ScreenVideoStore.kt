package com.rkremote.remote

/**
 * Latest-only H.264 publication point. A slow reader skips stale access units;
 * the store never accumulates a frame queue.
 */
object ScreenVideoStore {
    data class Config(val width: Int, val height: Int, val csd0: ByteArray, val csd1: ByteArray)
    data class Frame(val sequence: Long, val ptsUs: Long, val flags: Int, val bytes: ByteArray)

    private val monitor = java.lang.Object()
    @Volatile private var config: Config? = null
    @Volatile private var latest: Frame? = null
    private var sequence = 0L

    fun configure(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray) = synchronized(monitor) {
        require(width in 2..480 && height in 2..480)
        require(csd0.size <= MAX_CODEC_CONFIG && csd1.size <= MAX_CODEC_CONFIG)
        config = Config(width, height, csd0.copyOf(), csd1.copyOf())
        latest = null
        monitor.notifyAll()
    }

    fun publish(ptsUs: Long, flags: Int, bytes: ByteArray) = synchronized(monitor) {
        if (bytes.isEmpty() || bytes.size > MAX_ACCESS_UNIT) return@synchronized
        sequence += 1
        latest = Frame(sequence, ptsUs, flags, bytes.copyOf())
        monitor.notifyAll()
    }

    fun hasConfig(): Boolean = config != null

    fun awaitConfig(timeoutMs: Long): Config? = synchronized(monitor) {
        val end = System.currentTimeMillis() + timeoutMs
        while (config == null && System.currentTimeMillis() < end) {
            monitor.wait((end - System.currentTimeMillis()).coerceAtLeast(1))
        }
        config
    }

    fun awaitNext(afterSequence: Long, timeoutMs: Long): Frame? = synchronized(monitor) {
        val end = System.currentTimeMillis() + timeoutMs
        while ((latest == null || latest!!.sequence <= afterSequence) &&
            config != null && System.currentTimeMillis() < end) {
            monitor.wait((end - System.currentTimeMillis()).coerceAtLeast(1))
        }
        latest?.takeIf { it.sequence > afterSequence }
    }

    fun clear() = synchronized(monitor) {
        config = null
        latest = null
        monitor.notifyAll()
    }

    private const val MAX_CODEC_CONFIG = 64 * 1024
    const val MAX_ACCESS_UNIT = 2 * 1024 * 1024
}
