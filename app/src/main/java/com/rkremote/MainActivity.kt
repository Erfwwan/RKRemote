package com.rkremote

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rkremote.remote.PairingPanel
import com.rkremote.remote.LanRemotePanel
import com.rkremote.remote.SecureInternetPanel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LocalSetupScreen()
                }
            }
        }
    }
}

private data class PermissionItem(
    val title: String,
    val description: String,
    val permissions: List<String>
)

@Composable
private fun LocalSetupScreen() {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedFolder by remember { mutableStateOf(readSavedFolder(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                context.getSharedPreferences("rkremote_setup", Context.MODE_PRIVATE)
                    .edit().putString("selected_tree_uri", uri.toString()).apply()
                selectedFolder = uri.toString()
            }
        }
        refreshKey++
    }

    val permissionItems = buildList {
        add(PermissionItem(
            "Camera",
            "Required only when the owner starts a visible camera session.",
            listOf(Manifest.permission.CAMERA)
        ))
        add(PermissionItem(
            "Microphone",
            "Required only during an explicitly started audio or walkie-talkie session.",
            listOf(Manifest.permission.RECORD_AUDIO)
        ))
        add(PermissionItem(
            "Contacts",
            "Allows reading contacts on this device after permission is granted.",
            listOf(Manifest.permission.READ_CONTACTS)
        ))
        add(PermissionItem(
            "Call history",
            "Allows reading call-log records where Android and device policy permit it.",
            listOf(Manifest.permission.READ_CALL_LOG)
        ))
        add(PermissionItem(
            "Location",
            "Foreground location permission. Background location is not requested in this build.",
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        ))
        if (Build.VERSION.SDK_INT >= 33) {
            add(PermissionItem(
                "Photos and videos",
                "Requests access to media. Android may offer selected-media access.",
                buildList {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                    if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            ))
            add(PermissionItem(
                "Notifications",
                "Runtime notification permission. Reading other apps' notifications requires the separate Notification Access switch below.",
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            ))
        } else {
            add(PermissionItem(
                "Photos and videos",
                "Legacy shared-storage read permission. Folder access is separately granted below.",
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            ))
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val observedRefresh = refreshKey
    val notificationAccess = hasNotificationAccess(context)
    val notificationCount = EncryptedNotificationStore.readAll(context).length()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("RKRemote", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Local device setup · owner-approved access",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("RKRemote prototype · Sections 1–8", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Local permissions, LAN control, screen sharing and remote input, file transfer, camera/microphone, push-to-talk, and owner-approved notification history prototypes. Includes bounded connection retry and health diagnostics. Use only on trusted private Wi-Fi; security and physical-device validation remain.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item { PairingPanel() }
        item { SecureInternetPanel() }
        item { LanRemotePanel() }
        item {
            Text("Android permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(permissionItems) { item ->
            val granted = item.permissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (granted) "Granted" else "Not granted",
                            color = if (granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.description, style = MaterialTheme.typography.bodySmall)
                    if (!granted) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { permissionLauncher.launch(item.permissions.toTypedArray()) }) {
                            Text("Request")
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Notification history", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (notificationAccess) "Notification Access is enabled · $notificationCount local events stored"
                        else "Notification Access is not enabled · no notification content is being captured",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) {
                        Text("Open Notification Access settings")
                    }
                    if (!notificationAccess) {
                        Text("Enable Notification Access in Android settings. Android requires this explicit owner action.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("File and gallery folder", fontWeight = FontWeight.SemiBold)
                    Text(
                        selectedFolder ?: "No folder selected. Choose a folder to grant persistent access to its contents.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { folderPicker.launch(null) }) {
                        Text("Choose folder")
                    }
                }
            }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { refreshKey++ }) {
                Text("Refresh access status")
            }
            Text(
                "Notification content is encrypted locally with an Android Keystore AES-GCM key. Remote delivery still requires a securely paired encrypted connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun readSavedFolder(context: Context): String? =
    context.getSharedPreferences("rkremote_setup", Context.MODE_PRIVATE)
        .getString("selected_tree_uri", null)

private fun hasNotificationAccess(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    val component = ComponentName(context, NotificationCaptureService::class.java)
    return enabled.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
}
