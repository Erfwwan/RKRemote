package com.rkremote.remote

import android.os.Build
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

@Composable
fun SecureInternetPanel() {
    val context = LocalContext.current
    val url = remember { mutableStateOf("") }
    val room = remember { mutableStateOf("") }
    val role = remember { mutableStateOf(CloudflareSignalingClient.Role.TARGET) }
    val status = remember { mutableStateOf("Not connected") }
    val peerId = remember { mutableStateOf<String?>(null) }
    val sas = remember { mutableStateOf<String?>(null) }
    val error = remember { mutableStateOf<String?>(null) }
    val session = remember { mutableStateOf<SecureInternetPeer?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Secure Internet P2P", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Noise XX authentication + WebRTC ICE/DTLS data channel. Both owners must compare and approve the same code. This is a transport test panel; it does not yet route screen, files, notifications, camera or microphone controls.",
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
                            val created = SecureInternetPeer(
                                context = context,
                                baseWssUrl = url.value,
                                roomId = room.value,
                                role = role.value,
                                handshake = handshake,
                                identityStore = identityStore,
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
                                        mainHandler.post { status.value = "WebRTC DataChannel connected (DTLS)" }
                                    }
                                    override fun onData(payload: ByteArray) {
                                        mainHandler.post { status.value = "Secure data received · ${payload.size} bytes" }
                                    }
                                    override fun onRoomEvent(type: String, payload: JSONObject) {
                                        mainHandler.post { status.value = "Signaling room: $type" }
                                    }
                                    override fun onDisconnected(reason: String) {
                                        mainHandler.post { status.value = reason }
                                    }
                                    override fun onFailure(failure: Throwable) {
                                        mainHandler.post { error.value = failure.message ?: "Secure connection failed" }
                                    }
                                }
                            )
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
                        session.value?.close()
                        session.value = null
                        peerId.value = null
                        sas.value = null
                        status.value = "Disconnected locally"
                    }
                ) { Text("Stop") }
            }
            if (Build.VERSION.SDK_INT < 33) {
                Text("SecureLink requires Android 13 (API 33) or later in this build.")
            }
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
                "Setup: deploy cloudflare/worker, enter its workers.dev WSS URL, generate one room ID on the controller, enter that same ID on the target, connect target first, then controller. For difficult NATs, add your own TURN service configuration in code; Cloudflare Worker is signaling only and does not relay media.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
