/*
 * RTQ NOW - Spotify section routes.
 *
 * Wires the Spotify tab (home + login) into Flow's navigation graph.
 */
package io.github.aedev.flow.spotify.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import io.github.aedev.flow.spotify.SpotifySessionManager
import io.github.aedev.flow.ui.page
import io.github.aedev.flow.ui.screens.music.sharedMusicPlayerViewModel

internal fun NavGraphBuilder.spotifyRoutes(
    navController: NavHostController,
    onMusicStarted: () -> Unit,
) {
    page("spotify") {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val sessionManager = remember { SpotifySessionManager.get(context, scope) }
        val spotifyViewModel: SpotifyViewModel = viewModel()
        val musicPlayerViewModel = sharedMusicPlayerViewModel()

        SpotifyHomeScreen(
            viewModel = spotifyViewModel,
            sessionManager = sessionManager,
            onPlayMatched = { track ->
                musicPlayerViewModel.loadAndPlayTrack(track, listOf(track), "Spotify")
                onMusicStarted()
            },
            onLoginClick = { navController.navigate("spotifyLogin") },
        )
    }

    page("spotifyLogin") {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val sessionManager = remember { SpotifySessionManager.get(context, scope) }

        SpotifyLoginScreen(
            sessionManager = sessionManager,
            onLoggedIn = { navController.popBackStack() },
            onBack = { navController.popBackStack() },
        )
    }
}
