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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceHolder
import android.view.SurfaceView
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
    var videoSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var videoSurfaceReady by remember { mutableStateOf(false) }
    var videoPlayer by remember { mutableStateOf<RemoteVideoPlayer?>(null) }
    var videoAspectRatio by remember { mutableStateOf(9f / 16f) }
    var previewRunning by remember { mutableStateOf(false) }
    var screenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var screenshotStatus by remember { mutableStateOf("No screenshot captured yet.") }
    var tapX by remember { mutableStateOf("0.5") }
    var tapY by remember { mutableStateOf("0.5") }
    var swipeEndX by remember { mutableStateOf("0.8") }
    var swipeEndY by remember { mutableStateOf("0.5") }
    var remoteText by remember { mutableStateOf("") }
    var remoteFileItems by remember { mutableStateOf(emptyList<RemoteFileItem>()) }
    var notificationItems by remember { mutableStateOf(emptyList<JSONObject>()) }
    var notificationResult by remember { mutableStateOf("No notification history requested yet.") }
    var currentFolderId by remember { mutableStateOf("") }
    var folderHistory by remember { mutableStateOf(emptyList<String>()) }
    DisposableEffect(Unit) { onDispose { videoPlayer?.stop(); reconnectJob?.cancel() } }
    DisposableEffect(previewRunning, videoSurfaceReady, targetIp, targetToken, videoSurfaceView) {
        val view = videoSurfaceView
        var player: RemoteVideoPlayer? = null
        if (previewRunning && videoSurfaceReady && view != null &&
            targetIp.isNotBlank() && targetToken.isNotBlank()) {
            player = RemoteVideoPlayer(
                targetIp.trim(), targetToken.trim(), view.holder.surface,
                onStatus = { message -> android.os.Handler(android.os.Looper.getMainLooper()).post { output = message } },
                onVideoSize = { width, height ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { videoAspectRatio = width.toFloat() / height.toFloat() }
                }
            ).also { it.start() }
            videoPlayer = player
            output = "Connecting to live H.264 video…"
        } else {
            videoPlayer?.stop()
            videoPlayer = null
        }
        onDispose {
            player?.stop()
            if (videoPlayer === player) videoPlayer = null
        }
    }
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
    val screenshotConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val captureIntent = Intent(context, RemoteScreenshotService::class.java)
                .putExtra(RemoteScreenshotService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(RemoteScreenshotService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, captureIntent)
            output = "Independent screenshot standby enabled. Keep its persistent notification active."
        } else {
            output = "Screenshot standby permission was cancelled."
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
                        LanControllerClient.uploadFile(
                            context, targetIp.trim(), targetToken.trim(), uri, fileName, mime, currentFolderId
                        )
                    }
                }.fold(
                    onSuccess = { "Upload complete: " + it },
                    onFailure = { "Upload failed: " + it.message }
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
                    previewRunning = false
                }) { Text("Stop screen") }
            }
            Text("INDEPENDENT SCREENSHOT STANDBY", fontWeight = FontWeight.SemiBold)
            Text(
                "Separate from live video. Android permits only one active MediaProjection session per app: stop live video before enabling this standby. While standby remains active, still images need no repeated prompt.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = notificationsAllowed, onClick = {
                    if (ScreenVideoStore.hasConfig()) {
                        output = "Stop live video first; Android allows only one active screen-capture session for this app."
                    } else {
                        screenshotConsentLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                }) { Text("Enable screenshot standby") }
                OutlinedButton(onClick = {
                    context.startService(
                        Intent(context, RemoteScreenshotService::class.java)
                            .setAction(RemoteScreenshotService.ACTION_STOP)
                    )
                }) { Text("Stop screenshot standby") }
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
                                        val listenerError = health.opt("lastError")
                                            ?.takeUnless { it == JSONObject.NULL }
                                            ?.toString()
                                            ?.takeIf { it.isNotBlank() }
                                        connectionStatus = buildString {
                                            append("Connected · uptime ")
                                            append(health.optLong("uptimeMs") / 1000L)
                                            append("s · active ")
                                            append(health.optInt("activeClients"))
                                            append(" · queued ")
                                            append(health.optInt("queuedClients"))
                                            if (listenerError != null) {
                                                append(" · agent error: ")
                                                append(listenerError.take(80))
                                            }
                                        }
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
                            result.onSuccess { health ->
                                val listenerError = health.opt("lastError")
                                    ?.takeUnless { it == JSONObject.NULL }
                                    ?.toString()
                                    ?.takeIf { it.isNotBlank() }
                                connectionStatus = buildString {
                                    append("Connected · uptime ")
                                    append(health.optLong("uptimeMs") / 1000L)
                                    append("s · active ")
                                    append(health.optInt("activeClients"))
                                    append(" · queued ")
                                    append(health.optInt("queuedClients"))
                                    if (listenerError != null) {
                                        append(" · agent error: ")
                                        append(listenerError.take(80))
                                    }
                                }
                                output = health.toString(2)
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
                                JSONObject(LanControllerClient.request(targetIp.trim(), targetToken.trim(), "/files"))
                            }
                        }
                        result.onSuccess { response ->
                            output = response.toString(2)
                            currentFolderId = response.optString("parentId", "")
                            folderHistory = emptyList()
                            val items = response.optJSONArray("items") ?: JSONArray()
                            remoteFileItems = (0 until items.length()).mapNotNull { index ->
                                val item = items.optJSONObject(index) ?: return@mapNotNull null
                                RemoteFileItem(
                                    id = item.optString("id"),
                                    name = item.optString("name", "unnamed"),
                                    size = if (item.isNull("size")) -1L else item.optLong("size", -1L),
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
            Text("FILE TRANSFER · TARGET STORAGE", fontWeight = FontWeight.SemiBold)
            Text(
                "Browse all files and subfolders available under the folder selected by the owner on the target phone. Transfers are streamed; no app-imposed file-size cap. Existing files are never overwritten.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { uploadPicker.launch(arrayOf("*/*")) }) {
                    Text("Upload file to target")
                }
                OutlinedButton(
                    enabled = !busy && folderHistory.isNotEmpty(),
                    onClick = {
                        val parent = folderHistory.last()
                        busy = true
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    val encoded = java.net.URLEncoder.encode(parent, "UTF-8")
                                    JSONObject(LanControllerClient.request(targetIp.trim(), targetToken.trim(), "/files?parent=$encoded"))
                                }
                            }
                            result.onSuccess { response ->
                                currentFolderId = response.optString("parentId", "")
                                folderHistory = folderHistory.dropLast(1)
                                val items = response.optJSONArray("items") ?: JSONArray()
                                remoteFileItems = (0 until items.length()).mapNotNull { index ->
                                    val item = items.optJSONObject(index) ?: return@mapNotNull null
                                    RemoteFileItem(item.optString("id"), item.optString("name", "unnamed"),
                                        if (item.isNull("size")) -1L else item.optLong("size", -1L), item.optBoolean("directory"),
                                        item.optString("mimeType", "application/octet-stream"))
                                }
                            }.onFailure { output = "Folder navigation failed: " + it.message }
                            busy = false
                        }
                    }
                ) { Text("Back") }
            }
            Text("Current folder: " + if (folderHistory.isEmpty()) "selected root" else "nested folder",
                style = MaterialTheme.typography.bodySmall)
            if (remoteFileItems.isNotEmpty()) {
                remoteFileItems.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(if (item.directory) "Folder: " + item.name else item.name, fontWeight = FontWeight.Medium)
                            Text(
                                if (item.size >= 0) item.size.toString() + " bytes" else if (item.directory) "Folder" else "Size not reported",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (item.directory) {
                            OutlinedButton(enabled = !busy && item.id.isNotBlank(), onClick = {
                                val previous = currentFolderId
                                busy = true
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            val encoded = java.net.URLEncoder.encode(item.id, "UTF-8")
                                            JSONObject(LanControllerClient.request(targetIp.trim(), targetToken.trim(), "/files?parent=$encoded"))
                                        }
                                    }
                                    result.onSuccess { response ->
                                        folderHistory = folderHistory + previous
                                        currentFolderId = response.optString("parentId", item.id)
                                        val items = response.optJSONArray("items") ?: JSONArray()
                                        remoteFileItems = (0 until items.length()).mapNotNull { index ->
                                            val child = items.optJSONObject(index) ?: return@mapNotNull null
                                            RemoteFileItem(child.optString("id"), child.optString("name", "unnamed"),
                                                if (child.isNull("size")) -1L else child.optLong("size", -1L), child.optBoolean("directory"),
                                                child.optString("mimeType", "application/octet-stream"))
                                        }
                                    }.onFailure { output = "Folder navigation failed: " + it.message }
                                    busy = false
                                }
                            }) { Text("Open") }
                        } else {
                            OutlinedButton(
                                enabled = !busy && item.id.isNotBlank(),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        output = runCatching {
                                            withContext(Dispatchers.IO) {
                                                val saved = LanControllerClient.downloadFileToReceivedFolder(
                                                    context, targetIp.trim(), targetToken.trim(), item.id,
                                                    item.name, item.mimeType
                                                )
                                                "Downloaded to Download/RKRemote/Received/: $saved"
                                            }
                                        }.fold({ it }, { "Download failed: " + it.message })
                                        busy = false
                                    }
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
                "Enable Android Notification Access once on the target phone. RKRemote keeps the latest 500 captured events in its encrypted local store.",
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
                                if (response.optBoolean("truncated")) " · showing latest 500" else "."
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
            Text("CONTROLLER PHONE · LIVE SCREEN VIDEO", fontWeight = FontWeight.SemiBold)
            Text(
                "Continuous H.264 video · maximum 480 px on the longest side · hardware codec when available. Video reconnects after a link interruption.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !previewRunning && targetIp.isNotBlank() && targetToken.isNotBlank(),
                    onClick = { previewRunning = true }
                ) { Text("Start live video") }
                OutlinedButton(
                    enabled = previewRunning,
                    onClick = { previewRunning = false; videoPlayer?.stop(); videoPlayer = null }
                ) { Text("Stop video") }
            }
            AndroidView(
                factory = { viewContext ->
                    SurfaceView(viewContext).also { surfaceView ->
                        videoSurfaceView = surfaceView
                        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) { videoSurfaceReady = true }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                videoSurfaceReady = true
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) { videoSurfaceReady = false }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
                    .pointerInput(targetIp, targetToken, previewRunning) {
                        detectTapGestures(
                            onTap = { point ->
                                val x = (point.x / size.width).coerceIn(0f, 1f)
                                val y = (point.y / size.height).coerceIn(0f, 1f)
                                scope.launch {
                                    output = runCatching {
                                        withContext(Dispatchers.IO) {
                                            LanControllerClient.postJson(
                                                targetIp.trim(), targetToken.trim(), "/input/gesture",
                                                JSONObject().put("startX", x).put("startY", y)
                                                    .put("endX", x).put("endY", y).put("durationMs", 80)
                                            )
                                        }
                                    }.fold({ "Remote tap accepted." }, { "Remote tap failed: ${it.message}" })
                                }
                            },
                            onLongPress = { point ->
                                val x = (point.x / size.width).coerceIn(0f, 1f)
                                val y = (point.y / size.height).coerceIn(0f, 1f)
                                scope.launch {
                                    output = runCatching {
                                        withContext(Dispatchers.IO) {
                                            LanControllerClient.postJson(
                                                targetIp.trim(), targetToken.trim(), "/input/gesture",
                                                JSONObject().put("startX", x).put("startY", y)
                                                    .put("endX", x).put("endY", y).put("durationMs", 650)
                                            )
                                        }
                                    }.fold({ "Long press accepted." }, { "Long press failed: ${it.message}" })
                                }
                            }
                        )
                    }
                    .pointerInput(targetIp, targetToken, previewRunning) {
                        var dragStart = Offset.Zero
                        var dragEnd = Offset.Zero
                        var dragDistance = 0f
                        detectDragGestures(
                            onDragStart = { point -> dragStart = point; dragEnd = point; dragDistance = 0f },
                            onDrag = { change, amount ->
                                dragEnd = change.position
                                dragDistance += kotlin.math.abs(amount.x) + kotlin.math.abs(amount.y)
                                change.consume()
                            },
                            onDragEnd = {
                                if (dragDistance > 8f) {
                                    val x1 = (dragStart.x / size.width).coerceIn(0f, 1f)
                                    val y1 = (dragStart.y / size.height).coerceIn(0f, 1f)
                                    val x2 = (dragEnd.x / size.width).coerceIn(0f, 1f)
                                    val y2 = (dragEnd.y / size.height).coerceIn(0f, 1f)
                                    scope.launch {
                                        output = runCatching {
                                            withContext(Dispatchers.IO) {
                                                LanControllerClient.postJson(
                                                    targetIp.trim(), targetToken.trim(), "/input/gesture",
                                                    JSONObject().put("startX", x1).put("startY", y1)
                                                        .put("endX", x2).put("endY", y2).put("durationMs", 350)
                                                )
                                            }
                                        }.fold({ "Remote gesture accepted." }, { "Remote gesture failed: ${it.message}" })
                                    }
                                }
                            }
                        )
                    }
            )
            Text(
                if (previewRunning) "Live video is connecting or playing. Tap, hold, or drag directly on the video."
                else "Video is stopped. Start live video to control the target screen.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("ONE-SHOT SCREENSHOT", fontWeight = FontWeight.SemiBold)
            Text(
                "This request is independent of live video. The target phone must have screenshot standby enabled.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                enabled = !busy && targetIp.isNotBlank() && targetToken.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val jpeg = LanControllerClient.requestScreenshot(
                                    targetIp.trim(), targetToken.trim()
                                )
                                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                    ?: throw IllegalStateException("Target returned an invalid JPEG image")
                                val saved = LanControllerClient.saveScreenshotToDownloads(context, jpeg)
                                bitmap to saved
                            }
                        }
                        result.onSuccess { (bitmap, saved) ->
                            screenshotBitmap = bitmap
                            screenshotStatus = "Captured at native screen resolution · saved to $saved"
                            output = screenshotStatus
                        }.onFailure {
                            screenshotStatus = "Screenshot failed: " + (it.message ?: "unknown error")
                            output = screenshotStatus
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Requesting…" else "Capture target screenshot") }
            Text(screenshotStatus, style = MaterialTheme.typography.bodySmall)
            screenshotBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Latest remote screenshot",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
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
                "Prototype boundary: local trusted Wi-Fi only; file access is limited to the subtree the target owner selected through Android Storage Access Framework. File transfers are streamed without an app-imposed size cap. The temporary-token HTTP transport is plaintext and must only be used on trusted private Wi-Fi until Section 1 encryption is implemented. Remote taps require the target owner to enable RKRemote Accessibility manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
