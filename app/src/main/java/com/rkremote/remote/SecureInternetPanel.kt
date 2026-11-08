package com.rkremote.remote

import android.os.Build
import android.provider.CallLog
import android.provider.Telephony
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.Date
import java.text.DateFormat
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class SecureFeatureSection {
    CONNECTION, SCREEN, CAMERA, AUDIO, FILES, NOTIFICATIONS, CALL_LOGS, SMS_MESSAGES, SESSION_PERMISSIONS
}

@Composable
fun SecureInternetPanel(feature: SecureFeatureSection = SecureFeatureSection.CONNECTION) {
    val context = LocalContext.current
    val initialNetworkProfile = remember(context) {
        runCatching {
            val raw = context.getSharedPreferences("rkremote_custom_networks", android.content.Context.MODE_PRIVATE)
                .getString("active_profile", null) ?: return@runCatching null
            JSONObject(raw)
        }.getOrNull()
    }
    val setupPrefs = remember(context) { context.getSharedPreferences("rkremote_cloudflare_setup", android.content.Context.MODE_PRIVATE) }
    val savedRole = remember { setupPrefs.getString("role", "target") ?: "target" }
    val url = remember { mutableStateOf(setupPrefs.getString("worker_url", null) ?: initialNetworkProfile?.optString("signalingUrl").orEmpty()) }
    val room = remember { mutableStateOf(setupPrefs.getString("room_id", null).orEmpty()) }
    val role = remember { mutableStateOf(if (savedRole == "controller") CloudflareSignalingClient.Role.CONTROLLER else CloudflareSignalingClient.Role.TARGET) }
    val turnUrls = remember {
        val savedIce = setupPrefs.getString("ice_urls", null)
        val addresses = initialNetworkProfile?.optJSONArray("iceUrls")
        mutableStateOf(savedIce ?: if (addresses == null) "" else
            (0 until addresses.length()).joinToString(", ") { addresses.optString(it) })
    }
    val turnUsername = remember { mutableStateOf("") }
    val turnCredential = remember { mutableStateOf("") }
    val forceTurnRelay = remember { mutableStateOf(false) }
    val status = remember { mutableStateOf("Not connected") }
    val dataChannelConnected = remember { mutableStateOf(false) }
    val peerId = remember { mutableStateOf<String?>(null) }
    val sas = remember { mutableStateOf<String?>(null) }
    val error = remember { mutableStateOf<String?>(null) }
    val session = remember { mutableStateOf<SecureInternetPeer?>(null) }
    val activePeerRef = remember { AtomicReference<SecureInternetPeer?>(null) }
    val renderer = remember { mutableStateOf<SecureScreenVideoRenderer?>(null) }
    val cameraBitmap = remember { mutableStateOf<Bitmap?>(null) }
    val cameraStatus = remember { mutableStateOf("Camera snapshot idle") }
    val imageAssembler = remember { SecureImageFrameAssembler() }
    val cameraTransferActive = remember { AtomicBoolean(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val streamStatus = remember { mutableStateOf("Screen stream idle") }
    val streamSender = remember { mutableStateOf<SecureScreenStreamSender?>(null) }
    val remoteText = remember { mutableStateOf("") }
    val notificationSummary = remember { mutableStateOf("No notification sync requested") }
    val callLogItems = remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val callLogStatus = remember { mutableStateOf("هنوز گزارشی بارگذاری نشده است") }
    val smsItems = remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val smsStatus = remember { mutableStateOf("هنوز گزارشی بارگذاری نشده است") }
    val fileItems = remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val fileStatus = remember { mutableStateOf("No folder loaded") }
    val fileParentId = remember { mutableStateOf("") }
    val fileRootId = remember { mutableStateOf("") }
    val fileDownloadStatus = remember { mutableStateOf("No download active") }
    val fileUploadStatus = remember { mutableStateOf("No upload active") }
    val downloadReceiver = remember(context) { SecureDownloadReceiver(context) }
    val downloadResumePrefs = remember(context) {
        context.getSharedPreferences("rkremote_download_resume", android.content.Context.MODE_PRIVATE)
    }
    val pendingDownloadId = remember(downloadReceiver) { mutableStateOf(downloadReceiver.pendingTransferId()) }
    val pendingDownloadDocumentId = remember(context) {
        mutableStateOf(downloadResumePrefs.getString("document_id", null))
    }
    val safBridge = remember(context) { SecureSafTransferBridge(context) }
    val transferExecutor = remember { Executors.newFixedThreadPool(SecureSafTransferBridge.MAX_ACTIVE_UPLOADS) }
    val transferActive = remember { AtomicBoolean(false) }
    val activeTransferId = remember { AtomicReference<String?>(null) }
    val targetActiveUploadIds = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    val outgoingUploadIds = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    val audioRelayActive = remember { AtomicBoolean(false) }
    val pttRelayActive = remember { AtomicBoolean(false) }
    val pttStatus = remember { mutableStateOf("Push-to-talk idle") }
    val audioExecutor = remember { Executors.newSingleThreadExecutor() }
    val audioPlayback = remember(context) { SecureAudioPlayback() }
    val audioStatus = remember { mutableStateOf("Microphone stream idle") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val peer = session.value
            val availableSlots = SecureSafTransferBridge.MAX_ACTIVE_UPLOADS - outgoingUploadIds.size
            if (peer == null || uris.size > availableSlots) {
                fileUploadStatus.value = if (peer == null) "Not connected" else "Only $availableSlots upload slot(s) available"
            } else {
                val destinationParent = fileParentId.value.ifBlank { fileRootId.value }
                uris.forEach { uri ->
                    val transferId = UUID.randomUUID().toString()
                    if (!outgoingUploadIds.add(transferId)) return@forEach
                    transferExecutor.execute {
                        var uploadFailureMessage: String? = null
                        try {
                            var displayName = "upload.bin"
                            var sizeBytes = -1L
                            context.contentResolver.query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                                null,
                                null,
                                null
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    displayName = cursor.getString(0) ?: displayName
                                    sizeBytes = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                                }
                            }
                            require(sizeBytes in 0L..SecureSafTransferBridge.MAX_FILE_BYTES) {
                                "File size is unknown or exceeds 50 MiB"
                            }
                            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            fun sendWithRetry(type: String, payload: JSONObject): Boolean {
                                var attempt = 0
                                while (outgoingUploadIds.contains(transferId) && attempt < 300) {
                                    if (peer.sendMessage(type, payload)) return true
                                    attempt++
                                    Thread.sleep(100)
                                }
                                return false
                            }
                            check(sendWithRetry(
                                "file.upload.begin",
                                JSONObject()
                                    .put("requestId", UUID.randomUUID().toString())
                                    .put("transferId", transferId)
                                    .put("name", displayName)
                                    .put("mimeType", mime)
                                    .put("totalBytes", sizeBytes)
                                    .put("parentId", destinationParent)
                            )) { "Could not start secure upload" }
                            val digest = MessageDigest.getInstance("SHA-256")
                            var total = 0L
                            var lastReportedPercent = -1
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                val buffer = ByteArray(SecureSafTransferBridge.MAX_CHUNK_BYTES)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= SecureSafTransferBridge.MAX_FILE_BYTES) {
                                        "File grew beyond the 50 MiB transfer limit"
                                    }
                                    digest.update(buffer, 0, count)
                                    val bytes = buffer.copyOf(count)
                                    check(sendWithRetry(
                                        "file.transfer.chunk",
                                        JSONObject()
                                            .put("requestId", UUID.randomUUID().toString())
                                            .put("transferId", transferId)
                                            .put("offset", total - count)
                                            .put("data", Base64.getEncoder().encodeToString(bytes))
                                    )) { "Secure upload stopped or connection was lost" }
                                    val percent = if (sizeBytes == 0L) 100 else
                                        ((total * 100L) / sizeBytes).toInt().coerceIn(0, 100)
                                    if (percent != lastReportedPercent) {
                                        lastReportedPercent = percent
                                        mainHandler.post {
                                            if (outgoingUploadIds.contains(transferId)) {
                                                fileUploadStatus.value = "Uploading $displayName · $percent%"
                                            }
                                        }
                                    }
                                }
                            } ?: error("Could not open the selected file")
                            check(total == sizeBytes) { "File size changed during upload" }
                            check(sendWithRetry(
                                "file.transfer.complete",
                                JSONObject()
                                    .put("requestId", UUID.randomUUID().toString())
                                    .put("transferId", transferId)
                                    .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                            )) { "Could not finalize secure upload" }
                            mainHandler.post {
                                fileUploadStatus.value = "Upload sent · ${outgoingUploadIds.size} transfer(s) active or awaiting confirmation"
                            }
                        } catch (failure: Throwable) {
                            runCatching {
                                peer.sendMessage(
                                    "file.transfer.cancel",
                                    JSONObject()
                                        .put("requestId", UUID.randomUUID().toString())
                                        .put("transferId", transferId)
                                )
                            }
                            uploadFailureMessage = failure.message ?: "Upload failed"
                        } finally {
                            outgoingUploadIds.remove(transferId)
                            mainHandler.post {
                                if (outgoingUploadIds.isEmpty()) {
                                    if (uploadFailureMessage != null) {
                                        fileUploadStatus.value = "Upload failed: $uploadFailureMessage"
                                    } else if (fileUploadStatus.value.startsWith("Uploading")) {
                                        fileUploadStatus.value = "Upload sent · waiting for target verification"
                                    }
                                }
                            }
                        }
                    }
                }
                fileUploadStatus.value = "Queued ${uris.size} secure upload(s)"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            transferActive.set(false)
            activeTransferId.set(null)
            outgoingUploadIds.clear()
            targetActiveUploadIds.clear()
            safBridge.cancelAllTransfers()
            targetActiveUploadIds.clear()
            cameraTransferActive.set(false)
            imageAssembler.abort()
            cameraExecutor.shutdownNow()
            audioRelayActive.set(false)
            pttRelayActive.set(false)
            audioPlayback.close()
            audioExecutor.shutdownNow()
            streamSender.value?.close()
            streamSender.value = null
            val disposingSession = session.value
            activePeerRef.compareAndSet(disposingSession, null)
            if (disposingSession != null) {
                SessionForegroundService.stopInternetSession(context)
                disposingSession.revokeOwnerApprovedCommands()
                disposingSession.close()
            }
            session.value = null
            renderer.value?.close()
            renderer.value = null
            transferExecutor.shutdownNow()
            downloadReceiver.close()
            safBridge.abortUpload()
        }
    }

    fun sendInput(type: String, payload: JSONObject): Boolean =
        session.value?.sendMessage(type, payload.put("requestId", UUID.randomUUID().toString())) == true

    fun resumePendingDownloadAfterReconnect() {
        val transferId = pendingDownloadId.value ?: return
        val documentId = pendingDownloadDocumentId.value ?: return
        if (!downloadReceiver.hasPendingTransfer() ||
            downloadReceiver.pendingTransferId() != transferId ||
            transferActive.get()
        ) return
        if (!transferActive.compareAndSet(false, true)) return
        activeTransferId.set(transferId)
        val offset = downloadReceiver.pendingOffset()
        val sent = session.value?.sendMessage(
            "file.download.request",
            JSONObject().put("requestId", transferId)
                .put("documentId", documentId)
                .put("offset", offset)
        ) == true
        if (!sent) {
            transferActive.set(false)
            activeTransferId.compareAndSet(transferId, null)
            fileDownloadStatus.value = "Download is saved; resume will retry when the connection is ready"
        } else {
            fileDownloadStatus.value = "Automatically resuming download from $offset bytes"
        }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (feature != SecureFeatureSection.CONNECTION) {
                val sectionTitle = when (feature) {
                    SecureFeatureSection.SCREEN -> "نمایش و کنترل گوشی"
                    SecureFeatureSection.CAMERA -> "دوربین زنده"
                    SecureFeatureSection.AUDIO -> "میکروفن و واکی‌تاکی"
                    SecureFeatureSection.FILES -> "مدیریت و انتقال فایل‌ها"
                    SecureFeatureSection.NOTIFICATIONS -> "همگام‌سازی اعلان‌ها"
                    SecureFeatureSection.CALL_LOGS -> "گزارش تاریخچه تماس‌ها"
                    SecureFeatureSection.SMS_MESSAGES -> "گزارش پیامک‌ها"
                    SecureFeatureSection.SESSION_PERMISSIONS -> "مجوزهای نشست"
                    SecureFeatureSection.CONNECTION -> "اتصال و روش‌ها"
                }
                Text(sectionTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "این قابلیت مستقل از بخش‌های دیگر مدیریت می‌شود؛ اتصال امن باید برقرار و تأیید دوطرفه انجام شده باشد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (feature == SecureFeatureSection.CONNECTION) {
            Text("Secure Internet P2P", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Noise XX identity verification + WebRTC DTLS DataChannel. Cloudflare is used only for encrypted rendezvous signaling.",
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
            OutlinedTextField(value = turnUrls.value, onValueChange = { turnUrls.value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Optional TURN URLs (comma-separated)") }, singleLine = true)
            OutlinedButton(enabled = session.value == null, onClick = {
                runCatching {
                    val saved = context.getSharedPreferences("rkremote_cloudflare_setup", android.content.Context.MODE_PRIVATE)
                    url.value = saved.getString("worker_url", "").orEmpty()
                    room.value = saved.getString("room_id", "").orEmpty()
                    turnUrls.value = saved.getString("ice_urls", "").orEmpty()
                    role.value = if (saved.getString("role", "target") == "controller")
                        CloudflareSignalingClient.Role.CONTROLLER else CloudflareSignalingClient.Role.TARGET
                    turnUsername.value = ""
                    turnCredential.value = ""
                    forceTurnRelay.value = false
                    error.value = "Per-device Cloudflare setup loaded"
                }.onFailure { error.value = it.message ?: "Could not load saved setup" }
            }) { Text("Load saved Cloudflare setup for this phone") }
            OutlinedButton(enabled = session.value == null, onClick = {
                runCatching {
                    val raw = context.getSharedPreferences("rkremote_custom_networks", android.content.Context.MODE_PRIVATE)
                        .getString("active_profile", null) ?: error("No active custom network profile")
                    val profile = JSONObject(raw)
                    val addresses = profile.optJSONArray("iceUrls")
                    url.value = profile.getString("signalingUrl")
                    turnUrls.value = if (addresses == null) "" else
                        (0 until addresses.length()).joinToString(", ") { addresses.optString(it) }
                    turnUsername.value = ""
                    turnCredential.value = ""
                    forceTurnRelay.value = false
                    error.value = "Loaded profile: ${profile.optString("name")} · enter TURN credentials if required"
                }.onFailure { error.value = it.message ?: "Could not load profile" }
            }) { Text("Load active custom network profile") }
            OutlinedTextField(value = turnUsername.value, onValueChange = { turnUsername.value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("TURN username") }, singleLine = true)
            OutlinedTextField(value = turnCredential.value, onValueChange = { turnCredential.value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("TURN credential") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = forceTurnRelay.value, onCheckedChange = { forceTurnRelay.value = it }, enabled = session.value == null)
                Column {
                    Text("Force TURN relay (diagnostic)", style = MaterialTheme.typography.bodyMedium)
                    Text("Requires working TURN URLs and temporary credentials; direct ICE paths are disabled.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = session.value == null, onClick = { role.value = CloudflareSignalingClient.Role.TARGET }) {
                    Text(if (role.value == CloudflareSignalingClient.Role.TARGET) "✓ Target" else "Target")
                }
                OutlinedButton(enabled = session.value == null, onClick = { role.value = CloudflareSignalingClient.Role.CONTROLLER }) {
                    Text(if (role.value == CloudflareSignalingClient.Role.CONTROLLER) "✓ Controller" else "Controller")
                }
                OutlinedButton(onClick = { room.value = CloudflareSignalingClient.newRoomId() }) { Text("New room") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = session.value == null && Build.VERSION.SDK_INT >= 33 &&
                        url.value.startsWith("wss://") && room.value.matches(Regex("[a-f0-9]{64}")),
                    onClick = {
                        error.value = null
                        peerId.value = null
                        sas.value = null
                        var createdSession: SecureInternetPeer? = null
                        try {
                            val identityStore = P2pIdentityStore()
                            val handshake = SecureLinkHandshake.create(
                                initiator = role.value == CloudflareSignalingClient.Role.CONTROLLER,
                                identityStore = identityStore,
                                staticKeyStore = SecureLinkStaticKeyStore(context)
                            )
                            val configuredTurnServers = if (
                                turnUrls.value.isBlank() && turnUsername.value.isBlank() && turnCredential.value.isBlank()
                            ) emptyList() else {
                                require(turnUrls.value.isNotBlank() && turnUsername.value.isNotBlank() && turnCredential.value.isNotBlank()) {
                                    "Enter TURN URLs, username and credential together"
                                }
                                listOf(SecureWebRtcPeer.TurnServer(
                                    turnUrls.value.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                    turnUsername.value.trim(),
                                    turnCredential.value
                                ))
                            }
                            require(!forceTurnRelay.value || configuredTurnServers.isNotEmpty()) {
                                "Force TURN relay requires TURN URLs, username and credential"
                            }
                            var activePeer: SecureInternetPeer? = null
                            val targetRouter = if (role.value == CloudflareSignalingClient.Role.TARGET) {
                                SecureRemoteCommandRouter().apply {
                                    register("file.list.request", RemoteCapability.FILES) { message ->
                                        val result = safBridge.list(message.payload.optString("parentId").takeIf { it.isNotBlank() })
                                        check(activePeer?.sendMessage(
                                            "file.list.result",
                                            JSONObject()
                                                .put("requestId", message.payload.getString("requestId"))
                                                .put("result", result)
                                        ) == true) { "Could not send encrypted folder listing" }
                                        "file.list.result"
                                    }
                                    register("file.upload.begin", RemoteCapability.FILES) { message ->
                                        val transferId = message.payload.getString("transferId")
                                        check(targetActiveUploadIds.size < SecureSafTransferBridge.MAX_ACTIVE_UPLOADS) {
                                            "Too many concurrent uploads"
                                        }
                                        check(targetActiveUploadIds.add(transferId)) {
                                            "This upload ID is already active"
                                        }
                                        try {
                                            safBridge.beginUpload(
                                                transferId = transferId,
                                                rawName = message.payload.getString("name"),
                                                rawMimeType = message.payload.optString("mimeType", "application/octet-stream"),
                                                totalBytes = message.payload.getLong("totalBytes"),
                                                parentId = message.payload.optString("parentId").takeIf { it.isNotBlank() }
                                            )
                                        } catch (failure: Throwable) {
                                            targetActiveUploadIds.remove(transferId)
                                            throw failure
                                        }
                                        null
                                    }
                                    register("file.transfer.chunk", RemoteCapability.FILES) { message ->
                                        val transferId = message.payload.getString("transferId")
                                        check(targetActiveUploadIds.contains(transferId)) {
                                            "Upload was cancelled or is no longer active"
                                        }
                                        try {
                                            val bytes = Base64.getDecoder().decode(message.payload.getString("data"))
                                            safBridge.appendUploadChunk(
                                                transferId,
                                                message.payload.getLong("offset"),
                                                bytes
                                            )
                                        } catch (failure: Throwable) {
                                            safBridge.abortUpload(transferId)
                                            targetActiveUploadIds.remove(transferId)
                                            throw failure
                                        }
                                        null
                                    }
                                    register("file.transfer.complete", RemoteCapability.FILES) { message ->
                                        val transferId = message.payload.getString("transferId")
                                        check(targetActiveUploadIds.contains(transferId)) {
                                            "Upload was cancelled or is no longer active"
                                        }
                                        try {
                                            safBridge.completeUpload(
                                                transferId,
                                                message.payload.getString("sha256")
                                            )
                                        } finally {
                                            targetActiveUploadIds.remove(transferId)
                                        }
                                        null
                                    }
                                    register("file.transfer.cancel", RemoteCapability.FILES) { message ->
                                        val transferId = message.payload.getString("transferId")
                                        if (targetActiveUploadIds.remove(transferId)) {
                                            safBridge.cancelTransfer(transferId)
                                        } else {
                                            check(activeTransferId.get() == transferId) {
                                                "No active file transfer matches this cancellation request"
                                            }
                                            // Downloads share one receiver and are cancelled independently
                                            // from the concurrent upload registry.
                                            safBridge.cancelTransfer(transferId)
                                            transferActive.set(false)
                                            activeTransferId.compareAndSet(transferId, null)
                                        }
                                        null
                                    }
                                    register("file.download.request", RemoteCapability.FILES) { message ->
                                        val transferId = message.payload.getString("requestId")
                                        val documentId = message.payload.getString("documentId")
                                        check(activeTransferId.compareAndSet(null, transferId)) {
                                            "Another file transfer is already active"
                                        }
                                        if (!transferActive.compareAndSet(false, true)) {
                                            activeTransferId.compareAndSet(transferId, null)
                                            error("Another file transfer is already active")
                                        }
                                        transferExecutor.execute {
                                            var retryableNetworkFailure = false
                                            try {
                                                safBridge.streamFile(
                                                    transferId = transferId,
                                                    documentId = documentId,
                                                    startOffset = message.payload.optLong("offset", 0L),
                                                    onStart = { metadata ->
                                                        val started = activeTransferId.get() == transferId && transferActive.get() &&
                                                            activePeer?.sendMessage(
                                                            "file.download.begin",
                                                            JSONObject()
                                                                .put("transferId", transferId)
                                                                .put("name", metadata.name)
                                                                .put("mimeType", metadata.mimeType)
                                                                .put("totalBytes", metadata.sizeBytes)
                                                        ) == true
                                                        if (!started) retryableNetworkFailure = true
                                                        started
                                                    },
                                                    onChunk = { offset, bytes ->
                                                        var sent = false
                                                        var attempts = 0
                                                        while (!sent && attempts < 300 &&
                                                            transferActive.get() && activeTransferId.get() == transferId) {
                                                            sent = activePeer?.sendMessage(
                                                                "file.download.chunk",
                                                                JSONObject()
                                                                    .put("transferId", transferId)
                                                                    .put("offset", offset)
                                                                    .put("data", Base64.getEncoder().encodeToString(bytes))
                                                            ) == true
                                                            if (!sent) {
                                                                attempts++
                                                                Thread.sleep(100)
                                                            }
                                                        }
                                                        if (!sent) retryableNetworkFailure = true
                                                        sent && transferActive.get() && activeTransferId.get() == transferId
                                                    },
                                                    onComplete = { hash ->
                                                        if (transferActive.get() && activeTransferId.get() == transferId) {
                                                            val completed = activePeer?.sendMessage(
                                                                "file.download.complete",
                                                                JSONObject().put("transferId", transferId).put("sha256", hash)
                                                            ) == true
                                                            if (!completed) retryableNetworkFailure = true
                                                            check(completed) { "Could not send file-transfer completion" }
                                                        }
                                                    }
                                                )
                                            } catch (failure: Throwable) {
                                                activePeer?.sendMessage(
                                                    "file.download.error",
                                                    JSONObject()
                                                        .put("transferId", transferId)
                                                        .put("retryable", retryableNetworkFailure)
                                                        .put("reason", failure.message ?: "File transfer failed")
                                                )
                                            } finally {
                                                transferActive.set(false)
                                                activeTransferId.compareAndSet(transferId, null)
                                            }
                                        }
                                        "file.download.begin"
                                    }
                                    register("camera.start.request", RemoteCapability.CAMERA) {
                                        check(CameraFrameStore.latestFrame != null) {
                                            "Start the camera locally on the target first"
                                        }
                                        check(cameraTransferActive.compareAndSet(false, true)) {
                                            "Secure camera stream is already active"
                                        }
                                        cameraExecutor.execute {
                                            try {
                                                fun sendWithRetry(type: String, payload: JSONObject): Boolean {
                                                    var attempts = 0
                                                    while (cameraTransferActive.get() && attempts < 200) {
                                                        if (activePeer?.sendMessage(type, payload) == true) return true
                                                        attempts++
                                                        Thread.sleep(50)
                                                    }
                                                    return false
                                                }

                                                // Relay only fresh frames already published by the
                                                // locally started CameraCaptureService. This loop
                                                // never opens or switches the camera.
                                                while (cameraTransferActive.get()) {
                                                    val frame = CameraFrameStore.latestFrame?.copyOf()
                                                        ?: error("Local camera capture stopped")
                                                    require(frame.size in 1..SecureImageFrameAssembler.MAX_IMAGE_BYTES) {
                                                        "Camera frame exceeds the secure stream limit"
                                                    }
                                                    val transferId = UUID.randomUUID().toString()
                                                    check(sendWithRetry(
                                                        "camera.frame.begin",
                                                        JSONObject()
                                                            .put("transferId", transferId)
                                                            .put("totalBytes", frame.size)
                                                            .put("mimeType", CameraFrameStore.latestMimeType)
                                                    )) { "Could not start secure camera frame transfer" }

                                                    var offset = 0
                                                    while (offset < frame.size && cameraTransferActive.get()) {
                                                        val endOffset = minOf(
                                                            offset + SecureImageFrameAssembler.MAX_CHUNK_BYTES,
                                                            frame.size
                                                        )
                                                        val chunk = frame.copyOfRange(offset, endOffset)
                                                        check(sendWithRetry(
                                                            "camera.frame.chunk",
                                                            JSONObject()
                                                                .put("transferId", transferId)
                                                                .put("offset", offset)
                                                                .put("data", Base64.getEncoder().encodeToString(chunk))
                                                        )) { "Secure camera frame transfer was interrupted" }
                                                        offset = endOffset
                                                    }
                                                    if (!cameraTransferActive.get()) break
                                                    val hash = MessageDigest.getInstance("SHA-256").digest(frame)
                                                        .joinToString("") { "%02x".format(it) }
                                                    check(sendWithRetry(
                                                        "camera.frame.complete",
                                                        JSONObject().put("transferId", transferId).put("sha256", hash)
                                                    )) { "Could not complete secure camera frame transfer" }
                                                    Thread.sleep(650)
                                                }
                                            } catch (failure: Throwable) {
                                                if (cameraTransferActive.get()) {
                                                    mainHandler.post {
                                                        if (activePeerRef.get() !== activePeer) return@post
                                                        cameraStatus.value = failure.message ?: "Secure camera stream failed"
                                                    }
                                                }
                                            } finally {
                                                cameraTransferActive.set(false)
                                            }
                                        }
                                        "camera.frame.begin"
                                    }
                                    register("camera.stop", RemoteCapability.CAMERA) {
                                        cameraTransferActive.set(false)
                                        null
                                    }
                                    register("screen.stream.start", RemoteCapability.SCREEN_VIEW) {
                                        check(ScreenVideoStore.hasConfig()) { "Start screen sharing locally on the target first" }
                                        streamSender.value?.close()
                                        val sender = SecureScreenStreamSender(
                                            sendMessage = { type, payload -> activePeer?.sendMessage(type, payload) == true },
                                            onState = { message -> mainHandler.post { streamStatus.value = message } }
                                        )
                                        streamSender.value = sender
                                        check(sender.start()) { "Screen stream is already running" }
                                        "screen.video.config"
                                    }
                                    register("screen.stream.stop", RemoteCapability.SCREEN_VIEW) {
                                        streamSender.value?.close()
                                        streamSender.value = null
                                        null
                                    }
                                    register("ptt.start", RemoteCapability.PUSH_TO_TALK) {
                                        check(PttAudioQueue.isEnabled()) {
                                            "Enable the PTT speaker locally on the target first"
                                        }
                                        check(!audioRelayActive.get() && !MicrophoneChunkStore.isCapturing()) {
                                            "Stop target microphone monitoring before push-to-talk to keep audio half-duplex"
                                        }
                                        check(pttRelayActive.compareAndSet(false, true)) {
                                            "A push-to-talk transmission is already active"
                                        }
                                        null
                                    }
                                    register("ptt.stop", RemoteCapability.PUSH_TO_TALK) {
                                        pttRelayActive.set(false)
                                        PttAudioQueue.clear()
                                        null
                                    }
                                    register("audio.start.request", RemoteCapability.MICROPHONE) {
                                        check(!pttRelayActive.get()) {
                                            "Stop push-to-talk before starting microphone monitoring"
                                        }
                                        check(MicrophoneChunkStore.isCapturing()) {
                                            "Start microphone capture locally on the target first"
                                        }
                                        check(audioRelayActive.compareAndSet(false, true)) {
                                            "Microphone stream is already active"
                                        }
                                        audioExecutor.execute {
                                            try {
                                                while (audioRelayActive.get() && MicrophoneChunkStore.isCapturing()) {
                                                    val chunk = MicrophoneChunkStore.take()
                                                    if (chunk == null) {
                                                        Thread.sleep(12)
                                                        continue
                                                    }
                                                    val sent = activePeer?.sendMessage(
                                                        "audio.chunk",
                                                        JSONObject().put("data", Base64.getEncoder().encodeToString(chunk))
                                                    ) == true
                                                    if (!sent) break
                                                }
                                            } catch (_: InterruptedException) {
                                                Thread.currentThread().interrupt()
                                            } finally {
                                                audioRelayActive.set(false)
                                            }
                                        }
                                        null
                                    }
                                    register("audio.stop", RemoteCapability.MICROPHONE) {
                                        audioRelayActive.set(false)
                                        null
                                    }
                                    register("notifications.sync.request", RemoteCapability.NOTIFICATIONS) { message ->
                                        val response = SecureNotificationSync.buildLatestResponse(
                                            context,
                                            message.payload.getString("requestId")
                                        )
                                        check(activePeer?.sendMessage("notifications.sync.result", response) == true) {
                                            "Could not send encrypted notification response"
                                        }
                                        "notifications.sync.result"
                                    }
                                    register("sms.sync.request", RemoteCapability.CONTACTS) { message ->
                                        val response = SecureSmsSync.buildLatestResponse(
                                            context,
                                            message.payload.getString("requestId")
                                        )
                                        check(activePeer?.sendMessage("sms.sync.result", response) == true) {
                                            "Could not send encrypted SMS report"
                                        }
                                        "sms.sync.result"
                                    }
                                    register("calllog.sync.request", RemoteCapability.CALL_LOG) { message ->
                                        val response = SecureCallLogSync.buildLatestResponse(
                                            context,
                                            message.payload.getString("requestId")
                                        )
                                        check(activePeer?.sendMessage("calllog.sync.result", response) == true) {
                                            "Could not send encrypted call history response"
                                        }
                                        "calllog.sync.result"
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
                                            "text" -> RemoteAccessibilityService.setFocusedText(payload.getString("text"))
                                            else -> error("Unsupported screen input action")
                                        }
                                        check(accepted) { "Android Accessibility is unavailable or rejected this action" }
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
                                forceTurnRelay = forceTurnRelay.value,
                                commandRouter = targetRouter,
                                listener = object : SecureInternetPeer.Listener {
                                    override fun onPeerVerificationRequired(id: String, code: String) {
                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            peerId.value = id
                                            sas.value = code
                                            status.value = "Compare the code on both devices"
                                        }
                                    }
                                    override fun onSecureSignalingReady(id: String) {
                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            status.value = "Owners approved · negotiating WebRTC"
                                            }
                                    }
                                    override fun onWebRtcConnecting() {
                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            status.value = "Establishing direct P2P connection"
                                            }
                                    }
                                    override fun onWebRtcConnected() {
                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            dataChannelConnected.value = true
                                            status.value = "WebRTC DataChannel connected (DTLS)"
                                            resumePendingDownloadAfterReconnect()
                                        }
                                    }
                                    override fun onData(payload: ByteArray) = Unit
                                    override fun onRemoteMessage(message: RemoteMessage) {
                                        when (message.type) {
                                            "camera.frame.begin" -> runCatching {
                                                imageAssembler.begin(message.payload)
                                            }.onSuccess {
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            cameraStatus.value = "Receiving secure camera frame"
                                            }
                                            }.onFailure {
                                                imageAssembler.abort()
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            cameraStatus.value = it.message ?: "Invalid camera frame header"
                                            }
                                            }
                                            "camera.frame.chunk" -> runCatching {
                                                imageAssembler.append(message.payload)
                                            }.onFailure {
                                                imageAssembler.abort()
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            cameraStatus.value = it.message ?: "Invalid camera frame chunk"
                                            }
                                            }
                                            "camera.frame.complete" -> runCatching {
                                                imageAssembler.finish(message.payload)
                                            }.onSuccess { result ->
                                                val bitmap = BitmapFactory.decodeByteArray(result.second, 0, result.second.size)
                                                    ?: error("Received camera snapshot is not a valid image")
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                                    cameraBitmap.value = bitmap
                                                    cameraStatus.value = "Secure live camera frame received"
                                                }
                                            }.onFailure {
                                                imageAssembler.abort()
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            cameraStatus.value = it.message ?: "Camera snapshot verification failed"
                                            }
                                            }
                                            "ptt.audio.chunk" -> {
                                                if (role.value == CloudflareSignalingClient.Role.TARGET && pttRelayActive.get()) {
                                                    runCatching {
                                                        val bytes = Base64.getDecoder().decode(message.payload.getString("data"))
                                                        check(bytes.size in 2..PttAudioQueue.MAX_CHUNK_BYTES && bytes.size % 2 == 0)
                                                        check(PttAudioQueue.offer(bytes)) {
                                                            "Target PTT speaker is not accepting audio"
                                                        }
                                                    }.onFailure { failure ->
                                                        pttRelayActive.set(false)
                                                        PttAudioQueue.clear()
                                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            pttStatus.value = failure.message ?: "PTT audio rejected"
                                            }
                                                    }
                                                }
                                            }
                                            "audio.chunk" -> {
                                                if (role.value == CloudflareSignalingClient.Role.CONTROLLER) {
                                                    runCatching {
                                                        val bytes = Base64.getDecoder().decode(message.payload.getString("data"))
                                                        check(bytes.size in 2..SecureAudioPlayback.MAX_CHUNK_BYTES && bytes.size % 2 == 0)
                                                        audioPlayback.start()
                                                        check(audioPlayback.offer(bytes)) { "Secure audio playback queue is unavailable" }
                                                    }.onFailure { failure ->
                                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            audioStatus.value = failure.message ?: "Audio chunk rejected"
                                            }
                                                    }.onSuccess {
                                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            audioStatus.value = "Receiving secure microphone audio"
                                            }
                                                    }
                                                }
                                            }
                                            "file.list.result" -> {
                                                val result = message.payload.optJSONObject("result")
                                                val array = result?.optJSONArray("items")
                                                val entries = buildList {
                                                    for (index in 0 until (array?.length() ?: 0)) {
                                                        array?.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
                                                    }
                                                }
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                                    fileItems.value = entries
                                                    fileParentId.value = result?.optString("parentId", "") ?: ""
                                                    fileRootId.value = result?.optString("rootId", "") ?: ""
                                                    fileStatus.value = if (result?.optBoolean("truncated") == true)
                                                        "Showing first ${entries.size} entries (more available)"
                                                    else "${entries.size} entries"
                                                }
                                            }
                                            "command.result" -> {
                                                if (message.payload.optString("commandType") == "sms.sync.request" &&
                                                    !message.payload.optBoolean("accepted")
                                                ) {
                                                    mainHandler.post {
                                                        if (activePeerRef.get() !== activePeer) return@post
                                                        smsStatus.value = "دریافت پیامک‌ها رد شد: " +
                                                            message.payload.optString("reason", "دسترسی تأیید نشد")
                                                    }
                                                }
                                                if (message.payload.optString("commandType") == "calllog.sync.request" &&
                                                    !message.payload.optBoolean("accepted")
                                                ) {
                                                    mainHandler.post {
                                                        if (activePeerRef.get() !== activePeer) return@post
                                                        callLogStatus.value = "دریافت گزارش رد شد: " +
                                                            message.payload.optString("reason", "دسترسی تأیید نشد")
                                                    }
                                                }
                                                if (message.payload.optString("commandType").startsWith("file.")) {
                                                    val accepted = message.payload.optBoolean("accepted")
                                                    val transferId = message.payload.optString("transferId")
                                                    mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                                        if (!accepted) {
                                                            if (transferId.isNotBlank()) {
                                                                outgoingUploadIds.remove(transferId)
                                                                if (activeTransferId.get() == transferId) {
                                                                    transferActive.set(false)
                                                                    activeTransferId.compareAndSet(transferId, null)
                                                                    downloadReceiver.abort()
                                                                    downloadResumePrefs.edit().remove("document_id").commit()
                                                                    pendingDownloadId.value = null
                                                                    pendingDownloadDocumentId.value = null
                                                                }
                                                            }
                                                            fileUploadStatus.value =
                                                                message.payload.optString("reason", "Target rejected file transfer")
                                                        } else if (message.payload.optString("commandType") == "file.transfer.complete") {
                                                            if (transferId.isNotBlank()) outgoingUploadIds.remove(transferId)
                                                            fileUploadStatus.value = "Target verified and saved uploaded file"
                                                        }
                                                    }
                                                }
                                            }
                                            "file.download.begin" -> runCatching {
                                                val transferId = message.payload.getString("transferId")
                                                check(activeTransferId.get() == transferId && transferActive.get()) {
                                                    "Download response does not match the active transfer"
                                                }
                                                downloadReceiver.begin(message.payload)
                                            }.onSuccess {
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                                    fileDownloadStatus.value = "Receiving ${message.payload.optString("name")}"
                                                }
                                            }.onFailure {
                                                downloadReceiver.abort()
                                                downloadResumePrefs.edit().remove("document_id").commit()
                                                pendingDownloadId.value = null
                                                pendingDownloadDocumentId.value = null
                                                val transferId = message.payload.optString("transferId")
                                                if (transferId.isNotBlank() && activePeerRef.get() === activePeer) {
                                                    activePeer?.sendMessage(
                                                        "file.transfer.cancel",
                                                        JSONObject()
                                                            .put("requestId", UUID.randomUUID().toString())
                                                            .put("transferId", transferId)
                                                    )
                                                }
                                                if (transferId.isNotBlank()) {
                                                    activeTransferId.compareAndSet(transferId, null)
                                                    transferActive.set(false)
                                                }
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            fileDownloadStatus.value = it.message ?: "Could not start download"
                                            }
                                            }
                                            "file.download.chunk" -> runCatching {
                                                downloadReceiver.chunk(message.payload)
                                            }.onSuccess {
                                                val percent = downloadReceiver.progressPercent()
                                                val received = downloadReceiver.receivedBytes()
                                                val total = downloadReceiver.totalBytes()
                                                mainHandler.post {
                                                    if (activePeerRef.get() !== activePeer) return@post
                                                    fileDownloadStatus.value =
                                                        "Downloading · $percent% ($received/$total bytes)"
                                                }
                                            }.onFailure {
                                                downloadReceiver.abort()
                                                downloadResumePrefs.edit().remove("document_id").commit()
                                                pendingDownloadId.value = null
                                                pendingDownloadDocumentId.value = null
                                                val transferId = message.payload.optString("transferId")
                                                if (transferId.isNotBlank() && activePeerRef.get() === activePeer) {
                                                    activePeer?.sendMessage(
                                                        "file.transfer.cancel",
                                                        JSONObject()
                                                            .put("requestId", UUID.randomUUID().toString())
                                                            .put("transferId", transferId)
                                                    )
                                                }
                                                if (transferId.isNotBlank()) {
                                                    activeTransferId.compareAndSet(transferId, null)
                                                    transferActive.set(false)
                                                }
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            fileDownloadStatus.value = it.message ?: "Download chunk rejected"
                                            }
                                            }
                                            "file.download.complete" -> runCatching {
                                                downloadReceiver.complete(message.payload)
                                            }.onSuccess { saved ->
                                                val transferId = message.payload.optString("transferId")
                                                downloadResumePrefs.edit().remove("document_id").commit()
                                                pendingDownloadId.value = null
                                                pendingDownloadDocumentId.value = null
                                                if (transferId.isNotBlank()) {
                                                    activeTransferId.compareAndSet(transferId, null)
                                                    transferActive.set(false)
                                                }
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            fileDownloadStatus.value = "Saved: $saved"
                                            }
                                            }.onFailure {
                                                downloadReceiver.abort()
                                                downloadResumePrefs.edit().remove("document_id").commit()
                                                pendingDownloadId.value = null
                                                pendingDownloadDocumentId.value = null
                                                val transferId = message.payload.optString("transferId")
                                                if (transferId.isNotBlank()) {
                                                    activeTransferId.compareAndSet(transferId, null)
                                                    transferActive.set(false)
                                                }
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            fileDownloadStatus.value = it.message ?: "Download verification failed"
                                            }
                                            }
                                            "file.download.error" -> {
                                                val retryable = message.payload.optBoolean("retryable", false)
                                                if (retryable) {
                                                    downloadReceiver.pause()
                                                } else {
                                                    downloadReceiver.fail(message.payload)
                                                    downloadResumePrefs.edit().remove("document_id").commit()
                                                    pendingDownloadId.value = null
                                                    pendingDownloadDocumentId.value = null
                                                }
                                                val transferId = message.payload.optString("transferId")
                                                if (transferId.isNotBlank()) {
                                                    activeTransferId.compareAndSet(transferId, null)
                                                    transferActive.set(false)
                                                }
                                                mainHandler.post {
                                                    if (activePeerRef.get() !== activePeer) return@post
                                                    val reason = message.payload.optString("reason", "Remote file transfer failed")
                                                    fileDownloadStatus.value = if (retryable)
                                                        "Connection interrupted · ${downloadReceiver.pendingOffset()} bytes saved · resume after reconnect"
                                                    else reason
                                                }
                                            }
                                            "sms.sync.result" -> {
                                                val records = message.payload.optJSONArray("records")
                                                val entries = buildList {
                                                    for (index in 0 until (records?.length() ?: 0)) {
                                                        records?.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
                                                    }
                                                }
                                                mainHandler.post {
                                                    if (activePeerRef.get() !== activePeer) return@post
                                                    smsItems.value = entries
                                                    val returned = message.payload.optInt("returned", entries.size)
                                                    val available = message.payload.optInt("available", returned)
                                                    smsStatus.value = if (message.payload.optBoolean("truncated"))
                                                        "$returned پیامک از $available پیامک (گزارش محدود شده)"
                                                    else "$returned پیامک دریافت شد"
                                                }
                                            }
                                            "calllog.sync.result" -> {
                                                val records = message.payload.optJSONArray("records")
                                                val entries = buildList {
                                                    for (index in 0 until (records?.length() ?: 0)) {
                                                        records?.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
                                                    }
                                                }
                                                mainHandler.post {
                                                    if (activePeerRef.get() !== activePeer) return@post
                                                    callLogItems.value = entries
                                                    val returned = message.payload.optInt("returned", entries.size)
                                                    val available = message.payload.optInt("available", returned)
                                                    callLogStatus.value = if (message.payload.optBoolean("truncated"))
                                                        "$returned تماس از $available تماس (گزارش محدود شده)"
                                                    else "$returned تماس دریافت شد"
                                                }
                                            }
                                            "notifications.sync.result" -> {
                                                val events = message.payload.optJSONArray("events")
                                                val preview = buildList {
                                                    for (index in 0 until minOf(events?.length() ?: 0, 5)) {
                                                        val item = events?.optJSONObject(index) ?: continue
                                                        add(item.optString("title") + ": " + item.optString("text"))
                                                    }
                                                }
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                                    notificationSummary.value = if (preview.isEmpty())
                                                        "No recent notification events"
                                                    else preview.joinToString("\n")
                                                }
                                            }
                                            "screen.video.config", "screen.video.chunk", "screen.video.end" -> {
                                                renderer.value?.accept(message)
                                                mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                                    streamStatus.value = when (message.type) {
                                                        "screen.video.config" -> "Receiving H.264 screen stream"
                                                        "screen.video.end" -> "Remote screen stream ended"
                                                        else -> streamStatus.value
                                                    }
                                                }
                                            }
                                            "secure.ping" -> {
                                                val correlation = message.payload.optString("correlationId")
                                                session.value?.sendMessage(
                                                    "secure.pong",
                                                    JSONObject().put("correlationId", correlation)
                                                )
                                            }
                                        }
                                    }
                                    override fun onCommandResult(
                                        message: RemoteMessage,
                                        result: SecureRemoteCommandRouter.Result
                                    ) {
                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            status.value = "Command ${message.type}: ${result.reason}"
                                            }
                                    }
                                    override fun onRoomEvent(type: String, payload: JSONObject) {
                                        mainHandler.post {
                                            if (activePeerRef.get() !== activePeer) return@post
                                            status.value = "Signaling room: $type"
                                            }
                                    }
                                    override fun onDisconnected(reason: String) {
                                        mainHandler.post {
                                            // Do not let an old peer clean up resources owned by a newer session.
                                            if (activePeerRef.get() !== activePeer) return@post
                                            transferActive.set(false)
                                            activeTransferId.set(null)
                                            downloadReceiver.pause()
                                            safBridge.cancelAllTransfers()
                                            targetActiveUploadIds.clear()
                                            cameraTransferActive.set(false)
                                            imageAssembler.abort()
                                            audioRelayActive.set(false)
                                            pttRelayActive.set(false)
                                            PttAudioQueue.clear()
                                            audioPlayback.close()
                                            safBridge.abortUpload()
                                            streamSender.value?.close()
                                            streamSender.value = null
                                            SessionForegroundService.stopInternetSession(context)
                                            activePeerRef.compareAndSet(activePeer, null)
                                            session.value = null
                                            dataChannelConnected.value = false
                                            peerId.value = null
                                            sas.value = null
                                            renderer.value?.close()
                                            renderer.value = null
                                            status.value = reason
                                        }
                                    }
                                    override fun onFailure(failure: Throwable) {
                                        mainHandler.post {
                                            // A delayed failure from an old peer must not tear down
                                            // resources or UI state belonging to a newer session.
                                            if (activePeerRef.get() !== activePeer) return@post
                                            transferActive.set(false)
                                            activeTransferId.set(null)
                                            downloadReceiver.pause()
                                            safBridge.cancelAllTransfers()
                                            targetActiveUploadIds.clear()
                                            cameraTransferActive.set(false)
                                            imageAssembler.abort()
                                            audioRelayActive.set(false)
                                            pttRelayActive.set(false)
                                            PttAudioQueue.clear()
                                            audioPlayback.close()
                                            safBridge.abortUpload()
                                            streamSender.value?.close()
                                            streamSender.value = null
                                            SessionForegroundService.stopInternetSession(context)
                                            activePeerRef.compareAndSet(activePeer, null)
                                            session.value = null
                                            dataChannelConnected.value = false
                                            peerId.value = null
                                            sas.value = null
                                            renderer.value?.close()
                                            renderer.value = null
                                            error.value = failure.message ?: "Secure connection failed"
                                        }
                                    }
                                }
                            )
                            activePeer = created
                            createdSession = created
                            activePeerRef.set(created)
                            session.value = created
                            status.value = "Starting visible foreground session service"
                            // The service owns the active peer while the app is backgrounded.
                            // Android cannot reconstruct a live WebRTC/Noise session after process death.
                            SessionForegroundService.startInternetSession(context, created)
                            status.value = "Connecting to Cloudflare rendezvous"
                            created.connect()
                        } catch (failure: Throwable) {
                            createdSession?.let { activePeerRef.compareAndSet(it, null) }
                            createdSession?.close()
                            if (session.value === createdSession) {
                                session.value = null
                                dataChannelConnected.value = false
                                renderer.value?.close()
                                renderer.value = null
                            }
                            error.value = failure.message ?: "Could not start SecureLink"
                        }
                    }
                ) { Text("Connect") }
                OutlinedButton(
                    enabled = session.value != null,
                    onClick = {
                        transferActive.set(false)
                        activeTransferId.set(null)
                        safBridge.cancelAllTransfers()
                        targetActiveUploadIds.clear()
                        cameraTransferActive.set(false)
                        audioRelayActive.set(false)
                        pttRelayActive.set(false)
                        PttAudioQueue.clear()
                        audioPlayback.close()
                        imageAssembler.abort()
                        safBridge.abortUpload()
                        streamSender.value?.close()
                        streamSender.value = null
                        val stoppingSession = session.value
                        activePeerRef.compareAndSet(stoppingSession, null)
                        SessionForegroundService.stopInternetSession(context)
                        stoppingSession?.revokeOwnerApprovedCommands()
                        stoppingSession?.close()
                        session.value = null
                        renderer.value?.close()
                        renderer.value = null
                        dataChannelConnected.value = false
                        peerId.value = null
                        sas.value = null
                        status.value = "Disconnected locally"
                    }
                ) { Text("Stop") }
                OutlinedButton(
                    enabled = session.value != null && peerId.value != null,
                    onClick = {
                        val requested = runCatching {
                            session.value?.requestNetworkRecovery() == true
                        }.getOrDefault(false)
                        status.value = if (requested) {
                            "Network recovery requested · waiting for ICE restart"
                        } else {
                            "Recovery request unavailable · wait briefly or start a fresh secure session"
                        }
                    }
                ) { Text("Repair connection") }
            }

            }
            if (Build.VERSION.SDK_INT < 33) {
                Text("SecureLink requires Android 13 (API 33) or later in this build.")
            }
            if (role.value == CloudflareSignalingClient.Role.TARGET && feature == SecureFeatureSection.SESSION_PERMISSIONS) {
                Text("Owner controls", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Screen capture, Notification Access, and Accessibility must each be enabled locally first. Every remote capability below is granted separately for this session.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching {
                        check(CameraFrameStore.latestFrame != null) {
                            "Start the rear or front camera locally on the target first"
                        }
                        session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.CAMERA))
                    }.onSuccess { status.value = "Camera snapshots allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant camera access" }
                }) { Text("Allow camera snapshots") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching { session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.SCREEN_VIEW)) }
                        .onSuccess { status.value = "Screen viewing allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant screen viewing" }
                }) { Text("Allow screen viewing") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching {
                        check(RemoteAccessibilityService.isActive()) { "Enable RKRemote in Android Accessibility settings first" }
                        session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.SCREEN_INPUT))
                    }.onSuccess { status.value = "Remote touch and text allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant remote input" }
                }) { Text("Allow remote touch and text") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching {
                        check(SecureCallLogSync.hasCallLogPermission(context)) {
                            "Enable Android Call history permission locally first"
                        }
                        session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.CALL_LOG))
                    }.onSuccess { status.value = "Call history sharing allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant call history access" }
                }) { Text("Allow call history report") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching {
                        check(SecureSmsSync.hasSmsPermission(context)) {
                            "Enable Android SMS permission locally first"
                        }
                        // SMS and call-log commands share the session gate; the SMS handler
                        // independently verifies READ_SMS before reading any message.
                        session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.CONTACTS))
                    }.onSuccess { status.value = "SMS report allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant SMS report access" }
                }) { Text("Allow SMS report") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching {
                        check(SecureNotificationSync.isNotificationAccessEnabled(context)) {
                            "Enable RKRemote Notification Access in Android settings first"
                        }
                        session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.NOTIFICATIONS))
                    }.onSuccess { status.value = "Notification sharing allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant notification access" }
                }) { Text("Allow notification sharing") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching {
                        check(MicrophoneChunkStore.isCapturing()) {
                            "Start microphone capture locally on the target first"
                        }
                        session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.MICROPHONE))
                    }.onSuccess { status.value = "Secure microphone streaming allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant microphone access" }
                }) { Text("Allow microphone streaming") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching {
                        check(PttAudioQueue.isEnabled()) { "Enable the PTT speaker locally on the target first" }
                        session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.PUSH_TO_TALK))
                    }.onSuccess { status.value = "Push-to-talk allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant push-to-talk" }
                }) { Text("Allow push-to-talk") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    runCatching { session.value?.grantOwnerApprovedCapabilities(setOf(RemoteCapability.FILES)) }
                        .onSuccess { status.value = "Secure file access allowed for this session" }
                        .onFailure { error.value = it.message ?: "Could not grant file access" }
                }) { Text("Allow secure file access") }
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    transferActive.set(false)
                    cameraTransferActive.set(false)
                    audioRelayActive.set(false)
                    pttRelayActive.set(false)
                    PttAudioQueue.clear()
                    audioPlayback.close()
                    imageAssembler.abort()
                    safBridge.abortUpload()
                    streamSender.value?.close()
                    streamSender.value = null
                    session.value?.revokeOwnerApprovedCommands()
                    status.value = "All remote command permissions revoked for this session"
                }) { Text("Revoke all remote permissions") }
                Text(
                    "Start Android screen sharing locally before allowing screen viewing. Remote commands never start screen, camera or microphone capture.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (role.value == CloudflareSignalingClient.Role.CONTROLLER) {
                Text("Controller", style = MaterialTheme.typography.titleSmall)
                if (feature == SecureFeatureSection.CAMERA) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = session.value != null && dataChannelConnected.value && !cameraTransferActive.get(), onClick = {
                        cameraStatus.value = if (sendInput("camera.start.request", JSONObject()))
                            "Live camera stream requested" else "Could not start live camera stream"
                    }) { Text("Start live camera") }
                    OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                        cameraTransferActive.set(false)
                        imageAssembler.abort()
                        sendInput("camera.stop", JSONObject())
                        cameraStatus.value = "Live camera stream stopped"
                    }) { Text("Stop camera stream") }
                }
                Text(cameraStatus.value, style = MaterialTheme.typography.bodySmall)
                cameraBitmap.value?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Secure owner-approved live camera frame",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }
                }
                if (feature == SecureFeatureSection.SCREEN) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = session.value != null && dataChannelConnected.value, onClick = {
                        streamStatus.value = if (sendInput("screen.stream.start", JSONObject()))
                            "Live-screen request sent" else "Could not request screen"
                    }) { Text("Request live screen") }
                    OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                        streamStatus.value = if (sendInput("screen.stream.stop", JSONObject()))
                            "Stop request sent" else "Could not stop stream"
                    }) { Text("Stop stream") }
                }
                OutlinedTextField(
                    value = remoteText.value,
                    onValueChange = { remoteText.value = it.take(4096) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Text for the focused field on target") },
                    minLines = 1,
                    maxLines = 3
                )
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value && remoteText.value.isNotEmpty(), onClick = {
                    streamStatus.value = if (sendInput(
                        "screen.input",
                        JSONObject().put("action", "text").put("text", remoteText.value)
                    )) "Text insertion requested" else "Could not send text"
                }) { Text("Insert text on target") }
                }
                if (feature == SecureFeatureSection.AUDIO) {
                Card(
                    modifier = Modifier.fillMaxWidth().pointerInput(session.value, dataChannelConnected.value, role.value) {
                        detectTapGestures(onPress = {
                            if (role.value == CloudflareSignalingClient.Role.CONTROLLER && dataChannelConnected.value) {
                                if (!MicrophoneChunkStore.isCapturing()) {
                                    pttStatus.value = "Start microphone capture locally on this phone first"
                                } else if (pttRelayActive.compareAndSet(false, true)) {
                                    val started = sendInput("ptt.start", JSONObject())
                                    if (!started) {
                                        pttRelayActive.set(false)
                                        pttStatus.value = "Target did not accept push-to-talk"
                                    } else {
                                        pttStatus.value = "Transmitting · release to stop"
                                        audioExecutor.execute {
                                            try {
                                                while (pttRelayActive.get() && MicrophoneChunkStore.isCapturing()) {
                                                    val chunk = MicrophoneChunkStore.take()
                                                    if (chunk == null) {
                                                        Thread.sleep(8)
                                                        continue
                                                    }
                                                    val sent = session.value?.sendMessage(
                                                        "ptt.audio.chunk",
                                                        JSONObject().put("data", Base64.getEncoder().encodeToString(chunk))
                                                    ) == true
                                                    if (!sent) {
                                                        mainHandler.post { pttStatus.value = "PTT connection lost" }
                                                        pttRelayActive.set(false)
                                                        break
                                                    }
                                                }
                                            } catch (_: InterruptedException) {
                                                Thread.currentThread().interrupt()
                                            }
                                        }
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            pttRelayActive.set(false)
                                            sendInput("ptt.stop", JSONObject())
                                            pttStatus.value = "Push-to-talk stopped"
                                        }
                                    }
                                }
                            }
                        })
                    }
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            if (pttRelayActive.get()) "HOLDING · TRANSMITTING" else "HOLD TO TALK",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(pttStatus.value, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                        audioStatus.value = if (sendInput("audio.start.request", JSONObject()))
                            "Microphone stream requested" else "Could not request microphone stream"
                    }) { Text("Listen to target microphone") }
                    OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                        audioStatus.value = if (sendInput("audio.stop", JSONObject()))
                            "Microphone stream stop requested" else "Could not stop microphone stream"
                        audioPlayback.close()
                    }) { Text("Stop listening") }
                }
                Text(audioStatus.value, style = MaterialTheme.typography.bodySmall)
                }
                if (feature == SecureFeatureSection.NOTIFICATIONS) {
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    notificationSummary.value = if (sendInput("notifications.sync.request", JSONObject()))
                        "Notification sync requested" else "Could not request notification sync"
                }) { Text("Fetch recent notifications") }
                Text(notificationSummary.value, style = MaterialTheme.typography.bodySmall)
                }
                if (feature == SecureFeatureSection.SMS_MESSAGES) {
                    if (role.value == CloudflareSignalingClient.Role.CONTROLLER) {
                        OutlinedButton(
                            enabled = session.value != null && dataChannelConnected.value,
                            onClick = {
                                smsStatus.value = if (sendInput("sms.sync.request", JSONObject()))
                                    "درخواست گزارش پیامک‌ها به دستگاه مقصد ارسال شد"
                                else "ارسال درخواست گزارش ناموفق بود"
                            }
                        ) { Text("دریافت گزارش پیامک‌های دستگاه مقصد") }
                    } else {
                        Text(
                            if (SecureSmsSync.hasSmsPermission(context))
                                "مجوز پیامک فعال است. گزارش فقط با فشردن دکمه زیر روی همین دستگاه خوانده می‌شود."
                            else "برای مشاهده گزارش، ابتدا مجوز «پیامک‌ها» را در بخش مجوزهای اندروید فعال کن.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            enabled = SecureSmsSync.hasSmsPermission(context),
                            onClick = {
                                runCatching { SecureSmsSync.readLatest(context) }
                                    .onSuccess { result ->
                                        smsItems.value = buildList {
                                            for (index in 0 until result.records.length()) {
                                                result.records.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
                                            }
                                        }
                                        smsStatus.value = "${result.records.length()} پیامک از ${result.totalAvailable} پیامک"
                                    }
                                    .onFailure { smsStatus.value = it.message ?: "خواندن پیامک‌ها ناموفق بود" }
                            }
                        ) { Text("نمایش پیامک‌های همین گوشی") }
                    }
                    Text(smsStatus.value, style = MaterialTheme.typography.bodySmall)
                    smsItems.value.forEach { item ->
                        val typeLabel = when (item.optInt("type")) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "دریافتی"
                            Telephony.Sms.MESSAGE_TYPE_SENT -> "ارسالی"
                            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "پیش‌نویس"
                            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "در صف ارسال"
                            Telephony.Sms.MESSAGE_TYPE_FAILED -> "ناموفق"
                            Telephony.Sms.MESSAGE_TYPE_QUEUED -> "در انتظار ارسال"
                            else -> "نامشخص"
                        }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.optString("address").ifBlank { "شماره ناشناس" }, fontWeight = FontWeight.SemiBold)
                                Text(typeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(item.optString("body"), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                        .format(Date(item.optLong("timestamp"))),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (role.value == CloudflareSignalingClient.Role.CONTROLLER) {
                        Text(
                            "گزارش فقط پس از فعال‌سازی مجوز SMS و تأیید مالک دستگاه در همان نشست قابل دریافت است. فقط پیامک‌های متنی SMS خوانده می‌شوند؛ MMS شامل این گزارش نیست.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (feature == SecureFeatureSection.CALL_LOGS) {
                    if (role.value == CloudflareSignalingClient.Role.CONTROLLER) {
                        OutlinedButton(
                            enabled = session.value != null && dataChannelConnected.value,
                            onClick = {
                                callLogStatus.value = if (sendInput("calllog.sync.request", JSONObject()))
                                    "درخواست گزارش تماس به دستگاه مقصد ارسال شد"
                                else "ارسال درخواست گزارش ناموفق بود"
                            }
                        ) { Text("دریافت گزارش تماس‌های دستگاه مقصد") }
                    } else {
                        Text(
                            if (SecureCallLogSync.hasCallLogPermission(context))
                                "مجوز تاریخچه تماس فعال است. گزارش فقط با فشردن دکمه زیر روی همین دستگاه خوانده می‌شود."
                            else "برای مشاهده گزارش، ابتدا مجوز «تاریخچه تماس‌ها» را در بخش مجوزهای اندروید فعال کن.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            enabled = SecureCallLogSync.hasCallLogPermission(context),
                            onClick = {
                                runCatching { SecureCallLogSync.readLatest(context) }
                                    .onSuccess { result ->
                                        callLogItems.value = buildList {
                                            for (index in 0 until result.records.length()) {
                                                result.records.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
                                            }
                                        }
                                        callLogStatus.value = "${result.records.length()} تماس از ${result.totalAvailable} تماس"
                                    }
                                    .onFailure { callLogStatus.value = it.message ?: "خواندن تاریخچه تماس ناموفق بود" }
                            }
                        ) { Text("نمایش گزارش تماس‌های همین گوشی") }
                    }
                    Text(callLogStatus.value, style = MaterialTheme.typography.bodySmall)
                    callLogItems.value.forEach { item ->
                        val seconds = item.optLong("durationSeconds").coerceAtLeast(0L)
                        val duration = "%d:%02d".format(seconds / 60, seconds % 60)
                        val typeLabel = when (item.optInt("type")) {
                            CallLog.Calls.INCOMING_TYPE -> "ورودی"
                            CallLog.Calls.OUTGOING_TYPE -> "خروجی"
                            CallLog.Calls.MISSED_TYPE -> "ازدست‌رفته"
                            CallLog.Calls.REJECTED_TYPE -> "ردشده"
                            CallLog.Calls.BLOCKED_TYPE -> "مسدودشده"
                            CallLog.Calls.VOICEMAIL_TYPE -> "پیام‌گیر صوتی"
                            else -> "نامشخص"
                        }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    item.optString("name").ifBlank { item.optString("number").ifBlank { "شماره ناشناس" } },
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (item.optString("name").isNotBlank() && item.optString("number").isNotBlank()) {
                                    Text(item.optString("number"), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "$typeLabel · مدت: $duration · " +
                                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                            .format(Date(item.optLong("timestamp"))),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    if (role.value == CloudflareSignalingClient.Role.CONTROLLER) {
                        Text(
                            "گزارش فقط پس از فعال‌سازی مجوز اندروید و تأیید جداگانه «گزارش تاریخچه تماس‌ها» روی دستگاه مقصد قابل دریافت است.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (feature == SecureFeatureSection.FILES) {
                OutlinedButton(enabled = session.value != null && dataChannelConnected.value, onClick = {
                    fileStatus.value = if (sendInput("file.list.request", JSONObject()))
                        "Folder listing requested" else "Could not request folder listing"
                }) { Text("Browse target folder") }
                OutlinedButton(
                    enabled = session.value != null && dataChannelConnected.value &&
                        fileParentId.value.isNotBlank() && fileParentId.value != fileRootId.value,
                    onClick = {
                        val sent = session.value?.sendMessage(
                            "file.list.request",
                            JSONObject().put("requestId", UUID.randomUUID().toString()).put("parentId", fileRootId.value)
                        ) == true
                        if (sent) fileStatus.value = "Returned to selected root folder"
                    }
                ) { Text("Selected root") }
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
                                    JSONObject().put("requestId", UUID.randomUUID().toString())
                                        .put("parentId", item.optString("id"))
                                ) == true
                                if (sent) fileStatus.value = "Opening " + item.optString("name")
                            }) { Text("Open") }
                        } else {
                            OutlinedButton(enabled = !transferActive.get(), onClick = {
                                val transferId = UUID.randomUUID().toString()
                                if (!transferActive.compareAndSet(false, true)) {
                                    fileDownloadStatus.value = "Another file transfer is active"
                                } else {
                                    activeTransferId.set(transferId)
                                    pendingDownloadId.value = transferId
                                    pendingDownloadDocumentId.value = item.optString("id")
                                    downloadResumePrefs.edit().putString("document_id", item.optString("id")).commit()
                                    val sent = session.value?.sendMessage(
                                        "file.download.request",
                                        JSONObject().put("requestId", transferId)
                                            .put("documentId", item.optString("id"))
                                            .put("offset", 0L)
                                    ) == true
                                    if (!sent) {
                                        transferActive.set(false)
                                        activeTransferId.compareAndSet(transferId, null)
                                        downloadResumePrefs.edit().remove("document_id").commit()
                                        pendingDownloadId.value = null
                                        pendingDownloadDocumentId.value = null
                                    }
                                    fileDownloadStatus.value = if (sent) "Download requested: " + item.optString("name")
                                        else "Could not request download"
                                }
                            }) { Text("Download") }
                        }
                    }
                }
                Text(fileDownloadStatus.value, style = MaterialTheme.typography.bodySmall)
                if (downloadReceiver.hasPendingTransfer() &&
                    pendingDownloadId.value == downloadReceiver.pendingTransferId() &&
                    !transferActive.get()) {
                    OutlinedButton(
                        enabled = session.value != null && dataChannelConnected.value &&
                            !pendingDownloadDocumentId.value.isNullOrBlank(),
                        onClick = {
                            val transferId = pendingDownloadId.value ?: return@OutlinedButton
                            val documentId = pendingDownloadDocumentId.value ?: return@OutlinedButton
                            if (transferActive.compareAndSet(false, true)) {
                                activeTransferId.set(transferId)
                                val offset = downloadReceiver.pendingOffset()
                                val sent = session.value?.sendMessage(
                                    "file.download.request",
                                    JSONObject().put("requestId", transferId)
                                        .put("documentId", documentId)
                                        .put("offset", offset)
                                ) == true
                                if (!sent) {
                                    transferActive.set(false)
                                    activeTransferId.compareAndSet(transferId, null)
                                }
                                fileDownloadStatus.value = if (sent)
                                    "Resuming download from $offset bytes" else "Could not resume download"
                            }
                        }
                    ) { Text("Resume previous download (${downloadReceiver.pendingOffset()} bytes)") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = session.value != null && dataChannelConnected.value &&
                            outgoingUploadIds.size < SecureSafTransferBridge.MAX_ACTIVE_UPLOADS,
                        onClick = { uploadPicker.launch(arrayOf("*/*")) }
                    ) { Text("Upload files (up to 3)") }
                    OutlinedButton(
                        enabled = session.value != null && dataChannelConnected.value &&
                            (outgoingUploadIds.isNotEmpty() || activeTransferId.get() != null),
                        onClick = {
                            outgoingUploadIds.toList().forEach { transferId ->
                                sendInput(
                                    "file.transfer.cancel",
                                    JSONObject().put("transferId", transferId)
                                )
                                outgoingUploadIds.remove(transferId)
                            }
                            val downloadId = activeTransferId.get()
                            if (downloadId != null) {
                                sendInput(
                                    "file.transfer.cancel",
                                    JSONObject().put("transferId", downloadId)
                                )
                                transferActive.set(false)
                                activeTransferId.compareAndSet(downloadId, null)
                                downloadReceiver.abort()
                                downloadResumePrefs.edit().remove("document_id").commit()
                                pendingDownloadId.value = null
                                pendingDownloadDocumentId.value = null
                            }
                            fileDownloadStatus.value = "Active or pending download cancelled"
                            fileUploadStatus.value = "Outgoing uploads cancelled"
                        }
                    ) { Text("Cancel transfers") }
                }
                Text(fileUploadStatus.value, style = MaterialTheme.typography.bodySmall)
                }
                if (feature == SecureFeatureSection.SCREEN) {
            val screenModifier = Modifier.fillMaxWidth().height(240.dp)
                .pointerInput(session.value) {
                    val viewWidth = size.width.coerceAtLeast(1)
                    val viewHeight = size.height.coerceAtLeast(1)
                    detectTapGestures { point ->
                        if (role.value == CloudflareSignalingClient.Role.CONTROLLER) {
                            val x = (point.x / viewWidth).coerceIn(0f, 1f)
                            val y = (point.y / viewHeight).coerceIn(0f, 1f)
                            sendInput(
                                "screen.input",
                                JSONObject().put("action", "gesture")
                                    .put("startX", x).put("startY", y)
                                    .put("endX", x).put("endY", y)
                                    .put("durationMs", 80)
                            )
                        }
                    }
                }
                .pointerInput(session.value) {
                    val viewWidth = size.width.coerceAtLeast(1)
                    val viewHeight = size.height.coerceAtLeast(1)
                    var start = Offset.Zero
                    var end = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start = it; end = it },
                        onDrag = { change, _ -> end = change.position; change.consume() },
                        onDragEnd = {
                            if (role.value == CloudflareSignalingClient.Role.CONTROLLER) {
                                val x1 = (start.x / viewWidth).coerceIn(0f, 1f)
                                val y1 = (start.y / viewHeight).coerceIn(0f, 1f)
                                val x2 = (end.x / viewWidth).coerceIn(0f, 1f)
                                val y2 = (end.y / viewHeight).coerceIn(0f, 1f)
                                if (kotlin.math.abs(x2 - x1) + kotlin.math.abs(y2 - y1) > 0.005f) {
                                    sendInput(
                                        "screen.input",
                                        JSONObject().put("action", "gesture")
                                            .put("startX", x1).put("startY", y1)
                                            .put("endX", x2).put("endY", y2)
                                            .put("durationMs", 350)
                                    )
                                }
                            }
                        }
                    )
                }

            AndroidView(
                modifier = screenModifier,
                factory = { viewContext ->
                    SurfaceView(viewContext).also { surface ->
                        renderer.value = SecureScreenVideoRenderer(surface) { message ->
                            mainHandler.post { streamStatus.value = message }
                        }
                    }
                }
            )
            Text(streamStatus.value, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (role.value == CloudflareSignalingClient.Role.TARGET &&
                feature != SecureFeatureSection.CONNECTION &&
                feature != SecureFeatureSection.SESSION_PERMISSIONS
            ) {
                Text(
                    "این دستگاه در نقش Target است. برای واگذاری دسترسی همین نشست، وارد بخش «مجوزهای نشست» شو. ضبط صفحه، دوربین و میکروفن همچنان باید روی خود دستگاه و با تأیید صاحب آن شروع شوند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (role.value == CloudflareSignalingClient.Role.CONTROLLER &&
                feature == SecureFeatureSection.SESSION_PERMISSIONS
            ) {
                Text("این صفحه برای تأیید مجوزها روی دستگاه Target است؛ کنترلر فقط می‌تواند درخواست‌های مجاز را ارسال کند.")
            }
            peerId.value?.let { Text("Peer ID: $it", style = MaterialTheme.typography.bodySmall) }
            sas.value?.let {
                Text("Verification code: " + it.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineSmall)
                Text("Compare the exact code on both phones and verify the peer ID before approving.")
                Button(onClick = {
                    runCatching { session.value?.approvePeer(sas.value!!, peerId.value!!) }
                        .onSuccess { status.value = "Local approval sent · waiting for other device" }
                        .onFailure { error.value = it.message ?: "Approval failed" }
                }) { Text("I compared both devices · approve") }
            }
            Text("Status: " + status.value, style = MaterialTheme.typography.bodyMedium)
            error.value?.let { Text("Error: " + it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(2.dp))
            Text(
                "Setup: deploy cloudflare/worker, enter its WSS URL, share the same fresh room ID with the other device, connect the target first, then controller. TURN credentials must come from a service you control. Cloudflare does not relay media or files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
