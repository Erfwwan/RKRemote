package com.rkremote.remote

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.security.SecureRandom

@Composable
fun CloudflareSetupPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("rkremote_cloudflare_setup", Context.MODE_PRIVATE)
    }
    var role by remember { mutableStateOf(prefs.getString("role", "target") ?: "target") }
    var workerUrl by remember { mutableStateOf(prefs.getString("worker_url", "") ?: "") }
    var roomId by remember { mutableStateOf(prefs.getString("room_id", "") ?: "") }
    var iceUrls by remember { mutableStateOf(prefs.getString("ice_urls", "stun:stun.l.google.com:19302") ?: "") }
    var status by remember { mutableStateOf("تنظیمات این دستگاه هنوز ذخیره نشده است.") }

    fun newRoomId(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("راه‌اندازی Cloudflare برای این گوشی", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("هر گوشی تنظیمات محلی خودش را ذخیره می‌کند. هر دو گوشی باید به همان Worker مستقرشده و Room ID یکسان متصل شوند؛ نقش‌ها متفاوت‌اند.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { role = "target" }) { Text(if (role == "target") "✓ گوشی مقصد" else "گوشی مقصد") }
                OutlinedButton(onClick = { role = "controller" }) { Text(if (role == "controller") "✓ کنترل‌کننده" else "کنترل‌کننده") }
            }
            OutlinedTextField(
                value = workerUrl, onValueChange = { workerUrl = it.trim() },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("آدرس Worker مخصوص این اتصال (WSS)") },
                placeholder = { Text("wss://your-worker.workers.dev/signal/ROOM_ID?role=target") }
            )
            OutlinedTextField(
                value = roomId, onValueChange = { roomId = it.filter { ch -> ch in '0'..'9' || ch in 'a'..'f' }.take(64) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Room ID مشترک ۶۴ کاراکتری") }
            )
            OutlinedButton(onClick = { roomId = newRoomId() }) { Text("ساخت Room ID تصادفی") }
            OutlinedTextField(
                value = iceUrls, onValueChange = { iceUrls = it.take(4096) },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("آدرس‌های STUN / TURN با کاما") }
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val validUrl = workerUrl.startsWith("wss://") &&
                        workerUrl.contains("/signal/") &&
                        workerUrl.contains("role=")
                    if (!validUrl) {
                        status = "آدرس باید WSS و شامل مسیر /signal/ و پارامتر role باشد."
                    } else if (roomId.length != 64) {
                        status = "Room ID باید دقیقاً ۶۴ رقم هگزادسیمال باشد."
                    } else {
                        val parsed = runCatching {
                            val uri = java.net.URI(workerUrl)
                            require(uri.host != null && uri.userInfo == null && uri.fragment == null)
                            require(workerUrl.contains(roomId))
                            require(workerUrl.contains("role=$role"))
                            iceUrls.split(',').map { it.trim() }.filter { it.isNotEmpty() }.also { list ->
                                require(list.size <= 8)
                                require(list.all { it.startsWith("stun:") || it.startsWith("stuns:") || it.startsWith("turn:") || it.startsWith("turns:") })
                            }
                        }
                        if (parsed.isFailure) {
                            status = "تنظیمات معتبر نیست: ${parsed.exceptionOrNull()?.message ?: "بررسی آدرس ناموفق"}"
                        } else {
                            prefs.edit()
                                .putString("role", role)
                                .putString("worker_url", workerUrl)
                                .putString("room_id", roomId)
                                .putString("ice_urls", iceUrls)
                                .apply()
                            status = "تنظیمات این گوشی ذخیره شد. حالا صفحه اتصال اینترنتی امن را باز کن."
                        }
                    }
                }
            ) { Text("اعتبارسنجی و ذخیره تنظیمات این گوشی") }
            OutlinedButton(onClick = {
                roomId = newRoomId()
                status = "Room ID جدید ساخته شد؛ برای گوشی دوم همین مقدار را وارد کن."
            }) { Text("تولید Room ID تازه") }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("راهنمای ساخت Cloudflare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("۱) در dash.cloudflare.com حساب بساز یا وارد حساب خودت شو.")
            Text("۲) در Workers & Pages یک Worker جدید بساز و Durable Objects را فعال کن.")
            Text("۳) کد Worker رسمی RKRemote را از پوشه cloudflare/worker مخزن پروژه با Wrangler مستقر کن؛ Worker عمومی دلخواه یا WebSocket معمولی سازگار نیست.")
            Text("۴) بعد از Deploy، دامنه workers.dev را بردار. برای گوشی مقصد URL را به شکل زیر بساز:")
            Text("wss://YOUR-WORKER.workers.dev/signal/ROOM_ID?role=target", style = MaterialTheme.typography.bodySmall)
            Text("۵) برای گوشی کنترل‌کننده همان دامنه و Room ID را استفاده کن و فقط role را controller بگذار.")
            Text("۶) Room ID را در هر دو گوشی دقیقاً یکسان وارد کن. گوشی مقصد باید اول وصل شود.")
            Text("۷) STUN عمومی برای شروع کافی است. اگر NAT یا اپراتور اتصال مستقیم را مسدود کرد، TURN جداگانه تهیه کن و URL و اعتبارنامه موقت آن را در صفحه اتصال امن وارد کن.")
            Text("۸) بعد از اتصال، Peer ID و کد امنیتی شش‌رقمی را در هر دو گوشی مقایسه کن و فقط در صورت تطابق، هر دو طرف تأیید کنند.")
            Text("Cloudflare فقط signaling را عبور می‌دهد؛ تصویر، صدا و فایل‌ها از مسیر WebRTC مستقیم یا TURN عبور می‌کنند. Cloudflare به‌تنهایی TURN نیست.")
            Text("آدرس Worker و ICE محرمانه نیستند؛ اعتبارنامه TURN را در این صفحه یا فایل عمومی ذخیره نکن. هر گوشی تنظیمات خودش را در حافظه محلی نگه می‌دارد.")
            Text("راهنمای کامل استقرار: cloudflare/worker/README.md")
        }
    }
}
