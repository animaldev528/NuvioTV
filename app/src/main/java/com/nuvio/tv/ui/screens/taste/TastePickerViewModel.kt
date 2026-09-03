package com.nuvio.tv.ui.screens.taste

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.TastePickSyncService
import com.nuvio.tv.core.taste.TastePickerCatalog
import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.domain.model.TastePickType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backing logic for the on-TV taste picker. Owns the four per-type option rails, the
 * "popular / live search" toggle inside a tab, the insertion-ordered selection, and the
 * final save through [TastePickSyncService] (which mirrors the server's `taste_completed`
 * flip back into the local profile so the picker exits).
 */
@HiltViewModel
class TastePickerViewModel @Inject constructor(
    private val catalog: TastePickerCatalog,
    profileManager: ProfileManager,
    private val tastePickSyncService: TastePickSyncService
) : ViewModel() {

    companion object {
        private const val TAG = "TastePickerViewModel"
        /** Below this a query is too short to search; the tab shows its popular rail instead. */
        const val MIN_QUERY_LENGTH = 2
        private const val SEARCH_DEBOUNCE_MS = 350L
    }

    private val activeProfileId = profileManager.activeProfileId.value

    private val _uiState = MutableStateFlow(TastePickerUiState())
    val uiState: StateFlow<TastePickerUiState> = _uiState.asStateFlow()

    /** Cached default (popular) rails so re-entering a tab never refetches. */
    private val defaultRails = mutableMapOf<TastePickType, List<TastePick>>()

    private var searchJob: Job? = null

    /** Per-type in-flight markers so switching tabs while a rail is loading queues its own load. */
    private val pendingLoads = mutableSetOf<TastePickType>()

    init {
        loadDefaultFor(TastePickType.MOVIE)
    }

    fun onTabSelected(tab: TastePickType) {
        if (tab == _uiState.value.tab) return
        searchJob?.cancel()
        _uiState.update { it.copy(tab = tab, query = "", loadingOptions = false, errorMessage = null) }
        loadDefaultFor(tab)
    }

    fun onQueryChanged(query: String) {
        val state = _uiState.value
        if (state.tab == TastePickType.GENRE || state.pushState != TastePushState.Idle) return

        searchJob?.cancel()
        _uiState.update { it.copy(query = query, errorMessage = null) }

        val trimmed = query.trim()
        if (trimmed.length >= MIN_QUERY_LENGTH) {
            // Live search: keep whatever is on screen while the debounce elapses, then swap.
            _uiState.update { it.copy(loadingOptions = true) }
            searchJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                runSearch(state.tab, trimmed)
            }
        } else {
            // Query cleared/too short -> back to this tab's popular rail.
            searchJob = null
            val rail = defaultRails[state.tab]
            if (rail != null) {
                _uiState.update { applyOptions(it, state.tab, rail) }
            } else {
                loadDefaultFor(state.tab)
            }
        }
    }

    fun onTogglePick(pick: TastePick) {
        val state = _uiState.value
        if (state.pushState != TastePushState.Idle) return
        val selected = if (state.isSelected(pick)) {
            state.selected.filterNot { it.pickType == pick.pickType && it.tmdbId == pick.tmdbId }
        } else {
            state.selected + pick
        }
        _uiState.update { it.copy(selected = selected, errorMessage = null) }
    }

    fun retryCurrentTab() {
        val state = _uiState.value
        if (state.hasActiveQuery) {
            val query = state.query.trim()
            viewModelScope.launch { runSearch(state.tab, query) }
        } else {
            loadDefaultFor(state.tab, force = true)
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.selected.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Pick at least one favourite to build your home.") }
            return
        }
        if (state.pushState != TastePushState.Idle) return

        _uiState.update { it.copy(pushState = TastePushState.Pushing, errorMessage = null) }
        val profileId = activeProfileId
        val picks = state.selected
        viewModelScope.launch {
            tastePickSyncService.pushTastePicks(profileId, picks)
                .onSuccess {
                    _uiState.update { it.copy(pushState = TastePushState.Done) }
                }
                .onFailure { error ->
                    Log.e(TAG, "Taste pick push failed", error)
                    _uiState.update {
                        it.copy(
                            pushState = TastePushState.Idle,
                            errorMessage = "Couldn't save your picks. Check your connection and try again."
                        )
                    }
                }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private suspend fun defaultLoader(type: TastePickType): List<TastePick> = when (type) {
        TastePickType.MOVIE -> catalog.popularMovies()
        TastePickType.SERIES -> catalog.popularSeries()
        TastePickType.PERSON -> catalog.popularPeople()
        TastePickType.GENRE -> catalog.movieGenres()
    }

    private fun loadDefaultFor(type: TastePickType, force: Boolean = false) {
        if (pendingLoads.contains(type)) return
        if (!force && defaultRails.containsKey(type)) {
            _uiState.update { applyOptions(it, type, defaultRails.getValue(type)) }
            return
        }
        pendingLoads.add(type)
        _uiState.update { it.copy(loadingOptions = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val options = defaultLoader(type)
                defaultRails[type] = options
                _uiState.update { applyOptions(it, type, options) }
            } catch (e: Exception) {
                Log.e(TAG, "Default rail load failed for $type", e)
                _uiState.update {
                    it.copy(
                        loadingOptions = false,
                        errorMessage = "Couldn't load ${label(type)}. Check your connection and try again."
                    )
                }
            } finally {
                pendingLoads.remove(type)
            }
        }
    }

    private suspend fun runSearch(type: TastePickType, query: String) {
        _uiState.update { it.copy(loadingOptions = true, errorMessage = null) }
        try {
            val results = when (type) {
                TastePickType.MOVIE -> catalog.searchMovies(query)
                TastePickType.SERIES -> catalog.searchSeries(query)
                TastePickType.PERSON -> catalog.searchPeople(query)
                TastePickType.GENRE -> emptyList()
            }
            val current = _uiState.value
            // Ignore a stale response if the user moved tabs or edited the query meanwhile.
            if (current.tab != type || current.query.trim() != query) return
            _uiState.update { applyOptions(it, type, results) }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for $type", e)
            val current = _uiState.value
            if (current.tab != type || current.query.trim() != query) return
            _uiState.update {
                it.copy(
                    loadingOptions = false,
                    errorMessage = "Search didn't work. Check your connection and try again."
                )
            }
        }
    }

    /** Swap one tab's options list, preserving everything else (selection included). */
    private fun applyOptions(state: TastePickerUiState, type: TastePickType, options: List<TastePick>): TastePickerUiState {
        val deduped = options.distinctBy { it.tmdbId }
        return when (type) {
            TastePickType.MOVIE -> state.copy(movieOptions = deduped, loadingOptions = false)
            TastePickType.SERIES -> state.copy(seriesOptions = deduped, loadingOptions = false)
            TastePickType.PERSON -> state.copy(peopleOptions = deduped, loadingOptions = false)
            TastePickType.GENRE -> state.copy(genreOptions = deduped, loadingOptions = false)
        }
    }

    private fun label(type: TastePickType): String = when (type) {
        TastePickType.MOVIE -> "movies"
        TastePickType.SERIES -> "series"
        TastePickType.PERSON -> "people"
        TastePickType.GENRE -> "genres"
    }
}
