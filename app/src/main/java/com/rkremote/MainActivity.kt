package com.rkremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionDashboard()
                }
            }
        }
    }
}

private data class PermissionItem(
    val title: String,
    val description: String,
    val permissions: List<String>,
    val optional: Boolean = false
)

@Composable
private fun PermissionDashboard() {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshKey++
    }

    val items = listOf(
        PermissionItem(
            title = "Camera",
            description = "Only used in a clearly indicated, user-started camera session.",
            permissions = listOf(Manifest.permission.CAMERA)
        ),
        PermissionItem(
            title = "Microphone",
            description = "Only used in a clearly indicated, user-started audio session.",
            permissions = listOf(Manifest.permission.RECORD_AUDIO)
        ),
        PermissionItem(
            title = "Location",
            description = "Foreground location only. Background tracking is not enabled.",
            permissions = listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ),
        PermissionItem(
            title = "Notifications",
            description = "Android 13+ notification permission. Notification forwarding requires a separate, explicit Notification Access setting.",
            permissions = if (Build.VERSION.SDK_INT >= 33) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            },
            optional = Build.VERSION.SDK_INT < 33
        )
    )

    // Read current grants during composition; refreshKey is incremented after a permission result.
    @Suppress("UNUSED_VARIABLE")
    val refresh = refreshKey

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "RK Remote",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Personal device management · transparent sessions",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Local setup", fontWeight = FontWeight.SemiBold)
                    Text(
                        "This starter build does not connect to another phone yet. Grant only the permissions you intend to use. Camera, microphone and location sessions will require visible user action.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        items(items) { item ->
            val granted = item.permissions.isEmpty() ||
                item.permissions.all { permission ->
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                }

            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when {
                                item.optional -> "Not required"
                                granted -> "Granted"
                                else -> "Not granted"
                            },
                            color = if (granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(item.description, style = MaterialTheme.typography.bodySmall)
                    if (!granted && !item.optional) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { permissionLauncher.launch(item.permissions.toTypedArray()) }
                        ) {
                            Text("Grant permission")
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { refreshKey++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh permission status")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Pairing, encrypted remote transport, screen sharing and file transfer are planned milestones and are not implemented in this starter version.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
