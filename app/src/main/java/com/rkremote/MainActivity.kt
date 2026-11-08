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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import kotlinx.coroutines.launch
import com.rkremote.remote.ConnectionHubPanel
import com.rkremote.remote.SecureFeatureSection

private enum class AppPage(val title: String) {
    HOME("خانه"),
    CONNECTIONS("اتصال و روش‌ها"),
    SCREEN("نمایش و کنترل گوشی"),
    CAMERA("دوربین"),
    AUDIO("میکروفن و واکی‌تاکی"),
    REMOTE_FILES("مدیریت فایل‌ها"),
    REMOTE_NOTIFICATIONS("همگام‌سازی اعلان‌ها"),
    CALL_HISTORY("گزارش تاریخچه تماس‌ها"),
    SMS_HISTORY("گزارش پیامک‌ها"),
    SESSION_PERMISSIONS("مجوزهای نشست"),
    PERMISSIONS("مجوزهای اندروید"),
    NOTIFICATIONS("تاریخچه اعلان‌ها"),
    FILES("پوشه مجاز فایل‌ها")
}

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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentPage by remember { mutableStateOf(AppPage.HOME) }
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

    val permissionItems = remember {
        buildList {
            add(PermissionItem(
                "دوربین",
                "فقط هنگام شروع آگاهانه نشست دوربین توسط صاحب گوشی لازم است.",
                listOf(Manifest.permission.CAMERA)
            ))
            add(PermissionItem(
                "پیامک‌ها",
                "برای نمایش پیامک‌های همین گوشی و ارسال گزارش فقط پس از تأیید جداگانه مالک دستگاه لازم است. Android ممکن است این مجوز را به‌دلیل محدودیت‌های امنیتی یا روش نصب برنامه مسدود کند.",
                listOf(Manifest.permission.READ_SMS)
            ))
            add(PermissionItem(
                "تاریخچه تماس‌ها",
                "برای نمایش گزارش تماس‌ها روی همین گوشی و ارسال گزارش فقط پس از تأیید جداگانه همان نشست لازم است. امکان اعطای این مجوز ممکن است با محدودیت‌های نسخه اندروید یا روش نصب برنامه روبه‌رو شود.",
                listOf(Manifest.permission.READ_CALL_LOG)
            ))
            add(PermissionItem(
                "میکروفن",
                "فقط برای نشست صوتی یا واکی‌تاکی که خودت شروع می‌کنی لازم است.",
                listOf(Manifest.permission.RECORD_AUDIO)
            ))
            if (Build.VERSION.SDK_INT >= 33) {
                add(PermissionItem(
                    "اعلان‌ها",
                    "مجوز اعلان برنامه؛ خواندن اعلان برنامه‌های دیگر به فعال‌سازی جداگانه Notification Access نیاز دارد.",
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                ))
            }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val observedRefresh = refreshKey
    val notificationAccess = hasNotificationAccess(context)
    val notificationCount = EncryptedNotificationStore.readAll(context).length()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "RKRemote",
                    modifier = Modifier.padding(start = 22.dp, top = 24.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "بخش موردنظر را انتخاب کن",
                    modifier = Modifier.padding(start = 22.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppPage.entries.forEach { page ->
                    NavigationDrawerItem(
                        label = { Text(page.title) },
                        selected = currentPage == page,
                        onClick = {
                            currentPage = page
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "قابلیت‌های حساس فقط با تأیید صاحب دستگاه فعال می‌شوند.",
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { scope.launch { drawerState.open() } }) {
                        Text("☰")
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("RKRemote", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(currentPage.title, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Keep the connection hub composed while visiting other pages.
            // This preserves an active secure session when the drawer is used.
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().offset(x = if (currentPage == AppPage.HOME) 0.dp else 2000.dp)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("مدیریت دستگاه", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("تمام روش‌های اتصال در گزینه «اتصال و روش‌ها» در منوی سه‌خط جمع شده‌اند.")
                                }
                            }
                        }
                        item {
                            Card {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("وضعیت اعلان‌ها", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (notificationAccess) "دسترسی اعلان فعال است · $notificationCount رویداد ذخیره شده"
                                        else "دسترسی اعلان هنوز فعال نشده است",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxSize().offset(
                    x = if (currentPage in setOf(
                        AppPage.CONNECTIONS, AppPage.SCREEN, AppPage.CAMERA, AppPage.AUDIO,
                        AppPage.REMOTE_FILES, AppPage.REMOTE_NOTIFICATIONS, AppPage.CALL_HISTORY, AppPage.SMS_HISTORY, AppPage.SESSION_PERMISSIONS
                    )) 0.dp else 2000.dp
                )) {
                    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            ConnectionHubPanel(
                                feature = when (currentPage) {
                                    AppPage.SCREEN -> SecureFeatureSection.SCREEN
                                    AppPage.CAMERA -> SecureFeatureSection.CAMERA
                                    AppPage.AUDIO -> SecureFeatureSection.AUDIO
                                    AppPage.REMOTE_FILES -> SecureFeatureSection.FILES
                                    AppPage.REMOTE_NOTIFICATIONS -> SecureFeatureSection.NOTIFICATIONS
                                    AppPage.CALL_HISTORY -> SecureFeatureSection.CALL_LOGS
                                    AppPage.SMS_HISTORY -> SecureFeatureSection.SMS_MESSAGES
                                    AppPage.SESSION_PERMISSIONS -> SecureFeatureSection.SESSION_PERMISSIONS
                                    else -> SecureFeatureSection.CONNECTION
                                }
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxSize().offset(x = if (currentPage == AppPage.PERMISSIONS) 0.dp else 2000.dp)) {
                    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("مجوزهای اندروید", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
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
                                            if (granted) "فعال" else "فعال نشده",
                                            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(item.description, style = MaterialTheme.typography.bodySmall)
                                    if (!granted) {
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(onClick = { permissionLauncher.launch(item.permissions.toTypedArray()) }) {
                                            Text("درخواست مجوز")
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { refreshKey++ }) {
                                Text("به‌روزرسانی وضعیت مجوزها")
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxSize().offset(x = if (currentPage == AppPage.NOTIFICATIONS) 0.dp else 2000.dp)) {
                    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("تاریخچه اعلان‌ها", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (notificationAccess) "دسترسی اعلان فعال است · $notificationCount رویداد به‌صورت محلی ذخیره شده"
                                        else "دسترسی اعلان فعال نیست · محتوای اعلان‌ها دریافت نمی‌شود",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Button(onClick = {
                                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    }) {
                                        Text("باز کردن تنظیمات دسترسی اعلان")
                                    }
                                    if (!notificationAccess) {
                                        Text("فعال‌سازی این دسترسی باید مستقیماً توسط صاحب دستگاه در تنظیمات اندروید انجام شود.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("محتوا ابتدا روی دستگاه رمزنگاری و ذخیره می‌شود. ارسال از راه دور فقط روی اتصال رمزنگاری‌شده و با مجوز همان نشست انجام می‌شود.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxSize().offset(x = if (currentPage == AppPage.FILES) 0.dp else 2000.dp)) {
                    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("دسترسی به فایل‌ها", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(
                                        selectedFolder ?: "هنوز پوشه‌ای انتخاب نشده است. پوشه‌ای را انتخاب کن تا دسترسی پایدار به همان پوشه داده شود.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedButton(onClick = { folderPicker.launch(null) }) {
                                        Text("انتخاب پوشه")
                                    }
                                    Text("دسترسی فایل‌ها محدود به پوشه‌ای است که خودت از پنجره رسمی Android انتخاب می‌کنی.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
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
