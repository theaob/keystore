package com.onurbaykal.keystoremanager

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.onurbaykal.keystoremanager.ads.Ads
import com.onurbaykal.keystoremanager.ui.KeystoreApp
import com.onurbaykal.keystoremanager.ui.theme.KeystoreTheme

// FragmentActivity because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Passwords are shown on screen; keep them out of screenshots and the recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            KeystoreTheme {
                KeystoreApp()
            }
        }
        Ads.start(this)
    }
}
