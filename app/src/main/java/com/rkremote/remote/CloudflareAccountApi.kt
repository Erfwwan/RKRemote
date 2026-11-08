package com.rkremote.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
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

object CloudflareOAuth {
    const val REDIRECT_URI = "com.rkremote.app:/oauth2redirect"
    const val REQUIRED_SCOPES = "account.read workers-platform.read workers-platform.write"
    val serviceConfiguration = AuthorizationServiceConfiguration(
        android.net.Uri.parse("https://dash.cloudflare.com/oauth2/auth"),
        android.net.Uri.parse("https://dash.cloudflare.com/oauth2/token"),
        null,
        android.net.Uri.parse("https://dash.cloudflare.com/oauth2/revoke")
    )

    fun authorizationRequest(clientId: String): AuthorizationRequest {
        require(clientId.isNotBlank() && !clientId.contains("REPLACE")) {
            "Cloudflare OAuth Client ID is not configured in the app build"
        }
        return AuthorizationRequest.Builder(
            serviceConfiguration,
            clientId,
            ResponseTypeValues.CODE,
            android.net.Uri.parse(REDIRECT_URI)
        ).setScope(REQUIRED_SCOPES).build()
    }
}

/** Stores Cloudflare OAuth tokens encrypted with an Android Keystore AES-GCM key. */
object CloudflareTokenStore {
    private const val PREFS = "rkremote_cf_oauth_encrypted"
    private const val KEY_ALIAS = "rkremote.cloudflare.oauth.aes.v1"
    private const val CIPHERTEXT = "token_blob"
    private const val IV = "token_iv"

    fun save(context: Context, accessToken: String, refreshToken: String?, expiresAtMillis: Long) {
        require(accessToken.isNotBlank())
        val payload = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken ?: JSONObject.NULL)
            .put("expires_at", expiresAtMillis)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(payload)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun read(context: Context): JSONObject? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = Base64.decode(prefs.getString(CIPHERTEXT, null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString(IV, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        JSONObject(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
    }.getOrNull()

    fun accessToken(context: Context): String? =
        read(context)?.optString("access_token")?.takeIf { it.isNotBlank() }

    fun refreshToken(context: Context): String? =
        read(context)?.optString("refresh_token")?.takeIf { it.isNotBlank() && it != "null" }

    fun expiresAt(context: Context): Long = read(context)?.optLong("expires_at", 0L) ?: 0L

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
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

data class CloudflareAccount(val id: String, val name: String)

object CloudflareAccountApi {
    private const val API = "https://api.cloudflare.com/client/v4"
    private const val WORKER_NAME = "rkremote-signaling"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun listAccounts(accessToken: String): List<CloudflareAccount> {
        val json = request(
            Request.Builder().url("$API/accounts?per_page=50")
                .header("Authorization", "Bearer $accessToken").get().build()
        )
        val result = json.optJSONArray("result") ?: JSONArray()
        return (0 until result.length()).mapNotNull { index ->
            val item = result.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) null else CloudflareAccount(id, item.optString("name", "Cloudflare account"))
        }
    }

    fun deployWorker(context: Context, accessToken: String, accountId: String): String {
        require(accountId.matches(Regex("[a-fA-F0-9]{16,64}"))) { "Invalid Cloudflare account ID" }
        val scriptUrl = "$API/accounts/$accountId/workers/scripts/$WORKER_NAME"
        val existing = runCatching {
            request(
                Request.Builder().url(scriptUrl)
                    .header("Authorization", "Bearer $accessToken").get().build()
            ).optJSONObject("result")
        }.getOrNull()
        val hasMigration = existing?.optString("migration_tag") != "v1"
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
        if (hasMigration) {
            metadata.put(
                "migrations",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "v1")
                        .put("new_sqlite_classes", JSONArray().put("SignalRoom"))
                )
            )
        }
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
                .header("Authorization", "Bearer $accessToken")
                .put(body).build()
        )

        val subdomainUrl = "$scriptUrl/subdomain"
        request(
            Request.Builder().url(subdomainUrl)
                .header("Authorization", "Bearer $accessToken")
                .post(
                    JSONObject().put("enabled", true).toString()
                        .toRequestBody("application/json".toMediaType())
                ).build()
        )
        val accountSubdomainUrl = "$API/accounts/$accountId/workers/subdomain"
        val subdomainJson = runCatching {
            request(
                Request.Builder().url(accountSubdomainUrl)
                    .header("Authorization", "Bearer $accessToken").get().build()
            )
        }.getOrElse {
            val generated = "rkremote-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            request(
                Request.Builder().url(accountSubdomainUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .put(
                        JSONObject().put("subdomain", generated).toString()
                            .toRequestBody("application/json".toMediaType())
                    ).build()
            )
        }
        val subdomain = subdomainJson.optJSONObject("result")?.optString("subdomain").orEmpty()
        require(subdomain.isNotBlank()) { "Cloudflare did not return a workers.dev subdomain" }
        return "wss://$WORKER_NAME.$subdomain.workers.dev"
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
                throw IllegalStateException(first?.takeIf { it.isNotBlank() } ?: "Cloudflare API request failed (HTTP ${response.code})")
            }
            return json
        }
    }
}
