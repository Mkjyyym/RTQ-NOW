/*
 * Adapted from Symphony (https://github.com/zyrouge/symphony),
 * services/groove/repositories (SongRepository, AlbumRepository, ArtistRepository),
 * Copyright (C) Zyrouge, licensed under AGPL-3.0. RTQ NOW is AGPL-3.0; see LICENSE.
 *
 * Symphony's album/artist grouping and sort model, reworked to organize Flow's
 * MediaStore-backed LocalMediaItem list. Playback stays Flow's Media3 engine;
 * this layer adds Symphony-style library organization on top.
 */
package io.github.aedev.flow.symphony.library

import io.github.aedev.flow.data.localmedia.LocalMediaItem
import io.github.aedev.flow.symphony.fuzzy.FuzzySearchOption
import io.github.aedev.flow.symphony.fuzzy.FuzzySearcher
import io.github.aedev.flow.symphony.fuzzy.FuzzyResultEntity
import io.github.aedev.flow.utils.foldForSearch

data class OrganizedAlbum(
    val id: String,
    val name: String,
    val artists: Set<String>,
    val numberOfTracks: Int,
    val durationMs: Long,
    val artworkUri: String?,
    val songIds: List<Long>,
)

data class OrganizedArtist(
    val name: String,
    val numberOfTracks: Int,
    val numberOfAlbums: Int,
    val songIds: List<Long>,
)

enum class SongSortBy { TITLE, ARTIST, ALBUM, DURATION, DATE_MODIFIED, FILENAME }
enum class AlbumSortBy { ALBUM_NAME, ARTIST_NAME, TRACKS_COUNT }
enum class ArtistSortBy { ARTIST_NAME, TRACKS_COUNT }

/** Symphony-style organization of Flow's scanned device music. */
class LocalMusicOrganizer(songs: List<LocalMediaItem>) {

    private val songsById: Map<Long, LocalMediaItem> = songs.associateBy { it.id }
    val songs: List<LocalMediaItem> = songs

    private val songSearcher = FuzzySearcher<Long>(
        options = listOf(
            FuzzySearchOption({ v -> songsById[v]?.title?.let { compareString(it) } }, 3),
            FuzzySearchOption({ v -> songsById[v]?.fileName?.let { compareString(it) } }, 2),
            FuzzySearchOption({ v -> songsById[v]?.artist?.let { compareString(it) } }),
            FuzzySearchOption({ v -> songsById[v]?.album?.let { compareString(it) } }),
        )
    )

    /** Symphony: album id = "albumName-albumArtist(s)", so same-named albums stay distinct. */
    val albums: List<OrganizedAlbum> = songs
        .filter { it.album.isNotBlank() }
        .groupBy { it.album to it.artist }
        .map { (key, tracks) ->
            val (albumName, albumArtist) = key
            OrganizedAlbum(
                id = "$albumName-$albumArtist",
                name = albumName,
                artists = tracks.map { it.artist }.filter { it.isNotBlank() }.toSet(),
                numberOfTracks = tracks.size,
                durationMs = tracks.sumOf { it.durationMs },
                artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
                songIds = tracks.map { it.id },
            )
        }

    private val albumsById = albums.associateBy { it.id }

    val artists: List<OrganizedArtist> = songs
        .filter { it.artist.isNotBlank() }
        .groupBy { it.artist }
        .map { (name, tracks) ->
            OrganizedArtist(
                name = name,
                numberOfTracks = tracks.size,
                numberOfAlbums = tracks.map { it.album }.filter { it.isNotBlank() }.distinct().size,
                songIds = tracks.map { it.id },
            )
        }

    private val albumSearcher = FuzzySearcher<String>(
        options = listOf(
            FuzzySearchOption({ v -> albumsById[v]?.name?.let { compareString(it) } }, 3),
            FuzzySearchOption({ v -> albumsById[v]?.artists?.let { compareCollection(it) } }),
        )
    )

    private val artistSearcher = FuzzySearcher<String>(
        options = listOf(
            FuzzySearchOption({ v -> compareString(v) }, 3),
        )
    )

    fun searchSongs(terms: String, limit: Int = -1): List<LocalMediaItem> =
        songSearcher.search(terms, songs.map { it.id }, maxLength = limit)
            .filter { it.score > 0 }
            .mapNotNull { songsById[it.entity] }

    fun searchAlbums(terms: String, limit: Int = -1): List<OrganizedAlbum> =
        albumSearcher.search(terms, albums.map { it.id }, maxLength = limit)
            .filter { it.score > 0 }
            .mapNotNull { albumsById[it.entity] }

    fun searchArtists(terms: String, limit: Int = -1): List<OrganizedArtist> {
        val byName = artists.associateBy { it.name }
        return artistSearcher.search(terms, artists.map { it.name }, maxLength = limit)
            .filter { it.score > 0 }
            .mapNotNull { byName[it.entity] }
    }

    fun sortSongs(items: List<LocalMediaItem>, by: SongSortBy, reverse: Boolean): List<LocalMediaItem> {
        val sorted = when (by) {
            SongSortBy.TITLE -> items.sortedBy { it.title.foldForSearch() }
            SongSortBy.ARTIST -> items.sortedBy { it.artist.foldForSearch() }
            SongSortBy.ALBUM -> items.sortedBy { it.album.foldForSearch() }
            SongSortBy.DURATION -> items.sortedBy { it.durationMs }
            SongSortBy.DATE_MODIFIED -> items.sortedBy { it.modifiedMs }
            SongSortBy.FILENAME -> items.sortedBy { it.fileName.foldForSearch() }
        }
        return if (reverse) sorted.reversed() else sorted
    }

    fun sortAlbums(items: List<OrganizedAlbum>, by: AlbumSortBy, reverse: Boolean): List<OrganizedAlbum> {
        val sorted = when (by) {
            AlbumSortBy.ALBUM_NAME -> items.sortedBy { it.name.foldForSearch() }
            AlbumSortBy.ARTIST_NAME -> items.sortedBy { it.artists.joinToString().foldForSearch() }
            AlbumSortBy.TRACKS_COUNT -> items.sortedBy { it.numberOfTracks }
        }
        return if (reverse) sorted.reversed() else sorted
    }

    fun sortArtists(items: List<OrganizedArtist>, by: ArtistSortBy, reverse: Boolean): List<OrganizedArtist> {
        val sorted = when (by) {
            ArtistSortBy.ARTIST_NAME -> items.sortedBy { it.name.foldForSearch() }
            ArtistSortBy.TRACKS_COUNT -> items.sortedBy { it.numberOfTracks }
        }
        return if (reverse) sorted.reversed() else sorted
    }

    fun album(id: String): OrganizedAlbum? = albumsById[id]
    fun song(id: Long): LocalMediaItem? = songsById[id]
    fun albumSongs(album: OrganizedAlbum): List<LocalMediaItem> = album.songIds.mapNotNull { songsById[it] }
    fun artistSongs(artist: OrganizedArtist): List<LocalMediaItem> = artist.songIds.mapNotNull { songsById[it] }
}
