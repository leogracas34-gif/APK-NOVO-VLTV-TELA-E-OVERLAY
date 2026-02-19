package com.vltv.play

data class TmdbSearchResponse(
    val results: List<TmdbMovie>?
)

data class TmdbMovie(
    val title: String?,
    val overview: String?,
    val vote_average: Float?,
    val release_date: String?,
    val poster_path: String?
)
