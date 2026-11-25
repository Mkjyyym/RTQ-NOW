/*
 * RTQ NOW - Spotify home screen.
 *
 * Authenticated Spotify search + saved tracks, from the ported spotify_gql_client
 * (AGPL-3.0, sonic-liberation/spotube-plugin-spotify). Tapping a track matches it to
 * YouTube and plays through Flow's player; audio never comes from Spotify.
 */
package io.github.aedev.flow.spotify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.spotify.SpotifySessionManager
import io.github.aedev.flow.spotify.ui.SpotifyViewModel.Companion.toTrackInfo
import io.github.sonic_liberation.spotify_gql_client.gql.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyHomeScreen(
    viewModel: SpotifyViewModel,
    sessionManager: SpotifySessionManager,
    onPlayMatched: (io.github.aedev.flow.data.music.model.MusicTrack) -> Unit,
    onLoginClick: () -> Unit,
) {
    val loggedIn by viewModel.loggedIn.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spotify") },
                actions = {
                    if (loggedIn) {
                        TextButton(onClick = { viewModel.logout() }) { Text("Log out") }
                    }
                },
            )
        },
    ) { padding ->
        if (!loggedIn) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Sign in to browse your Spotify library and search the catalog. " +
                            "Tracks play through YouTube matching.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                    Button(onClick = onLoginClick) { Text("Sign in to Spotify") }
                }
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.search(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search Spotify") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )

            uiState.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (uiState.searching || uiState.matchingTrackId != null) {
                Row(modifier = Modifier.padding(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        if (uiState.matchingTrackId != null) "Matching to YouTube..." else "Searching...",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            val showingSearch = uiState.query.isNotBlank()
            if (showingSearch) {
                TrackList(
                    tracks = uiState.results,
                    matchingId = uiState.matchingTrackId,
                    onClick = { viewModel.play(it, onPlayMatched) },
                )
            } else {
                Text(
                    "Your saved tracks",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                androidx.compose.runtime.LaunchedEffect(loggedIn) { viewModel.loadSavedTracks() }
                TrackList(
                    tracks = uiState.savedTracks,
                    matchingId = uiState.matchingTrackId,
                    onClick = { viewModel.play(it, onPlayMatched) },
                )
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    matchingId: String?,
    onClick: (Track) -> Unit,
) {
    LazyColumn {
        items(tracks, key = { it.uri.ifBlank { it.name } }) { track ->
            val info = track.toTrackInfo()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = info != null) { onClick(track) },
                leadingContent = {
                    AsyncImage(
                        model = track.albumOfTrack?.coverArt?.sources?.firstOrNull()?.url,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop,
                    )
                },
                headlineContent = { Text(track.name, maxLines = 1) },
                supportingContent = {
                    Text(
                        (track.firstArtist ?: track.artists)
                            ?.items
                            ?.mapNotNull { it.profile?.name }
                            ?.joinToString()
                            .orEmpty(),
                        maxLines = 1,
                    )
                },
                trailingContent = {
                    if (matchingId != null && matchingId == info?.spotifyId) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                },
            )
        }
    }
}
