package com.rkremote.remote

import android.Manifest
import android.content.Intent
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Base64

@Composable
fun LanRemotePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var targetIp by remember { mutableStateOf("") }
    var targetToken by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("No request yet.") }
    var busy by remember { mutableStateOf(false) }
    var screenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val projectionManager = remember { context.getSystemService(MediaProjectionManager::class.java) }
    val screenConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val captureIntent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, captureIntent)
            output = "Screen sharing started. Use the persistent notification to stop."
        } else {
            output = "Screen sharing permission was cancelled."
        }
    }
    val localIp = remember { LanControllerClient.localIpv4() ?: "Wi-Fi address unavailable" }
    val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Part 1 · Local Wi-Fi connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Start the agent on the phone you want to access. Both phones must be on the same trusted Wi-Fi network.")
            Text("TARGET PHONE · AGENT", fontWeight = FontWeight.SemiBold)
            Text("This phone's local IP: $localIp")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
                    token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                    val intent = Intent(context, SessionForegroundService::class.java)
                        .setAction(SessionForegroundService.ACTION_START_LAN)
                        .putExtra(SessionForegroundService.EXTRA_TOKEN, token)
                    ContextCompat.startForegroundService(context, intent)
                }) { Text("Start agent") }
                OutlinedButton(onClick = {
                    context.startService(
                        Intent(context, SessionForegroundService::class.java)
                            .setAction(SessionForegroundService.ACTION_STOP)
                    )
                    token = ""
                }) { Text("Stop") }
            }
            if (!notificationsAllowed) {
                Text("Grant notification permission in Android permissions above before starting the visible session.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (token.isNotBlank()) {
                Text("Temporary access token (share only with your controller phone):", style = MaterialTheme.typography.bodySmall)
                Text(token, fontWeight = FontWeight.Bold)
                Text("Port: ${LanAgentServer.DEFAULT_PORT} · Read-only · Stop from notification or here.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text("SCREEN SHARING · TARGET PHONE", fontWeight = FontWeight.SemiBold)
            Text("Android will show its own screen-capture consent dialog. Sharing stays visible in a persistent notification.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = notificationsAllowed, onClick = {
                    screenConsentLauncher.launch(projectionManager.createScreenCaptureIntent())
                }) { Text("Share this screen") }
                OutlinedButton(onClick = {
                    context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                    screenBitmap = null
                }) { Text("Stop screen") }
            }
            Spacer(Modifier.height(4.dp))
            Text("CONTROLLER PHONE", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = targetIp, onValueChange = { targetIp = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Agent phone IPv4 address") },
                placeholder = { Text("192.168.1.20") }
            )
            OutlinedTextField(
                value = targetToken, onValueChange = { targetToken = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Temporary access token") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        output = runCatching {
                            withContext(Dispatchers.IO) {
                                LanControllerClient.request(targetIp.trim(), targetToken.trim(), "/health")
                            }
                        }.getOrElse { "Connection failed: ${it.message}" }
                        busy = false
                    }
                }) { Text(if (busy) "Connecting…" else "Test connection") }
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        output = runCatching {
                            withContext(Dispatchers.IO) {
                                LanControllerClient.request(targetIp.trim(), targetToken.trim(), "/files")
                            }
                        }.getOrElse { "Request failed: ${it.message}" }
                        busy = false
                    }
                }) { Text("List selected folder") }
            }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val bytes = LanControllerClient.requestBytes(targetIp.trim(), targetToken.trim(), "/screen.jpg")
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid screen image")
                        }
                    }
                    result.onSuccess { screenBitmap = it; output = "Screen snapshot received." }
                    result.onFailure { output = "Screen request failed: ${it.message}" }
                    busy = false
                }
            }) { Text("View screen snapshot") }
            screenBitmap?.let { bitmap ->
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Remote phone screen snapshot", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
            Text("Result", fontWeight = FontWeight.SemiBold)
            Text(output, style = MaterialTheme.typography.bodySmall)
            Text(
                "Prototype boundary: local trusted Wi-Fi only; read-only folder listing and status. No internet relay, camera, microphone or file download. Screen sharing is an owner-approved still-image snapshot, not continuous video or remote input.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
