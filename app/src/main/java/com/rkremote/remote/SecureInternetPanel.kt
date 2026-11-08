package com.rkremote.remote

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

@Composable
fun SecureInternetPanel() {
    val context = LocalContext.current
    val url = remember { mutableStateOf("") }
    val room = remember { mutableStateOf("") }
    val role = remember { mutableStateOf(CloudflareSignalingClient.Role.TARGET) }
    val turnUrls = remember { mutableStateOf("") }
    val turnUsername = remember { mutableStateOf("") }
    val turnCredential = remember { mutableStateOf("") }
    val status = remember { mutableStateOf("Not connected") }
    val dataChannelConnected = remember { mutableStateOf(false) }
    val peerId = remember { mutableStateOf<String?>(null) }
    val sas = remember { mutableStateOf<String?>(null) }
    val error = remember { mutableStateOf<String?>(null) }
    val session = remember { mutableStateOf<SecureInternetPeer?>(null) }
    val renderer = remember { mutableStateOf<SecureScreenVideoRenderer?>(null) }
    val streamStatus = remember { mutableStateOf("Screen stream idle") }
    val streamSenderState = remember { mutableStateOf<SecureScreenStreamSender?>(null) }
    val remoteText = remember { mutableStateOf("") }
    val notificationSummary = remember { mutableStateOf("No notification sync requested") }
    val fileItems = remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val fileStatus = remember { mutableStateOf("No folder loaded") }
    val fileParentId = remember { mutableStateOf("") }
    val fileRootId = remember { mutableStateOf("") }
    val fileDownloadStatus = remember { mutableStateOf("No download active") }
    val downloadReceiver = remember(context) { SecureDownloadReceiver(context) }
    val safBridge = remember(context) { SecureSafTransferBridge(context) }
    val transferExecutor = remember { Executors.newSingleThreadExecutor() }
    val transferActive = remember { AtomicBoolean(false) }
    val fileEntries = remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val fileParentId = remember { mutableStateOf("") }
    val selectedFileId = remember { mutableStateOf<String?>(null) }
    val fileStatus = remember { mutableStateOf("File browser idle") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose {
            streamSenderState.value?.close()
            streamSenderState.value = null
            session.value?.revokeOwnerApprovedCommands()
            session.value?.close()
            session.value = null
            renderer.value?.close()
            renderer.value = null
            transferActive.set(false)
            transferExecutor.shutdownNow()
            downloadReceiver.close()
        }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Secure Internet P2P", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Noise XX authentication + WebRTC ICE/DTLS. Live H.264 screen viewing is available after local screen-capture consent and a separate target-side permission grant. Other remote features are not yet migrated.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = url.value,
                onValueChange = { url.value = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cloudflare Worker WSS URL") },
                placeholder = { Text("wss://your-worker.workers.dev") },
                singleLine = true
            )
            OutlinedTextField(
                value = room.value,
                onValueChange = { room.value = it.filter { ch -> ch in '0'..'9' || ch in 'a'..'f' }.take(64) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("64-character room ID") },
                singleLine = true
            )
            OutlinedTextField(value = turnUrls.value, onValueChange = { turnUrls.value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Optional TURN URLs (comma-separated)") }, placeholder = { Text("turn:turn.example.net:3478, turns:turn.example.net:5349") }, singleLine = true)
            OutlinedTextField(value = turnUsername.value, onValueChange = { turnUsername.value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("TURN username") }, singleLine = true)
            OutlinedTextField(value = turnCredential.value, onValueChange = { turnCredential.value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("TURN credential") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = session.value == null, onClick = { role.value = CloudflareSignalingClient.Role.TARGET }) {
                    Text(if (role.value == CloudflareSignalingClient.Role.TARGET) "✓ Target" else "Target")
                }
                OutlinedButton(enabled = session.value == null, onClick = { role.value = CloudflareSignalingClient.Role.CONTROLLER }) {
                    Text(if (role.value == CloudflareSignalingClient.Role.CONTROLLER) "✓ Controller" else "Controller")
                }
                OutlinedButton(onClick = { room.value = CloudflareSignalingClient.newRoomId() }) {
                    Text("New room")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = session.value == null && Build.VERSION.SDK_INT >= 33 &&
                        url.value.startsWith("wss://") && room.value.matches(Regex("[a-f0-9]{64}")),
                    onClick = {
                        error.value = null
                        peerId.value = null
                        sas.value = null
                        try {
                            val identityStore = P2pIdentityStore()
                            val handshake = SecureLinkHandshake.create(
                                initiator = role.value == CloudflareSignalingClient.Role.CONTROLLER,
                                identityStore = identityStore,
                                staticKeyStore = SecureLinkStaticKeyStore(context)
                            )
                            val configuredTurnServers = if (turnUrls.value.isBlank() && turnUsername.value.isBlank() && turnCredential.value.isBlank()) emptyList() else {
                                require(turnUrls.value.isNotBlank() && turnUsername.value.isNotBlank() && turnCredential.value.isNotBlank()) { "Enter TURN URLs, username and credential together" }
                                listOf(SecureWebRtcPeer.TurnServer(turnUrls.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }, turnUsername.value, turnCredential.value))
                            }
                            var activePeer: SecureInternetPeer? = null
                            var downloadUri: Uri? = null
                            var downloadOutput: OutputStream? = null
                            var downloadDigest: MessageDigest? = null
                            var downloadBytes = 0L
                            var downloadExpectedBytes = -1L
                            val targetRouter = if (role.value == CloudflareSignalingClient.Role.TARGET) {
                                SecureRemoteCommandRouter().apply {
                                    register("file.list.request", RemoteCapability.FILES) { message ->
                                        val requestId = message.payload.getString("requestId")
                                        val result = safBridge.list(message.payload.optString("parentId").takeIf { it.isNotBlank() })
                                        check(activePeer?.sendMessage(
                                            "file.list.result",
                                            JSONObject().put("requestId", requestId).put("result", result)
                                        ) == true) { "Could not send encrypted folder listing" }
                                        "file.list.result"
                                    }
                                    register("file.download.request", RemoteCapability.FILES) { message ->
                                        val requestId = message.payload.getString("requestId")
                                        val documentId = message.payload.getString("documentId")
                                        check(transferActive.compareAndSet(false, true)) { "Another file transfer is already active" }
                                        transferExecutor.execute {
                                            try {
                                                safBridge.streamFile(
                                                    documentId = documentId,
                                                    onStart = { metadata ->
                                                        activePeer?.sendMessage(
                                                            "file.download.begin",
                                                            JSONObject().put("transferId", requestId)
                                                                .put("name", metadata.name)
                                                                .put("mimeType", metadata.mimeType)
                                                                .put("totalBytes", metadata.sizeBytes)
                                                        ) == true
                                                    },
                                                    onChunk = { offset, bytes ->
                                                        var sent = false
                                                        var attempts = 0
                                                        while (!sent && attempts < 300 && transferActive.get()) {
                                                            sent = activePeer?.sendMessage(
                                                                "file.download.chunk",
                                                                JSONObject().put("transferId", requestId)
                                                                    .put("offset", offset)
                                                                    .put("data", java.util.Base64.getEncoder().encodeToString(bytes))
                                                            ) == true
                                                            if (!sent) {
                                                                attempts++
                                                                Thread.sleep(100)
                                                            }
                                                        }
                                                        sent && transferActive.get()
                                                    },
                                                    onComplete = { hash ->
                                                        if (transferActive.get()) activePeer?.sendMessage(
                                                            "file.download.complete",
                                                            JSONObject().put("transferId", requestId).put("sha256", hash)
                                                        )
                                                    }
                                                )
                                            } catch (failure: Throwable) {
                                                activePeer?.sendMessage(
                                                    "file.download.error",
                                                    JSONObject().put("transferId", requestId)
                                                        .put("reason", failure.message ?: "File transfer failed")
                                                )
                                            } finally {
                                                transferActive.set(false)
                                            }
                                        }
                                        "file.download.begin"
                                    }
                                    val safBridge = SecureSafTransferBridge(context)
                                    register("file.list.request", RemoteCapability.FILES) { message ->
                                        val response = safBridge.list(message.payload.optString("parentId").takeIf { it.isNotBlank() })
                                            .put("requestId", message.payload.getString("requestId"))
                                        check(activePeer?.sendMessage("file.list.result", response) == true) {
                                            "Could not send secure file listing"
                                        }
                                        "file.list.result"
                                    }
                                    register("file.download.request", RemoteCapability.FILES) { message ->
                                        val requestId = message.payload.getString("requestId")
                                        val documentId = message.payload.getString("documentId")
                                        Thread({
                                            try {
                                                var streamedSize = -1L
                                                safBridge.streamFile(
                                                    documentId = documentId,
                                                    onStart = { metadata ->
                                                        streamedSize = metadata.sizeBytes
                                                        activePeer?.sendMessage(
                                                            "file.download.begin",
                                                            JSONObject()
                                                                .put("requestId", requestId)
                                                                .put("documentId", metadata.documentId)
                                                                .put("name", metadata.name.take(255))
                                                                .put("mimeType", metadata.mimeType.take(160))
                                                                .put("totalBytes", metadata.sizeBytes)
                                                        ) == true
                                                    },
                                                    onChunk = { offset, bytes ->
                                                        val sent = activePeer?.sendMessage(
                                                            "file.download.chunk",
                                                            JSONObject()
                                                                .put("requestId", requestId)
                                                                .put("offset", offset)
                                                                .put("data", Base64.getEncoder().encodeToString(bytes))
                                                        ) == true
                                                        if (sent) Thread.sleep(15)
                                                        sent
                                                    },
                                                    onComplete = { sha256 ->
                                                        check(activePeer?.sendMessage(
                                                            "file.download.complete",
                                                            JSONObject()
                                                                .put("requestId", requestId)
                                                                .put("totalBytes", streamedSize)
                                                                .put("sha256", sha256)
                                                        ) == true) { "Could not send file transfer completion" }
                                                    }
                                                )
                                            } catch (failure: Throwable) {
                                                activePeer?.sendMessage(
                                                    "file.download.error",
                                                    JSONObject().put("requestId", requestId)
                                                        .put("reason", failure.message?.take(180) ?: "File transfer failed")
                                                )
                                            }
                                        }, "RKRemote-Secure-File-Download").start()
                                        "file.download.begin"
                                    }
                                    register("screen.stream.start", RemoteCapability.SCREEN_VIEW) {
                                        check(ScreenVideoStore.hasConfig()) {
                                            "Start screen sharing locally on the target first"
                                        }
                                        streamSenderState.value?.close()
                                        val sender = SecureScreenStreamSender(
                                            sendMessage = { type, payload ->
                                                activePeer?.sendMessage(type, payload) == true
                                            },
                                            onState = { message ->
                                                mainHandler.post { streamStatus.value = message }
                                            }
                                        )
                                        streamSenderState.value = sender
                                        check(sender.start()) { "Screen stream is already running" }
                                        "screen.video.config"
                                    }
                                    register("screen.stream.stop", RemoteCapability.SCREEN_VIEW) {
                                        streamSenderState.value?.close()
                                        streamSenderState.value = null
                                        null
                                    }
                                    register("notifications.sync.request", RemoteCapability.NOTIFICATIONS) { message ->
                                        val response = SecureNotificationSync.buildLatestResponse(context, message.payload.getString("requestId"))
                                        check(activePeer?.sendMessage("notifications.sync.result", response) == true) { "Could not send encrypted notification response" }
                                        "notifications.sync.result"
                                    }
                                    register("screen.input", RemoteCapability.SCREEN_INPUT) { message ->
                                        val payload = message.payload
                                        val accepted = when (payload.getString("action")) {
                                            "gesture" -> RemoteAccessibilityService.dispatchGestureNormalized(
                                                payload.getDouble("startX").toFloat(),
                                                payload.getDouble("startY").toFloat(),
                                                payload.getDouble("endX").toFloat(),
                                                payload.getDouble("endY").toFloat(),
                                                payload.getLong("durationMs")
                                            )
                                            "text" -> RemoteAccessibilityService.setFocusedText(
                                                payload.getString("text")
                                            )
                                            else -> error("Unsupported screen input action")
                                        }
                                        check(accepted) {
                                            "Android Accessibility is unavailable or rejected this action"
                                        }
                                        null
                                    }
                                }
                            } else null
                            val created = SecureInternetPeer(
                                context = context,
                                baseWssUrl = url.value,
                                roomId = room.value,
                                role = role.value,
                                handshake = handshake,
                                identityStore = identityStore,
                                turnServers = configuredTurnServers,
                                commandRouter = targetRouter,
                                listener = object : SecureInternetPeer.Listener {
                                    override fun onPeerVerificationRequired(id: String, code: String) {
                                        mainHandler.post {
                                            peerId.value = id
                                            sas.value = code
                                            status.value = "Compare the code on both devices"
                                        }
                                    }
                                    override fun onSecureSignalingReady(id: String) {
                                        mainHandler.post { status.value = "Owners approved · negotiating WebRTC" }
                                    }
                                    override fun onWebRtcConnecting() {
                                        mainHandler.post { status.value = "Establishing direct P2P connection" }
                                    }
                                    override fun onWebRtcConnected() {
                                        mainHandler.post {
                                            dataChannelConnected.value = true
                                            status.value = "WebRTC DataChannel connected (DTLS)"
                                        }
                                    }
                                    override fun onData(payload: ByteArray) {
                                        mainHandler.post { status.value = "Secure transport received · ${payload.size} bytes" }
                                    }
                                    override fun onRemoteMessage(message: RemoteMessage) {
                                        if (message.type == "file.list.result") {
                                            val items = message.payload.optJSONArray("items")
                                            val parsed = buildList {
                                                for (index in 0 until (items?.length() ?: 0)) {
                                                    items?.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
                                                }
                                            }
                                            mainHandler.post {
                                                fileEntries.value = parsed
                                                fileParentId.value = message.payload.optString("parentId")
                                                selectedFileId.value = null
                                                fileStatus.value = "Loaded ${parsed.size} entries"
                                            }
                                        }
                                        if (message.type == "file.download.begin") {
                                            runCatching {
                                                check(Build.VERSION.SDK_INT >= 29) { "Downloads folder integration requires Android 10+" }
                                                downloadOutput?.close()
                                                downloadUri?.let { context.contentResolver.delete(it, null, null) }
                                                val name = message.payload.optString("name").take(255).ifBlank { "download.bin" }
                                                val mime = message.payload.optString("mimeType").take(160)
                                                    .takeIf { it.matches(Regex("[A-Za-z0-9!#                                    override fun onRemoteMessage(message: RemoteMessage) {
                                        when (message.type) {
                                            "file.list.result" -> {
                                                val result = message.payload.optJSONObject("result")
                                                val items = result?.optJSONArray("items")
                                                val parsed = buildList {
                                                    for (index in 0 until (items?.length() ?: 0)) {
                                                        items?.optJSONObject(index)?.let { add(it) }
                                                    }
                                                }
                                                mainHandler.post {
                                                    fileItems.value = parsed
                                                    fileParentId.value = result?.optString("parentId", "") ?: ""
                                                    fileRootId.value = result?.optString("rootId", "") ?: ""
                                                    fileStatus.value = if (result?.optBoolean("truncated") == true)
                                                        "Showing first ${parsed.size} entries (folder has more)"
                                                    else "${parsed.size} entries"
                                                }
                                            }
                                            "file.download.begin" -> runCatching {
                                                downloadReceiver.begin(message.payload)
                                            }.onSuccess {
                                                mainHandler.post { fileDownloadStatus.value = "Receiving ${message.payload.optString("name")}" }
                                            }.onFailure {
                                                downloadReceiver.abort()
                                                mainHandler.post { fileDownloadStatus.value = it.message ?: "Could not start download" }
                                            }
                                            "file.download.chunk" -> runCatching {
                                                downloadReceiver.chunk(message.payload)
                                            }.onFailure {
                                                downloadReceiver.abort()
                                                mainHandler.post { fileDownloadStatus.value = it.message ?: "Download chunk rejected" }
                                            }
                                            "file.download.complete" -> runCatching {
                                                downloadReceiver.complete(message.payload)
                                            }.onSuccess { saved ->
                                                mainHandler.post { fileDownloadStatus.value = "Saved: $saved" }
                                            }.onFailure {
                                                downloadReceiver.abort()
                                                mainHandler.post { fileDownloadStatus.value = it.message ?: "Download verification failed" }
                                            }
                                            "file.download.error" -> {
                                                downloadReceiver.fail(message.payload)
                                                mainHandler.post { fileDownloadStatus.value = message.payload.optString("reason", "Remote file transfer failed") }
                                            }
                                        }
                                        if (message.type == "notifications.sync.result") {^_.+-]+/[A-Za-z0-9!#                                    override fun onRemoteMessage(message: RemoteMessage) {
                                        if (message.type == "notifications.sync.result") {^_.+-]+")) }
                                                    ?: "application/octet-stream"
                                                val values = ContentValues().apply {
                                                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/RKRemote")
                                                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                                                }
                                                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                                    ?: error("Could not create a file in Downloads")
                                                downloadUri = uri
                                                downloadOutput = context.contentResolver.openOutputStream(uri, "w")
                                                    ?: error("Could not open the destination file")
                                                downloadDigest = MessageDigest.getInstance("SHA-256")
                                                downloadBytes = 0L
                                                downloadExpectedBytes = message.payload.optLong("totalBytes", -1L)
                                                mainHandler.post { fileStatus.value = "Receiving ${name} · ${downloadExpectedBytes} bytes" }
                                            }.onFailure {
                                                downloadOutput?.close()
                                                downloadOutput = null
                                                downloadUri?.let { context.contentResolver.delete(it, null, null) }
                                                downloadUri = null
                                                mainHandler.post { fileStatus.value = it.message ?: "Could not create download file" }
                                            }
                                        }
                                        if (message.type == "file.download.chunk") {
                                            runCatching {
                                                val offset = message.payload.getLong("offset")
                                                check(offset == downloadBytes) { "File chunk offset mismatch" }
                                                val bytes = Base64.getDecoder().decode(message.payload.getString("data"))
                                                check(bytes.isNotEmpty() && bytes.size <= SecureSafTransferBridge.MAX_CHUNK_BYTES)
                                                downloadOutput?.write(bytes) ?: error("No active file download")
                                                downloadDigest?.update(bytes)
                                                downloadBytes += bytes.size.toLong()
                                                check(downloadBytes <= SecureSafTransferBridge.MAX_FILE_BYTES)
                                            }.onFailure {
                                                downloadOutput?.close()
                                                downloadOutput = null
                                                downloadUri?.let { context.contentResolver.delete(it, null, null) }
                                                downloadUri = null
                                                mainHandler.post { fileStatus.value = it.message ?: "File transfer failed" }
                                            }
                                        }
                                        if (message.type == "file.download.complete") {
                                            runCatching {
                                                val expectedHash = message.payload.getString("sha256")
                                                val actualHash = downloadDigest?.digest()?.joinToString("") { "%02x".format(it) }
                                                check(actualHash == expectedHash) { "Downloaded file SHA-256 verification failed" }
                                                check(downloadBytes == message.payload.getLong("totalBytes")) { "Downloaded file size mismatch" }
                                                downloadOutput?.flush()
                                                downloadOutput?.close()
                                                downloadOutput = null
                                                val uri = downloadUri ?: error("Download destination disappeared")
                                                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                                                context.contentResolver.update(uri, values, null, null)
                                                downloadUri = null
                                                mainHandler.post { fileStatus.value = "Download verified · ${downloadBytes} bytes · saved in Download/RKRemote" }
                                            }.onFailure {
                                                downloadOutput?.close()
                                                downloadOutput = null
                                                downloadUri?.let { context.contentResolver.delete(it, null, null) }
                                                downloadUri = null
                                                mainHandler.post { fileStatus.value = it.message ?: "Download verification failed" }
                                            }
                                        }
                                        if (message.type == "file.download.error") {
                                            downloadOutput?.close()
                                            downloadOutput = null
                                            downloadUri?.let { context.contentResolver.delete(it, null, null) }
                                            downloadUri = null
                                            mainHandler.post { fileStatus.value = message.payload.optString("reason", "File transfer failed") }
                                        }
                                        if (message.type == "notifications.sync.result") {
                                            val events = message.payload.optJSONArray("events")
                                            mainHandler.post {
                                                val preview = buildList {
                                                    val count = (events?.length() ?: 0).coerceAtMost(5)
                                                    for (index in 0 until count) {
                                                        val item = events?.optJSONObject(index) ?: continue
                                                        add(item.optString("title") + ": " + item.optString("text"))
                                                    }
                                                }
                                                notificationSummary.value = if (preview.isEmpty()) "No recent notification events" else preview.joinToString("\n")
                                            }
                                        }
                                        if (message.type.startsWith("screen.video.")) {
                                            renderer.value?.accept(message)
                                            mainHandler.post {
                                                streamStatus.value = when (message.type) {
                                                    "screen.video.config" -> "Receiving H.264 screen stream"
                                                    "screen.video.end" -> "Remote screen stream ended"
                                                    else -> streamStatus.value
                                                }
                                            }
                                        }
                                        mainHandler.post {
                                            status.value = "Secure message: ${message.type} · seq ${message.sequence}"
                                            if (message.type == "secure.ping") {
                                                val correlation = message.payload.optString("correlationId")
                                                session.value?.sendMessage("secure.pong", JSONObject().put("correlationId", correlation))
                                            }
                                        }
                                    }
                                    override fun onCommandResult(message: RemoteMessage, result: SecureRemoteCommandRouter.Result) {
                                        mainHandler.post {
                                            status.value = "Command ${message.type}: ${result.reason}"
                                        }
                                    }
                                    override fun onRoomEvent(type: String, payload: JSONObject) {
                                        mainHandler.post { status.value = "Signaling room: $type" }
                                    }
                                    override fun onDisconnected(reason: String) {
                                        streamSenderState.value?.close()
                                        streamSenderState.value = null
                                        mainHandler.post {
                                            dataChannelConnected.value = false
                                            status.value = reason
                                        }
                                    }
                                    override fun onFailure(failure: Throwable) {
                                        streamSenderState.value?.close()
                                        streamSenderState.value = null
                                        mainHandler.post {
                                            dataChannelConnected.value = false
                                            error.value = failure.message ?: "Secure connection failed"
                                        }
                                    }
                                }
                            )
                            activePeer = created
                            session.value = created
                            status.value = "Connecting to Cloudflare rendezvous"
                            created.connect()
                        } catch (failure: Throwable) {
                            error.value = failure.message ?: "Could not start SecureLink"
                        }
                    }
                ) { Text("Connect") }
                OutlinedButton(
                    enabled = session.value != null,
                    onClick = {
                        streamSenderState.value?.close()
                        streamSenderState.value = null
                        session.value?.revokeOwnerApprovedCommands()
                        session.value?.close()
                        session.value = null
                        renderer.value?.close()
                        renderer.value = null
                        dataChannelConnected.value = false
                        peerId.value = null
                        sas.value = null
                        status.value = "Disconnected locally"
                    }
                ) { Text("Stop") }
            }
            if (Build.VERSION.SDK_INT < 33) {
                Text("SecureLink requires Android 13 (API 33) or later in this build.")
            }
            OutlinedButton(
                enabled = session.value != null && dataChannelConnected.value,
                onClick = {
                    val correlation = java.util.UUID.randomUUID().toString()
                    val sent = session.value?.sendMessage("secure.ping", JSONObject().put("correlationId", correlation)) == true
                    status.value = if (sent) "Secure ping sent · waiting for peer response" else "DataChannel is not open"
                }
            ) { Text("Test secure message exchange") }
            if (role.value == CloudflareSignalingClient.Role.TARGET) {
                Button(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        runCatching {
                            session.value?.activateOwnerApprovedCommands(setOf(RemoteCapability.SCREEN_VIEW))
                        }.onSuccess {
                            status.value = "Screen-view permission granted for this session only"
                        }.onFailure {
                            error.value = it.message ?: "Could not grant screen-view permission"
                        }
                    }
                ) { Text("Allow this controller to view my screen") }
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        streamSenderState.value?.close()
                        streamSenderState.value = null
                        session.value?.revokeOwnerApprovedCommands()
                        status.value = "All remote command permissions revoked for this session"
                    }
                ) { Text("Revoke remote control permissions") }
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        runCatching {
                            session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.FILES))
                        }.onSuccess { status.value = "Secure file access approved for this session" }
                            .onFailure { error.value = it.message ?: "Could not grant file access" }
                    }
                ) { Text("Allow secure file browsing and downloads") }
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        runCatching {
                            check(RemoteAccessibilityService.isActive()) {
                                "Enable RKRemote in Android Accessibility settings on the target first"
                            }
                            session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.SCREEN_INPUT))
                        }.onSuccess {
                            status.value = "Remote touch and text input permission granted for this session"
                        }.onFailure {
                            error.value = it.message ?: "Could not grant remote input permission"
                        }
                    }
                ) { Text("Allow remote touch and text input") }
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        runCatching {
                            session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.FILES))
                        }.onSuccess { status.value = "Secure file browsing and downloads approved for this session" }
                            .onFailure { error.value = it.message ?: "Could not grant file access" }
                    }
                ) { Text("Allow secure file browsing and downloads") }
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        runCatching {
                            check(SecureNotificationSync.isNotificationAccessEnabled(context)) { "Enable RKRemote Notification Access in Android settings first" }
                            session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.NOTIFICATIONS))
                        }.onSuccess { status.value = "Notification sharing approved for this session" }
                            .onFailure { error.value = it.message ?: "Could not grant notification permission" }
                    }
                ) { Text("Allow notification sharing") }
                Text(
                    "Start Android screen sharing locally first. This permission does not start MediaProjection; the target must already be visibly sharing its screen.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = session.value != null && dataChannelConnected.value,
                        onClick = {
                            val sent = session.value?.sendMessage(
                                "screen.stream.start",
                                JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                            ) == true
                            streamStatus.value = if (sent) "Screen request sent" else "Could not send screen request"
                        }
                    ) { Text("Request live screen") }
                    OutlinedButton(
                        enabled = session.value != null && dataChannelConnected.value,
                        onClick = {
                            val sent = session.value?.sendMessage(
                                "screen.stream.stop",
                                JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                            ) == true
                            streamStatus.value = if (sent) "Stop request sent" else "Could not send stop request"
                        }
                    ) { Text("Stop stream") }
                }
                OutlinedTextField(
                    value = remoteText.value,
                    onValueChange = { remoteText.value = it.take(4096) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Text for the focused field on target") },
                    minLines = 1,
                    maxLines = 3
                )
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value && remoteText.value.isNotEmpty(),
                    onClick = {
                        val sent = session.value?.sendMessage(
                            "screen.input",
                            JSONObject()
                                .put("requestId", java.util.UUID.randomUUID().toString())
                                .put("action", "text")
                                .put("text", remoteText.value)
                        ) == true
                        streamStatus.value = if (sent) "Text insertion request sent" else "Could not send text"
                    }
                ) { Text("Insert text on target") }
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        val sent = session.value?.sendMessage(
                            "file.list.request",
                            JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                                .put("parentId", fileParentId.value)
                        ) == true
                        fileStatus.value = if (sent) "File list requested" else "Could not request file list"
                    }
                ) { Text("Browse target files") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = fileParentId.value.isNotBlank() && session.value != null && dataChannelConnected.value,
                        onClick = {
                            val sent = session.value?.sendMessage(
                                "file.list.request",
                                JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                                    .put("parentId", "")
                            ) == true
                            fileStatus.value = if (sent) "Root folder requested" else "Could not navigate to root"
                        }
                    ) { Text("Root") }
                    OutlinedButton(
                        enabled = selectedFileId.value != null && session.value != null && dataChannelConnected.value,
                        onClick = {
                            val sent = session.value?.sendMessage(
                                "file.download.request",
                                JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                                    .put("documentId", selectedFileId.value)
                            ) == true
                            fileStatus.value = if (sent) "Secure download requested" else "Could not start download"
                        }
                    ) { Text("Download selected") }
                }
                fileEntries.value.forEach { item ->
                    val directory = item.optBoolean("directory")
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (directory) {
                                val sent = session.value?.sendMessage(
                                    "file.list.request",
                                    JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                                        .put("parentId", item.optString("id"))
                                ) == true
                                if (sent) fileStatus.value = "Opening ${item.optString("name")}"
                            } else {
                                selectedFileId.value = item.optString("id")
                                fileStatus.value = "Selected ${item.optString("name")}"
                            }
                        }
                    ) {
                        Text((if (directory) "📁 " else "📄 ") + item.optString("name") +
                            (if (directory) "" else " · " + item.opt("size").toString() + " B"))
                    }
                }
                Text(fileStatus.value, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        val sent = session.value?.sendMessage(
                            "file.list.request",
                            JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                        ) == true
                        fileStatus.value = if (sent) "Folder listing requested" else "Could not request folder listing"
                    }
                ) { Text("Browse target folder") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = session.value != null && dataChannelConnected.value && fileParentId.value != fileRootId.value,
                        onClick = {
                            val sent = session.value?.sendMessage(
                                "file.list.request",
                                JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                                    .put("parentId", fileRootId.value)
                            ) == true
                            if (sent) fileStatus.value = "Returned to selected root folder"
                        }
                    ) { Text("Selected root") }
                }
                Text(fileStatus.value, style = MaterialTheme.typography.bodySmall)
                fileItems.value.take(40).forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            (if (item.optBoolean("directory")) "📁 " else "📄 ") + item.optString("name"),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (item.optBoolean("directory")) {
                            OutlinedButton(onClick = {
                                val sent = session.value?.sendMessage(
                                    "file.list.request",
                                    JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                                        .put("parentId", item.optString("id"))
                                ) == true
                                if (sent) fileStatus.value = "Opening ${item.optString("name")}"
                            }) { Text("Open") }
                        } else {
                            OutlinedButton(
                                enabled = !transferActive.get(),
                                onClick = {
                                    val sent = session.value?.sendMessage(
                                        "file.download.request",
                                        JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
                                            .put("documentId", item.optString("id"))
                                    ) == true
                                    fileDownloadStatus.value = if (sent) "Download requested: ${item.optString("name")}" else "Could not request download"
                                }
                            ) { Text("Download") }
                        }
                    }
                }
                Text(fileDownloadStatus.value, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value,
                    onClick = {
                        val sent = session.value?.sendMessage("notifications.sync.request", JSONObject().put("requestId", java.util.UUID.randomUUID().toString())) == true
                        notificationSummary.value = if (sent) "Notification sync requested" else "Could not request notification sync"
                    }
                ) { Text("Fetch recent notifications") }
                Text(notificationSummary.value, style = MaterialTheme.typography.bodySmall)
            }
            Text(streamStatus.value, style = MaterialTheme.typography.bodySmall)
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(240.dp)
                    .pointerInput(session.value) {
                        detectTapGestures { point ->
                            val x = (point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val y = (point.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            session.value?.sendMessage(
                                "screen.input",
                                JSONObject()
                                    .put("requestId", java.util.UUID.randomUUID().toString())
                                    .put("action", "gesture")
                                    .put("startX", x).put("startY", y)
                                    .put("endX", x).put("endY", y)
                                    .put("durationMs", 80)
                            )
                        }
                    }
                    .pointerInput(session.value) {
                        var dragStart = Offset.Zero
                        var dragEnd = Offset.Zero
                        detectDragGestures(
                            onDragStart = { point -> dragStart = point; dragEnd = point },
                            onDrag = { change, _ -> dragEnd = change.position; change.consume() },
                            onDragEnd = {
                                val x1 = (dragStart.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val y1 = (dragStart.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val x2 = (dragEnd.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val y2 = (dragEnd.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                if (kotlin.math.abs(x2 - x1) + kotlin.math.abs(y2 - y1) > 0.005f) {
                                    session.value?.sendMessage(
                                        "screen.input",
                                        JSONObject()
                                            .put("requestId", java.util.UUID.randomUUID().toString())
                                            .put("action", "gesture")
                                            .put("startX", x1).put("startY", y1)
                                            .put("endX", x2).put("endY", y2)
                                            .put("durationMs", 350)
                                    )
                                }
                            }
                        )
                    },
                factory = { viewContext ->
                    SurfaceView(viewContext).also { surface ->
                        renderer.value = SecureScreenVideoRenderer(surface) { message ->
                            mainHandler.post { streamStatus.value = message }
                        }
                    }
                }
            )
            peerId.value?.let {
                Text("Peer ID: $it", style = MaterialTheme.typography.bodySmall)
            }
            sas.value?.let {
                Text("Verification code: ${it.chunked(3).joinToString(" ")}", style = MaterialTheme.typography.headlineSmall)
                Text("Compare this exact code on both phones. Approve only if both codes and the expected peer match.")
                Button(onClick = {
                    runCatching { session.value?.approvePeer(sas.value!!, peerId.value!!) }
                        .onSuccess { status.value = "Local approval sent · waiting for other device" }
                        .onFailure { error.value = it.message ?: "Approval failed" }
                }) { Text("I compared both devices · approve") }
            }
            Text("Status: ${status.value}", style = MaterialTheme.typography.bodyMedium)
            error.value?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(2.dp))
            Text(
                "Setup: deploy cloudflare/worker, enter its workers.dev WSS URL, generate one room ID on the controller, enter that same ID on the target, connect target first, then controller. For difficult NATs, enter TURN URLs and short-lived credentials from a TURN service you control. Cloudflare Worker is signaling only and does not relay media.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
