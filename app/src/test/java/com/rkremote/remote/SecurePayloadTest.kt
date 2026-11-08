package com.rkremote.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecurePayloadTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun encryptsAndDecrypts() {
        val crypto = SecurePayload(key)
        val clear = "owner-approved payload".toByteArray()
        val encrypted = crypto.encrypt(clear, "metadata".toByteArray())
        assertArrayEquals(clear, crypto.decrypt(encrypted, "metadata".toByteArray()))
    }

    @Test fun eachEncryptionUsesFreshNonce() {
        val crypto = SecurePayload(key)
        val first = crypto.encrypt("same".toByteArray())
        val second = crypto.encrypt("same".toByteArray())
        assertNotEquals(first.nonceBase64, second.nonceBase64)
    }

    @Test fun alteredAssociatedDataFailsAuthentication() {
        val crypto = SecurePayload(key)
        val encrypted = crypto.encrypt("secret".toByteArray(), "session-A".toByteArray())
        assertThrows(Exception::class.java) {
            crypto.decrypt(encrypted, "session-B".toByteArray())
        }
    }

    @Test fun wrongKeyFailsAuthentication() {
        val encrypted = SecurePayload(key).encrypt("secret".toByteArray())
        assertThrows(Exception::class.java) {
            SecurePayload(ByteArray(32) { 9 }).decrypt(encrypted)
        }
    }
}
