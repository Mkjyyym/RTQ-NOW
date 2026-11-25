/*
 * RTQ NOW - organized local music library (Symphony slice).
 *
 * Organization model adapted from Symphony (https://github.com/zyrouge/symphony),
 * AGPL-3.0. Data comes from Flow's LocalMediaRepository (MediaStore); playback
 * stays Flow's Media3 music player.
 */
package io.github.aedev.flow.symphony.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.localmedia.LocalMediaRepository
import io.github.aedev.flow.symphony.library.LocalMusicOrganizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SymphonyLibraryUiState(
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val organizer: LocalMusicOrganizer? = null,
    val songCount: Int = 0,
)

@HiltViewModel
class SymphonyLocalLibraryViewModel
    @Inject
    constructor(
        private val repository: LocalMediaRepository,
    ) : ViewModel() {

        val uiState: StateFlow<SymphonyLibraryUiState> =
            repository.library
                .map { library ->
                    val organizer = LocalMusicOrganizer(library.music)
                    SymphonyLibraryUiState(
                        isLoading = false,
                        failed = library.failed,
                        organizer = organizer,
                        songCount = organizer.songs.size,
                    )
                }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SymphonyLibraryUiState())

        fun refresh() = repository.refresh()
    }
