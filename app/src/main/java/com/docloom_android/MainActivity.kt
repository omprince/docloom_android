package com.docloom_android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.docloom_android.core.parseDeepLink
import com.docloom_android.navigation.DocloomRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch carries a link; a recreated activity already handled it.
        if (savedInstanceState == null) handleLink(intent)
        setContent { DocloomRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** Website invite and password-reset links (see src/release/AndroidManifest.xml). */
    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = parseDeepLink(intent.dataString, BuildConfig.WEBSITE_URL)
        (application as DocloomApp).container.deepLinks.offer(link)
    }
}
