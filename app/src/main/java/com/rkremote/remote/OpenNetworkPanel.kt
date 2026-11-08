package com.rkremote.remote

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private const val NETWORK_PREFS = "rkremote_custom_networks"
private const val NETWORK_LIST_KEY = "profiles"
private const val ACTIVE_NETWORK_KEY = "active_profile"

private data class NetworkProfile(
    val name: String,
    val signalingUrl: String,
    val iceUrls: List<String>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("name", name)
        .put("signalingUrl", signalingUrl)
        .put("iceUrls", JSONArray(iceUrls))

    companion object {
        fun fromJson(json: JSONObject): NetworkProfile {
            require(json.optInt("schemaVersion", 1) == 1) { "Unsupported network profile version" }
            val name = json.getString("name").trim()
            val signaling = json.getString("signalingUrl").trim()
            require(name.isNotBlank() && name.length <= 40) { "Name must contain 1–40 characters" }
            require(signaling.startsWith("wss://", true) && signaling.length <= 512) {
                "Signaling address must be a valid WSS URL"
            }
            val ice = json.optJSONArray("iceUrls") ?: JSONArray()
            require(ice.length() <= 8) { "A maximum of 8 STUN/TURN addresses is allowed" }
            val urls = (0 until ice.length()).map { ice.getString(it).trim() }
            urls.forEach { address ->
                require(address.length <= 512 &&
                    (address.startsWith("stun:", true) || address.startsWith("stuns:", true) ||
                     address.startsWith("turn:", true) || address.startsWith("turns:", true))) {
                    "Only STUN/STUNS/TURN/TURNS addresses are allowed"
                }
                require('@' !in address && !address.contains("://")) {
                    "Do not put usernames or passwords inside server addresses"
                }
            }
            return NetworkProfile(name, signaling, urls.distinct())
        }
    }
}

private fun readProfiles(context: Context): List<NetworkProfile> = runCatching {
    val raw = context.getSharedPreferences(NETWORK_PREFS, Context.MODE_PRIVATE)
        .getString(NETWORK_LIST_KEY, "[]") ?: "[]"
    val array = JSONArray(raw)
    (0 until array.length()).map { NetworkProfile.fromJson(array.getJSONObject(it)) }
}.getOrDefault(emptyList())

private fun writeProfiles(context: Context, profiles: List<NetworkProfile>) {
    val array = JSONArray()
    profiles.forEach { array.put(it.toJson()) }
    context.getSharedPreferences(NETWORK_PREFS, Context.MODE_PRIVATE)
        .edit().putString(NETWORK_LIST_KEY, array.toString()).apply()
}

