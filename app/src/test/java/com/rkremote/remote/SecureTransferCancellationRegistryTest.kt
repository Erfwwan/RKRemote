package com.rkremote.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureTransferCancellationRegistryTest {
    @Test
    fun cancellationIsBoundToOneTransferId() {
        val registry = SecureTransferCancellationRegistry()
        assertTrue(registry.register("upload-1"))
        assertTrue(registry.register("download-2"))

        assertFalse(registry.cancel("unknown"))
        assertTrue(registry.cancel("upload-1"))
        assertTrue(registry.isCancelled("upload-1"))
        assertFalse(registry.isCancelled("download-2"))
        assertTrue(registry.cancel("download-2"))
    }

    @Test
    fun duplicateRegistrationAndRepeatedCancellationAreRejected() {
        val registry = SecureTransferCancellationRegistry()
        assertTrue(registry.register("transfer-1"))
        assertFalse(registry.register("transfer-1"))
        assertTrue(registry.cancel("transfer-1"))
        assertFalse(registry.cancel("transfer-1"))
    }

    @Test
    fun finishingTransferRemovesItsCancellationState() {
        val registry = SecureTransferCancellationRegistry()
        assertTrue(registry.register("transfer-1"))
        registry.finish("transfer-1")
        assertTrue(registry.isCancelled("transfer-1"))
        assertFalse(registry.cancel("transfer-1"))
        assertTrue(registry.register("transfer-1"))
    }

    @Test
    fun clearCancelsAllActiveTransfers() {
        val registry = SecureTransferCancellationRegistry()
        assertTrue(registry.register("transfer-1"))
        assertTrue(registry.register("transfer-2"))
        registry.clear()
        assertTrue(registry.isCancelled("transfer-1"))
        assertTrue(registry.isCancelled("transfer-2"))
        assertFalse(registry.cancel("transfer-1"))
    }
}
