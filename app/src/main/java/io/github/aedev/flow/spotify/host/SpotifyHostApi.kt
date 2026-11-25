/*
 * RTQ NOW - Spotify host-API shim.
 *
 * The Spotify data layer under io.github.sonic_liberation.spotify_gql_client is ported from
 * sonic-liberation/spotube-plugin-spotify (AGPL-3.0). Upstream it runs against Spotube's
 * plugin host interfaces (org.spotube.plugin_interfaces.host_apis). This file provides the
 * minimal equivalent surface on Flow primitives so the ported client compiles and runs
 * inside RTQ NOW:
 *
 *   HttpClientAPI        -> SpotifyHttpClient (implemented over Flow's proxy-aware OkHttp)
 *   HttpMethod           -> SpotifyHttpMethod
 *   Cookie               -> SpotifyCookie
 *   SystemInformationAPI -> SpotifySystemInfo
 *
 * Only what the ported code actually calls is declared here.
 */

package io.github.aedev.flow.spotify.host

import io.github.aedev.flow.network.ProxyAwareClient
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TimeZone

enum class SpotifyHttpMethod { Get, Post, Put, Delete }

@Serializable
data class SpotifyCookie(val name: String, val value: String)

data class SpotifyHttpResponse(
    val statusCode: Int,
    val body: String?,
)

interface SpotifyHttpClient {
    suspend fun request(
        method: SpotifyHttpMethod,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): SpotifyHttpResponse
}

interface SpotifySystemInfo {
    fun getTimeZone(): String
}

class FlowSpotifySystemInfo : SpotifySystemInfo {
    override fun getTimeZone(): String = TimeZone.getDefault().id
}

/** [SpotifyHttpClient] over Flow's shared, proxy-aware OkHttp instance. */
class FlowSpotifyHttpClient(
    private val clients: ProxyAwareClient = ProxyAwareClient(),
) : SpotifyHttpClient {
    override suspend fun request(
        method: SpotifyHttpMethod,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): SpotifyHttpResponse = withContext(Dispatchers.IO) {
        val requestBody = body?.toRequestBody("application/json".toMediaType())
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        when (method) {
            SpotifyHttpMethod.Get -> builder.get()
            SpotifyHttpMethod.Post -> builder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            SpotifyHttpMethod.Put -> builder.put(requestBody ?: ByteArray(0).toRequestBody(null))
            SpotifyHttpMethod.Delete ->
                if (requestBody != null) builder.delete(requestBody) else builder.delete()
        }
        clients.get().newCall(builder.build()).execute().use { response ->
            SpotifyHttpResponse(
                statusCode = response.code,
                body = response.body?.string(),
            )
        }
    }
}
