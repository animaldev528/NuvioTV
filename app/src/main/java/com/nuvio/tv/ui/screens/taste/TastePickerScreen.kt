package com.nuvio.tv.ui.screens.taste

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.domain.model.TastePickType
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max

/**
 * On-TV taste picker (Phase 3): the one-shot onboarding a curated profile (Jack) sees in place
 * of Home until it has a seed. Four pick-type tabs share one interaction model:
 *
 *  - **Movies / Series / People** show a "popular" poster rail that becomes a live search rail
 *    as soon as the D-pad reaches the search field and a query is typed. OK toggles a pick.
 *  - **Genres** is a fixed grid of the TMDB movie-genre taxonomy (movie genre ids are what the
 *    curated-row engine expects for a genre pick).
 *
 * Focus model mirrors [com.nuvio.tv.ui.components.AvatarPickerGrid]: the active tab chip drops
 * down into the grid's first tile; the top grid row returns up to the search field (or straight
 * back to the chips on the Genres tab). Selected tiles get a Secondary ring + check badge.
 * The trailing "Done (N)" chip in the tab row saves the seed via
 * [com.nuvio.tv.core.sync.TastePickSyncService]; once the profile's `taste_completed` flag flips
 * (server + local mirror) MainActivity recomposes and this screen unmounts back into the normal
 * startup flow.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TastePickerScreen(
    profileName: String?,
    viewModel: TastePickerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val tabRequesters = remember { TastePickType.values().associateWith { FocusRequester() } }
    val searchFieldRequester = remember { FocusRequester() }
    val contentEntryRequester = remember { FocusRequester() }

    // Only the currently active tab is composed, so a single content-entry requester is safe.
    val activeTabRequester = tabRequesters.getValue(uiState.tab)
    val isGenreTab = uiState.tab == TastePickType.GENRE

    var contentFocusGivenFor by remember { mutableStateOf<TastePickType?>(null) }

    val ownName = profileName?.takeIf { it.isNotBlank() }
    val greeting = if (ownName != null) "Tell us what $ownName loves" else "Tell us what you love"

    // Landing focus on a fresh tab: wait for its rail, then focus the grid's first tile.
    LaunchedEffect(uiState.tab) { contentFocusGivenFor = null }
    LaunchedEffect(uiState.tab, uiState.loadingOptions) {
        if (contentFocusGivenFor != uiState.tab && !uiState.loadingOptions &&
            uiState.currentOptions.isNotEmpty()
        ) {
            contentFocusGivenFor = uiState.tab
            delay(160)
            runCatching { contentEntryRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ───────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = NuvioTheme.spacing.xxxl,
                        end = NuvioTheme.spacing.xxxl,
                        top = NuvioTheme.spacing.xxl,
                        bottom = NuvioTheme.spacing.lg
                    ),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Pick movies, shows, people and genres — every pick becomes a shelf on your home.",
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Tab chips + Done ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TastePickType.values().forEach { tab ->
                    val active = uiState.tab == tab
                    PickerChip(
                        text = tabLabel(tab),
                        selected = active,
                        modifier = Modifier
                            .focusRequester(tabRequesters.getValue(tab))
                            .then(
                                if (active) {
                                    Modifier.focusProperties { down = contentEntryRequester }
                                } else {
                                    Modifier
                                }
                            ),
                        onClick = { viewModel.onTabSelected(tab) }
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                }

                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                DoneChip(
                    count = uiState.selected.size,
                    enabled = uiState.canSubmit,
                    pushing = uiState.pushState == TastePushState.Pushing,
                    onClick = { viewModel.submit() }
                )
            }

            // ── Active tab content ───────────────────────────────────────────
            if (isGenreTab) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = NuvioTheme.spacing.md)
                ) {
                    TasteOptionsArea(
                        options = uiState.genreOptions,
                        loading = uiState.loadingOptions,
                        errorMessage = uiState.errorMessage,
                        upFocusRequester = activeTabRequester,
                        entryFocusRequester = contentEntryRequester,
                        emptyMessage = "No genres to show right now.",
                        onToggle = viewModel::onTogglePick,
                        isSelected = uiState::isSelected,
                        onRetry = viewModel::retryCurrentTab
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = NuvioTheme.spacing.sm)
                ) {
                    TasteSearchField(
                        query = uiState.query,
                        tab = uiState.tab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTheme.spacing.xxxl)
                            .focusRequester(searchFieldRequester)
                            .focusProperties {
                                up = activeTabRequester
                                down = contentEntryRequester
                            },
                        onQueryChanged = viewModel::onQueryChanged
                    ) {
                        keyboardController?.hide()
                        runCatching { contentEntryRequester.requestFocus() }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = NuvioTheme.spacing.md)
                    ) {
                        TasteOptionsArea(
                            options = uiState.currentOptions,
                            loading = uiState.loadingOptions,
                            errorMessage = uiState.errorMessage,
                            upFocusRequester = searchFieldRequester,
                            entryFocusRequester = contentEntryRequester,
                            emptyMessage = "No results. Try a different search.",
                            onToggle = viewModel::onTogglePick,
                            isSelected = uiState::isSelected,
                            onRetry = viewModel::retryCurrentTab
                        )
                    }
                }
            }

            // ── Footer: transient status ─────────────────────────────────────
            val footerMessage = when {
                uiState.pushState == TastePushState.Pushing -> "Saving your picks…"
                uiState.errorMessage != null -> uiState.errorMessage
                uiState.selected.isEmpty() -> "Pick at least one favourite, then press Done."
                else -> null
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = NuvioTheme.spacing.xxxl,
                        vertical = NuvioTheme.spacing.md
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                if (footerMessage != null) {
                    Text(
                        text = footerMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.errorMessage != null) {
                            NuvioTheme.colors.TextSecondary
                        } else {
                            NuvioTheme.colors.TextTertiary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * The shared poster/result area used by all four tabs: a poster grid (media + people) or a
 * genre chip grid, with loading / error / empty fallbacks. [entryFocusRequester] lands on the
 * grid's first tile; top-row tiles point [upFocusRequester] back out of the grid.
 */
