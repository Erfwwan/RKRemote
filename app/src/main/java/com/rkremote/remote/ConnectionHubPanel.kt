package com.rkremote.remote

import androidx.compose.runtime.Composable

/**
 * The only supported internet transport is the encrypted Cloudflare relay.
 * All legacy connection-method selectors and custom-network panels are removed.
 */
@Composable
fun ConnectionHubPanel(feature: SecureFeatureSection = SecureFeatureSection.CONNECTION) {
    SecureInternetPanel(feature)
}
