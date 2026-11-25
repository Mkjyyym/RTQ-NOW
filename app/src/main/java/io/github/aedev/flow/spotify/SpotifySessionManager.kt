/*
 * RTQ NOW - Spotify session manager.
 *
 * Android port of the auth/session logic from
 * sonic-liberation/spotube-plugin-spotify RealCoreAPI.kt (AGPL-3.0):
 *   - WebView login to accounts.spotify.com captures the sp_dc cookie (UI layer supplies it)
 *   - the cookie is exchanged for a web access token via open.spotify.com/api/token,
 *     authenticated with a server-time-synced TOTP whose secret/version ("nuance") comes
 *     from the community-maintained gist the upstream plugin also uses
 *   - the token is refreshed on a schedule before expiry, with a minimum delay floor
 *
 * Differences from upstream: Spotube host APIs (storage, crypto, HTTP, WebView) are replaced
 * by Flow primitives - SharedPreferences-backed persistence, javax.crypto TOTP
 * (SpotifyTotp), and Flow's proxy-aware OkHttp via FlowSpotifyHttpClient.
 *
 * RTQ NOW plays audio by matching Spotify tracks to YouTube; no audio is ever fetched
 * from Spotify servers. This layer provides authenticated metadata/search/library only.
 */

package io.github.aedev.flow.spotify

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.spotify.host.FlowSpotifyHttpClient
import io.github.aedev.flow.spotify.host.SpotifyCookie
import io.github.aedev.flow.spotify.host.SpotifyHttpClient
import io.github.aedev.flow.spotify.host.SpotifyHttpMethod
import io.github.sonic_liberation.spotify_gql_client.gql.CredentialsFromCookieResult
import io.github.sonic_liberation.spotify_gql_client.gql.SpotifyGQLBaseClient
import io.github.sonic_liberation.spotify_gql_client.gql.SpotifyGQLClient
import io.github.aedev.flow.spotify.host.FlowSpotifySystemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ServerTimeResponse(val serverTime: Long)

@Serializable
private data class NuanceInfo(val v: Int, val s: String)

@Serializable
private data class SpotifyTokenResponse(
    val accessToken: String,
    val accessTokenExpirationTimestampMs: Long,
    val isAnonymous: Boolean,
)

