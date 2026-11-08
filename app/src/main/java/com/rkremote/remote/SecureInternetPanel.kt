package com.rkremote.remote

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose { renderer.value?.close(); renderer.value = null }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Secure Internet P2P", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Noise XX authentication + WebRTC ICE/DTLS data channel. Both owners must compare and approve the same code. This panel exchanges versioned, replay-checked messages; legacy feature controls are not yet migrated.",
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
                OutlinedButton(onClick = { role.value = CloudflareSignalingClient.Role.TARGET }) {
                    Text(if (role.value == CloudflareSignalingClient.Role.TARGET) "✓ Target" else "Target")
                }
                OutlinedButton(onClick = { role.value = CloudflareSignalingClient.Role.CONTROLLER }) {
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
                            var streamSender: SecureScreenStreamSender? = null
                            val targetRouter = if (role.value == CloudflareSignalingClient.Role.TARGET) {
                                SecureRemoteCommandRouter().apply {
                                    register("screen.stream.start", RemoteCapability.SCREEN_VIEW) {
                                        check(ScreenVideoStore.hasConfig()) {
                                            "Start screen sharing locally on the target first"
                                        }
                                        streamSender?.close()
                                        val sender = SecureScreenStreamSender(
                                            sendMessage = { type, payload ->
                                                activePeer?.sendMessage(type, payload) == true
                                            },
                                            onState = { message ->
                                                mainHandler.post { streamStatus.value = message }
                                            }
                                        )
                                        streamSender = sender
                                        check(sender.start()) { "Screen stream is already running" }
                                        "screen.video.config"
                                    }
                                    register("screen.stream.stop", RemoteCapability.SCREEN_VIEW) {
                                        streamSender?.close()
                                        streamSender = null
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
                                        mainHandler.post {
                                            dataChannelConnected.value = false
                                            status.value = reason
                                        }
                                    }
                                    override fun onFailure(failure: Throwable) {
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
            }
            Text(streamStatus.value, style = MaterialTheme.typography.bodySmall)
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(240.dp),
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
