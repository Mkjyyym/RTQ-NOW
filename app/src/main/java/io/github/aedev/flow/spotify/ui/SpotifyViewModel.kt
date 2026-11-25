/*
 * RTQ NOW - Spotify section ViewModel.
 *
 * Backs the Spotify tab: login state, search and the user's saved tracks, from the ported
 * spotify_gql_client (AGPL-3.0, sonic-liberation/spotube-plugin-spotify). Playing a Spotify
 * track matches it to YouTube via SpotifyTrackMatcher - no audio comes from Spotify.
 */
package io.github.aedev.flow.spotify.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.aedev.flow.spotify.SpotifySessionManager
import io.github.aedev.flow.spotify.SpotifyTrackMatcher
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.sonic_liberation.spotify_gql_client.gql.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpotifyViewModel(app: Application) : AndroidViewModel(app) {
    private val session = SpotifySessionManager.get(app, viewModelScope)

    val loggedIn = session.loggedIn

    data class UiState(
        val searching: Boolean = false,
        val query: String = "",
        val results: List<Track> = emptyList(),
        val savedTracks: List<Track> = emptyList(),
        val loadingSaved: Boolean = false,
        val matchingTrackId: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun search(query: String) {
        _uiState.update { it.copy(query = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(searching = true, error = null) }
            try {
                val response = withContext(Dispatchers.IO) {
                    session.gqlClient.search.tracksV2(query, limit = 20)
                }
                val tracks = response
                    ?.data?.searchV2?.tracksV2?.items
                    ?.mapNotNull { it.item.data }
                    .orEmpty()
                _uiState.update { it.copy(results = tracks, searching = false) }
            } catch (e: Exception) {
                Log.w(TAG, "spotify search failed", e)
                _uiState.update { it.copy(searching = false, error = e.message) }
            }
        }
    }

    fun loadSavedTracks() {
        if (_uiState.value.loadingSaved || _uiState.value.savedTracks.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingSaved = true) }
            try {
                val response = withContext(Dispatchers.IO) {
                    session.gqlClient.library.savedTracks(limit = 50)
                }
                _uiState.update {
                    it.copy(savedTracks = response.toTrackList(), loadingSaved = false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "spotify saved tracks failed", e)
                _uiState.update { it.copy(loadingSaved = false, error = e.message) }
            }
        }
    }

    /** Match [track] to YouTube and hand the result to [onMatched] for playback. */
    fun play(track: Track, onMatched: (MusicTrack) -> Unit) {
        val info = track.toTrackInfo() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(matchingTrackId = info.spotifyId) }
            val matched = withContext(Dispatchers.IO) { SpotifyTrackMatcher.match(info) }
            _uiState.update {
                it.copy(
                    matchingTrackId = null,
                    error = if (matched == null) "No YouTube match for ${info.title}" else null,
                )
            }
            if (matched != null) onMatched(matched)
        }
    }

    fun logout() = session.logout()

    companion object {
        private const val TAG = "SpotifyViewModel"

        fun Track.toTrackInfo(): SpotifyTrackMatcher.SpotifyTrackInfo? {
            val id = id ?: uri.substringAfterLast(':').takeIf { it.isNotBlank() } ?: return null
            return SpotifyTrackMatcher.SpotifyTrackInfo(
                spotifyId = id,
                title = name,
                artists = (firstArtist ?: artists)?.items?.mapNotNull { it.profile?.name }
                    .orEmpty(),
                album = albumOfTrack?.name,
                durationMs = (duration ?: trackDuration)?.totalMilliseconds ?: 0,
                imageUrl = albumOfTrack?.coverArt?.sources?.firstOrNull()?.url,
            )
        }
    }
}

/** Unwrap the saved-tracks library response into plain tracks. */
private fun io.github.sonic_liberation.spotify_gql_client.gql.library.LibrarySavedTracksResponse?
    .toTrackList(): List<Track> {
    if (this == null) return emptyList()
    return data.me.library.items
        .mapNotNull { it.track?.data }
    }
