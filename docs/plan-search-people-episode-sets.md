# Search People rows + "Add all to library" with curated episode sets (NuvioTV)

> **Status: DESIGN FOR REVIEW.** Product decisions are locked with the product owner (below). Not yet implemented. Publishing as a draft PR for senior-dev review before any code lands.
>
> Implementation will be one or more feature branches off fork `dev`, compiled only on the build box (`192.168.68.63`, `:app:assembleFullRelease`), same flow as PRs #1/#2.

## Context

Two coupled product asks, scoped to the **NuvioTV TV app** first:

1. **Actor rows in search.** When you search on the TV, you should get **people you click to get into their stuff** (their filmography). Today search returns one poster row per installed addon catalog; people never appear because the bsf ai-search addon deliberately drops TMDB's `person` bucket. The app already has the exact "person → their stuff" experience (`CastDetailScreen`, fed client-side by direct TMDB `person/{id}` + `combined_credits`).
2. **Add search results to the library.** Long-press a poster in a *search-result row* and get a new action: **Add all results to library**. When the row holds *episodes* (bfs episode metas are `tt<show>:S:E`, series-typed, landscape), adding groups by show into **one library poster per show that holds only those episodes** — e.g. searching "Simpsons Halloween episodes" then "Simpsons Sideshow Bob episodes" yields **one Simpsons poster** whose episode list is the union of both searches. Opening that poster shows the curated episodes, each playable.

**Decisions locked with the user:** NuvioTV-first · episode-set entries **local-first** (on-device per-profile only; whole-title adds keep the existing Supabase sync untouched) · the "Add all" action appears on **search result rows only** in v1 · **no backend (bsf) change** in v1 — the people row is a client-side TMDB search.

**Build reality:** client changes compile only on the build box (`root@192.168.68.63`, `./gradlew :app:assembleFullRelease`, flavor `full`); keep the diff small, add **no new runtime deps** (reuse Retrofit `TmdbApi`, `TmdbMetadataService`, `LibraryPreferences`, `PosterOptions*`, `Screen.Stream`), and default every new field so no unrelated call site changes.

---

## Feature A — "People" strip in search (client-side TMDB `/search/person`)

A new horizontal strip above the addon-catalog rows. Person cards are headshot + name + "Actor · known-for". D-pad click → existing `CastDetailScreen`.

**Network/domain**
- `data/remote/api/TmdbApi.kt` — add `@GET("search/person") searchPerson(api_key, query, language, page)` (model on existing `search/*` calls ~:225) + DTOs `TmdbPersonSearchResponse(results)` / result (`id,name,profile_path,known_for_department,known_for`).
- `domain/model/PersonDetail.kt` (or new file) — `PersonSearchPreview(tmdbId, name, profilePhotoUrl?, knownForLabel?)`.
- `core/tmdb/TmdbMetadataService.kt` — `searchPeople(query, language, limit=12): List<PersonSearchPreview>`; key+language from the existing TMDB settings path; image via existing `buildImageUrl(_, "w342")`; `knownForLabel` = dept + first ~3 known-for titles; small in-memory cache; never throw → empty list.

**State / VM** (`ui/screens/search/`)
- `SearchUiState.kt` — add `people: List<PersonSearchPreview>` + `peopleLoading: Boolean`.
- `SearchViewModel.kt` — inject `TmdbMetadataService`; in `performSearch` (~:454) clear `people` at run start and launch a child coroutine `fetchPeople(query, generation)` that does NOT join `activeSearchJobs` (children are still cancelled by `cancelSearchRun()`); guard writes by generation; short-query branch (:471-486) resets people too.

**UI + focus**
- `SearchScreen.kt` — new `onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean) -> Unit = {_,_,_ ->}` param; render a `PeopleRowSection` as the first results item (only when `people.isNotEmpty()`); while loading with nothing yet render nothing (people failure is non-fatal).
- New `PeopleRowSection` (private in `SearchScreen.kt`): header "People" + `LazyRow` of tv-material cards; first card holds an `entryFocusRequester`.
- **Focus (riskiest seam):** today `focusResults && index == 0` (:725-730) + effects (:413-457) assume catalog row 0 is first focusable. When the people row is visible, divert entry focus to it (new small `LaunchedEffect`) and gate the catalog-row mapping with `&& !peopleVisible`. **Fallback if risky in review:** place the strip *after* the last catalog row (zero focus risk).
- `NuvioNavHost.kt` Search host (~:1100) — wire `onNavigateToCastDetail` to `Screen.CastDetail.createRoute(...)` (same as MetaDetails wiring ~:326).