class SpotifySessionManager private constructor(
    context: Context,
    private val http: SpotifyHttpClient,
    private val scope: CoroutineScope,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn = _loggedIn.asStateFlow()

    val gqlClient: SpotifyGQLClient =
        SpotifyGQLClient(http, FlowSpotifySystemInfo())

    init {
        scope.launch { restoreSession() }
    }

    val cookies: List<SpotifyCookie>?
        get() = SpotifyGQLBaseClient.credentials?.cookies

    private suspend fun restoreSession() {
        // The stored blob is Keystore-sealed; a copied-out preferences file is useless off-device.
        val raw = KeystoreSecretBox.open(prefs.getString(KEY_CREDENTIALS, null))
        if (raw.isEmpty()) return
        val credentials = runCatching {
            json.decodeFromString<CredentialsFromCookieResult>(raw)
        }.getOrNull() ?: return
        SpotifyGQLBaseClient.credentials = credentials
        val now = System.currentTimeMillis()
        if (credentials.expiration - now <= REFRESH_MARGIN_MILLIS) {
            runCatching { storeSession(credentialsFromCookie(credentials.cookies)) }
                .onFailure { Log.w(TAG, "failed to refresh expired session on restore", it) }
        }
        _loggedIn.value = true
        scheduleRefresh()
    }

    private fun storeSession(credentials: CredentialsFromCookieResult) {
        val sealed = KeystoreSecretBox.seal(json.encodeToString(credentials))
        prefs.edit().putString(KEY_CREDENTIALS, sealed).apply()
        SpotifyGQLBaseClient.credentials = credentials
    }

    /** Called by the login UI once the accounts.spotify.com WebView reaches the status page. */
    suspend fun onLoginCookies(cookies: List<SpotifyCookie>) {
        storeSession(credentialsFromCookie(cookies))
        _loggedIn.value = true
        scheduleRefresh()
    }

    fun logout() {
        refreshJob?.cancel()
        SpotifyGQLBaseClient.credentials = null
        prefs.edit().remove(KEY_CREDENTIALS).apply()
        _loggedIn.value = false
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val expiration = SpotifyGQLBaseClient.credentials?.expiration
            val cookies = SpotifyGQLBaseClient.credentials?.cookies
            if (expiration == null || cookies == null) {
                Log.w(TAG, "cannot schedule refresh: missing credentials")
                return@launch
            }
            val remaining = expiration - System.currentTimeMillis()
            delay((remaining - REFRESH_MARGIN_MILLIS).coerceAtLeast(MIN_REFRESH_DELAY_MILLIS))
            refreshMutex.withLock {
                runCatching { storeSession(credentialsFromCookie(cookies)) }
                    .onFailure {
                        Log.w(TAG, "session refresh failed; backing off", it)
                        delay(MIN_REFRESH_DELAY_MILLIS)
                    }
            }
            scheduleRefresh()
        }
    }

    private suspend fun generateTimedOtp(secret: String): String {
        val res = http.request(
            SpotifyHttpMethod.Get,
            "https://open.spotify.com/api/server-time",
            emptyMap(),
            null,
        )
        val serverTime = json.decodeFromString<ServerTimeResponse>(res.body ?: "").serverTime
        return SpotifyTotp.generate(secret, serverTime * 1000)
    }

    private suspend fun latestNuance(cached: Boolean = true): NuanceInfo {
        val url = buildString {
            append("https://gist.githubusercontent.com/raw/22ed9c6ba463899e933427f7de1f0eef/nuances.json")
            if (!cached) append("?t=${System.currentTimeMillis()}")
        }
        val res = http.request(SpotifyHttpMethod.Get, url, emptyMap(), null)
        val nuances = json.decodeFromString<List<NuanceInfo>>(res.body ?: "[]")
        return nuances.maxByOrNull { it.v } ?: error("no nuances found")
    }

    private suspend fun getToken(totp: String, spDc: String, totpVer: Int): SpotifyTokenResponse {
        val url = "https://open.spotify.com/api/token?reason=transport" +
            "&productType=web-player&totp=$totp&totpServer=$totp&totpVer=$totpVer"
        val res = http.request(
            SpotifyHttpMethod.Get,
            url,
            mapOf(
                "User-Agent" to "${System.currentTimeMillis()}${(0..255).random()}",
                "Cookie" to "sp_dc=$spDc;",
            ),
            null,
        )
        if (res.statusCode != 200) {
            error("failed to get token: ${res.statusCode}")
        }
        return json.decodeFromString(res.body ?: "")
    }

    private suspend fun credentialsFromCookie(cookies: List<SpotifyCookie>): CredentialsFromCookieResult {
        val spDc = cookies.firstOrNull { it.name == "sp_dc" }?.value
            ?: error("sp_dc cookie not found")
        var nuance = latestNuance()
        var totp = generateTimedOtp(nuance.s)
        val token = runCatching { getToken(totp, spDc, nuance.v) }.getOrElse {
            // Bust the nuance cache and retry once, mirroring upstream.
            nuance = latestNuance(cached = false)
            totp = generateTimedOtp(nuance.s)
            getToken(totp, spDc, nuance.v)
        }
        return CredentialsFromCookieResult(
            cookies = cookies,
            accessToken = token.accessToken,
            expiration = token.accessTokenExpirationTimestampMs,
        )
    }

    companion object {
        private const val TAG = "SpotifySessionManager"
        private const val PREFS_NAME = "rtqnow_spotify"
        private const val KEY_CREDENTIALS = "credentials"
        private const val REFRESH_MARGIN_MILLIS = 60_000L
        private const val MIN_REFRESH_DELAY_MILLIS = 30_000L

        @Volatile
        private var instance: SpotifySessionManager? = null

        fun get(context: Context, scope: CoroutineScope): SpotifySessionManager =
            instance ?: synchronized(this) {
                instance ?: SpotifySessionManager(
                    context.applicationContext,
                    FlowSpotifyHttpClient(),
                    scope,
                ).also { instance = it }
            }
    }
}