@Composable
private fun TasteOptionsArea(
    options: List<TastePick>,
    loading: Boolean,
    errorMessage: String?,
    upFocusRequester: FocusRequester?,
    entryFocusRequester: FocusRequester?,
    emptyMessage: String,
    onToggle: (TastePick) -> Unit,
    isSelected: (TastePick) -> Boolean,
    onRetry: () -> Unit
) {
    val genreLayout = options.firstOrNull()?.pickType == TastePickType.GENRE

    when {
        loading && options.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        }

        errorMessage != null && options.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                // Reuses the content entry so D-pad-down from the tab row lands here.
                RetryChip(
                    onClick = onRetry,
                    modifier = if (entryFocusRequester != null) {
                        Modifier.focusRequester(entryFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }

        options.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
                )
            }
        }

        else -> {
            TastePickerGrid(
                options = options,
                genreLayout = genreLayout,
                upFocusRequester = upFocusRequester,
                entryFocusRequester = entryFocusRequester,
                onToggle = onToggle,
                isSelected = isSelected,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun TastePickerGrid(
    options: List<TastePick>,
    genreLayout: Boolean,
    upFocusRequester: FocusRequester?,
    entryFocusRequester: FocusRequester?,
    onToggle: (TastePick) -> Unit,
    isSelected: (TastePick) -> Boolean,
    modifier: Modifier = Modifier
) {
    val upRequester = upFocusRequester
    val entryRequester = entryFocusRequester
    val minCell = if (genreLayout) 220.dp else 150.dp
    val gridPadding = 24.dp
    val gridSpacing = 14.dp

    BoxWithConstraints(modifier = modifier) {
        val maxWidth = this.maxWidth
        val columnCount = rememberColumnCount(
            maxWidth = maxWidth,
            minCellWidth = minCell,
            spacing = gridSpacing,
            horizontalPadding = gridPadding
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCell),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = gridPadding,
                end = gridPadding,
                top = 4.dp,
                bottom = gridPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
            verticalArrangement = Arrangement.spacedBy(gridSpacing)
        ) {
            itemsIndexed(
                items = options,
                key = { _, pick -> "${pick.pickType.wire}:${pick.tmdbId}" }
            ) { index, pick ->
                val itemModifier = Modifier
                    .then(
                        if (index == 0 && entryRequester != null) {
                            Modifier.focusRequester(entryRequester)
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (index < columnCount && upRequester != null) {
                            Modifier.focusProperties { up = upRequester }
                        } else {
                            Modifier
                        }
                    )
                    .fillMaxWidth()

                if (genreLayout) {
                    GenrePickTile(
                        pick = pick,
                        selected = isSelected(pick),
                        modifier = itemModifier,
                        onToggle = onToggle
                    )
                } else {
                    PosterPickTile(
                        pick = pick,
                        selected = isSelected(pick),
                        modifier = itemModifier,
                        onToggle = onToggle
                    )
                }
            }
        }
    }
}

/** Poster (or people headshot) tile with a caption; OK toggles the pick. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PosterPickTile(
    pick: TastePick,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (TastePick) -> Unit
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.xl)
    val bg = NuvioTheme.colors.BackgroundCard
    Card(
        onClick = { onToggle(pick) },
        modifier = modifier,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(shape)
                        .background(bg),
                    contentAlignment = Alignment.Center
                ) {
                    val poster = pick.posterPath
                    if (poster != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(poster)
                                .crossfade(true)
                                .build(),
                            contentDescription = pick.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = pick.name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleLarge,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = pick.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.xs)
                )
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(shape)
                        .border(
                            border = BorderStroke(3.dp, NuvioTheme.colors.Secondary),
                            shape = shape
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(NuvioTheme.spacing.xs)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(NuvioTheme.colors.Secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = NuvioTheme.colors.OnSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** Genres render as a filled name chip — the genre itself is the "art". */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenrePickTile(
    pick: TastePick,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (TastePick) -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val container = NuvioTheme.colors.BackgroundCard
    Card(
        onClick = { onToggle(pick) },
        modifier = modifier.height(96.dp),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.28f) else container),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md)
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(end = NuvioTheme.spacing.xs)
                    )
                }
                Text(
                    text = pick.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Search field shown above the Movies / Series / People grids. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TasteSearchField(
    query: String,
    tab: TastePickType,
    modifier: Modifier = Modifier,
    onQueryChanged: (String) -> Unit,
    onDpadDown: () -> Unit
) {
    val placeholder = when (tab) {
        TastePickType.MOVIE -> "Search movies…"
        TastePickType.SERIES -> "Search series…"
        TastePickType.PERSON -> "Search people…"
        TastePickType.GENRE -> ""
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                    keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN
                ) {
                    onDpadDown()
                    true
                } else {
                    false
                }
            },
        singleLine = true,
        shape = RoundedCornerShape(NuvioTheme.radii.md),
        placeholder = {
            Text(text = placeholder, color = NuvioTheme.colors.TextTertiary)
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary
            )
        },
        keyboardOptions = KeyboardOptions.Default.copy(
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false
        ),
        keyboardActions = KeyboardActions(onDone = { onDpadDown() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = NuvioTheme.colors.BackgroundCard,
            unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
            focusedIndicatorColor = NuvioTheme.colors.FocusRing,
            unfocusedIndicatorColor = NuvioTheme.colors.Border,
            focusedTextColor = NuvioTheme.colors.TextPrimary,
            unfocusedTextColor = NuvioTheme.colors.TextPrimary,
            cursorColor = NuvioTheme.colors.FocusRing
        )
    )
}

