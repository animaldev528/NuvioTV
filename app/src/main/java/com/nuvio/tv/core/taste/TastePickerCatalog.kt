package com.nuvio.tv.core.taste

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbPersonSearchResult
import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.domain.model.TastePickType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal TMDB catalog access for the on-TV taste picker (Phase 3).
 *
 * This is deliberately a thin read-only rail source and NOT a general search service —
 * the app's richer TMDB layer lives in [com.nuvio.tv.core.tmdb.TmdbMetadataService] and is
 * aimed at detail/rail rendering. The picker only needs a small "popular" rail and query
 * search for the four pick types, so it talks to [TmdbApi] directly and maps results onto
 * [TastePick]s ready to push via [com.nuvio.tv.core.sync.TastePickSyncService].
 *
 * Genre picks carry the MOVIE-taxonomy TMDB genre id (see [TastePickType.GENRE]); the
 * curated-row engine maps movie genres to the TV taxonomy itself when it builds a series wall.
 */
@Singleton
class TastePickerCatalog @Inject constructor(
    private val tmdbApi: TmdbApi
) {
    private val apiKey: String = BuildConfig.TMDB_API_KEY

    suspend fun popularMovies(limit: Int = 36): List<TastePick> = withContext(Dispatchers.IO) {
        tmdbApi.popularMovies(apiKey = apiKey, language = LANGUAGE)
            .body()?.results.orEmpty()
            .take(limit)
            .mapNotNull { toMediaPick(it, TastePickType.MOVIE) }
    }

    suspend fun popularSeries(limit: Int = 36): List<TastePick> = withContext(Dispatchers.IO) {
        tmdbApi.popularTv(apiKey = apiKey, language = LANGUAGE)
            .body()?.results.orEmpty()
            .take(limit)
            .mapNotNull { toMediaPick(it, TastePickType.SERIES) }
    }

    suspend fun popularPeople(limit: Int = 36): List<TastePick> = withContext(Dispatchers.IO) {
        tmdbApi.popularPeople(apiKey = apiKey, language = LANGUAGE)
            .body()?.results.orEmpty()
            .take(limit)
            .mapNotNull(::toPersonPick)
    }

    suspend fun searchMovies(query: String, limit: Int = 24): List<TastePick> =
        withContext(Dispatchers.IO) {
            tmdbApi.searchMovies(apiKey = apiKey, query = query, language = LANGUAGE)
                .body()?.results.orEmpty()
                .take(limit)
                .mapNotNull { toMediaPick(it, TastePickType.MOVIE) }
        }

    suspend fun searchSeries(query: String, limit: Int = 24): List<TastePick> =
        withContext(Dispatchers.IO) {
            tmdbApi.searchTv(apiKey = apiKey, query = query, language = LANGUAGE)
                .body()?.results.orEmpty()
                .take(limit)
                .mapNotNull { toMediaPick(it, TastePickType.SERIES) }
        }

    suspend fun searchPeople(query: String, limit: Int = 24): List<TastePick> =
        withContext(Dispatchers.IO) {
            tmdbApi.searchPerson(apiKey = apiKey, query = query, language = LANGUAGE)
                .body()?.results.orEmpty()
                .take(limit)
                .mapNotNull(::toPersonPick)
        }

    suspend fun movieGenres(): List<TastePick> = withContext(Dispatchers.IO) {
        tmdbApi.getMovieGenres(apiKey = apiKey, language = LANGUAGE)
            .body()?.genres.orEmpty()
            .map { genre ->
                TastePick(
                    pickType = TastePickType.GENRE,
                    tmdbId = genre.id.toLong(),
                    name = genre.name,
                    posterPath = null
                )
            }
    }

    // ── mappers ──────────────────────────────────────────────────────────────

    /** Movie + series rails share the discover-result shape (`title` for films, `name` for shows). */
    private fun toMediaPick(result: TmdbDiscoverResult, type: TastePickType): TastePick? {
        val name = result.title?.takeIf { it.isNotBlank() }
            ?: result.name?.takeIf { it.isNotBlank() }
            ?: return null
        if (result.id <= 0) return null
        return TastePick(
            pickType = type,
            tmdbId = result.id.toLong(),
            name = name.trim(),
            posterPath = imageUrl(result.posterPath, size = "w342")
        )
    }

    private fun toPersonPick(result: TmdbPersonSearchResult): TastePick? {
        val name = result.name?.takeIf { it.isNotBlank() } ?: return null
        if (result.id <= 0) return null
        return TastePick(
            pickType = TastePickType.PERSON,
            tmdbId = result.id.toLong(),
            name = name.trim(),
            posterPath = imageUrl(result.profilePath, size = "w342")
        )
    }

    private fun imageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    private companion object {
        const val LANGUAGE = "en-US"
    }
}