@Composable
fun OpenNetworkPanel() {
    val context = LocalContext.current
    val profiles = remember { mutableStateOf(readProfiles(context)) }
    val selected = remember { mutableStateOf<String?>(null) }
    val name = remember { mutableStateOf("") }
    val signaling = remember { mutableStateOf("") }
    val ice = remember { mutableStateOf("") }
    val status = remember { mutableStateOf("No custom network selected") }
    val guideOpen = remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read the selected file")
            require(raw.length <= 32 * 1024) { "Profile file is larger than 32 KiB" }
            val parsed = NetworkProfile.fromJson(JSONObject(raw))
            val updated = (profiles.value.filterNot { it.name.equals(parsed.name, true) } + parsed).take(20)
            profiles.value = updated
            writeProfiles(context, updated)
            name.value = parsed.name
            signaling.value = parsed.signalingUrl
            ice.value = parsed.iceUrls.joinToString(", ")
            status.value = "Imported: ${parsed.name}"
        }.onFailure { status.value = "Import failed: ${it.message ?: "invalid profile"}" }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val profile = profiles.value.firstOrNull { it.name == selected.value }
        if (uri != null && profile != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(profile.toJson().toString(2))
            } ?: error("Could not write the selected file")
            status.value = "Exported: ${profile.name}"
        }.onFailure { status.value = "Export failed: ${it.message}" }
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("شبکه‌های سفارشی · Open Network", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("پروفایل‌های اتصال را بساز، وارد کن، خروجی بگیر و روی اتصال اینترنتی امن اعمال کن. هر پروفایل فقط تنظیمات عمومی سرور را نگه می‌دارد؛ رمز TURN در فایل خروجی قرار نمی‌گیرد.")
            profiles.value.forEach { profile ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                        Text(profile.signalingUrl, style = MaterialTheme.typography.bodySmall)
                        Text("${profile.iceUrls.size} ICE server(s)", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                selected.value = profile.name
                                name.value = profile.name
                                signaling.value = profile.signalingUrl
                                ice.value = profile.iceUrls.joinToString(", ")
                                status.value = "Selected: ${profile.name}"
                            }) { Text("Edit / Select") }
                            OutlinedButton(onClick = {
                                context.getSharedPreferences(NETWORK_PREFS, Context.MODE_PRIVATE)
                                    .edit().putString(ACTIVE_NETWORK_KEY, profile.toJson().toString()).apply()
                                status.value = "Applied: ${profile.name} · reopen Secure Internet to use it"
                            }) { Text("Apply") }
                            OutlinedButton(onClick = {
                                val updated = profiles.value.filterNot { it.name == profile.name }
                                profiles.value = updated
                                writeProfiles(context, updated)
                                if (selected.value == profile.name) selected.value = null
                                status.value = "Deleted: ${profile.name}"
                            }) { Text("Delete") }
                        }
                    }
                }
            }
            OutlinedTextField(value = name.value, onValueChange = { name.value = it.take(40) },
                modifier = Modifier.fillMaxWidth(), label = { Text("Network name") }, singleLine = true)
            OutlinedTextField(value = signaling.value, onValueChange = { signaling.value = it.trim() },
                modifier = Modifier.fillMaxWidth(), label = { Text("Signaling WSS URL") },
                placeholder = { Text("wss://your-domain.example/ws") }, singleLine = true)
            OutlinedTextField(value = ice.value, onValueChange = { ice.value = it.take(4096) },
                modifier = Modifier.fillMaxWidth(), label = { Text("STUN/TURN URLs · comma separated") },
                placeholder = { Text("stun:stun.example:3478, turns:relay.example:5349") }, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching {
                        val addresses = ice.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        val profile = NetworkProfile.fromJson(JSONObject()
                            .put("schemaVersion", 1).put("name", name.value.trim())
                            .put("signalingUrl", signaling.value.trim()).put("iceUrls", JSONArray(addresses)))
                        val updated = (profiles.value.filterNot { it.name.equals(profile.name, true) } + profile).take(20)
                        profiles.value = updated
                        writeProfiles(context, updated)
                        selected.value = profile.name
                        status.value = "Saved: ${profile.name}"
                    }.onFailure { status.value = "Validation failed: ${it.message}" }
                }) { Text("Validate & Save") }
                OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/*")) }) {
                    Text("Import JSON")
                }
                OutlinedButton(enabled = selected.value != null, onClick = {
                    exportPicker.launch("${selected.value ?: "rkremote-network"}.json")
                }) { Text("Export") }
            }
            OutlinedButton(onClick = { guideOpen.value = !guideOpen.value }) {
                Text(if (guideOpen.value) "بستن راهنما" else "راهنمای ساخت شبکه")
            }
            if (guideOpen.value) {
                Text("۱) یک سرویس Signaling سازگار با پروتکل RKRemote راه‌اندازی کن و نشانی امن WSS آن را وارد کن. صرفاً داشتن یک WebSocket عمومی کافی نیست؛ سرور باید پیام‌های signaling موردنیاز برنامه را پشتیبانی کند.")
                Text("۲) برای اتصال مستقیم، آدرس STUN معتبر اضافه کن. برای شبکه‌های محدود، یک TURN server تهیه کن و نشانی turn: یا turns: آن را وارد کن.")
                Text("۳) اعتبارنامه TURN را در صفحه اتصال امن و فقط روی دستگاه خودت وارد کن. نام کاربری و رمز را در فایل JSON یا لینک عمومی قرار نده.")
                Text("۴) «اعتبارسنجی و ذخیره» را بزن؛ سپس «اعمال» را انتخاب کن و در صفحه اتصال اینترنتی امن از پروفایل فعال استفاده کن.")
                Text("۵) برای اشتراک‌گذاری، فقط JSON تنظیمات عمومی را خروجی بگیر. پیش از استفاده از پروفایل دیگران، دامنه و مالک سرور را بررسی کن.")
                Text("نمونه فایل:")
                Text("""{
  "schemaVersion": 1,
  "name": "My Network",
  "signalingUrl": "wss://signal.example.com/ws",
  "iceUrls": [
    "stun:stun.example.com:3478",
    "turns:relay.example.com:5349"
  ]
}""", style = MaterialTheme.typography.bodySmall)
            }
            Text(status.value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("حداکثر ۲۰ پروفایل · حداکثر ۸ آدرس ICE در هر پروفایل · فایل JSON تا ۳۲ KiB",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
