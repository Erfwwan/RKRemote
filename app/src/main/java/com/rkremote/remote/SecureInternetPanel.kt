package com.rkremote.remote

import android.os.Build
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
                            val targetRouter = if (role.value == CloudflareSignalingClient.Role.TARGET) {
                                SecureRemoteCommandRouter().apply {
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
