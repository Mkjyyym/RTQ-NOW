/*
 * RTQ NOW - Spotify track -> YouTube playback matching.
 *
 * RTQ NOW never fetches audio from Spotify. As in Spotube, a Spotify track is matched to a
 * YouTube song and played through Flow's existing player. This matcher does that mapping:
 * it searches YouTube Music for "<title> <artists>" and picks the result whose duration is
 * closest to the Spotify track's, preferring exact title matches.
 */
package io.github.aedev.flow.spotify

import android.util.Log
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.YTItem
import kotlin.math.abs

object SpotifyTrackMatcher {
    private const val TAG = "SpotifyTrackMatcher"

    data class SpotifyTrackInfo(
        val spotifyId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long,
        val imageUrl: String?,
    )

    /** The YouTube match for [track], or null when search finds nothing playable. */
    suspend fun match(track: SpotifyTrackInfo): MusicTrack? {
        val query = buildString {
            append(track.title)
            track.artists.firstOrNull()?.let { append(' ').append(it) }
        }
        val items: List<YTItem> = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
            .getOrElse {
                Log.w(TAG, "YouTube search failed for '$query'", it)
                return null
            }
            .items
        val songs = items.filterIsInstance<SongItem>()
        if (songs.isEmpty()) return null

        val best = songs
            .map { song -> song to score(song, track) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
            ?.first
            ?: return null

        return MusicTrack(
            videoId = best.id,
            title = track.title,
            artist = track.artists.joinToString(),
            thumbnailUrl = track.imageUrl ?: best.thumbnail,
            duration = (track.durationMs / 1000).toInt(),
            sourceUrl = "https://www.youtube.com/watch?v=${best.id}",
            album = track.album ?: "Unknown Album",
            channelId = "",
            artists = track.artists.map { MusicArtist(it) },
        )
    }

    /**
     * Higher is better. Title overlap dominates; duration proximity breaks ties (Spotify and
     * YouTube durations for the same recording are usually within a few seconds).
     */
    private fun score(song: SongItem, track: SpotifyTrackInfo): Int {
        var score = 0
        if (song.title.equals(track.title, ignoreCase = true)) {
            score += 100
        } else if (song.title.contains(track.title, ignoreCase = true) ||
            track.title.contains(song.title, ignoreCase = true)
        ) {
            score += 60
        }
        val songArtist = song.artists.joinToString(" ") { it.name }
        if (track.artists.any { songArtist.contains(it, ignoreCase = true) }) score += 40
        val songDurationMs = (song.duration ?: 0) * 1000L
        if (songDurationMs > 0 && track.durationMs > 0) {
            val diff = abs(songDurationMs - track.durationMs)
            score += when {
                diff <= 3_000 -> 30
                diff <= 8_000 -> 15
                diff <= 15_000 -> 5
                else -> 0
            }
        }
        return score
    }
}
