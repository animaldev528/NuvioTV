package com.nuvio.tv.ui.screens.taste

import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.domain.model.TastePickType

/** Lifecycle of the one-shot save at the end of the picker. */
enum class TastePushState {
    Idle,
    Pushing,
    Done
}

/**
 * State for the on-TV taste picker (Phase 3). One option rail is kept per pick type; the
 * screen shows whichever [tab] is active. [selected] preserves insertion order so the push
 * to `sync_push_taste_picks` is stable across retries (the server full-replaces anyway,
 * ordering is cosmetic).
 */
data class TastePickerUiState(
    val tab: TastePickType = TastePickType.MOVIE,
    val query: String = "",
    val loadingOptions: Boolean = false,
    val movieOptions: List<TastePick> = emptyList(),
    val seriesOptions: List<TastePick> = emptyList(),
    val peopleOptions: List<TastePick> = emptyList(),
    val genreOptions: List<TastePick> = emptyList(),
    val selected: List<TastePick> = emptyList(),
    val pushState: TastePushState = TastePushState.Idle,
    val errorMessage: String? = null
) {
    /** The options to render for the active tab. */
    val currentOptions: List<TastePick>
        get() = when (tab) {
            TastePickType.MOVIE -> movieOptions
            TastePickType.SERIES -> seriesOptions
            TastePickType.PERSON -> peopleOptions
            TastePickType.GENRE -> genreOptions
        }

    /** True while the field holds a real query (>= MIN_QUERY_LENGTH), i.e. options show search hits. */
    val hasActiveQuery: Boolean
        get() = query.trim().length >= TastePickerViewModel.MIN_QUERY_LENGTH

    /** Only a non-empty selection can be saved — an empty push would wipe the seed to nothing. */
    val canSubmit: Boolean
        get() = selected.isNotEmpty() && pushState == TastePushState.Idle

    fun isSelected(pick: TastePick): Boolean =
        selected.any { it.pickType == pick.pickType && it.tmdbId == pick.tmdbId }

    fun isPendingErrorMessageVisible(optionsEmpty: Boolean): Boolean =
        errorMessage != null && optionsEmpty && pushState == TastePushState.Idle
}
