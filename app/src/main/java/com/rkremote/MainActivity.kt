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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Brush
import com.rkremote.ui.theme.RKRemoteTheme
import com.rkremote.remote.PermissionRequestPolicy
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
    CONNECTIONS("اتصال Cloudflare"),
    SCREEN("نمایش و کنترل گوشی"),
    CAMERA("دوربین"),
    AUDIO("میکروفن و واکی‌تاکی"),
    REMOTE_FILES("مدیریت فایل‌ها"),
    REMOTE_NOTIFICATIONS("همگام‌سازی اعلان‌ها"),
    CALL_HISTORY("گزارش تاریخچه تماس‌ها"),
    SMS_HISTORY("گزارش پیامک‌ها"),
    LOCATION("موقعیت مکانی زنده"),
    SESSION_PERMISSIONS("مجوزهای نشست"),
    PERMISSIONS("مجوزهای اندروید"),
    NOTIFICATIONS("تاریخچه اعلان‌ها"),
    FILES("پوشه مجاز فایل‌ها")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPairInvite(intent?.data)
        setContent {
            var darkMode by remember {
                mutableStateOf(
                    getSharedPreferences("rkremote_ui", Context.MODE_PRIVATE)
                        .getBoolean("dark_mode", true)
                )
            }
            RKRemoteTheme(darkTheme = darkMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LocalSetupScreen(
                        darkMode = darkMode,
                        onToggleTheme = {
                            darkMode = !darkMode
                            getSharedPreferences("rkremote_ui", Context.MODE_PRIVATE)
                                .edit().putBoolean("dark_mode", darkMode).apply()
                        }
                    )
                }
            }
        }
    }

    private fun applyPairInvite(uri: Uri?) {
        if (uri?.scheme != "rkremote" || uri.host != "pair") return
        val worker = uri.getQueryParameter("worker")?.trim().orEmpty()
        val room = uri.getQueryParameter("room")?.trim().orEmpty()
        if (!worker.startsWith("wss://") || !room.matches(Regex("[a-f0-9]{64}"))) return
        runCatching {
            val parsed = java.net.URI(worker)
            require(!parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
                parsed.rawQuery == null && parsed.rawFragment == null &&
                (parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/"))
            getSharedPreferences("rkremote_cloudflare_setup", Context.MODE_PRIVATE)
                .edit()
                .putString("worker_url", worker)
                .putString("room_id", room)
                .putString("role", "controller")
                .putBoolean("auto_connect", true)
                .apply()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyPairInvite(intent.data)
        recreate()
    }
}

private data class PermissionItem(
    val key: String,
    val title: String,
    val description: String,
    val permissions: List<String>
)

@Composable
private fun LocalSetupScreen(darkMode: Boolean, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentPage by remember {
        mutableStateOf(
            if (context.getSharedPreferences("rkremote_cloudflare_setup", Context.MODE_PRIVATE)
                    .getBoolean("auto_connect", false)) AppPage.CONNECTIONS else AppPage.HOME
        )
    }
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
                "camera",
                "دوربین",
                "فقط هنگام شروع آگاهانه نشست دوربین توسط صاحب گوشی لازم است.",
                listOf(Manifest.permission.CAMERA)
            ))
            add(PermissionItem(
                "contacts",
                "مخاطبان",
                "برای نمایش دفترچه مخاطبین؛ دسترسی و ارسال فهرست فقط با تأیید مستقل صاحب گوشی انجام می‌شود.",
                listOf(Manifest.permission.READ_CONTACTS)
            ))
            add(PermissionItem(
                "sms",
                "پیامک‌ها",
                "برای نمایش پیامک‌های همین گوشی و ارسال گزارش فقط پس از تأیید جداگانه مالک دستگاه لازم است. Android ممکن است این مجوز را به‌دلیل محدودیت‌های امنیتی یا روش نصب برنامه مسدود کند.",
                listOf(Manifest.permission.READ_SMS)
            ))
            add(PermissionItem(
                "location",
                "موقعیت مکانی",
                "برای ثبت موقعیت لازم است. در Android 10 دسترسی پس‌زمینه همراه با مجوز اصلی درخواست می‌شود؛ در نسخه‌های جدیدتر جداگانه مدیریت می‌شود.",
                if (Build.VERSION.SDK_INT == 29) {
                    listOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                } else {
                    listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            ))
            if (Build.VERSION.SDK_INT >= 30) {
                add(PermissionItem(
                    "background_location",
                    "موقعیت مکانی در پس‌زمینه",
                    "برای ثبت دوره‌ای موقعیت هنگام بسته بودن برنامه لازم است. این دسترسی جدا از موقعیت تقریبی است و باید آگاهانه از تنظیمات اندروید فعال شود.",
                    listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                ))
            }
            add(PermissionItem(
                "call_log",
                "تاریخچه تماس‌ها",
                "برای نمایش گزارش تماس‌ها روی همین گوشی و ارسال گزارش فقط پس از تأیید جداگانه همان نشست لازم است. امکان اعطای این مجوز ممکن است با محدودیت‌های نسخه اندروید یا روش نصب برنامه روبه‌رو شود.",
                listOf(Manifest.permission.READ_CALL_LOG)
            ))
            add(PermissionItem(
                "microphone",
                "میکروفن",
                "فقط برای نشست صوتی یا واکی‌تاکی که خودت شروع می‌کنی لازم است.",
                listOf(Manifest.permission.RECORD_AUDIO)
            ))
            if (Build.VERSION.SDK_INT >= 33) {
                add(PermissionItem(
                    "notifications",
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
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("RK", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                        Text("RKRemote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("SECURE DEVICE CONTROL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (darkMode) "حالت شب" else "حالت روز", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = onToggleTheme) { Text(if (darkMode) "☀  حالت روز" else "☾  حالت شب") }
                }
                Text("ناوبری", modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
            Surface(shadowElevation = 0.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Text("☰", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("RKRemote", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                        AnimatedContent(targetState = currentPage.title, label = "page-title") { title ->
                            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onToggleTheme) {
                        Text(if (darkMode) "☀" else "☾", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Keep the connection hub composed while visiting other pages.
            // This preserves an active secure session when the drawer is used.
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().offset(x = animatedPageOffset(currentPage == AppPage.HOME))) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
                                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant
                                ))).padding(22.dp)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("RKREMOTE  /  SECURE LINK", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        Text("کنترل هوشمند،\nاتصال امن.", style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("مدیریت دستگاه‌های متصل با ارتباط رمزنگاری‌شده و کنترل دسترسی شفاف.",
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Button(onClick = { currentPage = AppPage.CONNECTIONS }) { Text("ورود به بخش اتصال  ↗") }
                                    }
                                }
                            }
                        }
                        item { Text("نمای کلی", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(14.dp),
                                        color = if (notificationAccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                                        Text(if (notificationAccess) "✓" else "!", Modifier.padding(12.dp),
                                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("وضعیت دسترسی اعلان", fontWeight = FontWeight.Bold)
                                        Text(if (notificationAccess) "فعال · $notificationCount رویداد ذخیره‌شده" else "برای همگام‌سازی، دسترسی لازم است",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("حریم خصوصی در اولویت", fontWeight = FontWeight.Bold)
                                    Text("قابلیت‌های حساس فقط با تأیید صاحب دستگاه فعال می‌شوند.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxSize().offset(x = animatedPageOffset(currentPage in setOf(
                    AppPage.CONNECTIONS, AppPage.SCREEN, AppPage.CAMERA, AppPage.AUDIO,
                    AppPage.REMOTE_FILES, AppPage.REMOTE_NOTIFICATIONS, AppPage.CALL_HISTORY,
                    AppPage.SMS_HISTORY, AppPage.LOCATION, AppPage.SESSION_PERMISSIONS
                )))) {
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
                                    AppPage.LOCATION -> SecureFeatureSection.LOCATION
                                    AppPage.SESSION_PERMISSIONS -> SecureFeatureSection.SESSION_PERMISSIONS
                                    else -> SecureFeatureSection.CONNECTION
                                }
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxSize().offset(x = animatedPageOffset(currentPage == AppPage.PERMISSIONS))) {
                    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("مجوزهای اندروید", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                        items(permissionItems) { item ->
                            val granted = item.permissions.all { permission ->
                                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                            }
                            val requestedOnce = remember(item.key, refreshKey) {
                                PermissionRequestPolicy.wasRequested(context, item.permissions)
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
                                        if (item.key == "background_location" &&
                                            Build.VERSION.SDK_INT >= 30
                                        ) {
                                            val foregroundLocationGranted =
                                                ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                ) == PackageManager.PERMISSION_GRANTED ||
                                                    ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.ACCESS_FINE_LOCATION
                                                    ) == PackageManager.PERMISSION_GRANTED
                                            OutlinedButton(
                                                enabled = foregroundLocationGranted,
                                                onClick = {
                                                    context.startActivity(PermissionRequestPolicy.appSettingsIntent(context))
                                                }
                                            ) {
                                                Text("مدیریت دسترسی در تنظیمات")
                                            }
                                            if (!foregroundLocationGranted) {
                                                Text(
                                                    "ابتدا مجوز موقعیت مکانی را در بخش قبلی فعال کن.",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        } else if (!requestedOnce) {
                                            val foregroundLocationGranted =
                                                item.key != "background_location" ||
                                                    ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                                    ) == PackageManager.PERMISSION_GRANTED ||
                                                    ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.ACCESS_FINE_LOCATION
                                                    ) == PackageManager.PERMISSION_GRANTED
                                            OutlinedButton(
                                                enabled = foregroundLocationGranted,
                                                onClick = {
                                                    // Record before launching so dismissing or denying the
                                                    // system dialog cannot cause repeated prompts.
                                                    PermissionRequestPolicy.markRequested(context, item.permissions)
                                                    refreshKey++
                                                    permissionLauncher.launch(item.permissions.toTypedArray())
                                                }
                                            ) {
                                                Text("درخواست مجوز (فقط یک‌بار)")
                                            }
                                            if (!foregroundLocationGranted) {
                                                Text(
                                                    "ابتدا مجوز موقعیت مکانی را در بخش قبلی فعال کن.",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        } else {
                                            OutlinedButton(onClick = {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                        data = Uri.fromParts("package", context.packageName, null)
                                                    }
                                                )
                                            }) {
                                                Text("باز کردن تنظیمات برنامه")
                                            }
                                            Text(
                                                "درخواست سیستمی قبلاً انجام شده؛ برای تغییر دسترسی، آن را از تنظیمات اندروید مدیریت کن.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
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

                Box(Modifier.fillMaxSize().offset(x = animatedPageOffset(currentPage == AppPage.NOTIFICATIONS))) {
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

                Box(Modifier.fillMaxSize().offset(x = animatedPageOffset(currentPage == AppPage.FILES))) {
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


@Composable
private fun animatedPageOffset(active: Boolean): androidx.compose.ui.unit.Dp {
    val offset by animateDpAsState(
        targetValue = if (active) 0.dp else 2000.dp,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 520f),
        label = "page-slide"
    )
    return offset
}
