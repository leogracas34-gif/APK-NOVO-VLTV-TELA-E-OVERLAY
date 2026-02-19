package com.vltv.play

data class SearchResultItem(
    val id: Int,
    val title: String,
    val type: String,      // "live", "movie", "series"
    val extraInfo: String? = null,
    val iconUrl: String? = null
)