---

## Feature B — "Add all results to library" + curated episode-set entries

### B0 shared helper (new)
`isEpisodeMetaId(id)` (≥3 `:`-segments, last two parse as int) + `episodeShowId(id)` (`dropLast(2).joinToString(":")` keeps prefixed parents like `mal:63375`; matches `StreamRepositoryImpl.kt:686-701` parser).

### B1 model + storage (`data/local/LibraryPreferences.kt` is the whole DataStore store)
- `SavedLibraryItem.kt` — add `data class SavedEpisode(id /* tt<show>:S:E */, season, episode, title, thumbnail?, released?)` and **nullable** `val episodes: List<SavedEpisode>? = null` (nullable so old Gson JSON loads; always read `.orEmpty()`).
- `LibraryModels.kt` — add `episodes: List<SavedEpisode> = emptyList()` to `LibraryEntry` (defaulted → all existing construction sites compile).
- `LibraryPreferences.kt` — new key `library_episode_sets` (**never routed through `LibrarySyncState`** — `LibrarySyncReducer.applySnapshot` would drop/push local-only entries to Supabase). Add `episodeSets` flow (profile-scoped like `libraryItems`), `upsertEpisodeSet` (merge by `id+type`, union of `SavedEpisode.id` sorted S/E, keep min `addedAt`, refresh show name/poster when non-blank), `removeEpisodeSet`, `observeEpisodeSet(id,type)`, and a **batch whole-title** `addAll(items)` = one `edit` over `LibrarySyncReducer.upsertLocal` + one `writeLibrarySyncState` (single local commit).

### B2 repository
- `LibraryRepository.kt` — add: `addTitlesToLibrary(items): result`, `addEpisodeSet(item)`, `removeEpisodeSet(id,type)`, `removeLocalItem(itemId,type)`, `observeEpisodeSet(id,type)`.
- `LibraryRepositoryImpl.kt` — implement; `addTitlesToLibrary` = `prefs.addAll(...)` then **one** debounced `triggerRemoteSync`; episode-set ops never sync. Rewrite the LOCAL `libraryItems` branch to `combine(libraryItems, episodeSets)` and **merge into one row list keyed `"${type}:${id}"`** — if a whole title and an episode set share the key, emit one `LibraryEntry` (whole-title's display fields, set's episode list); set-only shows emit an entry from the set. One tile per show, always.

### B3 PosterOptions action (search rows only)
- `PosterOptionsState.kt` — `rowMetas: List<MetaPreview> = emptyList()`, `addAllLabel: String? = null`.
- `PosterOptionsController.kt` — `show(item, addonBaseUrl, rowMetas = emptyList())` (:136): store row metas; set label — all non-episode → "Add all (N) to library"; all episode → "Add these N episodes to My Shows"; mixed → "Add all results to library". Clear on dismiss (:191-196). New `addAllResults()` (LOCAL mode only): non-episode metas → `addTitlesToLibrary(map { it.toLibraryEntryInput(addon) })` (reuses `toLibraryEntryInput` :555-588 incl. landscape→portrait); episode metas → **group by `episodeShowId`**, per group call `buildEpisodeSetEntry(showId, eps, addon)` which fetches the **parent show's** name/poster/backdrop/logo via `metaRepository.getMetaFromAllAddons("series", showId).first{ it !is Loading }` (fallback: show id + first episode still; `posterShape = POSTER`), then `addEpisodeSet(...)`.
- `PosterOptionsDialog.kt` — optional `addAllLabel`/`onAddAll`; a button between Details and the library toggle, shown when `sourceMode == LOCAL && rowMetas.isNotEmpty()`.
- `SearchScreen.kt:765-767` — pass `rowMetas = catalogRow.items` at the long-press site (`catalogRow` is in scope ~:745).
- `strings.xml` — label variants.

