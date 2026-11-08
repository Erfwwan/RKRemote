package com.rkremote.remote

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Local pairing-code UX prototype. It intentionally does not claim that a
 * remote peer has connected; network pairing is a separate milestone.
 */
@Composable
fun PairingPanel() {
    val coordinator = remember { PairingCoordinator() }
    val challenge = remember { mutableStateOf<PairingCoordinator.Challenge?>(null) }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Device pairing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Create a one-time code on this device. Share it only with a controller you trust.",
                style = MaterialTheme.typography.bodySmall
            )
            challenge.value?.let {
                Text(
                    it.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Expires in five minutes. No peer is connected by generating this code.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { challenge.value = coordinator.createChallenge() }) {
                    Text("Generate pairing code")
                }
                OutlinedButton(onClick = {
                    coordinator.revokeChallenge()
                    challenge.value = null
                }) {
                    Text("Cancel")
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Pairing code display is local-only in this milestone. Do not treat it as a completed secure pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
