package com.rkremote.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class ConnectionSection(val title: String) {
    CLOUDFLARE("Cloudflare"),
    SECURE("اتصال امن"),
    CUSTOM("شبکه سفارشی")
}

/**
 * Single entry point for every connection method.
 * All panels remain composed while switching sections so an active secure
 * session is not torn down when the user opens another connection setting.
 */
@Composable
fun ConnectionHubPanel(feature: SecureFeatureSection = SecureFeatureSection.CONNECTION) {
    var section by remember { mutableStateOf(ConnectionSection.SECURE) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (feature == SecureFeatureSection.CONNECTION) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "اتصال و روش‌ها",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "تنظیم مسیر ارتباط، انتخاب روش اتصال و مدیریت نشست هر دو دستگاه از همین قسمت انجام می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
    
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ConnectionSection.entries.forEach { item ->
                    if (section == item) {
                        androidx.compose.material3.Button(
                            modifier = Modifier.weight(1f),
                            onClick = { section = item }
                        ) { Text(item.title, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { section = item }
                        ) { Text(item.title, maxLines = 1) }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().offset(
                x = if (feature != SecureFeatureSection.CONNECTION || section == ConnectionSection.SECURE) 0.dp else 3000.dp
            )) {
                SecureInternetPanel(feature)
            }
            Box(Modifier.fillMaxWidth().offset(
                x = if (feature == SecureFeatureSection.CONNECTION && section == ConnectionSection.CLOUDFLARE) 0.dp else 3000.dp
            )) {
                CloudflareSetupPanel()
            }
            Box(Modifier.fillMaxWidth().offset(
                x = if (feature == SecureFeatureSection.CONNECTION && section == ConnectionSection.CUSTOM) 0.dp else 3000.dp
            )) {
                OpenNetworkPanel()
            }
        }
    }
}
