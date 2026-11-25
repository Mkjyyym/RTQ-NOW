/*
 * RTQ NOW - Spotify login screen.
 *
 * Android port of the WebView login from sonic-liberation/spotube-plugin-spotify
 * RealCoreAPI.login() (AGPL-3.0): the user signs in at accounts.spotify.com in a WebView;
 * when the URL reaches the post-login status page, the sp_dc cookie is captured and handed
 * to SpotifySessionManager, which exchanges it for a web access token.
 */
package io.github.aedev.flow.spotify.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.aedev.flow.spotify.SpotifySessionManager
import io.github.aedev.flow.spotify.host.SpotifyCookie
import kotlinx.coroutines.launch

private val STATUS_URL = Regex("""^https://accounts\.spotify\.com/[^/]+/status($|\?.*)$""")

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    sessionManager: SpotifySessionManager,
    onLoggedIn: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to Spotify") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                val safeUrl =
                                    if (url.endsWith("/") && url.length > 1) url.dropLast(1) else url
                                if (!STATUS_URL.matches(safeUrl)) return
                                val raw = cookieManager.getCookie("https://spotify.com") ?: return
                                val cookies = raw.split(";").mapNotNull {
                                    val parts = it.trim().split("=", limit = 2)
                                    if (parts.size == 2) SpotifyCookie(parts[0], parts[1]) else null
                                }
                                if (cookies.none { it.name == "sp_dc" }) return
                                finishing = true
                                scope.launch {
                                    sessionManager.onLoginCookies(cookies)
                                    onLoggedIn()
                                }
                            }
                        }
                        loadUrl("https://accounts.spotify.com/")
                    }
                },
            )
            if (finishing) {
                Column(modifier = Modifier.align(Alignment.Center)) {
                    CircularProgressIndicator()
                    Text("Finishing sign in...")
                }
            }
        }
    }
}
