package com.rkremote.remote

import android.Manifest
import android.content.Intent
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.security.SecureRandom
import java.util.Base64

private const val MAX_UI_TRANSFER_BYTES = 8 * 1024 * 1024

private data class RemoteFileItem(val id: String, val name: String, val size: Long, val directory: Boolean, val mimeType: String)

@Composable
fun LanRemotePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var targetIp by remember { mutableStateOf("") }
    var targetToken by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("No request yet.") }
    var busy by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("Not connected") }
    var reconnectJob by remember { mutableStateOf<Job?>(null) }
    var screenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewRunning by remember { mutableStateOf(false) }
    var tapX by remember { mutableStateOf("0.5") }
    var tapY by remember { mutableStateOf("0.5") }
    var swipeEndX by remember { mutableStateOf("0.8") }
    var swipeEndY by remember { mutableStateOf("0.5") }
    var remoteText by remember { mutableStateOf("") }
    var remoteFileItems by remember { mutableStateOf(emptyList<RemoteFileItem>()) }
    var notificationItems by remember { mutableStateOf(emptyList<JSONObject>()) }
    var notificationResult by remember { mutableStateOf("No notification history requested yet.") }
    var pendingDownloadId by remember { mutableStateOf("") }
    var pendingDownloadName by remember { mutableStateOf("download.bin") }
    DisposableEffect(Unit) { onDispose { previewJob?.cancel(); reconnectJob?.cancel() } }
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
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            scope.launch {
                output = runCatching {
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                            ?.takeIf { it.isNotBlank() } ?: "upload.bin"
                        val mime = resolver.getType(uri) ?: "application/octet-stream"
                        val bytes = resolver.openInputStream(uri)?.use { input ->
                            val result = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_UI_TRANSFER_BYTES) { "File exceeds 8 MiB transfer limit" }
                                result.write(buffer, 0, count)
                            }
                            result.toByteArray()
                        } ?: error("Unable to read selected file")
                        LanControllerClient.uploadFile(targetIp.trim(), targetToken.trim(), fileName, mime, bytes)
                    }
                }.fold(
                    onSuccess = { "Upload complete: " + it },
                    onFailure = { "Upload failed: " + it.message }
                )
                busy = false
            }
        }
    }
    val downloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination: Uri? ->
        val documentId = pendingDownloadId
        if (destination != null && documentId.isNotBlank()) {
            busy = true
            scope.launch {
                output = runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = LanControllerClient.downloadFile(targetIp.trim(), targetToken.trim(), documentId)
                        require(bytes.size <= MAX_UI_TRANSFER_BYTES) { "Downloaded file exceeds 8 MiB limit" }
                        context.contentResolver.openOutputStream(destination, "w")?.use { it.write(bytes) }
                            ?: error("Unable to write destination file")
                        "Downloaded " + pendingDownloadName + " (" + bytes.size + " bytes)."
                    }
                }.fold(
                    onSuccess = { it },
                    onFailure = { "Download failed: " + it.message }
                )
                busy = false
            }
        }
    }

    var localIp by remember { mutableStateOf(LanControllerClient.localIpv4() ?: "Wi-Fi address unavailable") }
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
            OutlinedButton(onClick = {
                localIp = LanControllerClient.localIpv4() ?: "Wi-Fi address unavailable"
            }) { Text("Refresh local IP") }
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
            Text("Connection: $connectionStatus", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = targetIp.isNotBlank() && targetToken.isNotBlank(),
                    onClick = {
                        if (reconnectJob?.isActive == true) {
                            reconnectJob?.cancel()
                            reconnectJob = null
                            connectionStatus = "Automatic reconnect stopped"
                        } else {
                            reconnectJob = scope.launch {
                                var failures = 0
                                while (isActive) {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            JSONObject(
                                                LanControllerClient.request(
                                                    targetIp.trim(), targetToken.trim(), "/health"
                                                )
                                            )
                                        }
                                    }
                                    result.onSuccess { health ->
                                        failures = 0
                                        connectionStatus = "Connected · uptime " +
                                            (health.optLong("uptimeMs") / 1000L) + "s · clients " +
                                            health.optInt("activeClients")
                                    }.onFailure { error ->
                                        failures++
                                        connectionStatus = "Disconnected · " +
                                            (error.message ?: "network error") + " · retrying"
                                    }
                                    delay(ReconnectPolicy.delayMillis(failures))
                                }
                            }
                        }
                    }
                ) { Text(if (reconnectJob?.isActive == true) "Stop auto reconnect" else "Auto reconnect") }
                OutlinedButton(
                    enabled = !busy && targetIp.isNotBlank() && targetToken.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    JSONObject(LanControllerClient.request(
                                        targetIp.trim(), targetToken.trim(), "/health"
                                    ))
                                }
                            }
                            result.onSuccess {
                                connectionStatus = "Connected · uptime " + (it.optLong("uptimeMs") / 1000L) + "s"
                                output = it.toString(2)
                            }.onFailure {
                                connectionStatus = "Disconnected · " + it.message
                                output = "Health check failed: " + it.message
                            }
                            busy = false
                        }
                    }
                ) { Text("Check now") }
            }
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
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                LanControllerClient.request(targetIp.trim(), targetToken.trim(), "/files")
                            }
                        }
                        result.onSuccess { response ->
                            output = response
                            val items = JSONObject(response).optJSONArray("items") ?: JSONArray()
                            remoteFileItems = (0 until items.length()).mapNotNull { index ->
                                val item = items.optJSONObject(index) ?: return@mapNotNull null
                                RemoteFileItem(
                                    id = item.optString("id"),
                                    name = item.optString("name", "unnamed"),
                                    size = item.optLong("size", -1L),
                                    directory = item.optBoolean("directory"),
                                    mimeType = item.optString("mimeType", "application/octet-stream")
                                )
                            }
                        }
                        result.onFailure { output = "Request failed: " + it.message }
                        busy = false
                    }
                }) { Text("List selected folder") }
            }
            Text("FILE TRANSFER · SELECTED TARGET FOLDER", fontWeight = FontWeight.SemiBold)
            Text("Transfers use only the folder explicitly selected on the target phone. Maximum file size is 8 MiB. Existing files are never overwritten.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { uploadPicker.launch(arrayOf("*/*")) }) {
                Text("Choose file to upload to target")
            }
            if (remoteFileItems.isNotEmpty()) {
                remoteFileItems.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(if (item.directory) "Folder: " + item.name else item.name, fontWeight = FontWeight.Medium)
                            Text(
                                if (item.directory) "Subfolder navigation is not available yet"
                                else if (item.size >= 0) item.size.toString() + " bytes" else "File size unknown",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!item.directory) {
                            OutlinedButton(
                                enabled = !busy && item.id.isNotBlank() && item.size in 0L..MAX_UI_TRANSFER_BYTES.toLong(),
                                onClick = {
                                    pendingDownloadId = item.id
                                    pendingDownloadName = item.name
                                    downloadPicker.launch(item.name)
                                }
                            ) { Text("Download") }
                        }
                    }
                }
            } else {
                Text("No file list loaded. Tap List selected folder after connecting.", style = MaterialTheme.typography.bodySmall)
            }
            Text("PART 6 · REMOTE NOTIFICATION HISTORY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "On the target phone, enable Android Notification Access and separately turn on notification sharing. Only then can this controller read the locally encrypted event history.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                enabled = !busy && targetIp.isNotBlank() && targetToken.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                JSONObject(
                                    LanControllerClient.requestBytes(
                                        targetIp.trim(), targetToken.trim(), "/notifications"
                                    ).toString(Charsets.UTF_8)
                                )
                            }
                        }
                        result.onSuccess { response ->
                            val items = response.optJSONArray("items") ?: JSONArray()
                            notificationItems = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                            notificationResult = "Received " + notificationItems.size + " events" +
                                if (response.optBoolean("truncated")) " · showing latest 100" else "."
                        }.onFailure {
                            notificationResult = "Could not read notifications: " + it.message
                        }
                        busy = false
                    }
                }
            ) { Text("Load notification history") }
            Text(notificationResult, style = MaterialTheme.typography.bodySmall)
            if (notificationItems.isNotEmpty()) {
                notificationItems.asReversed().forEach { event ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(
                            event.optString("package", "Unknown app") +
                                " · " + event.optString("type", "posted"),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        val title = event.optString("title", "")
                        if (title.isNotBlank()) Text(title, fontWeight = FontWeight.Medium)
                        val body = event.optString("text", "")
                        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodySmall)
                        val conversation = event.optString("conversation", "")
                        if (conversation.isNotBlank()) Text(conversation, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Captured: " + java.util.Date(event.optLong("capturedAt", 0L)).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            RemoteMediaPanel(targetIp = targetIp, targetToken = targetToken) { message ->
                output = message
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && !previewRunning, onClick = {
                    previewRunning = true
                    val ip = targetIp.trim()
                    val auth = targetToken.trim()
                    previewJob = scope.launch {
                        var failures = 0
                        try {
                            while (isActive) {
                                val bitmap = try {
                                    withContext(Dispatchers.IO) {
                                        val bytes = LanControllerClient.requestBytes(ip, auth, "/screen.jpg")
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid screen image")
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    failures++
                                    output = "Preview connection lost · retry $failures: ${error.message}"
                                    delay(ReconnectPolicy.delayMillis(failures))
                                    continue
                                }
                                failures = 0
                                screenBitmap = bitmap
                                output = "Preview running · JPEG snapshots about once per second."
                                delay(1000)
                            }
                        } finally {
                            previewRunning = false
                            previewJob = null
                        }
                    }
                }) { Text("Start live preview") }
                OutlinedButton(enabled = previewRunning, onClick = {
                    previewJob?.cancel()
                    output = "Stopping live preview…"
                }) { Text("Stop preview") }
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
            }) { Text("Get one snapshot") }
            screenBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Remote phone screen. Tap to control; long press for a long press gesture.",
                    modifier = Modifier.fillMaxWidth().pointerInput(bitmap, targetIp, targetToken) {
                        detectTapGestures(
                            onTap = { offset ->
                                val x = (offset.x / size.width).coerceIn(0f, 1f)
                                val y = (offset.y / size.height).coerceIn(0f, 1f)
                                scope.launch {
                                    output = runCatching {
                                        withContext(Dispatchers.IO) {
                                            LanControllerClient.postJson(
                                                targetIp.trim(), targetToken.trim(), "/input/gesture",
                                                JSONObject().put("startX", x).put("startY", y)
                                                    .put("endX", x).put("endY", y).put("durationMs", 80)
                                            )
                                        }
                                    }.getOrElse { "Remote tap failed: ${it.message}" }
                                }
                            },
                            onLongPress = { offset ->
                                val x = (offset.x / size.width).coerceIn(0f, 1f)
                                val y = (offset.y / size.height).coerceIn(0f, 1f)
                                scope.launch {
                                    output = runCatching {
                                        withContext(Dispatchers.IO) {
                                            LanControllerClient.postJson(
                                                targetIp.trim(), targetToken.trim(), "/input/gesture",
                                                JSONObject().put("startX", x).put("startY", y)
                                                    .put("endX", x).put("endY", y).put("durationMs", 650)
                                            )
                                        }
                                    }.getOrElse { "Long press failed: ${it.message}" }
                                }
                            }
                        )
                    },
                    contentScale = ContentScale.Fit
                )
                Text("Tap directly on the image to tap the target phone. Press and hold on the image for a long press.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text("REMOTE TOUCH · TARGET PHONE", fontWeight = FontWeight.SemiBold)
            Text(
                "On the target phone, enable RKRemote touch control manually in Android Accessibility settings. Android will show that this service can perform gestures. You can disable it there at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("Open Accessibility settings") }
            Text("Touch position on target screen (normalized 0.0–1.0)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tapX, onValueChange = { tapX = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("X · left to right") }
                )
                OutlinedTextField(
                    value = tapY, onValueChange = { tapY = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("Y · top to bottom") }
                )
            }
            OutlinedButton(enabled = !busy, onClick = {
                val x = tapX.toFloatOrNull()
                val y = tapY.toFloatOrNull()
                if (x == null || y == null || x !in 0f..1f || y !in 0f..1f) {
                    output = "Touch coordinates must be numbers between 0.0 and 1.0."
                } else {
                    busy = true
                    scope.launch {
                        output = runCatching {
                            withContext(Dispatchers.IO) {
                                LanControllerClient.postJson(
                                    targetIp.trim(), targetToken.trim(), "/input/tap",
                                    JSONObject().put("x", x).put("y", y)
                                )
                            }
                        }.getOrElse { "Touch command failed: ${it.message}" }
                        busy = false
                    }
                }
            }) { Text("Send one tap") }
            Text("Example: X=0.5 and Y=0.5 taps the center of the target display.", style = MaterialTheme.typography.bodySmall)
            Text("SWIPE · NORMALIZED COORDINATES", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = swipeEndX, onValueChange = { swipeEndX = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("End X") })
                OutlinedTextField(value = swipeEndY, onValueChange = { swipeEndY = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("End Y") })
            }
            OutlinedButton(enabled = !busy, onClick = {
                val x1 = tapX.toFloatOrNull()
                val y1 = tapY.toFloatOrNull()
                val x2 = swipeEndX.toFloatOrNull()
                val y2 = swipeEndY.toFloatOrNull()
                if (x1 == null || y1 == null || x2 == null || y2 == null ||
                    x1 !in 0f..1f || y1 !in 0f..1f || x2 !in 0f..1f || y2 !in 0f..1f) {
                    output = "Swipe coordinates must be numbers between 0.0 and 1.0."
                } else {
                    busy = true
                    scope.launch {
                        output = runCatching {
                            withContext(Dispatchers.IO) {
                                LanControllerClient.postJson(
                                    targetIp.trim(), targetToken.trim(), "/input/gesture",
                                    JSONObject().put("startX", x1).put("startY", y1)
                                        .put("endX", x2).put("endY", y2).put("durationMs", 350)
                                )
                            }
                        }.getOrElse { "Swipe failed: ${it.message}" }
                        busy = false
                    }
                }
            }) { Text("Send swipe") }
            Text("Swipe starts at the X/Y values above and ends at End X/End Y. Direct long press is available on the screen image.", style = MaterialTheme.typography.bodySmall)
            Text("TYPE INTO THE FOCUSED FIELD", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = remoteText, onValueChange = { if (it.length <= 4096) remoteText = it },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                label = { Text("Text to insert on target phone") }
            )
            OutlinedButton(enabled = !busy && remoteText.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    output = runCatching {
                        withContext(Dispatchers.IO) {
                            LanControllerClient.postJson(
                                targetIp.trim(), targetToken.trim(), "/input/text",
                                JSONObject().put("text", remoteText)
                            )
                        }
                    }.getOrElse { "Text insertion failed: ${it.message}" }
                    busy = false
                }
            }) { Text("Insert into focused field") }
            Text("Before sending, tap an editable text field on the target phone so it has focus. Text is inserted using Android Accessibility ACTION_SET_TEXT; it does not simulate individual key presses.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("Result", fontWeight = FontWeight.SemiBold)
            Text(output, style = MaterialTheme.typography.bodySmall)
            Text(
                "Prototype boundary: local trusted Wi-Fi only; owner-selected folder file transfer (8 MiB limit). No internet relay, camera or microphone. Screen sharing uses owner-approved JPEG snapshots; live preview polls snapshots about once per second. Remote taps require the target owner to enable the RKRemote Accessibility service manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
