/*
 * RTQ NOW - organized local music library screen (Symphony slice).
 *
 * Songs/albums/artists organization + fuzzy search adapted from Symphony
 * (https://github.com/zyrouge/symphony), AGPL-3.0. Rows play through Flow's
 * existing music player via the same callbacks LocalMediaScreen uses.
 */
package io.github.aedev.flow.symphony.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.localmedia.LocalMediaItem
import io.github.aedev.flow.symphony.library.LocalMusicOrganizer
import io.github.aedev.flow.symphony.library.OrganizedAlbum
import io.github.aedev.flow.symphony.library.OrganizedArtist
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.screens.library.MediaAccess
import io.github.aedev.flow.ui.screens.library.localMediaPermissions
import io.github.aedev.flow.ui.screens.library.musicAccess
import io.github.aedev.flow.utils.formatDurationMillis

private enum class LibraryTab { Songs, Albums, Artists }

private sealed interface LibraryDrill {
    data class IntoAlbum(val album: OrganizedAlbum) : LibraryDrill
    data class IntoArtist(val artist: OrganizedArtist) : LibraryDrill
}

@Composable
fun SymphonyLocalLibraryScreen(
    onBackClick: () -> Unit,
    onPlayMusic: (items: List<LocalMediaItem>, startIndex: Int, shuffle: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SymphonyLocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var accessCheck by remember { mutableIntStateOf(0) }
    val access = remember(accessCheck) { context.musicAccess() }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            accessCheck++
            viewModel.refresh()
        }

    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Songs) }
    var drill by remember { mutableStateOf<LibraryDrill?>(null) }

    BackHandler(enabled = drill != null) { drill = null }

    Scaffold(
        modifier = modifier,
        topBar = {
            FlowTopBar(
                title = when (val d = drill) {
                    is LibraryDrill.IntoAlbum -> d.album.name
                    is LibraryDrill.IntoArtist -> d.artist.name
                    null -> stringResource(R.string.local_library_title)
                },
                onBack = {
                    when {
                        drill != null -> drill = null
                        else -> onBackClick()
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                access == MediaAccess.NONE -> {
                    FlowEmptyState(
                        title = stringResource(R.string.local_library_empty),
                        icon = Icons.Outlined.MusicNote,
                        action = {
                            Button(onClick = { permissionLauncher.launch(localMediaPermissions()) }) {
                                Text(stringResource(R.string.local_library_grant_access))
                            }
                        },
                    )
                }

                state.isLoading -> FlowLoadingIndicator()

                state.failed -> FlowEmptyState(
                    title = stringResource(R.string.local_library_empty),
                    icon = Icons.Outlined.MusicNote,
                )

                else -> {
                    val organizer = state.organizer
                    if (organizer == null || organizer.songs.isEmpty()) {
                        FlowEmptyState(
                            title = stringResource(R.string.local_library_empty),
                            icon = Icons.Outlined.MusicNote,
                        )
                    } else {
                        LibraryBody(
                            organizer = organizer,
                            tab = tab,
                            onTab = { tab = it },
                            query = query,
                            onQuery = { query = it },
                            drill = drill,
                            onDrill = { drill = it },
                            onPlayMusic = onPlayMusic,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryBody(
    organizer: LocalMusicOrganizer,
    tab: LibraryTab,
    onTab: (LibraryTab) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    drill: LibraryDrill?,
    onDrill: (LibraryDrill?) -> Unit,
    onPlayMusic: (items: List<LocalMediaItem>, startIndex: Int, shuffle: Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (drill == null) {
            FlowSearchField(
                query = query,
                onQueryChange = onQuery,
                placeholder = stringResource(R.string.local_library_search_hint),
                onClear = { onQuery("") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val tabs = LibraryTab.entries
            TabRow(selectedTabIndex = tabs.indexOf(tab)) {
                tabs.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        text = {
                            Text(
                                when (t) {
                                    LibraryTab.Songs -> stringResource(R.string.local_library_songs_tab)
                                    LibraryTab.Albums -> stringResource(R.string.local_library_albums_tab)
                                    LibraryTab.Artists -> stringResource(R.string.local_library_artists_tab)
                                }
                            )
                        },
                    )
                }
            }
        }

        when (val d = drill) {
            is LibraryDrill.IntoAlbum -> {
                val songs = organizer.albumSongs(d.album)
                SongList(songs = songs, onPlayMusic = onPlayMusic)
            }

            is LibraryDrill.IntoArtist -> {
                val songs = organizer.artistSongs(d.artist)
                SongList(songs = songs, onPlayMusic = onPlayMusic)
            }

            null -> when (tab) {
                LibraryTab.Songs -> {
                    val songs =
                        if (query.isBlank()) {
                            organizer.sortSongs(organizer.songs, io.github.aedev.flow.symphony.library.SongSortBy.TITLE, false)
                        } else {
                            organizer.searchSongs(query)
                        }
                    SongList(songs = songs, onPlayMusic = onPlayMusic)
                }

                LibraryTab.Albums -> {
                    val albums =
                        if (query.isBlank()) {
                            organizer.sortAlbums(organizer.albums, io.github.aedev.flow.symphony.library.AlbumSortBy.ALBUM_NAME, false)
                        } else {
                            organizer.searchAlbums(query)
                        }
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        itemsIndexed(albums, key = { _, a -> a.id }) { _, album ->
                            ListItem(
                                headlineContent = { Text(album.name, maxLines = 1) },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            val names = album.artists.joinToString(", ")
                                            if (names.isNotBlank()) {
                                                append(names)
                                                append(" · ")
                                            }
                                            append(pluralStringResource(R.plurals.local_library_song_count, album.numberOfTracks, album.numberOfTracks))
                                        },
                                        maxLines = 1,
                                    )
                                },
                                leadingContent = {
                                    if (album.artworkUri != null) {
                                        AsyncImage(
                                            model = album.artworkUri,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                        )
                                    } else {
                                        Icon(Icons.Outlined.Album, contentDescription = null, modifier = Modifier.size(48.dp))
                                    }
                                },
                                modifier = Modifier.clickable { onDrill(LibraryDrill.IntoAlbum(album)) },
                            )
                        }
                    }
                }

                LibraryTab.Artists -> {
                    val artists =
                        if (query.isBlank()) {
                            organizer.sortArtists(organizer.artists, io.github.aedev.flow.symphony.library.ArtistSortBy.ARTIST_NAME, false)
                        } else {
                            organizer.searchArtists(query)
                        }
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        itemsIndexed(artists, key = { _, a -> a.name }) { _, artist ->
                            ListItem(
                                headlineContent = { Text(artist.name, maxLines = 1) },
                                supportingContent = {
                                    Text(
                                        pluralStringResource(R.plurals.local_library_song_count, artist.numberOfTracks, artist.numberOfTracks),
                                        maxLines = 1,
                                    )
                                },
                                leadingContent = {
                                    Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(48.dp))
                                },
                                modifier = Modifier.clickable { onDrill(LibraryDrill.IntoArtist(artist)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<LocalMediaItem>,
    onPlayMusic: (items: List<LocalMediaItem>, startIndex: Int, shuffle: Boolean) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            ListItem(
                headlineContent = { Text(song.title, maxLines = 1) },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOf(song.artist, song.album)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                                .ifBlank { song.folderName },
                            maxLines = 1,
                        )
                    }
                },
                leadingContent = {
                    if (song.artworkUri != null) {
                        AsyncImage(
                            model = song.artworkUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp))
                    }
                },
                trailingContent = {
                    Text(
                        formatDurationMillis(song.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable { onPlayMusic(songs, index, false) },
            )
        }
    }
}