/** Text chip for the pick-type tabs, mirroring ProfileEditorTabs' visuals. */
@Composable
private fun PickerChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)

    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected && focused -> NuvioTheme.colors.FocusBackground
            selected -> NuvioTheme.colors.Secondary.copy(alpha = 0.22f)
            focused -> NuvioTheme.colors.FocusBackground
            else -> Color.White.copy(alpha = 0.06f)
        },
        animationSpec = tween(150),
        label = "pickerTabBackground"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected || focused) Color.White else NuvioTheme.colors.TextSecondary,
        animationSpec = tween(150),
        label = "pickerTabText"
    )

    Text(
        text = text,
        color = textColor,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(
                border = if (focused) {
                    NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
                } else {
                    BorderStroke(NuvioTheme.spacing.hairline, if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border)
                },
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.sm)
    )
}

/** Right-aligned "Done (N)" commit chip in the tab row. Disabled until something is picked. */
@Composable
private fun DoneChip(
    count: Int,
    enabled: Boolean,
    pushing: Boolean,
    onClick: () -> Unit
) {
    val label = when {
        pushing -> "Saving…"
        count > 0 -> "Done ($count)"
        else -> "Done"
    }
    val shape = RoundedCornerShape(20.dp)

    if (!enabled) {
        Text(
            text = label,
            color = NuvioTheme.colors.TextTertiary.copy(alpha = 0.5f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(shape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = shape
                )
                .padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.sm)
        )
        return
    }

    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColor by animateColorAsState(
        targetValue = if (focused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.Secondary,
        animationSpec = tween(150),
        label = "doneChipBackground"
    )

    Text(
        text = label,
        color = NuvioTheme.colors.OnSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(backgroundColor)
            .border(
                border = if (focused) {
                    NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
                } else {
                    BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Secondary)
                },
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = true,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.sm)
    )
}

/** Retry button shown inside the error state; doubles as the content focus entry. */
@Composable
private fun RetryChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(150),
        label = "retryChipScale"
    )
    Text(
        text = "Try again",
        color = NuvioTheme.colors.TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundCard)
            .border(
                border = if (focused) {
                    NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
                } else {
                    BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
                },
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = NuvioTheme.spacing.md)
    )
}

/** Mirror of the column-count arithmetic AvatarPickerGrid uses to wire first-row focus. */
private fun rememberColumnCount(
    maxWidth: androidx.compose.ui.unit.Dp,
    minCellWidth: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp
): Int {
    val availableWidth = maxWidth - horizontalPadding
    return max(
        1,
        floor(
            (availableWidth.value + spacing.value) /
                (minCellWidth.value + spacing.value)
        ).toInt()
    )
}

private fun tabLabel(tab: TastePickType): String = when (tab) {
    TastePickType.MOVIE -> "Movies"
    TastePickType.SERIES -> "Series"
    TastePickType.PERSON -> "People"
    TastePickType.GENRE -> "Genres"
}
