package com.rkremote.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-owned Cloudflare API Token storage, encrypted locally with Android Keystore. */
object CloudflareApiTokenStore {
    private const val PREFS = "rkremote_cf_api_token"
    private const val KEY_ALIAS = "rkremote.cloudflare.api-token.aes.v1"
    private const val CIPHERTEXT = "token_blob"
    private const val IV = "token_iv"

    fun save(context: Context, token: String) {
        require(token.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun token(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = Base64.decode(prefs.getString(CIPHERTEXT, null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString(IV, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun hasToken(context: Context): Boolean = token(context) != null

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

object CloudflareAccountApi {
    private const val API = "https://api.cloudflare.com/client/v4"
    private const val WORKER_NAME = "rkremote-signaling"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Updates an already manually-created Worker; it does not create accounts or Workers. */
    fun updateExistingWorker(context: Context, apiToken: String, accountId: String) {
        require(apiToken.isNotBlank()) { "Cloudflare API Token is empty" }
        require(accountId.matches(Regex("[a-fA-F0-9]{32}"))) { "Invalid Cloudflare Account ID" }

        val scriptUrl = "$API/accounts/$accountId/workers/scripts/$WORKER_NAME"
        request(
            Request.Builder().url(scriptUrl)
                .header("Authorization", "Bearer $apiToken")
                .get().build()
        )

        val bindings = JSONArray().put(
            JSONObject()
                .put("type", "durable_object_namespace")
                .put("name", "SIGNAL_ROOMS")
                .put("class_name", "SignalRoom")
        )
        val metadata = JSONObject()
            .put("main_module", "index.js")
            .put("compatibility_date", "2026-11-08")
            .put("bindings", bindings)

        val workerSource = context.assets.open("cloudflare/worker.js")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "metadata",
                null,
                metadata.toString().toRequestBody("application/json".toMediaType())
            )
            .addFormDataPart(
                "index.js",
                "index.js",
                workerSource.toRequestBody("application/javascript+module".toMediaType())
            )
            .build()

        request(
            Request.Builder().url(scriptUrl)
                .header("Authorization", "Bearer $apiToken")
                .put(body).build()
        )
    }

    private fun request(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw IllegalStateException("Cloudflare API returned an unreadable response (HTTP ${response.code})")
            }
            if (!response.isSuccessful || !json.optBoolean("success", false)) {
                val errors = json.optJSONArray("errors")
                val first = errors?.optJSONObject(0)?.optString("message")
                throw IllegalStateException(
                    first?.takeIf { it.isNotBlank() }
                        ?: "Cloudflare API request failed (HTTP ${response.code})"
                )
            }
            return json
        }
    }
}