### B4 curated-episode view (library tile open + play)
- New route `Screen.CuratedEpisodes("curated_episodes/{showId}/{name}?addonBaseUrl=..&poster=..")` in `Screen.kt`.
- `LibraryScreen.kt` — branch at the click site (:450-455): entry with non-empty `episodes` → `onOpenCuratedEpisodes(item)`, else today's `onNavigateToDetail`. Long-press on a curated tile opens a **dedicated remove dialog** (PosterOptions can't offer "remove" for a set-only entry — `isInLibrary` would be false): "Remove curated episodes" (set-only entry disappears) and, when the same whole-title exists, "Remove show from library" (set stays).
- New `ui/screens/curatedepisodes/CuratedEpisodesScreen.kt` + `CuratedEpisodesViewModel.kt`: header (poster + show name + "N episodes"); `LazyVerticalGrid` of episode cards `S#E# · title` + thumbnail; VM observes `libraryRepository.observeEpisodeSet(showId,"series")` so a later "Add all" from a new search live-merges in.
- **Play path** — least-invasive, no change to `MetaDetailsScreen`: episode card click → NavHost `Screen.Stream.createRoute(videoId = episode.id, type="series", id=showId, season, episode, ...)` exactly like the MetaDetails play wiring (`NuvioNavHost.kt:342-362`); stream resolution finds the addon by id prefix. Stream sits atop the curated screen in the back stack.

### B5 (optional v1 polish)
Tile subtitle "N episodes" / "S1E1–S1E5"; non-blocking show-meta enrichment in the curated VM.

---

## File change list by phase

- **A** (lands independently): `TmdbApi.kt` · TMDB person-search DTO file · `PersonDetail.kt`+`PersonSearchPreview` · `TmdbMetadataService.kt` · `SearchUiState.kt` · `SearchViewModel.kt` · `SearchScreen.kt` · new `PeopleRow` · `NuvioNavHost.kt` · `strings.xml`
- **B1 model/storage**: `SavedLibraryItem.kt` · `LibraryModels.kt` · `LibraryPreferences.kt` · new id-helper file
- **B2 repo**: `LibraryRepository.kt` · `LibraryRepositoryImpl.kt`
- **B3 action**: `PosterOptionsState.kt` · `PosterOptionsController.kt` · `PosterOptionsDialog.kt` · `SearchScreen.kt` (long-press) · `strings.xml`
- **B4 view**: `Screen.kt` · new `CuratedEpisodesScreen.kt`/`CuratedEpisodesViewModel.kt` · `LibraryScreen.kt` · `NuvioNavHost.kt`
- **B5 polish** (optional)

## v1 edge cases — decided / deferred

- **Decided:** one tile per show even if a whole title is also added (merge display); whole-title-add *after* an episode set just becomes that tile's base; set-only removal leaves any whole title. Episode-set entries are LOCAL-only → hidden if the user later switches Library source mode to TRAKT/SIMKL (acceptable; release note).
- **Deferred:** canonicalizing every row title via `tmdbToImdb` before batch-add (only `tmdb:`-ids need it — single-item path already canonicalizes); primary tested v1 paths are all-titles and all-episodes rows (mixed rows still supported by branching).

## Verification

1. **Compile on build box** (source is a NuvioTV fork feature worktree, branch off fork `dev`): rsync with the **`--exclude='*/build/'`** rule (never `**/build`), copy `local.properties` + `nuviotv.jks` from `/opt/nuvio-tv`, `./gradlew :app:assembleFullRelease` → BUILD SUCCESSFUL; rsync `app-full-{armeabi-v7a,arm64-v8a}-release.apk` back to `dist/`.
2. **Sideload smoke** (.53 demo Pi arm64; firestick .59 v7a):
   - **A:** search an actor name → People strip appears above catalog rows; focus lands on it; click → CastDetail filmography opens and plays from there.
   - **B:** search "the simpsons halloween episodes" → long-press a poster in that row → "Add these N episodes…" → Library shows one Simpsons poster. Run a second Simpsons episode search ("sideshow bob"), add-all again → still one poster, episode list = union. Open poster → only those episodes render; click one → streams.
   - Whole-title search row (e.g. a movie query) → "Add all (N) to library" adds each title once; Library unchanged for whole-title behavior; Supabase sync still only carries whole titles.
3. **Regression:** existing single long-press "Add to library"/library/detail flows unchanged; search entry focus still lands sensibly (fall back to bottom placement if top feels off).
